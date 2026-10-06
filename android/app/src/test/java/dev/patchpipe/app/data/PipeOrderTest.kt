package dev.patchpipe.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PipeOrderTest {
    @Test
    fun sortedByNameIsAlphabeticalAndIgnoresEnabled() {
        val pipes = listOf(
            pipe(id = "3", name = "zeta", enabled = true),
            pipe(id = "1", name = "alpha", enabled = false),
            pipe(id = "2", name = "Beta", enabled = true),
            pipe(id = "4", name = "alpha", enabled = true),
        )
        val disabled = pipes.map { it.copy(enabled = !it.enabled) }

        assertEquals(listOf("1", "4", "2", "3"), pipes.sortedByName().map { it.id })
        assertEquals(listOf("1", "4", "2", "3"), disabled.sortedByName().map { it.id })
    }

    private fun pipe(id: String, name: String, enabled: Boolean): SavedPipe {
        return SavedPipe(id = id, name = name, enabled = enabled)
    }
}
