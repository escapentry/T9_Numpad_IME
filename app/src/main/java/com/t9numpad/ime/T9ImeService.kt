package com.t9numpad.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.widget.ImageViewCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

class T9ImeService : InputMethodService() {

    enum class InputMode { T9, ABC, NUM }
    enum class CaseMode { LOWER, TITLE, UPPER }

    private lateinit var rootView: View
    private lateinit var trayWrapper: View
    private lateinit var trayContainer: View
    private lateinit var popupOverlayLayer: FrameLayout
    private lateinit var stripBar: View
    private lateinit var numpadGrid: GridLayout
    private lateinit var symbolsPanel: LinearLayout
    private lateinit var symbolsGrid: GridLayout
    private lateinit var symbolsFooter: LinearLayout
    private lateinit var clipboardPanel: LinearLayout
    private lateinit var clipboardScroll: ScrollView
    private lateinit var clipboardList: LinearLayout
    private lateinit var clipBackBtn: AppCompatButton
    private lateinit var clipClearBtn: AppCompatButton
    private lateinit var symbolPageBtn: AppCompatButton
    private lateinit var symBackBtn: AppCompatButton
    private lateinit var symSpaceBtn: AppCompatButton
    private lateinit var symBackspaceBtn: AppCompatImageButton
    private lateinit var candidateContainer: LinearLayout
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var caseBadge: TextView
    private lateinit var clipboardButton: AppCompatImageButton
    private lateinit var t9ToggleButton: AppCompatButton
    private lateinit var modeButton: AppCompatButton
    private lateinit var symButton: AppCompatButton
    private lateinit var shiftButton: AppCompatButton
    private lateinit var hashButton: AppCompatButton
    private lateinit var backspaceButton: AppCompatImageButton
    private lateinit var enterButton: AppCompatImageButton
    private lateinit var settingsButton: AppCompatImageButton

    private val t9Engine = T9Engine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioExecutor = Executors.newSingleThreadScheduledExecutor()
    private lateinit var prefs: SharedPreferences
    private var clipboardManager: ClipboardManager? = null
    private val clipboardHistory = ArrayList<String>()

    private val pcmCache = HashMap<String, ByteArray>()
    private var keyPopupView: TextView? = null
    private val dismissPopupRunnable = Runnable { dismissKeyPopupImmediate() }

    private var t9Enabled = true
    private var isNumericMode = false
    private var inputMode = InputMode.T9
    private var caseMode = CaseMode.TITLE
    private var symbolsOpen = false
    private var clipboardOpen = false
    private var symbolPage = 0
    private var isSpellingToDict = false
    private var currentKeyHeightPx = 0

    private val composingDigits = StringBuilder()
    private var candidates: List<T9Candidate> = emptyList()
    private var candidateIndex = 0

    // Multi-tap state (ABC mode)
    private var lastMultiTapKey: Char? = null
    private var multiTapCycleIndex = 0
    private val multiTapCommitRunnable = Runnable { commitMultiTapChar() }
    private val multiTapTimeoutMs = 700L

    // Punctuation cycle state for key 1
    private val punctCycle = listOf(".", ",", "?", "!", "'", "-", ":", "/", "@", "\"")
    private var lastPunctTimeMs = 0L
    private var lastPunctIndex = -1
    private var lastPunctAddedSpace = false

    // Continuous hold-to-delete character by character
    private var isBackspaceHeld = false
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (!isBackspaceHeld) return
            handleBackspace()
            mainHandler.postDelayed(this, 70L)
        }
    }

    // Settings state
    private var themeId = "material_light"
    private var keyHeightId = "standard"
    private var soundEnabled = true
    private var soundVolume = 75
    private var soundSetId = "clicks"
    private var hapticEnabled = true
    private var hapticStrength = 65
    private var showKeyPopups = true
    private var autoCapitalize = true
    private var autoSpacing = true
    private var autoCorrectWhenT9Off = true

    private val symbolPages = listOf(
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/", "*", "\"", "'", ":", ";", "!", "?", "%"),
        listOf("~", "`", "|", "^", "=", "{", "}", "[", "]", "<", ">", "\\", "°", "•", "€", "£", "¥", "₹"),
        listOf("©", "®", "™", "§", "¶", "±", "×", "÷", "≠", "≤", "≥", "∞", "→", "←", "↑", "↓", "✓", "★")
    )

    private val autoSpacePunct = setOf(".", ",", "?", "!", ";", ":")

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureCurrentSystemClip()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        t9Engine.loadDictionary(assets.open("t9_words.txt"))
        syncCustomWordsFromPrefs()
        syncLearnedFreqsFromPrefs()
        syncLearnedBigramsFromPrefs()
        loadClipboardHistory()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        captureCurrentSystemClip()
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        mainHandler.removeCallbacks(dismissPopupRunnable)
        dismissKeyPopupImmediate()
        audioExecutor.shutdownNow()
    }

    @Suppress("DEPRECATION")
    private fun applyBlackNavBarAppearance() {
        val win = window?.window ?: return
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            win.decorView.systemUiVisibility =
                win.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        applyBlackNavBarAppearance()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!::trayWrapper.isInitialized || trayWrapper.height == 0) return
        val topOfTray = trayWrapper.top
        outInsets.contentTopInsets = topOfTray
        outInsets.visibleTopInsets = topOfTray
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
    }

    override fun onCreateInputView(): View {
        rootView = layoutInflater.inflate(R.layout.keyboard_t9_numpad, null)
        trayWrapper = rootView.findViewById(R.id.ime_tray_wrapper)
        trayContainer = rootView.findViewById(R.id.keyboard_tray)
        popupOverlayLayer = rootView.findViewById(R.id.popup_overlay_layer)
        stripBar = rootView.findViewById(R.id.strip_bar)
        numpadGrid = rootView.findViewById(R.id.numpad_grid)
        symbolsPanel = rootView.findViewById(R.id.symbols_panel)
        symbolsGrid = rootView.findViewById(R.id.symbols_grid)
        symbolsFooter = rootView.findViewById(R.id.symbols_footer)
        clipboardPanel = rootView.findViewById(R.id.clipboard_panel)
        clipboardScroll = rootView.findViewById(R.id.clipboard_scroll)
        clipboardList = rootView.findViewById(R.id.clipboard_list)
        clipBackBtn = rootView.findViewById(R.id.btn_clip_back)
        clipClearBtn = rootView.findViewById(R.id.btn_clip_clear)
        symbolPageBtn = rootView.findViewById(R.id.btn_sym_page)
        symBackBtn = rootView.findViewById(R.id.btn_sym_back)
        symSpaceBtn = rootView.findViewById(R.id.btn_sym_space)
        symBackspaceBtn = rootView.findViewById(R.id.btn_sym_backspace)
        candidateContainer = rootView.findViewById(R.id.candidate_container)
        candidateScroll = rootView.findViewById(R.id.candidate_scroll)
        caseBadge = rootView.findViewById(R.id.case_badge)
        clipboardButton = rootView.findViewById(R.id.btn_clipboard)
        t9ToggleButton = rootView.findViewById(R.id.btn_toggle_t9)
        modeButton = rootView.findViewById(R.id.key_mode)
        symButton = rootView.findViewById(R.id.key_sym)
        shiftButton = rootView.findViewById(R.id.key_star)
        hashButton = rootView.findViewById(R.id.key_hash)
        backspaceButton = rootView.findViewById(R.id.key_backspace)
        enterButton = rootView.findViewById(R.id.key_enter)
        settingsButton = rootView.findViewById(R.id.btn_ime_settings)

        bindDigitKeys()
        bindActionKeys()
        bindSymbolsPanel()
        bindClipboardPanel()
        loadAndApplySettings()
        refreshModeUI()
        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyBlackNavBarAppearance()
        captureCurrentSystemClip()
        loadAndApplySettings()
        syncCustomWordsFromPrefs()
        syncLearnedFreqsFromPrefs()
        syncLearnedBigramsFromPrefs()
        resetComposition()
        isSpellingToDict = false
        symbolsOpen = false
        clipboardOpen = false
        dismissKeyPopupImmediate()
        updatePanelVisibility()

        t9Enabled = prefs.getBoolean(SettingsActivity.KEY_T9_ENABLED, true)
        isNumericMode = prefs.getBoolean(SettingsActivity.KEY_IS_NUMERIC, false)

        val inputType = info?.inputType ?: 0
        val isNumericField = (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER ||
            (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_PHONE
        inputMode = when {
            isNumericField || isNumericMode -> InputMode.NUM
            t9Enabled -> InputMode.T9
            else -> InputMode.ABC
        }
        caseMode = if (autoCapitalize) CaseMode.TITLE else CaseMode.LOWER
        updateAutoCapitalization()
        refreshModeUI()
        renderCandidates()
    }

    private fun syncCustomWordsFromPrefs() {
        val saved = prefs.getStringSet(SettingsActivity.KEY_CUSTOM_WORDS, emptySet()) ?: emptySet()
        saved.forEach { word ->
            val clean = word.lowercase().trim()
            if (clean.length >= 2) {
                t9Engine.insertWord(clean, 10000)
            }
        }
    }

    private fun syncLearnedFreqsFromPrefs() {
        val raw = prefs.getString(SettingsActivity.KEY_LEARNED_FREQS, "") ?: ""
        val map = HashMap<String, Int>()
        raw.split(",").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val w = parts[0].trim().lowercase()
                val c = parts[1].toIntOrNull() ?: 0
                if (w.length >= 2 && c > 0) map[w] = c
            }
        }
        t9Engine.applyLearnedFrequencies(map)
    }

    private fun syncLearnedBigramsFromPrefs() {
        val raw = prefs.getString(SettingsActivity.KEY_LEARNED_BIGRAMS, "{}") ?: "{}"
        val map = HashMap<String, MutableMap<String, Int>>()
        try {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val prevW = keys.next().lowercase()
                val inner = obj.optJSONObject(prevW) ?: continue
                val innerMap = HashMap<String, Int>()
                val iKeys = inner.keys()
                while (iKeys.hasNext()) {
                    val nextW = iKeys.next().lowercase()
                    val count = inner.optInt(nextW, 0)
                    if (count > 0) innerMap[nextW] = count
                }
                if (innerMap.isNotEmpty()) map[prevW] = innerMap
            }
        } catch (_: Throwable) {
        }
        t9Engine.applyLearnedBigrams(map)
    }

    private fun getPreviousWordBeforeCursor(skipCurrentWord: Boolean = false): String {
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString() ?: return ""
        val target = if (skipCurrentWord) {
            before.replace(Regex("[a-zA-Z]+$"), "")
        } else {
            before
        }
        val match = Regex("([a-zA-Z']+)\\s+$").find(target) ?: return ""
        return match.groupValues[1].lowercase().filter { it in 'a'..'z' }
    }

    private fun recordWordUsage(rawWord: String, prevWord: String = "") {
        val clean = rawWord.lowercase().trim().filter { it in 'a'..'z' }
        if (clean.length < 2) return
        val cleanPrev = prevWord.lowercase().trim().filter { it in 'a'..'z' }
        val newCount = t9Engine.recordWordUsage(clean, cleanPrev)
        val raw = prefs.getString(SettingsActivity.KEY_LEARNED_FREQS, "") ?: ""
        val map = LinkedHashMap<String, Int>()
        raw.split(",").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val w = parts[0].trim().lowercase()
                val c = parts[1].toIntOrNull() ?: 0
                if (w.length >= 2 && c > 0) map[w] = c
            }
        }
        map[clean] = newCount
        val serialized = map.entries
            .sortedByDescending { it.value }
            .take(300)
            .joinToString(",") { "${it.key}:${it.value}" }

        val editor = prefs.edit().putString(SettingsActivity.KEY_LEARNED_FREQS, serialized)
        if (cleanPrev.isNotEmpty() && cleanPrev != clean) {
            try {
                val obj = JSONObject(prefs.getString(SettingsActivity.KEY_LEARNED_BIGRAMS, "{}") ?: "{}")
                val inner = obj.optJSONObject(cleanPrev) ?: JSONObject()
                inner.put(clean, inner.optInt(clean, 0) + 1)
                obj.put(cleanPrev, inner)
                editor.putString(SettingsActivity.KEY_LEARNED_BIGRAMS, obj.toString())
            } catch (_: Throwable) {
            }
        }
        editor.apply()
    }

    private fun saveCustomWord(word: String) {
        val clean = word.lowercase().trim().filter { it in 'a'..'z' }
        if (clean.length < 2) return
        val existing = prefs.getStringSet(SettingsActivity.KEY_CUSTOM_WORDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(clean)
        prefs.edit().putStringSet(SettingsActivity.KEY_CUSTOM_WORDS, existing).apply()
        t9Engine.insertWord(clean, 10000)
        recordWordUsage(clean, getPreviousWordBeforeCursor(skipCurrentWord = true))
    }

    private fun loadAndApplySettings() {
        themeId = prefs.getString(SettingsActivity.KEY_THEME, "material_light") ?: "material_light"
        if (themeId != "material_light" && themeId != "material_dark" && themeId != "amoled") {
            themeId = "material_light"
        }
        keyHeightId = prefs.getString(SettingsActivity.KEY_HEIGHT, "standard") ?: "standard"
        soundEnabled = prefs.getBoolean(SettingsActivity.KEY_SOUND, true)
        soundVolume = prefs.getInt(SettingsActivity.KEY_SOUND_VOLUME, 75)
        val newSoundSet = prefs.getString(SettingsActivity.KEY_SOUND_SET, "clicks") ?: "clicks"
        if (newSoundSet != soundSetId) {
            soundSetId = newSoundSet
            pcmCache.clear()
        }
        hapticEnabled = prefs.getBoolean(SettingsActivity.KEY_HAPTIC, true)
        hapticStrength = prefs.getInt(SettingsActivity.KEY_HAPTIC_STRENGTH, 65)
        showKeyPopups = prefs.getBoolean(SettingsActivity.KEY_KEY_POPUP, true)
        autoCapitalize = prefs.getBoolean(SettingsActivity.KEY_AUTO_CAP, true)
        autoSpacing = prefs.getBoolean(SettingsActivity.KEY_AUTO_SPACE, true)
        autoCorrectWhenT9Off = prefs.getBoolean(SettingsActivity.KEY_AUTO_CORRECT_ABC, true)

        if (::rootView.isInitialized) {
            applyThemeAndHeight()
        }
    }

    private data class ThemePalette(
        val trayHex: String,
        val stripHex: String,
        val keyHex: String,
        val actionHex: String,
        val secondaryHex: String,
        val enterHex: String,
        val keyTextHex: String,
        val subTextHex: String,
        val actionTextHex: String,
        val enterTextHex: String,
        val shiftActiveBg: String,
        val shiftActiveText: String
    )

    private fun currentPalette(): ThemePalette = when (themeId) {
        "material_dark" -> ThemePalette(
            trayHex = "#1B1C1F",
            stripHex = "#202226",
            keyHex = "#2E3036",
            actionHex = "#3A4352",
            secondaryHex = "#25272C",
            enterHex = "#A8C7FA",
            keyTextHex = "#E8EAED",
            subTextHex = "#9AA0A6",
            actionTextHex = "#D3E3FD",
            enterTextHex = "#062E6F",
            shiftActiveBg = "#3A4352",
            shiftActiveText = "#A8C7FA"
        )
        "amoled" -> ThemePalette(
            trayHex = "#000000",
            stripHex = "#08080A",
            keyHex = "#151518",
            actionHex = "#1D2433",
            secondaryHex = "#0F0F12",
            enterHex = "#2563EB",
            keyTextHex = "#F4F4F5",
            subTextHex = "#A1A1AA",
            actionTextHex = "#BFDBFE",
            enterTextHex = "#FFFFFF",
            shiftActiveBg = "#1D2433",
            shiftActiveText = "#93C5FD"
        )
        else -> ThemePalette(
            trayHex = "#DCE4EF",
            stripHex = "#E5ECF5",
            keyHex = "#FFFFFF",
            actionHex = "#C8DDFC",
            secondaryHex = "#EDF2F8",
            enterHex = "#0B57D0",
            keyTextHex = "#1A1C1E",
            subTextHex = "#5A6270",
            actionTextHex = "#052354",
            enterTextHex = "#FFFFFF",
            shiftActiveBg = "#C8DDFC",
            shiftActiveText = "#0B57D0"
        )
    }

    private fun applyThemeAndHeight() {
        val heightDp = when (keyHeightId) {
            "compact" -> 46
            "tall" -> 64
            else -> 54
        }
        currentKeyHeightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            heightDp.toFloat(),
            resources.displayMetrics
        ).toInt()

        val palette = currentPalette()

        trayContainer.background = makeRoundedDrawable(palette.trayHex, 16f).apply {
            if (themeId == "amoled") {
                setStroke(2, Color.parseColor("#27272A"))
            }
        }
        stripBar.background = makeTopRoundedDrawable(palette.stripHex, 16f)
        ImageViewCompat.setImageTintList(
            clipboardButton,
            ColorStateList.valueOf(
                Color.parseColor(if (clipboardOpen) palette.enterHex else palette.subTextHex)
            )
        )
        ImageViewCompat.setImageTintList(
            settingsButton,
            ColorStateList.valueOf(Color.parseColor(palette.subTextHex))
        )

        val digitIds = listOf(
            R.id.key_1, R.id.key_2, R.id.key_3,
            R.id.key_4, R.id.key_5, R.id.key_6,
            R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0
        )
        for (id in digitIds) {
            val container = rootView.findViewById<LinearLayout>(id)
            container.layoutParams = (container.layoutParams as GridLayout.LayoutParams).apply {
                height = currentKeyHeightPx
            }
            container.background = makeRoundedDrawable(palette.keyHex, 16f)
            if (container.childCount >= 2) {
                (container.getChildAt(0) as? TextView)?.setTextColor(Color.parseColor(palette.keyTextHex))
                (container.getChildAt(1) as? TextView)?.setTextColor(Color.parseColor(palette.subTextHex))
            }
        }

        val actionTextButtons = listOf(modeButton, symButton)
        for (btn in actionTextButtons) {
            btn.layoutParams = (btn.layoutParams as GridLayout.LayoutParams).apply {
                height = currentKeyHeightPx
            }
            btn.background = makeRoundedDrawable(palette.actionHex, 16f)
            btn.setTextColor(Color.parseColor(palette.actionTextHex))
        }

        backspaceButton.layoutParams = (backspaceButton.layoutParams as GridLayout.LayoutParams).apply {
            height = currentKeyHeightPx
        }
        backspaceButton.background = makeRoundedDrawable(palette.actionHex, 16f)
        ImageViewCompat.setImageTintList(
            backspaceButton,
            ColorStateList.valueOf(Color.parseColor(palette.actionTextHex))
        )

        val secIds = listOf(R.id.key_star, R.id.key_hash)
        for (id in secIds) {
            val btn = rootView.findViewById<Button>(id)
            btn.layoutParams = (btn.layoutParams as GridLayout.LayoutParams).apply {
                height = currentKeyHeightPx
            }
            btn.background = makeRoundedDrawable(palette.secondaryHex, 16f)
            btn.setTextColor(Color.parseColor(palette.keyTextHex))
        }

        enterButton.layoutParams = (enterButton.layoutParams as GridLayout.LayoutParams).apply {
            height = currentKeyHeightPx
        }
        enterButton.background = makeRoundedDrawable(palette.enterHex, 16f)
        ImageViewCompat.setImageTintList(
            enterButton,
            ColorStateList.valueOf(Color.parseColor(palette.enterTextHex))
        )

        // Lock Symbols & Clipboard footer height and theme colors to match the main numpad row height
        listOf(symBackBtn, symbolPageBtn, symSpaceBtn, symBackspaceBtn).forEach { v ->
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                height = currentKeyHeightPx
            }
        }
        symBackBtn.background = makeRoundedDrawable(palette.actionHex, 16f)
        symBackBtn.setTextColor(Color.parseColor(palette.actionTextHex))
        symbolPageBtn.background = makeRoundedDrawable(palette.secondaryHex, 16f)
        symbolPageBtn.setTextColor(Color.parseColor(palette.keyTextHex))
        symSpaceBtn.background = makeRoundedDrawable(palette.keyHex, 16f)
        symSpaceBtn.setTextColor(Color.parseColor(palette.keyTextHex))
        symBackspaceBtn.background = makeRoundedDrawable(palette.actionHex, 16f)
        ImageViewCompat.setImageTintList(
            symBackspaceBtn,
            ColorStateList.valueOf(Color.parseColor(palette.actionTextHex))
        )

        // Match Clipboard scroll area to 3 rows of keys so overall keyboard height stays fixed
        val clipMarginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics).toInt()
        clipboardScroll.layoutParams = (clipboardScroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = currentKeyHeightPx * 3 + clipMarginPx
        }
        listOf(clipBackBtn, clipClearBtn).forEach { v ->
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                height = currentKeyHeightPx
            }
        }
        clipBackBtn.background = makeRoundedDrawable(palette.actionHex, 16f)
        clipBackBtn.setTextColor(Color.parseColor(palette.actionTextHex))
        clipClearBtn.background = makeRoundedDrawable(palette.secondaryHex, 16f)
        clipClearBtn.setTextColor(Color.parseColor(palette.keyTextHex))

        renderSymbolPage()
        renderClipboardHistory()
    }

    private fun makeRoundedDrawable(hex: String, radiusDp: Float): GradientDrawable {
        val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, resources.displayMetrics)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.parseColor(hex))
        }
    }

    private fun makeTopRoundedDrawable(hex: String, topRadiusDp: Float): GradientDrawable {
        val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, topRadiusDp, resources.displayMetrics)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setColor(Color.parseColor(hex))
        }
    }

    private fun showKeyPopup(anchor: View, label: String) {
        if (!showKeyPopups || label.isEmpty() || !::popupOverlayLayer.isInitialized) return
        mainHandler.removeCallbacks(dismissPopupRunnable)

        val isLightKeyboard = themeId == "material_light"
        // Opposite-theme high-contrast bold popup bubble floating freely over the keyboard frame
        val popupBgHex = if (isLightKeyboard) "#181A1E" else "#FFFFFF"
        val popupTextHex = if (isLightKeyboard) "#FFFFFF" else "#111318"
        val popupBorderHex = if (isLightKeyboard) "#334155" else "#CBD5E1"

        val tv = keyPopupView ?: TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(32, 16, 32, 16)
            elevation = 18f
            popupOverlayLayer.addView(
                this,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            keyPopupView = this
        }
        tv.text = label
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(Color.parseColor(popupTextHex))
        tv.background = makeRoundedDrawable(popupBgHex, 16f).apply {
            setStroke(2, Color.parseColor(popupBorderHex))
        }
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val overlayLoc = IntArray(2)
        val anchorLoc = IntArray(2)
        popupOverlayLayer.getLocationInWindow(overlayLoc)
        anchor.getLocationInWindow(anchorLoc)
        val relX = anchorLoc[0] - overlayLoc[0]
        val relY = anchorLoc[1] - overlayLoc[1]
        val maxX = (popupOverlayLayer.width - tv.measuredWidth - 6).coerceAtLeast(6)
        val x = (relX + (anchor.width - tv.measuredWidth) / 2).coerceIn(6, maxX)
        val y = (relY - tv.measuredHeight - 8).coerceAtLeast(2)
        tv.layoutParams = (tv.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = x
            topMargin = y
        }
        tv.visibility = View.VISIBLE
        tv.bringToFront()
    }

    private fun scheduleDismissKeyPopup() {
        mainHandler.removeCallbacks(dismissPopupRunnable)
        mainHandler.postDelayed(dismissPopupRunnable, 100L)
    }

    private fun dismissKeyPopupImmediate() {
        mainHandler.removeCallbacks(dismissPopupRunnable)
        keyPopupView?.visibility = View.GONE
    }

    private fun attachPopupTouch(view: View, labelProvider: () -> String) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> showKeyPopup(v, labelProvider())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> scheduleDismissKeyPopup()
            }
            false // Return false so click and long-click listeners continue working normally
        }
    }

    private fun bindDigitKeys() {
        val digitMap = mapOf(
            R.id.key_1 to '1', R.id.key_2 to '2', R.id.key_3 to '3',
            R.id.key_4 to '4', R.id.key_5 to '5', R.id.key_6 to '6',
            R.id.key_7 to '7', R.id.key_8 to '8', R.id.key_9 to '9',
            R.id.key_0 to '0'
        )

        digitMap.forEach { (viewId, digit) ->
            val keyView = rootView.findViewById<View>(viewId)
            attachPopupTouch(keyView) { getPopupLabelForDigit(digit) }
            keyView.setOnClickListener {
                val kind = if (digit == '0') SoundKind.SPACE else SoundKind.DIGIT
                performFeedback(kind, keyChar = digit)
                handleDigitTap(digit)
            }
            keyView.setOnLongClickListener {
                performFeedback(SoundKind.DIGIT, strong = true, keyChar = digit)
                showKeyPopup(keyView, digit.toString())
                commitActiveWord()
                currentInputConnection?.commitText(digit.toString(), 1)
                updateAutoCapitalization()
                true
            }
        }
    }

    private fun bindActionKeys() {
        settingsButton.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        // Clipboard history toggle in strip bar
        clipboardButton.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            openClipboardPanel(toggle = true)
        }

        // Dedicated T9 ON/OFF toggle next to Settings icon
        t9ToggleButton.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            commitActiveWord()
            commitMultiTapChar()
            isSpellingToDict = false
            if (isNumericMode) {
                isNumericMode = false
                t9Enabled = true
            } else {
                t9Enabled = !t9Enabled
            }
            prefs.edit()
                .putBoolean(SettingsActivity.KEY_T9_ENABLED, t9Enabled)
                .putBoolean(SettingsActivity.KEY_IS_NUMERIC, isNumericMode)
                .apply()
            inputMode = when {
                isNumericMode -> InputMode.NUM
                t9Enabled -> InputMode.T9
                else -> InputMode.ABC
            }
            refreshModeUI()
            renderCandidates()
        }

        attachPopupTouch(shiftButton) {
            if (inputMode == InputMode.NUM) "*" else when (caseMode) {
                CaseMode.UPPER -> "ABC"
                CaseMode.TITLE -> "Abc"
                CaseMode.LOWER -> "abc"
            }
        }
        shiftButton.setOnClickListener {
            performFeedback(SoundKind.ACTION, keyChar = '*')
            if (inputMode == InputMode.NUM) {
                currentInputConnection?.commitText("*", 1)
            } else {
                cycleCaseMode()
            }
        }
        shiftButton.setOnLongClickListener {
            performFeedback(SoundKind.ACTION, strong = true, keyChar = '*')
            commitActiveWord()
            currentInputConnection?.commitText("*", 1)
            true
        }

        attachPopupTouch(hashButton) { if (inputMode == InputMode.T9) "Next" else "#" }
        hashButton.setOnClickListener {
            performFeedback(SoundKind.ACTION, keyChar = '#')
            if (inputMode == InputMode.T9 && candidates.isNotEmpty()) {
                candidateIndex = (candidateIndex + 1) % candidates.size
                updateComposingWord()
                renderCandidates()
            } else {
                commitActiveWord()
                currentInputConnection?.commitText("#", 1)
            }
        }
        // Hold # to access Clipboard history directly
        hashButton.setOnLongClickListener {
            performFeedback(SoundKind.ACTION, strong = true, keyChar = '#')
            dismissKeyPopupImmediate()
            openClipboardPanel(toggle = false)
            true
        }

        // Switch key strictly switches between Numbers (123) and Alphabets (T9/ABC)
        modeButton.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            commitActiveWord()
            commitMultiTapChar()
            isSpellingToDict = false
            isNumericMode = !isNumericMode
            prefs.edit().putBoolean(SettingsActivity.KEY_IS_NUMERIC, isNumericMode).apply()
            inputMode = when {
                isNumericMode -> InputMode.NUM
                t9Enabled -> InputMode.T9
                else -> InputMode.ABC
            }
            symbolsOpen = false
            clipboardOpen = false
            updatePanelVisibility()
            refreshModeUI()
            renderCandidates()
        }

        symButton.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            commitActiveWord()
            commitMultiTapChar()
            clipboardOpen = false
            symbolsOpen = !symbolsOpen
            updatePanelVisibility()
            renderSymbolPage()
        }

        attachContinuousBackspace(backspaceButton)

        enterButton.setOnClickListener {
            performFeedback(SoundKind.ENTER, strong = true)
            commitActiveWord()
            commitMultiTapChar()
            if (inputMode == InputMode.ABC && isSpellingToDict) {
                val spelled = getAlphaWordBeforeCursor()
                if (spelled.length >= 2) {
                    if (!t9Engine.hasWord(spelled)) saveCustomWord(spelled) else recordWordUsage(spelled)
                }
                isSpellingToDict = false
                t9Enabled = true
                isNumericMode = false
                inputMode = InputMode.T9
                prefs.edit()
                    .putBoolean(SettingsActivity.KEY_T9_ENABLED, true)
                    .putBoolean(SettingsActivity.KEY_IS_NUMERIC, false)
                    .apply()
                refreshModeUI()
            } else {
                val wasAbc = inputMode == InputMode.ABC
                maybeAutoCorrectAbcWord()
                if (wasAbc) {
                    val alpha = getAlphaWordBeforeCursor()
                    if (alpha.length >= 2) {
                        recordWordUsage(alpha, getPreviousWordBeforeCursor(skipCurrentWord = true))
                    }
                }
            }
            val ic = currentInputConnection ?: return@setOnClickListener
            val actionId = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
                ?: EditorInfo.IME_ACTION_NONE
            if (actionId != EditorInfo.IME_ACTION_NONE && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(actionId)
            } else {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            updateAutoCapitalization()
        }

        caseBadge.setOnClickListener {
            if (inputMode != InputMode.NUM) {
                performFeedback(SoundKind.ACTION)
                cycleCaseMode()
            }
        }
    }

    private fun attachContinuousBackspace(button: View) {
        button.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    isBackspaceHeld = true
                    performFeedback(SoundKind.ACTION)
                    handleBackspace()
                    mainHandler.removeCallbacks(backspaceRepeatRunnable)
                    mainHandler.postDelayed(backspaceRepeatRunnable, 340L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    isBackspaceHeld = false
                    mainHandler.removeCallbacks(backspaceRepeatRunnable)
                    true
                }
                else -> false
            }
        }
    }

    private fun bindSymbolsPanel() {
        symBackBtn.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            symbolsOpen = false
            updatePanelVisibility()
        }
        symbolPageBtn.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            symbolPage = (symbolPage + 1) % symbolPages.size
            renderSymbolPage()
        }
        symSpaceBtn.setOnClickListener {
            performFeedback(SoundKind.SPACE, keyChar = '0')
            handleSpaceTap()
        }
        attachContinuousBackspace(symBackspaceBtn)
    }

    private fun bindClipboardPanel() {
        clipBackBtn.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            clipboardOpen = false
            updatePanelVisibility()
        }
        clipClearBtn.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            clipboardHistory.clear()
            persistClipboardHistory()
            renderClipboardHistory()
        }
    }

    private fun loadClipboardHistory() {
        clipboardHistory.clear()
        val json = prefs.getString(SettingsActivity.KEY_CLIPBOARD_HISTORY, "[]") ?: "[]"
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty() && !clipboardHistory.contains(item)) {
                    clipboardHistory.add(item)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun persistClipboardHistory() {
        val arr = JSONArray()
        clipboardHistory.take(25).forEach { arr.put(it) }
        prefs.edit().putString(SettingsActivity.KEY_CLIPBOARD_HISTORY, arr.toString()).apply()
    }

    private fun captureCurrentSystemClip() {
        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount <= 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        if (clipboardHistory.firstOrNull() != text) {
            clipboardHistory.remove(text)
            clipboardHistory.add(0, text)
            while (clipboardHistory.size > 25) {
                clipboardHistory.removeAt(clipboardHistory.lastIndex)
            }
            persistClipboardHistory()
            if (::clipboardList.isInitialized && clipboardOpen) {
                mainHandler.post { renderClipboardHistory() }
            }
        }
    }

    private fun openClipboardPanel(toggle: Boolean = false) {
        commitActiveWord()
        commitMultiTapChar()
        symbolsOpen = false
        clipboardOpen = if (toggle) !clipboardOpen else true
        if (clipboardOpen) {
            captureCurrentSystemClip()
            renderClipboardHistory()
        }
        updatePanelVisibility()
    }

    private fun renderClipboardHistory() {
        if (!::clipboardList.isInitialized) return
        clipboardList.removeAllViews()
        val palette = currentPalette()
        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics).toInt()

        if (clipboardHistory.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Clipboard is empty. Copy words or phrases back-to-back and tap any clip here to paste."
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(24, 40, 24, 40)
                setTextColor(Color.parseColor(palette.subTextHex))
                background = makeRoundedDrawable(palette.secondaryHex, 16f)
            }
            clipboardList.addView(emptyTv)
            return
        }

        clipboardHistory.forEachIndexed { idx, clipText ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = makeRoundedDrawable(palette.keyHex, 16f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, marginPx)
                }
            }

            val pasteTv = TextView(this).apply {
                text = clipText
                textSize = 14f
                maxLines = 2
                setPadding(24, 20, 12, 20)
                setTextColor(Color.parseColor(palette.keyTextHex))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    performFeedback(SoundKind.SPACE, keyChar = '0')
                    commitActiveWord()
                    commitMultiTapChar()
                    val ic = currentInputConnection
                    val before = ic?.getTextBeforeCursor(1, 0)?.toString() ?: ""
                    val needsSpace = before.isNotEmpty() && !before.last().isWhitespace() && !clipText.first().isWhitespace()
                    ic?.commitText((if (needsSpace) " " else "") + clipText, 1)
                    updateAutoCapitalization()
                    clipboardOpen = false
                    updatePanelVisibility()
                }
            }

            val removeBtn = TextView(this).apply {
                text = "✕"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(22, 18, 22, 18)
                setTextColor(Color.parseColor(palette.subTextHex))
                setOnClickListener {
                    performFeedback(SoundKind.ACTION)
                    if (idx in clipboardHistory.indices) {
                        clipboardHistory.removeAt(idx)
                        persistClipboardHistory()
                        renderClipboardHistory()
                    }
                }
            }

            row.addView(pasteTv)
            row.addView(removeBtn)
            clipboardList.addView(row)
        }
    }

    private fun updatePanelVisibility() {
        numpadGrid.visibility = if (!symbolsOpen && !clipboardOpen) View.VISIBLE else View.GONE
        symbolsPanel.visibility = if (symbolsOpen) View.VISIBLE else View.GONE
        clipboardPanel.visibility = if (clipboardOpen) View.VISIBLE else View.GONE
        val modeLabel = when (inputMode) {
            InputMode.T9 -> "T9"
            InputMode.ABC -> "ABC"
            InputMode.NUM -> "123"
        }
        symButton.text = if (symbolsOpen) (if (inputMode == InputMode.NUM) "123" else "ABC") else "@#&"
        symBackBtn.text = "Back ($modeLabel)"
        clipBackBtn.text = "Back ($modeLabel)"
        if (::clipboardButton.isInitialized) {
            val palette = currentPalette()
            ImageViewCompat.setImageTintList(
                clipboardButton,
                ColorStateList.valueOf(
                    Color.parseColor(if (clipboardOpen) palette.enterHex else palette.subTextHex)
                )
            )
        }
    }

    private fun renderSymbolPage() {
        if (!::symbolsGrid.isInitialized) return
        symbolsGrid.removeAllViews()
        symbolPageBtn.text = "Page ${symbolPage + 1}/${symbolPages.size}"

        val palette = currentPalette()
        val items = symbolPages[symbolPage]
        val heightPx = if (currentKeyHeightPx > 0) {
            currentKeyHeightPx
        } else {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 54f, resources.displayMetrics).toInt()
        }
        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics).toInt()

        items.forEach { sym ->
            val btn = AppCompatButton(this).apply {
                text = sym
                textSize = 16f
                isAllCaps = false
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                includeFontPadding = false
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                setTextColor(Color.parseColor(palette.keyTextHex))
                background = makeRoundedDrawable(palette.keyHex, 16f)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = heightPx
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                attachPopupTouch(this) { sym }
                setOnClickListener {
                    performFeedback(SoundKind.DIGIT, keyChar = sym.firstOrNull())
                    insertPunctuationOrSymbol(sym)
                }
            }
            symbolsGrid.addView(btn)
        }
    }

    private fun insertPunctuationOrSymbol(sym: String) {
        val ic = currentInputConnection ?: return
        commitActiveWord()
        commitMultiTapChar()
        maybeAutoCorrectAbcWord()

        val addSpace = autoSpacing && inputMode != InputMode.NUM && autoSpacePunct.contains(sym)
        if (addSpace) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.endsWith(" ")) {
                ic.deleteSurroundingText(1, 0)
            }
            ic.commitText("$sym ", 1)
        } else {
            ic.commitText(sym, 1)
        }
        updateAutoCapitalization()
    }

    private fun handleKey1Punctuation() {
        val ic = currentInputConnection ?: return
        commitActiveWord()
        commitMultiTapChar()
        maybeAutoCorrectAbcWord()

        val now = System.currentTimeMillis()
        val withinCycle = (now - lastPunctTimeMs) < 900L && lastPunctIndex >= 0
        val prevPunct = if (withinCycle) punctCycle[lastPunctIndex] else null
        val prevSuffix = if (prevPunct != null) {
            if (lastPunctAddedSpace) "$prevPunct " else prevPunct
        } else ""

        val before = ic.getTextBeforeCursor(4, 0)?.toString() ?: ""
        if (withinCycle && prevSuffix.isNotEmpty() && before.endsWith(prevSuffix)) {
            val nextIdx = (lastPunctIndex + 1) % punctCycle.size
            val nextPunct = punctCycle[nextIdx]
            val addSpace = autoSpacing && autoSpacePunct.contains(nextPunct)
            lastPunctIndex = nextIdx
            lastPunctTimeMs = now
            lastPunctAddedSpace = addSpace
            ic.deleteSurroundingText(prevSuffix.length, 0)
            ic.commitText(if (addSpace) "$nextPunct " else nextPunct, 1)
        } else {
            val nextPunct = punctCycle[0]
            val addSpace = autoSpacing && autoSpacePunct.contains(nextPunct)
            if (autoSpacing && before.endsWith(" ")) {
                ic.deleteSurroundingText(1, 0)
            }
            lastPunctIndex = 0
            lastPunctTimeMs = now
            lastPunctAddedSpace = addSpace
            ic.commitText(if (addSpace) "$nextPunct " else nextPunct, 1)
        }
        updateAutoCapitalization()
    }

    private fun handleDigitTap(digit: Char) {
        val ic = currentInputConnection ?: return

        if (inputMode == InputMode.NUM) {
            ic.commitText(digit.toString(), 1)
            return
        }

        if (digit == '0') {
            handleSpaceTap()
            return
        }

        if (digit == '1') {
            handleKey1Punctuation()
            return
        }

        if (inputMode == InputMode.T9) {
            composingDigits.append(digit)
            val prevW = getPreviousWordBeforeCursor(skipCurrentWord = true)
            candidates = t9Engine.predict(composingDigits.toString(), prevWord = prevW)
            candidateIndex = 0
            updateComposingWord()
            renderCandidates()
        } else {
            handleMultiTapDigit(digit)
        }
    }

    private fun handleMultiTapDigit(digit: Char) {
        val ic = currentInputConnection ?: return
        val letters = T9Engine.MULTITAP_MAP[digit] ?: return
        mainHandler.removeCallbacks(multiTapCommitRunnable)

        if (lastMultiTapKey == digit) {
            multiTapCycleIndex = (multiTapCycleIndex + 1) % letters.size
        } else {
            commitMultiTapChar()
            lastMultiTapKey = digit
            multiTapCycleIndex = 0
        }

        val ch = applyCase(letters[multiTapCycleIndex].toString())
        ic.setComposingText(ch, 1)
        mainHandler.postDelayed(multiTapCommitRunnable, multiTapTimeoutMs)
        renderCandidates()
    }

    private fun commitMultiTapChar() {
        mainHandler.removeCallbacks(multiTapCommitRunnable)
        if (lastMultiTapKey != null) {
            currentInputConnection?.finishComposingText()
            lastMultiTapKey = null
            multiTapCycleIndex = 0
            if (caseMode == CaseMode.TITLE) {
                caseMode = CaseMode.LOWER
                refreshModeUI()
            }
            renderCandidates()
        }
    }

    private fun handleSpaceTap() {
        val ic = currentInputConnection ?: return
        if (inputMode == InputMode.ABC) {
            commitMultiTapChar()
            if (isSpellingToDict) {
                val spelled = getAlphaWordBeforeCursor()
                if (spelled.length >= 2) {
                    if (!t9Engine.hasWord(spelled)) saveCustomWord(spelled)
                    else recordWordUsage(spelled, getPreviousWordBeforeCursor(skipCurrentWord = true))
                }
                isSpellingToDict = false
                t9Enabled = true
                isNumericMode = false
                inputMode = InputMode.T9
                prefs.edit()
                    .putBoolean(SettingsActivity.KEY_T9_ENABLED, true)
                    .putBoolean(SettingsActivity.KEY_IS_NUMERIC, false)
                    .apply()
                refreshModeUI()
            } else {
                maybeAutoCorrectAbcWord()
                val alpha = getAlphaWordBeforeCursor()
                if (alpha.length >= 2) {
                    recordWordUsage(alpha, getPreviousWordBeforeCursor(skipCurrentWord = true))
                }
            }
        }
        commitActiveWord()
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (!before.endsWith(" ")) {
            ic.commitText(" ", 1)
        }
        updateAutoCapitalization()
        renderCandidates()
    }

    private fun getAlphaWordBeforeCursor(): String {
        val before = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString() ?: return ""
        val match = Regex("([a-zA-Z]{2,})$").find(before) ?: return ""
        return match.groupValues[1].lowercase()
    }

    // Auto-Correct runs ONLY when T9 is OFF (ABC Multi-Tap mode)
    private fun maybeAutoCorrectAbcWord() {
        if (inputMode != InputMode.ABC || !autoCorrectWhenT9Off) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return
        val match = Regex("(^|[\\s\\n])([a-zA-Z]{2,})$").find(before) ?: return
        val rawWord = match.groupValues[2]
        val prevW = getPreviousWordBeforeCursor(skipCurrentWord = true)
        val corrected = t9Engine.autoCorrect(rawWord, prevW) ?: return
        if (corrected != rawWord) {
            ic.deleteSurroundingText(rawWord.length, 0)
            ic.commitText(corrected, 1)
        }
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        if (inputMode == InputMode.T9 && composingDigits.isNotEmpty()) {
            composingDigits.deleteCharAt(composingDigits.length - 1)
            if (composingDigits.isEmpty()) {
                ic.setComposingText("", 1)
                candidates = emptyList()
                renderCandidates()
            } else {
                val prevW = getPreviousWordBeforeCursor(skipCurrentWord = true)
                candidates = t9Engine.predict(composingDigits.toString(), prevWord = prevW)
                candidateIndex = 0
                updateComposingWord()
                renderCandidates()
            }
            return
        }

        if (inputMode == InputMode.ABC && lastMultiTapKey != null) {
            mainHandler.removeCallbacks(multiTapCommitRunnable)
            lastMultiTapKey = null
            ic.setComposingText("", 1)
            renderCandidates()
            return
        }

        ic.deleteSurroundingText(1, 0)
        if (autoCapitalize) {
            val before = ic.getTextBeforeCursor(32, 0)?.toString() ?: ""
            if (before.isEmpty() || before.endsWith(". ") || before.endsWith("! ") || before.endsWith("? ") || before.endsWith("\n")) {
                caseMode = CaseMode.TITLE
                refreshModeUI()
            } else if (before.lastOrNull()?.isLetter() == true && caseMode == CaseMode.TITLE) {
                caseMode = CaseMode.LOWER
                refreshModeUI()
            }
        }
        renderCandidates()
    }

    private fun updateComposingWord() {
        val ic = currentInputConnection ?: return
        val selected = candidates.getOrNull(candidateIndex)?.word ?: composingDigits.toString()
        ic.setComposingText(applyCase(selected), 1)
    }

    private fun commitActiveWord() {
        if (composingDigits.isEmpty()) return
        val chosen = candidates.getOrNull(candidateIndex)?.word
        if (chosen != null && chosen.all { it in 'a'..'z' } && chosen.length >= 2) {
            recordWordUsage(chosen, getPreviousWordBeforeCursor(skipCurrentWord = true))
        }
        currentInputConnection?.finishComposingText()
        resetComposition()
        if (caseMode == CaseMode.TITLE) {
            caseMode = CaseMode.LOWER
            refreshModeUI()
        }
    }

    private fun resetComposition() {
        composingDigits.clear()
        candidates = emptyList()
        candidateIndex = 0
        if (::candidateContainer.isInitialized) renderCandidates()
    }

    private fun cycleCaseMode() {
        caseMode = when (caseMode) {
            CaseMode.LOWER -> CaseMode.TITLE
            CaseMode.TITLE -> CaseMode.UPPER
            CaseMode.UPPER -> CaseMode.LOWER
        }
        if (composingDigits.isNotEmpty()) updateComposingWord()
        renderCandidates()
        refreshModeUI()
    }

    private fun updateAutoCapitalization() {
        if (!autoCapitalize) return
        val before = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString() ?: ""
        if (before.isEmpty() || before.endsWith(". ") || before.endsWith("! ") || before.endsWith("? ") || before.endsWith("\n")) {
            if (caseMode == CaseMode.LOWER) {
                caseMode = CaseMode.TITLE
                refreshModeUI()
            }
        }
    }

    private fun applyCase(word: String): String = when (caseMode) {
        CaseMode.LOWER -> word.lowercase()
        CaseMode.TITLE -> word.replaceFirstChar { it.uppercase() }
        CaseMode.UPPER -> word.uppercase()
    }

    private fun formatDigitSubLabel(digit: Char): String {
        if (inputMode == InputMode.NUM) return ""
        val raw = when (digit) {
            '1' -> ".,?!"
            '2' -> "abc"
            '3' -> "def"
            '4' -> "ghi"
            '5' -> "jkl"
            '6' -> "mno"
            '7' -> "pqrs"
            '8' -> "tuv"
            '9' -> "wxyz"
            '0' -> "␣"
            else -> ""
        }
        if (digit in '2'..'9') {
            return when (caseMode) {
                CaseMode.LOWER -> raw
                CaseMode.TITLE -> raw.replaceFirstChar { it.uppercase() }
                CaseMode.UPPER -> raw.uppercase()
            }
        }
        return raw
    }

    private fun getPopupLabelForDigit(digit: Char): String {
        if (inputMode == InputMode.NUM) return digit.toString()
        if (digit == '0') return "␣"
        if (digit == '1') return punctCycle[0]
        val letters = T9Engine.MULTITAP_MAP[digit] ?: return digit.toString()
        if (inputMode == InputMode.ABC) {
            val nextIdx = if (lastMultiTapKey == digit) {
                (multiTapCycleIndex + 1) % letters.size
            } else {
                0
            }
            return applyCase(letters[nextIdx].toString())
        }
        val nextDigits = composingDigits.toString() + digit
        val prevW = getPreviousWordBeforeCursor(skipCurrentWord = true)
        val topWord = t9Engine.predict(nextDigits, 1, prevWord = prevW).firstOrNull()?.word
        val ch = topWord?.getOrNull(composingDigits.length) ?: letters.firstOrNull() ?: digit
        return if (composingDigits.isEmpty()) {
            applyCase(ch.toString())
        } else if (caseMode == CaseMode.UPPER) {
            ch.uppercase()
        } else {
            ch.lowercase()
        }
    }

    private fun renderCandidates() {
        if (!::candidateContainer.isInitialized) return
        candidateContainer.removeAllViews()
        val palette = currentPalette()
        val normalColor = Color.parseColor(palette.keyTextHex)
        val activeTextColor = Color.parseColor(palette.actionTextHex)
        val activeBgHex = palette.actionHex

        if (inputMode == InputMode.T9 && composingDigits.isNotEmpty()) {
            candidates.forEachIndexed { idx, candidate ->
                val tv = TextView(this).apply {
                    text = applyCase(candidate.word)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(28, 12, 28, 12)
                    isAllCaps = false
                    setTextColor(if (idx == candidateIndex) activeTextColor else normalColor)
                    if (idx == candidateIndex) {
                        background = makeRoundedDrawable(activeBgHex, 12f)
                    }
                    setOnClickListener {
                        performFeedback(SoundKind.SPACE, keyChar = '0')
                        candidateIndex = idx
                        updateComposingWord()
                        commitActiveWord()
                        if (autoSpacing) {
                            currentInputConnection?.commitText(" ", 1)
                        }
                        updateAutoCapitalization()
                        renderCandidates()
                    }
                }
                candidateContainer.addView(tv)
            }

            // Always offer + Spell chip to spell an unlisted word in ABC and save it to T9
            val spellChip = TextView(this).apply {
                text = "+ Spell"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(24, 10, 24, 10)
                setTextColor(activeTextColor)
                background = makeRoundedDrawable(palette.secondaryHex, 12f)
                setOnClickListener {
                    performFeedback(SoundKind.ACTION)
                    currentInputConnection?.setComposingText("", 1)
                    resetComposition()
                    isSpellingToDict = true
                    t9Enabled = false
                    isNumericMode = false
                    inputMode = InputMode.ABC
                    refreshModeUI()
                    renderCandidates()
                }
            }
            candidateContainer.addView(spellChip)
            return
        }

        // Gboard / Swype zero-digit Next-Word Prediction strip in T9 mode
        if (inputMode == InputMode.T9 && composingDigits.isEmpty()) {
            val prevW = getPreviousWordBeforeCursor()
            if (prevW.isNotEmpty()) {
                val nextWords = t9Engine.predictNextWords(prevW, limit = 5)
                nextWords.forEach { nextW ->
                    val chip = TextView(this).apply {
                        text = applyCase(nextW)
                        textSize = 14f
                        gravity = Gravity.CENTER
                        setPadding(24, 10, 24, 10)
                        isAllCaps = false
                        setTextColor(normalColor)
                        background = makeRoundedDrawable(palette.secondaryHex, 12f)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 8, 0) }
                        setOnClickListener {
                            performFeedback(SoundKind.SPACE, keyChar = '0')
                            recordWordUsage(nextW, prevW)
                            val committed = applyCase(nextW) + if (autoSpacing) " " else ""
                            currentInputConnection?.commitText(committed, 1)
                            if (caseMode == CaseMode.TITLE) {
                                caseMode = CaseMode.LOWER
                                refreshModeUI()
                            }
                            updateAutoCapitalization()
                            renderCandidates()
                        }
                    }
                    candidateContainer.addView(chip)
                }
            }
            return
        }

        if (inputMode == InputMode.ABC) {
            val word = getAlphaWordBeforeCursor()
            if (word.length >= 2 && !t9Engine.hasWord(word)) {
                val addBtn = TextView(this).apply {
                    text = "+ Add \"$word\" to T9"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(26, 10, 26, 10)
                    setTextColor(activeTextColor)
                    background = makeRoundedDrawable(activeBgHex, 12f)
                    setOnClickListener {
                        performFeedback(SoundKind.SPACE, keyChar = '0')
                        commitMultiTapChar()
                        saveCustomWord(word)
                        if (autoSpacing) {
                            currentInputConnection?.commitText(" ", 1)
                        }
                        if (isSpellingToDict) {
                            isSpellingToDict = false
                            t9Enabled = true
                            isNumericMode = false
                            inputMode = InputMode.T9
                            prefs.edit()
                                .putBoolean(SettingsActivity.KEY_T9_ENABLED, true)
                                .putBoolean(SettingsActivity.KEY_IS_NUMERIC, false)
                                .apply()
                            refreshModeUI()
                        }
                        updateAutoCapitalization()
                        renderCandidates()
                    }
                }
                candidateContainer.addView(addBtn)
            } else if (isSpellingToDict) {
                val hint = TextView(this).apply {
                    text = "Spell word in ABC…"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(16, 10, 16, 10)
                    setTextColor(Color.parseColor(palette.subTextHex))
                }
                candidateContainer.addView(hint)
            }
        }
    }

    private fun refreshModeUI() {
        if (!::modeButton.isInitialized) return
        val modeLabel = when (inputMode) {
            InputMode.T9 -> "T9"
            InputMode.ABC -> "ABC"
            InputMode.NUM -> "123"
        }
        // Switch key displays target mode: 123 when typing letters, ABC when typing numbers
        modeButton.text = if (inputMode == InputMode.NUM) "ABC" else "123"

        val palette = currentPalette()

        // Update T9 toggle button next to Settings icon
        val t9IsActive = t9Enabled && inputMode != InputMode.NUM
        t9ToggleButton.background = makeRoundedDrawable(
            if (t9IsActive) palette.enterHex else if (t9Enabled) palette.actionHex else palette.secondaryHex,
            10f
        )
        t9ToggleButton.setTextColor(
            Color.parseColor(
                if (t9IsActive) palette.enterTextHex else if (t9Enabled) palette.actionTextHex else palette.subTextHex
            )
        )

        // Update on-key letter sub-labels dynamically to match active case (abc / Abc / ABC)
        val keyViews = listOf(
            R.id.key_1 to '1', R.id.key_2 to '2', R.id.key_3 to '3',
            R.id.key_4 to '4', R.id.key_5 to '5', R.id.key_6 to '6',
            R.id.key_7 to '7', R.id.key_8 to '8', R.id.key_9 to '9',
            R.id.key_0 to '0'
        )
        for ((id, d) in keyViews) {
            val container = rootView.findViewById<LinearLayout>(id)
            val subTv = container.getChildAt(1) as? TextView ?: continue
            val subText = formatDigitSubLabel(d)
            if (subText.isEmpty()) {
                subTv.visibility = View.GONE
            } else {
                subTv.visibility = View.VISIBLE
                subTv.text = subText
            }
        }

        val caseLabel = when (caseMode) {
            CaseMode.LOWER -> "abc"
            CaseMode.TITLE -> "Abc"
            CaseMode.UPPER -> "ABC"
        }

        if (::hashButton.isInitialized) {
            hashButton.text = if (inputMode == InputMode.T9) "# ↻\nnext·clip" else "#\nclip"
        }

        if (inputMode == InputMode.NUM) {
            shiftButton.text = "*"
            shiftButton.background = makeRoundedDrawable(palette.secondaryHex, 16f)
            shiftButton.setTextColor(Color.parseColor(palette.keyTextHex))
            caseBadge.text = "123"
            caseBadge.background = makeRoundedDrawable(palette.actionHex, 10f)
            caseBadge.setTextColor(Color.parseColor(palette.actionTextHex))
        } else {
            shiftButton.text = when (caseMode) {
                CaseMode.LOWER -> "* ⇧\nabc"
                CaseMode.TITLE -> "* ⇧\nAbc"
                CaseMode.UPPER -> "* ⇪\nABC"
            }
            when (caseMode) {
                CaseMode.UPPER -> {
                    shiftButton.background = makeRoundedDrawable(palette.enterHex, 16f)
                    shiftButton.setTextColor(Color.parseColor(palette.enterTextHex))
                    caseBadge.background = makeRoundedDrawable(palette.enterHex, 10f)
                    caseBadge.setTextColor(Color.parseColor(palette.enterTextHex))
                }
                CaseMode.TITLE -> {
                    shiftButton.background = makeRoundedDrawable(palette.shiftActiveBg, 16f)
                    shiftButton.setTextColor(Color.parseColor(palette.shiftActiveText))
                    caseBadge.background = makeRoundedDrawable(palette.shiftActiveBg, 10f)
                    caseBadge.setTextColor(Color.parseColor(palette.shiftActiveText))
                }
                CaseMode.LOWER -> {
                    shiftButton.background = makeRoundedDrawable(palette.secondaryHex, 16f)
                    shiftButton.setTextColor(Color.parseColor(palette.keyTextHex))
                    caseBadge.background = makeRoundedDrawable(palette.secondaryHex, 10f)
                    caseBadge.setTextColor(Color.parseColor(palette.keyTextHex))
                }
            }
            caseBadge.text = "$modeLabel · $caseLabel"
        }

        // Always use flat monochrome vector drawables on Enter (never colored emojis)
        val actionId = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        val enterIconRes = when (actionId) {
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_enter_send
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_enter_search
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_enter_next
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_enter_done
            else -> R.drawable.ic_enter_return
        }
        enterButton.setImageResource(enterIconRes)
        ImageViewCompat.setImageTintList(
            enterButton,
            ColorStateList.valueOf(Color.parseColor(palette.enterTextHex))
        )
        if (symbolsOpen) {
            symButton.text = if (inputMode == InputMode.NUM) "123" else "ABC"
            symBackBtn.text = "Back ($modeLabel)"
        }
    }

    enum class SoundKind { DIGIT, ACTION, SPACE, ENTER }

    private fun performFeedback(kind: SoundKind = SoundKind.DIGIT, strong: Boolean = false, keyChar: Char? = null) {
        if (hapticEnabled && hapticStrength > 0) {
            triggerHardwareVibration(this, hapticStrength, strong)
        }
        if (soundEnabled && soundVolume > 0) {
            val cacheKey = "$soundSetId:${kind.name}:${keyChar ?: '_'}"
            val pcm = pcmCache.getOrPut(cacheKey) {
                SoundSynth.buildPcm(soundSetId, kind, keyChar)
            }
            playSynthesizedPcm(pcm, soundVolume)
        }
    }

    private fun playSynthesizedPcm(pcmBytes: ByteArray, volumePct: Int) {
        if (pcmBytes.isEmpty()) return
        val vol = (volumePct / 100f).coerceIn(0.05f, 1f)
        audioExecutor.execute {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(SoundSynth.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcmBytes.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcmBytes, 0, pcmBytes.size)
                track.setVolume(vol)
                track.play()
                val releaseMs = if (soundSetId == "piano") 180L else 85L
                audioExecutor.schedule({
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Throwable) {
                    }
                }, releaseMs, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        fun triggerHardwareVibration(context: Context, strengthPct: Int, strong: Boolean = false) {
            if (strengthPct <= 0) return
            val durationMs = (7L + (strengthPct.coerceIn(1, 100) * 28L) / 100L) + if (strong) 10L else 0L
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            val amplitude = if (vibrator.hasAmplitudeControl()) {
                ((strengthPct.coerceIn(1, 100) * 255) / 100).coerceIn(1, 255)
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }
    }

    object SoundSynth {
        const val SAMPLE_RATE = 22050
        private val PIANO_NOTES = doubleArrayOf(
            523.25, 587.33, 659.25, 783.99, 880.0, 1046.5,
            1174.66, 1318.51, 1567.98, 440.0, 392.0, 493.88
        )

        private fun padToMinAudioTrackSize(rawBytes: ByteArray): ByteArray {
            val minBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)
            if (rawBytes.size >= minBytes) return rawBytes
            val padded = ByteArray(minBytes)
            System.arraycopy(rawBytes, 0, padded, 0, rawBytes.size)
            return padded
        }

        private fun noteFreqForKey(kind: SoundKind, keyChar: Char?): Double {
            val idx = when (keyChar) {
                '1' -> 0; '2' -> 1; '3' -> 2; '4' -> 3; '5' -> 4; '6' -> 5
                '7' -> 6; '8' -> 7; '9' -> 8; '*' -> 9; '0' -> 10; '#' -> 11
                else -> -1
            }
            if (idx >= 0) return PIANO_NOTES[idx]
            if (keyChar != null) return PIANO_NOTES[keyChar.code % PIANO_NOTES.size]
            return when (kind) {
                SoundKind.ENTER -> 659.25
                SoundKind.SPACE -> 392.0
                SoundKind.ACTION -> 493.88
                SoundKind.DIGIT -> 587.33
            }
        }

        fun buildPcm(soundSet: String, kind: SoundKind, keyChar: Char? = null): ByteArray {
            val durationMs = when (soundSet) {
                "piano" -> 130
                "beeps" -> if (kind == SoundKind.ENTER) 55 else 38
                else -> 24
            }
            val numSamples = (SAMPLE_RATE * durationMs) / 1000
            val out = ByteArray(numSamples * 2)
            val noteHz = noteFreqForKey(kind, keyChar)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val rawSample = when (soundSet) {
                    "piano" -> {
                        val env = exp(-t * 24.0)
                        val attack = (i.toDouble() / 60.0).coerceAtMost(1.0)
                        val wave = sin(2.0 * PI * noteHz * t) * 0.65 +
                            sin(2.0 * PI * (noteHz * 2.0) * t) * 0.25 * exp(-t * 10.0) +
                            sin(2.0 * PI * (noteHz * 3.0) * t) * 0.10 * exp(-t * 18.0)
                        wave * env * attack * 26000.0
                    }
                    "beeps" -> {
                        val baseHz = when (kind) {
                            SoundKind.ENTER -> if (i > numSamples / 2) 1040.0 else 840.0
                            SoundKind.SPACE -> 520.0
                            SoundKind.ACTION -> 620.0
                            SoundKind.DIGIT -> 660.0 + ((keyChar?.code ?: 53) % 12) * 32.0
                        }
                        val env = exp(-t * 65.0)
                        val attack = (i.toDouble() / 40.0).coerceAtMost(1.0)
                        sin(2.0 * PI * baseHz * t) * env * attack * 23000.0
                    }
                    else -> {
                        val baseHz = when (kind) {
                            SoundKind.ENTER -> 680.0
                            SoundKind.SPACE -> 360.0
                            SoundKind.ACTION -> 460.0
                            SoundKind.DIGIT -> 580.0 + ((keyChar?.code ?: 53) % 9) * 18.0
                        }
                        val env = exp(-t * 175.0)
                        val body = sin(2.0 * PI * baseHz * (1.0 - t * 18.0) * t)
                        val snap = if (i < numSamples / 3) sin(2.0 * PI * (baseHz * 2.6) * t) * 0.45 else 0.0
                        (body * 0.72 + snap) * env * 27000.0
                    }
                }
                val clamped = rawSample.toInt().coerceIn(-32767, 32767)
                out[i * 2] = (clamped and 0xff).toByte()
                out[i * 2 + 1] = ((clamped shr 8) and 0xff).toByte()
            }
            return padToMinAudioTrackSize(out)
        }
    }
}
