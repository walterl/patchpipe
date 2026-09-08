package dev.patchpipe.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dev.patchpipe.core.generateSecretKeyHex

class SecretStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        "patchpipe_secrets",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun defaultSecretKeyHex(): String {
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val generated = generateSecretKeyHex()
        prefs.edit().putString(KEY, generated).apply()
        return generated
    }

    companion object {
        private const val KEY = "default_secret_hex"
    }
}
