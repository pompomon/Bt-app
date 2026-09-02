package com.github.pompomon.btapp

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

internal class ThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ThemeMode = ThemeMode.fromKey(preferences.getString(KEY_MODE, ThemeMode.SYSTEM.key))

    fun save(mode: ThemeMode) {
        preferences.edit().putString(KEY_MODE, mode.key).apply()
    }

    enum class ThemeMode(val key: String) {
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark");

        fun toNightMode(): Int = when (this) {
            SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }

        companion object {
            fun fromKey(value: String?): ThemeMode = when (value) {
                LIGHT.key -> LIGHT
                DARK.key -> DARK
                else -> SYSTEM
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "app_preferences"
        const val KEY_MODE = "theme_mode"
    }
}
