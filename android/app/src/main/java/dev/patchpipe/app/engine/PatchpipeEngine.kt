package dev.patchpipe.app.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.patchpipe.app.PatchpipeApp
import dev.patchpipe.app.data.AppSettings
import dev.patchpipe.app.data.ConfigBackup
import dev.patchpipe.app.data.DEFAULT_AUTO_START
import dev.patchpipe.app.data.InvalidConfigException
import dev.patchpipe.app.data.PipeMode
import dev.patchpipe.app.data.PipeStore
import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.app.data.decodeConfigBackup
import dev.patchpipe.app.data.encodeConfigBackup
import dev.patchpipe.app.service.PipeForegroundService
import dev.patchpipe.app.service.setBootReceiverEnabled
import dev.patchpipe.app.service.shouldStartOnBoot
import dev.patchpipe.app.util.toRelayConfig
import dev.patchpipe.core.ActivePipe
import dev.patchpipe.core.PatchpipeEndpoint
import dev.patchpipe.core.PipeStatus
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PatchpipeEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = PipeStore(context)
    private val settings = AppSettings(context)
    private val mutex = Mutex()
    private val bootMutex = Mutex()
    private val active = mutableMapOf<String, ActivePipe>()
    private val activation = AtomicBoolean(false)
    private val firstSync = CompletableDeferred<Unit>()
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

    val autoStart: StateFlow<Boolean> = settings.autoStart.stateIn(
        scope,
        SharingStarted.Eagerly,
        DEFAULT_AUTO_START,
    )

    init {
        scope.launch(Dispatchers.IO) {
            try {
                bootMutex.withLock {
                    setBootReceiverEnabled(context, settings.isAutoStartEnabled())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "failed to sync boot receiver", e)
            }
        }
    }

    /**
     * Starts enabled pipes and keeps them aligned with the store.
     *
     * Opening the app and a restarted foreground service call this. A boot
     * broadcast does not: that path calls [activateAndAwaitRunning] only when
     * the user has opted in. Creating the engine must not start pipes, or the
     * boot process would bring them up with the option still off.
     */
    fun activate() {
        if (!activation.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                store.pipes.collect { list ->
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
                        firstSync.complete(Unit)
                    }
                }
            } catch (e: CancellationException) {
                if (!firstSync.isCompleted) firstSync.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "pipe sync stopped", e)
                if (!firstSync.isCompleted) firstSync.completeExceptionally(e)
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

    /** Like [activate], but returns after the first store snapshot has started pipes. */
    suspend fun activateAndAwaitRunning() {
        activate()
        firstSync.await()
    }

    /**
     * Starts enabled pipes after unlock when the user opted in.
     * The receiver stays disabled while the option is off, including after app data is cleared.
     */
    suspend fun onBootCompleted(action: String?) {
        val start = bootMutex.withLock {
            val autoStart = settings.isAutoStartEnabled()
            if (!shouldStartOnBoot(autoStart, action)) {
                if (!autoStart) setBootReceiverEnabled(context, false)
                false
            } else {
                true
            }
        }
        if (start) activateAndAwaitRunning()
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

    suspend fun setAutoStart(enabled: Boolean) {
        try {
            bootMutex.withLock {
                settings.setAutoStart(enabled)
                setBootReceiverEnabled(context, enabled)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "failed to update boot receiver", e)
        }
    }

    /** Writes every pipe and app setting to [uri]. Returns the number of pipes written. */
    suspend fun exportConfig(uri: Uri): Int = withContext(Dispatchers.IO) {
        val pipes = store.pipes.first()
        val text = encodeConfigBackup(pipes, settings.isAutoStartEnabled())
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not open the file.")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        pipes.size
    }

    /** Reads and checks a file from [exportConfig] without applying it. */
    suspend fun readConfig(uri: Uri): ConfigBackup = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the file.")
        val bytes = input.use { it.readNBytesCompat(MAX_CONFIG_BYTES + 1) }
        if (bytes.size > MAX_CONFIG_BYTES) {
            throw InvalidConfigException("The file is too large to be a Patchpipe config.")
        }
        decodeConfigBackup(bytes.toString(Charsets.UTF_8))
    }

    suspend fun importConfig(backup: ConfigBackup) {
        store.importPipes(backup.pipes)
        backup.autoStart?.let { setAutoStart(it) }
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
        private const val MAX_CONFIG_BYTES = 1 shl 20

        fun from(context: Context): PatchpipeEngine {
            return (context.applicationContext as PatchpipeApp).engine
        }
    }
}

/** InputStream.readNBytes needs API 33; minSdk is 26. */
private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
