package dev.patchpipe.app.util

import dev.patchpipe.app.data.RelaySetting
import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.core.RelayConfig

fun parseCustomRelayUrls(raw: String): List<String> {
    return raw
        .split('\n', ',', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

fun SavedPipe.toRelayConfig(): RelayConfig {
    return when (relay) {
        RelaySetting.DEFAULT -> RelayConfig.Default
        RelaySetting.DISABLED -> RelayConfig.Disabled
        RelaySetting.CUSTOM -> RelayConfig.Custom(parseCustomRelayUrls(customRelayUrls))
    }
}

fun connectTcpCommand(ticket: String): String {
    return "dumbpipe connect-tcp $ticket"
}
