package dev.patchpipe.app.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.patchpipe.app.data.ConfigBackup
import dev.patchpipe.app.data.InvalidConfigException
import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.app.engine.PatchpipeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PatchpipeViewModel(private val engine: PatchpipeEngine) : ViewModel() {
    val pipes = engine.pipes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val statuses = engine.statuses
    val autoStart = engine.autoStart

    private val _pendingImport = MutableStateFlow<ConfigBackup?>(null)

    /** A parsed file waiting for the user to confirm the import. */
    val pendingImport: StateFlow<ConfigBackup?> = _pendingImport.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** One-off result text for a snackbar; clear it with [messageShown]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun save(pipe: SavedPipe) {
        viewModelScope.launch { engine.save(pipe) }
    }

    fun delete(id: String) {
        viewModelScope.launch { engine.delete(id) }
    }

    fun setEnabled(pipe: SavedPipe, enabled: Boolean) {
        viewModelScope.launch { engine.setEnabled(pipe, enabled) }
    }

    fun setAutoStart(enabled: Boolean) {
        viewModelScope.launch { engine.setAutoStart(enabled) }
    }

    fun exportConfig(uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val count = engine.exportConfig(uri)
                "Exported $count ${pipeWord(count)}. The file holds tickets and keys; keep it private."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "config export failed", e)
                "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun readConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                _pendingImport.value = engine.readConfig(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidConfigException) {
                _message.value = "Import failed: ${e.message}"
            } catch (e: Throwable) {
                Log.w(TAG, "config read failed", e)
                _message.value = "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun confirmImport() {
        val backup = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            _message.value = try {
                engine.importConfig(backup)
                "Imported ${backup.pipes.size} ${pipeWord(backup.pipes.size)}."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "config import failed", e)
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    fun messageShown() {
        _message.value = null
    }

    fun ticketFor(id: String): String? = engine.ticketFor(id)

    fun pipeById(id: String): SavedPipe? = pipes.value.find { it.id == id }

    companion object {
        private const val TAG = "PatchpipeViewModel"

        fun factory(engine: PatchpipeEngine): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return PatchpipeViewModel(engine) as T
                }
            }
        }
    }
}

internal fun pipeWord(count: Int): String = if (count == 1) "pipe" else "pipes"
