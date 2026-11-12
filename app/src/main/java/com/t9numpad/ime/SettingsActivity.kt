package com.t9numpad.ime

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "t9_ime_prefs"
        const val KEY_THEME = "ime_theme"
        const val KEY_HEIGHT = "ime_height"
        const val KEY_T9_ENABLED = "ime_t9_enabled"
        const val KEY_IS_NUMERIC = "ime_is_numeric"
        const val KEY_SOUND = "ime_sound"
        const val KEY_SOUND_VOLUME = "ime_sound_volume"
        const val KEY_SOUND_SET = "ime_sound_set"
        const val KEY_HAPTIC = "ime_haptic"
        const val KEY_HAPTIC_STRENGTH = "ime_haptic_strength"
        const val KEY_KEY_POPUP = "ime_key_popup"
        const val KEY_AUTO_CAP = "ime_auto_cap"
        const val KEY_AUTO_SPACE = "ime_auto_space"
        const val KEY_AUTO_CORRECT_ABC = "ime_auto_correct_abc_only"
        const val KEY_CUSTOM_WORDS = "ime_custom_words"
        const val KEY_LEARNED_FREQS = "ime_learned_freqs"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var cardLight: LinearLayout
    private lateinit var cardDark: LinearLayout
    private lateinit var cardAmoled: LinearLayout
    private lateinit var tvThemeSummary: TextView
    private lateinit var tvHeightSummary: TextView
    private lateinit var tvFeedbackSummary: TextView
    private lateinit var tvTypingSummary: TextView
    private lateinit var tvDictSummary: TextView
    private lateinit var tvCustomWordsList: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        tvThemeSummary = findViewById(R.id.tv_theme_summary)
        tvHeightSummary = findViewById(R.id.tv_height_summary)
        tvFeedbackSummary = findViewById(R.id.tv_feedback_summary)
        tvTypingSummary = findViewById(R.id.tv_typing_summary)
        tvDictSummary = findViewById(R.id.tv_dict_summary)
        tvCustomWordsList = findViewById(R.id.tv_custom_words_list)

        setupAccordion(R.id.sec_theme_header, R.id.sec_theme_body, R.id.tv_theme_chevron, startExpanded = true)
        setupAccordion(R.id.sec_height_header, R.id.sec_height_body, R.id.tv_height_chevron, startExpanded = false)
        setupAccordion(R.id.sec_feedback_header, R.id.sec_feedback_body, R.id.tv_feedback_chevron, startExpanded = false)
        setupAccordion(R.id.sec_typing_header, R.id.sec_typing_body, R.id.tv_typing_chevron, startExpanded = false)
        setupAccordion(R.id.sec_dict_header, R.id.sec_dict_body, R.id.tv_dict_chevron, startExpanded = false)

        cardLight = findViewById(R.id.card_theme_light)
        cardDark = findViewById(R.id.card_theme_dark)
        cardAmoled = findViewById(R.id.card_theme_amoled)

        val currentTheme = prefs.getString(KEY_THEME, "material_light") ?: "material_light"
        selectThemeCard(currentTheme, persist = false)

        cardLight.setOnClickListener { selectThemeCard("material_light", persist = true) }
        cardDark.setOnClickListener { selectThemeCard("material_dark", persist = true) }
        cardAmoled.setOnClickListener { selectThemeCard("amoled", persist = true) }

        val heightGroup = findViewById<RadioGroup>(R.id.rg_height)
        when (prefs.getString(KEY_HEIGHT, "standard")) {
            "compact" -> heightGroup.check(R.id.rb_height_compact)
            "tall" -> heightGroup.check(R.id.rb_height_tall)
            else -> heightGroup.check(R.id.rb_height_standard)
        }
        updateHeightSummary(prefs.getString(KEY_HEIGHT, "standard") ?: "standard")

        heightGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rb_height_compact -> "compact"
                R.id.rb_height_tall -> "tall"
                else -> "standard"
            }
            prefs.edit().putString(KEY_HEIGHT, value).apply()
            updateHeightSummary(value)
        }

        val switchSound = findViewById<SwitchMaterial>(R.id.switch_sound)
        val soundSetGroup = findViewById<RadioGroup>(R.id.rg_sound_set)
        val seekSoundVol = findViewById<SeekBar>(R.id.seek_sound_volume)
        val tvSoundVol = findViewById<TextView>(R.id.tv_sound_volume_val)

        val switchHaptic = findViewById<SwitchMaterial>(R.id.switch_haptic)
        val seekHapticStrength = findViewById<SeekBar>(R.id.seek_haptic_strength)
        val tvHapticStrength = findViewById<TextView>(R.id.tv_haptic_strength_val)
        val switchKeyPopup = findViewById<SwitchMaterial>(R.id.switch_key_popup)

        val soundOn = prefs.getBoolean(KEY_SOUND, true)
        var currentSoundSet = prefs.getString(KEY_SOUND_SET, "clicks") ?: "clicks"
        val soundVol = prefs.getInt(KEY_SOUND_VOLUME, 75)
        val hapticOn = prefs.getBoolean(KEY_HAPTIC, true)
        val hapticStr = prefs.getInt(KEY_HAPTIC_STRENGTH, 65)
        val popupOn = prefs.getBoolean(KEY_KEY_POPUP, true)

        switchSound.isChecked = soundOn
        when (currentSoundSet) {
            "beeps" -> soundSetGroup.check(R.id.rb_sound_beeps)
            "piano" -> soundSetGroup.check(R.id.rb_sound_piano)
            else -> soundSetGroup.check(R.id.rb_sound_clicks)
        }
        seekSoundVol.progress = soundVol
        tvSoundVol.text = "$soundVol%"
        seekSoundVol.isEnabled = soundOn

        switchHaptic.isChecked = hapticOn
        seekHapticStrength.progress = hapticStr
        tvHapticStrength.text = "$hapticStr%"
        seekHapticStrength.isEnabled = hapticOn
        switchKeyPopup.isChecked = popupOn

        fun refreshFeedbackSummary() {
            val setLabel = when (currentSoundSet) {
                "piano" -> "Piano"
                "beeps" -> "Beeps"
                else -> "Clicks"
            }
            val sText = if (switchSound.isChecked) "$setLabel ${seekSoundVol.progress}%" else "Sound Off"
            val hText = if (switchHaptic.isChecked) "Haptic ${seekHapticStrength.progress}%" else "Haptic Off"
            tvFeedbackSummary.text = "$sText · $hText"
        }
        refreshFeedbackSummary()

        switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SOUND, isChecked).apply()
            seekSoundVol.isEnabled = isChecked
            refreshFeedbackSummary()
        }
        soundSetGroup.setOnCheckedChangeListener { _, checkedId ->
            currentSoundSet = when (checkedId) {
                R.id.rb_sound_beeps -> "beeps"
                R.id.rb_sound_piano -> "piano"
                else -> "clicks"
            }
            prefs.edit().putString(KEY_SOUND_SET, currentSoundSet).apply()
            refreshFeedbackSummary()
            if (switchSound.isChecked) playTestSound(currentSoundSet, seekSoundVol.progress)
        }
        seekSoundVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvSoundVol.text = "$progress%"
                if (fromUser) {
                    prefs.edit().putInt(KEY_SOUND_VOLUME, progress).apply()
                    refreshFeedbackSummary()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (switchSound.isChecked) playTestSound(currentSoundSet, seekSoundVol.progress)
            }
        })

        switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_HAPTIC, isChecked).apply()
            seekHapticStrength.isEnabled = isChecked
            refreshFeedbackSummary()
        }
        seekHapticStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvHapticStrength.text = "$progress%"
                if (fromUser) {
                    prefs.edit().putInt(KEY_HAPTIC_STRENGTH, progress).apply()
                    refreshFeedbackSummary()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (switchHaptic.isChecked) {
                    T9ImeService.triggerHardwareVibration(this@SettingsActivity, seekHapticStrength.progress)
                }
            }
        })

        switchKeyPopup.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_KEY_POPUP, isChecked).apply()
        }

        findViewById<Button>(R.id.btn_test_feedback).setOnClickListener {
            if (switchSound.isChecked) playTestSound(currentSoundSet, seekSoundVol.progress)
            if (switchHaptic.isChecked) {
                T9ImeService.triggerHardwareVibration(this, seekHapticStrength.progress, strong = true)
            }
        }

        val switchAutoCap = findViewById<SwitchMaterial>(R.id.switch_auto_cap)
        val switchAutoSpace = findViewById<SwitchMaterial>(R.id.switch_auto_space)
        val switchAutoCorrectAbc = findViewById<SwitchMaterial>(R.id.switch_auto_correct_abc)

        switchAutoCap.isChecked = prefs.getBoolean(KEY_AUTO_CAP, true)
        switchAutoSpace.isChecked = prefs.getBoolean(KEY_AUTO_SPACE, true)
        switchAutoCorrectAbc.isChecked = prefs.getBoolean(KEY_AUTO_CORRECT_ABC, true)

        fun refreshTypingSummary() {
            val active = mutableListOf<String>()
            if (switchAutoCap.isChecked) active.add("Auto-Cap")
            if (switchAutoSpace.isChecked) active.add("Auto-Space")
            if (switchAutoCorrectAbc.isChecked) active.add("ABC Auto-Correct")
            tvTypingSummary.text = if (active.isEmpty()) "All disabled" else active.joinToString(" · ")
        }
        refreshTypingSummary()

        switchAutoCap.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_CAP, isChecked).apply()
            refreshTypingSummary()
        }
        switchAutoSpace.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_SPACE, isChecked).apply()
            refreshTypingSummary()
        }
        switchAutoCorrectAbc.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_CORRECT_ABC, isChecked).apply()
            refreshTypingSummary()
        }

        // Personal Dictionary & Learned Frequency Words section
        val etNewWord = findViewById<EditText>(R.id.et_new_word)
        findViewById<Button>(R.id.btn_add_custom_word).setOnClickListener {
            val clean = etNewWord.text.toString().lowercase().trim().filter { it in 'a'..'z' }
            if (clean.length >= 2) {
                val set = prefs.getStringSet(KEY_CUSTOM_WORDS, emptySet())?.toMutableSet() ?: mutableSetOf()
                set.add(clean)
                prefs.edit().putStringSet(KEY_CUSTOM_WORDS, set).apply()
                etNewWord.setText("")
                refreshCustomWordsUI()
            }
        }
        findViewById<Button>(R.id.btn_clear_custom_words).setOnClickListener {
            prefs.edit()
                .putStringSet(KEY_CUSTOM_WORDS, emptySet())
                .putString(KEY_LEARNED_FREQS, "")
                .apply()
            refreshCustomWordsUI()
        }
        refreshCustomWordsUI()

        findViewById<Button>(R.id.btn_enable_ime).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_switch_ime).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    private fun setupAccordion(headerId: Int, bodyId: Int, chevronId: Int, startExpanded: Boolean) {
        val header = findViewById<View>(headerId)
        val body = findViewById<View>(bodyId)
        val chevron = findViewById<TextView>(chevronId)
        body.visibility = if (startExpanded) View.VISIBLE else View.GONE
        chevron.text = if (startExpanded) "▾" else "▸"
        header.setOnClickListener {
            val expand = body.visibility != View.VISIBLE
            body.visibility = if (expand) View.VISIBLE else View.GONE
            chevron.text = if (expand) "▾" else "▸"
        }
    }

    private fun selectThemeCard(themeId: String, persist: Boolean) {
        if (persist) {
            prefs.edit().putString(KEY_THEME, themeId).apply()
        }
        val map = mapOf(
            "material_light" to cardLight,
            "material_dark" to cardDark,
            "amoled" to cardAmoled
        )
        map.forEach { (id, card) ->
            val selected = id == themeId
            val strokePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                if (selected) 2f else 1f,
                resources.displayMetrics
            ).toInt()
            val radiusPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                14f,
                resources.displayMetrics
            )
            card.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusPx
                setColor(Color.parseColor(if (selected) "#F0F6FF" else "#FFFFFF"))
                setStroke(strokePx, Color.parseColor(if (selected) "#0B57D0" else "#CBD5E1"))
            }
        }
        tvThemeSummary.text = when (themeId) {
            "material_dark" -> "Material Dark"
            "amoled" -> "AMOLED Black"
            else -> "Material Light"
        }
    }

    private fun updateHeightSummary(heightId: String) {
        tvHeightSummary.text = when (heightId) {
            "compact" -> "Compact (46dp) · synced with Symbols"
            "tall" -> "Tall (64dp) · synced with Symbols"
            else -> "Standard (54dp) · synced with Symbols"
        }
    }

    private fun refreshCustomWordsUI() {
        val words = (prefs.getStringSet(KEY_CUSTOM_WORDS, emptySet()) ?: emptySet()).sorted()
        val freqRaw = prefs.getString(KEY_LEARNED_FREQS, "") ?: ""
        val learnedPairs = freqRaw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size == 2 && parts[0].isNotBlank()) "${parts[0]}(×${parts[1]})" else null
        }
        tvDictSummary.text = "${words.size} custom · ${learnedPairs.size} learned frequent words"
        val customLine = if (words.isEmpty()) "Custom: none" else "Custom: ${words.joinToString(", ")}"
        val learnedLine = if (learnedPairs.isEmpty()) {
            "Frequent words: type to learn automatically"
        } else {
            "Frequent: ${learnedPairs.take(15).joinToString(", ")}"
        }
        tvCustomWordsList.text = "$customLine\n$learnedLine"
    }

    private fun playTestSound(soundSet: String, volumePct: Int) {
        if (volumePct <= 0) return
        val pcm = T9ImeService.SoundSynth.buildPcm(soundSet, T9ImeService.SoundKind.DIGIT, '5')
        Thread {
            try {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(T9ImeService.SoundSynth.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(pcm.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.setVolume((volumePct / 100f).coerceIn(0.05f, 1f))
                track.play()
                Thread.sleep(160)
                track.stop()
                track.release()
            } catch (_: Throwable) {
            }
        }.start()
    }
}
