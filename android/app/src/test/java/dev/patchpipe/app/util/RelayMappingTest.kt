package dev.patchpipe.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayMappingTest {
    @Test
    fun customSplitsUrls() {
        val urls = parseCustomRelayUrls("https://a.example\nhttps://b.example, https://c.example")
        assertEquals(
            listOf("https://a.example", "https://b.example", "https://c.example"),
            urls,
        )
    }
}
