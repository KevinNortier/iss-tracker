package io.github.kevinnortier.isstracker.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
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
    fun `settings survive a restart`() {
        runBlocking { newRepository().setLocationMode(LocationMode.SAVED) }
        scope.cancel() // close the first DataStore before opening the file again
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val settings = runBlocking { newRepository().settings.first() }
        assertEquals(LocationMode.SAVED, settings.locationMode)
    }
}
