package io.github.kevinnortier.isstracker.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
}
