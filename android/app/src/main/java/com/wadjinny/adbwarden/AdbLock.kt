package com.wadjinny.adbwarden

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdbState(val wifi: Boolean, val usb: Boolean, val hasPermission: Boolean, val pausedUntil: Long)

/**
 * ADB lock: keeps wireless (and optionally USB) debugging off outside approved sessions, so nothing on any
 * computer can reach the phone over adb unless the user has just tapped Allow. Needs WRITE_SECURE_SETTINGS,
 * granted once with `adb shell pm grant com.wadjinny.adbwarden android.permission.WRITE_SECURE_SETTINGS`.
 */
class AdbLock(private val ctx: Context, private val core: Core) {
    private val cr = ctx.contentResolver
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AdbState> = _state.asStateFlow()
    private var resumeJob: Job? = null

    private var settleJob: Job? = null
    private var lastWarnAt = 0L

    /**
     * Android's AdbService writes the setting back while it is still starting adbd, so reacting immediately
     * makes the two fight. Wait for the system to settle, then enforce once.
     */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh()
            settleJob?.cancel()
            settleJob = core.scope.launch {
                delay(SETTLE_MS)
                refresh()
                enforce(fromObserver = true)
            }
        }
    }

    fun start() {
        cr.registerContentObserver(Settings.Global.getUriFor(KEY_WIFI), false, observer)
        cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, observer)
        schedulePauseEnd()
        enforce(fromObserver = false)
    }

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun read() = AdbState(
        wifi = Settings.Global.getInt(cr, KEY_WIFI, 0) == 1,
        usb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1,
        hasPermission = hasPermission(),
        pausedUntil = core.prefs.adbLockPausedUntil,
    )

    fun refresh() { _state.value = read() }

    /** Lock is active: rule on, permission granted, not paused by the user. */
    fun active(): Boolean {
        val p = core.policy.value
        return p.adbLock && hasPermission() && System.currentTimeMillis() >= core.prefs.adbLockPausedUntil
    }

    private fun busy() = core.session.value != null || core.pairing.value != null

    /** Turn debugging on for an approved session or a pairing request. */
    fun open(why: String) {
        if (!active()) return
        val p = core.policy.value
        val changed = mutableListOf<String>()
        if (p.adbLockUsb && !read().usb && put(Settings.Global.ADB_ENABLED, 1)) changed += "USB"
        if (!read().wifi && put(KEY_WIFI, 1)) changed += "wireless"
        if (changed.isNotEmpty()) core.log("info", "ADB lock: turned ${changed.joinToString(" and ")} debugging on for $why")
        refresh()
    }

    /** Turn debugging off if nothing needs it. */
    fun closeIfIdle() = enforce(fromObserver = false)

    private fun enforce(fromObserver: Boolean) {
        if (!active() || busy()) return
        val p = core.policy.value
        val s = read()
        val changed = mutableListOf<String>()
        if (s.wifi && put(KEY_WIFI, 0)) changed += "wireless"
        if (p.adbLockUsb && s.usb && put(Settings.Global.ADB_ENABLED, 0)) changed += "USB"
        refresh()
        if (changed.isEmpty()) return
        val what = changed.joinToString(" and ")
        if (fromObserver) {
            val now = System.currentTimeMillis()
            if (now - lastWarnAt > WARN_EVERY_MS) {
                lastWarnAt = now
                core.log("warn", "ADB lock: $what debugging was turned on outside a session and was turned off again")
                core.notifier.showAdbLockBlocked(what)
            }
        } else {
            core.log("info", "ADB lock: turned $what debugging off")
        }
    }

    /** Let the user use adb themselves for a while. */
    fun pause(ms: Long) {
        core.prefs.adbLockPausedUntil = System.currentTimeMillis() + ms
        core.log("info", "ADB lock paused for ${ms / 60_000} min")
        core.notifier.cancelAdbLockBlocked()
        refresh()
        schedulePauseEnd()
    }

    fun resume() {
        core.prefs.adbLockPausedUntil = 0
        resumeJob?.cancel()
        core.log("info", "ADB lock resumed")
        refresh()
        enforce(fromObserver = false)
    }

    private fun schedulePauseEnd() {
        resumeJob?.cancel()
        val wait = core.prefs.adbLockPausedUntil - System.currentTimeMillis()
        if (wait <= 0) return
        resumeJob = core.scope.launch {
            delay(wait)
            core.log("info", "ADB lock pause ended")
            refresh()
            enforce(fromObserver = false)
        }
    }

    private fun put(key: String, v: Int): Boolean = runCatching { Settings.Global.putInt(cr, key, v) }
        .onFailure { core.log("error", "ADB lock: could not change $key: ${it.message}") }
        .getOrDefault(false)

    companion object {
        /** Settings.Global.ADB_WIFI_ENABLED (hidden constant, API 30+). */
        const val KEY_WIFI = "adb_wifi_enabled"
        const val GRANT_COMMAND = "adb shell pm grant com.wadjinny.adbwarden android.permission.WRITE_SECURE_SETTINGS"
        const val PAUSE_MS = 60 * 60_000L
        const val SETTLE_MS = 1500L
        const val WARN_EVERY_MS = 10_000L
    }
}
