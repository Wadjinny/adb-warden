package com.wadjinny.adbwarden

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

class Notifier(private val ctx: Context, private val core: Core) {
    private val nm = NotificationManagerCompat.from(ctx)

    init {
        val sys = ctx.getSystemService(NotificationManager::class.java)
        sys.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Server status and the agent-in-control notice"
            }
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_REQUESTS, "Requests", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Control requests and pairing codes"
            }
        )
        sys.createNotificationChannel(
            NotificationChannel(CH_SESSIONS, "Finished sessions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Summary when an agent finishes"
            }
        )
    }

    private fun canPost() = canPost(ctx)

    private fun post(id: Int, n: Notification) { if (canPost()) runCatching { nm.notify(id, n) } }

    private fun activityPi(req: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(ctx, req, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun broadcastPi(req: Int, action: String, extras: Intent.() -> Unit = {}, mutable: Boolean = false): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(action).apply(extras)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, req, i, flags)
    }

    private fun openApp() = activityPi(0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))

    // ------------------------------------------------------------- status (FGS)

    fun buildStatus(): Notification {
        val s = core.session.value
        val conns = core.connections.value
        val b = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_warden)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (s != null) {
            b.setContentTitle("Agent in control — ${s.agent}")
                .setContentText(s.lastAction ?: "Waiting for the first action")
                .setColor(0xFF7C3AED.toInt()).setColorized(true)
                .setUsesChronometer(true).setWhen(s.startedAt)
                .addAction(0, "Stop", broadcastPi(1, ACTION_STOP))
        } else if (conns.isNotEmpty()) {
            b.setContentTitle("Connected: ${conns.joinToString { it.computerName }.take(60)}")
                .setContentText("No agent in control")
        } else {
            b.setContentTitle(if (core.serverRunning.value) "Waiting for a computer" else "Server stopped")
                .setContentText("Port ${core.prefs.port}")
        }
        return b.build()
    }

    fun updateStatus() = post(ID_STATUS, buildStatus())

    // ---------------------------------------------------------------- consent

    fun showConsent(p: PendingConsent) {
        val open = Intent(ctx, ConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Background start is allowed because the app holds "display over other apps".
        runCatching { ctx.startActivity(open) }
        val full = activityPi(2, open)
        val n = NotificationCompat.Builder(ctx, CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_warden)
            .setContentTitle("${p.agent} wants to control your phone")
            .setContentText("${p.computerName}: ${p.reason}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("From ${p.computerName}\n${p.reason}"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
            .setTimeoutAfter(Core.CONSENT_TIMEOUT_MS)
            .setAutoCancel(true)
            .addAction(0, "Deny", broadcastPi(3, ACTION_DENY, { putExtra(EXTRA_KEY, p.key) }))
            .addAction(0, "Allow", broadcastPi(4, ACTION_ALLOW, { putExtra(EXTRA_KEY, p.key) }))
            .build()
        post(ID_CONSENT, n)
    }

    fun cancelConsent() = nm.cancel(ID_CONSENT)

    // ---------------------------------------------------------------- pairing

    fun showPairing(p: PendingPairing) {
        val open = Intent(ctx, PairingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { ctx.startActivity(open) }
        post(ID_PAIRING, pairingBuilder(p).build())
    }

    private fun pairingBuilder(p: PendingPairing): NotificationCompat.Builder {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit code (optional: code port)").build()
        val reply = NotificationCompat.Action.Builder(0, "Enter pairing code", broadcastPi(5, ACTION_PAIR_CODE, mutable = true))
            .addRemoteInput(input).setAllowGeneratedReplies(false).build()
        val dev = activityPi(
            6, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return NotificationCompat.Builder(ctx, CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_warden)
            .setContentTitle("Pairing code needed")
            .setContentText("${p.computerName}: Wireless debugging → Pair device with pairing code")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${p.reason}\n\nOpen Developer options → Wireless debugging → Pair device with pairing code, " +
                        "then type the 6-digit code here without leaving that screen."
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityPi(7, Intent(ctx, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
            .setTimeoutAfter(Core.PAIRING_TIMEOUT_MS)
            .addAction(reply)
            .addAction(0, "Wireless debugging", dev)
            .addAction(0, "Cancel", broadcastPi(8, ACTION_PAIR_CANCEL))
    }

    /** Re-post with an error so the inline reply spinner stops. */
    fun showPairingError(message: String) {
        val p = core.pairing.value ?: return
        post(ID_PAIRING, pairingBuilder(p).setContentText(message).build())
    }

    fun showPairingProgress() {
        post(
            ID_PAIRING, NotificationCompat.Builder(ctx, CH_REQUESTS)
                .setSmallIcon(R.drawable.ic_stat_warden)
                .setContentTitle("Pairing…")
                .setContentText("Code sent to the computer")
                .setProgress(0, 0, true)
                .setTimeoutAfter(60_000)
                .build()
        )
    }

    fun cancelPairing() = nm.cancel(ID_PAIRING)

    fun showPairingResult(ok: Boolean, message: String) {
        post(
            ID_PAIRING, NotificationCompat.Builder(ctx, CH_SESSIONS)
                .setSmallIcon(R.drawable.ic_stat_warden)
                .setContentTitle(if (ok) "Paired with the computer" else "Pairing failed")
                .setContentText(message)
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
        )
    }

    // ------------------------------------------------------------------- done

    fun showDone(s: ActiveSession, reason: String, summary: String?) {
        val dur = formatDuration(System.currentTimeMillis() - s.startedAt)
        val line = "$dur · ${s.actions} actions · ${s.denied} denied · ${Core.reasonLabel(reason)}"
        val big = if (summary.isNullOrBlank()) line else "$line\n\n$summary"
        post(
            ID_DONE, NotificationCompat.Builder(ctx, CH_SESSIONS)
                .setSmallIcon(R.drawable.ic_stat_warden)
                .setContentTitle("${s.agent} finished")
                .setContentText(line)
                .setStyle(NotificationCompat.BigTextStyle().bigText(big))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build()
        )
        updateStatus()
    }

    fun showAdbLockBlocked(what: String) = post(
        ID_ADB_LOCK, NotificationCompat.Builder(ctx, CH_SESSIONS)
            .setSmallIcon(R.drawable.ic_stat_warden)
            .setContentTitle("Turned $what debugging off")
            .setContentText("It was turned on outside a session. ADB lock keeps it off until you approve an agent.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "It was turned on outside a session. ADB lock keeps it off until you approve an agent. " +
                    "To use adb yourself, pause the lock."))
            .setContentIntent(openApp())
            .addAction(0, "Pause lock for 1 hour", broadcastPi(40, ACTION_ADB_PAUSE))
            .setAutoCancel(true)
            .build()
    )

    fun cancelAdbLockBlocked() = nm.cancel(ID_ADB_LOCK)

    companion object {
        fun canPost(ctx: Context): Boolean = if (android.os.Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else NotificationManagerCompat.from(ctx).areNotificationsEnabled()

        const val CH_STATUS = "status"
        const val CH_REQUESTS = "requests"
        const val CH_SESSIONS = "sessions"
        const val ID_STATUS = 1
        const val ID_CONSENT = 2
        const val ID_PAIRING = 3
        const val ID_DONE = 4
        const val ID_ADB_LOCK = 5
        const val ACTION_ADB_PAUSE = "com.wadjinny.adbwarden.ADB_PAUSE"

        const val ACTION_STOP = "com.wadjinny.adbwarden.STOP"
        const val ACTION_ALLOW = "com.wadjinny.adbwarden.ALLOW"
        const val ACTION_DENY = "com.wadjinny.adbwarden.DENY"
        const val ACTION_PAIR_CODE = "com.wadjinny.adbwarden.PAIR_CODE"
        const val ACTION_PAIR_CANCEL = "com.wadjinny.adbwarden.PAIR_CANCEL"
        const val EXTRA_KEY = "key"
        const val KEY_CODE = "code"

        fun formatDuration(ms: Long): String {
            val s = ms / 1000
            return when {
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m ${s % 60}s"
                else -> "${s / 3600}h ${(s % 3600) / 60}m"
            }
        }
    }
}
