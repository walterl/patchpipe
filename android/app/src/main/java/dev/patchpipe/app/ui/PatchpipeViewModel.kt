package dev.patchpipe.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.app.engine.PatchpipeEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PatchpipeViewModel(private val engine: PatchpipeEngine) : ViewModel() {
    val pipes = engine.pipes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val statuses = engine.statuses
    val autoStart = engine.autoStart

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

    fun ticketFor(id: String): String? = engine.ticketFor(id)

    fun pipeById(id: String): SavedPipe? = pipes.value.find { it.id == id }

    companion object {
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
