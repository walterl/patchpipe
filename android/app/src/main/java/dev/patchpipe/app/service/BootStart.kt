package dev.patchpipe.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Device boot starts pipes only when the user opted in.
 * [Intent.ACTION_LOCKED_BOOT_COMPLETED] is ignored: pipe config is in
 * credential-encrypted storage, which is unavailable until the user unlocks.
 *
 * The manifest receiver is disabled. [setBootReceiverEnabled] turns it on
 * only while [autoStart] is true, so a default install receives no boot broadcast.
 */
fun shouldStartOnBoot(autoStart: Boolean, action: String?): Boolean {
    return autoStart && action == Intent.ACTION_BOOT_COMPLETED
}

/**
 * Enables or disables [BootReceiver]. The manifest declares the receiver
 * disabled, so [PackageManager.COMPONENT_ENABLED_STATE_DEFAULT] means off.
 */
fun setBootReceiverEnabled(context: Context, enabled: Boolean) {
    val pm = context.packageManager
    val component = ComponentName(context, BootReceiver::class.java)
    val currentlyEnabled = pm.getComponentEnabledSetting(component) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    if (currentlyEnabled == enabled) return
    val state = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
}
