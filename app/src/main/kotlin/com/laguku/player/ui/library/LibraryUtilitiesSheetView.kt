package com.laguku.player.ui.library

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.laguku.player.R
import com.laguku.player.model.CoverPalette
import com.laguku.player.service.ClickGesture
import com.laguku.player.service.MediaAction
import com.laguku.player.service.MorningAlarmConfig
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.LiquidGlassDrawable
import com.laguku.player.ui.motion.SpringAnimatorHelper

/**
 * Progressive-disclosure bottom sheet grouping all collection & automation utilities:
 * Listening Insights, Min-Duration Filter (<30s), Library Health Scanner,
 * TWS Multi-Click Mapper, Morning Fade-In Alarm, Tag Editor, Backup/Restore JSON,
 * and Blacklisted Folders management.
 */
@SuppressLint("ViewConstructor")
class LibraryUtilitiesSheetView(
    context: Context,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    interface Callbacks {
        fun onOpenInsights()
        fun onToggleMinDurationFilter(enabled: Boolean)
        fun onRunHealthScan()
        fun onCycleTwsMapping(gesture: ClickGesture): MediaAction
        fun onAlarmChanged(config: MorningAlarmConfig)
        fun onEditCurrentTrackTags()
        fun onExportBackupJson()
        fun onImportBackupJson()
        fun onUnhideBlacklistedFolder(folderPath: String)
        fun onPinWidgetToHomeScreen()
    }

    var callbacks: Callbacks? = null

    private val density = resources.displayMetrics.density
    private var palette: CoverPalette = CoverPaletteExtractor.defaultPalette()

    private val sheetCard = LinearLayout(context)
    private val sheetGlass = LiquidGlassDrawable(context, 24f)
    private val bodyContainer = LinearLayout(context)
    private var insightsActionBtn: TextView? = null

    init {
        setBackgroundColor(0x9904060A.toInt())
        isClickable = true
        setOnClickListener { onDismiss() }
        buildUi()
        applyPalette(palette)
    }

    fun getInsightsAnchorView(): View? = insightsActionBtn

    fun applyBottomInset(bottomPx: Int) {
        sheetCard.setPadding(dp(16), dp(16), dp(16), dp(16) + bottomPx)
    }

    fun setPalette(newPalette: CoverPalette) {
        palette = newPalette
        applyPalette(newPalette)
    }

    fun bindState(
        minDurationEnabled: Boolean,
        alarmConfig: MorningAlarmConfig,
        twsMappings: Map<ClickGesture, MediaAction>,
        blacklistedFolders: Set<String>
    ) {
        bodyContainer.removeAllViews()

        insightsActionBtn = addActionButton("Statistik Dengar Offline (Insights)") {
            onDismiss()
            callbacks?.onOpenInsights()
        }
        addActionButton("Edit Tag Lagu Aktif (FLAC / MP3 / M4A)") {
            onDismiss()
            callbacks?.onEditCurrentTrackTags()
        }
        addActionButton("Pindai Kesehatan Koleksi (Duplikat & Korup)") {
            callbacks?.onRunHealthScan()
        }
        addActionButton(context.getString(R.string.pin_widget_label)) {
            callbacks?.onPinWidgetToHomeScreen()
        }

        addToggleRow("Sembunyikan Audio < 30 Detik", minDurationEnabled) { checked ->
            callbacks?.onToggleMinDurationFilter(checked)
        }

        // Morning Alarm
        addSectionTitle("Alarm Pagi Fade-In 30 Detik")
        var currentAlarm = alarmConfig
        addToggleRow("Aktifkan Alarm Pagi (${formatTime(currentAlarm.hour, currentAlarm.minute)})", currentAlarm.enabled) { checked ->
            currentAlarm = currentAlarm.copy(enabled = checked)
            callbacks?.onAlarmChanged(currentAlarm)
        }
        val alarmSteppers = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val timeTv = TextView(context).apply {
            text = formatTime(currentAlarm.hour, currentAlarm.minute)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            fontFeatureSettings = "tnum"
            gravity = Gravity.CENTER
        }
        alarmSteppers.addView(createCompactBtn("-1 Jam") {
            currentAlarm = currentAlarm.copy(hour = (currentAlarm.hour + 23) % 24)
            timeTv.text = formatTime(currentAlarm.hour, currentAlarm.minute)
            callbacks?.onAlarmChanged(currentAlarm)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        alarmSteppers.addView(createCompactBtn("+1 Jam") {
            currentAlarm = currentAlarm.copy(hour = (currentAlarm.hour + 1) % 24)
            timeTv.text = formatTime(currentAlarm.hour, currentAlarm.minute)
            callbacks?.onAlarmChanged(currentAlarm)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        alarmSteppers.addView(timeTv, LinearLayout.LayoutParams(dp(64), dp(44)))
        alarmSteppers.addView(createCompactBtn("+15m") {
            currentAlarm = currentAlarm.copy(minute = (currentAlarm.minute + 15) % 60)
            timeTv.text = formatTime(currentAlarm.hour, currentAlarm.minute)
            callbacks?.onAlarmChanged(currentAlarm)
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
        bodyContainer.addView(alarmSteppers, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        // TWS Click Mapper
        addSectionTitle("Ketukan Multi-Klik TWS")
        val gestureLabels = listOf(
            ClickGesture.SINGLE to "1x Ketuk",
            ClickGesture.DOUBLE to "2x Ketuk",
            ClickGesture.TRIPLE to "3x Ketuk",
            ClickGesture.LONG_PRESS to "Tekan Tahan"
        )
        for ((gesture, gLabel) in gestureLabels) {
            val actionName = twsMappings[gesture]?.name ?: MediaAction.NONE.name
            val btn = createCompactBtn("$gLabel \u2192 $actionName") {}
            btn.setOnClickListener {
                val next = callbacks?.onCycleTwsMapping(gesture) ?: return@setOnClickListener
                btn.text = "$gLabel \u2192 ${next.name}"
            }
            bodyContainer.addView(btn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
                bottomMargin = dp(6)
            })
        }

        // Backup & Restore JSON
        addSectionTitle("Cadangan 1-Berkas JSON")
        val backupRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        backupRow.addView(createCompactBtn("Ekspor JSON") { callbacks?.onExportBackupJson() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        backupRow.addView(createCompactBtn("Impor JSON") { callbacks?.onImportBackupJson() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        bodyContainer.addView(backupRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        // Blacklisted folders
        if (blacklistedFolders.isNotEmpty()) {
            addSectionTitle("Folder Disembunyikan (Ketuk untuk Pulihkan)")
            for (folder in blacklistedFolders) {
                val btn = createCompactBtn("Pulihkan: $folder") {}
                btn.setOnClickListener {
                    callbacks?.onUnhideBlacklistedFolder(folder)
                    bodyContainer.removeView(btn)
                }
                bodyContainer.addView(btn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
                    bottomMargin = dp(6)
                })
            }
        }
    }

    private fun buildUi() {
        sheetCard.apply {
            orientation = LinearLayout.VERTICAL
            background = sheetGlass
            setPadding(dp(16), dp(16), dp(16), dp(18))
            elevation = 20f * density
            isClickable = true
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = context.getString(R.string.utilities_title)
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
        val maxH = (resources.displayMetrics.heightPixels * 0.54f).toInt()
        sheetCard.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        sheetCard.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, maxH))
        addView(sheetCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun addSectionTitle(label: String) {
        bodyContainer.addView(TextView(context).apply {
            text = label
            setTextColor(palette.accentGlow)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(6))
        })
    }

    private fun addActionButton(label: String, onClick: () -> Unit): TextView {
        val btn = createCompactBtn(label, onClick)
        bodyContainer.addView(btn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(8)
        })
        return btn
    }

    private fun createCompactBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minimumHeight = dp(44)
            background = LiquidGlassDrawable(context, 16f, palette)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onClick() }
        }
    }

    private fun addToggleRow(label: String, initial: Boolean, onChanged: (Boolean) -> Unit) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(4), 0, dp(4))
        }
        val tv = TextView(context).apply {
            text = label
            setTextColor(0xE6FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        val sw = Switch(context).apply {
            isChecked = initial
            thumbTintList = ColorStateList.valueOf(palette.accentGlow)
            trackTintList = ColorStateList.valueOf(0x44FFFFFF)
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        bodyContainer.addView(row)
    }

    private fun formatTime(hour: Int, minute: Int): String =
        "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    private fun applyPalette(newPalette: CoverPalette) {
        sheetGlass.setPalette(newPalette)
        sheetGlass.setTintProfile(baseFraction = 0.88f, accentFraction = 0.14f)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
