package com.t9numpad.ime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.widget.ImageViewCompat
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
    private lateinit var stripBar: View
    private lateinit var numpadGrid: GridLayout
    private lateinit var symbolsPanel: LinearLayout
    private lateinit var symbolsGrid: GridLayout
    private lateinit var symbolsFooter: LinearLayout
    private lateinit var symbolPageBtn: AppCompatButton
    private lateinit var symBackBtn: AppCompatButton
    private lateinit var symSpaceBtn: AppCompatButton
    private lateinit var symBackspaceBtn: AppCompatImageButton
    private lateinit var candidateContainer: LinearLayout
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var caseBadge: TextView
    private lateinit var t9ToggleButton: AppCompatButton
    private lateinit var modeButton: AppCompatButton
    private lateinit var symButton: AppCompatButton
    private lateinit var shiftButton: AppCompatButton
    private lateinit var backspaceButton: AppCompatImageButton
    private lateinit var enterButton: AppCompatImageButton
    private lateinit var settingsButton: AppCompatImageButton

    private val t9Engine = T9Engine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioExecutor = Executors.newSingleThreadScheduledExecutor()
    private lateinit var prefs: SharedPreferences

    private val pcmCache = HashMap<String, ByteArray>()
    private var keyPopupWindow: PopupWindow? = null
    private var keyPopupView: TextView? = null

    private var t9Enabled = true
    private var isNumericMode = false
    private var inputMode = InputMode.T9
    private var caseMode = CaseMode.TITLE
    private var symbolsOpen = false
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

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(SettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        t9Engine.loadDictionary(assets.open("t9_words.txt"))
        syncCustomWordsFromPrefs()
        syncLearnedFreqsFromPrefs()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissKeyPopup()
        audioExecutor.shutdownNow()
    }

    override fun onCreateInputView(): View {
        rootView = layoutInflater.inflate(R.layout.keyboard_t9_numpad, null)
        stripBar = rootView.findViewById(R.id.strip_bar)
        numpadGrid = rootView.findViewById(R.id.numpad_grid)
        symbolsPanel = rootView.findViewById(R.id.symbols_panel)
        symbolsGrid = rootView.findViewById(R.id.symbols_grid)
        symbolsFooter = rootView.findViewById(R.id.symbols_footer)
        symbolPageBtn = rootView.findViewById(R.id.btn_sym_page)
        symBackBtn = rootView.findViewById(R.id.btn_sym_back)
        symSpaceBtn = rootView.findViewById(R.id.btn_sym_space)
        symBackspaceBtn = rootView.findViewById(R.id.btn_sym_backspace)
        candidateContainer = rootView.findViewById(R.id.candidate_container)
        candidateScroll = rootView.findViewById(R.id.candidate_scroll)
        caseBadge = rootView.findViewById(R.id.case_badge)
        t9ToggleButton = rootView.findViewById(R.id.btn_toggle_t9)
        modeButton = rootView.findViewById(R.id.key_mode)
        symButton = rootView.findViewById(R.id.key_sym)
        shiftButton = rootView.findViewById(R.id.key_star)
        backspaceButton = rootView.findViewById(R.id.key_backspace)
        enterButton = rootView.findViewById(R.id.key_enter)
        settingsButton = rootView.findViewById(R.id.btn_ime_settings)

        bindDigitKeys()
        bindActionKeys()
        bindSymbolsPanel()
        loadAndApplySettings()
        refreshModeUI()
        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadAndApplySettings()
        syncCustomWordsFromPrefs()
        syncLearnedFreqsFromPrefs()
        resetComposition()
        isSpellingToDict = false
        symbolsOpen = false
        dismissKeyPopup()
        updateSymbolsVisibility()

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

    private fun recordWordUsage(rawWord: String) {
        val clean = rawWord.lowercase().trim().filter { it in 'a'..'z' }
        if (clean.length < 2) return
        val newCount = t9Engine.recordWordUsage(clean)
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
        prefs.edit().putString(SettingsActivity.KEY_LEARNED_FREQS, serialized).apply()
    }

    private fun saveCustomWord(word: String) {
        val clean = word.lowercase().trim().filter { it in 'a'..'z' }
        if (clean.length < 2) return
        val existing = prefs.getStringSet(SettingsActivity.KEY_CUSTOM_WORDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(clean)
        prefs.edit().putStringSet(SettingsActivity.KEY_CUSTOM_WORDS, existing).apply()
        t9Engine.insertWord(clean, 10000)
        recordWordUsage(clean)
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

        rootView.setBackgroundColor(Color.parseColor(palette.trayHex))
        stripBar.setBackgroundColor(Color.parseColor(palette.stripHex))
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
        enterButton.background = makeRoundedDrawable(palette.enterHex, 28f)
        ImageViewCompat.setImageTintList(
            enterButton,
            ColorStateList.valueOf(Color.parseColor(palette.enterTextHex))
        )

        // Lock Symbols footer height and theme colors to match the main numpad row height
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

        renderSymbolPage()
    }

    private fun makeRoundedDrawable(hex: String, radiusDp: Float): GradientDrawable {
        val r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, resources.displayMetrics)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.parseColor(hex))
        }
    }

    private fun showKeyPopup(anchor: View, label: String) {
        if (!showKeyPopups || label.isEmpty()) return
        val palette = currentPalette()
        val tv = keyPopupView ?: TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 20f
            includeFontPadding = false
            setPadding(28, 16, 28, 16)
            keyPopupView = this
        }
        tv.text = label
        tv.setTextColor(Color.parseColor(palette.keyTextHex))
        tv.background = makeRoundedDrawable(palette.keyHex, 14f).apply {
            setStroke(2, Color.parseColor(palette.subTextHex))
        }
        val pw = keyPopupWindow ?: PopupWindow(
            tv,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            false
        ).apply {
            isTouchable = false
            isClippingEnabled = false
            elevation = 14f
            keyPopupWindow = this
        }
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + (anchor.width - tv.measuredWidth) / 2
        val y = loc[1] - tv.measuredHeight - 10
        if (pw.isShowing) {
            pw.update(x, y, -1, -1)
        } else {
            pw.showAtLocation(rootView, Gravity.NO_GRAVITY, x, y)
        }
    }

    private fun dismissKeyPopup() {
        try {
            keyPopupWindow?.dismiss()
        } catch (_: Throwable) {
        }
    }

    private fun attachPopupTouch(view: View, labelProvider: () -> String) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> showKeyPopup(v, labelProvider())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dismissKeyPopup()
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
            attachPopupTouch(keyView) {
                if (inputMode == InputMode.NUM || digit == '0' || digit == '1') {
                    digit.toString()
                } else {
                    val sub = formatDigitSubLabel(digit)
                    if (sub.isNotEmpty()) "$digit\n$sub" else digit.toString()
                }
            }
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

        val hashButton = rootView.findViewById<View>(R.id.key_hash)
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
        hashButton.setOnLongClickListener {
            performFeedback(SoundKind.ACTION, strong = true, keyChar = '#')
            commitActiveWord()
            currentInputConnection?.commitText("#", 1)
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
            updateSymbolsVisibility()
            refreshModeUI()
            renderCandidates()
        }

        symButton.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            commitActiveWord()
            commitMultiTapChar()
            symbolsOpen = !symbolsOpen
            updateSymbolsVisibility()
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
                    if (alpha.length >= 2) recordWordUsage(alpha)
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
            updateSymbolsVisibility()
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

    private fun updateSymbolsVisibility() {
        numpadGrid.visibility = if (symbolsOpen) View.GONE else View.VISIBLE
        symbolsPanel.visibility = if (symbolsOpen) View.VISIBLE else View.GONE
        val modeLabel = when (inputMode) {
            InputMode.T9 -> "T9"
            InputMode.ABC -> "ABC"
            InputMode.NUM -> "123"
        }
        symButton.text = if (symbolsOpen) (if (inputMode == InputMode.NUM) "123" else "ABC") else "@#&"
        symBackBtn.text = "Back ($modeLabel)"
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
                background = makeRoundedDrawable(palette.keyHex, 14f)
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
            candidates = t9Engine.predict(composingDigits.toString())
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
                maybeAutoCorrectAbcWord()
                val alpha = getAlphaWordBeforeCursor()
                if (alpha.length >= 2) recordWordUsage(alpha)
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
        val before = ic.getTextBeforeCursor(32, 0)?.toString() ?: return
        val match = Regex("(^|[\\s\\n])([a-zA-Z]{2,})$").find(before) ?: return
        val rawWord = match.groupValues[2]
        val corrected = t9Engine.autoCorrect(rawWord) ?: return
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
                candidates = t9Engine.predict(composingDigits.toString())
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
        if (inputMode == InputMode.ABC) renderCandidates()
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
            recordWordUsage(chosen)
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
                        background = makeRoundedDrawable(activeBgHex, 10f)
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
                background = makeRoundedDrawable(palette.secondaryHex, 10f)
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

        if (inputMode == InputMode.ABC) {
            val word = getAlphaWordBeforeCursor()
            if (word.length >= 2 && !t9Engine.hasWord(word)) {
                val addBtn = TextView(this).apply {
                    text = "+ Add \"$word\" to T9"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(26, 10, 26, 10)
                    setTextColor(activeTextColor)
                    background = makeRoundedDrawable(activeBgHex, 10f)
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
            8f
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

        if (inputMode == InputMode.NUM) {
            shiftButton.text = "*"
            shiftButton.background = makeRoundedDrawable(palette.secondaryHex, 16f)
            shiftButton.setTextColor(Color.parseColor(palette.keyTextHex))
            caseBadge.text = "123"
            caseBadge.background = makeRoundedDrawable(palette.actionHex, 8f)
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
                    caseBadge.background = makeRoundedDrawable(palette.enterHex, 8f)
                    caseBadge.setTextColor(Color.parseColor(palette.enterTextHex))
                }
                CaseMode.TITLE -> {
                    shiftButton.background = makeRoundedDrawable(palette.shiftActiveBg, 16f)
                    shiftButton.setTextColor(Color.parseColor(palette.shiftActiveText))
                    caseBadge.background = makeRoundedDrawable(palette.shiftActiveBg, 8f)
                    caseBadge.setTextColor(Color.parseColor(palette.shiftActiveText))
                }
                CaseMode.LOWER -> {
                    shiftButton.background = makeRoundedDrawable(palette.secondaryHex, 16f)
                    shiftButton.setTextColor(Color.parseColor(palette.keyTextHex))
                    caseBadge.background = makeRoundedDrawable(palette.secondaryHex, 8f)
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
