package com.wadjinny.adbwarden

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LinkedComputer(
    val id: String,
    val name: String,
    val token: String,
    val linkedAt: Long,
    val lastSeen: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("token", token)
        .put("linkedAt", linkedAt).put("lastSeen", lastSeen)

    companion object {
        fun fromJson(o: JSONObject) = LinkedComputer(
            o.getString("id"), o.optString("name", "computer"), o.getString("token"),
            o.optLong("linkedAt"), o.optLong("lastSeen"),
        )
    }
}

/** Persistent settings. All writes are synchronous-safe via apply(). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("warden", Context.MODE_PRIVATE)

    val deviceId: String = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        sp.edit().putString("deviceId", it).apply()
    }

    var port: Int
        get() = sp.getInt("port", DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    var adbLockPausedUntil: Long
        get() = sp.getLong("adbLockPausedUntil", 0)
        set(v) = sp.edit().putLong("adbLockPausedUntil", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", true)
        set(v) = sp.edit().putBoolean("startOnBoot", v).apply()

    var policy: Policy
        get() = Policy.fromJson(sp.getString("policy", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
        set(v) = sp.edit().putString("policy", v.toJson().toString()).apply()

    @get:Synchronized @set:Synchronized
    var computers: List<LinkedComputer>
        get() {
            val raw = sp.getString("computers", null) ?: return emptyList()
            return runCatching {
                val a = JSONArray(raw)
                (0 until a.length()).map { LinkedComputer.fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        set(v) {
            val a = JSONArray(); v.forEach { a.put(it.toJson()) }
            sp.edit().putString("computers", a.toString()).apply()
        }

    @Synchronized
    fun upsertComputer(c: LinkedComputer) {
        computers = computers.filter { it.id != c.id } + c
    }

    @Synchronized
    fun removeComputer(id: String) {
        computers = computers.filter { it.id != id }
    }

    companion object {
        const val DEFAULT_PORT = 47800
    }
}
