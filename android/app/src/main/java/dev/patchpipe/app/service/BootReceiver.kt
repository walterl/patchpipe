package dev.patchpipe.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.patchpipe.app.engine.PatchpipeEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Brings enabled pipes up after the user unlocks the device, when start-at-boot is on.
 * The receiver is disabled in the manifest until the user opts in.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(BOOT_START_TIMEOUT_MS) {
                    PatchpipeEngine.from(context).onBootCompleted(action)
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "boot start timed out", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "boot start failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "PatchpipeBoot"
        private const val BOOT_START_TIMEOUT_MS = 15_000L
    }
}
