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
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.laguku.player.MainActivity
import com.laguku.player.R
import com.laguku.player.model.Track
import com.laguku.player.service.PlaybackService
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.StackBlur
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Resizable Liquid Glass home-screen pill widget for Laguku.
 *
 * Updates strictly on playback events pushed from [PlaybackService] with zero polling timers.
 * All bitmap blurring and palette composition runs off the main thread on a dedicated
 * single-thread executor.
 */
class LagukuPillWidgetProvider : AppWidgetProvider() {

    enum class WidgetLayoutMode {
        COMPACT,
        STANDARD,
        EXPANDED
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = cachedState
        val appContext = context.applicationContext
        for (appWidgetId in appWidgetIds) {
            applyWidgetUpdate(appContext, appWidgetManager, appWidgetId, state)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        applyWidgetUpdate(
            context.applicationContext,
            appWidgetManager,
            appWidgetId,
            cachedState,
            overrideOptions = newOptions
        )
    }

    private data class CachedWidgetState(
        val track: Track? = null,
        val isPlaying: Boolean = false,
        val artworkPath: String? = null,
        val artworkIdentity: Int = 0,
        val backdropBitmap: Bitmap? = null,
        val roundedArtworkBitmap: Bitmap? = null
    )

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "com.laguku.player.EXTRA_OPEN_NOW_PLAYING"

        private const val BACKDROP_MAX_WIDTH = 320
        private const val BACKDROP_MAX_HEIGHT = 120
        private const val ARTWORK_SIZE_PX = 128
        private const val ARTWORK_CORNER_RADIUS_PX = 24f
        private const val STACK_BLUR_RADIUS = 12

        private const val DEFAULT_MIN_WIDTH_DP = 220
        private const val DEFAULT_MIN_HEIGHT_DP = 64

        private const val REQ_OPEN_NOW_PLAYING = 200
        private const val REQ_PREV = 201
        private const val REQ_PLAY_PAUSE = 202
        private const val REQ_NEXT = 203

        private const val SHEEN_TOP_COLOR = 0x22FFFFFF
        private const val SHEEN_BOTTOM_COLOR = 0x00FFFFFF
        private const val RIM_TOP_LEFT_COLOR = 0x57FFFFFF
        private const val RIM_BOTTOM_RIGHT_COLOR = 0x0DFFFFFF

        private val widgetExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "LagukuWidgetWorker").apply { isDaemon = true }
        }

        @Volatile
        private var cachedState: CachedWidgetState = CachedWidgetState()

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

        /**
         * Requests the launcher to pin the Laguku Pill widget onto the home screen.
         * Returns false when the active launcher does not support automatic pinning.
         */
        fun requestPinWidget(context: Context): Boolean {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(appContext, LagukuPillWidgetProvider::class.java)
            return manager.requestPinAppWidget(provider, null, null)
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
                if (appWidgetIds.isEmpty()) {
                    cachedState = CachedWidgetState(
                        track = track,
                        isPlaying = isPlaying
                    )
                    return@execute
                }

                val sameArtwork = artworkRef != null &&
                    trackPath != null &&
                    trackPath == prev.artworkPath &&
                    artworkId == prev.artworkIdentity &&
                    prev.backdropBitmap != null &&
                    !prev.backdropBitmap.isRecycled &&
                    prev.roundedArtworkBitmap != null &&
                    !prev.roundedArtworkBitmap.isRecycled

                val backdrop: Bitmap?
                val roundedArt: Bitmap?
                if (sameArtwork) {
                    backdrop = prev.backdropBitmap
                    roundedArt = prev.roundedArtworkBitmap
                } else if (artworkRef != null && !artworkRef.isRecycled) {
                    val density = appContext.resources.displayMetrics.density
                    backdrop = composeGlassBackdropBitmap(artworkRef, density)
                    roundedArt = createRoundedArtworkBitmap(artworkRef)
                } else {
                    backdrop = null
                    roundedArt = null
                }

                val nextState = CachedWidgetState(
                    track = track,
                    isPlaying = isPlaying,
                    artworkPath = trackPath,
                    artworkIdentity = artworkId,
                    backdropBitmap = backdrop,
                    roundedArtworkBitmap = roundedArt
                )
                cachedState = nextState

                for (appWidgetId in appWidgetIds) {
                    applyWidgetUpdate(appContext, appWidgetManager, appWidgetId, nextState)
                }
            }
        }

        private fun applyWidgetUpdate(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            state: CachedWidgetState,
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

            val backdrop = state.backdropBitmap?.takeIf { !it.isRecycled }
            if (backdrop != null) {
                views.setViewVisibility(R.id.widget_backdrop, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_backdrop, backdrop)
            } else {
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
         * Composes a lightweight (<= 320x120) Liquid Glass backdrop bitmap combining:
         * - 64x64 2-pass StackBlur (radius 12) + #7A080A0F scrim
         * - CoverPaletteExtractor deepBackdrop / accentGlow translucent layers
         * - Top-edge glass sheen and 1.2dp diagonal specular rim highlight
         */
        private fun composeGlassBackdropBitmap(artwork: Bitmap, density: Float): Bitmap {
            val blurred64 = StackBlur.createBlurredBackdrop(artwork, radius = STACK_BLUR_RADIUS)
            val palette = CoverPaletteExtractor.extract(artwork)

            val output = Bitmap.createBitmap(
                BACKDROP_MAX_WIDTH,
                BACKDROP_MAX_HEIGHT,
                Bitmap.Config.ARGB_8888
            )
            try {
                val canvas = Canvas(output)
                val destRect = RectF(0f, 0f, BACKDROP_MAX_WIDTH.toFloat(), BACKDROP_MAX_HEIGHT.toFloat())
                val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(blurred64, null, destRect, bitmapPaint)

                // Match LiquidGlassDrawable layered fill: dark base + subtle accent tint
                val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = (0x73 shl 24) or (palette.deepBackdrop and 0x00FFFFFF)
                }
                canvas.drawRect(destRect, basePaint)

                val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = (0x24 shl 24) or (palette.accentGlow and 0x00FFFFFF)
                }
                canvas.drawRect(destRect, accentPaint)

                val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = LinearGradient(
                        0f,
                        0f,
                        0f,
                        BACKDROP_MAX_HEIGHT * 0.45f,
                        SHEEN_TOP_COLOR,
                        SHEEN_BOTTOM_COLOR,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(destRect, sheenPaint)

                val strokePx = (1.2f * density).coerceAtLeast(1f)
                val halfStroke = strokePx * 0.5f
                val rimRect = RectF(
                    halfStroke,
                    halfStroke,
                    BACKDROP_MAX_WIDTH - halfStroke,
                    BACKDROP_MAX_HEIGHT - halfStroke
                )
                val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = strokePx
                    shader = LinearGradient(
                        0f,
                        0f,
                        BACKDROP_MAX_WIDTH.toFloat(),
                        BACKDROP_MAX_HEIGHT.toFloat(),
                        RIM_TOP_LEFT_COLOR,
                        RIM_BOTTOM_RIGHT_COLOR,
                        Shader.TileMode.CLAMP
                    )
                }
                val cornerRadius = (BACKDROP_MAX_HEIGHT * 0.43f)
                canvas.drawRoundRect(rimRect, cornerRadius, cornerRadius, rimPaint)
            } finally {
                blurred64.recycle()
            }
            return output
        }

        private fun createRoundedArtworkBitmap(source: Bitmap): Bitmap {
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
            canvas.drawRoundRect(rect, ARTWORK_CORNER_RADIUS_PX, ARTWORK_CORNER_RADIUS_PX, paint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                color = 0x33FFFFFF
            }
            val borderRect = RectF(0.75f, 0.75f, ARTWORK_SIZE_PX - 0.75f, ARTWORK_SIZE_PX - 0.75f)
            canvas.drawRoundRect(
                borderRect,
                ARTWORK_CORNER_RADIUS_PX - 0.75f,
                ARTWORK_CORNER_RADIUS_PX - 0.75f,
                borderPaint
            )

            return output
        }
    }
}
