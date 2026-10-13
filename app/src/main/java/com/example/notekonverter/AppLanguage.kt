package com.example.notekonverter

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import java.util.Locale

/**
 * In-app language override. [SYSTEM] follows the device: Turkish devices get
 * the `values-tr` strings, every other locale falls back to English.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    TURKISH("tr"),
    ;

    companion object {
        private const val PREFS = "settings"
        private const val KEY = "language"

        fun get(context: Context): AppLanguage {
            val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "")
            return entries.firstOrNull { it.tag == tag } ?: SYSTEM
        }

        fun set(context: Context, language: AppLanguage) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, language.tag) }
        }

        /** Applies the saved override to an activity's base context. */
        fun wrap(base: Context): Context {
            val language = get(base)
            if (language == SYSTEM) return base
            val config = Configuration(base.resources.configuration)
            config.setLocale(Locale.forLanguageTag(language.tag))
            return base.createConfigurationContext(config)
        }
    }
}
