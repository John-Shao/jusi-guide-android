package com.jusiai.guidedog.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persistent settings for the guide-dog client. The device token is a secret, so
 * everything is stored in EncryptedSharedPreferences (AndroidKeystore AES256-GCM).
 *
 * The defaults let a fresh install talk to the dev relay immediately. Issue ONE
 * token per device on the relay (DEVICE_TOKENS) — never share a token between
 * devices, since the relay's dedup state is keyed by it.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "guide-dog-settings",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var relayUrl: String
        get() = prefs.getString(K_RELAY_URL, DEFAULT_RELAY_URL)!!
        set(v) { prefs.edit().putString(K_RELAY_URL, v.trim()).apply() }

    var deviceToken: String
        get() = prefs.getString(K_TOKEN, DEFAULT_DEVICE_TOKEN)!!
        set(v) { prefs.edit().putString(K_TOKEN, v.trim()).apply() }

    var lang: String
        get() = prefs.getString(K_LANG, "zh")!!
        set(v) { prefs.edit().putString(K_LANG, v).apply() }

    var wantAudio: Boolean
        get() = prefs.getBoolean(K_AUDIO, true)
        set(v) { prefs.edit().putBoolean(K_AUDIO, v).apply() }

    /** Capture-cadence floor (ms). A cycle usually runs longer (relay round-trip),
     *  so the loop is effectively back-to-back — freshest frame wins. */
    var intervalMs: Long
        get() = prefs.getLong(K_INTERVAL, 1000L)
        set(v) { prefs.edit().putLong(K_INTERVAL, v).apply() }

    var uploadMaxDim: Int
        get() = prefs.getInt(K_MAXDIM, 640)
        set(v) { prefs.edit().putInt(K_MAXDIM, v).apply() }

    var uploadQuality: Int
        get() = prefs.getInt(K_QUALITY, 80)
        set(v) { prefs.edit().putInt(K_QUALITY, v).apply() }

    /** Frame-diff: skip a cycle when the 16x16 block-avg luma MAD vs the last SENT
     *  frame is <= threshold; re-send anyway after forceMs of stillness (0 = never). */
    var frameDiffThreshold: Int
        get() = prefs.getInt(K_FDTHRESH, 4)
        set(v) { prefs.edit().putInt(K_FDTHRESH, v).apply() }

    var frameDiffForceMs: Long
        get() = prefs.getLong(K_FDFORCE, 30_000L)
        set(v) { prefs.edit().putLong(K_FDFORCE, v).apply() }

    fun configured(): Boolean = relayUrl.isNotBlank() && deviceToken.isNotBlank()

    companion object {
        const val DEFAULT_RELAY_URL = "https://login.jusiai.com"

        // Dev token. ADD this to the relay's DEVICE_TOKENS and redeploy, e.g.:
        //   DEVICE_TOKENS=...,t_androiddev_K9pQ3zVx7mNbR4tLy0sWcf2:guide-android-01
        // For a real device, generate a fresh one and change it in Settings.
        const val DEFAULT_DEVICE_TOKEN = "t_androiddev_K9pQ3zVx7mNbR4tLy0sWcf2"

        private const val K_RELAY_URL = "relay_url"
        private const val K_TOKEN = "device_token"
        private const val K_LANG = "lang"
        private const val K_AUDIO = "want_audio"
        private const val K_INTERVAL = "interval_ms"
        private const val K_MAXDIM = "upload_max_dim"
        private const val K_QUALITY = "upload_quality"
        private const val K_FDTHRESH = "frame_diff_threshold"
        private const val K_FDFORCE = "frame_diff_force_ms"
    }
}
