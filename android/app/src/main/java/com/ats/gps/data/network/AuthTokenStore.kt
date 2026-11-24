package com.ats.gps.data.network

import android.content.Context
import android.content.SharedPreferences

/**
 * Almacen local seguro de token Bearer de sesion con expiracion de 48 horas por host.
 */
class AuthTokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLastHost(host: String) {
        val normalizedHost = host.trim()
        if (normalizedHost.isEmpty()) return
        prefs.edit().putString(KEY_LAST_HOST, normalizedHost).apply()
    }

    fun getSavedHost(): String? {
        return prefs.getString(KEY_LAST_HOST, null)?.takeIf { it.isNotBlank() }
            ?: prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }
    }

    fun saveToken(host: String, token: String, expiresInHours: Int = DEFAULT_EXPIRES_HOURS) {
        val normalizedHost = host.trim()
        if (normalizedHost.isEmpty() || token.isBlank()) return

        val hours = expiresInHours.coerceAtLeast(1)
        val expiresAtMillis = System.currentTimeMillis() + hours * MILLIS_PER_HOUR

        prefs.edit()
            .putString(KEY_HOST, normalizedHost)
            .putString(KEY_LAST_HOST, normalizedHost)
            .putString(KEY_TOKEN, token.trim())
            .putLong(KEY_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    fun getValidToken(host: String): String? {
        val normalizedHost = host.trim()
        if (normalizedHost.isEmpty()) return null

        val savedHost = prefs.getString(KEY_HOST, null) ?: return null
        val savedToken = prefs.getString(KEY_TOKEN, null) ?: return null
        val expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT, 0L)

        if (savedToken.isBlank() || savedHost != normalizedHost) {
            return null
        }

        if (System.currentTimeMillis() >= expiresAtMillis) {
            clearToken()
            return null
        }

        return savedToken
    }

    fun clearToken() {
        prefs.edit()
            .remove(KEY_HOST)
            .remove(KEY_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ats_gps_auth"
        private const val KEY_HOST = "host"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_TOKEN = "token"
        private const val KEY_EXPIRES_AT = "expiresAtMillis"
        private const val DEFAULT_EXPIRES_HOURS = 48
        private const val MILLIS_PER_HOUR = 3_600_000L
    }
}
