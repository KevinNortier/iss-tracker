package io.github.kevinnortier.isstracker

import android.app.Application
import io.github.kevinnortier.isstracker.data.CelesTrakDownloader
import io.github.kevinnortier.isstracker.data.TleRefreshWorker
import io.github.kevinnortier.isstracker.data.TleRepository
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import java.io.File

/** A satellite the app can track. */
data class Satellite(val noradId: Int, val displayName: String) {
    companion object {
        val ISS = Satellite(25544, "ISS")
    }
}

/** Where the observer location comes from (until real location support exists). */
data class ObserverLocation(val location: GeodeticLocation, val description: String)

/** Simple manual dependency container, created once per process. */
class AppContainer(app: Application) {

    val satellite: Satellite = Satellite.ISS

    val tleRepository = TleRepository(
        cacheDir = File(app.filesDir, "tle"),
        downloader = CelesTrakDownloader(userAgent = "ISS-Tracker-Android/${BuildConfig.VERSION_NAME}"),
    )

    /**
     * Temporary observer location: the developer test location from local.properties
     * (`iss.testLocation=lat,lon,altMetres`) in debug builds, otherwise Greenwich Observatory.
     */
    val observerLocation: ObserverLocation = parseTestLocation(BuildConfig.TEST_LOCATION)
        ?.let { ObserverLocation(it, "Test location (local.properties)") }
        ?: ObserverLocation(GeodeticLocation(51.4769, -0.0005, 46.0), "Greenwich (default)")

    private fun parseTestLocation(text: String): GeodeticLocation? {
        val parts = text.split(',').map { it.trim().toDoubleOrNull() }
        if (parts.size !in 2..3 || parts.any { it == null }) return null
        return runCatching {
            GeodeticLocation(parts[0]!!, parts[1]!!, parts.getOrNull(2) ?: 0.0)
        }.getOrNull()
    }
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
