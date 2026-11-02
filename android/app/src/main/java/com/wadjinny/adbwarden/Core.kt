package com.wadjinny.adbwarden

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ConnInfo(val key: Long, val computerId: String, val computerName: String, val ip: String, val since: Long)

data class ActiveSession(
    val id: String,
    val connKey: Long,
    val computerId: String,
    val computerName: String,
    val agent: String,
    val reason: String,
    val startedAt: Long,
    val actions: Int = 0,
    val denied: Int = 0,
    val lastAction: String? = null,
)

data class PendingConsent(
    val key: String,
    val connKey: Long,
    val replyId: Long,
    val computerId: String,
    val computerName: String,
    val agent: String,
    val reason: String,
    val createdAt: Long,
)

data class PendingPairing(
    val key: String,
    val connKey: Long,
    val replyId: Long,
    val computerName: String,
    val reason: String,
    val createdAt: Long,
)

data class LinkMode(val code: String, val expiresAt: Long, val attempts: Int = 0)

/**
 * Central state + session/pairing/link logic. Bridge connections call into it from IO threads;
 * all mutations go through [lock].
 */
class Core(val ctx: Context) {
    val prefs = Prefs(ctx)
    val db = HistoryDb(ctx)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val rng = SecureRandom()

    private val _serverRunning = MutableStateFlow(false)
    val serverRunning: StateFlow<Boolean> = _serverRunning.asStateFlow()
    val serverError = MutableStateFlow<String?>(null)
    private val _connections = MutableStateFlow<List<ConnInfo>>(emptyList())
    val connections: StateFlow<List<ConnInfo>> = _connections.asStateFlow()
    private val _session = MutableStateFlow<ActiveSession?>(null)
    val session: StateFlow<ActiveSession?> = _session.asStateFlow()
    private val _consent = MutableStateFlow<PendingConsent?>(null)
    val consent: StateFlow<PendingConsent?> = _consent.asStateFlow()
    private val _pairing = MutableStateFlow<PendingPairing?>(null)
    val pairing: StateFlow<PendingPairing?> = _pairing.asStateFlow()
    private val _link = MutableStateFlow<LinkMode?>(null)
    val link: StateFlow<LinkMode?> = _link.asStateFlow()
    private val _policy = MutableStateFlow(prefs.policy)
    val policy: StateFlow<Policy> = _policy.asStateFlow()
    private val _computers = MutableStateFlow(prefs.computers)
    val computers: StateFlow<List<LinkedComputer>> = _computers.asStateFlow()
    /** Bumped on every DB write so screens can re-query. */
    private val _dbVersion = MutableStateFlow(0L)
    val dbVersion: StateFlow<Long> = _dbVersion.asStateFlow()

    private val conns = ConcurrentHashMap<Long, ClientConn>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    lateinit var notifier: Notifier
    lateinit var overlay: OverlayController
    lateinit var adbLock: AdbLock
    private var bridge: BridgeServer? = null

    init {
        db.housekeeping()
    }

    private fun bump() { _dbVersion.value = _dbVersion.value + 1 }

    fun log(level: String, message: String) {
        Log.println(if (level == "error") Log.ERROR else if (level == "warn") Log.WARN else Log.INFO, "AdbWarden", message)
        scope.launch { db.insertLog(level, message); bump() }
    }

    // ---------------------------------------------------------------- server

    fun startServer(): Unit = synchronized(lock) {
        if (bridge != null) return
        val port = prefs.port
        val b = BridgeServer(this, port)
        bridge = b
        b.start(
            onStarted = {
                _serverRunning.value = true; serverError.value = null
                log("info", "Server listening on port $port")
            },
            onFailed = { e ->
                _serverRunning.value = false; serverError.value = e.message
                synchronized(lock) { if (bridge === b) bridge = null }
                log("error", "Server could not start on port $port: ${e.message}")
            },
        )
    }

    fun stopServer() {
        val b = synchronized(lock) { bridge.also { bridge = null } } ?: return
        b.stop()
        conns.values.forEach { it.close() }
        _serverRunning.value = false
        log("info", "Server stopped")
    }

    fun restartServer() { stopServer(); startServer() }

    // ---------------------------------------------------------- connections

    fun onConnOpened(c: ClientConn) { conns[c.key] = c }

    fun onAuthed(c: ClientConn, comp: LinkedComputer) {
        val updated = comp.copy(lastSeen = System.currentTimeMillis())
        prefs.upsertComputer(updated)
        _computers.value = prefs.computers
        _connections.value = _connections.value + ConnInfo(c.key, comp.id, comp.name, c.ip, System.currentTimeMillis())
        log("info", "Computer \"${comp.name}\" connected from ${c.ip}")
    }

    fun onConnClosed(c: ClientConn) {
        conns.remove(c.key)
        val wasAuthed = _connections.value.any { it.key == c.key }
        _connections.value = _connections.value.filter { it.key != c.key }
        if (wasAuthed) log("info", "Computer \"${c.computerName}\" disconnected (${c.ip})")
        synchronized(lock) {
            _session.value?.takeIf { it.connKey == c.key }?.let { endSessionLocked("proxy_disconnected", null, notifyProxy = false) }
            _consent.value?.takeIf { it.connKey == c.key }?.let {
                _consent.value = null
                notifier.cancelConsent()
                recordRequest(it, "cancelled")
                log("warn", "Consent request from \"${it.computerName}\" cancelled: computer disconnected")
            }
            _pairing.value?.takeIf { it.connKey == c.key }?.let {
                _pairing.value = null
                notifier.cancelPairing()
                log("warn", "Pairing request cancelled: computer disconnected")
            }
        }
        refreshStatus()
    }

    fun authenticate(computerId: String, mac: String, nonce: String): LinkedComputer? {
        val comp = prefs.computers.firstOrNull { it.id == computerId } ?: return null
        val expected = Crypto.hmacHex(comp.token, nonce)
        return if (Crypto.constantTimeEquals(expected, mac.lowercase())) comp else null
    }

    fun removeComputer(id: String) {
        val name = prefs.computers.firstOrNull { it.id == id }?.name ?: id
        prefs.removeComputer(id)
        _computers.value = prefs.computers
        conns.values.filter { it.computerId == id }.forEach { it.close() }
        log("info", "Removed linked computer \"$name\"")
    }

    // ------------------------------------------------------------------ link

    fun startLink() {
        val code = (0 until 6).joinToString("") { rng.nextInt(10).toString() }
        _link.value = LinkMode(code, System.currentTimeMillis() + 180_000)
        log("info", "Link mode started")
    }

    fun cancelLink() { if (_link.value != null) { _link.value = null; log("info", "Link mode ended") } }

    sealed class LinkResult {
        data class Ok(val computer: LinkedComputer) : LinkResult()
        data class Fail(val code: String, val message: String) : LinkResult()
    }

    fun tryLink(code: String, host: String, ip: String): LinkResult = synchronized(lock) {
        val lm = _link.value
        if (lm == null || lm.expiresAt < System.currentTimeMillis()) {
            if (lm != null) _link.value = null
            log("warn", "Link attempt from $ip rejected: link mode is not open")
            return LinkResult.Fail("not_linking", "Open Setup → Link a computer on the phone first")
        }
        if (!Crypto.constantTimeEquals(lm.code, code)) {
            val n = lm.attempts + 1
            if (n >= 5) {
                _link.value = null
                log("warn", "Link attempt from $ip: wrong code, link mode closed after 5 attempts")
            } else {
                _link.value = lm.copy(attempts = n)
                log("warn", "Link attempt from $ip: wrong code ($n/5)")
            }
            return LinkResult.Fail("bad_link_code", "Wrong link code")
        }
        val now = System.currentTimeMillis()
        val comp = LinkedComputer(UUID.randomUUID().toString(), host.ifBlank { ip }, Crypto.randomToken(), now, now)
        prefs.upsertComputer(comp)
        _computers.value = prefs.computers
        _link.value = null
        log("info", "Linked computer \"${comp.name}\" ($ip)")
        LinkResult.Ok(comp)
    }

    // --------------------------------------------------------------- consent

    fun handleSessionRequest(c: ClientConn, replyId: Long, agent: String, reason: String): Unit = synchronized(lock) {
        val compId = c.computerId ?: return
        val who = "\"$agent\" on \"${c.computerName}\""
        fun deny(r: String) {
            c.send(JSONObject().put("t", "reply").put("id", replyId).put("approved", false).put("reason", r))
        }
        if (_session.value != null || _consent.value != null) {
            log("warn", "Session request from $who refused: busy"); deny("busy"); return
        }
        val cd = cooldownUntil[compId] ?: 0
        if (cd > System.currentTimeMillis()) {
            log("warn", "Session request from $who refused: cooldown"); deny("cooldown"); return
        }
        val p = PendingConsent(
            UUID.randomUUID().toString(), c.key, replyId, compId, c.computerName ?: "computer",
            agent.ifBlank { "unknown agent" }, reason.ifBlank { "(no reason given)" }, System.currentTimeMillis(),
        )
        _consent.value = p
        log("info", "Session requested by $who: ${p.reason}")
        notifier.showConsent(p)
        scope.launch {
            delay(CONSENT_TIMEOUT_MS)
            decideConsent(p.key, false, "timeout")
        }
    }

    /** Called by ConsentActivity / notification actions. */
    fun decideConsent(key: String, approved: Boolean, denyReason: String = "denied"): Unit = synchronized(lock) {
        val p = _consent.value?.takeIf { it.key == key } ?: return
        _consent.value = null
        notifier.cancelConsent()
        val c = conns[p.connKey]
        if (c == null) { recordRequest(p, "cancelled"); return }
        if (approved) {
            val s = ActiveSession(
                UUID.randomUUID().toString(), p.connKey, p.computerId, p.computerName, p.agent, p.reason,
                System.currentTimeMillis(),
            )
            db.insertSession(
                SessionRow(s.id, s.computerId, s.computerName, s.agent, s.reason, "active", s.startedAt, null, null, null, 0, 0)
            )
            bump()
            _session.value = s
            adbLock.open("the session")
            c.send(JSONObject().put("t", "reply").put("id", p.replyId).put("approved", true).put("sessionId", s.id))
            log("info", "Session approved for \"${p.agent}\" on \"${p.computerName}\"")
            overlay.show(s)
        } else {
            if (denyReason == "denied") cooldownUntil[p.computerId] = System.currentTimeMillis() + COOLDOWN_MS
            recordRequest(p, if (denyReason == "timeout") "timeout" else "denied")
            c.send(JSONObject().put("t", "reply").put("id", p.replyId).put("approved", false).put("reason", denyReason))
            log("info", "Session ${if (denyReason == "timeout") "request timed out" else "denied"} for \"${p.agent}\" on \"${p.computerName}\"")
        }
        refreshStatus()
    }

    private fun recordRequest(p: PendingConsent, status: String) {
        db.insertSession(
            SessionRow(UUID.randomUUID().toString(), p.computerId, p.computerName, p.agent, p.reason, status,
                p.createdAt, System.currentTimeMillis(), status, null, 0, 0)
        )
        bump()
    }

    // --------------------------------------------------------------- session

    fun handleAction(c: ClientConn, o: JSONObject): Unit = synchronized(lock) {
        val sid = o.optString("sessionId")
        val status = o.optString("status", "ok")
        val summary = o.optString("summary", o.optString("tool", "action"))
        val s = _session.value?.takeIf { it.id == sid && it.connKey == c.key }
        if (s == null && !db.sessionExists(sid)) return
        db.insertAction(
            ActionRow(
                sid, o.optInt("seq"), o.optLong("ts", System.currentTimeMillis()), o.optString("tool"), summary, status,
                o.optString("detail").takeIf { o.has("detail") && !o.isNull("detail") },
                if (o.has("durationMs") && !o.isNull("durationMs")) o.optLong("durationMs") else null,
            )
        )
        bump()
        if (s != null) {
            val label = if (status == "denied") "Denied: $summary" else summary
            val ns = s.copy(actions = s.actions + 1, denied = s.denied + if (status == "denied") 1 else 0, lastAction = label)
            _session.value = ns
            overlay.update(ns)
            notifier.updateStatus()
        }
    }

    fun handleSessionEnd(c: ClientConn, sessionId: String, reason: String, summary: String?): Unit = synchronized(lock) {
        val s = _session.value ?: return
        if (s.id != sessionId || s.connKey != c.key) return
        endSessionLocked(reason.ifBlank { "agent_ended" }, summary, notifyProxy = false)
    }

    /** User pressed Stop (pill, notification, or app). */
    fun stopSession(): Unit = synchronized(lock) {
        val s = _session.value ?: return
        cooldownUntil[s.computerId] = System.currentTimeMillis() + COOLDOWN_MS
        endSessionLocked("stopped_by_user", null, notifyProxy = true)
    }

    private fun endSessionLocked(reason: String, summary: String?, notifyProxy: Boolean) {
        val s = _session.value ?: return
        _session.value = null
        if (notifyProxy) conns[s.connKey]?.send(JSONObject().put("t", "stop").put("sessionId", s.id))
        db.endSession(s.id, reason, summary, s.actions, s.denied)
        bump()
        overlay.hide()
        adbLock.closeIfIdle()
        notifier.showDone(s, reason, summary)
        log("info", "Session ended (${reasonLabel(reason)}) for \"${s.agent}\": ${s.actions} actions, ${s.denied} denied")
        refreshStatus()
    }

    // --------------------------------------------------------------- pairing

    fun handlePairingRequest(c: ClientConn, replyId: Long, reason: String): Unit = synchronized(lock) {
        _pairing.value?.let { old ->
            conns[old.connKey]?.send(JSONObject().put("t", "reply").put("id", old.replyId).put("cancelled", true))
        }
        val p = PendingPairing(UUID.randomUUID().toString(), c.key, replyId, c.computerName ?: "computer",
            reason.ifBlank { "The computer needs to pair over wireless debugging" }, System.currentTimeMillis())
        _pairing.value = p
        adbLock.open("pairing")
        log("info", "Pairing code requested by \"${p.computerName}\": ${p.reason}")
        notifier.showPairing(p)
        scope.launch {
            delay(PAIRING_TIMEOUT_MS)
            synchronized(lock) {
                if (_pairing.value?.key == p.key) {
                    log("warn", "Pairing request timed out")
                    cancelPairingLocked()
                }
            }
        }
    }

    fun submitPairing(code: String, port: Int?): Boolean = synchronized(lock) {
        val p = _pairing.value ?: return false
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) return false
        _pairing.value = null
        conns[p.connKey]?.send(
            JSONObject().put("t", "reply").put("id", p.replyId).put("code", clean).put("port", port ?: JSONObject.NULL)
        )
        notifier.showPairingProgress()
        log("info", "Pairing code sent to \"${p.computerName}\"${if (port != null) " (port $port)" else ""}")
        true
    }

    fun cancelPairing(): Unit = synchronized(lock) { cancelPairingLocked() }

    private fun cancelPairingLocked() {
        val p = _pairing.value ?: return
        _pairing.value = null
        conns[p.connKey]?.send(JSONObject().put("t", "reply").put("id", p.replyId).put("cancelled", true))
        notifier.cancelPairing()
        log("info", "Pairing cancelled")
        adbLock.closeIfIdle()
    }

    fun handlePairingResult(ok: Boolean, message: String) {
        log(if (ok) "info" else "error", "Pairing ${if (ok) "succeeded" else "failed"}: $message")
        notifier.showPairingResult(ok, message)
        adbLock.closeIfIdle()
    }

    // ---------------------------------------------------------------- policy

    fun setPolicy(p: Policy) {
        val old = _policy.value
        if (old == p) return
        prefs.policy = p
        _policy.value = p
        val msg = JSONObject().put("t", "policy").put("policy", p.toJson())
        conns.values.filter { it.authed }.forEach { it.send(msg) }
        log("info", "Rules changed: ${describeChange(old, p)}")
        adbLock.refresh()
        if (p.adbLock) adbLock.closeIfIdle()
    }

    private fun describeChange(a: Policy, b: Policy): String {
        val parts = mutableListOf<String>()
        if (a.screen != b.screen) parts += "screen ${onOff(b.screen)}"
        if (a.input != b.input) parts += "input ${onOff(b.input)}"
        if (a.apps != b.apps) parts += "apps ${onOff(b.apps)}"
        if (a.install != b.install) parts += "install ${onOff(b.install)}"
        if (a.shell != b.shell) parts += "shell ${b.shell}"
        if (a.appFilter != b.appFilter) parts += "app filter ${b.appFilter}"
        if (a.packages != b.packages) parts += "${b.packages.size} filtered app(s)"
        if (a.idleTimeoutSec != b.idleTimeoutSec) parts += "idle timeout ${b.idleTimeoutSec / 60} min"
        if (a.adbLock != b.adbLock) parts += "ADB lock ${onOff(b.adbLock)}"
        if (a.adbLockUsb != b.adbLockUsb) parts += "ADB lock for USB ${onOff(b.adbLockUsb)}"
        return parts.joinToString(", ").ifEmpty { "no change" }
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    fun refreshStatus() { if (::notifier.isInitialized) notifier.updateStatus() }

    companion object {
        const val CONSENT_TIMEOUT_MS = 60_000L
        const val PAIRING_TIMEOUT_MS = 180_000L
        const val COOLDOWN_MS = 20_000L

        fun reasonLabel(r: String?): String = when (r) {
            "agent_ended" -> "ended by agent"
            "idle_timeout" -> "idle timeout"
            "proxy_exit" -> "proxy exited"
            "stopped_by_user" -> "stopped by you"
            "proxy_disconnected" -> "computer disconnected"
            "app_restarted" -> "app restarted"
            "denied" -> "denied"
            "timeout" -> "no answer"
            "cancelled" -> "cancelled"
            null -> ""
            else -> r.replace('_', ' ')
        }
    }
}
