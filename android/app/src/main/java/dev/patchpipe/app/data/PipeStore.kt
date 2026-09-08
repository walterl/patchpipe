package dev.patchpipe.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.pipeDataStore by preferencesDataStore(name = "pipes")

class PipeStore(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val pipes: Flow<List<SavedPipe>> = context.pipeDataStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map emptyList()
        json.decodeFromString(PipeList.serializer(), raw).pipes
    }

    suspend fun upsert(pipe: SavedPipe) {
        mutate { current ->
            val without = current.filterNot { it.id == pipe.id }
            without + pipe
        }
    }

    suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    private suspend fun mutate(update: (List<SavedPipe>) -> List<SavedPipe>) {
        context.pipeDataStore.edit { prefs ->
            val current = prefs[KEY]?.let {
                json.decodeFromString(PipeList.serializer(), it).pipes
            } ?: emptyList()
            prefs[KEY] = json.encodeToString(PipeList.serializer(), PipeList(update(current)))
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("pipes_json")
    }
}
