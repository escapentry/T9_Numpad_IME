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
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.widget.ImageViewCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
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

    private var digitPcm: ByteArray = ByteArray(0)
    private var actionPcm: ByteArray = ByteArray(0)
    private var enterPcm: ByteArray = ByteArray(0)

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
    private var hapticEnabled = true
    private var hapticStrength = 65
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
        digitPcm = SoundSynth.buildClickPcm(freqHz = 560.0, durationMs = 16)
        actionPcm = SoundSynth.buildClickPcm(freqHz = 420.0, durationMs = 14)
        enterPcm = SoundSynth.buildClickPcm(freqHz = 640.0, durationMs = 20)
    }

    override fun onDestroy() {
        super.onDestroy()
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
        resetComposition()
        isSpellingToDict = false
        symbolsOpen = false
        updateSymbolsVisibility()

        // Only temporarily force NUM for strict numeric/phone fields;
        // otherwise restore the user's remembered mode (T9, ABC, or NUM).
        val inputType = info?.inputType ?: 0
        val isNumeric = (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER ||
            (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_PHONE
        inputMode = if (isNumeric) {
            InputMode.NUM
        } else {
            when (prefs.getString(SettingsActivity.KEY_INPUT_MODE, InputMode.T9.name)) {
                InputMode.ABC.name -> InputMode.ABC
                InputMode.NUM.name -> InputMode.NUM
                else -> InputMode.T9
            }
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

    private fun saveCustomWord(word: String) {
        val clean = word.lowercase().trim().filter { it in 'a'..'z' }
        if (clean.length < 2) return
        val existing = prefs.getStringSet(SettingsActivity.KEY_CUSTOM_WORDS, emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(clean)
        prefs.edit().putStringSet(SettingsActivity.KEY_CUSTOM_WORDS, existing).apply()
        t9Engine.insertWord(clean, 10000)
    }

    private fun loadAndApplySettings() {
        themeId = prefs.getString(SettingsActivity.KEY_THEME, "material_light") ?: "material_light"
        if (themeId != "material_light" && themeId != "material_dark" && themeId != "amoled") {
            themeId = "material_light"
        }
        keyHeightId = prefs.getString(SettingsActivity.KEY_HEIGHT, "standard") ?: "standard"
        soundEnabled = prefs.getBoolean(SettingsActivity.KEY_SOUND, true)
        soundVolume = prefs.getInt(SettingsActivity.KEY_SOUND_VOLUME, 75)
        hapticEnabled = prefs.getBoolean(SettingsActivity.KEY_HAPTIC, true)
        hapticStrength = prefs.getInt(SettingsActivity.KEY_HAPTIC_STRENGTH, 65)
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

    private fun bindDigitKeys() {
        val digitMap = mapOf(
            R.id.key_1 to '1', R.id.key_2 to '2', R.id.key_3 to '3',
            R.id.key_4 to '4', R.id.key_5 to '5', R.id.key_6 to '6',
            R.id.key_7 to '7', R.id.key_8 to '8', R.id.key_9 to '9',
            R.id.key_0 to '0'
        )

        digitMap.forEach { (viewId, digit) ->
            val keyView = rootView.findViewById<View>(viewId)
            keyView.setOnClickListener {
                val kind = if (digit == '0') SoundKind.SPACE else SoundKind.DIGIT
                performFeedback(kind)
                handleDigitTap(digit)
            }
            keyView.setOnLongClickListener {
                performFeedback(SoundKind.DIGIT, strong = true)
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

        shiftButton.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            cycleCaseMode()
        }
        shiftButton.setOnLongClickListener {
            performFeedback(SoundKind.ACTION, strong = true)
            commitActiveWord()
            currentInputConnection?.commitText("*", 1)
            true
        }

        val hashButton = rootView.findViewById<View>(R.id.key_hash)
        hashButton.setOnClickListener {
            performFeedback(SoundKind.ACTION)
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
            performFeedback(SoundKind.ACTION, strong = true)
            commitActiveWord()
            currentInputConnection?.commitText("#", 1)
            true
        }

        modeButton.setOnClickListener {
            performFeedback(SoundKind.ACTION)
            commitActiveWord()
            commitMultiTapChar()
            isSpellingToDict = false
            inputMode = when (inputMode) {
                InputMode.T9 -> InputMode.ABC
                InputMode.ABC -> InputMode.NUM
                InputMode.NUM -> InputMode.T9
            }
            prefs.edit().putString(SettingsActivity.KEY_INPUT_MODE, inputMode.name).apply()
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
                if (spelled.length >= 2 && !t9Engine.hasWord(spelled)) {
                    saveCustomWord(spelled)
                }
                isSpellingToDict = false
                inputMode = InputMode.T9
                prefs.edit().putString(SettingsActivity.KEY_INPUT_MODE, inputMode.name).apply()
                refreshModeUI()
            } else {
                maybeAutoCorrectAbcWord()
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
            performFeedback(SoundKind.SPACE)
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
        symButton.text = if (symbolsOpen) modeLabel else "@#&"
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
                setOnClickListener {
                    performFeedback(SoundKind.DIGIT)
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
                if (spelled.length >= 2 && !t9Engine.hasWord(spelled)) {
                    saveCustomWord(spelled)
                }
                isSpellingToDict = false
                inputMode = InputMode.T9
                prefs.edit().putString(SettingsActivity.KEY_INPUT_MODE, inputMode.name).apply()
                refreshModeUI()
            } else {
                maybeAutoCorrectAbcWord()
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
                        performFeedback(SoundKind.SPACE)
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
                        performFeedback(SoundKind.SPACE)
                        commitMultiTapChar()
                        saveCustomWord(word)
                        if (autoSpacing) {
                            currentInputConnection?.commitText(" ", 1)
                        }
                        if (isSpellingToDict) {
                            isSpellingToDict = false
                            inputMode = InputMode.T9
                            prefs.edit().putString(SettingsActivity.KEY_INPUT_MODE, inputMode.name).apply()
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
        modeButton.text = modeLabel

        val caseLabel = when (caseMode) {
            CaseMode.LOWER -> "abc"
            CaseMode.TITLE -> "Abc"
            CaseMode.UPPER -> "ABC"
        }

        val palette = currentPalette()
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
            symButton.text = modeLabel
            symBackBtn.text = "Back ($modeLabel)"
        }
    }

    enum class SoundKind { DIGIT, ACTION, SPACE, ENTER }

    private fun performFeedback(kind: SoundKind = SoundKind.DIGIT, strong: Boolean = false) {
        if (hapticEnabled && hapticStrength > 0) {
            triggerHardwareVibration(this, hapticStrength, strong)
        }
        if (soundEnabled && soundVolume > 0) {
            val pcm = when (kind) {
                SoundKind.ENTER -> enterPcm
                SoundKind.ACTION -> actionPcm
                SoundKind.SPACE, SoundKind.DIGIT -> digitPcm
            }
            playSynthesizedPcm(pcm, soundVolume)
        }
    }

    private fun playSynthesizedPcm(pcmBytes: ByteArray, volumePct: Int) {
        if (pcmBytes.isEmpty()) return
        val vol = (volumePct / 100f).coerceIn(0f, 1f)
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
                audioExecutor.schedule({
                    try {
                        track.stop()
                        track.release()
                    } catch (_: Throwable) {
                    }
                }, 65L, TimeUnit.MILLISECONDS)
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

        fun buildClickPcm(freqHz: Double, durationMs: Int): ByteArray {
            val numSamples = (SAMPLE_RATE * durationMs) / 1000
            val out = ByteArray(numSamples * 2)
            for (i in 0 until numSamples) {
                val t = i.toDouble() / SAMPLE_RATE
                val env = exp(-t * 210.0)
                val body = sin(2.0 * PI * freqHz * (1.0 - t * 18.0) * t)
                val snap = if (i < numSamples / 4) sin(2.0 * PI * (freqHz * 2.8) * t) * 0.45 else 0.0
                val sample = ((body * 0.72 + snap) * env * 22000.0).toInt().coerceIn(-32767, 32767)
                out[i * 2] = (sample and 0xff).toByte()
                out[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
            }
            return out
        }
    }
}
