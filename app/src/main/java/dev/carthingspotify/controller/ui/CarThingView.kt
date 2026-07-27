package dev.carthingspotify.controller.ui

import android.animation.ValueAnimator
import android.content.Context
import android.annotation.SuppressLint
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import dev.carthingspotify.controller.R
import dev.carthingspotify.controller.api.LrcLine
import dev.carthingspotify.controller.api.TimeText
import dev.carthingspotify.controller.model.*
import dev.carthingspotify.controller.utils.Constants
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

enum class SurfaceScreen { NOW_PLAYING, LIBRARY, COLLECTION, SEARCH, QUEUE, DEVICES, LYRICS, ADMIN_AUTH, ADMIN_SETUP }
enum class NotificationType { NONE, LOADING, SUCCESS, ERROR }

interface SurfaceActions {
    fun onPlayPause()
    fun onPrevious()
    fun onNext()
    fun onShuffle()
    fun onRepeat()
    fun onLike()
    fun onSeek(positionMs: Long)
    fun onVolume(percent: Int)
    fun onScreen(screen: SurfaceScreen)
    fun onSearchTap()
    fun onMediaItem(item: MediaItem)
    fun onMediaItemMenu(item: MediaItem)
    fun onArtworkNeeded(item: MediaItem)
    fun onCollectionBack()
    fun onCollectionPlay()
    fun onDevice(device: SpotifyDevice)
    fun onAdminRequested()
    fun onInteraction()
    fun onLyricsRequested()
    fun onAdminConfirm(password: String)
    fun onAdminSetup(password: String)
}

@SuppressLint("ViewConstructor")
class CarThingView(context: Context, private val actions: SurfaceActions) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val logical = RectF(0f, 0f, 1280f, 800f)
    private val touchZones = mutableListOf<Pair<RectF, () -> Unit>>()
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    private var downX = 0f
    private var downY = 0f
    private var adminDownAt = 0L
    private var progressDrag = false
    private var volumeDrag = false

    var screen: SurfaceScreen = SurfaceScreen.NOW_PLAYING
        set(value) {
            if (field != value) {
                val isOverlayChange = value == SurfaceScreen.LYRICS || field == SurfaceScreen.LYRICS ||
                                     value == SurfaceScreen.ADMIN_AUTH || field == SurfaceScreen.ADMIN_AUTH ||
                                     value == SurfaceScreen.ADMIN_SETUP || field == SurfaceScreen.ADMIN_SETUP
                
                transitionDirection = if (isOverlayChange) 0f else (if (screenIndex(value) > screenIndex(field)) 1f else -1f)
                animateScreenTransition()
                field = value
                page = 0
            }
        }
    var playback: PlaybackInfo = PlaybackInfo()
        set(value) {
            field = value
            invalidate()
        }
    var connection: ConnectionState = ConnectionState.LoginRequired
        set(value) { field = value; invalidate() }
    var liked: Boolean = false
        set(value) { field = value; invalidate() }
    var artwork: Bitmap? = null
        set(value) {
            if (field != value) {
                oldArtwork = field
                field = value
                updateBackgroundCache()
                animateArtworkCrossfade()
            }
        }
    var accentColor: Int = Color.rgb(30, 215, 96)
        set(value) {
            if (field != value) {
                field = value
                updateBackgroundCache()
                invalidate()
            }
        }
    var sections: List<BrowseSection> = emptyList()
        set(value) { field = value; page = 0; invalidate() }
    var collection: CollectionView? = null
        set(value) { field = value; page = 0; invalidate() }
    var queueItems: List<MediaItem> = emptyList()
        set(value) { field = value; page = 0; invalidate() }
    var devices: List<SpotifyDevice> = emptyList()
        set(value) { field = value; page = 0; invalidate() }
    var searchQuery: String = ""
        set(value) { field = value; invalidate() }
    var toastMessage: String = ""
        set(value) { field = value; invalidate() }
    var batteryPercent: Int = -1
        set(value) { field = value; invalidate() }
    var isCharging: Boolean = false
        set(value) { field = value; invalidate() }
    var clockEnabled: Boolean = true
        set(value) { field = value; invalidate() }
    var lyricsLines: List<LrcLine> = emptyList()
        set(value) { field = value; invalidate() }
    var lyricsPlain: String? = null
        set(value) { field = value; invalidate() }
    var isInstrumental: Boolean = false
        set(value) { field = value; invalidate() }
    var passwordInput: String = ""
        set(value) { field = value; invalidate() }
    var itemArtworks: Map<String, Bitmap> = emptyMap()
        set(value) { field = value; invalidate() }
    var notificationMsg: String = ""
        set(value) { field = value; invalidate() }
    var notificationType: NotificationType = NotificationType.NONE
        set(value) { field = value; invalidate() }
    var blackout: Boolean = false
        set(value) { field = value; invalidate() }
    var sessionInfo: String = ""
        set(value) { field = value; invalidate() }

    private val iconCache = mutableMapOf<Int, Drawable>()
    private var pressedZone: RectF? = null
    private var tappedRect: RectF? = null
    private var notificationSpin: Float = 0f
    private var cachedBg: Bitmap? = null

    // Animation properties
    private var artworkAnimator: ValueAnimator? = null
    private var transitionProgress = 1f
    private var transitionDirection = 1f
        private var screenAlpha = 1f
    private var screenTranslationY = 0f
    private var lyricsScrollY = 0f
    private var targetLyricsScrollY = 0f
    private var artworkAlpha = 1f
    private var oldArtwork: Bitmap? = null
    private var buttonScale = 1f
    private var page = 0

    private val fastOutSlowIn = AccelerateDecelerateInterpolator()
    private val decelerate = DecelerateInterpolator()

    private fun screenIndex(s: SurfaceScreen) = when(s) {
        SurfaceScreen.NOW_PLAYING -> 0
        SurfaceScreen.LIBRARY -> 1
        SurfaceScreen.COLLECTION -> 1
        SurfaceScreen.SEARCH -> 2
        SurfaceScreen.QUEUE -> 3
        SurfaceScreen.DEVICES -> 4
        else -> 0
    }

    private fun animateScreenTransition() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = fastOutSlowIn
            addUpdateListener {
                transitionProgress = it.animatedValue as Float
                screenAlpha = transitionProgress
                screenTranslationY = (1f - transitionProgress) * 40f * transitionDirection
                invalidate()
            }
            start()
        }
    }

    private fun animateArtworkCrossfade() {
        artworkAnimator?.cancel()
        artworkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400
            interpolator = fastOutSlowIn
            addUpdateListener {
                artworkAlpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    oldArtwork = null
                }
            })
            start()
        }
    }

    private fun animateLyricsScroll(target: Float) {
        if (abs(target - targetLyricsScrollY) < 1f) return
        targetLyricsScrollY = target
        ValueAnimator.ofFloat(lyricsScrollY, target).apply {
            duration = 400
            interpolator = fastOutSlowIn
            addUpdateListener {
                lyricsScrollY = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animateButtonPress(pressed: Boolean) {
        ValueAnimator.ofFloat(buttonScale, if (pressed) 0.98f else 1f).apply {
            duration = 150
            interpolator = decelerate
            addUpdateListener {
                buttonScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    init {
        isFocusable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (blackout) {
            canvas.drawColor(Color.BLACK)
            return
        }

        val sx = width / logical.width()
        val sy = height / logical.height()
        canvas.save()
        canvas.scale(sx, sy)
        drawBackground(canvas)
        
        // Permanent UI: Sidebar (Fixed)
        touchZones.clear()
        drawRail(canvas)

        // Animating Content: Central Page
        canvas.save()
        canvas.translate(0f, screenTranslationY)
        paint.alpha = (screenAlpha * 255).toInt()
        
        when (screen) {
            SurfaceScreen.NOW_PLAYING -> drawNowPlaying(canvas)
            SurfaceScreen.LIBRARY -> drawPlaylistGrid(canvas)
            SurfaceScreen.COLLECTION -> drawCollection(canvas)
            SurfaceScreen.SEARCH -> drawSections(canvas)
            SurfaceScreen.QUEUE -> drawQueueList(canvas)
            SurfaceScreen.DEVICES -> drawDevices(canvas)
            SurfaceScreen.LYRICS -> drawLyrics(canvas)
            SurfaceScreen.ADMIN_AUTH -> drawKeypad(canvas, "Administrator", "Enter password to unlock")
            SurfaceScreen.ADMIN_SETUP -> drawKeypad(canvas, "Setup Admin", "Create a numeric password")
        }
        canvas.restore()
        
        // Permanent UI: Status Area & Notifications (Fixed)
        drawTopStatus(canvas)
        drawNotification(canvas)
        drawToast(canvas)
        
        canvas.restore()
        if (clockEnabled || screen == SurfaceScreen.LYRICS || screenAlpha < 1f || notificationType == NotificationType.LOADING || artworkAlpha < 1f) {
            if (notificationType == NotificationType.LOADING) {
                notificationSpin = (notificationSpin + 12f) % 360f
            }
            postInvalidateOnAnimation()
        } else if (clockEnabled) {
            postInvalidateDelayed(30_000L)
        }
    }

    private fun updateBackgroundCache() {
        val width = 640
        val height = 400
        val bg = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val deepBase = Color.rgb(4, 5, 4)
        val surfaceBase = blend(accentColor, Color.BLACK, 0.94f)
        
        // Layer 1: Vertically centered gradient base
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), deepBase, surfaceBase, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        
        // Layer 2: Soft Primary Bloom (Bottom Left)
        val primaryGlow = blend(accentColor, Color.BLACK, 0.45f)
        p.shader = RadialGradient(150f, 350f, 500f, intArrayOf(primaryGlow, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        
        // Layer 3: Accent Bloom (Top Right)
        val secondaryGlow = blend(accentColor, Color.BLACK, 0.65f)
        p.shader = RadialGradient(550f, 50f, 400f, intArrayOf(secondaryGlow, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)

        cachedBg = bg
    }

    private fun drawBackground(canvas: Canvas) {
        if (cachedBg == null) updateBackgroundCache()
        cachedBg?.let {
            canvas.drawBitmap(it, null, logical, null)
        }
    }

    private fun drawRail(canvas: Canvas) {
        val nav = listOf(
            SurfaceScreen.NOW_PLAYING to R.drawable.ic_now_playing,
            SurfaceScreen.LIBRARY to R.drawable.ic_library,
            SurfaceScreen.SEARCH to R.drawable.ic_search,
            SurfaceScreen.QUEUE to R.drawable.ic_queue,
            SurfaceScreen.DEVICES to R.drawable.ic_devices
        )

        val itemHeight = 58f
        val gap = 48f
        val totalHeight = (nav.size * itemHeight) + ((nav.size - 1) * gap)
        val startTop = (800f - totalHeight) / 2f

        paint.color = Color.argb(100, 0, 0, 0)
        canvas.drawRoundRect(RectF(16f, startTop - 20f, 94f, startTop + totalHeight + 20f), 28f, 28f, paint)

        nav.forEachIndexed { index, pair ->
            val top = startTop + index * (itemHeight + gap)
            val selected = screen == pair.first ||
                (screen == SurfaceScreen.COLLECTION && pair.first == SurfaceScreen.LIBRARY)
            val rect = RectF(27f, top, 83f, top + itemHeight)

            if (selected) {
                paint.color = accentColor
                canvas.drawRoundRect(rect, 20f, 20f, paint)
            } else if (pressedZone?.contains(55f, top + 29f) == true) {
                 paint.color = Color.argb(40, 255, 255, 255)
                 canvas.drawRoundRect(rect, 20f, 20f, paint)
            }

            drawIcon(canvas, pair.second, 55f, top + 29f, 30f, if (selected) Color.BLACK else Color.LTGRAY)
            touchZones += RectF(20f, top - 10f, 90f, top + itemHeight + 10f) to { actions.onScreen(pair.first) }
        }
    }

    private fun drawIcon(canvas: Canvas, resId: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val drawable = iconCache.getOrPut(resId) { context.getDrawable(resId)!! }.constantState!!.newDrawable().mutate()
        drawable.setTint(color)
        val half = size / 2f
        drawable.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        drawable.draw(canvas)
    }

    private fun drawPager(canvas: Canvas, itemCount: Int, pageSize: Int, cx: Float = 1135f, cy: Float = 178f) {
        val pages = ((itemCount + pageSize - 1) / pageSize).coerceAtLeast(1)
        if (pages <= 1) return
        page = page.coerceIn(0, pages - 1)
        drawText(canvas, "${page + 1} / $pages", cx, cy, 15f, Color.LTGRAY, Paint.Align.CENTER, true)
        controlButton(canvas, RectF(cx - 115f, cy - 36f, cx - 55f, cy + 12f), glyph = "‹") {
            if (page > 0) { page--; invalidate() }
        }
        controlButton(canvas, RectF(cx + 53f, cy - 36f, cx + 113f, cy + 12f), glyph = "›") {
            if (page < pages - 1) { page++; invalidate() }
        }
    }

    private fun drawTopStatus(canvas: Canvas) {
        if (clockEnabled) {
            val timeText = clockFormat.format(Date())
            val batteryText = if (batteryPercent >= 0) "$batteryPercent%" else ""

            textPaint.textSize = 29f
            textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
            val timeWidth = textPaint.measureText(timeText)

            textPaint.textSize = 20f
            val batteryWidth = if (batteryText.isNotEmpty()) textPaint.measureText(batteryText) else 0f

            val totalWidth = timeWidth + (if (batteryWidth > 0) batteryWidth + 24f else 0f)
            val startX = 1242f - totalWidth

            if (batteryText.isNotEmpty()) {
                if (isCharging) {
                    drawText(canvas, "⚡", startX + batteryWidth - textPaint.measureText(batteryText) - 20f, 50f, 18f, Color.rgb(30, 215, 96), Paint.Align.RIGHT, true)
                }
                drawText(canvas, batteryText, startX + batteryWidth, 50f, 20f, Color.LTGRAY, Paint.Align.RIGHT, true)
            }
            drawText(canvas, timeText, 1242f, 50f, 29f, Color.WHITE, Paint.Align.RIGHT, true)
            drawText(canvas, dateFormat.format(Date()), 1242f, 72f, 13f, Color.LTGRAY, Paint.Align.RIGHT)
        }
        touchZones += RectF(1130f, 0f, 1280f, 92f) to { /* long-press only */ }
    }

    private fun drawNowPlaying(canvas: Canvas) {
        val artRect = RectF(128f, 150f, 630f, 652f)
        if (artworkAlpha < 1f && oldArtwork != null) {
            drawArtwork(canvas, oldArtwork!!, artRect, ((1f - artworkAlpha) * 255).toInt())
        }
        val bitmap = artwork
        if (bitmap != null) {
            drawArtwork(canvas, bitmap, artRect, (artworkAlpha * 255).toInt())
        } else {
            paint.color = Color.rgb(25, 31, 28)
            paint.alpha = (artworkAlpha * 255).toInt()
            canvas.drawRoundRect(artRect, 30f, 30f, paint)
            drawText(canvas, "♪", artRect.centerX(), artRect.centerY() + 45f, 150f, Color.argb((artworkAlpha * 255).toInt(), 60, 60, 60), Paint.Align.CENTER, true)
        }

        drawEllipsized(canvas, playback.track.name, 684f, 206f, 535f, 48f, Color.WHITE, true)
        drawEllipsized(canvas, playback.track.artist, 686f, 251f, 500f, 24f, Color.LTGRAY, false)
        drawEllipsized(canvas, playback.track.album, 686f, 284f, 500f, 17f, Color.GRAY, false)

        val buttonY = 416f
        controlButton(canvas, RectF(660f, buttonY - 38f, 736f, buttonY + 38f), icon = R.drawable.ic_shuffle, selected = playback.shuffle) { actions.onShuffle() }
        controlButton(canvas, RectF(760f, buttonY - 42f, 844f, buttonY + 42f), icon = R.drawable.ic_previous) { actions.onPrevious() }

        val playRect = RectF(860f, buttonY - 61f, 982f, buttonY + 61f)
        val isPlayPressed = pressedZone?.let { playRect.intersect(it) } ?: false
        val pScale = if (isPlayPressed) buttonScale else 1f

        canvas.save()
        canvas.scale(pScale, pScale, 921f, buttonY)
        paint.color = accentColor
        canvas.drawCircle(921f, buttonY, 61f, paint)
        drawIcon(canvas, if (playback.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, 921f, buttonY, 48f, Color.BLACK)
        canvas.restore()
        touchZones += playRect to { actions.onPlayPause() }

        controlButton(canvas, RectF(998f, buttonY - 42f, 1082f, buttonY + 42f), icon = R.drawable.ic_next) { actions.onNext() }
        controlButton(canvas, RectF(1106f, buttonY - 38f, 1182f, buttonY + 38f), icon = if (playback.repeat == "track") R.drawable.ic_repeat_one else R.drawable.ic_repeat, selected = playback.repeat != "off") { actions.onRepeat() }

        val progress = estimatedProgress()
        drawSlider(canvas, RectF(684f, 514f, 1190f, 525f), progress.toFloat() / playback.track.durationMs.coerceAtLeast(1L), accentColor)
        drawText(canvas, TimeText.elapsed(progress), 684f, 557f, 16f, Color.LTGRAY, Paint.Align.LEFT)
        drawText(canvas, TimeText.remaining(progress, playback.track.durationMs), 1190f, 557f, 16f, Color.LTGRAY, Paint.Align.RIGHT)
        touchZones += RectF(670f, 490f, 1205f, 550f) to { }

        val heartIcon = if (liked) R.drawable.ic_heart else R.drawable.ic_heart_outline
        val heartColor = if (liked) accentColor else Color.WHITE
        val heartRect = RectF(666f, 600f, 750f, 680f)
        val isHeartPressed = pressedZone?.let { heartRect.intersect(it) } ?: false
        val hScale = if (isHeartPressed) buttonScale else 1f
        canvas.save()
        canvas.scale(hScale, hScale, 706f, 636f)
        drawIcon(canvas, heartIcon, 706f, 626f, 36f, heartColor)
        canvas.restore()
        touchZones += heartRect to { actions.onLike() }

        val volCenterY = 624f
        drawText(canvas, "VOL", 782f, volCenterY + 6f, 13f, Color.GRAY, Paint.Align.LEFT, true)
        drawSlider(canvas, RectF(830f, volCenterY - 5f, 1155f, volCenterY + 5f), playback.volume / 100f, Color.WHITE)
        drawText(canvas, "${playback.volume}%", 1192f, volCenterY + 6f, 15f, Color.LTGRAY, Paint.Align.RIGHT)
        touchZones += RectF(810f, 596f, 1175f, 664f) to { }

        val barRect = RectF(Constants.LOGICAL_WIDTH / 2f - 315f, 690f, Constants.LOGICAL_WIDTH / 2f + 315f, 755f)
        paint.color = Color.argb(115, 0, 0, 0)
        canvas.drawRoundRect(barRect, 22f, 22f, paint)

        // Left: Device Info
        drawText(canvas, "PLAYING ON", barRect.left + 25f, 714f, 11f, Color.GRAY, Paint.Align.LEFT, true)
        drawEllipsized(canvas, playback.deviceName.uppercase(Locale.getDefault()), barRect.left + 25f, 741f, 190f, 19f, Color.WHITE, true)

        // Center: Connection Status & Session Info
        val statusText = when (connection) {
            ConnectionState.Online -> "ONLINE"
            ConnectionState.Connecting -> "CONNECTING"
            else -> "OFFLINE"
        }
        val statusColor = when (connection) {
            ConnectionState.Online -> accentColor
            ConnectionState.Connecting -> Color.rgb(255, 190, 70)
            else -> Color.rgb(255, 105, 105)
        }
        
        val centerX = barRect.centerX()
        
        // Session Info (Above)
        if (sessionInfo.isNotEmpty()) {
            drawText(canvas, sessionInfo.uppercase(Locale.getDefault()), centerX, 715f, 10f, Color.argb(160, 255, 255, 255), Paint.Align.CENTER, false)
        }
        
        // Status Text (Below)
        textPaint.textSize = 19f
        textPaint.typeface = Typeface.create("sans", Typeface.BOLD)
        val statusWidth = textPaint.measureText(statusText)
        val totalStatusW = statusWidth + 22f // text + spacing + dot
        val startStatusX = centerX - (totalStatusW / 2f)
        
        drawText(canvas, statusText, startStatusX, 742f, 19f, Color.WHITE, Paint.Align.LEFT, true)
        paint.color = statusColor
        canvas.drawCircle(startStatusX + statusWidth + 18f, 734f, 6f, paint)

        // Right: Action Buttons
        val devRect = RectF(barRect.right - 180f, 698f, barRect.right - 100f, 746f)
        controlButton(canvas, devRect, icon = R.drawable.ic_devices) { actions.onScreen(SurfaceScreen.DEVICES) }

        val spotRect = RectF(barRect.right - 88f, 698f, barRect.right - 8f, 746f)
        controlButton(canvas, spotRect, icon = R.drawable.ic_lyrics) { actions.onLyricsRequested() }
    }

    private fun drawArtwork(canvas: Canvas, bitmap: Bitmap, rect: RectF, alpha: Int) {
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(rect.width() / bitmap.width, rect.height() / bitmap.height)
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(rect.left - (bitmap.width * scale - rect.width()) / 2f, rect.top - (bitmap.height * scale - rect.height()) / 2f)
        }
        shader.setLocalMatrix(matrix)
        paint.shader = shader
        paint.alpha = alpha
        canvas.drawRoundRect(rect, 30f, 30f, paint)
        paint.shader = null
    }

    private fun drawSections(canvas: Canvas) {
        val titleText = if (screen == SurfaceScreen.SEARCH) (if (searchQuery.isBlank()) "Search Spotify" else "Results for “$searchQuery”") else "Your music"

        val isSearchEmpty = screen == SurfaceScreen.SEARCH && searchQuery.isBlank()

        if (!isSearchEmpty && sections.isNotEmpty()) {
            val flat = sections.flatMap { section -> section.items.map { section.title to it } }
            // Hide pager for Search tab
            val showPager = screen != SurfaceScreen.SEARCH
            drawPageHeader(canvas, titleText, if (showPager) flat.size else 0, 6)

            // Limit search results to 1 page (6 items)
            val visible = if (screen == SurfaceScreen.SEARCH) flat.take(6) else flat.drop(page * 6).take(6)
            visible.forEachIndexed { index, entry ->
                mediaRow(canvas, entry.second, 220f + index * 95f, badge = entry.first)
            }
        } else {
            drawPageHeader(canvas, titleText, 0, 0)
        }

        if (screen == SurfaceScreen.SEARCH) {
            // Reposition search box to stay stable in the header area
            val searchBox = RectF(850f, 105f, 1200f, 165f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(180, 255, 255, 255)
            canvas.drawRoundRect(searchBox, 30f, 30f, paint)
            paint.style = Paint.Style.FILL
            drawText(canvas, if (searchQuery.isBlank()) "⌕  Tap to search" else "⌕  $searchQuery", searchBox.left + 25f, 144f, 19f, Color.LTGRAY, Paint.Align.LEFT)
            touchZones += searchBox to { actions.onSearchTap() }

            if (searchQuery.isBlank()) {
                drawText(canvas, "⌕", 680f, 430f, 120f, Color.argb(40, 255, 255, 255), Paint.Align.CENTER, true)
                drawText(canvas, "Tap the search bar to find music", 680f, 490f, 26f, Color.GRAY, Paint.Align.CENTER)
            }
        }
    }

    private fun drawQueueList(canvas: Canvas) {
        drawPageHeader(canvas, "Up Next", queueItems.size, 6)
        if (queueItems.isEmpty()) {
            drawText(canvas, "The queue is empty", 160f, 260f, 24f, Color.GRAY, Paint.Align.LEFT)
            return
        }
        val visible = queueItems.drop(page * 6).take(6)
        visible.forEachIndexed { index, item ->
            val top = 220f + index * 95f
            mediaRow(canvas, item, top, index + 1 + page * 6)
        }
    }

    private fun drawPlaylistGrid(canvas: Canvas) {
        val playlists = sections.find { it.title.contains("playlists", ignoreCase = true) }?.items ?: emptyList()
        drawPageHeader(canvas, "Your Music", playlists.size, 9)

        if (playlists.isEmpty()) {
            drawText(canvas, "Your library will appear here", 160f, 260f, 24f, Color.GRAY, Paint.Align.LEFT)
            return
        }

        val cols = 3
        val cardW = 340f
        val cardH = 140f
        val hGap = 30f
        val vGap = 30f
        val startX = 160f
        val startY = 230f
        playlists.drop(page * 9).take(9).forEachIndexed { index, item ->
            val row = index / cols
            val col = index % cols
            val left = startX + col * (cardW + hGap)
            val top = startY + row * (cardH + vGap)
            val rect = RectF(left, top, left + cardW, top + cardH)

            val isPressed = pressedZone?.let { rect.intersect(it) } ?: false
            val scale = if (isPressed) buttonScale else 1f

            canvas.save()
            canvas.scale(scale, scale, rect.centerX(), rect.centerY())
            paint.color = Color.argb(45, 255, 255, 255)
            canvas.drawRoundRect(rect, 28f, 28f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = Color.argb(60, 255, 255, 255)
            canvas.drawRoundRect(rect, 28f, 28f, paint)
            paint.style = Paint.Style.FILL
            val imgRect = RectF(left + 15f, top + 15f, left + 125f, top + 125f)
            val cached = itemArtworks[item.uri]
            if (cached != null) {
                drawArtwork(canvas, cached, imgRect, 255)
            } else {
                actions.onArtworkNeeded(item)
                paint.color = Color.argb(60, 0, 0, 0)
                canvas.drawRoundRect(imgRect, 16f, 16f, paint)
                drawIcon(canvas, R.drawable.ic_library, imgRect.centerX(), imgRect.centerY(), 40f, Color.GRAY)
            }
            drawEllipsized(canvas, item.name, left + 140f, top + 55f, cardW - 150f, 22f, Color.WHITE, true)
            drawEllipsized(canvas, item.subtitle, left + 140f, top + 85f, cardW - 150f, 16f, Color.LTGRAY, false)
            canvas.restore()
            touchZones += rect to { tappedRect = rect; actions.onMediaItem(item) }
        }
    }

    private fun drawCollection(canvas: Canvas) {
        val detail = collection
        if (detail == null) {
            drawText(canvas, "Playlist unavailable", 160f, 180f, 36f, Color.WHITE, Paint.Align.LEFT, true)
            controlButton(canvas, RectF(128f, 215f, 208f, 275f), glyph = "‹") { actions.onCollectionBack() }
            return
        }

        controlButton(canvas, RectF(128f, 104f, 188f, 156f), glyph = "‹") {
            actions.onCollectionBack()
        }

        val artRect = RectF(215f, 104f, 415f, 304f)
        val cached = itemArtworks[detail.container.uri]
        if (cached != null) {
            drawArtwork(canvas, cached, artRect, 255)
        } else {
            actions.onArtworkNeeded(detail.container)
            paint.color = Color.argb(65, 0, 0, 0)
            canvas.drawRoundRect(artRect, 24f, 24f, paint)
            drawIcon(canvas, R.drawable.ic_library, artRect.centerX(), artRect.centerY(), 62f, Color.LTGRAY)
        }

        val kind = when (detail.container.type) {
            MediaType.ALBUM -> "ALBUM"
            else -> "PLAYLIST"
        }
        drawText(canvas, kind, 455f, 135f, 14f, accentColor, Paint.Align.LEFT, true)
        drawEllipsized(canvas, detail.container.name, 455f, 188f, 610f, 42f, Color.WHITE, true)
        drawEllipsized(canvas, detail.container.subtitle, 455f, 226f, 510f, 20f, Color.LTGRAY, false)
        drawText(
            canvas,
            "${detail.items.size} ${if (detail.items.size == 1) "song" else "songs"}",
            455f,
            263f,
            17f,
            Color.GRAY,
            Paint.Align.LEFT
        )

        controlButton(
            canvas,
            RectF(1035f, 205f, 1125f, 295f),
            icon = R.drawable.ic_play,
            selected = true
        ) { actions.onCollectionPlay() }

        drawText(canvas, "Tracks", 160f, 356f, 30f, Color.WHITE, Paint.Align.LEFT, true)
        drawPager(canvas, detail.items.size, 4, 1100f, 352f)

        if (detail.items.isEmpty()) {
            drawText(canvas, "No playable tracks were returned", 160f, 440f, 23f, Color.GRAY, Paint.Align.LEFT)
            return
        }

        detail.items.drop(page * 4).take(4).forEachIndexed { index, item ->
            val absoluteIndex = page * 4 + index + 1
            mediaRow(canvas, item, 390f + index * 95f, absoluteIndex)
        }
    }

    private fun drawPageHeader(canvas: Canvas, title: String, itemCount: Int, pageSize: Int) {
        val headerY = 150f
        val leftMargin = 160f
        drawText(canvas, title, leftMargin, headerY, 42f, Color.WHITE, Paint.Align.LEFT, true)

        if (itemCount > pageSize && pageSize > 0) {
            val pages = (itemCount + pageSize - 1) / pageSize
            val cx = 1070f
            val cy = headerY - 12f

            // Symmetrical Pager
            drawText(canvas, "PAGE ${page + 1} / $pages", cx, cy + 8f, 15f, Color.LTGRAY, Paint.Align.CENTER, true)

            val btnW = 60f
            val btnH = 48f
            val gap = 62f

            // Prev
            controlButton(canvas, RectF(cx - gap - btnW, cy - btnH/2f, cx - gap, cy + btnH/2f), glyph = "‹") {
                if (page > 0) { page--; invalidate() }
            }

            // Next
            controlButton(canvas, RectF(cx + gap, cy - btnH/2f, cx + gap + btnW, cy + btnH/2f), glyph = "›") {
                if (page < pages - 1) { page++; invalidate() }
            }
        }
    }

    private fun mediaRow(canvas: Canvas, item: MediaItem, top: Float, index: Int? = null, badge: String = "") {
        val rect = RectF(118f, top, 1225f, top + 85f)
        val isPressed = pressedZone?.let { rect.intersect(it) } ?: false
        val scale = if (isPressed) buttonScale else 1f
        canvas.save()
        canvas.scale(scale, scale, rect.centerX(), rect.centerY())
        paint.color = Color.argb(45, 255, 255, 255)
        canvas.drawRoundRect(rect, 22f, 22f, paint)
        if (index != null) drawText(canvas, index.toString(), 145f, top + 52f, 17f, Color.GRAY, Paint.Align.CENTER, true)
        val imgRect = RectF(if (index == null) 132f else 178f, top + 10f, if (index == null) 192f else 238f, top + 75f)
        val cached = itemArtworks[item.uri]
            ?: collection
                ?.takeIf { screen == SurfaceScreen.COLLECTION && it.container.type == MediaType.ALBUM }
                ?.let { itemArtworks[it.container.uri] }
        if (cached != null) {
            drawArtwork(canvas, cached, imgRect, 255)
        } else {
            actions.onArtworkNeeded(item)
            paint.color = Color.argb(60, 0, 0, 0)
            canvas.drawRoundRect(imgRect, 14f, 14f, paint)
            val typeIcon = when(item.type) {
                MediaType.TRACK -> R.drawable.ic_play
                MediaType.ALBUM -> R.drawable.ic_library
                MediaType.ARTIST -> R.drawable.ic_now_playing
                MediaType.PLAYLIST -> R.drawable.ic_queue
                MediaType.EPISODE -> R.drawable.ic_lyrics
            }
            drawIcon(canvas, typeIcon, imgRect.centerX(), imgRect.centerY(), 26f, Color.WHITE)
        }
        val textX = if (index == null) 214f else 262f
        drawEllipsized(canvas, item.name, textX, top + 38f, 750f, 22f, Color.WHITE, true)
        drawEllipsized(canvas, item.subtitle, textX, top + 65f, 700f, 16f, Color.GRAY, false)
        if (badge.isNotBlank()) drawEllipsized(canvas, badge.uppercase(Locale.getDefault()), 1035f, top + 32f, 125f, 11f, Color.GRAY, true)
        drawIcon(canvas, R.drawable.ic_play, 1185f, top + 42f, 24f, accentColor)
        canvas.restore()
        touchZones += rect to { tappedRect = rect; actions.onMediaItem(item) }
    }

    private fun drawDevices(canvas: Canvas) {
        drawText(canvas, "Playback devices", 128f, 132f, 42f, Color.WHITE, Paint.Align.LEFT, true)
        drawText(canvas, "Choose the Windows PC running Spotify", 128f, 169f, 17f, Color.GRAY, Paint.Align.LEFT)
        if (devices.isEmpty()) drawText(canvas, "Open Spotify on your PC, then refresh this screen", 128f, 245f, 24f, Color.GRAY, Paint.Align.LEFT)
        devices.drop(page * 6).take(6).forEachIndexed { index, device ->
            val top = 205f + index * 91f
            paint.color = if (device.active) Color.argb(45, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)) else Color.argb(65, 255, 255, 255)
            canvas.drawRoundRect(RectF(118f, top, 1225f, top + 78f), 18f, 18f, paint)
            drawIcon(canvas, if (device.type.equals("computer", true)) R.drawable.ic_devices else R.drawable.ic_now_playing, 166f, top + 40f, 29f, if (device.active) accentColor else Color.LTGRAY)
            drawText(canvas, device.name, 215f, top + 38f, 22f, Color.WHITE, Paint.Align.LEFT, true)
            drawText(canvas, "${device.type.lowercase().replaceFirstChar { it.uppercase() }}  ·  ${device.volume}%", 215f, top + 62f, 14f, Color.GRAY, Paint.Align.LEFT)
            drawText(canvas, if (device.active) "ACTIVE" else "CONNECT", 1182f, top + 46f, 14f, if (device.active) accentColor else Color.LTGRAY, Paint.Align.RIGHT, true)
            touchZones += RectF(110f, top - 4f, 1240f, top + 82f) to { actions.onDevice(device) }
        }
        drawPager(canvas, devices.size, 6)
    }

    private fun drawLyrics(canvas: Canvas) {
        val titleRect = RectF(128f, 90f, 1240f, 760f)
        paint.color = Color.argb(40, 0, 0, 0)
        canvas.drawRoundRect(titleRect, 32f, 32f, paint)
        drawText(canvas, "Lyrics", 160f, 145f, 42f, Color.WHITE, Paint.Align.LEFT, true)
        val progress = estimatedProgress()
        if (isInstrumental) {
            drawText(canvas, "Instrumental", 640f, 400f, 32f, Color.GRAY, Paint.Align.CENTER)
        } else if (lyricsLines.isEmpty() && lyricsPlain == null) {
            drawText(canvas, "No lyrics available", 640f, 400f, 32f, Color.GRAY, Paint.Align.CENTER)
        } else if (lyricsLines.isNotEmpty()) {
            val activeIndex = lyricsLines.indexOfLast { it.timeMs <= progress }.coerceAtLeast(0)
            val centerY = 400f
            val lineHeight = 64f
            animateLyricsScroll(activeIndex * lineHeight)
            canvas.save()
            canvas.clipRect(128f, 160f, 1240f, 740f)
            lyricsLines.forEachIndexed { index, line ->
                val y = centerY + (index * lineHeight) - lyricsScrollY
                if (y in 80f..820f) {
                    val isActive = index == activeIndex
                    drawText(canvas, line.text, 640f, y, if (isActive) 35f else 28f, Color.argb(if (isActive) 255 else 110, 255, 255, 255), Paint.Align.CENTER, isActive)
                }
            }
            canvas.restore()
        } else {
            val lines = lyricsPlain?.split("\n") ?: emptyList()
            lines.forEachIndexed { index, line ->
                val y = 200f + index * 40f
                if (y in 160f..740f) drawText(canvas, line, 640f, y, 24f, Color.WHITE, Paint.Align.CENTER)
            }
        }
        controlButton(canvas, RectF(1080f, 110f, 1210f, 160f), glyph = "Close") { actions.onScreen(SurfaceScreen.NOW_PLAYING) }
    }

    private fun drawKeypad(canvas: Canvas, title: String, subtitle: String) {
        val bg = RectF(340f, 70f, 940f, 730f)
        paint.color = Color.argb(240, 15, 18, 16)
        canvas.drawRoundRect(bg, 40f, 40f, paint)
        val cancelRect = RectF(870f, 90f, 920f, 140f)
        controlButton(canvas, cancelRect, glyph = "✕") { passwordInput = ""; actions.onScreen(SurfaceScreen.NOW_PLAYING) }
        drawText(canvas, title, 640f, 130f, 36f, Color.WHITE, Paint.Align.CENTER, true)
        drawText(canvas, subtitle, 640f, 170f, 18f, Color.GRAY, Paint.Align.CENTER)
        val dotsX = 640f - (passwordInput.length * 25f) / 2f
        for (i in passwordInput.indices) {
            paint.color = accentColor
            canvas.drawCircle(dotsX + i * 25f + 12f, 215f, 6f, paint)
        }
        val startX = 460f
        val startY = 260f
        val btnSize = 100f
        val hGap = 30f
        val vGap = 15f
        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3
            val digit = (i + 1).toString()
            val rect = RectF(startX + col * (btnSize + hGap), startY + row * (btnSize + vGap),
                             startX + col * (btnSize + hGap) + btnSize, startY + row * (btnSize + vGap) + btnSize)
            val isPressed = pressedZone?.let { RectF(rect).intersect(it) } ?: false
            paint.color = Color.argb(if (isPressed) 160 else 80, 255, 255, 255)
            canvas.drawRoundRect(rect, btnSize / 2f, btnSize / 2f, paint)
            drawText(canvas, digit, rect.centerX(), rect.centerY() + 14f, 32f, Color.WHITE, Paint.Align.CENTER, true)
            touchZones += rect to { if (passwordInput.length < 8) passwordInput += digit }
        }
        val zeroRect = RectF(startX + 1 * (btnSize + hGap), startY + 3 * (btnSize + vGap), startX + 1 * (btnSize + hGap) + btnSize, startY + 3 * (btnSize + vGap) + btnSize)
        val zeroPressed = pressedZone?.let { RectF(zeroRect).intersect(it) } ?: false
        paint.color = Color.argb(if (zeroPressed) 160 else 80, 255, 255, 255)
        canvas.drawRoundRect(zeroRect, btnSize / 2f, btnSize / 2f, paint)
        drawText(canvas, "0", zeroRect.centerX(), zeroRect.centerY() + 14f, 32f, Color.WHITE, Paint.Align.CENTER, true)
        touchZones += zeroRect to { if (passwordInput.length < 8) passwordInput += "0" }

        val delRect = RectF(startX, startY + 3 * (btnSize + vGap), startX + btnSize, startY + 3 * (btnSize + vGap) + btnSize)
        val delPressed = pressedZone?.let { RectF(delRect).intersect(it) } ?: false
        paint.color = Color.argb(if (delPressed) 160 else 80, 255, 100, 100)
        canvas.drawRoundRect(delRect, btnSize / 2f, btnSize / 2f, paint)
        drawText(canvas, "←", delRect.centerX(), delRect.centerY() + 12f, 32f, Color.WHITE, Paint.Align.CENTER, true)
        touchZones += delRect to { if (passwordInput.isNotEmpty()) passwordInput = passwordInput.dropLast(1) }
        val okRect = RectF(startX + 2 * (btnSize + hGap), startY + 3 * (btnSize + vGap), startX + 2 * (btnSize + hGap) + btnSize, startY + 3 * (btnSize + vGap) + btnSize)
        val okPressed = pressedZone?.let { RectF(okRect).intersect(it) } ?: false
        paint.color = if (okPressed) blend(accentColor, Color.BLACK, 0.2f) else accentColor
        canvas.drawRoundRect(okRect, btnSize / 2f, btnSize / 2f, paint)
        drawText(canvas, "✓", okRect.centerX(), okRect.centerY() + 12f, 32f, Color.BLACK, Paint.Align.CENTER, true)
        touchZones += okRect to {
            if (screen == SurfaceScreen.ADMIN_AUTH) actions.onAdminConfirm(passwordInput)
            else actions.onAdminSetup(passwordInput)
            passwordInput = ""
        }
    }

    private fun controlButton(canvas: Canvas, rect: RectF, glyph: String? = null, icon: Int? = null, selected: Boolean = false, action: () -> Unit) {
        val isPressed = pressedZone?.let { RectF(rect).apply { inset(-10f, -10f) }.intersect(it) } ?: false
        val scale = if (isPressed) buttonScale else 1f
        canvas.save()
        canvas.scale(scale, scale, rect.centerX(), rect.centerY())
        paint.color = if (selected) accentColor else Color.argb(if (isPressed) 130 else 80, 255, 255, 255)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        if (glyph != null) {
            drawText(canvas, glyph, rect.centerX(), rect.centerY() + 8f, 28f, if (selected) Color.BLACK else Color.WHITE, Paint.Align.CENTER, true)
        } else if (icon != null) {
            drawIcon(canvas, icon, rect.centerX(), rect.centerY(), rect.height() * 0.55f, if (selected) Color.BLACK else Color.WHITE)
        }
        canvas.restore()
        touchZones += RectF(rect).apply { inset(-10f, -10f) } to action
    }

    private fun drawSlider(canvas: Canvas, rect: RectF, fraction: Float, activeColor: Int) {
        val cy = rect.centerY()
        paint.color = Color.argb(75, 255, 255, 255)
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        val end = rect.left + rect.width() * fraction.coerceIn(0f, 1f)
        paint.color = activeColor
        canvas.drawRoundRect(RectF(rect.left, rect.top, end, rect.bottom), rect.height() / 2f, rect.height() / 2f, paint)
        canvas.drawCircle(end, cy, 9f, paint)
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align, bold: Boolean = false) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
        textPaint.typeface = if (bold) Typeface.create("sans", Typeface.BOLD) else Typeface.create("sans", Typeface.NORMAL)
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawEllipsized(canvas: Canvas, text: String, x: Float, y: Float, maxWidth: Float, size: Float, color: Int, bold: Boolean) {
        textPaint.textSize = size
        textPaint.typeface = if (bold) Typeface.create("sans", Typeface.BOLD) else Typeface.DEFAULT
        textPaint.color = color
        textPaint.textAlign = Paint.Align.LEFT
        val clipped = TextUtils.ellipsize(text, textPaint, maxWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(clipped, x, y, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x * 1280f / width.coerceAtLeast(1)
        val y = event.y * 800f / height.coerceAtLeast(1)
        
        if (blackout) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                actions.onInteraction()
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                actions.onInteraction()
                downX = x
                downY = y
                pressedZone = RectF(x - 10f, y - 10f, x + 10f, y + 10f)
                tappedRect = touchZones.find { it.first.contains(x, y) }?.first
                animateButtonPress(true)
                adminDownAt = if (x > 1130f && y < 100f) System.currentTimeMillis() else 0L
                progressDrag = screen == SurfaceScreen.NOW_PLAYING && x in 660f..1210f && y in 458f..538f
                volumeDrag = screen == SurfaceScreen.NOW_PLAYING && x in 800f..1180f && y in 575f..660f
                if (progressDrag) updateSeek(x)
                if (volumeDrag) updateVolume(x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (progressDrag) updateSeek(x)
                if (volumeDrag) updateVolume(x)
                if (adminDownAt > 0 && System.currentTimeMillis() - adminDownAt >= 2500L) {
                    adminDownAt = 0L
                    actions.onAdminRequested()
                }
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                if (adminDownAt > 0 && System.currentTimeMillis() - adminDownAt >= 2500L) actions.onAdminRequested()
                adminDownAt = 0L
                if (!progressDrag && !volumeDrag && abs(x - downX) < 35f && abs(y - downY) < 35f) {
                    touchZones.lastOrNull { it.first.contains(x, y) }?.second?.invoke()
                }
                progressDrag = false
                volumeDrag = false
                pressedZone = null
                animateButtonPress(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                adminDownAt = 0L
                progressDrag = false
                volumeDrag = false
                pressedZone = null
                animateButtonPress(false)
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateSeek(x: Float) {
        val fraction = ((x - 684f) / (1190f - 684f)).coerceIn(0f, 1f)
        actions.onSeek((playback.track.durationMs * fraction).roundToInt().toLong())
    }

    private fun updateVolume(x: Float) {
        actions.onVolume((((x - 830f) / (1155f - 830f)).coerceIn(0f, 1f) * 100f).roundToInt())
    }

    private fun estimatedProgress(): Long {
        val advanced = if (playback.isPlaying) System.currentTimeMillis() - playback.fetchedAt else 0L
        return (playback.progressMs + advanced).coerceIn(0L, playback.track.durationMs.coerceAtLeast(0L))
    }

    private fun drawNotification(canvas: Canvas) {
        if (notificationType == NotificationType.NONE) return

        val msg = notificationMsg
        textPaint.textSize = 20f
        val msgWidth = textPaint.measureText(msg)
        val h = 60f
        val w = (msgWidth + 100f).coerceAtMost(600f)
        val rect = RectF(640f - w/2f, 25f, 640f + w/2f, 25f + h)

        // Liquid Glass Notification
        paint.color = Color.argb(225, 20, 25, 22)
        canvas.drawRoundRect(rect, h/2f, h/2f, paint)

        // Rim light
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = Color.argb(40, 255, 255, 255)
        canvas.drawRoundRect(rect, h/2f, h/2f, paint)
        paint.style = Paint.Style.FILL

        val iconX = rect.left + 35f
        val iconY = rect.centerY()

        when (notificationType) {
            NotificationType.LOADING -> {
                canvas.save()
                canvas.rotate(notificationSpin, iconX, iconY)
                paint.color = accentColor
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                canvas.drawArc(RectF(iconX - 12f, iconY - 12f, iconX + 12f, iconY + 12f), 0f, 270f, false, paint)
                paint.style = Paint.Style.FILL
                canvas.restore()
            }
            NotificationType.SUCCESS -> {
                drawIcon(canvas, R.drawable.ic_check, iconX, iconY, 24f, accentColor)
            }
            NotificationType.ERROR -> {
                drawIcon(canvas, R.drawable.ic_error_mark, iconX, iconY, 24f, Color.RED)
            }
            else -> {}
        }

        drawText(canvas, msg, iconX + 30f, iconY + 7f, 20f, Color.WHITE, Paint.Align.LEFT)
    }

    private fun drawToast(canvas: Canvas) {
        if (toastMessage.isBlank()) return
        paint.color = Color.argb(230, 28, 32, 30)
        canvas.drawRoundRect(RectF(350f, 720f, 1035f, 778f), 25f, 25f, paint)
        drawEllipsized(canvas, toastMessage, 382f, 756f, 620f, 17f, Color.WHITE, false)
    }

    private fun blend(first: Int, second: Int, amountSecond: Float): Int {
        val a = amountSecond.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) * (1 - a) + Color.red(second) * a).roundToInt(),
            (Color.green(first) * (1 - a) + Color.green(second) * a).roundToInt(),
            (Color.blue(first) * (1 - a) + Color.blue(second) * a).roundToInt()
        )
    }
}
