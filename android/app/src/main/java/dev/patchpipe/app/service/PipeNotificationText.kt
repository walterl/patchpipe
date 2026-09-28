package dev.patchpipe.app.service

import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.core.PipeStatus

/**
 * One line per enabled pipe, e.g. "Home SSH: relay".
 *
 * Leaves out RTT so the text only changes when a pipe changes state; the notification is
 * re-posted on every text change.
 */
fun pipeStatusLines(pipes: List<SavedPipe>, statuses: Map<String, PipeStatus>): List<String> {
    return pipes.filter { it.enabled }.map { pipe ->
        val status = statuses[pipe.id]
        val state = status?.state ?: "starting"
        val error = status?.lastError?.let { " ($it)" } ?: ""
        "${pipe.name}: $state$error"
    }
}
