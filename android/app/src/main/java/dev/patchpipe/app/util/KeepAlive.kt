package dev.patchpipe.app.util

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import dev.patchpipe.app.service.PipeForegroundService

/** Whether Android lets Patchpipe run and restart its pipes in the background without limits. */
fun isBatteryUnrestricted(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java)
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/** Whether the pipe status notification can be shown (app-level and pipe channel). */
fun arePipeNotificationsEnabled(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) { return false }
    val channel = context.getSystemService(NotificationManager::class.java)
        .getNotificationChannel(PipeForegroundService.CHANNEL_ID)
    return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
}

/** Shows the system "allow unrestricted battery use" prompt, or the battery settings list if missing. */
@SuppressLint("BatteryLife")
fun openBatteryExemption(context: Context) {
    val prompt = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        "package:${context.packageName}".toUri(),
    )
    try {
        context.startActivity(prompt)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}
