package dev.patchpipe.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** App-wide preferences. [autoStart] is off until the user turns it on. */
class AppSettings(private val context: Context) {
    val autoStart: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[AUTO_START] ?: DEFAULT_AUTO_START
    }

    suspend fun isAutoStartEnabled(): Boolean = autoStart.first()

    suspend fun setAutoStart(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[AUTO_START] = enabled
        }
    }

    companion object {
        private val AUTO_START = booleanPreferencesKey("auto_start")
    }
}

/** Missing preference matches a fresh install: do not start at boot. */
const val DEFAULT_AUTO_START = false
