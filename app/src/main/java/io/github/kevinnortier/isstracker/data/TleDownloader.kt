package io.github.kevinnortier.isstracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the raw TLE text for a satellite. */
fun interface TleDownloader {
    /** Returns the TLE text (name line + 2 element lines). Throws [IOException] on failure. */
    suspend fun download(noradId: Int): String
}

/**
 * Downloads TLEs from CelesTrak's GP API.
 * CelesTrak updates the data every few hours and asks clients not to download more often than needed;
 * [TleRepository] makes sure we only download about once a day.
 */
class CelesTrakDownloader(
    private val baseUrl: String = "https://celestrak.org/NORAD/elements/gp.php",
    private val userAgent: String = "ISS-Tracker-Android",
) : TleDownloader {

    override suspend fun download(noradId: Int): String = withContext(Dispatchers.IO) {
        val url = URL("$baseUrl?CATNR=$noradId&FORMAT=TLE")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", "text/plain")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("CelesTrak returned HTTP $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
