package dev.patchpipe.app

import android.app.Application
import dev.patchpipe.app.engine.PatchpipeEngine

class PatchpipeApp : Application() {
    lateinit var engine: PatchpipeEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = PatchpipeEngine(this)
    }
}
