package io.github.kevinnortier.isstracker.location

import io.github.kevinnortier.isstracker.data.settings.LocationFix
import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LocationRepositoryTest {

    private fun fix(epochSecond: Long) =
        LocationFix(GeodeticLocation(1.0, 2.0), 10f, Instant.ofEpochSecond(epochSecond), "gps")

    @Test
    fun `stores the first fix`() {
        assertTrue(LocationRepository.shouldStore(fix(100), null))
    }

    @Test
    fun `stores a newer or equally new fix`() {
        assertTrue(LocationRepository.shouldStore(fix(200), fix(100)))
        assertTrue(LocationRepository.shouldStore(fix(100), fix(100)))
    }

    @Test
    fun `does not replace a newer fix with an older last-known one`() {
        assertFalse(LocationRepository.shouldStore(fix(100), fix(200)))
    }
}
