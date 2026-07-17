package com.eyeplus.data

import android.content.Context
import android.content.SharedPreferences
import com.eyeplus.data.ai.ProviderType

/**
 * Persistent storage for app settings via SharedPreferences.
 *
 * Stores API keys and selected provider so settings survive app restarts.
 */
object SettingsRepository {

    private const val PREFS_NAME = "eyeplus_settings"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    private const val KEY_GROQ_API_KEY = "groq_api_key"
    private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
    private const val KEY_SELECTED_PROVIDER = "selected_provider"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── API Keys ─────────────────────────────────

    fun getGeminiApiKey(context: Context): String =
        prefs(context).getString(KEY_GEMINI_API_KEY, "") ?: ""

    fun setGeminiApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, key).apply()
    }

    fun getGroqApiKey(context: Context): String =
        prefs(context).getString(KEY_GROQ_API_KEY, "") ?: ""

    fun setGroqApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GROQ_API_KEY, key).apply()
    }

    fun getOpenRouterApiKey(context: Context): String =
        prefs(context).getString(KEY_OPENROUTER_API_KEY, "") ?: ""

    fun setOpenRouterApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_OPENROUTER_API_KEY, key).apply()
    }

    // ─── Provider Selection ───────────────────────

    fun getSelectedProvider(context: Context): ProviderType {
        val name = prefs(context).getString(KEY_SELECTED_PROVIDER, null) ?: return ProviderType.GEMINI
        return try {
            ProviderType.valueOf(name)
        } catch (_: IllegalArgumentException) {
            ProviderType.GEMINI
        }
    }

    fun setSelectedProvider(context: Context, provider: ProviderType) {
        prefs(context).edit().putString(KEY_SELECTED_PROVIDER, provider.name).apply()
    }
}
