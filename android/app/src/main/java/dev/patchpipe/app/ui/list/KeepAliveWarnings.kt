package dev.patchpipe.app.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.patchpipe.app.util.arePipeNotificationsEnabled
import dev.patchpipe.app.util.isBatteryUnrestricted
import dev.patchpipe.app.util.openBatteryExemption
import dev.patchpipe.app.util.openNotificationSettings

/** Banners for settings that stop pipes from staying up or hide their status. Re-checked on resume. */
@Composable
fun KeepAliveWarnings(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var batteryUnrestricted by remember { mutableStateOf(true) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        batteryUnrestricted = isBatteryUnrestricted(context)
        notificationsEnabled = arePipeNotificationsEnabled(context)
        onPauseOrDispose { }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!batteryUnrestricted) {
            WarningCard(
                title = "Battery optimization is on",
                body = "If Android or your phone's battery saver stops Patchpipe in the " +
                    "background, it may not be allowed to bring your pipes back. They stay down " +
                    "until you reopen the app. Allowing unrestricted battery use does not " +
                    "make Patchpipe do more work; it only stops Android from pausing it.",
                action = "Allow",
                onAction = { openBatteryExemption(context) },
            )
        }
        if (!notificationsEnabled) {
            WarningCard(
                title = "Notifications are off",
                body = "Pipes keep running, but you can't see their status without opening " +
                    "the app.",
                action = "Settings",
                onAction = { openNotificationSettings(context) },
            )
        }
    }
}

@Composable
private fun WarningCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                Text(action)
            }
        }
    }
}
