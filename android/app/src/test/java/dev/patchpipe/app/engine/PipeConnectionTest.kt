package dev.patchpipe.app.engine

import dev.patchpipe.app.data.PipeMode
import dev.patchpipe.app.data.RelaySetting
import dev.patchpipe.app.data.SavedPipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PipeConnectionTest {
    private val base = SavedPipe(
        id = "a",
        mode = PipeMode.SERVER,
        localPort = 2222,
        ticket = "ticket",
        relay = RelaySetting.DEFAULT,
    )

    @Test
    fun cosmeticEditsKeepConnection() {
        val edited = base.copy(name = "Other", notes = "n", autoRetry = false, enabled = true)
        assertEquals(base.connection(), edited.connection())
    }

    @Test
    fun whitespaceAndUnusedFieldsKeepConnection() {
        assertEquals(base.connection(), base.copy(ticket = " ticket\n").connection())
        assertEquals(base.connection(), base.copy(customRelayUrls = "https://r").connection())
        val listener = base.copy(mode = PipeMode.LISTENER)
        assertEquals(listener.connection(), listener.copy(ticket = "other").connection())
    }

    @Test
    fun connectionEditsChangeConnection() {
        assertNotEquals(base.connection(), base.copy(localPort = 2223).connection())
        assertNotEquals(base.connection(), base.copy(ticket = "other").connection())
        assertNotEquals(base.connection(), base.copy(secretKeyHex = "ab").connection())
        assertNotEquals(base.connection(), base.copy(relay = RelaySetting.DISABLED).connection())
        val custom = base.copy(relay = RelaySetting.CUSTOM, customRelayUrls = "https://a")
        assertNotEquals(custom.connection(), custom.copy(customRelayUrls = "https://b").connection())
    }

    @Test
    fun retryBackoffDoublesAndCaps() {
        assertEquals(2_000L, retryDelayMs(0))
        assertEquals(4_000L, retryDelayMs(1))
        assertEquals(32_000L, retryDelayMs(4))
        assertEquals(60_000L, retryDelayMs(5))
        assertEquals(60_000L, retryDelayMs(Int.MAX_VALUE))
    }
}
