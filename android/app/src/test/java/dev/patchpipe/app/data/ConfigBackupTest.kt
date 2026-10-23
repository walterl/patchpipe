package dev.patchpipe.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupTest {
    @Test
    fun roundTripKeepsPipesAndSettings() {
        val pipes = listOf(
            SavedPipe(
                id = "b",
                name = "web",
                mode = PipeMode.LISTENER,
                localPort = 8080,
                secretKeyHex = "00ff",
                relay = RelaySetting.CUSTOM,
                customRelayUrls = "https://relay.example",
                enabled = true,
            ),
            SavedPipe(id = "a", name = "ssh", ticket = "endpointticket1abc"),
        )

        val decoded = decodeConfigBackup(encodeConfigBackup(pipes, autoStart = true))

        assertEquals(CONFIG_FORMAT, decoded.format)
        assertEquals(CONFIG_VERSION, decoded.version)
        assertEquals(true, decoded.autoStart)
        assertEquals(pipes.sortedByName(), decoded.pipes)
    }

    @Test
    fun rejectsOtherFiles() {
        assertThrows(InvalidConfigException::class.java) { decodeConfigBackup("not json") }
        assertThrows(InvalidConfigException::class.java) { decodeConfigBackup("[]") }
        assertThrows(InvalidConfigException::class.java) { decodeConfigBackup("""{"pipes":[]}""") }
    }

    @Test
    fun rejectsNewerVersion() {
        val text = """{"format":"$CONFIG_FORMAT","version":${CONFIG_VERSION + 1}}"""
        val e = assertThrows(InvalidConfigException::class.java) { decodeConfigBackup(text) }
        assertTrue(e.message!!.contains("newer"))
    }

    @Test
    fun rejectsInvalidPort() {
        val text = """{"format":"$CONFIG_FORMAT","version":1,"pipes":[{"id":"a","localPort":70000}]}"""
        assertThrows(InvalidConfigException::class.java) { decodeConfigBackup(text) }
    }

    @Test
    fun fillsMissingFieldsAndIds() {
        val text = """
            {"format":"$CONFIG_FORMAT","version":1,"future":1,
             "pipes":[{"id":"","name":"x","extra":true},{"id":"d","name":"one"},{"id":"d","name":"two"}]}
        """.trimIndent()

        val decoded = decodeConfigBackup(text)

        assertNull(decoded.autoStart)
        assertEquals(2, decoded.pipes.size)
        assertTrue(decoded.pipes.none { it.id.isBlank() })
        assertEquals("two", decoded.pipes.single { it.id == "d" }.name)
    }

    @Test
    fun mergeReplacesByIdAndKeepsRunningState() {
        val current = listOf(
            SavedPipe(id = "keep", name = "keep", enabled = true),
            SavedPipe(id = "same", name = "old", enabled = true),
        )
        val imported = listOf(
            SavedPipe(id = "same", name = "new", enabled = false),
            SavedPipe(id = "fresh", name = "fresh", enabled = true),
        )

        val merged = mergeImportedPipes(current, imported).associateBy { it.id }

        assertEquals(setOf("keep", "same", "fresh"), merged.keys)
        assertEquals("new", merged.getValue("same").name)
        assertTrue(merged.getValue("same").enabled)
        assertTrue(merged.getValue("keep").enabled)
        assertFalse(merged.getValue("fresh").enabled)
    }
}
