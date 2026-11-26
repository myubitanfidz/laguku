package com.laguku.player.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.laguku.player.R
import com.laguku.player.audio.AudioEngine
import com.laguku.player.audio.TenBandBiquadEq
import com.laguku.player.model.AudioDspConfig
import com.laguku.player.model.CoverPalette
import com.laguku.player.service.ActiveOutputRoute
import com.laguku.player.service.BluetoothSettings
import com.laguku.player.ui.glass.CoverPaletteExtractor
import com.laguku.player.ui.glass.LiquidGlassDrawable
import com.laguku.player.ui.motion.SpringAnimatorHelper
import kotlin.math.roundToInt

/**
 * Progressive-disclosure bottom-half Liquid Glass sheet consolidating all DSP,
 * Bluetooth intelligence, and tempo/loop controls into 3 clean capsule tabs:
 * 1) EQ 10-Band (+ active output route profile & presets)
 * 2) Akustik & Bluetooth (Crossfeed, Smart Loudness, Auto-Trim, Balance, Mono, Shuffle Crossfade,
 *    Sample Rate Passthrough, BT Latency Offset, BT Pre-Roll, Auto-Play / Auto-Pause TWS)
 * 3) Tempo & Loop (Speed 0.5x..2.0x, Pitch 0.5x..2.0x, AB-Loop, Chapter Markers)
 */
@SuppressLint("ViewConstructor")
class AudioLabSheetView(
    context: Context,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    enum class LabTab { EQ_10, ACOUSTICS_BT, TEMPO_LOOP }

    interface Callbacks {
        fun onDspConfigChanged(config: AudioDspConfig)
        fun onBluetoothSettingsChanged(settings: BluetoothSettings, deviceLatencyMs: Long)
        fun onClearAbLoop()
        fun onSetAbStart()
        fun onSetAbEnd()
        fun onJumpToChapter(startMs: Long)
    }

    var callbacks: Callbacks? = null

    private val density = resources.displayMetrics.density
    private var palette: CoverPalette = CoverPaletteExtractor.defaultPalette()
    private var activeTab: LabTab = LabTab.EQ_10

    private var dspConfig: AudioDspConfig = AudioDspConfig()
    private var btSettings: BluetoothSettings = BluetoothSettings()
    private var btDeviceLatencyMs: Long = -180L
    private var activeRoute: ActiveOutputRoute? = null
    private var audioEngine: AudioEngine? = null

    private val sheetCard = LinearLayout(context)
    private val sheetGlass = LiquidGlassDrawable(context, 24f)
    private val routeBanner = TextView(context)
    private val tabRow = LinearLayout(context)
    private val tabViews = ArrayList<Pair<LabTab, TextView>>(3)
    private val scrollView = ScrollView(context)
    private val bodyContainer = LinearLayout(context)

    init {
        setBackgroundColor(0x9904060A.toInt())
        isClickable = true
        setOnClickListener { onDismiss() }
        buildUi()
        applyPalette(palette)
    }

    fun applyBottomInset(bottomPx: Int) {
        sheetCard.setPadding(dp(16), dp(16), dp(16), dp(16) + bottomPx)
    }

    fun setPalette(newPalette: CoverPalette) {
        palette = newPalette
        applyPalette(newPalette)
    }

    fun bindState(
        engine: AudioEngine?,
        route: ActiveOutputRoute?,
        bt: BluetoothSettings,
        deviceLatencyMs: Long
    ) {
        audioEngine = engine
        activeRoute = route
        btSettings = bt
        btDeviceLatencyMs = deviceLatencyMs
        dspConfig = engine?.getDspConfig() ?: AudioDspConfig()
        routeBanner.text = route?.let { "Rute Aktif: ${it.displayName} (${it.type.name})" } ?: "Rute Aktif: Speaker"
        renderActiveTab()
    }

    private fun buildUi() {
        sheetCard.apply {
            orientation = LinearLayout.VERTICAL
            background = sheetGlass
            setPadding(dp(16), dp(16), dp(16), dp(20))
            elevation = 20f * density
            isClickable = true
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = context.getString(R.string.audio_lab_title)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val closeBtn = TextView(context).apply {
            text = context.getString(R.string.eq_close)
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER
            background = LiquidGlassDrawable(context, 14f, palette)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onDismiss() }
        }
        headerRow.addView(titleTv, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        routeBanner.apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, dp(10))
        }

        tabRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val specs = listOf(
            LabTab.EQ_10 to context.getString(R.string.tab_eq_10),
            LabTab.ACOUSTICS_BT to context.getString(R.string.tab_acoustics_bt),
            LabTab.TEMPO_LOOP to context.getString(R.string.tab_tempo_loop)
        )
        for ((tab, label) in specs) {
            val tv = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                minimumHeight = dp(48)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), dp(8), dp(8), dp(8))
                SpringAnimatorHelper.attachPressFeedback(this)
                setOnClickListener {
                    activeTab = tab
                    updateTabVisuals()
                    renderActiveTab()
                    SpringAnimatorHelper.springReveal(bodyContainer, fromTranslationY = 12f * density, fromScale = 0.99f)
                }
            }
            tabViews.add(tab to tv)
            tabRow.addView(tv, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        }

        bodyContainer.orientation = LinearLayout.VERTICAL
        scrollView.addView(bodyContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        sheetCard.addView(headerRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        sheetCard.addView(routeBanner, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        sheetCard.addView(tabRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        val maxBodyH = (resources.displayMetrics.heightPixels * 0.48f).toInt()
        sheetCard.addView(scrollView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, maxBodyH))

        addView(sheetCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        updateTabVisuals()
    }

    private fun updateTabVisuals() {
        for ((tab, tv) in tabViews) {
            val sel = tab == activeTab
            val glass = LiquidGlassDrawable(context, 16f, palette)
            glass.setTintProfile(
                baseFraction = if (sel) 0.35f else 0.56f,
                accentFraction = if (sel) 0.50f else 0.14f,
                preferAccentGlow = sel
            )
            tv.background = glass
            tv.setTextColor(if (sel) 0xFFFFFFFF.toInt() else 0xA3FFFFFF.toInt())
        }
    }

    private fun renderActiveTab() {
        bodyContainer.removeAllViews()
        when (activeTab) {
            LabTab.EQ_10 -> renderEqTab()
            LabTab.ACOUSTICS_BT -> renderAcousticsBtTab()
            LabTab.TEMPO_LOOP -> renderTempoLoopTab()
        }
    }

    private fun renderEqTab() {
        addToggleRow("Aktifkan Equalizer 10-Band", dspConfig.eqEnabled) { checked ->
            dspConfig = dspConfig.copy(eqEnabled = checked)
            callbacks?.onDspConfigChanged(dspConfig)
        }

        // Preset strip
        val presetScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(4), 0, dp(10))
        }
        val presetRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (presetName in TenBandBiquadEq.PRESET_NAMES) {
            val chip = TextView(context).apply {
                text = presetName
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                minimumHeight = dp(40)
                gravity = Gravity.CENTER
                background = LiquidGlassDrawable(context, 14f, palette)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                SpringAnimatorHelper.attachPressFeedback(this)
                setOnClickListener {
                    val preset = TenBandBiquadEq.PRESETS[presetName] ?: return@setOnClickListener
                    dspConfig = dspConfig.copy(eqEnabled = true, eqBandsDb = preset.copyOf())
                    callbacks?.onDspConfigChanged(dspConfig)
                    renderActiveTab()
                }
            }
            presetRow.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            })
        }
        presetScroll.addView(presetRow)
        bodyContainer.addView(presetScroll)

        for (i in 0 until TenBandBiquadEq.BAND_COUNT) {
            val hz = TenBandBiquadEq.BAND_FREQUENCIES_HZ[i].toInt()
            val label = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
            val currentDb = dspConfig.eqBandsDb[i]
            addSliderRow(
                label = label,
                initialValueText = formatDb(currentDb),
                maxProgress = 240,
                initialProgress = ((currentDb + 12f) * 10f).roundToInt().coerceIn(0, 240)
            ) { progress, tv, fromUser ->
                val db = (progress - 120) / 10f
                tv.text = formatDb(db)
                if (fromUser) {
                    val nextBands = dspConfig.eqBandsDb.copyOf()
                    nextBands[i] = db
                    dspConfig = dspConfig.copy(eqBandsDb = nextBands)
                    callbacks?.onDspConfigChanged(dspConfig)
                }
            }
        }
    }

    private fun renderAcousticsBtTab() {
        addSliderRow(
            label = "Crossfeed",
            initialValueText = "${(dspConfig.crossfeedLevel * 100).roundToInt()}%",
            maxProgress = 100,
            initialProgress = (dspConfig.crossfeedLevel * 100f).roundToInt().coerceIn(0, 100)
        ) { p, tv, fromUser ->
            tv.text = "$p%"
            if (fromUser) {
                dspConfig = dspConfig.copy(crossfeedLevel = p / 100f)
                callbacks?.onDspConfigChanged(dspConfig)
            }
        }

        addSliderRow(
            label = "L/R Balance",
            initialValueText = formatBalance(dspConfig.balance),
            maxProgress = 200,
            initialProgress = ((dspConfig.balance + 1f) * 100f).roundToInt().coerceIn(0, 200)
        ) { p, tv, fromUser ->
            val bal = (p - 100) / 100f
            tv.text = formatBalance(bal)
            if (fromUser) {
                dspConfig = dspConfig.copy(balance = bal)
                callbacks?.onDspConfigChanged(dspConfig)
            }
        }

        addToggleRow("Smart Loudness (RMS)", dspConfig.smartLoudnessEnabled) { checked ->
            dspConfig = dspConfig.copy(smartLoudnessEnabled = checked)
            callbacks?.onDspConfigChanged(dspConfig)
        }
        addToggleRow("Pangkas Hening Awal/Akhir (-65 dB)", dspConfig.autoTrimSilenceEnabled) { checked ->
            dspConfig = dspConfig.copy(autoTrimSilenceEnabled = checked)
            callbacks?.onDspConfigChanged(dspConfig)
        }
        addToggleRow("Mono Downmix", dspConfig.monoDownmix) { checked ->
            dspConfig = dspConfig.copy(monoDownmix = checked)
            callbacks?.onDspConfigChanged(dspConfig)
        }
        addToggleRow("Crossfade Shuffle 1.8s (Gapless di Album)", dspConfig.shuffleCrossfadeEnabled) { checked ->
            dspConfig = dspConfig.copy(shuffleCrossfadeEnabled = checked)
            callbacks?.onDspConfigChanged(dspConfig)
        }
        addToggleRow("Passthrough Sample Rate (USB DAC)", dspConfig.passthroughSampleRateEnabled) { checked ->
            dspConfig = dspConfig.copy(passthroughSampleRateEnabled = checked)
            callbacks?.onDspConfigChanged(dspConfig)
        }

        addSliderRow(
            label = "Latensi Lirik BT",
            initialValueText = "${btDeviceLatencyMs}ms",
            maxProgress = 100,
            initialProgress = ((btDeviceLatencyMs + 500L) / 10L).toInt().coerceIn(0, 100)
        ) { p, tv, fromUser ->
            val ms = (p * 10L) - 500L
            tv.text = "${ms}ms"
            if (fromUser) {
                btDeviceLatencyMs = ms
                btSettings = btSettings.copy(defaultBtLatencyOffsetMs = ms)
                callbacks?.onBluetoothSettingsChanged(btSettings, btDeviceLatencyMs)
            }
        }

        addToggleRow("BT Wake-Up Pre-Roll (~150ms)", btSettings.btPreRollEnabled) { checked ->
            btSettings = btSettings.copy(btPreRollEnabled = checked)
            dspConfig = dspConfig.copy(btPreRollEnabled = checked)
            callbacks?.onDspConfigChanged(dspConfig)
            callbacks?.onBluetoothSettingsChanged(btSettings, btDeviceLatencyMs)
        }
        addToggleRow("Putar Otomatis Saat TWS Terhubung", btSettings.autoPlayOnConnect) { checked ->
            btSettings = btSettings.copy(autoPlayOnConnect = checked)
            callbacks?.onBluetoothSettingsChanged(btSettings, btDeviceLatencyMs)
        }
        addToggleRow("Jeda Otomatis Saat TWS Dilepas", btSettings.autoPauseOnDisconnect) { checked ->
            btSettings = btSettings.copy(autoPauseOnDisconnect = checked)
            callbacks?.onBluetoothSettingsChanged(btSettings, btDeviceLatencyMs)
        }
    }

    private fun renderTempoLoopTab() {
        addSliderRow(
            label = "Kecepatan",
            initialValueText = String.format(java.util.Locale.US, "%.2fx", dspConfig.speed),
            maxProgress = 150,
            initialProgress = ((dspConfig.speed - 0.5f) * 100f).roundToInt().coerceIn(0, 150)
        ) { p, tv, fromUser ->
            val speed = (0.5f + p / 100f).coerceIn(0.5f, 2.0f)
            tv.text = String.format(java.util.Locale.US, "%.2fx", speed)
            if (fromUser) {
                dspConfig = dspConfig.copy(speed = speed)
                callbacks?.onDspConfigChanged(dspConfig)
            }
        }

        addSliderRow(
            label = "Nada (Pitch)",
            initialValueText = String.format(java.util.Locale.US, "%.2fx", dspConfig.pitch),
            maxProgress = 150,
            initialProgress = ((dspConfig.pitch - 0.5f) * 100f).roundToInt().coerceIn(0, 150)
        ) { p, tv, fromUser ->
            val pitch = (0.5f + p / 100f).coerceIn(0.5f, 2.0f)
            tv.text = String.format(java.util.Locale.US, "%.2fx", pitch)
            if (fromUser) {
                dspConfig = dspConfig.copy(pitch = pitch)
                callbacks?.onDspConfigChanged(dspConfig)
            }
        }

        val resetBtn = createChipButton("Reset Tempo & Nada 1.00x") {
            dspConfig = dspConfig.copy(speed = 1.0f, pitch = 1.0f)
            callbacks?.onDspConfigChanged(dspConfig)
            renderActiveTab()
        }
        bodyContainer.addView(resetBtn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(4)
            bottomMargin = dp(12)
        })

        val abRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        abRow.addView(createChipButton("Set A") { callbacks?.onSetAbStart() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        abRow.addView(createChipButton("Set B") { callbacks?.onSetAbEnd() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        abRow.addView(createChipButton("Hapus AB") { callbacks?.onClearAbLoop() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        bodyContainer.addView(abRow, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })

        val chapters = audioEngine?.getCurrentTrack()?.chapters.orEmpty()
        if (chapters.isNotEmpty()) {
            val chHeader = TextView(context).apply {
                text = "Penanda Bab (Chapters)"
                setTextColor(0xCCFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(4), 0, dp(6))
            }
            bodyContainer.addView(chHeader)
            for (ch in chapters) {
                val sec = ch.startMs / 1000L
                val timeLabel = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
                val btn = createChipButton("$timeLabel \u2022 ${ch.title}") {
                    callbacks?.onJumpToChapter(ch.startMs)
                }
                bodyContainer.addView(btn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
                    bottomMargin = dp(6)
                })
            }
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

    private fun addSliderRow(
        label: String,
        initialValueText: String,
        maxProgress: Int,
        initialProgress: Int,
        onProgress: (Int, TextView, Boolean) -> Unit
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(42)
            setPadding(0, dp(2), 0, dp(2))
        }
        val labelTv = TextView(context).apply {
            text = label
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            width = dp(82)
        }
        val valTv = TextView(context).apply {
            text = initialValueText
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
            width = dp(60)
        }
        val sb = SeekBar(context).apply {
            max = maxProgress
            progress = initialProgress
            progressTintList = ColorStateList.valueOf(palette.accentGlow)
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    onProgress(progress, valTv, fromUser)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        row.addView(labelTv)
        row.addView(sb, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valTv)
        bodyContainer.addView(row)
    }

    private fun createChipButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            background = LiquidGlassDrawable(context, 16f, palette)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            SpringAnimatorHelper.attachPressFeedback(this)
            setOnClickListener { onClick() }
        }
    }

    private fun formatDb(db: Float): String {
        val rounded = (db * 10f).roundToInt() / 10f
        return if (rounded > 0f) "+$rounded dB" else "$rounded dB"
    }

    private fun formatBalance(bal: Float): String {
        val pct = (bal * 100f).roundToInt()
        return when {
            pct == 0 -> "C"
            pct < 0 -> "L ${-pct}%"
            else -> "R $pct%"
        }
    }

    private fun applyPalette(newPalette: CoverPalette) {
        sheetGlass.setPalette(newPalette)
        sheetGlass.setTintProfile(baseFraction = 0.88f, accentFraction = 0.14f)
        updateTabVisuals()
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
