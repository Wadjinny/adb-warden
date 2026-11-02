package com.wadjinny.adbwarden

import android.app.Application
import android.content.Context

class WardenApp : Application() {
    lateinit var core: Core
        private set

    override fun onCreate() {
        super.onCreate()
        core = Core(this)
        core.notifier = Notifier(this, core)
        core.overlay = OverlayController(this, core)
        core.adbLock = AdbLock(this, core)
        core.adbLock.start()
    }

    companion object {
        fun core(ctx: Context): Core = (ctx.applicationContext as WardenApp).core
    }
}
