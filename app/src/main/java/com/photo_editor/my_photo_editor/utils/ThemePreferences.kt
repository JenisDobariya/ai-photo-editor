package com.photo_editor.my_photo_editor.utils

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import com.photo_editor.my_photo_editor.R

/**
 * Appearance settings: Light / Dark / System theme and the accent colour.
 * Stored in SharedPreferences; applied by [com.photo_editor.my_photo_editor.PhotoEditorApplication]
 * (night mode) and [com.photo_editor.my_photo_editor.BaseActivity] (accent overlay).
 */
object ThemePreferences {

    enum class Mode(val label: String, val nightMode: Int) {
        SYSTEM("System default", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("Light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("Dark", AppCompatDelegate.MODE_NIGHT_YES)
    }

    /** Accent choices. Tones per theme live in values/accents.xml and values-night/accents.xml. */
    enum class Accent(val label: String, @StyleRes val overlay: Int, @ColorRes val swatch: Int) {
        BLUE("Blue", R.style.ThemeOverlay_Accent_Blue, R.color.accent_swatch_blue),
        INDIGO("Indigo", R.style.ThemeOverlay_Accent_Indigo, R.color.accent_swatch_indigo),
        PURPLE("Purple", R.style.ThemeOverlay_Accent_Purple, R.color.accent_swatch_purple),
        PINK("Pink", R.style.ThemeOverlay_Accent_Pink, R.color.accent_swatch_pink),
        RED("Red", R.style.ThemeOverlay_Accent_Red, R.color.accent_swatch_red),
        ORANGE("Orange", R.style.ThemeOverlay_Accent_Orange, R.color.accent_swatch_orange),
        GREEN("Green", R.style.ThemeOverlay_Accent_Green, R.color.accent_swatch_green),
        TEAL("Teal", R.style.ThemeOverlay_Accent_Teal, R.color.accent_swatch_teal),
        CYAN("Cyan", R.style.ThemeOverlay_Accent_Cyan, R.color.accent_swatch_cyan),
        SLATE("Slate", R.style.ThemeOverlay_Accent_Slate, R.color.accent_swatch_slate)
    }

    private const val PREFS = "appearance"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_ACCENT = "accent"

    /** Bumped whenever the accent changes, so open screens know to recreate themselves. */
    @Volatile
    var accentVersion = 0
        private set

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): Mode =
        prefs(context).getString(KEY_MODE, null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
            ?: Mode.SYSTEM

    fun accent(context: Context): Accent =
        prefs(context).getString(KEY_ACCENT, null)?.let { runCatching { Accent.valueOf(it) }.getOrNull() }
            ?: Accent.BLUE

    /** Call once at app start. */
    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(mode(context).nightMode)
    }

    /** Saves and applies the theme; AppCompat recreates open screens itself. */
    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    /** Saves the accent; screens pick it up when (re)created. */
    fun setAccent(context: Context, accent: Accent) {
        if (accent == accent(context)) return
        prefs(context).edit().putString(KEY_ACCENT, accent.name).apply()
        accentVersion++
    }
}
