package dev.patchpipe.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

/**
 * Calls [onChange] whenever the device's default network switches (e.g. Wi-Fi to cellular),
 * but not for the network already present at registration. Event-driven; no polling.
 */
fun watchDefaultNetworkChanges(context: Context, onChange: () -> Unit) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null

        override fun onAvailable(network: Network) {
            val previous = current
            current = network
            if (previous != null && previous != network) { onChange() }
        }
    })
}
