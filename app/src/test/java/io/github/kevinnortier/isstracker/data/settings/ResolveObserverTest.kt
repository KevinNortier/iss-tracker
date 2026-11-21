package io.github.kevinnortier.isstracker.data.settings

import io.github.kevinnortier.isstracker.orbit.GeodeticLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ResolveObserverTest {

    private val saved = SavedLocation("Home", GeodeticLocation(10.0, 20.0))
    private val fix = LocationFix(GeodeticLocation(30.0, 40.0), 5f, Instant.EPOCH, "gps")

    @Test
    fun `current mode uses the last fix`() {
        val observer = AppSettings(LocationMode.CURRENT, saved, fix).resolveObserver()
        assertEquals(Observer.Current(fix), observer)
        assertEquals(30.0, observer!!.location.latitudeDeg, 0.0)
    }

    @Test
    fun `saved mode uses the saved location`() {
        val observer = AppSettings(LocationMode.SAVED, saved, fix).resolveObserver()
        assertEquals(Observer.Saved(saved), observer)
    }

    @Test
    fun `missing source gives no observer instead of falling back`() {
        assertNull(AppSettings(LocationMode.CURRENT, saved, null).resolveObserver())
        assertNull(AppSettings(LocationMode.SAVED, null, fix).resolveObserver())
    }
}
