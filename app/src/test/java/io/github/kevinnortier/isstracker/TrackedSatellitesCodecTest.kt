package io.github.kevinnortier.isstracker

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackedSatellitesCodecTest {

    private val tiangong = Satellite(48274, "CSS (TIANHE)", bright = true)
    private val so50 = Satellite(27607, "SAUDISAT 1C (SO-50)")

    @Test
    fun `round trip keeps order, names, brightness and colours`() {
        val list = listOf(tiangong.copy(colorIndex = 3), Satellite.ISS, so50)
        assertEquals(list, TrackedSatellitesCodec.decode(TrackedSatellitesCodec.encode(list)))
    }

    @Test
    fun `lines saved before colours were stored are read without a colour`() {
        assertEquals(
            listOf(Satellite(48274, "CSS", bright = true), Satellite(27607, "SO-50")),
            TrackedSatellitesCodec.decode("48274\t1\tCSS\n27607\t0\tSO-50"),
        )
    }

    @Test
    fun `colours go to satellites without one, least used first, keeping existing ones`() {
        val a = Satellite(1, "A")
        val b = Satellite(2, "B", colorIndex = 0)
        val c = Satellite(3, "C")
        val broken = Satellite(4, "D", colorIndex = 42) // out of range: treated as none
        assertEquals(
            listOf(a.copy(colorIndex = 1), b, c.copy(colorIndex = 2), broken.copy(colorIndex = 3)),
            assignColors(listOf(a, b, c, broken)),
        )
    }

    @Test
    fun `after all colours are used they repeat evenly`() {
        val list = (1..Satellite.COLOR_COUNT + 3).map { Satellite(it, "S$it") }
        val colors = assignColors(list).map { it.colorIndex }
        assertEquals((0 until Satellite.COLOR_COUNT).toList() + listOf(0, 1, 2), colors)
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
