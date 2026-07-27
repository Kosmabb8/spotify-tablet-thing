package dev.carthingspotify.controller.api

import dev.carthingspotify.controller.auth.SpotifyAuth
import dev.carthingspotify.controller.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

class SpotifyApi(private val auth: SpotifyAuth) {
    private val blockedUntil = AtomicLong(0)
    private val browsePageSize = 50
    private val maxBrowseItems = 1_000

    fun playback(): PlaybackInfo? {
        val response = request("GET", "/me/player")
        if (response.code == 204) return PlaybackInfo()
        checkOk(response)
        val root = JSONObject(response.body)
        val item = root.optJSONObject("item")
        val device = root.optJSONObject("device")
        val context = root.optJSONObject("context")
        return PlaybackInfo(
            track = parseTrack(item),
            isPlaying = root.optBoolean("is_playing"),
            progressMs = root.optLong("progress_ms"),
            shuffle = root.optBoolean("shuffle_state"),
            repeat = root.optString("repeat_state", "off"),
            contextUri = context?.optString("uri").orEmpty(),
            deviceName = device?.optString("name", "No active device") ?: "No active device",
            deviceId = device?.optString("id").orEmpty(),
            volume = device?.optInt("volume_percent", 0) ?: 0
        )
    }

    fun devices(): List<SpotifyDevice> {
        val response = request("GET", "/me/player/devices")
        checkOk(response)
        val array = JSONObject(response.body).optJSONArray("devices") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                SpotifyDevice(
                    id = it.optString("id"),
                    name = it.optString("name", "Unknown device"),
                    type = it.optString("type", "Unknown"),
                    active = it.optBoolean("is_active"),
                    volume = it.optInt("volume_percent", 0)
                )
            }
        }.filter { it.id.isNotBlank() }
    }

    fun transfer(deviceId: String, play: Boolean = false) = command(
        "PUT", "/me/player", body = JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", play).toString()
    )

    fun play(deviceId: String? = null) = command("PUT", "/me/player/play", deviceId)
    fun pause(deviceId: String? = null) = command("PUT", "/me/player/pause", deviceId)
    fun next(deviceId: String? = null) = command("POST", "/me/player/next", deviceId)
    fun previous(deviceId: String? = null) = command("POST", "/me/player/previous", deviceId)
    fun seek(positionMs: Long, deviceId: String? = null) = command(
        "PUT", "/me/player/seek", deviceId, mapOf("position_ms" to positionMs.coerceAtLeast(0).toString())
    )
    fun shuffle(enabled: Boolean, deviceId: String? = null) = command(
        "PUT", "/me/player/shuffle", deviceId, mapOf("state" to enabled.toString())
    )
    fun repeat(mode: String, deviceId: String? = null) = command(
        "PUT", "/me/player/repeat", deviceId, mapOf("state" to mode)
    )
    fun volume(percent: Int, deviceId: String? = null) = command(
        "PUT", "/me/player/volume", deviceId, mapOf("volume_percent" to percent.coerceIn(0, 100).toString())
    )

    fun playItem(item: MediaItem, deviceId: String? = null) {
        val body = if (item.type == MediaType.TRACK || item.type == MediaType.EPISODE) {
            JSONObject().put("uris", JSONArray().put(item.uri))
        } else {
            JSONObject().put("context_uri", item.uri)
        }
        command("PUT", "/me/player/play", deviceId, body = body.toString())
    }

    fun playItemInContext(item: MediaItem, contextUri: String?, deviceId: String? = null) {
        val preservedContext = PlaybackRules.contextForSelection(contextUri, item.uri)
            ?: throw SpotifyException(
                409,
                "This item is not part of an active playlist or album. Open its playlist before selecting it."
            )
        val body = JSONObject()
            .put("context_uri", preservedContext)
            .put("offset", JSONObject().put("uri", item.uri))
        command("PUT", "/me/player/play", deviceId, body = body.toString())
    }

    fun addToQueue(uri: String, deviceId: String? = null) = command(
        "POST", "/me/player/queue", deviceId, mapOf("uri" to uri)
    )

    fun queue(): List<MediaItem> {
        val response = request("GET", "/me/player/queue")
        checkOk(response)
        val array = JSONObject(response.body).optJSONArray("queue") ?: JSONArray()
        return (0 until array.length()).mapNotNull { parseMedia(array.optJSONObject(it), MediaType.TRACK) }
    }

    fun recentlyPlayed(): List<MediaItem> {
        val response = request("GET", "/me/player/recently-played", mapOf("limit" to "10"))
        checkOk(response)
        val array = JSONObject(response.body).optJSONArray("items") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            parseMedia(array.optJSONObject(index)?.optJSONObject("track"), MediaType.TRACK)
        }
    }

    fun playlists(): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        var offset = 0
        while (result.size < maxBrowseItems) {
            val response = request(
                "GET",
                "/me/playlists",
                mapOf("limit" to browsePageSize.toString(), "offset" to offset.toString())
            )
            checkOk(response)
            val root = JSONObject(response.body)
            val array = root.optJSONArray("items") ?: break
            result += parseItems(array, MediaType.PLAYLIST)
            offset += array.length()
            if (!hasNextPage(root, array)) break
        }
        return result.take(maxBrowseItems)
    }

    fun playlistItems(playlistId: String): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        var offset = 0
        while (result.size < maxBrowseItems) {
            val response = request(
                "GET",
                "/playlists/$playlistId/items",
                mapOf("limit" to browsePageSize.toString(), "offset" to offset.toString())
            )
            checkOk(response)
            val root = JSONObject(response.body)
            val array = root.optJSONArray("items") ?: break
            result += (0 until array.length()).mapNotNull { index ->
                val wrapper = array.optJSONObject(index)
                parseMedia(wrapper?.optJSONObject("item") ?: wrapper?.optJSONObject("track"), MediaType.TRACK)
            }
            offset += array.length()
            if (!hasNextPage(root, array)) break
        }
        return result.take(maxBrowseItems)
    }

    fun albumItems(albumId: String): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        var offset = 0
        while (result.size < maxBrowseItems) {
            val response = request(
                "GET",
                "/albums/$albumId/tracks",
                mapOf("limit" to browsePageSize.toString(), "offset" to offset.toString())
            )
            checkOk(response)
            val root = JSONObject(response.body)
            val array = root.optJSONArray("items") ?: break
            result += parseItems(array, MediaType.TRACK)
            offset += array.length()
            if (!hasNextPage(root, array)) break
        }
        return result.take(maxBrowseItems)
    }

    fun search(query: String): List<BrowseSection> {
        if (query.isBlank()) return emptyList()
        val response = request(
            "GET", "/search", mapOf("q" to query, "type" to "track,album,artist,playlist", "limit" to "10")
        )
        checkOk(response)
        val root = JSONObject(response.body)
        return listOf(
            BrowseSection("Songs", parseItems(root.optJSONObject("tracks")?.optJSONArray("items"), MediaType.TRACK)),
            BrowseSection("Albums", parseItems(root.optJSONObject("albums")?.optJSONArray("items"), MediaType.ALBUM)),
            BrowseSection("Artists", parseItems(root.optJSONObject("artists")?.optJSONArray("items"), MediaType.ARTIST)),
            BrowseSection("Playlists", parseItems(root.optJSONObject("playlists")?.optJSONArray("items"), MediaType.PLAYLIST))
        ).filter { it.items.isNotEmpty() }
    }

    fun isSaved(uri: String): Boolean {
        if (uri.isBlank()) return false
        val response = request("GET", "/me/library/contains", mapOf("uris" to uri))
        checkOk(response)
        return JSONArray(response.body).optBoolean(0, false)
    }

    fun setSaved(uri: String, saved: Boolean) {
        command(if (saved) "PUT" else "DELETE", "/me/library", query = mapOf("uris" to uri))
    }

    private fun command(
        method: String,
        path: String,
        deviceId: String? = null,
        query: Map<String, String> = emptyMap(),
        body: String? = null
    ) {
        val params = query.toMutableMap()
        if (!deviceId.isNullOrBlank()) params["device_id"] = deviceId
        checkOk(request(method, path, params, body))
    }

    private fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: String? = null
    ): ApiResponse {
        val waitMs = blockedUntil.get() - System.currentTimeMillis()
        if (waitMs > 0) throw SpotifyException(429, "Spotify asked us to wait ${waitMs / 1000 + 1}s")
        val token = auth.validAccessToken() ?: throw SpotifyException(401, "Spotify login required")
        val queryString = query.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val fullUrl = "https://api.spotify.com/v1$path" + if (queryString.isBlank()) "" else "?$queryString"
        val connection = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) OutputStreamWriter(connection.outputStream).use { it.write(body) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val retry = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 0L
        if (code == 429) blockedUntil.set(System.currentTimeMillis() + retry.coerceAtLeast(1L) * 1000L)
        connection.disconnect()
        return ApiResponse(code, text, retry)
    }

    private fun checkOk(response: ApiResponse) {
        if (response.code in 200..299) return
        val rawMessage = try {
            val error = JSONObject(response.body).opt("error")
            when (error) {
                is JSONObject -> error.optString("message")
                else -> error?.toString().orEmpty()
            }
        } catch (_: Exception) { "" }
        val fallback = when (response.code) {
            401 -> "Spotify login expired"
            403 -> "Spotify Premium or the requested permission is required"
            404 -> "No active Spotify player was found"
            429 -> "Spotify rate limit; retry in ${response.retryAfterSeconds}s"
            else -> "Spotify request failed (${response.code})"
        }
        throw SpotifyException(response.code, rawMessage.ifBlank { fallback })
    }

    private fun parseItems(array: JSONArray?, type: MediaType): List<MediaItem> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { parseMedia(array.optJSONObject(it), type) }
    }

    private fun hasNextPage(root: JSONObject, items: JSONArray): Boolean {
        return items.length() > 0 && !root.isNull("next") && root.optString("next").isNotBlank()
    }

    private fun parseMedia(json: JSONObject?, type: MediaType): MediaItem? {
        if (json == null || json.optBoolean("is_local")) return null
        val uri = json.optString("uri")
        if (uri.isBlank()) return null
        val actualType = when (json.optString("type")) {
            "album" -> MediaType.ALBUM
            "artist" -> MediaType.ARTIST
            "playlist" -> MediaType.PLAYLIST
            "episode" -> MediaType.EPISODE
            "track" -> MediaType.TRACK
            else -> type
        }
        val subtitle = when (actualType) {
            MediaType.TRACK -> artistNames(json.optJSONArray("artists"))
            MediaType.ALBUM -> artistNames(json.optJSONArray("artists"))
            MediaType.ARTIST -> "Artist"
            MediaType.PLAYLIST -> json.optJSONObject("owner")?.optString("display_name", "Playlist") ?: "Playlist"
            MediaType.EPISODE -> json.optJSONObject("show")?.optString("name", "Episode") ?: "Episode"
        }
        val image = when (actualType) {
            MediaType.TRACK -> imageUrl(json.optJSONObject("album")?.optJSONArray("images"))
            else -> imageUrl(json.optJSONArray("images"))
        }
        return MediaItem(json.optString("name", "Untitled"), subtitle, uri, image, actualType)
    }

    private fun parseTrack(item: JSONObject?): TrackInfo {
        if (item == null) return TrackInfo()
        val album = item.optJSONObject("album")
        val show = item.optJSONObject("show")
        return TrackInfo(
            name = item.optString("name", "Nothing playing"),
            artist = artistNames(item.optJSONArray("artists")).ifBlank { show?.optString("name").orEmpty() },
            album = album?.optString("name").orEmpty(),
            uri = item.optString("uri"),
            imageUrl = imageUrl(album?.optJSONArray("images") ?: item.optJSONArray("images")),
            durationMs = item.optLong("duration_ms")
        )
    }

    private fun artistNames(array: JSONArray?): String {
        if (array == null) return ""
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }.filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun imageUrl(images: JSONArray?): String {
        if (images == null || images.length() == 0) return ""
        return images.optJSONObject(0)?.optString("url").orEmpty()
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}

class SpotifyException(val status: Int, message: String) : Exception(message)

object TimeText {
    fun elapsed(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000L
        return "%d:%02d".format(seconds / 60L, seconds % 60L)
    }

    fun remaining(progressMs: Long, durationMs: Long): String = "−" + elapsed((durationMs - progressMs).coerceAtLeast(0L))
}
