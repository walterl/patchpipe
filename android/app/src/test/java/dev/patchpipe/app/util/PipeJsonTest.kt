package dev.patchpipe.app.util

import dev.patchpipe.app.data.PipeList
import dev.patchpipe.app.data.PipeMode
import dev.patchpipe.app.data.RelaySetting
import dev.patchpipe.app.data.SavedPipe
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipeJsonTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun roundTripSavedPipe() {
        val pipe = SavedPipe(
            id = "abc",
            name = "ssh",
            mode = PipeMode.SERVER,
            localPort = 2222,
            ticket = "ticket",
            relay = RelaySetting.DISABLED,
        )
        val encoded = json.encodeToString(PipeList.serializer(), PipeList(listOf(pipe)))
        val decoded = json.decodeFromString(PipeList.serializer(), encoded)
        assertEquals(pipe, decoded.pipes.single())
    }

    @Test
    fun connectCommandIncludesTicket() {
        val cmd = connectTcpCommand("endpointticket1abc")
        assertTrue(cmd.startsWith("dumbpipe connect-tcp "))
        assertTrue(cmd.contains("endpointticket1abc"))
    }
}
