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
    private lateinit var modeButton: Button
    private lateinit var symButton: Button
    private lateinit var shiftButton: Button
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

    // Settings state
    private var themeId = "material_light"
    private var keyHeightId = "standard"
    private var soundEnabled = true
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
        modeButton = rootView.findViewById(R.id.key_mode)
        symButton = rootView.findViewById(R.id.key_sym)
        shiftButton = rootView.findViewById(R.id.key_star)
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
        keyHeightId = prefs.getString(SettingsActivity.KEY_HEIGHT, "standard") ?: "standard"
        soundEnabled = prefs.getBoolean(SettingsActivity.KEY_SOUND, true)
        autoCapitalize = prefs.getBoolean(SettingsActivity.KEY_AUTO_CAP, true)
        autoSpacing = prefs.getBoolean(SettingsActivity.KEY_AUTO_SPACE, true)
        autoCorrectWhenT9Off = prefs.getBoolean(SettingsActivity.KEY_AUTO_CORRECT_ABC, true)

        if (::rootView.isInitialized) {
            applyThemeAndHeight()
        }
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

        val (trayHex, keyHex, actionHex, secondaryHex, enterHex, keyTextHex, subTextHex, actionTextHex, enterTextHex) =
            when (themeId) {
                "material_dark" -> listOf("#1B1C1F", "#2E3036", "#3A4352", "#25272C", "#A8C7FA", "#E8EAED", "#9AA0A6", "#D3E3FD", "#062E6F")
                "amoled" -> listOf("#000000", "#141417", "#1D2433", "#101013", "#2563EB", "#F4F4F5", "#A1A1AA", "#BFDBFE", "#FFFFFF")
                "classic_3310" -> listOf("#8EA47B", "#283320", "#3A482F", "#323F28", "#192113", "#D4E6C3", "#9BB588", "#E1F0D2", "#D4E6C3")
                else -> listOf("#ECEFF4", "#FFFFFF", "#D3E3FD", "#DFE5EE", "#0B57D0", "#1F1F1F", "#5F6368", "#041E49", "#FFFFFF")
            }

        rootView.setBackgroundColor(Color.parseColor(trayHex))
        stripBar.setBackgroundColor(Color.parseColor(trayHex))

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
            container.background = makeRoundedDrawable(keyHex, 16f)
            if (container.childCount >= 2) {
                (container.getChildAt(0) as? TextView)?.setTextColor(Color.parseColor(keyTextHex))
                (container.getChildAt(1) as? TextView)?.setTextColor(Color.parseColor(subTextHex))
            }
        }

        val actionIds = listOf(R.id.key_mode, R.id.key_sym, R.id.key_backspace)
        for (id in actionIds) {
            val btn = rootView.findViewById<Button>(id)
            btn.layoutParams = (btn.layoutParams as GridLayout.LayoutParams).apply {
                height = heightPx
            }
            btn.background = makeRoundedDrawable(actionHex, 16f)
            btn.setTextColor(Color.parseColor(actionTextHex))
        }

        val secIds = listOf(R.id.key_star, R.id.key_hash)
        for (id in secIds) {
            val btn = rootView.findViewById<Button>(id)
            btn.layoutParams = (btn.layoutParams as GridLayout.LayoutParams).apply {
                height = heightPx
            }
            btn.background = makeRoundedDrawable(secondaryHex, 16f)
            btn.setTextColor(Color.parseColor(keyTextHex))
        }

        val enterBtn = rootView.findViewById<Button>(R.id.key_enter)
        enterBtn.layoutParams = (enterBtn.layoutParams as GridLayout.LayoutParams).apply {
            height = heightPx
        }
        enterBtn.background = makeRoundedDrawable(enterHex, 28f)
        enterBtn.setTextColor(Color.parseColor(enterTextHex))

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
        backspaceBtn.setOnClickListener { view ->
            performFeedback(view)
            handleBackspace()
        }
        backspaceBtn.setOnLongClickListener { view ->
            performFeedback(view, strong = true)
            deletePreviousWord()
            true
        }

        rootView.findViewById<View>(R.id.key_enter).setOnClickListener { view ->
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
        rootView.findViewById<View>(R.id.btn_sym_backspace).setOnClickListener { view ->
            performFeedback(view)
            handleBackspace()
        }
    }

    private fun updateSymbolsVisibility() {
        numpadGrid.visibility = if (symbolsOpen) View.GONE else View.VISIBLE
        symbolsPanel.visibility = if (symbolsOpen) View.VISIBLE else View.GONE
        symButton.text = if (symbolsOpen) "ABC" else "@#&"
    }

    private fun renderSymbolPage() {
        if (!::symbolsGrid.isInitialized) return
        symbolsGrid.removeAllViews()
        symbolPageBtn.text = "Page ${symbolPage + 1}/${symbolPages.size}"

        val isDark = themeId == "material_dark" || themeId == "amoled" || themeId == "classic_3310"
        val bgHex = when (themeId) {
            "material_dark" -> "#2E3036"
            "amoled" -> "#141417"
            "classic_3310" -> "#283320"
            else -> "#FFFFFF"
        }
        val fgHex = if (themeId == "classic_3310") "#D4E6C3" else if (isDark) "#E8EAED" else "#1F1F1F"

        val items = symbolPages[symbolPage]
        val heightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics).toInt()
        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics).toInt()

        items.forEach { sym ->
            val btn = Button(this).apply {
                text = sym
                textSize = 16f
                isAllCaps = false
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                setTextColor(Color.parseColor(fgHex))
                background = makeRoundedDrawable(bgHex, 12f)
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
            insertPunctuationOrSymbol(".")
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
        updateAutoCapitalization()
    }

    private fun deletePreviousWord() {
        val ic = currentInputConnection ?: return
        if (composingDigits.isNotEmpty()) {
            resetComposition()
            ic.setComposingText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return
        val trimmedLen = before.trimEnd().length
        val lastSpace = before.substring(0, trimmedLen).lastIndexOf(' ')
        val deleteCount = if (lastSpace >= 0) before.length - lastSpace - 1 else before.length
        if (deleteCount > 0) ic.deleteSurroundingText(deleteCount, 0)
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
        val isDark = themeId == "material_dark" || themeId == "amoled" || themeId == "classic_3310"
        val normalColor = if (themeId == "classic_3310") Color.parseColor("#D4E6C3") else if (isDark) Color.parseColor("#E8EAED") else Color.parseColor("#1F1F1F")
        val activeTextColor = if (themeId == "amoled") Color.parseColor("#BFDBFE") else if (themeId == "classic_3310") Color.parseColor("#E1F0D2") else if (isDark) Color.parseColor("#D3E3FD") else Color.parseColor("#041E49")
        val activeBgHex = when (themeId) {
            "material_dark" -> "#3A4352"
            "amoled" -> "#1D2433"
            "classic_3310" -> "#283320"
            else -> "#D3E3FD"
        }

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
        modeButton.text = when (inputMode) {
            InputMode.T9 -> "T9"
            InputMode.ABC -> "ABC"
            InputMode.NUM -> "123"
        }
    }

    private fun performFeedback(view: View, strong: Boolean = false) {
        val flag = if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP
        view.performHapticFeedback(flag)
        if (soundEnabled) {
            audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.7f)
        }
    }
}
