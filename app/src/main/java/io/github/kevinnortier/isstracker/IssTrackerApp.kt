package io.github.kevinnortier.isstracker

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.kevinnortier.isstracker.data.CelesTrakDownloader
import io.github.kevinnortier.isstracker.data.TleRefreshWorker
import io.github.kevinnortier.isstracker.data.TleRepository
import io.github.kevinnortier.isstracker.data.settings.SettingsRepository
import io.github.kevinnortier.isstracker.data.settings.resolveObserver
import io.github.kevinnortier.isstracker.location.DeviceLocationProvider
import io.github.kevinnortier.isstracker.location.LocationRepository
import io.github.kevinnortier.isstracker.notify.PassReminderScheduler
import io.github.kevinnortier.isstracker.notify.ReminderNotifications
import io.github.kevinnortier.isstracker.sensors.OrientationSensor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** A satellite the app can track. */
data class Satellite(val noradId: Int, val displayName: String) {
    companion object {
        val ISS = Satellite(25544, "ISS")
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Simple manual dependency container, created once per process. */
class AppContainer(app: Application) {

    val satellite: Satellite = Satellite.ISS

    val tleRepository = TleRepository(
        cacheDir = File(app.filesDir, "tle"),
        downloader = CelesTrakDownloader(userAgent = "ISS-Tracker-Android/${BuildConfig.VERSION_NAME}"),
    )

    val settingsRepository = SettingsRepository(app.settingsDataStore)

    val locationRepository = LocationRepository(settingsRepository, DeviceLocationProvider(app))

    val reminderScheduler = PassReminderScheduler(app, satellite, settingsRepository, tleRepository)

    val orientationSensor = OrientationSensor(app)
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
     * Re-arms the pass reminders whenever something that affects them changes:
     * the selection, the location, the minimum elevation, the reminder settings or the orbit data.
     */
    private fun keepRemindersUpToDate() {
        val c = container
        appScope.launch {
            c.tleRepository.getCached(c.satellite.noradId) // make the cached TLE available to latest()
            combine(
                c.settingsRepository.settings
                    .map { listOf(it.resolveObserver()?.location, it.minElevationDeg, it.notifyTrigger, it.notifyLeadMinutes) }
                    .distinctUntilChanged(),
                c.settingsRepository.selectedPasses,
                c.tleRepository.latest(c.satellite.noradId).map { it?.tle }.distinctUntilChanged(),
            ) { _, _, _ -> Unit }
                .collectLatest {
                    delay(500) // let quick successive changes settle
                    c.reminderScheduler.reschedule()
                }
        }
    }
}
