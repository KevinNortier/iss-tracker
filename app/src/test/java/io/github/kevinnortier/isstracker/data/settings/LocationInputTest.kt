package io.github.kevinnortier.isstracker.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationInputTest {

    private fun valid(lat: String, lon: String, alt: String = "") =
        LocationInput.parse(lat, lon, alt) as LocationInput.Result.Valid

    private fun errors(lat: String, lon: String, alt: String = "") =
        (LocationInput.parse(lat, lon, alt) as LocationInput.Result.Invalid).errors

    @Test
    fun `parses decimal degrees`() {
        val loc = valid("51.4769", "-0.0005", "46").location
        assertEquals(51.4769, loc.latitudeDeg, 1e-12)
        assertEquals(-0.0005, loc.longitudeDeg, 1e-12)
        assertEquals(46.0, loc.altitudeMeters, 1e-12)
    }

    @Test
    fun `accepts comma as decimal separator and surrounding spaces`() {
        val loc = valid(" 59,5 ", "18,25", "12,5").location
        assertEquals(59.5, loc.latitudeDeg, 1e-12)
        assertEquals(18.25, loc.longitudeDeg, 1e-12)
        assertEquals(12.5, loc.altitudeMeters, 1e-12)
    }

    @Test
    fun `empty altitude means sea level`() {
        assertEquals(0.0, valid("10", "20").location.altitudeMeters, 0.0)
    }

    @Test
    fun `limits are inclusive`() {
        valid("90", "180")
        valid("-90", "-180")
    }

    @Test
    fun `reports missing values`() {
        val e = errors("", " ")
        assertEquals("Required", e.latitude)
        assertEquals("Required", e.longitude)
        assertNull(e.altitude)
    }

    @Test
    fun `reports values out of range`() {
        val e = errors("90.1", "-180.5", "10000")
        assertTrue(e.latitude!!.contains("-90"))
        assertTrue(e.longitude!!.contains("-180"))
        assertTrue(e.altitude!!.contains("9000"))
    }

    @Test
    fun `reports text that is not a number`() {
        val e = errors("abc", "12.3.4", "NaN")
        assertEquals("Not a number", e.latitude)
        assertEquals("Not a number", e.longitude)
        assertEquals("Not a number", e.altitude)
    }

    @Test
    fun `formats coordinates without scientific notation`() {
        assertEquals("-0.0005", LocationInput.formatForInput(-0.0005))
        assertEquals("0.00001", LocationInput.formatForInput(0.00001))
        assertEquals("51.4769", LocationInput.formatForInput(51.4769))
        assertEquals("12", LocationInput.formatForInput(12.0))
        assertEquals("0", LocationInput.formatForInput(-0.0000001))
        assertEquals("46", LocationInput.formatForInput(46.4, maxDecimals = 0))
        assertEquals("59.12346", LocationInput.formatForInput(59.123456, maxDecimals = 5))
    }

    @Test
    fun `formatted values parse back to the same location`() {
        val text = LocationInput.formatForInput(-0.0005)
        val loc = (LocationInput.parse("51.4769", text, "") as LocationInput.Result.Valid).location
        assertEquals(-0.0005, loc.longitudeDeg, 1e-12)
    }

    @Test
    fun `only the bad field gets an error`() {
        val e = errors("45", "200")
        assertNull(e.latitude)
        assertTrue(e.longitude != null)
        assertNull(e.altitude)
    }
}
