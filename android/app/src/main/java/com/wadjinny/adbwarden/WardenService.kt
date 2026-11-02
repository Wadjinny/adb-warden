package com.wadjinny.adbwarden

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Foreground service that keeps the bridge server and mDNS advertisement alive. */
class WardenService : Service() {
    private lateinit var core: Core
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        core = WardenApp.core(this)
        val n = core.notifier.buildStatus()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_STATUS, n)
        }
        watch = core.scope.launch {
            combine(core.serverRunning, core.connections, core.session) { _, _, _ -> }.collect { core.notifier.updateStatus() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTART) {
            unregisterNsd(); core.restartServer()
        } else {
            core.startServer()
        }
        registerNsd()
        return START_STICKY
    }

    override fun onDestroy() {
        watch?.cancel()
        unregisterNsd()
        core.stopServer()
        super.onDestroy()
    }

    private fun registerNsd() {
        if (nsdListener != null) return
        val mgr = getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "ADB Warden ${Build.MODEL}"
            serviceType = "_adbwarden._tcp"
            port = core.prefs.port
            setAttribute("id", core.prefs.deviceId)
            setAttribute("v", "1")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { core.log("info", "Advertising \"${si.serviceName}\" on the network") }
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                core.log("warn", "mDNS advertisement failed ($errorCode)"); nsdListener = null
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
        }
        nsd = mgr; nsdListener = l
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { nsdListener = null }
    }

    private fun unregisterNsd() {
        val l = nsdListener ?: return
        runCatching { nsd?.unregisterService(l) }
        nsdListener = null
    }

    companion object {
        const val ACTION_RESTART = "restart"

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WardenService::class.java))
        }

        fun restart(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WardenService::class.java).setAction(ACTION_RESTART))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WardenService::class.java)) }
    }
}
