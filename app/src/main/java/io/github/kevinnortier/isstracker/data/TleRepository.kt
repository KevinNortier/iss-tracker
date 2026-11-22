package io.github.kevinnortier.isstracker.data

import io.github.kevinnortier.isstracker.orbit.Tle
import io.github.kevinnortier.isstracker.orbit.TleParseException
import io.github.kevinnortier.isstracker.orbit.TleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A TLE together with when we downloaded it. */
data class CachedTle(val tle: Tle, val fetchedAt: Instant)

/**
 * Result of loading a TLE: the best TLE we have, whether it was just downloaded,
 * and the error if a download attempt failed.
 */
data class TleLoadResult(
    val cached: CachedTle,
    val downloaded: Boolean,
    val refreshError: Exception?,
)

class NoTleAvailableException(cause: Exception) :
    IOException("No orbit data available. Connect to the internet and try again.", cause)

/**
 * Provides the TLE for one satellite, downloading it when the cached copy is too old.
 *
 * The cache is a small text file:
 * ```
 * # fetched=<epoch millis>
 * <name line>
 * <line 1>
 * <line 2>
 * ```
 */
class TleRepository(
    private val cacheDir: File,
    private val downloader: TleDownloader,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        /** Download a new TLE when the cached one is older than this. */
        val REFRESH_AFTER: Duration = Duration.ofHours(24)

        /** Never download more often than this, even when the user asks (be nice to CelesTrak). */
        val MIN_REFRESH_INTERVAL: Duration = Duration.ofHours(2)

        private const val HEADER_PREFIX = "# fetched="
    }

    private val mutex = Mutex()

    /** The most recently loaded TLE per satellite, so all screens see a refresh made by any of them. */
    private val latest = MutableStateFlow<Map<Int, CachedTle>>(emptyMap())

    /** Emits the latest loaded TLE for [noradId] (null until [getTle] or [getCached] has loaded one). */
    fun latest(noradId: Int): Flow<CachedTle?> = latest.map { it[noradId] }.distinctUntilChanged()

    private fun publish(noradId: Int, cached: CachedTle) {
        latest.update { it + (noradId to cached) }
    }

    /**
     * Returns the TLE for [noradId], downloading a new one if the cache is missing or older than
     * [REFRESH_AFTER]. With [forceRefresh] it downloads if the cache is older than [MIN_REFRESH_INTERVAL].
     *
     * If the download fails but a cached TLE exists, the cached one is returned together with the error.
     * Throws [NoTleAvailableException] if there is neither a cached TLE nor a successful download.
     */
    suspend fun getTle(noradId: Int, forceRefresh: Boolean = false): TleLoadResult =
        load(noradId, forceRefresh).also { publish(noradId, it.cached) }

    private suspend fun load(noradId: Int, forceRefresh: Boolean): TleLoadResult = mutex.withLock {
        val cached = readCache(noradId)
        // Millisecond precision, the same as what the cache file stores.
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val age = cached?.let { Duration.between(it.fetchedAt, now) }
        val shouldDownload = when {
            cached == null || age == null -> true
            age.isNegative -> true // the clock was changed; don't trust the cache time
            forceRefresh -> age >= MIN_REFRESH_INTERVAL
            else -> age >= REFRESH_AFTER
        }
        if (!shouldDownload) return@withLock TleLoadResult(cached!!, downloaded = false, refreshError = null)

        try {
            val fresh = CachedTle(downloadAndParse(noradId), now)
            writeCache(noradId, fresh)
            TleLoadResult(fresh, downloaded = true, refreshError = null)
        } catch (e: IOException) {
            cached?.let { TleLoadResult(it, downloaded = false, refreshError = e) } ?: throw NoTleAvailableException(e)
        } catch (e: TleParseException) {
            cached?.let { TleLoadResult(it, downloaded = false, refreshError = e) } ?: throw NoTleAvailableException(e)
        }
    }

    /** Returns the cached TLE without downloading, or null if there is none. */
    suspend fun getCached(noradId: Int): CachedTle? =
        mutex.withLock { readCache(noradId) }?.also { publish(noradId, it) }

    /**
     * Stores a TLE that was just downloaded elsewhere (e.g. while browsing the catalog), so adding
     * a satellite doesn't download it again.
     */
    suspend fun put(tle: Tle) = mutex.withLock {
        val cached = CachedTle(tle, clock.instant().truncatedTo(ChronoUnit.MILLIS))
        writeCache(tle.noradId, cached)
        publish(tle.noradId, cached)
    }

    /** Forgets the cached TLE of a satellite that is no longer tracked. */
    suspend fun delete(noradId: Int) = mutex.withLock {
        withContext(Dispatchers.IO) { cacheFile(noradId).delete() }
        latest.update { it - noradId }
    }

    private suspend fun downloadAndParse(noradId: Int): Tle {
        val text = downloader.download(noradId)
        val tle = try {
            TleParser.parseAll(text).firstOrNull()
        } catch (e: TleParseException) {
            throw TleParseException("Unexpected response from CelesTrak: ${e.message}")
        } ?: throw TleParseException("Unexpected response from CelesTrak: no TLE found")
        if (tle.noradId != noradId) {
            throw TleParseException("Expected satellite $noradId but got ${tle.noradId}")
        }
        return tle
    }

    private fun cacheFile(noradId: Int) = File(cacheDir, "tle_$noradId.txt")

    private suspend fun readCache(noradId: Int): CachedTle? = withContext(Dispatchers.IO) {
        val file = cacheFile(noradId)
        if (!file.exists()) return@withContext null
        try {
            val lines = file.readLines()
            val fetchedAt = lines.firstOrNull()
                ?.takeIf { it.startsWith(HEADER_PREFIX) }
                ?.removePrefix(HEADER_PREFIX)?.trim()?.toLongOrNull()
                ?.let(Instant::ofEpochMilli)
                ?: return@withContext null
            val tle = TleParser.parseAll(lines.drop(1).joinToString("\n")).firstOrNull()
                ?: return@withContext null
            if (tle.noradId != noradId) null else CachedTle(tle, fetchedAt)
        } catch (e: IOException) {
            null
        } catch (e: TleParseException) {
            null // corrupt cache: treat as missing
        }
    }

    private suspend fun writeCache(noradId: Int, cached: CachedTle) = withContext(Dispatchers.IO) {
        cacheDir.mkdirs()
        val text = buildString {
            append(HEADER_PREFIX).append(cached.fetchedAt.toEpochMilli()).append('\n')
            cached.tle.name?.let { append(it).append('\n') }
            append(cached.tle.line1).append('\n')
            append(cached.tle.line2).append('\n')
        }
        // Write to a temp file and rename, so a crash never leaves a half-written cache.
        val target = cacheFile(noradId)
        val temp = File(cacheDir, "${target.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) throw IOException("Could not write TLE cache")
        }
    }
}
