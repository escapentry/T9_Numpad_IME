package com.t9numpad.ime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class T9ImeService : InputMethodService() {

    enum class InputMode { T9, ABC, NUM }
    enum class CaseMode { LOWER, TITLE, UPPER }

    private lateinit var rootView: View
    private lateinit var stripBar: View
    private lateinit var numpadGrid: GridLayout
    private lateinit var symbolsPanel: LinearLayout
    private lateinit var symbolsGrid: GridLayout
    private lateinit var symbolPageBtn: Button
    private lateinit var candidateContainer: LinearLayout
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var caseBadge: TextView
    private lateinit var modeButton: Button
    private lateinit var symButton: Button
    private lateinit var shiftButton: Button
    private lateinit var enterButton: Button
    private lateinit var settingsButton: View

    private val t9Engine = T9Engine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var audioManager: AudioManager? = null

    private var inputMode = InputMode.T9
    private var caseMode = CaseMode.TITLE
    private var symbolsOpen = false
    private var symbolPage = 0

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
    private var hapticEnabled = true
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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        t9Engine.loadDictionary(assets.open("t9_words.txt"))
    }

    override fun onCreateInputView(): View {
        rootView = layoutInflater.inflate(R.layout.keyboard_t9_numpad, null)
        stripBar = rootView.findViewById(R.id.strip_bar)
        numpadGrid = rootView.findViewById(R.id.numpad_grid)
        symbolsPanel = rootView.findViewById(R.id.symbols_panel)
        symbolsGrid = rootView.findViewById(R.id.symbols_grid)
        symbolPageBtn = rootView.findViewById(R.id.btn_sym_page)
        candidateContainer = rootView.findViewById(R.id.candidate_container)
        candidateScroll = rootView.findViewById(R.id.candidate_scroll)
        caseBadge = rootView.findViewById(R.id.case_badge)
        modeButton = rootView.findViewById(R.id.key_mode)
        symButton = rootView.findViewById(R.id.key_sym)
        shiftButton = rootView.findViewById(R.id.key_star)
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
        resetComposition()
        symbolsOpen = false
        updateSymbolsVisibility()

        val inputType = info?.inputType ?: 0
        val isNumeric = (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER ||
            (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_PHONE
        inputMode = if (isNumeric) InputMode.NUM else InputMode.T9
        caseMode = if (autoCapitalize) CaseMode.TITLE else CaseMode.LOWER
        updateAutoCapitalization()
        refreshModeUI()
    }

    private fun loadAndApplySettings() {
        themeId = prefs.getString(SettingsActivity.KEY_THEME, "material_light") ?: "material_light"
        if (themeId != "material_light" && themeId != "material_dark" && themeId != "amoled") {
            themeId = "material_light"
        }
        keyHeightId = prefs.getString(SettingsActivity.KEY_HEIGHT, "standard") ?: "standard"
        soundEnabled = prefs.getBoolean(SettingsActivity.KEY_SOUND, true)
        hapticEnabled = prefs.getBoolean(SettingsActivity.KEY_HAPTIC, true)
        autoCapitalize = prefs.getBoolean(SettingsActivity.KEY_AUTO_CAP, true)
        autoSpacing = prefs.getBoolean(SettingsActivity.KEY_AUTO_SPACE, true)
        autoCorrectWhenT9Off = prefs.getBoolean(SettingsActivity.KEY_AUTO_CORRECT_ABC, true)

        if (::rootView.isInitialized) {
            applyThemeAndHeight()
        }
    }

    private data class ThemePalette(
        val trayHex: String,
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
        "material_dark" -> ThemePalette("#1B1C1F", "#2E3036", "#3A4352", "#25272C", "#A8C7FA", "#E8EAED", "#9AA0A6", "#D3E3FD", "#062E6F", "#3A4352", "#A8C7FA")
        "amoled" -> ThemePalette("#000000", "#141417", "#1D2433", "#101013", "#2563EB", "#F4F4F5", "#A1A1AA", "#BFDBFE", "#FFFFFF", "#1D2433", "#93C5FD")
        else -> ThemePalette("#ECEFF4", "#FFFFFF", "#D3E3FD", "#DFE5EE", "#0B57D0", "#1F1F1F", "#5F6368", "#041E49", "#FFFFFF", "#D3E3FD", "#0B57D0")
    }

    private fun applyThemeAndHeight() {
        val heightDp = when (keyHeightId) {
            "compact" -> 46
            "tall" -> 64
            else -> 54
        }
        val heightPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            heightDp.toFloat(),
            resources.displayMetrics
        ).toInt()

        val palette = currentPalette()

        rootView.setBackgroundColor(Color.parseColor(palette.trayHex))
        stripBar.setBackgroundColor(Color.parseColor(palette.trayHex))

        val digitIds = listOf(
            R.id.key_1, R.id.key_2, R.id.key_3,
            R.id.key_4, R.id.key_5, R.id.key_6,
            R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0
        )
        for (id in digitIds) {
            val container = rootView.findViewById<LinearLayout>(id)
            container.layoutParams = (container.layoutParams as GridLayout.LayoutParams).apply {
                height = heightPx
            }
            container.background = makeRoundedDrawable(palette.keyHex, 16f)
            if (container.childCount >= 2) {
                (container.getChildAt(0) as? TextView)?.setTextColor(Color.parseColor(palette.keyTextHex))
                (container.getChildAt(1) as? TextView)?.setTextColor(Color.parseColor(palette.subTextHex))
            }
        }

        val actionIds = listOf(R.id.key_mode, R.id.key_sym, R.id.key_backspace)
        for (id in actionIds) {
            val btn = rootView.findViewById<Button>(id)
            btn.layoutParams = (btn.layoutParams as GridLayout.LayoutParams).apply {
                height = heightPx
            }
            btn.background = makeRoundedDrawable(palette.actionHex, 16f)
            btn.setTextColor(Color.parseColor(palette.actionTextHex))
        }

        val secIds = listOf(R.id.key_star, R.id.key_hash)
        for (id in secIds) {
            val btn = rootView.findViewById<Button>(id)
            btn.layoutParams = (btn.layoutParams as GridLayout.LayoutParams).apply {
                height = heightPx
            }
            btn.background = makeRoundedDrawable(palette.secondaryHex, 16f)
            btn.setTextColor(Color.parseColor(palette.keyTextHex))
        }

        val enterBtn = rootView.findViewById<Button>(R.id.key_enter)
        enterBtn.layoutParams = (enterBtn.layoutParams as GridLayout.LayoutParams).apply {
            height = heightPx
        }
        enterBtn.background = makeRoundedDrawable(palette.enterHex, 28f)
        enterBtn.setTextColor(Color.parseColor(palette.enterTextHex))

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
            keyView.setOnClickListener { view ->
                performFeedback(view)
                handleDigitTap(digit)
            }
            keyView.setOnLongClickListener { view ->
                performFeedback(view, strong = true)
                commitActiveWord()
                currentInputConnection?.commitText(digit.toString(), 1)
                updateAutoCapitalization()
                true
            }
        }
    }

    private fun bindActionKeys() {
        settingsButton.setOnClickListener { view ->
            performFeedback(view)
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        shiftButton.setOnClickListener { view ->
            performFeedback(view)
            cycleCaseMode()
        }
        shiftButton.setOnLongClickListener { view ->
            performFeedback(view, strong = true)
            commitActiveWord()
            currentInputConnection?.commitText("*", 1)
            true
        }

        val hashButton = rootView.findViewById<View>(R.id.key_hash)
        hashButton.setOnClickListener { view ->
            performFeedback(view)
            if (inputMode == InputMode.T9 && candidates.isNotEmpty()) {
                candidateIndex = (candidateIndex + 1) % candidates.size
                updateComposingWord()
                renderCandidates()
            } else {
                commitActiveWord()
                currentInputConnection?.commitText("#", 1)
            }
        }
        hashButton.setOnLongClickListener { view ->
            performFeedback(view, strong = true)
            commitActiveWord()
            currentInputConnection?.commitText("#", 1)
            true
        }

        modeButton.setOnClickListener { view ->
            performFeedback(view)
            commitActiveWord()
            commitMultiTapChar()
            inputMode = when (inputMode) {
                InputMode.T9 -> InputMode.ABC
                InputMode.ABC -> InputMode.NUM
                InputMode.NUM -> InputMode.T9
            }
            symbolsOpen = false
            updateSymbolsVisibility()
            refreshModeUI()
        }

        symButton.setOnClickListener { view ->
            performFeedback(view)
            commitActiveWord()
            commitMultiTapChar()
            symbolsOpen = !symbolsOpen
            updateSymbolsVisibility()
            renderSymbolPage()
        }

        val backspaceBtn = rootView.findViewById<View>(R.id.key_backspace)
        attachContinuousBackspace(backspaceBtn)

        enterButton.setOnClickListener { view ->
            performFeedback(view, strong = true)
            commitActiveWord()
            commitMultiTapChar()
            maybeAutoCorrectAbcWord()
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

        caseBadge.setOnClickListener { view ->
            if (inputMode != InputMode.NUM) {
                performFeedback(view)
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
                    performFeedback(v)
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
        rootView.findViewById<View>(R.id.btn_sym_back).setOnClickListener { view ->
            performFeedback(view)
            symbolsOpen = false
            updateSymbolsVisibility()
        }
        symbolPageBtn.setOnClickListener { view ->
            performFeedback(view)
            symbolPage = (symbolPage + 1) % symbolPages.size
            renderSymbolPage()
        }
        rootView.findViewById<View>(R.id.btn_sym_space).setOnClickListener { view ->
            performFeedback(view)
            handleSpaceTap()
        }
        attachContinuousBackspace(rootView.findViewById(R.id.btn_sym_backspace))
    }

    private fun updateSymbolsVisibility() {
        numpadGrid.visibility = if (symbolsOpen) View.GONE else View.VISIBLE
        symbolsPanel.visibility = if (symbolsOpen) View.VISIBLE else View.GONE
        symButton.text = if (symbolsOpen) when (inputMode) {
            InputMode.T9 -> "T9"
            InputMode.ABC -> "ABC"
            InputMode.NUM -> "123"
        } else "@#&"
    }

    private fun renderSymbolPage() {
        if (!::symbolsGrid.isInitialized) return
        symbolsGrid.removeAllViews()
        symbolPageBtn.text = "Page ${symbolPage + 1}/${symbolPages.size}"

        val palette = currentPalette()
        val items = symbolPages[symbolPage]
        val heightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics).toInt()
        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics).toInt()

        items.forEach { sym ->
            val btn = androidx.appcompat.widget.AppCompatButton(this).apply {
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
                background = makeRoundedDrawable(palette.keyHex, 12f)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0
                    height = heightPx
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                setOnClickListener { v ->
                    performFeedback(v)
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
        }
    }

    private fun handleSpaceTap() {
        val ic = currentInputConnection ?: return
        if (inputMode == InputMode.ABC) {
            commitMultiTapChar()
            maybeAutoCorrectAbcWord()
        }
        commitActiveWord()
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (!before.endsWith(" ")) {
            ic.commitText(" ", 1)
        }
        updateAutoCapitalization()
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
        candidateContainer.removeAllViews()
        val palette = currentPalette()
        val normalColor = Color.parseColor(palette.keyTextHex)
        val activeTextColor = Color.parseColor(palette.actionTextHex)
        val activeBgHex = palette.actionHex

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
                setOnClickListener { view ->
                    performFeedback(view)
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

        val actionId = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        enterButton.text = when (actionId) {
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "↵"
        }
    }

    private fun performFeedback(view: View, strong: Boolean = false) {
        if (hapticEnabled) {
            val flag = if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP
            view.performHapticFeedback(flag)
        }
        if (soundEnabled) {
            audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.7f)
        }
    }
}
