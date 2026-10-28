package dev.patchpipe.app.engine

import dev.patchpipe.app.data.PipeMode
import dev.patchpipe.app.data.RelaySetting
import dev.patchpipe.app.data.SavedPipe

/**
 * The saved fields a running pipe was started with. A change to any of them
 * restarts the pipe; name, notes and auto-retry do not.
 */
internal data class PipeConnection(
    val mode: PipeMode,
    val localPort: Int,
    val ticket: String,
    val secretKeyHex: String,
    val relay: RelaySetting,
    val customRelayUrls: String,
)

internal fun SavedPipe.connection(): PipeConnection = PipeConnection(
    mode = mode,
    localPort = localPort,
    ticket = if (mode == PipeMode.SERVER) ticket.trim() else "",
    secretKeyHex = secretKeyHex.trim(),
    relay = relay,
    customRelayUrls = if (relay == RelaySetting.CUSTOM) customRelayUrls.trim() else "",
)

internal const val RETRY_INITIAL_DELAY_MS = 2_000L
internal const val RETRY_MAX_DELAY_MS = 60_000L

/** Delay before start retry number [attempt] (0-based): doubles from 2 s, capped at 60 s. */
internal fun retryDelayMs(attempt: Int): Long {
    val shift = attempt.coerceIn(0, 30)
    return (RETRY_INITIAL_DELAY_MS shl shift).coerceAtMost(RETRY_MAX_DELAY_MS)
}
