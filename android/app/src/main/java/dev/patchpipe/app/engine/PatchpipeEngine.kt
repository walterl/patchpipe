package dev.patchpipe.app.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.patchpipe.app.PatchpipeApp
import dev.patchpipe.app.data.PipeMode
import dev.patchpipe.app.data.PipeStore
import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.app.service.PipeForegroundService
import dev.patchpipe.app.util.toRelayConfig
import dev.patchpipe.core.ActivePipe
import dev.patchpipe.core.PatchpipeEndpoint
import dev.patchpipe.core.PipeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PatchpipeEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = PipeStore(context)
    private val mutex = Mutex()
    private val active = mutableMapOf<String, ActivePipe>()
    private val factory: PatchpipeEndpoint by lazy {
        PatchpipeEndpoint(null)
    }

    val pipes: StateFlow<List<SavedPipe>> = store.pipes.stateIn(
        scope,
        SharingStarted.Eagerly,
        emptyList(),
    )

    private val _statuses = MutableStateFlow<Map<String, PipeStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, PipeStatus>> = _statuses.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            pipes.collect { list ->
                val wanted = list.filter { it.enabled }.map { it.id }.toSet()
                mutex.withLock {
                    active.keys.filter { it !in wanted }.forEach { stopLocked(it) }
                    list.filter { it.enabled }.forEach { pipe ->
                        if (!active.containsKey(pipe.id)) {
                            startLocked(pipe)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    syncService()
                }
            }
        }
        scope.launch {
            while (true) {
                delay(1_000)
                refreshStatuses()
            }
        }
        watchDefaultNetworkChanges(context) { notifyNetworkChange() }
    }

    suspend fun save(pipe: SavedPipe) {
        store.upsert(pipe)
    }

    suspend fun delete(id: String) {
        mutex.withLock { stopLocked(id) }
        store.delete(id)
        withContext(Dispatchers.Main) { syncService() }
    }

    suspend fun setEnabled(pipe: SavedPipe, enabled: Boolean) {
        store.upsert(pipe.copy(enabled = enabled))
    }

    fun ticketFor(id: String): String? {
        return active[id]?.ticket()
    }

    private fun startLocked(pipe: SavedPipe) {
        try {
            val override = pipe.secretKeyHex.trim().ifEmpty { null }
            val handle = when (pipe.mode) {
                PipeMode.SERVER -> factory.connectTcpLoopback(
                    pipe.ticket.trim(),
                    pipe.localPort.toUInt(),
                    pipe.toRelayConfig(),
                    override,
                )
                PipeMode.LISTENER -> factory.listenTcpLoopback(
                    pipe.localPort.toUInt(),
                    pipe.toRelayConfig(),
                    override,
                )
            }
            active[pipe.id] = handle
            _statuses.value = _statuses.value + (pipe.id to handle.status())
        } catch (e: Throwable) {
            Log.w(TAG, "failed to start pipe ${pipe.name}", e)
            _statuses.value = _statuses.value + (
                pipe.id to PipeStatus(
                    state = "error",
                    lastError = e.message ?: e.javaClass.simpleName,
                    rttMs = null,
                    remoteId = null,
                )
            )
        }
    }

    private fun stopLocked(id: String) {
        active.remove(id)?.stop()
        _statuses.value = _statuses.value - id
    }

    private fun notifyNetworkChange() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock { active.values.forEach { it.networkChange() } }
        }
    }

    private fun refreshStatuses() {
        val next = active.mapValues { it.value.status() }
        _statuses.value = _statuses.value + next
    }

    private fun syncService() {
        val count = active.size
        val intent = Intent(context, PipeForegroundService::class.java)
        try {
            if (count == 0) {
                context.stopService(intent)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "foreground service start/stop failed", e)
        }
    }

    companion object {
        private const val TAG = "PatchpipeEngine"

        fun from(context: Context): PatchpipeEngine {
            return (context.applicationContext as PatchpipeApp).engine
        }
    }
}



