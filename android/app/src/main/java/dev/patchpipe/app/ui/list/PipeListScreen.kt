package dev.patchpipe.app.ui.list

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.patchpipe.app.data.ConfigBackup
import dev.patchpipe.app.data.PipeMode
import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.app.ui.PatchpipeViewModel
import dev.patchpipe.app.ui.pipeWord
import dev.patchpipe.core.PipeStatus
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipeListScreen(
    vm: PatchpipeViewModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val pipes by vm.pipes.collectAsState()
    val statuses by vm.statuses.collectAsState()
    val autoStart by vm.autoStart.collectAsState()
    val pendingImport by vm.pendingImport.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(vm::exportConfig) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::readConfig) }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        // Clear only after it is shown: clearing changes the key and would cancel this effect.
        snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Long)
        vm.messageShown()
    }
    pendingImport?.let { backup ->
        ImportDialog(
            backup = backup,
            replaced = backup.pipes.count { new -> pipes.any { it.id == new.id } },
            onConfirm = vm::confirmImport,
            onDismiss = vm::cancelImport,
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Patchpipe") },
                actions = {
                    TextButton(
                        onClick = {
                            // Some providers label .json files as text or octet-stream.
                            importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                        },
                    ) { Text("Import") }
                    TextButton(
                        onClick = { exportLauncher.launch("patchpipe-config-${LocalDate.now()}.json") },
                    ) { Text("Export") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Text("+")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            KeepAliveWarnings(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
            AutoStartRow(
                checked = autoStart,
                onCheckedChange = vm::setAutoStart,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
            )
            if (pipes.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No pipes yet.")
                    Text(
                        "Add a server-mode pipe with a desktop dumbpipe listen-tcp ticket, then point a local app at 127.0.0.1.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pipes, key = { it.id }) { pipe ->
                        PipeRow(
                            pipe = pipe,
                            status = statuses[pipe.id],
                            onToggle = { vm.setEnabled(pipe, it) },
                            onClick = { onEdit(pipe.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(
    backup: ConfigBackup,
    replaced: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = backup.pipes.size
    val lines = buildList {
        add("Import $count ${pipeWord(count)} from this file?")
        if (replaced > 0) {
            add("$replaced existing ${pipeWord(replaced)} will be replaced. Other pipes stay.")
        }
        if (count > replaced) add("New pipes start switched off.")
        backup.autoStart?.let { add("Start at boot will be turned ${if (it) "on" else "off"}.") }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import config") },
        text = { Text(lines.joinToString("\n\n")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AutoStartRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Start at boot", style = MaterialTheme.typography.titleSmall)
            Text(
                "After the phone boots, start pipes that are switched on.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = "Start at boot" },
        )
    }
}

@Composable
private fun PipeRow(
    pipe: SavedPipe,
    status: PipeStatus?,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val modeLabel = if (pipe.mode == PipeMode.SERVER) "Server" else "Listener"
    val statusLabel = status?.state ?: if (pipe.enabled) "starting" else "off"
    val endpoint = "127.0.0.1:${pipe.localPort}"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(pipe.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "$modeLabel · $endpoint · $statusLabel",
                style = MaterialTheme.typography.bodySmall,
            )
            status?.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(endpoint)) },
        ) { Text("Copy") }
        Switch(checked = pipe.enabled, onCheckedChange = onToggle)
    }
}
