package io.github.kevinnortier.isstracker.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.kevinnortier.isstracker.Satellite
import io.github.kevinnortier.isstracker.notify.PassKey
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import io.github.kevinnortier.isstracker.sensors.HeadingSource
import io.github.kevinnortier.isstracker.sensors.PointingAxis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var file: File

    private fun newRepository(): SettingsRepository = SettingsRepository(
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }),
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        file = File(tempFolder.root, "settings.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults to current location with nothing stored`() = runBlocking {
        val settings = newRepository().settings.first()
        assertEquals(AppSettings(), settings)
        assertEquals(LocationMode.CURRENT, settings.locationMode)
        assertNull(settings.resolveObserver())
    }

    @Test
    fun `stores and reads all location settings`() = runBlocking {
        val repo = newRepository()
        val saved = SavedLocation("Greenwich", GeodeticLocation(51.4769, -0.0005, 46.0))
        val fix = LocationFix(GeodeticLocation(-33.8568, 151.2153, 5.0), 12.5f, Instant.ofEpochMilli(1_795_000_000_000), "gps")

        repo.setSavedLocation(saved)
        repo.setLastFix(fix)
        repo.setLocationMode(LocationMode.SAVED)

        assertEquals(AppSettings(LocationMode.SAVED, saved, fix), repo.settings.first())
    }

    @Test
    fun `fix without accuracy is stored without accuracy`() = runBlocking {
        val repo = newRepository()
        repo.setLastFix(LocationFix(GeodeticLocation(1.0, 2.0), 10f, Instant.ofEpochMilli(1000), "gps"))
        repo.setLastFix(LocationFix(GeodeticLocation(1.0, 2.0), null, Instant.ofEpochMilli(2000), "network"))
        val fix = repo.settings.first().lastFix!!
        assertNull(fix.accuracyMeters)
        assertEquals("network", fix.provider)
    }

    @Test
    fun `minimum elevation defaults to zero and can be changed`() = runBlocking {
        val repo = newRepository()
        assertEquals(0.0, repo.settings.first().minElevationDeg, 0.0)
        repo.setMinElevation(10.0)
        assertEquals(10.0, repo.settings.first().minElevationDeg, 0.0)
    }

    @Test
    fun `minimum highest point defaults to any and can be changed`() = runBlocking {
        val repo = newRepository()
        assertEquals(0.0, repo.settings.first().minApexElevationDeg, 0.0)
        repo.setMinApexElevation(30.0)
        assertEquals(30.0, repo.settings.first().minApexElevationDeg, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `minimum highest point above 90 is rejected`() {
        runBlocking { newRepository().setMinApexElevation(91.0) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `minimum elevation outside 0 to 45 is rejected`() {
        runBlocking { newRepository().setMinElevation(60.0) }
    }

    @Test
    fun `reminder settings default to rise and 15 minutes`() = runBlocking {
        val repo = newRepository()
        val s = repo.settings.first()
        assertEquals(NotifyTrigger.RISE, s.notifyTrigger)
        assertEquals(15, s.notifyLeadMinutes)
        repo.setNotifyTrigger(NotifyTrigger.APEX)
        repo.setNotifyLeadMinutes(30)
        val changed = repo.settings.first()
        assertEquals(NotifyTrigger.APEX, changed.notifyTrigger)
        assertEquals(30, changed.notifyLeadMinutes)
    }

    @Test
    fun `selected passes can be added, removed and replaced`() = runBlocking {
        val repo = newRepository()
        val a = PassKey(25544, 1_000L)
        val b = PassKey(25544, 2_000L)
        assertEquals(emptySet<PassKey>(), repo.selectedPasses.first())
        repo.setPassSelected(a, true)
        repo.setPassSelected(b, true)
        assertEquals(setOf(a, b), repo.selectedPasses.first())
        repo.setPassSelected(a, false)
        assertEquals(setOf(b), repo.selectedPasses.first())
        repo.setSelectedPasses(setOf(a))
        assertEquals(setOf(a), repo.selectedPasses.first())
    }

    @Test
    fun `pointing axis defaults to the back and can be changed`() = runBlocking {
        val repo = newRepository()
        assertEquals(PointingAxis.BACK, repo.settings.first().pointingAxis)
        repo.setPointingAxis(PointingAxis.TOP)
        assertEquals(PointingAxis.TOP, repo.settings.first().pointingAxis)
    }

    @Test
    fun `tracks the ISS until the user changes the list`() = runBlocking {
        assertEquals(listOf(Satellite.ISS), newRepository().trackedSatellites.first())
    }

    @Test
    fun `removing every satellite gives an empty list, not the default`() = runBlocking {
        val repo = newRepository()
        repo.removeTrackedSatellite(Satellite.ISS.noradId)
        assertEquals(emptyList<Satellite>(), repo.trackedSatellites.first())
    }

    @Test
    fun `adding appends, re-adding updates in place, and the list is capped`() = runBlocking {
        val repo = newRepository()
        val so50 = Satellite(27607, "SO-50")
        assertEquals(true, repo.addTrackedSatellite(so50))
        assertEquals(listOf(Satellite.ISS, so50), repo.trackedSatellites.first())
        // Same NORAD ID again: updated, not duplicated.
        repo.addTrackedSatellite(Satellite(25544, "ISS (ZARYA)", bright = true))
        assertEquals(listOf(Satellite(25544, "ISS (ZARYA)", bright = true), so50), repo.trackedSatellites.first())
        // Fill up to the limit; the next one is refused.
        for (id in 1..(Satellite.MAX_TRACKED - 2)) assertEquals(true, repo.addTrackedSatellite(Satellite(id, "S$id")))
        assertEquals(false, repo.addTrackedSatellite(Satellite(99999, "one too many")))
        assertEquals(Satellite.MAX_TRACKED, repo.trackedSatellites.first().size)
    }

    @Test
    fun `markBright only turns brightness on, for tracked satellites in the list`() = runBlocking {
        val repo = newRepository()
        val css = Satellite(48274, "CSS") // stored as faint, e.g. added while offline
        val so50 = Satellite(27607, "SO-50")
        val hst = Satellite(20580, "HST", bright = true) // added from "Brightest", missing from the list below
        listOf(css, so50, hst).forEach { repo.addTrackedSatellite(it) }

        repo.markBright(setOf(48274, 99999))

        assertEquals(
            listOf(Satellite.ISS, css.copy(bright = true), so50, hst),
            repo.trackedSatellites.first(),
        )
    }

    @Test
    fun `removing a satellite also forgets its reminders`() = runBlocking {
        val repo = newRepository()
        repo.addTrackedSatellite(Satellite(27607, "SO-50"))
        val issPass = PassKey(25544, 1_000L)
        val soPass = PassKey(27607, 2_000L)
        repo.setSelectedPasses(setOf(issPass, soPass))
        repo.removeTrackedSatellite(27607)
        assertEquals(listOf(Satellite.ISS), repo.trackedSatellites.first())
        assertEquals(setOf(issPass), repo.selectedPasses.first())
    }

    @Test
    fun `heading source defaults to compass and can be changed`() = runBlocking {
        val repo = newRepository()
        assertEquals(HeadingSource.COMPASS, repo.settings.first().headingSource)
        repo.setHeadingSource(HeadingSource.GYRO)
        assertEquals(HeadingSource.GYRO, repo.settings.first().headingSource)
    }

    @Test
    fun `theme defaults to system and can be changed`() = runBlocking {
        val repo = newRepository()
        assertEquals(ThemeMode.SYSTEM, repo.settings.first().themeMode)
        repo.setThemeMode(ThemeMode.NIGHT)
        assertEquals(ThemeMode.NIGHT, repo.settings.first().themeMode)
    }

    @Test
    fun `scheduled reminders are remembered`() = runBlocking {
        val repo = newRepository()
        val keys = setOf(PassKey(25544, 5_000L))
        repo.setScheduledReminders(keys)
        assertEquals(keys, repo.scheduledReminders())
    }

    @Test
    fun `settings survive a restart`() {
        runBlocking { newRepository().setLocationMode(LocationMode.SAVED) }
        scope.cancel() // close the first DataStore before opening the file again
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = runBlocking { newRepository().settings.first() }
        assertEquals(LocationMode.SAVED, settings.locationMode)
    }

    @Test
    fun `pass filter defaults to all and is stored`() = runBlocking {
        val repo = newRepository()
        assertEquals(emptySet<Int>(), repo.passFilter.first())
        repo.setPassFilter(setOf(25544, 48274))
        assertEquals(setOf(25544, 48274), repo.passFilter.first())
        repo.setPassFilter(emptySet())
        assertEquals(emptySet<Int>(), repo.passFilter.first())
    }
}
