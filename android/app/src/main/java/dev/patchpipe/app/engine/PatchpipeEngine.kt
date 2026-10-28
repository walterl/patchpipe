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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PatchpipeEngine(private val context: Context) {
    private class Running(val handle: ActivePipe, val connection: PipeConnection)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = PipeStore(context)
    private val settings = AppSettings(context)

    /** Guards [active], [retries] and [wanted]. Read them only while holding it. */
    private val mutex = Mutex()
    private val bootMutex = Mutex()
    private val active = mutableMapOf<String, Running>()
    private val retries = mutableMapOf<String, Job>()
    private var wanted = emptyMap<String, SavedPipe>()

    /** Immutable copy of [active] for synchronous reads such as [ticketFor]. */
    @Volatile
    private var activeSnapshot = emptyMap<String, ActivePipe>()

    /** Running pipes plus pending retries; the service runs while this is above zero. */
    @Volatile
    private var serviceCount = 0
    private val activation = AtomicBoolean(false)
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
     * broadcast does not: that path calls [onBootCompleted], which starts the
     * service only when the user has opted in. Creating the engine must not
     * start pipes, or the boot process would bring them up with the option still off.
     *
     * Running pipes always match the store: a pipe whose connection settings
     * change is restarted, and a pipe that fails to start is retried with
     * backoff while it stays enabled with auto-retry on.
     */
    fun activate() {
        if (!activation.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                store.pipes.collect { list -> reconcile(list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "pipe sync stopped", e)
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(1_000)
                refreshStatuses()
            }
        }
        watchDefaultNetworkChanges(context) { notifyNetworkChange() }
    }

    /**
     * Starts enabled pipes after unlock when the user opted in.
     * The receiver stays disabled while the option is off, including after app data is cleared.
     *
     * The foreground service starts first, so pipe startup (which can wait on
     * the network) runs under its protection instead of the receiver's short lifetime.
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
        if (!start) return
        if (store.pipes.first().any { it.enabled }) {
            withContext(Dispatchers.Main) { syncService(forceStart = true) }
        }
        activate()
    }

    suspend fun save(pipe: SavedPipe) {
        store.upsert(pipe)
    }

    suspend fun delete(id: String) {
        mutex.withLock {
            wanted = wanted - id
            stopLocked(id)
        }
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
        return activeSnapshot[id]?.ticket()
    }

    private suspend fun reconcile(list: List<SavedPipe>) {
        mutex.withLock {
            val enabled = list.filter { it.enabled }
            val previous = wanted
            wanted = enabled.associateBy { it.id }
            (active.keys + retries.keys)
                .filter { it !in wanted }
                .forEach { stopLocked(it) }
            enabled.forEach { pipe ->
                val current = active[pipe.id]
                when {
                    current == null -> {
                        // A pending retry keeps its backoff unless this pipe was edited.
                        if (retries[pipe.id] == null || previous[pipe.id] != pipe) {
                            retries.remove(pipe.id)?.cancel()
                            if (!startLocked(pipe) && pipe.autoRetry) scheduleRetryLocked(pipe.id)
                        }
                    }
                    current.connection != pipe.connection() -> {
                        stopLocked(pipe.id)
                        // The old handle releases its port and key asynchronously, so a
                        // restart gets a few retries even with auto-retry off.
                        if (!startLocked(pipe)) {
                            scheduleRetryLocked(pipe.id, minAttempts = RESTART_ATTEMPTS)
                        }
                    }
                }
            }
            publishLocked()
        }
        withContext(Dispatchers.Main) { syncService() }
    }

    /**
     * Retries starting [id] with backoff until it runs or is no longer wanted.
     * It tries at least [minAttempts] times, and keeps going while auto-retry is on.
     */
    private fun scheduleRetryLocked(id: String, minAttempts: Int = 0) {
        retries[id] = scope.launch(Dispatchers.IO) {
            val self = coroutineContext[Job]
            var attempt = 0
            while (true) {
                delay(retryDelayMs(attempt++))
                val done = mutex.withLock {
                    val pipe = wanted[id]
                    when {
                        retries[id] !== self || pipe == null || active.containsKey(id) -> true
                        startLocked(pipe) -> true
                        pipe.autoRetry -> false
                        else -> attempt >= minAttempts
                    }
                }
                if (done) break
            }
            mutex.withLock {
                if (retries[id] === self) retries.remove(id)
                publishLocked()
            }
            withContext(Dispatchers.Main) { syncService() }
        }
        publishLocked()
    }

    /** Returns true if the pipe is now running. */
    private fun startLocked(pipe: SavedPipe): Boolean {
        return try {
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
            active[pipe.id] = Running(handle, pipe.connection())
            publishLocked()
            _statuses.update { it + (pipe.id to handle.status()) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "failed to start pipe ${pipe.name}", e)
            val status = PipeStatus(
                state = "error",
                lastError = e.message ?: e.javaClass.simpleName,
                rttMs = null,
                remoteId = null,
            )
            _statuses.update { it + (pipe.id to status) }
            false
        }
    }

    private fun stopLocked(id: String) {
        retries.remove(id)?.cancel()
        active.remove(id)?.handle?.stop()
        publishLocked()
        _statuses.update { it - id }
    }

    /** Publishes lock-free copies of the running set for UI reads and [syncService]. */
    private fun publishLocked() {
        activeSnapshot = active.mapValues { it.value.handle }
        // Pending retries keep the service up so the process survives until they run.
        serviceCount = active.size + retries.size
    }

    private fun notifyNetworkChange() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock { active.values.forEach { it.handle.networkChange() } }
        }
    }

    private suspend fun refreshStatuses() {
        mutex.withLock {
            val next = active.mapValues { it.value.handle.status() }
            _statuses.update { it + next }
        }
    }

    /**
     * Starts or stops the foreground service to match the latest [serviceCount].
     * Callers update the count under [mutex] first, so the last call always sees the newest value.
     */
    private fun syncService(forceStart: Boolean = false) {
        val count = if (forceStart) 1 else serviceCount
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
        private const val RESTART_ATTEMPTS = 3

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
