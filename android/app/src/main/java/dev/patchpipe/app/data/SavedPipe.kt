package dev.patchpipe.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class PipeMode {
    SERVER,
    LISTENER,
}

@Serializable
enum class RelaySetting {
    DEFAULT,
    DISABLED,
    CUSTOM,
}

@Serializable
data class SavedPipe(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New pipe",
    val mode: PipeMode = PipeMode.SERVER,
    val localPort: Int = 2222,
    val ticket: String = "",
    val secretKeyHex: String = "",
    val notes: String = "",
    val enabled: Boolean = false,
    val autoRetry: Boolean = true,
    val relay: RelaySetting = RelaySetting.DEFAULT,
    val customRelayUrls: String = "",
)

@Serializable
data class PipeList(
    val pipes: List<SavedPipe> = emptyList(),
)

/** Case-insensitive name, then id, so enable/disable does not move a pipe. */
fun List<SavedPipe>.sortedByName(): List<SavedPipe> {
    return sortedWith(
        compareBy<SavedPipe, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id },
    )
}
