package dev.carthingspotify.controller

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.LruCache
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.carthingspotify.controller.api.LrcParser
import dev.carthingspotify.controller.api.LyricsApi
import dev.carthingspotify.controller.api.SpotifyApi
import dev.carthingspotify.controller.api.SpotifyException
import dev.carthingspotify.controller.auth.SecureTokenStore
import dev.carthingspotify.controller.auth.SpotifyAuth
import dev.carthingspotify.controller.device.CrashRestarter
import dev.carthingspotify.controller.device.DeviceModeManager
import dev.carthingspotify.controller.model.*
import dev.carthingspotify.controller.ui.*
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity(), SurfaceActions {
    private lateinit var store: SecureTokenStore
    private lateinit var auth: SpotifyAuth
    private lateinit var api: SpotifyApi
    private val lyricsApi = LyricsApi()
    private lateinit var surface: CarThingView
    private lateinit var deviceMode: DeviceModeManager
    private val io = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())
    
    private val imageCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val pendingItemImages = mutableSetOf<String>()
    private val itemImageRetryAfter = mutableMapOf<String, Long>()

    private val polling = AtomicBoolean(false)
    private var stopped = false
    private var lastTrackUri = ""
    private var lastArtworkUrl = ""
    private var toastGeneration = 0
    private var externalSettingsOpen = false
    private var pendingSeek: Long? = null
    private var pendingVolume: Int? = null
    private var lastInteraction = System.currentTimeMillis()
    private var wifiLock: WifiManager.WifiLock? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var lastDeviceResolveAt = 0L
    private var sessionStartMs: Long = 0L
    private var sessionsIncremented = false
    private var collectionReturnScreen = SurfaceScreen.LIBRARY

    private val poll = object : Runnable {
        override fun run() {
            if (!stopped) {
                refreshPlayback()
                updateSessionStats()
            }

            val elapsed = System.currentTimeMillis() - lastInteraction
            val isBlackout = ::surface.isInitialized && surface.blackout

            val delay = when {
                isBlackout -> 60_000L
                elapsed > 5 * 60_000L -> 15_000L // Idle
                ::surface.isInitialized && surface.playback.isPlaying -> 3_000L
                else -> 8_000L
            }
            main.postDelayed(this, delay)
        }
    }

    private fun updateSessionStats() {
        if (!::surface.isInitialized || surface.connection != ConnectionState.Online) return
        
        if (sessionStartMs == 0L) {
            sessionStartMs = System.currentTimeMillis()
        }
        
        if (!sessionsIncremented) {
            store.totalSessions++
            sessionsIncremented = true
        }
        
        val durationSec = (System.currentTimeMillis() - sessionStartMs) / 1000L
        val min = durationSec / 60
        val hr = min / 60
        
        val durationText = if (hr > 0) "${hr}h ${min % 60}m" else "${min}m"
        surface.sessionInfo = "Session: $durationText • Total: ${store.totalSessions}"
    }

    private val inactivityCheck = object : Runnable {
        override fun run() {
            if (::store.isInitialized && ::surface.isInitialized) {
                val elapsed = System.currentTimeMillis() - lastInteraction

                // Dimming Logic
                if (elapsed >= store.dimDelayMinutes * 60_000L && !surface.blackout) {
                    if (surface.screen != SurfaceScreen.NOW_PLAYING) surface.screen = SurfaceScreen.NOW_PLAYING
                    setWindowBrightness(store.dimPercent)
                }

                // Blackout Logic
                val timeout = store.screenTimeoutSeconds
                if (timeout > 0 && elapsed >= timeout * 1000L && !surface.blackout) {
                    surface.blackout = true
                    setWindowBrightness(1) // Minimum
                    toggleSystemPowerSaver(true)
                }
            }
            main.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureFullscreenWindow()
        CrashRestarter.install(applicationContext)
        store = SecureTokenStore(this)
        auth = SpotifyAuth(applicationContext, store)
        api = SpotifyApi(auth)
        deviceMode = DeviceModeManager(this)
        surface = CarThingView(this, this).also {
            it.clockEnabled = store.clockEnabled
            setContentView(it)
        }
        setWindowBrightness(store.brightnessPercent)
        enterImmersive()
        configurePowerBehavior()
        acquireWifiLock()
        if (deviceMode.isDeviceOwner) main.postDelayed({ deviceMode.applyDedicatedMode() }, 500L)
        if (store.load() == null) {
            surface.connection = ConnectionState.LoginRequired
            surface.toastMessage = "Hold the clock for setup, then connect Spotify"
        } else {
            surface.connection = ConnectionState.Connecting
        }
        main.post(poll)
        main.post(inactivityCheck)
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        enterImmersive()
        if (externalSettingsOpen) {
            externalSettingsOpen = false
            main.postDelayed({ if (deviceMode.isDeviceOwner) deviceMode.applyDedicatedMode() }, 800L)
        }
    }

    override fun onPause() {
        super.onPause()
        stopped = true
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        wifiLock?.let { if (it.isHeld) it.release() }
        powerReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        io.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Back is deliberately disabled in dedicated mode")
    override fun onBackPressed() {
        if (!deviceMode.isDeviceOwner && surface.screen != SurfaceScreen.NOW_PLAYING) {
            surface.screen = SurfaceScreen.NOW_PLAYING
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    private fun refreshPlayback() {
        if (!polling.compareAndSet(false, true)) return
        if (store.load() == null) {
            surface.connection = ConnectionState.LoginRequired
            polling.set(false)
            return
        }
        io.execute {
            try {
                var playback = api.playback() ?: PlaybackInfo()
                if (store.targetDeviceName.isNotBlank() && System.currentTimeMillis() - lastDeviceResolveAt > 30_000L) {
                    lastDeviceResolveAt = System.currentTimeMillis()
                    val match = PlaybackRules.preferredDevice(api.devices(), store.targetDeviceName)
                    if (match != null) {
                        store.targetDeviceId = match.id
                        if (playback.deviceId != match.id) {
                            api.transfer(match.id, false)
                            playback = playback.copy(deviceId = match.id, deviceName = match.name, volume = match.volume)
                        }
                    }
                }
                val newTrack = playback.track.uri != lastTrackUri
                val saved = if (newTrack && playback.track.uri.isNotBlank()) api.isSaved(playback.track.uri) else surface.liked
                main.post {
                    surface.playback = playback
                    surface.connection = ConnectionState.Online
                    if (newTrack) {
                        lastTrackUri = playback.track.uri
                        surface.liked = saved
                        loadArtwork(playback.track.imageUrl)
                        if (store.lyricsEnabled) {
                            fetchLyrics(playback.track)
                        } else {
                            surface.isInstrumental = false
                            surface.lyricsPlain = null
                            surface.lyricsLines = emptyList()
                        }
                    }
                }
            } catch (e: Exception) {
                main.post { handleError(e, quiet = true) }
            } finally {
                polling.set(false)
            }
        }
    }

    private fun loadArtwork(url: String) {
        if (url == lastArtworkUrl) return
        lastArtworkUrl = url
        if (url.isBlank()) {
            surface.artwork = null
            surface.accentColor = Color.rgb(30, 215, 96)
            return
        }
        
        val cached = imageCache.get(url)
        if (cached != null) {
            val color = artworkColor(cached)
            surface.artwork = cached
            surface.accentColor = color
            return
        }

        io.execute {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                val stream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(stream)
                connection.disconnect()

                if (bitmap != null && lastArtworkUrl == url) {
                    imageCache.put(url, bitmap)
                    val color = artworkColor(bitmap)
                    main.post { surface.artwork = bitmap; surface.accentColor = color }
                }
            } catch (_: Exception) { }
        }
    }

    private fun fetchImage(url: String, callback: (Bitmap?) -> Unit) {
        if (url.isBlank()) { callback(null); return }
        val cached = imageCache.get(url)
        if (cached != null) { callback(cached); return }

        io.execute {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                connection.disconnect()
                if (bitmap != null) {
                    imageCache.put(url, bitmap)
                    main.post { callback(bitmap) }
                } else {
                    main.post { callback(null) }
                }
            } catch (_: Exception) {
                main.post { callback(null) }
            }
        }
    }


    private fun artworkColor(bitmap: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var red = 0L; var green = 0L; var blue = 0L; var count = 0L
        for (x in 0 until 8) for (y in 0 until 8) {
            val color = sample.getPixel(x, y)
            val brightness = Color.red(color) + Color.green(color) + Color.blue(color)
            if (brightness in 100..690) {
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color); count++
            }
        }
        sample.recycle()
        if (count == 0L) return Color.rgb(30, 215, 96)
        val r = (red / count).toInt(); val g = (green / count).toInt(); val b = (blue / count).toInt()
        val max = maxOf(r, g, b).coerceAtLeast(1)
        val boost = (210f / max).coerceIn(1f, 2f)
        return Color.rgb((r * boost).toInt().coerceAtMost(255), (g * boost).toInt().coerceAtMost(255), (b * boost).toInt().coerceAtMost(255))
    }

    override fun onPlayPause() {
        val playing = surface.playback.isPlaying
        surface.playback = surface.playback.copy(isPlaying = !playing, fetchedAt = System.currentTimeMillis())
        command(if (playing) "Paused" else "Playing") { if (playing) api.pause(targetId()) else api.play(targetId()) }
    }

    override fun onPrevious() = command("Previous track") { api.previous(targetId()) }
    override fun onNext() = command("Next track") { api.next(targetId()) }

    override fun onShuffle() {
        val enabled = !surface.playback.shuffle
        surface.playback = surface.playback.copy(shuffle = enabled)
        command(if (enabled) "Shuffle on" else "Shuffle off") { api.shuffle(enabled, targetId()) }
    }

    override fun onRepeat() {
        val mode = PlaybackRules.nextRepeat(surface.playback.repeat)
        surface.playback = surface.playback.copy(repeat = mode)
        command("Repeat $mode") { api.repeat(mode, targetId()) }
    }

    override fun onLike() {
        val uri = surface.playback.track.uri
        if (uri.isBlank()) return
        val save = !surface.liked
        surface.liked = save
        command(if (save) "Saved to your library" else "Removed from your library") { api.setSaved(uri, save) }
    }

    override fun onSeek(positionMs: Long) {
        pendingSeek = positionMs
        surface.playback = surface.playback.copy(progressMs = positionMs, fetchedAt = System.currentTimeMillis())
        main.removeCallbacks(sendSeek)
        main.postDelayed(sendSeek, 300L)
    }

    private val sendSeek = Runnable {
        val value = pendingSeek ?: return@Runnable
        pendingSeek = null
        command("Seek") { api.seek(value, targetId()) }
    }

    override fun onVolume(percent: Int) {
        pendingVolume = percent
        surface.playback = surface.playback.copy(volume = percent)
        main.removeCallbacks(sendVolume)
        main.postDelayed(sendVolume, 250L)
    }

    private val sendVolume = Runnable {
        val value = pendingVolume ?: return@Runnable
        pendingVolume = null
        command("Volume $value%") { api.volume(value, targetId()) }
    }

    override fun onScreen(screen: SurfaceScreen) {
        surface.screen = screen
        when (screen) {
            SurfaceScreen.LIBRARY -> loadLibrary()
            SurfaceScreen.COLLECTION -> Unit
            SurfaceScreen.QUEUE -> loadQueue()
            SurfaceScreen.DEVICES -> loadDevices()
            SurfaceScreen.SEARCH -> Unit
            SurfaceScreen.NOW_PLAYING -> Unit
            SurfaceScreen.LYRICS -> Unit
            SurfaceScreen.ADMIN_AUTH -> Unit
            SurfaceScreen.ADMIN_SETUP -> Unit
        }
    }

    override fun onLyricsRequested() {
        if (!store.lyricsEnabled) {
            showNotification("Enable the optional lyrics service in the administrator menu", NotificationType.ERROR, 4500L)
            return
        }
        if (surface.screen == SurfaceScreen.LYRICS) {
            surface.screen = SurfaceScreen.NOW_PLAYING
        } else {
            surface.screen = SurfaceScreen.LYRICS
        }
    }

    private fun fetchLyrics(track: TrackInfo) {
        if (track.uri.isBlank()) return
        io.execute {
            val data = lyricsApi.fetchLyrics(track.artist, track.name, track.album, track.durationMs)
            main.post {
                if (store.lyricsEnabled) {
                    surface.isInstrumental = data?.isInstrumental == true
                    surface.lyricsPlain = data?.plainLyrics
                    surface.lyricsLines = data?.syncedLyrics?.let { LrcParser.parse(it) } ?: emptyList()
                }
            }
        }
    }

    override fun onSearchTap() {
        val field = EditText(this).apply {
            setText(surface.searchQuery)
            hint = "Song, artist, album or playlist"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Search Spotify")
            .setView(field)
            .setPositiveButton("Search") { _, _ ->
                val query = field.text.toString().trim()
                surface.searchQuery = query
                surface.screen = SurfaceScreen.SEARCH
                loadSearch(query)
            }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    override fun onMediaItem(item: MediaItem) {
        when {
            item.type == MediaType.PLAYLIST || item.type == MediaType.ALBUM -> browseContents(item)
            surface.screen == SurfaceScreen.COLLECTION -> playCollectionItem(item)
            surface.screen == SurfaceScreen.QUEUE -> playQueueItem(item)
            else -> {
                command("Playing ${item.name}") { api.playItem(item, targetId()) }
                surface.screen = SurfaceScreen.NOW_PLAYING
            }
        }
    }

    override fun onMediaItemMenu(item: MediaItem) {
        val browsable = item.type == MediaType.PLAYLIST || item.type == MediaType.ALBUM
        val options = when {
            browsable -> arrayOf("Open", "Play now")
            item.type == MediaType.TRACK || item.type == MediaType.EPISODE -> arrayOf("Play now", "Add to queue")
            else -> arrayOf("Play now")
        }
        AlertDialog.Builder(this).setTitle(item.name).setItems(options) { _, which ->
            if (browsable && which == 0) browseContents(item)
            else if (browsable) playContainer(item, returnToNowPlaying = true)
            else if (which == 0) onMediaItem(item)
            else command("Added to queue") { api.addToQueue(item.uri, targetId()) }
        }.show()
    }

    override fun onArtworkNeeded(item: MediaItem) {
        if (item.imageUrl.isBlank() || surface.itemArtworks.containsKey(item.uri)) return
        if ((itemImageRetryAfter[item.uri] ?: 0L) > System.currentTimeMillis()) return
        if (!pendingItemImages.add(item.uri)) return
        fetchImage(item.imageUrl) { bitmap ->
            pendingItemImages.remove(item.uri)
            if (bitmap == null) {
                itemImageRetryAfter[item.uri] = System.currentTimeMillis() + 30_000L
                return@fetchImage
            }
            itemImageRetryAfter.remove(item.uri)
            val current = LinkedHashMap(surface.itemArtworks)
            current[item.uri] = bitmap
            while (current.size > 80) current.remove(current.keys.first())
            surface.itemArtworks = current
        }
    }

    private fun browseContents(item: MediaItem) {
        collectionReturnScreen = surface.screen.takeUnless { it == SurfaceScreen.COLLECTION } ?: SurfaceScreen.LIBRARY
        backgroundLoad("Loading ${item.name}…", {
            val id = item.uri.substringAfterLast(':')
            val contents = if (item.type == MediaType.ALBUM) api.albumItems(id) else api.playlistItems(id)
            CollectionView(item, contents)
        }) {
            surface.collection = it
            surface.screen = SurfaceScreen.COLLECTION
            if (it.items.isEmpty()) showNotification("No playable tracks found", NotificationType.ERROR)
        }
    }

    private fun playContainer(item: MediaItem, returnToNowPlaying: Boolean) {
        command("Playing ${item.name}") { api.playItem(item, targetId()) }
        if (returnToNowPlaying) surface.screen = SurfaceScreen.NOW_PLAYING
    }

    private fun playCollectionItem(item: MediaItem) {
        val contextUri = surface.collection?.container?.uri
        command("Playing ${item.name}") {
            api.playItemInContext(item, contextUri, targetId())
        }
    }

    private fun playQueueItem(item: MediaItem) {
        val contextUri = PlaybackRules.contextForSelection(surface.playback.contextUri, item.uri)
        if (contextUri == null) {
            showNotification(
                "Open a playlist or album first; Spotify cannot jump inside this queue without replacing it",
                NotificationType.ERROR,
                5000L
            )
            return
        }
        command(
            "Playing ${item.name}",
            { api.playItemInContext(item, contextUri, targetId()) },
            { main.postDelayed({ loadQueue() }, 700L) }
        )
    }

    override fun onCollectionBack() {
        surface.screen = collectionReturnScreen
        surface.collection = null
    }

    override fun onCollectionPlay() {
        val container = surface.collection?.container ?: return
        playContainer(container, returnToNowPlaying = false)
    }

    override fun onDevice(device: SpotifyDevice) {
        store.targetDeviceId = device.id
        store.targetDeviceName = device.name
        command("Connected to ${device.name}") { api.transfer(device.id, false) }
        surface.playback = surface.playback.copy(deviceId = device.id, deviceName = device.name)
        surface.screen = SurfaceScreen.NOW_PLAYING
    }

    override fun onInteraction() {
        lastInteraction = System.currentTimeMillis()
        if (::surface.isInitialized && surface.blackout) {
            surface.blackout = false
            toggleSystemPowerSaver(false)
        }
        setWindowBrightness(store.brightnessPercent)
    }

    private fun toggleSystemPowerSaver(enable: Boolean) {
        try {
            Settings.Global.putInt(contentResolver, "low_power", if (enable) 1 else 0)
            ContentResolver.setMasterSyncAutomatically(!enable)
        } catch (_: Exception) {
            // Likely permission restriction if not granted via ADB
        }
    }

    private fun loadLibrary() = backgroundLoad(
        "Loading your music…",
        { listOf(BrowseSection("Your playlists", api.playlists()), BrowseSection("Recently played", api.recentlyPlayed())) },
        {
            surface.sections = it
        }
    )

    private fun loadSearch(query: String) = backgroundLoad("Searching…", { api.search(query) }) {
        surface.sections = it
    }
    
    private fun loadQueue() = backgroundLoad("Loading queue…", { api.queue() }) {
        surface.queueItems = it
    }
    private fun loadDevices() = backgroundLoad("Finding Spotify devices…", { api.devices() }) { found ->
        surface.devices = found
        val matching = PlaybackRules.preferredDevice(found, store.targetDeviceName)
        if (matching != null) store.targetDeviceId = matching.id
    }

    private fun <T> backgroundLoad(message: String, task: () -> T, result: (T) -> Unit) {
        showNotification(message, NotificationType.LOADING)
        io.execute {
            try {
                val value = task()
                main.post {
                    result(value)
                    showNotification("Done", NotificationType.SUCCESS, 1000L)
                }
            } catch (e: Exception) { main.post { handleError(e) } }
        }
    }

    private fun command(success: String, action: () -> Unit) {
        command(success, action, afterSuccess = null)
    }

    private fun command(success: String, action: () -> Unit, afterSuccess: (() -> Unit)?) {
        io.execute {
            try {
                action()
                main.post {
                    showNotification(success, NotificationType.SUCCESS)
                    main.postDelayed({ refreshPlayback() }, 400L)
                    afterSuccess?.invoke()
                }
            } catch (e: Exception) {
                main.post { handleError(e) }
            }
        }
    }

    private fun handleError(error: Exception, quiet: Boolean = false) {
        val message = error.message ?: "Connection failed"
        if (error is SpotifyException && error.status == 401) {
            surface.connection = ConnectionState.LoginRequired
        } else if (error is SpotifyException && error.status in 400..499) {
            // Spotify can reject a command or rate-limit the app without implying a network outage.
        } else {
            surface.connection = ConnectionState.Offline(if (hasNetwork()) message else "Wi-Fi offline")
        }
        if (!quiet || surface.playback.track.uri.isBlank()) {
            showNotification(message, NotificationType.ERROR, 5000L)
        }
    }

    private fun targetId(): String? = store.targetDeviceId.ifBlank { null }
    
    private var notificationGeneration = 0
    private fun showNotification(msg: String, type: NotificationType, duration: Long = 2500L) {
        val generation = ++notificationGeneration
        surface.notificationMsg = msg
        surface.notificationType = type
        if (type != NotificationType.LOADING) {
            main.postDelayed({
                if (generation == notificationGeneration) {
                    surface.notificationType = NotificationType.NONE
                }
            }, duration)
        }
    }

    override fun onAdminRequested() {
        if (store.adminPinHash.isBlank()) {
            surface.screen = SurfaceScreen.ADMIN_SETUP
        } else {
            surface.screen = SurfaceScreen.ADMIN_AUTH
        }
    }

    override fun onAdminConfirm(password: String) {
        if (verifyPassword(password)) {
            showAdminMenu()
            surface.screen = SurfaceScreen.NOW_PLAYING
        } else {
            showNotification("Incorrect password", NotificationType.ERROR)
        }
    }

    override fun onAdminSetup(password: String) {
        if (password.length < 4) {
            showNotification("Min 4 digits", NotificationType.ERROR)
        } else {
            store.adminPinHash = hashNewPassword(password)
            showNotification("Password set", NotificationType.SUCCESS)
            showAdminMenu()
            surface.screen = SurfaceScreen.NOW_PLAYING
        }
    }

    private fun showAdminMenu() {
        val mode = if (deviceMode.isDeviceOwner) "Dedicated mode: active" else "Dedicated mode: setup required"
        val spotify = if (store.load() == null) "Spotify account: connect" else "Spotify account: connected"
        val choices = arrayOf(
            spotify,
            "Spotify Client ID",
            "Wi-Fi settings",
            "Display brightness",
            "Preferred PC / playback device",
            "Screen dimming",
            "Automatic screen-off",
            if (store.clockEnabled) "Clock: shown" else "Clock: hidden",
            if (store.lyricsEnabled) "Lyrics service: enabled" else "Lyrics service: disabled",
            mode,
            "Exit dedicated mode",
            "About and diagnostics"
        )
        AlertDialog.Builder(this).setTitle("Car Thing administrator").setItems(choices) { _, index ->
            when (index) {
                0 -> if (store.load() == null) startSpotifyLogin() else confirmSpotifyLogout()
                1 -> editClientId()
                2 -> openExternal(Settings.ACTION_WIFI_SETTINGS)
                3 -> editBrightness()
                4 -> { surface.screen = SurfaceScreen.DEVICES; loadDevices() }
                5 -> editDimming()
                6 -> editScreenOff()
                7 -> { store.clockEnabled = !store.clockEnabled; surface.clockEnabled = store.clockEnabled; showNotification(if (store.clockEnabled) "Clock shown" else "Clock hidden", NotificationType.SUCCESS) }
                8 -> confirmLyricsToggle()
                9 -> if (deviceMode.isDeviceOwner) { deviceMode.applyDedicatedMode(); showNotification("Kiosk active", NotificationType.SUCCESS) } else showKioskInstructions()
                10 -> confirmExitKiosk()
                11 -> showDiagnostics()
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun confirmLyricsToggle() {
        if (store.lyricsEnabled) {
            store.lyricsEnabled = false
            surface.isInstrumental = false
            surface.lyricsPlain = null
            surface.lyricsLines = emptyList()
            if (surface.screen == SurfaceScreen.LYRICS) surface.screen = SurfaceScreen.NOW_PLAYING
            showNotification("Lyrics service disabled", NotificationType.SUCCESS)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Enable optional lyrics?")
            .setMessage(
                "Lyrics are retrieved from LRCLIB, an independent third-party service. " +
                    "The current track, artist, album, and duration are sent to lrclib.net over HTTPS. " +
                    "No Spotify token, Client ID, device identifier, or account name is sent."
            )
            .setPositiveButton("Enable") { _, _ ->
                store.lyricsEnabled = true
                showNotification("Lyrics service enabled", NotificationType.SUCCESS)
                if (surface.playback.track.uri.isNotBlank()) fetchLyrics(surface.playback.track)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSpotifyLogin() {
        if (store.clientId.isBlank()) { editClientId(connectAfter = true); return }
        deviceMode.pauseLockTask()
        externalSettingsOpen = true
        surface.connection = ConnectionState.Connecting
        auth.begin { result ->
            result.fold(
                onSuccess = { surface.connection = ConnectionState.Connecting; showNotification("Connected", NotificationType.SUCCESS); refreshPlayback() },
                onFailure = { handleError(it as? Exception ?: Exception(it.message)) }
            )
        }
    }

    private fun editClientId(connectAfter: Boolean = false) {
        val input = EditText(this).apply { setText(store.clientId); hint = "32-character Client ID"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Spotify Client ID").setMessage("Copy the Client ID from developer.spotify.com. Never enter the Client Secret.")
            .setView(input).setPositiveButton("Save") { _, _ ->
                store.clientId = input.text.toString()
                if (connectAfter) startSpotifyLogin() else showNotification("ID saved", NotificationType.SUCCESS)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmSpotifyLogout() {
        AlertDialog.Builder(this).setTitle("Disconnect Spotify?").setMessage("The encrypted access and refresh tokens will be deleted from this tablet.")
            .setPositiveButton("Disconnect") { _, _ -> store.clearTokens(); surface.connection = ConnectionState.LoginRequired; showNotification("Disconnected", NotificationType.SUCCESS) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun editBrightness() {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 8) }
        val label = TextView(this).apply { text = getString(R.string.percent_format, store.brightnessPercent); textSize = 18f }
        val slider = SeekBar(this).apply { max = 95; progress = store.brightnessPercent - 5 }
        wrapper.addView(label); wrapper.addView(slider)
        slider.setOnSeekBarChangeListener(simpleSeek { value -> val percent = value + 5; label.text = getString(R.string.percent_format, percent); setWindowBrightness(percent) })
        AlertDialog.Builder(this).setTitle("Display brightness").setView(wrapper).setPositiveButton("Save") { _, _ -> store.brightnessPercent = slider.progress + 5 }
            .setNegativeButton("Cancel") { _, _ -> setWindowBrightness(store.brightnessPercent) }.show()
    }

    private fun editDimming() {
        val input = EditText(this).apply { setText(getString(R.string.integer_format, store.dimDelayMinutes)); inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Screen dimming").setMessage("Minutes of inactivity before returning to Now Playing and dimming. The screen stays awake while charging.")
            .setView(input).setPositiveButton("Save") { _, _ ->
                store.dimDelayMinutes = input.text.toString().toIntOrNull() ?: 10
                lastInteraction = System.currentTimeMillis()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun editScreenOff() {
        val options = arrayOf("Disabled", "30 seconds", "1 minute", "2 minutes", "5 minutes", "10 minutes")
        val values = intArrayOf(0, 30, 60, 120, 300, 600)
        val current = values.indexOf(store.screenTimeoutSeconds).coerceAtLeast(0)

        AlertDialog.Builder(this).setTitle("Automatic screen-off")
            .setSingleChoiceItems(options, current) { d, i ->
                store.screenTimeoutSeconds = values[i]
                lastInteraction = System.currentTimeMillis()
                d.dismiss()
                showNotification("Timeout updated", NotificationType.SUCCESS)
            }.show()
    }

    private fun showKioskInstructions() {
        AlertDialog.Builder(this).setTitle("Device Owner not configured").setMessage("Factory-reset the tablet, install this APK before adding any accounts, then run scripts\\configure-device-owner.ps1 from Windows. The script verifies the exact model and confirms ownership.")
            .setPositiveButton("OK", null).show()
    }

    private fun confirmExitKiosk() {
        AlertDialog.Builder(this).setTitle("Restore normal tablet mode?").setMessage("This stops lock-task mode, restores the system bars and home-app choice, and removes Device Owner authority. Re-enabling Device Owner later requires another factory reset.")
            .setPositiveButton("Restore") { _, _ ->
                deviceMode.leaveDedicatedModePermanently()
                showNotification("Dedicated mode removed", NotificationType.SUCCESS)
                main.postDelayed({
                    try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }, 500L)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showDiagnostics() {
        val owner = if (deviceMode.isDeviceOwner) "yes" else "no"
        val lock = if (deviceMode.isLockTaskPermitted) "yes" else "no"
        val signed = if (store.load() != null) "yes" else "no"
        AlertDialog.Builder(this).setTitle("Car Thing Controller 1.0.0")
            .setMessage("Device Owner: $owner\nLock task allowed: $lock\nSpotify connected: $signed\nPreferred device: ${store.targetDeviceName.ifBlank { "automatic" }}\n\nHold the clock for 2.5 seconds to reopen this menu.")
            .setPositiveButton("Close", null).show()
    }

    private fun confirmExitNormalApp() = Unit

    private fun openExternal(action: String) {
        deviceMode.pauseLockTask()
        externalSettingsOpen = true
        try { startActivity(Intent(action)) } catch (e: Exception) { handleError(e) }
    }

    private fun hashNewPassword(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val encodedSalt = Base64.getEncoder().encodeToString(salt)
        return "$encodedSalt:${passwordDigest(salt, password)}"
    }

    private fun verifyPassword(password: String): Boolean {
        val parts = store.adminPinHash.split(':', limit = 2)
        if (parts.size != 2) return false
        return MessageDigest.isEqual(parts[1].toByteArray(), passwordDigest(Base64.getDecoder().decode(parts[0]), password).toByteArray())
    }

    private fun passwordDigest(salt: ByteArray, password: String): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return try {
            Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
        } finally { spec.clearPassword() }
    }

    private fun setWindowBrightness(percent: Int) {
        window.attributes = window.attributes.apply { screenBrightness = percent.coerceIn(1, 100) / 100f }
    }

    private fun configurePowerBehavior() {
        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

                if (level != -1 && scale != -1) {
                    val percent = (level * 100 / scale.toFloat()).toInt()
                    surface.batteryPercent = percent
                }

                val isCharging = plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                surface.isCharging = isCharging

                if (isCharging) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }.also { registerReceiver(it, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }

        screenReceiver = object : BroadcastReceiver() {
            @Suppress("DEPRECATION")
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    // Immediately wake the screen back up so the app stays in control
                    val pm = getSystemService(PowerManager::class.java)
                    val wakeLock = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                        "CarThing:ScreenOffOverride"
                    )
                    wakeLock.acquire(3000L) // hold for 3s to ensure screen turns on
                    main.postDelayed({ wakeLock.release() }, 3000L)

                    // Now enter blackout mode — screen is on but shows pure black
                    if (::surface.isInitialized) {
                        surface.blackout = true
                        setWindowBrightness(1)
                        toggleSystemPowerSaver(true)
                    }
                }
            }
        }.also { registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (deviceMode.isDeviceOwner && !wifi.isWifiEnabled) {
            try { wifi.isWifiEnabled = true } catch (_: Exception) { }
        }
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CarThing:Controller").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun hasNetwork(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        configureFullscreenWindow()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    @Suppress("DEPRECATION")
    private fun configureFullscreenWindow() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun showToast(message: String, duration: Long = 2_800L) {
        val generation = ++toastGeneration
        surface.toastMessage = message
        main.postDelayed({ if (generation == toastGeneration) surface.toastMessage = "" }, duration)
    }

    private fun clearToastSoon() = Unit

    private fun simpleSeek(update: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) update(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }
}
