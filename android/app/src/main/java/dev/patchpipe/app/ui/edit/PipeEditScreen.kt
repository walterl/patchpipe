package dev.patchpipe.app.ui.edit

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.patchpipe.app.data.PipeMode
import dev.patchpipe.app.data.RelaySetting
import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.app.ui.PatchpipeViewModel
import dev.patchpipe.app.util.connectTcpCommand
import dev.patchpipe.core.PatchpipeException
import dev.patchpipe.core.parseTicket

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipeEditScreen(
    vm: PatchpipeViewModel,
    pipeId: String,
    onBack: () -> Unit,
) {
    val pipes by vm.pipes.collectAsState()
    val statuses by vm.statuses.collectAsState()
    val existing = pipes.find { it.id == pipeId }
    val initial = existing ?: SavedPipe()
    var name by remember(pipeId) { mutableStateOf(initial.name) }
    var mode by remember(pipeId) { mutableStateOf(initial.mode) }
    var port by remember(pipeId) { mutableStateOf(initial.localPort.toString()) }
    var ticket by remember(pipeId) { mutableStateOf(initial.ticket) }
    var secret by remember(pipeId) { mutableStateOf(initial.secretKeyHex) }
    var notes by remember(pipeId) { mutableStateOf(initial.notes) }
    var autoRetry by remember(pipeId) { mutableStateOf(initial.autoRetry) }
    var relay by remember(pipeId) { mutableStateOf(initial.relay) }
    var customRelays by remember(pipeId) { mutableStateOf(initial.customRelayUrls) }
    var ticketInfo by remember { mutableStateOf<String?>(null) }
    var ticketError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val liveTicket = existing?.id?.let { vm.ticketFor(it) }
    val status = existing?.id?.let { statuses[it] }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New pipe" else "Edit pipe") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = mode == PipeMode.SERVER,
                    onClick = { mode = PipeMode.SERVER },
                    label = { Text("Server") },
                )
                FilterChip(
                    selected = mode == PipeMode.LISTENER,
                    onClick = { mode = PipeMode.LISTENER },
                    label = { Text("Listener") },
                )
            }
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text(if (mode == PipeMode.SERVER) "Local listen port" else "Local target port") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (mode == PipeMode.SERVER) {
                OutlinedTextField(
                    value = ticket,
                    onValueChange = {
                        ticket = it
                        ticketInfo = null
                        ticketError = null
                    },
                    label = { Text("Ticket") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                TextButton(
                    onClick = {
                        try {
                            val info = parseTicket(ticket)
                            val relays = if (info.relayUrls.isEmpty()) {
                                "no relay URLs in ticket"
                            } else {
                                info.relayUrls.joinToString()
                            }
                            ticketInfo = "Remote ${info.endpointId}\n$relays"
                            ticketError = null
                        } catch (e: PatchpipeException) {
                            ticketError = e.message
                            ticketInfo = null
                        }
                    },
                ) { Text("Validate ticket") }
                ticketInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                ticketError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } else {
                val shareTicket = liveTicket ?: ""
                if (shareTicket.isNotEmpty()) {
                    Text("Ticket:\n$shareTicket", style = MaterialTheme.typography.bodySmall)
                    Text(
                        connectTcpCommand(shareTicket),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareTicket)
                            }
                            context.startActivity(Intent.createChooser(send, "Share ticket"))
                        },
                    ) { Text("Share ticket") }
                } else {
                    Text("Activate this listener to produce a ticket.")
                }
            }
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Optional static secret key (hex)") },
                modifier = Modifier.fillMaxWidth(),
            )
            FilterChip(
                    selected = autoRetry,
                    onClick = { autoRetry = !autoRetry },
                    label = { Text("Auto-retry") },
                )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = relay == RelaySetting.DEFAULT,
                    onClick = { relay = RelaySetting.DEFAULT },
                    label = { Text("Default") },
                )
                FilterChip(
                    selected = relay == RelaySetting.DISABLED,
                    onClick = { relay = RelaySetting.DISABLED },
                    label = { Text("Disabled") },
                )
                FilterChip(
                    selected = relay == RelaySetting.CUSTOM,
                    onClick = { relay = RelaySetting.CUSTOM },
                    label = { Text("Custom") },
                )
            }
            if (relay == RelaySetting.CUSTOM) {
                OutlinedTextField(
                    value = customRelays,
                    onValueChange = { customRelays = it },
                    label = { Text("Relay URLs (one per line)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "This device endpoint id: ${vm.nodeId}",
                style = MaterialTheme.typography.bodySmall,
            )
            status?.let {
                Text(
                    "Status: ${it.state}" +
                        (it.rttMs?.let { rtt -> " · RTT ${rtt}ms" } ?: "") +
                        (it.remoteId?.let { id -> " · remote $id" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                it.lastError?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                "Test locally: nc 127.0.0.1 ${port.ifBlank { "<port>" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    val saved = initial.copy(
                        name = name.ifBlank { "Pipe" },
                        mode = mode,
                        localPort = port.toIntOrNull() ?: 0,
                        ticket = ticket,
                        secretKeyHex = secret.trim(),
                        notes = notes,
                        autoRetry = autoRetry,
                        relay = relay,
                        customRelayUrls = customRelays,
                    )
                    vm.save(saved)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            if (existing != null) {
                TextButton(
                    onClick = {
                        vm.delete(existing.id)
                        onBack()
                    },
                ) { Text("Delete") }
            }
        }
    }
}
