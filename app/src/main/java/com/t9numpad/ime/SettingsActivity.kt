package com.t9numpad.ime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "t9_ime_prefs"
        const val KEY_THEME = "ime_theme"
        const val KEY_HEIGHT = "ime_height"
        const val KEY_SOUND = "ime_sound"
        const val KEY_AUTO_CAP = "ime_auto_cap"
        const val KEY_AUTO_SPACE = "ime_auto_space"
        const val KEY_AUTO_CORRECT_ABC = "ime_auto_correct_abc_only"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val themeGroup = findViewById<RadioGroup>(R.id.rg_theme)
        val heightGroup = findViewById<RadioGroup>(R.id.rg_height)
        val switchSound = findViewById<SwitchMaterial>(R.id.switch_sound)
        val switchAutoCap = findViewById<SwitchMaterial>(R.id.switch_auto_cap)
        val switchAutoSpace = findViewById<SwitchMaterial>(R.id.switch_auto_space)
        val switchAutoCorrectAbc = findViewById<SwitchMaterial>(R.id.switch_auto_correct_abc)

        // Restore saved preferences
        when (prefs.getString(KEY_THEME, "material_light")) {
            "material_dark" -> themeGroup.check(R.id.rb_theme_dark)
            "amoled" -> themeGroup.check(R.id.rb_theme_amoled)
            "classic_3310" -> themeGroup.check(R.id.rb_theme_classic)
            else -> themeGroup.check(R.id.rb_theme_light)
        }

        when (prefs.getString(KEY_HEIGHT, "standard")) {
            "compact" -> heightGroup.check(R.id.rb_height_compact)
            "tall" -> heightGroup.check(R.id.rb_height_tall)
            else -> heightGroup.check(R.id.rb_height_standard)
        }

        switchSound.isChecked = prefs.getBoolean(KEY_SOUND, true)
        switchAutoCap.isChecked = prefs.getBoolean(KEY_AUTO_CAP, true)
        switchAutoSpace.isChecked = prefs.getBoolean(KEY_AUTO_SPACE, true)
        switchAutoCorrectAbc.isChecked = prefs.getBoolean(KEY_AUTO_CORRECT_ABC, true)

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rb_theme_dark -> "material_dark"
                R.id.rb_theme_amoled -> "amoled"
                R.id.rb_theme_classic -> "classic_3310"
                else -> "material_light"
            }
            prefs.edit().putString(KEY_THEME, value).apply()
        }

        heightGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rb_height_compact -> "compact"
                R.id.rb_height_tall -> "tall"
                else -> "standard"
            }
            prefs.edit().putString(KEY_HEIGHT, value).apply()
        }

        switchSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SOUND, isChecked).apply()
        }
        switchAutoCap.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_CAP, isChecked).apply()
        }
        switchAutoSpace.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_SPACE, isChecked).apply()
        }
        switchAutoCorrectAbc.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_AUTO_CORRECT_ABC, isChecked).apply()
        }

        findViewById<Button>(R.id.btn_enable_ime).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btn_switch_ime).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }
}
