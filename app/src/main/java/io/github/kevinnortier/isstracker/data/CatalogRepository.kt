package io.github.kevinnortier.isstracker.data

import io.github.kevinnortier.isstracker.orbit.Tle
import io.github.kevinnortier.isstracker.orbit.TleParseException
import io.github.kevinnortier.isstracker.orbit.TleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** CelesTrak groups offered when adding satellites. */
enum class CatalogGroup(val query: String, val title: String, val bright: Boolean) {
    STATIONS("stations", "Space stations", bright = true),
    AMATEUR("amateur", "Amateur radio", bright = false),
    WEATHER("weather", "Weather", bright = false),
    BRIGHTEST("visual", "Brightest", bright = true),
}

/**
 * True for satellites the app can calculate: SGP4 (near-earth) needs an orbital period under
 * 225 minutes, i.e. more than 6.4 revolutions per day. Higher orbits (GPS, geostationary,
 * Molniya) need the deep-space model (SDP4), which isn't implemented yet.
 */
fun Tle.isLowOrbit(): Boolean = meanMotion > MINUTES_PER_DAY / MAX_NEAR_EARTH_PERIOD_MINUTES

private const val MINUTES_PER_DAY = 1440.0
private const val MAX_NEAR_EARTH_PERIOD_MINUTES = 225.0

/**
 * Browsing and searching CelesTrak's catalog when adding satellites.
 *
 * Group lists are cached on disk for [GROUP_CACHE_TIME] so browsing doesn't hammer CelesTrak.
 * Name searches and NORAD ID lookups always ask CelesTrak (the user does them one at a time).
 */
class CatalogRepository(
    private val cacheDir: File,
    private val downloader: CatalogDownloader,
    private val singleDownloader: TleDownloader,
    private val clock: Clock = Clock.systemUTC(),
) {
    companion object {
        val GROUP_CACHE_TIME: Duration = Duration.ofHours(24)
        private const val HEADER_PREFIX = "# fetched="
    }

    private val mutex = Mutex()

    private val _brightListSaved = MutableSharedFlow<Set<Int>>(extraBufferCapacity = 8)

    /**
     * NORAD IDs of a freshly downloaded "bright" group list (space stations, brightest), emitted each
     * time one is saved. Lets tracked satellites that were wrongly stored as faint be corrected without
     * extra downloads.
     */
    val brightListSaved: SharedFlow<Set<Int>> = _brightListSaved.asSharedFlow()

    /** The satellites in [group], from the cache if it's recent enough. */
    suspend fun group(group: CatalogGroup): List<Tle> = mutex.withLock {
        val cached = readGroup(group)
        if (cached != null && Duration.between(cached.first, clock.instant()) < GROUP_CACHE_TIME) {
            return@withLock cached.second
        }
        try {
            val tles = parse(downloader.group(group.query))
            writeGroup(group, tles)
            if (group.bright) _brightListSaved.tryEmit(tles.map { it.noradId }.toSet())
            tles
        } catch (e: IOException) {
            cached?.second ?: throw e // offline: an old list is better than nothing
        }
    }

    /**
     * NORAD IDs in the cached "bright" group lists, however old. Never downloads; empty if none are cached.
     */
    suspend fun cachedBrightIds(): Set<Int> = mutex.withLock {
        CatalogGroup.entries.filter { it.bright }
            .flatMap { readGroup(it)?.second.orEmpty() }
            .map { it.noradId }
            .toSet()
    }

    /** Satellites whose name contains [text] (case-insensitive on CelesTrak's side). Empty if none match. */
    suspend fun search(text: String): List<Tle> = try {
        parse(downloader.searchName(text.trim()))
    } catch (e: NotFoundException) {
        emptyList()
    }

    /** The satellite with this NORAD ID, or null if CelesTrak doesn't know it (or has no current data). */
    suspend fun lookup(noradId: Int): Tle? = try {
        parse(singleDownloader.download(noradId)).firstOrNull { it.noradId == noradId }
    } catch (e: NotFoundException) {
        null
    }

    /**
     * True if the satellite is in a group whose members are bright enough to see (space stations,
     * brightest). Downloads those group lists if they aren't cached; false if that fails.
     */
    suspend fun isBright(noradId: Int): Boolean = CatalogGroup.entries.filter { it.bright }.any { group ->
        runCatching { group(group).any { it.noradId == noradId } }.getOrDefault(false)
    }

    private fun parse(text: String): List<Tle> = try {
        TleParser.parseAll(text)
    } catch (e: TleParseException) {
        throw IOException("Unexpected response from CelesTrak: ${e.message}", e)
    }

    private fun groupFile(group: CatalogGroup) = File(File(cacheDir, "catalog"), "group_${group.query}.txt")

    private suspend fun readGroup(group: CatalogGroup): Pair<Instant, List<Tle>>? = withContext(Dispatchers.IO) {
        val file = groupFile(group)
        if (!file.isFile) return@withContext null
        try {
            val lines = file.readLines()
            val fetched = lines.firstOrNull()?.removePrefix(HEADER_PREFIX)?.trim()?.toLongOrNull()
                ?: return@withContext null
            Instant.ofEpochMilli(fetched) to TleParser.parseAll(lines.drop(1).joinToString("\n"))
        } catch (e: IOException) {
            null
        } catch (e: TleParseException) {
            null
        }
    }

    private suspend fun writeGroup(group: CatalogGroup, tles: List<Tle>) = withContext(Dispatchers.IO) {
        val file = groupFile(group)
        file.parentFile?.mkdirs()
        val text = buildString {
            append(HEADER_PREFIX).append(clock.instant().toEpochMilli()).append('\n')
            for (tle in tles) {
                tle.name?.let { append(it).append('\n') }
                append(tle.line1).append('\n').append(tle.line2).append('\n')
            }
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }
}
