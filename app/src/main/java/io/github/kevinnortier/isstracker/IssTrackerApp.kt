package io.github.kevinnortier.isstracker

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.kevinnortier.isstracker.data.CelesTrakDownloader
import io.github.kevinnortier.isstracker.data.TleRefreshWorker
import io.github.kevinnortier.isstracker.data.TleRepository
import io.github.kevinnortier.isstracker.data.settings.SettingsRepository
import io.github.kevinnortier.isstracker.location.DeviceLocationProvider
import io.github.kevinnortier.isstracker.location.LocationRepository
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
}

class IssTrackerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        TleRefreshWorker.schedule(this)
    }
}
