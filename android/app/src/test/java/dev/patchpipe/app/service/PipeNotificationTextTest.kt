package dev.patchpipe.app.service

import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.core.PipeStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PipeNotificationTextTest {
    private fun status(state: String, error: String? = null, rtt: UInt? = null) =
        PipeStatus(state = state, lastError = error, rttMs = rtt, remoteId = null)

    @Test
    fun listsEachEnabledPipeWithItsState() {
        val pipes = listOf(
            SavedPipe(id = "a", name = "SSH", enabled = true),
            SavedPipe(id = "b", name = "Off", enabled = false),
            SavedPipe(id = "c", name = "Web", enabled = true),
            SavedPipe(id = "d", name = "New", enabled = true),
        )
        val statuses = mapOf(
            "a" to status("relay", rtt = 42u),
            "b" to status("closed"),
            "c" to status("error", error = "iroh connect failed"),
        )
        assertEquals(
            listOf("SSH: relay", "Web: error (iroh connect failed)", "New: starting"),
            pipeStatusLines(pipes, statuses),
        )
    }

    @Test
    fun rttChangesDoNotChangeText() {
        val pipes = listOf(SavedPipe(id = "a", name = "SSH", enabled = true))
        assertEquals(
            pipeStatusLines(pipes, mapOf("a" to status("direct", rtt = 10u))),
            pipeStatusLines(pipes, mapOf("a" to status("direct", rtt = 90u))),
        )
    }
}
