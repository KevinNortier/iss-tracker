package io.github.kevinnortier.isstracker.data

import io.github.kevinnortier.isstracker.orbit.TestUtil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

class TleRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val issTle = """
        ISS (ZARYA)
        1 25544U 98067A   26325.59122794  .00010184  00000+0  18913-3 0  9995
        2 25544  51.6319 241.1476 0007381  50.6945 309.4697 15.49534382596158
    """.trimIndent()

    private val newerIssTle = listOf(
        "ISS (ZARYA)",
        TestUtil.withChecksum("1 25544U 98067A   26326.20000000  .00010184  00000+0  18913-3 0  9990"),
        "2 25544  51.6319 241.1476 0007381  50.6945 309.4697 15.49534382596158",
    ).joinToString("\n")

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        fun advance(d: Duration) { now = now.plus(d) }
    }

    private class FakeDownloader(var response: () -> String) : TleDownloader {
        var calls = 0
        override suspend fun download(noradId: Int): String {
            calls++
            return response()
        }
    }

    private lateinit var cacheDir: File
    private lateinit var clock: MutableClock
    private lateinit var downloader: FakeDownloader
    private lateinit var repo: TleRepository

    @Before
    fun setUp() {
        cacheDir = File(tempFolder.root, "tle")
        clock = MutableClock(Instant.parse("2026-11-21T16:00:00Z"))
        downloader = FakeDownloader { issTle }
        repo = TleRepository(cacheDir, downloader, clock)
    }

    @Test
    fun `downloads and caches when there is no cache`() = runBlocking {
        val result = repo.getTle(25544)
        assertEquals(1, downloader.calls)
        assertEquals(25544, result.cached.tle.noradId)
        assertEquals("ISS (ZARYA)", result.cached.tle.name)
        assertEquals(clock.now, result.cached.fetchedAt)
        assertNull(result.refreshError)
        assertNotNull(repo.getCached(25544))
    }

    @Test
    fun `uses cache while it is fresh`() = runBlocking {
        repo.getTle(25544)
        clock.advance(Duration.ofHours(23))
        repo.getTle(25544)
        assertEquals(1, downloader.calls)
    }

    @Test
    fun `downloads again when cache is older than a day`() = runBlocking {
        repo.getTle(25544)
        clock.advance(Duration.ofHours(25))
        downloader.response = { newerIssTle }
        val result = repo.getTle(25544)
        assertEquals(2, downloader.calls)
        assertEquals(Instant.parse("2026-11-22T04:48:00Z"), result.cached.tle.epoch)
    }

    @Test
    fun `cache survives a new repository instance`() = runBlocking {
        repo.getTle(25544)
        val other = TleRepository(cacheDir, downloader, clock)
        val result = other.getTle(25544)
        assertEquals(1, downloader.calls)
        assertEquals(clock.now, result.cached.fetchedAt)
    }

    @Test
    fun `forced refresh is limited to once every two hours`() = runBlocking {
        repo.getTle(25544)
        clock.advance(Duration.ofMinutes(90))
        repo.getTle(25544, forceRefresh = true)
        assertEquals(1, downloader.calls)
        clock.advance(Duration.ofMinutes(31))
        repo.getTle(25544, forceRefresh = true)
        assertEquals(2, downloader.calls)
    }

    @Test
    fun `failed download falls back to cache and reports the error`() = runBlocking {
        repo.getTle(25544)
        clock.advance(Duration.ofDays(2))
        downloader.response = { throw IOException("offline") }
        val result = repo.getTle(25544)
        assertEquals(Instant.parse("2026-11-21T16:00:00Z"), result.cached.fetchedAt)
        assertEquals("offline", result.refreshError?.message)
    }

    @Test
    fun `failed download without cache throws`() {
        downloader.response = { throw IOException("offline") }
        assertThrows(NoTleAvailableException::class.java) { runBlocking { repo.getTle(25544) } }
    }

    @Test
    fun `garbage response keeps the old cache`() = runBlocking {
        repo.getTle(25544)
        clock.advance(Duration.ofDays(2))
        downloader.response = { "No GP data found" }
        val result = repo.getTle(25544)
        assertNotNull(result.refreshError)
        assertEquals(25544, result.cached.tle.noradId)
        // The cache file is untouched.
        assertEquals(Instant.parse("2026-11-21T16:00:00Z"), repo.getCached(25544)?.fetchedAt)
    }

    @Test
    fun `wrong satellite in response is rejected`() {
        downloader.response = { issTle }
        assertThrows(NoTleAvailableException::class.java) { runBlocking { repo.getTle(28057) } }
    }

    @Test
    fun `corrupt cache file is treated as missing`() = runBlocking {
        cacheDir.mkdirs()
        File(cacheDir, "tle_25544.txt").writeText("this is not a TLE")
        val result = repo.getTle(25544)
        assertEquals(1, downloader.calls)
        assertEquals(25544, result.cached.tle.noradId)
    }

    @Test
    fun `reports whether it downloaded`() = runBlocking {
        assertEquals(true, repo.getTle(25544).downloaded)
        assertEquals(false, repo.getTle(25544).downloaded)
        assertEquals(false, repo.getTle(25544, forceRefresh = true).downloaded)
        clock.advance(Duration.ofHours(3))
        assertEquals(true, repo.getTle(25544, forceRefresh = true).downloaded)
    }

    @Test
    fun `fetch time is the same before and after a cache round trip`() = runBlocking {
        // Real clocks have sub-millisecond precision; the cache file stores milliseconds.
        clock.now = Instant.parse("2026-11-21T16:00:00.123456789Z")
        val fresh = repo.getTle(25544).cached
        val reread = TleRepository(cacheDir, downloader, clock).getCached(25544)
        assertEquals(fresh.fetchedAt, reread?.fetchedAt)
        assertEquals(fresh, reread)
    }

    @Test
    fun `clock moved backwards triggers a download`() = runBlocking {
        repo.getTle(25544)
        clock.advance(Duration.ofDays(-1))
        repo.getTle(25544)
        assertEquals(2, downloader.calls)
    }
}
