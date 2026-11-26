package com.laguku.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.laguku.player.MainActivity
import com.laguku.player.R
import com.laguku.player.model.CoverPalette
import com.laguku.player.model.Track
import com.laguku.player.service.PlaybackService
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.StackBlur
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Resizable 4-layer Liquid Glass home-screen pill widget for Laguku.
 *
 * Updates strictly on playback events pushed from [PlaybackService] with zero polling timers.
 * Offscreen 4-layer glass composition and StackBlur run off the main thread on [widgetExecutor],
 * keyed by `(widthPx, heightPx, dominantColor, isPlaying)` within Binder's 1 MB budget.
 */
class LagukuPillWidgetProvider : AppWidgetProvider() {

    enum class WidgetLayoutMode {
        COMPACT,
        STANDARD,
        EXPANDED
    }

    enum class PinWidgetStatus {
        PIN_REQUEST_SENT,
        UNSUPPORTED_USE_MANUAL_GUIDE
    }

    data class BackdropCacheKey(
        val widthPx: Int,
        val heightPx: Int,
        val dominantColor: Int,
        val isPlaying: Boolean
    )

    class BackdropBitmapCache<T>(private val maxEntries: Int = 2) {
        private val entries = LinkedHashMap<BackdropCacheKey, T>(maxEntries + 1, 0.75f, true)

        @Synchronized
        fun get(key: BackdropCacheKey): T? = entries[key]

        @Synchronized
        fun put(key: BackdropCacheKey, value: T): T? {
            entries[key] = value
            if (entries.size <= maxEntries) return null
            val eldestKey = entries.entries.firstOrNull()?.key ?: return null
            return entries.remove(eldestKey)
        }

        @Synchronized
        fun size(): Int = entries.size

        @Synchronized
        fun clear(): List<T> {
            val all = entries.values.toList()
            entries.clear()
            return all
        }
    }

    object WidgetOpticsMath {
        const val MAX_BACKDROP_WIDTH_PX = 720
        const val MAX_BACKDROP_HEIGHT_PX = 180
        const val OUTER_MARGIN_DP = 4f
        const val INNER_ARTWORK_PADDING_DP = 8f
        const val BEZEL_HINT_DP = 10f

        const val NEUTRAL_IDLE_COLOR = 0xFF1A202C.toInt()
        const val GLASS_BASE_DARK = 0xFF0C0E14.toInt()

        const val ALPHA_DARK_BACKDROP = 0.58f
        const val ALPHA_LIGHT_BACKDROP = 0.68f

        const val RIM_ALPHA_UPPER_LEFT = 0.65f
        const val RIM_ALPHA_MID = 0.10f
        const val RIM_ALPHA_LOWER_RIGHT = 0.35f

        fun computeBackdropSizePx(
            widthDp: Int,
            heightDp: Int,
            density: Float
        ): Pair<Int, Int> {
            val contentWidthDp = (widthDp - OUTER_MARGIN_DP * 2f).coerceAtLeast(120f)
            val contentHeightDp = (heightDp - OUTER_MARGIN_DP * 2f).coerceAtLeast(44f)
            val scale = density.coerceIn(1.5f, 2.5f)
            var w = (contentWidthDp * scale).roundToInt().coerceAtLeast(1)
            var h = (contentHeightDp * scale).roundToInt().coerceAtLeast(1)
            if (w > MAX_BACKDROP_WIDTH_PX || h > MAX_BACKDROP_HEIGHT_PX) {
                val factor = minOf(
                    MAX_BACKDROP_WIDTH_PX.toFloat() / w,
                    MAX_BACKDROP_HEIGHT_PX.toFloat() / h
                )
                w = (w * factor).roundToInt().coerceAtLeast(1)
                h = (h * factor).roundToInt().coerceAtLeast(1)
            }
            return w to h
        }

        fun computeConcentricArtworkRadiusDp(
            capsuleHeightDp: Float,
            innerPaddingDp: Float = INNER_ARTWORK_PADDING_DP
        ): Float {
            val outerRadiusDp = capsuleHeightDp.coerceAtLeast(24f) * 0.5f
            return (outerRadiusDp - innerPaddingDp).coerceIn(12f, 16f)
        }

        fun computeConcentricArtworkRadiusPx(
            artworkSizePx: Int,
            capsuleHeightDp: Float,
            innerPaddingDp: Float = INNER_ARTWORK_PADDING_DP
        ): Float {
            val artSpanDp = (capsuleHeightDp - innerPaddingDp * 1.5f).coerceAtLeast(24f)
            val radiusDp = computeConcentricArtworkRadiusDp(capsuleHeightDp, innerPaddingDp)
            return ((radiusDp / artSpanDp) * artworkSizePx).coerceIn(8f, artworkSizePx * 0.45f)
        }

        fun computeAmbientTintColors(
            dominantColor: Int,
            accentGlow: Int = dominantColor,
            isLightBackdrop: Boolean = false
        ): Pair<Int, Int> {
            val alpha = if (isLightBackdrop) ALPHA_LIGHT_BACKDROP else ALPHA_DARK_BACKDROP
            val alphaByte = (alpha * 255f).roundToInt().coerceIn(0, 255)
            val topTint = blendDarkGlass(accentGlow, dominantColor, bleedWeight = 0.28f, maxChannel = 54)
            val bottomTint = blendDarkGlass(dominantColor, GLASS_BASE_DARK, bleedWeight = 0.18f, maxChannel = 34)
            return ((alphaByte shl 24) or (topTint and 0x00FFFFFF)) to
                ((alphaByte shl 24) or (bottomTint and 0x00FFFFFF))
        }

        fun computeDirectionalRimAlphas(): Triple<Float, Float, Float> {
            return Triple(RIM_ALPHA_UPPER_LEFT, RIM_ALPHA_MID, RIM_ALPHA_LOWER_RIGHT)
        }

        fun computeRelativeLuminance(argb: Int): Double {
            fun linearize(channel: Int): Double {
                val c = (channel and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            val r = linearize(argb ushr 16)
            val g = linearize(argb ushr 8)
            val b = linearize(argb)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        fun computeContrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double {
            val l1 = computeRelativeLuminance(foregroundArgb)
            val l2 = computeRelativeLuminance(backgroundArgb)
            val lighter = maxOf(l1, l2)
            val darker = minOf(l1, l2)
            return (lighter + 0.05) / (darker + 0.05)
        }

        private fun blendDarkGlass(
            sourceA: Int,
            sourceB: Int,
            bleedWeight: Float,
            maxChannel: Int
        ): Int {
            val baseR = (GLASS_BASE_DARK ushr 16) and 0xFF
            val baseG = (GLASS_BASE_DARK ushr 8) and 0xFF
            val baseB = GLASS_BASE_DARK and 0xFF

            val aR = (((sourceA ushr 16) and 0xFF) * 0.6f + ((sourceB ushr 16) and 0xFF) * 0.4f)
            val aG = (((sourceA ushr 8) and 0xFF) * 0.6f + ((sourceB ushr 8) and 0xFF) * 0.4f)
            val aB = ((sourceA and 0xFF) * 0.6f + (sourceB and 0xFF) * 0.4f)

            val w = bleedWeight.coerceIn(0f, 0.45f)
            val inv = 1f - w
            val r = (baseR * inv + aR * w).roundToInt().coerceIn(0, maxChannel)
            val g = (baseG * inv + aG * w).roundToInt().coerceIn(0, maxChannel)
            val b = (baseB * inv + aB * w).roundToInt().coerceIn(0, maxChannel)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_PINNED) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, LagukuPillWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                onUpdate(appContext, manager, ids)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = cachedState
        val appContext = context.applicationContext
        val density = appContext.resources.displayMetrics.density
        var needsAsyncBackdrop = false
        for (appWidgetId in appWidgetIds) {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_MIN_WIDTH_DP)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_MIN_HEIGHT_DP)
            val (bmpW, bmpH) = WidgetOpticsMath.computeBackdropSizePx(minWidthDp, minHeightDp, density)
            val key = BackdropCacheKey(bmpW, bmpH, state.dominantColor, state.isPlaying)
            val cachedBmp = backdropCache.get(key)?.takeIf { !it.isRecycled }
            if (cachedBmp == null) needsAsyncBackdrop = true
            applyWidgetUpdate(appContext, appWidgetManager, appWidgetId, state, cachedBmp, options)
        }
        if (needsAsyncBackdrop) {
            val idsCopy = appWidgetIds.copyOf()
            widgetExecutor.execute {
                for (id in idsCopy) {
                    renderAndApplyForWidgetId(appContext, appWidgetManager, id, cachedState)
                }
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val appContext = context.applicationContext
        val state = cachedState
        val density = appContext.resources.displayMetrics.density
        val minWidthDp = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_MIN_WIDTH_DP)
        val minHeightDp = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_MIN_HEIGHT_DP)
        val (bmpW, bmpH) = WidgetOpticsMath.computeBackdropSizePx(minWidthDp, minHeightDp, density)
        val key = BackdropCacheKey(bmpW, bmpH, state.dominantColor, state.isPlaying)
        val cachedBmp = backdropCache.get(key)?.takeIf { !it.isRecycled }
        applyWidgetUpdate(appContext, appWidgetManager, appWidgetId, state, cachedBmp, newOptions)
        if (cachedBmp == null) {
            widgetExecutor.execute {
                renderAndApplyForWidgetId(appContext, appWidgetManager, appWidgetId, cachedState, newOptions)
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        widgetExecutor.execute {
            for (bmp in backdropCache.clear()) {
                if (!bmp.isRecycled) bmp.recycle()
            }
            pendingRecycleBackdrop?.takeIf { !it.isRecycled }?.recycle()
            pendingRecycleBackdrop = null
            pendingRecycleBlurred?.takeIf { !it.isRecycled }?.recycle()
            pendingRecycleBlurred = null
        }
    }

    private data class CachedWidgetState(
        val track: Track? = null,
        val isPlaying: Boolean = false,
        val artworkPath: String? = null,
        val artworkIdentity: Int = 0,
        val palette: CoverPalette = CoverPaletteExtractor.defaultPalette(),
        val dominantColor: Int = WidgetOpticsMath.NEUTRAL_IDLE_COLOR,
        val blurred64: Bitmap? = null,
        val roundedArtworkBitmap: Bitmap? = null
    )

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "com.laguku.player.EXTRA_OPEN_NOW_PLAYING"
        const val ACTION_WIDGET_PINNED = "com.laguku.player.action.WIDGET_PINNED"

        private const val ARTWORK_SIZE_PX = 128
        private const val STACK_BLUR_RADIUS = 12

        private const val DEFAULT_MIN_WIDTH_DP = 250
        private const val DEFAULT_MIN_HEIGHT_DP = 56

        private const val REQ_OPEN_NOW_PLAYING = 200
        private const val REQ_PREV = 201
        private const val REQ_PLAY_PAUSE = 202
        private const val REQ_NEXT = 203
        private const val REQ_PIN_WIDGET = 204

        private const val SHEEN_TOP_COLOR = 0x36FFFFFF
        private const val SHEEN_BOTTOM_COLOR = 0x00FFFFFF
        private const val DEPTH_TOP_COLOR = 0x00000000
        private const val DEPTH_BOTTOM_COLOR = 0x28000000

        private val widgetExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "LagukuWidgetWorker").apply { isDaemon = true }
        }

        private val backdropCache = BackdropBitmapCache<Bitmap>(maxEntries = 2)

        @Volatile
        private var cachedState: CachedWidgetState = CachedWidgetState()
        private var pendingRecycleBackdrop: Bitmap? = null
        private var pendingRecycleBlurred: Bitmap? = null
        private var pendingRecycleArtwork: Bitmap? = null

        /**
         * Resolves adaptive layout mode from launcher-supplied widget dimensions in dp.
         */
        fun resolveLayoutMode(minWidthDp: Int, minHeightDp: Int): WidgetLayoutMode {
            return when {
                minHeightDp >= 110 -> WidgetLayoutMode.EXPANDED
                minHeightDp < 110 && minWidthDp < 220 -> WidgetLayoutMode.COMPACT
                else -> WidgetLayoutMode.STANDARD
            }
        }

        fun resolvePinRequestStatus(
            isPinSupported: Boolean,
            requestAccepted: Boolean
        ): PinWidgetStatus {
            return if (isPinSupported && requestAccepted) {
                PinWidgetStatus.PIN_REQUEST_SENT
            } else {
                PinWidgetStatus.UNSUPPORTED_USE_MANUAL_GUIDE
            }
        }

        fun resolvePinFeedbackRes(status: PinWidgetStatus): Int {
            return when (status) {
                PinWidgetStatus.PIN_REQUEST_SENT -> R.string.pin_widget_requested
                PinWidgetStatus.UNSUPPORTED_USE_MANUAL_GUIDE -> R.string.pin_widget_unsupported
            }
        }

        /**
         * Requests the launcher to pin the Laguku Pill widget onto the home screen
         * with an immutable confirmation broadcast PendingIntent.
         */
        fun requestPinWidgetStatus(context: Context): PinWidgetStatus {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val isSupported = runCatching { manager.isRequestPinAppWidgetSupported }.getOrDefault(false)
            if (!isSupported) return PinWidgetStatus.UNSUPPORTED_USE_MANUAL_GUIDE
            val provider = ComponentName(appContext, LagukuPillWidgetProvider::class.java)
            val callbackIntent = Intent(appContext, LagukuPillWidgetProvider::class.java)
                .setAction(ACTION_WIDGET_PINNED)
            val successCallback = PendingIntent.getBroadcast(
                appContext,
                REQ_PIN_WIDGET,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val accepted = runCatching {
                manager.requestPinAppWidget(provider, null, successCallback)
            }.getOrDefault(false)
            return resolvePinRequestStatus(isSupported, accepted)
        }

        fun requestPinWidget(context: Context): Boolean {
            return requestPinWidgetStatus(context) == PinWidgetStatus.PIN_REQUEST_SENT
        }

        /**
         * Event-driven entry point invoked by [PlaybackService] on track, state, quality,
         * queue-completion, or artwork-decode transitions. Never blocks the calling thread.
         */
        fun updateAllWidgets(
            context: Context,
            track: Track?,
            isPlaying: Boolean,
            artworkBitmap: Bitmap?
        ) {
            val appContext = context.applicationContext
            val artworkRef = if (artworkBitmap != null && !artworkBitmap.isRecycled) artworkBitmap else null
            val artworkId = artworkRef?.let { System.identityHashCode(it) } ?: 0
            val trackPath = track?.path

            widgetExecutor.execute {
                val appWidgetManager = AppWidgetManager.getInstance(appContext)
                val componentName = ComponentName(appContext, LagukuPillWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

                val prev = cachedState
                val sameArtwork = artworkRef != null &&
                    trackPath != null &&
                    trackPath == prev.artworkPath &&
                    artworkId == prev.artworkIdentity &&
                    prev.blurred64 != null &&
                    !prev.blurred64.isRecycled &&
                    prev.roundedArtworkBitmap != null &&
                    !prev.roundedArtworkBitmap.isRecycled

                val palette: CoverPalette
                val dominantColor: Int
                val blurred64: Bitmap?
                val roundedArt: Bitmap?
                if (sameArtwork) {
                    palette = prev.palette
                    dominantColor = prev.dominantColor
                    blurred64 = prev.blurred64
                    roundedArt = prev.roundedArtworkBitmap
                } else if (artworkRef != null && !artworkRef.isRecycled) {
                    palette = CoverPaletteExtractor.extract(artworkRef)
                    dominantColor = palette.glassTint
                    blurred64 = StackBlur.createBlurredBackdrop(artworkRef, radius = STACK_BLUR_RADIUS)
                    val density = appContext.resources.displayMetrics.density
                    val cornerPx = WidgetOpticsMath.computeConcentricArtworkRadiusPx(
                        artworkSizePx = ARTWORK_SIZE_PX,
                        capsuleHeightDp = DEFAULT_MIN_HEIGHT_DP - WidgetOpticsMath.OUTER_MARGIN_DP * 2f
                    )
                    roundedArt = createRoundedArtworkBitmap(artworkRef, cornerPx, density)
                } else {
                    palette = CoverPaletteExtractor.defaultPalette()
                    dominantColor = WidgetOpticsMath.NEUTRAL_IDLE_COLOR
                    blurred64 = null
                    roundedArt = null
                }

                val nextState = CachedWidgetState(
                    track = track,
                    isPlaying = isPlaying,
                    artworkPath = trackPath,
                    artworkIdentity = artworkId,
                    palette = palette,
                    dominantColor = dominantColor,
                    blurred64 = blurred64,
                    roundedArtworkBitmap = roundedArt
                )
                cachedState = nextState

                if (appWidgetIds.isNotEmpty()) {
                    for (appWidgetId in appWidgetIds) {
                        renderAndApplyForWidgetId(appContext, appWidgetManager, appWidgetId, nextState)
                    }
                }

                if (!sameArtwork) {
                    recyclePreviousAfterCommit(prev.blurred64, prev.roundedArtworkBitmap)
                }
            }
        }

        private fun renderAndApplyForWidgetId(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: CachedWidgetState,
            overrideOptions: Bundle? = null
        ) {
            val options = overrideOptions ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_MIN_WIDTH_DP)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_MIN_HEIGHT_DP)
            val density = context.resources.displayMetrics.density
            val (bmpW, bmpH) = WidgetOpticsMath.computeBackdropSizePx(minWidthDp, minHeightDp, density)
            val key = BackdropCacheKey(bmpW, bmpH, state.dominantColor, state.isPlaying)

            var backdrop = backdropCache.get(key)?.takeIf { !it.isRecycled }
            var evicted: Bitmap? = null
            if (backdrop == null) {
                backdrop = composeGlassBackdropBitmap(
                    widthPx = bmpW,
                    heightPx = bmpH,
                    capsuleHeightDp = (minHeightDp - WidgetOpticsMath.OUTER_MARGIN_DP * 2f).coerceAtLeast(44f),
                    palette = state.palette,
                    dominantColor = state.dominantColor,
                    blurred64 = state.blurred64?.takeIf { !it.isRecycled },
                    isPlaying = state.isPlaying
                )
                evicted = backdropCache.put(key, backdrop)
            }

            applyWidgetUpdate(context, appWidgetManager, appWidgetId, state, backdrop, options)

            if (evicted != null && evicted !== backdrop) {
                val previousPending = pendingRecycleBackdrop
                pendingRecycleBackdrop = evicted
                if (previousPending != null && previousPending !== backdrop && !previousPending.isRecycled) {
                    previousPending.recycle()
                }
            }
        }

        private fun recyclePreviousAfterCommit(oldBlurred: Bitmap?, oldRoundedArt: Bitmap?) {
            val prevB = pendingRecycleBlurred
            pendingRecycleBlurred = oldBlurred
            if (prevB != null && prevB !== oldBlurred && !prevB.isRecycled) {
                prevB.recycle()
            }

            val prevArt = pendingRecycleArtwork
            pendingRecycleArtwork = oldRoundedArt
            if (prevArt != null && prevArt !== oldRoundedArt && !prevArt.isRecycled) {
                prevArt.recycle()
            }
        }

        private fun applyWidgetUpdate(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: CachedWidgetState,
            backdropBitmap: Bitmap?,
            overrideOptions: Bundle? = null
        ) {
            val options = overrideOptions ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_MIN_WIDTH_DP)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, DEFAULT_MIN_HEIGHT_DP)
            val mode = resolveLayoutMode(minWidthDp, minHeightDp)

            val views = RemoteViews(context.packageName, R.layout.widget_laguku_pill)
            val track = state.track

            if (track == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_idle_title))
                views.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_idle_subtitle))
                views.setTextViewText(R.id.widget_meta, "")
                views.setViewVisibility(R.id.widget_meta, View.GONE)
            } else {
                val title = track.title.ifBlank { context.getString(R.string.unknown_title) }
                val artist = track.artist.ifBlank { context.getString(R.string.unknown_artist) }
                val album = track.album.ifBlank { context.getString(R.string.unknown_album) }
                val qualityBadge = track.quality.formatBadge()
                val meta = if (qualityBadge.isNotBlank()) "$album \u2022 $qualityBadge" else album

                views.setTextViewText(R.id.widget_title, title)
                views.setTextViewText(R.id.widget_artist, artist)
                views.setTextViewText(R.id.widget_meta, meta)
                views.setViewVisibility(
                    R.id.widget_meta,
                    if (mode == WidgetLayoutMode.EXPANDED) View.VISIBLE else View.GONE
                )
            }

            views.setViewVisibility(
                R.id.widget_btn_prev,
                if (mode == WidgetLayoutMode.COMPACT) View.GONE else View.VISIBLE
            )

            views.setImageViewResource(
                R.id.widget_btn_play_pause,
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            val backdrop = backdropBitmap?.takeIf { !it.isRecycled }
            if (backdrop != null) {
                views.setInt(R.id.widget_capsule, "setBackgroundResource", 0)
                views.setViewVisibility(R.id.widget_backdrop, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_backdrop, backdrop)
            } else {
                views.setInt(R.id.widget_capsule, "setBackgroundResource", R.drawable.bg_widget_pill_glass)
                views.setViewVisibility(R.id.widget_backdrop, View.GONE)
            }

            val roundedArt = state.roundedArtworkBitmap?.takeIf { !it.isRecycled }
            if (roundedArt != null) {
                views.setImageViewBitmap(R.id.widget_artwork, roundedArt)
            } else {
                views.setImageViewResource(R.id.widget_artwork, R.drawable.ic_default_album_cover)
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_OPEN_NOW_PLAYING, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                REQ_OPEN_NOW_PLAYING,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_content_area, openPendingIntent)

            views.setOnClickPendingIntent(
                R.id.widget_btn_prev,
                createControlPendingIntent(
                    context = context,
                    action = PlaybackService.ACTION_PREVIOUS,
                    requestCode = REQ_PREV
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_play_pause,
                createControlPendingIntent(
                    context = context,
                    action = PlaybackService.ACTION_PLAY_PAUSE,
                    requestCode = REQ_PLAY_PAUSE
                )
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_next,
                createControlPendingIntent(
                    context = context,
                    action = PlaybackService.ACTION_NEXT,
                    requestCode = REQ_NEXT
                )
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun createControlPendingIntent(
            context: Context,
            action: String,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(context, PlaybackService::class.java).setAction(action)
            return PendingIntent.getForegroundService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Renders the 4-layer Liquid Glass capsule offscreen bitmap at exact widget aspect ratio:
         * 1. Ambient Color Bleed & Tint (underlay + high-contrast translucent gradient)
         * 2. Top Specular Sheen & Bottom Optical Depth
         * 3. Bezel Edge Refraction Hint (~10dp inward perimeter band, centerWarp = false)
         * 4. 135-degree Directional Perimeter Rim Light (0.65 -> 0.10 -> 0.35 alpha at 1.0dp)
         */
        private fun composeGlassBackdropBitmap(
            widthPx: Int,
            heightPx: Int,
            capsuleHeightDp: Float,
            palette: CoverPalette,
            dominantColor: Int,
            blurred64: Bitmap?,
            isPlaying: Boolean
        ): Bitmap {
            val output = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val pxPerDp = (heightPx / capsuleHeightDp.coerceAtLeast(1f)).coerceAtLeast(1f)
            val aaInset = (0.75f * pxPerDp).coerceIn(1f, 2f)
            val destRect = RectF(aaInset, aaInset, widthPx - aaInset, heightPx - aaInset)
            val capsuleRadius = destRect.height() * 0.5f

            val clipPath = Path().apply {
                addRoundRect(destRect, capsuleRadius, capsuleRadius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)

            // Layer 1: Ambient Color Bleed & Translucent Tint
            if (blurred64 != null && !blurred64.isRecycled) {
                val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    alpha = 64
                }
                canvas.drawBitmap(blurred64, null, destRect, blurPaint)
            }
            val (topAmbient, bottomAmbient) = WidgetOpticsMath.computeAmbientTintColors(
                dominantColor = dominantColor,
                accentGlow = palette.accentGlow,
                isLightBackdrop = false
            )
            val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    destRect.left,
                    destRect.top,
                    destRect.right,
                    destRect.bottom,
                    topAmbient,
                    bottomAmbient,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(destRect, ambientPaint)

            // Layer 2: Top Specular Sheen & Bottom Optical Depth
            val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f,
                    destRect.top,
                    0f,
                    destRect.top + destRect.height() * 0.46f,
                    SHEEN_TOP_COLOR,
                    SHEEN_BOTTOM_COLOR,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(destRect, sheenPaint)

            val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                shader = LinearGradient(
                    0f,
                    destRect.top + destRect.height() * 0.58f,
                    0f,
                    destRect.bottom,
                    DEPTH_TOP_COLOR,
                    DEPTH_BOTTOM_COLOR,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(destRect, depthPaint)

            // Layer 3: Bezel Edge Refraction Hint (~10dp rim band, center text untouched)
            val bezelBandPx = (WidgetOpticsMath.BEZEL_HINT_DP * pxPerDp).coerceIn(6f, capsuleRadius * 0.48f)
            val bezelStrokeRect = RectF(
                destRect.left + bezelBandPx * 0.5f,
                destRect.top + bezelBandPx * 0.5f,
                destRect.right - bezelBandPx * 0.5f,
                destRect.bottom - bezelBandPx * 0.5f
            )
            val bezelRadius = (capsuleRadius - bezelBandPx * 0.5f).coerceAtLeast(0f)
            val bezelAlpha = if (isPlaying) 0x22 else 0x18
            val bezelColors = intArrayOf(
                (bezelAlpha shl 24) or (palette.accentGlow and 0x00FFFFFF),
                0x06FFFFFF,
                (0x14 shl 24) or 0x00FFFFFF
            )
            val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bezelBandPx
                shader = LinearGradient(
                    destRect.left,
                    destRect.top,
                    destRect.right,
                    destRect.bottom,
                    bezelColors,
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(bezelStrokeRect, bezelRadius, bezelRadius, bezelPaint)

            // Layer 4: 135-degree Directional Perimeter Rim Light (1.0dp optical hairline)
            val rimStrokePx = (1.0f * pxPerDp).coerceIn(1f, 2.5f)
            val halfRim = rimStrokePx * 0.5f
            val rimRect = RectF(
                destRect.left + halfRim,
                destRect.top + halfRim,
                destRect.right - halfRim,
                destRect.bottom - halfRim
            )
            val rimRadius = (capsuleRadius - halfRim).coerceAtLeast(0f)
            val (ulAlpha, midAlpha, lrAlpha) = WidgetOpticsMath.computeDirectionalRimAlphas()
            val rimColors = intArrayOf(
                ((ulAlpha * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF,
                ((midAlpha * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF,
                ((lrAlpha * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
            )
            val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = rimStrokePx
                shader = LinearGradient(
                    destRect.left,
                    destRect.top,
                    destRect.right,
                    destRect.bottom,
                    rimColors,
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint)
            canvas.restore()

            return output
        }

        private fun createRoundedArtworkBitmap(
            source: Bitmap,
            cornerRadiusPx: Float,
            density: Float
        ): Bitmap {
            val output = Bitmap.createBitmap(ARTWORK_SIZE_PX, ARTWORK_SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            val srcW = source.width.toFloat().coerceAtLeast(1f)
            val srcH = source.height.toFloat().coerceAtLeast(1f)
            val scale = maxOf(ARTWORK_SIZE_PX / srcW, ARTWORK_SIZE_PX / srcH)
            val dx = (ARTWORK_SIZE_PX - srcW * scale) * 0.5f
            val dy = (ARTWORK_SIZE_PX - srcH * scale) * 0.5f

            val shaderMatrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(shaderMatrix)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                this.shader = shader
            }
            val rect = RectF(0f, 0f, ARTWORK_SIZE_PX.toFloat(), ARTWORK_SIZE_PX.toFloat())
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)

            // 0.75dp optical rim matching 44dp widget artwork scale
            val strokePx = (0.75f * (ARTWORK_SIZE_PX / (44f * density.coerceAtLeast(1f))) * density.coerceAtLeast(1f))
                .coerceIn(1.2f, 3f)
            val halfStroke = strokePx * 0.5f
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = strokePx
                color = 0x3DFFFFFF
            }
            val borderRect = RectF(
                halfStroke,
                halfStroke,
                ARTWORK_SIZE_PX - halfStroke,
                ARTWORK_SIZE_PX - halfStroke
            )
            canvas.drawRoundRect(
                borderRect,
                (cornerRadiusPx - halfStroke).coerceAtLeast(0f),
                (cornerRadiusPx - halfStroke).coerceAtLeast(0f),
                borderPaint
            )

            return output
        }
    }
}
