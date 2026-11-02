package com.wadjinny.adbwarden

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** TCP NDJSON server described in docs/PROTOCOL.md. One thread per client. */
class BridgeServer(private val core: Core, private val port: Int) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val nextKey = AtomicLong(1)

    fun start(onStarted: () -> Unit, onFailed: (Exception) -> Unit) {
        running = true
        thread(name = "warden-accept", isDaemon = true) {
            val ss = try {
                ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("0.0.0.0", port)) }
            } catch (e: Exception) {
                running = false; onFailed(e); return@thread
            }
            server = ss
            onStarted()
            while (running) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                val conn = ClientConn(nextKey.getAndIncrement(), s, core)
                core.onConnOpened(conn)
                thread(name = "warden-conn-${conn.key}", isDaemon = true) { conn.run() }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }
}

class ClientConn(val key: Long, private val socket: Socket, private val core: Core) {
    val ip: String = socket.inetAddress?.hostAddress ?: "?"
    @Volatile var authed = false; private set
    @Volatile var computerId: String? = null; private set
    @Volatile var computerName: String? = null; private set
    private val out: OutputStream = socket.getOutputStream()
    @Volatile private var closed = false

    /** Writes happen on a per-connection thread, so callers may be on the main thread. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "warden-write-$key").apply { isDaemon = true } }

    fun send(o: JSONObject) {
        if (closed) return
        val bytes = (o.toString() + "\n").toByteArray(Charsets.UTF_8)
        runCatching {
            writer.execute {
                try { out.write(bytes); out.flush() } catch (e: IOException) { close() }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        // Let queued replies (e.g. "linked", "error") flush before the socket closes.
        runCatching { writer.execute { runCatching { socket.close() } }; writer.shutdown() }
            .onFailure { runCatching { socket.close() } }
    }

    fun run() {
        try {
            socket.soTimeout = SILENCE_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val nonce = Crypto.randomHex(16)
            send(
                JSONObject().put("t", "hello").put("v", 1).put("deviceId", core.prefs.deviceId)
                    .put("deviceName", deviceName()).put("nonce", nonce)
            )
            // Handshake
            val first = readJson(input) ?: return
            when (first.optString("t")) {
                "auth" -> {
                    val comp = core.authenticate(first.optString("computerId"), first.optString("mac"), nonce)
                    if (comp == null) {
                        core.log("warn", "Auth failed from $ip")
                        error("bad_auth", "Unknown computer or wrong token. Link the computer again."); return
                    }
                    computerId = comp.id
                    computerName = comp.name
                    authed = true
                    core.onAuthed(this, comp)
                    send(
                        JSONObject().put("t", "ready").put("policy", core.policy.value.toJson()).put("device", deviceJson())
                    )
                }
                "link" -> {
                    when (val r = core.tryLink(first.optString("code"), first.optString("host"), ip)) {
                        is Core.LinkResult.Ok -> send(
                            JSONObject().put("t", "linked").put("computerId", r.computer.id).put("token", r.computer.token)
                                .put("deviceId", core.prefs.deviceId).put("deviceName", deviceName())
                        )
                        is Core.LinkResult.Fail -> error(r.code, r.message)
                    }
                    return
                }
                else -> { error("bad_request", "Expected auth or link"); return }
            }
            // Main loop
            while (!closed) {
                val o = readJson(input) ?: break
                handle(o)
            }
        } catch (e: SocketTimeoutException) {
            core.log("warn", "Connection from $ip closed: no data for ${SILENCE_MS / 1000} s")
        } catch (e: IOException) {
            // closed
        } catch (e: Exception) {
            core.log("error", "Connection from $ip failed: ${e.message}")
        } finally {
            close()
            core.onConnClosed(this)
        }
    }

    private fun handle(o: JSONObject) {
        when (o.optString("t")) {
            "ping" -> send(JSONObject().put("t", "pong"))
            "pong" -> {}
            "session_request" -> core.handleSessionRequest(this, o.optLong("id"), o.optString("agent"), o.optString("reason"))
            "pairing_request" -> core.handlePairingRequest(this, o.optLong("id"), o.optString("reason"))
            "pairing_result" -> core.handlePairingResult(o.optBoolean("ok"), o.optString("message"))
            "action" -> core.handleAction(this, o)
            "session_end" -> core.handleSessionEnd(
                this, o.optString("sessionId"), o.optString("reason"),
                o.optString("summary").takeIf { o.has("summary") && !o.isNull("summary") && it.isNotBlank() },
            )
            "log" -> {
                val lvl = o.optString("level", "info").takeIf { it in listOf("info", "warn", "error") } ?: "info"
                core.log(lvl, "[$computerName] ${o.optString("message")}")
            }
            else -> {} // ignore unknown types for forward compatibility
        }
    }

    private fun error(code: String, message: String) {
        send(JSONObject().put("t", "error").put("code", code).put("message", message))
    }

    /** Reads one NDJSON line (max 1 MiB). Returns null on EOF or malformed JSON closes the connection. */
    private fun readJson(input: InputStream): JSONObject? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            buf.write(b)
            if (buf.size() > MAX_LINE) {
                error("bad_request", "Line too long"); return null
            }
        }
        val line = buf.toString(Charsets.UTF_8.name()).trim()
        if (line.isEmpty()) return readJson(input)
        return try {
            JSONObject(line)
        } catch (e: Exception) {
            error("bad_request", "Malformed JSON"); null
        }
    }

    companion object {
        const val SILENCE_MS = 60_000
        const val MAX_LINE = 1 shl 20

        fun deviceName(): String {
            val m = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$m ${Build.MODEL}"
        }

        fun deviceJson(): JSONObject = JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("ips", JSONArray(Net.ipv4Addresses()))
    }
}
