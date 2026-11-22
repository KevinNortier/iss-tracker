package io.github.kevinnortier.isstracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Downloads the raw TLE text for a satellite. */
fun interface TleDownloader {
    /**
     * Returns the TLE text (name line + 2 element lines). Throws [NotFoundException] when there is no
     * such satellite (or no current data for it), [IOException] on other failures.
     */
    suspend fun download(noradId: Int): String
}

/** Downloads lists of TLEs for browsing and searching the satellite catalog. */
interface CatalogDownloader {
    /** All TLEs in a CelesTrak group (e.g. "stations", "amateur"). */
    suspend fun group(group: String): String

    /** TLEs of satellites whose name contains [text]. Throws [NotFoundException] when nothing matches. */
    suspend fun searchName(text: String): String
}

/** CelesTrak has no data for the request ("No GP data found", HTTP 404). */
class NotFoundException(message: String = "No data found") : IOException(message)

/**
 * Downloads TLEs from CelesTrak's GP API.
 * CelesTrak updates the data every few hours and asks clients not to download more often than needed;
 * [TleRepository] and [CatalogRepository] cache the results.
 */
class CelesTrakDownloader(
    private val baseUrl: String = "https://celestrak.org/NORAD/elements/gp.php",
    private val userAgent: String = "ISS-Tracker-Android",
) : TleDownloader, CatalogDownloader {

    override suspend fun download(noradId: Int): String = fetch("CATNR=$noradId")

    override suspend fun group(group: String): String = fetch("GROUP=${encode(group)}")

    override suspend fun searchName(text: String): String = fetch("NAME=${encode(text)}")

    private suspend fun fetch(query: String): String = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl?$query&FORMAT=TLE")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "text/plain")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> throw NotFoundException()
                else -> throw IOException("CelesTrak returned HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    // The (String, Charset) overload needs API 33; minSdk is 31.
    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")
}
