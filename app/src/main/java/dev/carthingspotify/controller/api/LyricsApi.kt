package dev.carthingspotify.controller.api

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class LyricsData(
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val isInstrumental: Boolean
)

class LyricsApi {
    fun fetchLyrics(artist: String, track: String, album: String, durationMs: Long): LyricsData? {
        return try {
            val query = "track_name=${encode(track)}&artist_name=${encode(artist)}&album_name=${encode(album)}&duration=${durationMs / 1000}"
            val url = URL("https://lrclib.net/api/get?$query")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "CarThingController/1.0.0")
            }
            if (connection.responseCode == 200) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                LyricsData(
                    plainLyrics = json.optString("plainLyrics").takeIf { it.isNotBlank() },
                    syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() },
                    isInstrumental = json.optBoolean("instrumental")
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
}
