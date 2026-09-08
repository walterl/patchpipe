package dev.patchpipe.app

import android.content.Context

internal object NativeInit {
    init {
        System.loadLibrary("patchpipe_core")
    }

    @JvmStatic
    external fun installIrohDnsContext(context: Context)
}
