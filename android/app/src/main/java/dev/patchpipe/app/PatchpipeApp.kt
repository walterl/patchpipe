package dev.patchpipe.app

import android.app.Application
import dev.patchpipe.app.engine.PatchpipeEngine

class PatchpipeApp : Application() {
    lateinit var engine: PatchpipeEngine
        private set

    override fun onCreate() {
        super.onCreate()
        try {
            NativeInit.installIrohDnsContext(this)
        } catch (e: Throwable) {
            android.util.Log.w("Patchpipe", "system DNS JNI init failed; Quad9 fallback", e)
        }
        engine = PatchpipeEngine(this)
    }
}
