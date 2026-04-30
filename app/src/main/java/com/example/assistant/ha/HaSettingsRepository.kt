package com.example.assistant.ha

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Хранит URL сервера Home Assistant и Long-Lived Access Token в SharedPreferences.
 * Данные сохраняются между перезапусками приложения.
 */
class HaSettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(KEY_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    val isConfigured: Boolean
        get() = url.isNotBlank() && token.isNotBlank()

    fun save(url: String, token: String) {
        this.url   = url.trim().trimEnd('/')
        this.token = token.trim()
    }

    fun clear() {
        prefs.edit().remove(KEY_URL).remove(KEY_TOKEN).apply()
    }

    /**
     * Проверяет доступность Home Assistant через GET /api/.
     * Возвращает [HaCheckResult.Ok] если сервер ответил 200,
     * [HaCheckResult.Unauthorized] если токен неверный (401/403),
     * [HaCheckResult.Error] при сетевой или иной ошибке.
     */
    suspend fun checkConnection(): HaCheckResult = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext HaCheckResult.Error("URL или токен не заданы")
        try {
            val conn = (URL("$url/api/").openConnection() as HttpURLConnection).apply {
                requestMethod        = "GET"
                connectTimeout       = CONNECT_TIMEOUT_MS
                readTimeout          = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            val code = conn.responseCode
            conn.disconnect()
            Log.d(TAG, "HA /api/ → HTTP $code")
            when (code) {
                200            -> HaCheckResult.Ok
                401, 403       -> HaCheckResult.Unauthorized
                else           -> HaCheckResult.Error("HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "HA check failed: ${e.message}")
            HaCheckResult.Error(e.message ?: "Нет соединения")
        }
    }

    companion object {
        private const val TAG                = "HaSettings"
        private const val PREFS_NAME         = "ha_settings"
        private const val KEY_URL            = "ha_url"
        private const val KEY_TOKEN          = "ha_token"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS    = 5_000
    }
}

sealed class HaCheckResult {
    data object Ok           : HaCheckResult()
    data object Unauthorized : HaCheckResult()
    data class  Error(val message: String) : HaCheckResult()
}
