package dev.patchpipe.app.ui.list

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.patchpipe.app.data.PipeMode
import dev.patchpipe.app.data.SavedPipe
import dev.patchpipe.app.ui.PatchpipeViewModel
import dev.patchpipe.core.PipeStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipeListScreen(
    vm: PatchpipeViewModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val pipes by vm.pipes.collectAsState()
    val statuses by vm.statuses.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Patchpipe") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Text("+")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            KeepAliveWarnings(modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
            if (pipes.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
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
                    modifier = Modifier.fillMaxSize(),
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
private fun PipeRow(
    pipe: SavedPipe,
    status: PipeStatus?,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val modeLabel = if (pipe.mode == PipeMode.SERVER) "Server" else "Listener"
    val statusLabel = status?.state ?: if (pipe.enabled) "starting" else "off"
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
                "$modeLabel · 127.0.0.1:${pipe.localPort} · $statusLabel",
                style = MaterialTheme.typography.bodySmall,
            )
            status?.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Switch(checked = pipe.enabled, onCheckedChange = onToggle)
    }
}
