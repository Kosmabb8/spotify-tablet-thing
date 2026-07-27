package dev.carthingspotify.controller.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class OAuthTokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long)

class SecureTokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("car_thing_settings", Context.MODE_PRIVATE)
    private val alias = "car_thing_oauth_aes"

    var clientId: String
        get() = prefs.getString("spotify_client_id", "") ?: ""
        set(value) { prefs.edit().putString("spotify_client_id", value.trim()).apply() }

    var adminPinHash: String
        get() = prefs.getString("admin_pin_hash", "") ?: ""
        set(value) { prefs.edit().putString("admin_pin_hash", value).apply() }

    var dimPercent: Int
        get() = prefs.getInt("dim_percent", 12)
        set(value) { prefs.edit().putInt("dim_percent", value.coerceIn(1, 70)).apply() }

    var dimDelayMinutes: Int
        get() = prefs.getInt("dim_delay_minutes", 10)
        set(value) { prefs.edit().putInt("dim_delay_minutes", value.coerceIn(1, 120)).apply() }

    var clockEnabled: Boolean
        get() = prefs.getBoolean("clock_enabled", true)
        set(value) { prefs.edit().putBoolean("clock_enabled", value).apply() }

    var lyricsEnabled: Boolean
        get() = prefs.getBoolean("lyrics_enabled", false)
        set(value) { prefs.edit().putBoolean("lyrics_enabled", value).apply() }

    var brightnessPercent: Int
        get() = prefs.getInt("brightness_percent", 70)
        set(value) { prefs.edit().putInt("brightness_percent", value.coerceIn(5, 100)).apply() }

    var screenTimeoutSeconds: Int
        get() = prefs.getInt("screen_timeout_seconds", 0)
        set(value) { prefs.edit().putInt("screen_timeout_seconds", value).apply() }

    var totalSessions: Int
        get() = prefs.getInt("total_sessions", 0)
        set(value) { prefs.edit().putInt("total_sessions", value).apply() }

    fun save(tokens: OAuthTokens) {
        prefs.edit()
            .putString("access_token", encrypt(tokens.accessToken))
            .putString("refresh_token", encrypt(tokens.refreshToken))
            .putLong("expires_at", tokens.expiresAtMs)
            .apply()
    }

    fun load(): OAuthTokens? {
        val access = decrypt(prefs.getString("access_token", null) ?: return null) ?: return null
        val refresh = decrypt(prefs.getString("refresh_token", null) ?: return null) ?: return null
        return OAuthTokens(access, refresh, prefs.getLong("expires_at", 0L))
    }

    fun clearTokens() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = try {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8)
    } catch (_: Exception) { null }
}
