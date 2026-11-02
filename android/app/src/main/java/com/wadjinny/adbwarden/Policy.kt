package com.wadjinny.adbwarden

import org.json.JSONArray
import org.json.JSONObject

/** Mirrors the policy object in docs/PROTOCOL.md. */
data class Policy(
    val screen: Boolean = true,
    val input: Boolean = true,
    val apps: Boolean = true,
    val install: Boolean = false,
    val shell: String = SHELL_READONLY,
    val appFilter: String = FILTER_BLOCKLIST,
    val packages: List<String> = emptyList(),
    val idleTimeoutSec: Int = 300,
    val adbLock: Boolean = false,
    val adbLockUsb: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("screen", screen)
        .put("input", input)
        .put("apps", apps)
        .put("install", install)
        .put("shell", shell)
        .put("appFilter", appFilter)
        .put("packages", JSONArray(packages))
        .put("idleTimeoutSec", idleTimeoutSec)
        .put("adbLock", adbLock)
        .put("adbLockUsb", adbLockUsb)

    /** Human readable lines for the consent prompt. Pair = (allowed, text). */
    fun summary(): List<Pair<Boolean, String>> {
        val out = mutableListOf<Pair<Boolean, String>>()
        out += screen to "See the screen and read on-screen text"
        out += input to "Tap, swipe, type and press keys"
        out += apps to "Open apps and list installed apps"
        out += install to "Install apps"
        out += when (shell) {
            SHELL_ANY -> true to "Run any shell command (full control)"
            SHELL_READONLY -> true to "Run read-only shell commands"
            else -> false to "Run shell commands"
        }
        when (appFilter) {
            FILTER_BLOCKLIST -> if (packages.isNotEmpty()) out += false to "Use ${packages.size} blocked app(s)"
            FILTER_ALLOWLIST -> out += true to "Only use ${packages.size} allowed app(s)"
        }
        out += true to "Session ends after ${idleTimeoutSec / 60} min without activity"
        return out
    }

    companion object {
        const val SHELL_OFF = "off"
        const val SHELL_READONLY = "readonly"
        const val SHELL_ANY = "any"
        const val FILTER_OFF = "off"
        const val FILTER_BLOCKLIST = "blocklist"
        const val FILTER_ALLOWLIST = "allowlist"

        fun fromJson(o: JSONObject?): Policy {
            if (o == null) return Policy()
            val pk = o.optJSONArray("packages")
            val shell = o.optString("shell", SHELL_READONLY).takeIf { it in listOf(SHELL_OFF, SHELL_READONLY, SHELL_ANY) } ?: SHELL_READONLY
            val filter = o.optString("appFilter", FILTER_BLOCKLIST).takeIf { it in listOf(FILTER_OFF, FILTER_BLOCKLIST, FILTER_ALLOWLIST) } ?: FILTER_BLOCKLIST
            return Policy(
                screen = o.optBoolean("screen", true),
                input = o.optBoolean("input", true),
                apps = o.optBoolean("apps", true),
                install = o.optBoolean("install", false),
                shell = shell,
                appFilter = filter,
                packages = if (pk == null) emptyList() else (0 until pk.length()).map { pk.getString(it) },
                idleTimeoutSec = o.optInt("idleTimeoutSec", 300).coerceIn(60, 3600),
                adbLock = o.optBoolean("adbLock", false),
                adbLockUsb = o.optBoolean("adbLockUsb", false),
            )
        }
    }
}
