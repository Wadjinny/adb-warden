package com.wadjinny.adbwarden

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val core = WardenApp.core(context)
        when (intent.action) {
            Notifier.ACTION_STOP -> core.stopSession()
            Notifier.ACTION_ALLOW -> {
                // Approving from the shade on a locked phone is not allowed: open the prompt instead.
                val km = context.getSystemService(android.app.KeyguardManager::class.java)
                if (km.isKeyguardLocked) {
                    context.startActivity(Intent(context, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    intent.getStringExtra(Notifier.EXTRA_KEY)?.let { core.decideConsent(it, true) }
                }
            }
            Notifier.ACTION_DENY -> intent.getStringExtra(Notifier.EXTRA_KEY)?.let { core.decideConsent(it, false) }
            Notifier.ACTION_PAIR_CANCEL -> core.cancelPairing()
            Notifier.ACTION_ADB_PAUSE -> core.adbLock.pause(AdbLock.PAUSE_MS)
            Notifier.ACTION_PAIR_CODE -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_CODE)?.toString() ?: return
                val (code, port) = parsePairingInput(text)
                if (code == null || !core.submitPairing(code, port)) {
                    core.notifier.showPairingError("Type the 6-digit code (optionally followed by the port)")
                }
            }
        }
    }

    companion object {
        /** Accepts "123456", "123 456", "123456 37155", "123456:37155". */
        fun parsePairingInput(text: String): Pair<String?, Int?> {
            val groups = Regex("\\d+").findAll(text).map { it.value }.toList()
            if (groups.isEmpty()) return null to null
            val joined = groups.joinToString("")
            if (groups.size == 2 && groups[0].length == 3 && groups[1].length == 3) return joined to null
            val code = groups[0].takeIf { it.length == 6 } ?: return (if (joined.length == 6) joined else null) to null
            val port = groups.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..65535 }
            return code to port
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (WardenApp.core(context).prefs.startOnBoot) WardenService.start(context)
    }
}
