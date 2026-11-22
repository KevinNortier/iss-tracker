package io.github.kevinnortier.isstracker

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackedSatellitesCodecTest {

    private val tiangong = Satellite(48274, "CSS (TIANHE)", bright = true)
    private val so50 = Satellite(27607, "SAUDISAT 1C (SO-50)")

    @Test
    fun `round trip keeps order, names and brightness`() {
        val list = listOf(tiangong, Satellite.ISS, so50)
        assertEquals(list, TrackedSatellitesCodec.decode(TrackedSatellitesCodec.encode(list)))
    }

    @Test
    fun `empty list round trips to empty`() {
        assertEquals("", TrackedSatellitesCodec.encode(emptyList()))
        assertEquals(emptyList<Satellite>(), TrackedSatellitesCodec.decode(""))
    }

    @Test
    fun `tabs and line breaks in names can't break the format`() {
        val odd = Satellite(1, "A\tB\nC")
        assertEquals(listOf(Satellite(1, "A B C")), TrackedSatellitesCodec.decode(TrackedSatellitesCodec.encode(listOf(odd))))
    }

    @Test
    fun `broken lines are skipped and duplicates keep the first`() {
        val text = "25544\t1\tISS\ngarbage\n-5\t0\tbad id\n25544\t0\tISS again\n27607\t0\t"
        assertEquals(
            listOf(Satellite(25544, "ISS", bright = true), Satellite(27607, "#27607")),
            TrackedSatellitesCodec.decode(text),
        )
    }
}
