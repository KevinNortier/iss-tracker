package io.github.kevinnortier.isstracker

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.kevinnortier.isstracker.data.CatalogRepository
import io.github.kevinnortier.isstracker.data.CelesTrakDownloader
import io.github.kevinnortier.isstracker.data.TleRefreshWorker
import io.github.kevinnortier.isstracker.data.TleRepository
import io.github.kevinnortier.isstracker.data.settings.SettingsRepository
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.location.DeviceLocationProvider
import io.github.kevinnortier.isstracker.location.LocationRepository
import io.github.kevinnortier.isstracker.notify.PassReminderScheduler
import io.github.kevinnortier.isstracker.orbit.Tle
import io.github.kevinnortier.isstracker.notify.ReminderNotifications
import io.github.kevinnortier.isstracker.sensors.OrientationSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Simple manual dependency container, created once per process. */
class AppContainer(app: Application) {

    /**
     * The satellite the screens show. Still the ISS: the screens become per-satellite in later steps
     * (S3/S4). Background work (orbit data refresh, reminders) already covers all tracked satellites.
     */
    val satellite: Satellite = Satellite.ISS

    private val celesTrak = CelesTrakDownloader(userAgent = "ISS-Tracker-Android/${BuildConfig.VERSION_NAME}")

    val tleRepository = TleRepository(cacheDir = File(app.filesDir, "tle"), downloader = celesTrak)

    /** Browsing/searching CelesTrak when adding satellites (group lists cached in the cache dir). */
    val catalogRepository = CatalogRepository(app.cacheDir, downloader = celesTrak, singleDownloader = celesTrak)

    val settingsRepository = SettingsRepository(app.settingsDataStore)

    val locationRepository = LocationRepository(settingsRepository, DeviceLocationProvider(app))

    val reminderScheduler = PassReminderScheduler(app, settingsRepository, tleRepository)

    val orientationSensor = OrientationSensor(app)

    /**
     * Starts tracking the satellite in [tle] (or updates it if already tracked) and caches its orbit
     * data. Returns false if the tracked list is full.
     */
    suspend fun addSatellite(tle: Tle, bright: Boolean): Boolean {
        val satellite = Satellite(tle.noradId, tle.name ?: "#${tle.noradId}", bright)
        if (!settingsRepository.addTrackedSatellite(satellite)) return false
        tleRepository.put(tle)
        return true
    }

    /** Stops tracking a satellite: forgets its reminders and its cached orbit data. */
    suspend fun removeSatellite(noradId: Int) {
        settingsRepository.removeTrackedSatellite(noradId)
        tleRepository.delete(noradId)
    }
}

class IssTrackerApp : Application() {

    lateinit var container: AppContainer
        private set

    /** Lives as long as the process; used for work that isn't tied to a screen. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderNotifications.createChannel(this)
        TleRefreshWorker.schedule(this)
        keepRemindersUpToDate()
    }

    /**
     * Re-arms the pass reminders whenever something that affects them changes: the selection, the
     * tracked satellites, the location, the minimum elevation, the reminder settings or any orbit data.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun keepRemindersUpToDate() {
        val c = container
        // The TLEs of all tracked satellites (loading cached ones so latest() has them).
        val tles = c.settingsRepository.trackedSatellites.flatMapLatest { satellites ->
            satellites.forEach { c.tleRepository.getCached(it.noradId) }
            if (satellites.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(satellites.map { s -> c.tleRepository.latest(s.noradId).map { it?.tle } }) { it.toList() }
            }
        }.distinctUntilChanged()

        appScope.launch {
            combine(
                c.settingsRepository.settings
                    .map { listOf(it.resolveObserver()?.location, it.minElevationDeg, it.notifyTrigger, it.notifyLeadMinutes) }
                    .distinctUntilChanged(),
                c.settingsRepository.selectedPasses,
                tles,
            ) { _, _, _ -> Unit }
                .collectLatest {
                    delay(500) // let quick successive changes settle
                    c.reminderScheduler.reschedule()
                }
        }
    }
}
