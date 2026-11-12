package com.t9numpad.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

class T9ImeService : InputMethodService() {

    enum class InputMode { T9, ABC, NUM }
    enum class CaseMode { LOWER, TITLE, UPPER }

    private lateinit var rootView: View
    private lateinit var candidateContainer: LinearLayout
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var modeButton: Button
    private lateinit var shiftButton: Button

    private val t9Engine = T9Engine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var inputMode = InputMode.T9
    private var caseMode = CaseMode.TITLE
    private val composingDigits = StringBuilder()
    private var candidates: List<T9Candidate> = emptyList()
    private var candidateIndex = 0

    // Multi-tap state (ABC mode)
    private var lastMultiTapKey: Char? = null
    private var multiTapCycleIndex = 0
    private val multiTapCommitRunnable = Runnable { commitMultiTapChar() }
    private val multiTapTimeoutMs = 700L

    override fun onCreate() {
        super.onCreate()
        t9Engine.loadDictionary(assets.open("t9_words.txt"))
    }

    override fun onCreateInputView(): View {
        rootView = layoutInflater.inflate(R.layout.keyboard_t9_numpad, null)
        candidateContainer = rootView.findViewById(R.id.candidate_container)
        candidateScroll = rootView.findViewById(R.id.candidate_scroll)
        modeButton = rootView.findViewById(R.id.key_mode)
        shiftButton = rootView.findViewById(R.id.key_star)

        bindDigitKeys()
        bindActionKeys()
        refreshModeUI()
        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        resetComposition()
        val inputType = info?.inputType ?: 0
        val isNumeric = (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_NUMBER ||
            (inputType and EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_PHONE
        inputMode = if (isNumeric) InputMode.NUM else InputMode.T9
        updateAutoCapitalization()
        refreshModeUI()
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
                performHaptic(view)
                handleDigitTap(digit)
            }
            // Long-press any 0-9 key to insert its literal digit (Gboard/OG hybrid)
            keyView.setOnLongClickListener { view ->
                performHaptic(view, strong = true)
                commitActiveWord()
                currentInputConnection?.commitText(digit.toString(), 1)
                updateAutoCapitalization()
                true
            }
        }
    }

    private fun bindActionKeys() {
        // * Key: Shift / Case Cycle (long-press inserts '*')
        shiftButton.setOnClickListener { view ->
            performHaptic(view)
            cycleCaseMode()
        }
        shiftButton.setOnLongClickListener { view ->
            performHaptic(view, strong = true)
            commitActiveWord()
            currentInputConnection?.commitText("*", 1)
            true
        }

        // # Key: Next T9 Candidate (long-press inserts '#')
        val hashButton = rootView.findViewById<View>(R.id.key_hash)
        hashButton.setOnClickListener { view ->
            performHaptic(view)
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
            performHaptic(view, strong = true)
            commitActiveWord()
            currentInputConnection?.commitText("#", 1)
            true
        }

        // Right Column (Gboard Numpad ergonomics): Mode, Space, Backspace, Enter
        modeButton.setOnClickListener { view ->
            performHaptic(view)
            commitActiveWord()
            inputMode = when (inputMode) {
                InputMode.T9 -> InputMode.ABC
                InputMode.ABC -> InputMode.NUM
                InputMode.NUM -> InputMode.T9
            }
            refreshModeUI()
        }

        rootView.findViewById<View>(R.id.key_space).setOnClickListener { view ->
            performHaptic(view)
            handleSpaceTap()
        }

        val backspaceBtn = rootView.findViewById<View>(R.id.key_backspace)
        backspaceBtn.setOnClickListener { view ->
            performHaptic(view)
            handleBackspace()
        }
        backspaceBtn.setOnLongClickListener { view ->
            performHaptic(view, strong = true)
            deletePreviousWord()
            true
        }

        rootView.findViewById<View>(R.id.key_enter).setOnClickListener { view ->
            performHaptic(view, strong = true)
            commitActiveWord()
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
            commitActiveWord()
            ic.commitText(".", 1)
            updateAutoCapitalization()
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
        if (inputMode == InputMode.ABC) commitMultiTapChar()
        commitActiveWord()
        ic.commitText(" ", 1)
        updateAutoCapitalization()
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
        candidates.forEachIndexed { idx, candidate ->
            val tv = TextView(this).apply {
                text = applyCase(candidate.word)
                textSize = 15f
                setPadding(28, 12, 28, 12)
                isAllCaps = false
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (idx == candidateIndex) R.color.md_theme_onPrimaryContainer else R.color.md_theme_onSurface
                    )
                )
                if (idx == candidateIndex) {
                    setBackgroundResource(R.drawable.bg_candidate_active)
                }
                setOnClickListener { view ->
                    performHaptic(view)
                    candidateIndex = idx
                    updateComposingWord()
                    commitActiveWord()
                    currentInputConnection?.commitText(" ", 1)
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

    private fun performHaptic(view: View, strong: Boolean = false) {
        val flag = if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP
        view.performHapticFeedback(flag)
    }
}
