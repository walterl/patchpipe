package dev.patchpipe.app.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

const val CONFIG_FORMAT = "patchpipe-config"
const val CONFIG_VERSION = 1

/** Exported app config. Tickets and secret keys are included, so the file is sensitive. */
@Serializable
data class ConfigBackup(
    val format: String = "",
    val version: Int = 0,
    /** Null leaves the current setting alone on import. */
    val autoStart: Boolean? = null,
    val pipes: List<SavedPipe> = emptyList(),
)

class InvalidConfigException(message: String) : Exception(message)

private val backupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

fun encodeConfigBackup(pipes: List<SavedPipe>, autoStart: Boolean): String {
    val backup = ConfigBackup(
        format = CONFIG_FORMAT,
        version = CONFIG_VERSION,
        autoStart = autoStart,
        pipes = pipes.sortedByName(),
    )
    return backupJson.encodeToString(ConfigBackup.serializer(), backup)
}

/** Parses and checks an exported file. Throws [InvalidConfigException] with a user-facing message. */
fun decodeConfigBackup(text: String): ConfigBackup {
    val backup = try {
        backupJson.decodeFromString(ConfigBackup.serializer(), text)
    } catch (e: SerializationException) {
        throw InvalidConfigException("Not a Patchpipe config file.")
    } catch (e: IllegalArgumentException) {
        throw InvalidConfigException("Not a Patchpipe config file.")
    }
    if (backup.format != CONFIG_FORMAT) {
        throw InvalidConfigException("Not a Patchpipe config file.")
    }
    if (backup.version > CONFIG_VERSION) {
        throw InvalidConfigException("This file is from a newer Patchpipe. Update the app first.")
    }
    backup.pipes.forEach { pipe ->
        if (pipe.localPort !in 1..65535) {
            throw InvalidConfigException("Pipe \"${pipe.name}\" has an invalid port: ${pipe.localPort}.")
        }
    }
    val pipes = backup.pipes
        .map { if (it.id.isBlank()) it.copy(id = UUID.randomUUID().toString()) else it }
        // A repeated id keeps the last entry, as a later upsert would.
        .associateBy { it.id }
        .values
        .toList()
    return backup.copy(pipes = pipes)
}

/**
 * Adds imported pipes and replaces pipes with the same id. Other pipes stay.
 *
 * Imported pipes do not change what is running: a replaced pipe keeps its
 * on/off state, and a new one starts off. The same key may still be in use
 * on the device the file came from.
 */
fun mergeImportedPipes(current: List<SavedPipe>, imported: List<SavedPipe>): List<SavedPipe> {
    val byId = current.associateBy { it.id }.toMutableMap()
    imported.forEach { pipe ->
        byId[pipe.id] = pipe.copy(enabled = byId[pipe.id]?.enabled ?: false)
    }
    return byId.values.toList()
}
