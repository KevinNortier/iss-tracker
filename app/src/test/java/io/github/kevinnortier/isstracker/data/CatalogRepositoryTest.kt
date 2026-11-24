package io.github.kevinnortier.isstracker.data

import io.github.kevinnortier.isstracker.orbit.TestUtil
import io.github.kevinnortier.isstracker.orbit.TleParser
import io.github.kevinnortier.isstracker.ui.satellites.toCatalogItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CatalogRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** A TLE text block for a made-up satellite with the given NORAD ID, name and mean motion (rev/day). */
    private fun tle(id: Int, name: String, meanMotion: Double = 15.5): String {
        val l1 = TestUtil.withChecksum("1 ${id.toString().padStart(5, '0')}U 98067A   26325.59122794  .00010184  00000+0  18913-3 0  9990")
        val mm = String.format(java.util.Locale.ROOT, "%11.8f", meanMotion)
        val l2 = TestUtil.withChecksum("2 ${id.toString().padStart(5, '0')}  51.6319 241.1476 0007381  50.6945 309.4697 ${mm}596150")
        return "$name\n$l1\n$l2\n"
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
    }

    private inner class FakeCelesTrak : CatalogDownloader, TleDownloader {
        val groups = mutableMapOf<String, String>()
        val names = mutableMapOf<String, String>()
        val singles = mutableMapOf<Int, String>()
        var offline = false
        var groupCalls = 0

        override suspend fun group(group: String): String {
            groupCalls++
            if (offline) throw IOException("offline")
            return groups[group] ?: throw NotFoundException()
        }

        override suspend fun searchName(text: String): String {
            if (offline) throw IOException("offline")
            return names[text.lowercase()] ?: throw NotFoundException()
        }

        override suspend fun download(noradId: Int): String {
            if (offline) throw IOException("offline")
            return singles[noradId] ?: throw NotFoundException()
        }
    }

    private lateinit var fake: FakeCelesTrak
    private lateinit var clock: MutableClock
    private lateinit var repo: CatalogRepository

    @Before
    fun setUp() {
        fake = FakeCelesTrak()
        clock = MutableClock(Instant.parse("2026-11-22T12:00:00Z"))
        repo = CatalogRepository(File(tempFolder.root, "cache"), fake, fake, clock)
        fake.groups["stations"] = tle(25544, "ISS (ZARYA)") + tle(48274, "CSS (TIANHE)")
        fake.groups["visual"] = tle(20580, "HST")
        fake.groups["amateur"] = tle(27607, "SO-50") + tle(40069, "HIGH ONE", meanMotion = 2.0)
    }

    @Test
    fun `low orbit means more than 6_4 revolutions per day`() {
        assertTrue(TleParser.parseAll(tle(1, "A", 15.5)).single().isLowOrbit())
        assertTrue(TleParser.parseAll(tle(1, "A", 6.5)).single().isLowOrbit())   // ~221 min
        assertFalse(TleParser.parseAll(tle(1, "A", 6.3)).single().isLowOrbit())  // ~229 min
        assertFalse(TleParser.parseAll(tle(1, "A", 1.0027)).single().isLowOrbit()) // geostationary
    }

    @Test
    fun `result rows list low orbits first, sorted by name, without duplicates`() {
        val tles = TleParser.parseAll(tle(3, "zeta") + tle(2, "HIGH", 2.0) + tle(1, "Alpha") + tle(1, "Alpha"))
        val items = toCatalogItems(tles)
        assertEquals(listOf("Alpha", "zeta", "HIGH"), items.map { it.name })
        assertEquals(listOf(true, true, false), items.map { it.lowOrbit })
    }

    @Test
    fun `group lists are cached for a day`() = runBlocking {
        assertEquals(listOf(25544, 48274), repo.group(CatalogGroup.STATIONS).map { it.noradId })
        clock.now = clock.now.plus(Duration.ofHours(23))
        repo.group(CatalogGroup.STATIONS)
        assertEquals(1, fake.groupCalls)
        clock.now = clock.now.plus(Duration.ofHours(2))
        repo.group(CatalogGroup.STATIONS)
        assertEquals(2, fake.groupCalls)
    }

    @Test
    fun `an old cached group list is used when offline`() = runBlocking {
        repo.group(CatalogGroup.STATIONS)
        clock.now = clock.now.plus(Duration.ofDays(3))
        fake.offline = true
        assertEquals(2, repo.group(CatalogGroup.STATIONS).size)
    }

    @Test
    fun `no cache and offline fails`() {
        fake.offline = true
        assertThrows(IOException::class.java) { runBlocking { repo.group(CatalogGroup.AMATEUR) } }
    }

    @Test
    fun `search finds by name and returns empty when nothing matches`() = runBlocking {
        fake.names["tiangong"] = tle(48274, "CSS (TIANHE)")
        assertEquals(listOf(48274), repo.search("  Tiangong ").map { it.noradId })
        assertEquals(emptyList<Int>(), repo.search("nothing like this").map { it.noradId })
    }

    @Test
    fun `lookup by NORAD ID`() = runBlocking {
        fake.singles[27607] = tle(27607, "SO-50")
        assertEquals("SO-50", repo.lookup(27607)?.name)
        assertNull(repo.lookup(99999))
    }

    @Test
    fun `brightness comes from the stations and brightest groups`() = runBlocking {
        assertTrue(repo.isBright(48274)) // station
        assertTrue(repo.isBright(20580)) // brightest
        assertFalse(repo.isBright(27607)) // amateur radio
    }

    @Test
    fun `brightness check is false when the groups can't be loaded`() = runBlocking {
        fake.offline = true
        assertFalse(repo.isBright(25544))
    }

    @Test
    fun `cached bright IDs come from the cache only, however old`() = runBlocking {
        fake.offline = true
        assertEquals(emptySet<Int>(), repo.cachedBrightIds())
        assertEquals(0, fake.groupCalls)

        fake.offline = false
        repo.group(CatalogGroup.STATIONS)
        repo.group(CatalogGroup.AMATEUR) // not a bright group
        clock.now = clock.now.plus(Duration.ofDays(30))
        val calls = fake.groupCalls
        assertEquals(setOf(25544, 48274), repo.cachedBrightIds())
        assertEquals(calls, fake.groupCalls)
    }

    @Test
    fun `downloading a bright group list announces its IDs`() = runBlocking {
        val announced = mutableListOf<Set<Int>>()
        val job = launch(Dispatchers.Unconfined) { repo.brightListSaved.collect { announced += it } }
        repo.group(CatalogGroup.STATIONS)
        repo.group(CatalogGroup.STATIONS) // from the cache: no new announcement
        repo.group(CatalogGroup.AMATEUR) // not a bright group
        repo.group(CatalogGroup.BRIGHTEST)
        job.cancel()
        assertEquals(listOf(setOf(25544, 48274), setOf(20580)), announced)
    }

    @Test
    fun `a TLE stored from the catalog is used without downloading`() = runBlocking {
        val tleRepo = TleRepository(File(tempFolder.root, "tle"), { throw IOException("should not download") }, clock)
        val so50 = TleParser.parseAll(tle(27607, "SO-50")).single()
        tleRepo.put(so50)
        val result = tleRepo.getTle(27607)
        assertEquals(false, result.downloaded)
        assertEquals("SO-50", result.cached.tle.name)
        tleRepo.delete(27607)
        assertNull(tleRepo.getCached(27607))
    }
}
