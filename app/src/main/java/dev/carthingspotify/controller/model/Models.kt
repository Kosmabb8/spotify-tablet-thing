package dev.carthingspotify.controller.model

data class TrackInfo(
    val name: String = "Nothing playing",
    val artist: String = "Open Spotify on your PC",
    val album: String = "",
    val uri: String = "",
    val imageUrl: String = "",
    val durationMs: Long = 0L
)

data class PlaybackInfo(
    val track: TrackInfo = TrackInfo(),
    val isPlaying: Boolean = false,
    val progressMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: String = "off",
    val contextUri: String = "",
    val deviceName: String = "No active device",
    val deviceId: String = "",
    val volume: Int = 0,
    val fetchedAt: Long = System.currentTimeMillis()
)

data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val active: Boolean,
    val volume: Int
)

enum class MediaType { TRACK, ALBUM, ARTIST, PLAYLIST, EPISODE }

data class MediaItem(
    val name: String,
    val subtitle: String,
    val uri: String,
    val imageUrl: String = "",
    val type: MediaType = MediaType.TRACK
)

data class BrowseSection(val title: String, val items: List<MediaItem>)

data class CollectionView(
    val container: MediaItem,
    val items: List<MediaItem>
)

sealed class ConnectionState {
    data object Connecting : ConnectionState()
    data object Online : ConnectionState()
    data class Offline(val message: String) : ConnectionState()
    data object LoginRequired : ConnectionState()
}

data class ApiResponse(val code: Int, val body: String, val retryAfterSeconds: Long = 0)

object PlaybackRules {
    fun nextRepeat(current: String): String = when (current) {
        "off" -> "context"
        "context" -> "track"
        else -> "off"
    }

    fun preferredDevice(devices: List<SpotifyDevice>, rememberedName: String): SpotifyDevice? =
        devices.firstOrNull { it.name == rememberedName }

    fun contextForSelection(contextUri: String?, itemUri: String): String? =
        contextUri
            ?.trim()
            ?.takeIf {
                itemUri.startsWith("spotify:track:") &&
                    (it.startsWith("spotify:playlist:") || it.startsWith("spotify:album:"))
            }
}
