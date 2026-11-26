package com.laguku.player.ui.library

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.laguku.player.R
import com.laguku.player.data.ListeningInsights
import com.laguku.player.model.CoverPalette
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.LiquidGlassDrawable
import com.laguku.player.ui.motion.SpringAnimatorHelper

/**
 * Offline Listening Insights bottom sheet displaying total listening time,
 * Top 5 tracks, and Top 5 artists.
 */
@SuppressLint("ViewConstructor")
class InsightsSheetView(
    context: Context,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private var palette: CoverPalette = CoverPaletteExtractor.defaultPalette()

    private val sheetCard = LinearLayout(context)
    private val sheetGlass = LiquidGlassDrawable(context, 24f)
    private val bodyContainer = LinearLayout(context)

    init {
        setBackgroundColor(0x9904060A.toInt())
        isClickable = true
        setOnClickListener { onDismiss() }
        buildUi()
        applyPalette(palette)
    }

    fun applyBottomInset(bottomPx: Int) {
        sheetCard.setPadding(dp(18), dp(18), dp(18), dp(18) + bottomPx)
    }

    fun setPalette(newPalette: CoverPalette) {
        palette = newPalette
        applyPalette(newPalette)
    }

    fun bindInsights(insights: ListeningInsights) {
        bodyContainer.removeAllViews()

        val totalMinutes = insights.totalListenedMs / 60_000L
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        val totalText = if (hours > 0L) "$hours jam $mins menit" else "$mins menit"

        val heroCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = LiquidGlassDrawable(context, 18f, palette).apply {
                setTintProfile(0.45f, 0.35f, preferAccentGlow = true)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        heroCard.addView(TextView(context).apply {
            text = "Total Waktu Dengar Offline"
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        heroCard.addView(TextView(context).apply {
            text = totalText
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            fontFeatureSettings = "tnum"
        })
        bodyContainer.addView(heroCard, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        })

        addSectionTitle("Top 5 Lagu")
        if (insights.topTracks.isEmpty()) {
            addMutedLine("Putar lagu minimal 30 detik untuk mencatat statistik.")
        } else {
            insights.topTracks.forEachIndexed { idx, entry ->
                addRankRow("${idx + 1}. ${entry.title}", "${entry.subtitle} \u2022 ${entry.playCount}x")
            }
        }

        addSectionTitle("Top 5 Artis")
        if (insights.topArtists.isEmpty()) {
            addMutedLine("Belum ada data artis.")
        } else {
            insights.topArtists.forEachIndexed { idx, entry ->
                addRankRow("${idx + 1}. ${entry.title}", "${entry.playCount} putar")
            }
        }
    }

    private fun addSectionTitle(textStr: String) {
        bodyContainer.addView(TextView(context).apply {
            text = textStr
            setTextColor(palette.accentGlow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(6))
        })
    }

    private fun addMutedLine(textStr: String) {
        bodyContainer.addView(TextView(context).apply {
            text = textStr
            setTextColor(0x88FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun addRankRow(primary: String, secondary: String) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = LiquidGlassDrawable(context, 14f, palette)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        row.addView(TextView(context).apply {
            text = primary
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(TextView(context).apply {
            text = secondary
            setTextColor(0xA3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        bodyContainer.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
    }

    private fun buildUi() {
        sheetCard.apply {
            orientation = LinearLayout.VERTICAL
            background = sheetGlass
            setPadding(dp(18), dp(18), dp(18), dp(18))
            elevation = 20f * density
            isClickable = true
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = context.getString(R.string.insights_title)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val closeBtn = TextView(context).apply {
            text = context.getString(R.string.eq_close)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER
            background = LiquidGlassDrawable(context, 14f, palette)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onDismiss() }
        }
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(closeBtn)

        bodyContainer.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(context).apply {
            addView(bodyContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.50f).toInt()
        sheetCard.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        sheetCard.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, maxH))
        addView(sheetCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun applyPalette(newPalette: CoverPalette) {
        sheetGlass.setPalette(newPalette)
        sheetGlass.setTintProfile(baseFraction = 0.88f, accentFraction = 0.14f)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
