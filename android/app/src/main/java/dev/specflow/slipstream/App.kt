package dev.specflow.slipstream

import android.app.Application
import dev.specflow.slipstream.core.Store

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Read the store once, on the main thread, before anything asks for it.
        // It is a few kilobytes; the alternative is every screen guarding
        // against a null it will never actually see.
        Store.of(this)
    }
}
