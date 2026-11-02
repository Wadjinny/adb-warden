package com.wadjinny.adbwarden.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wadjinny.adbwarden.BottomBarSpace
import com.wadjinny.adbwarden.Core
import com.wadjinny.adbwarden.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RulesScreen(core: Core) {
    val t = LocalTokens.current
    val p by core.policy.collectAsState()
    var picker by remember { mutableStateOf(false) }
    fun set(np: Policy) = core.setPolicy(np)

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            GradientHeader("Rules", "What an agent may do. Checked by the computer on every action.") {
                val on = listOf(p.screen, p.input, p.apps, p.install).count { it }
                GlassTile("$on of 4", "Tool groups on")
                GlassTile(
                    when (p.shell) { Policy.SHELL_ANY -> "Full"; Policy.SHELL_READONLY -> "Read-only"; else -> "Off" }, "Shell",
                )
                GlassTile("${p.idleTimeoutSec / 60} min", "Idle timeout")
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.Face, t.violet, "Tools")
                SectionGap(4.dp)
                SwitchRow("See the screen", "Screenshots, on-screen elements, current app", p.screen) { set(p.copy(screen = it)) }
                Hairline()
                SwitchRow("Touch and type", "Tap, long press, swipe, type text, keys", p.input) { set(p.copy(input = it)) }
                Hairline()
                SwitchRow("Open apps", "Launch apps and list installed apps", p.apps) { set(p.copy(apps = it)) }
                Hairline()
                SwitchRow("Install apps", "Install an APK from the computer", p.install) { set(p.copy(install = it)) }
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.Build, t.amber, "Shell commands")
                SectionGap()
                Segmented(
                    listOf(Policy.SHELL_OFF to "Off", Policy.SHELL_READONLY to "Read-only", Policy.SHELL_ANY to "Any"),
                    p.shell,
                ) { set(p.copy(shell = it)) }
                SectionGap(10.dp)
                when (p.shell) {
                    Policy.SHELL_OFF -> Body("The agent cannot run shell commands.")
                    Policy.SHELL_READONLY -> Body("Only commands that read: ls, cat, getprop, dumpsys, pm list, settings get, logcat -d and similar. No redirects or chaining.")
                    else -> Box(Modifier.fillMaxWidth().clip(RCtrl).background(t.warnBg).padding(12.dp)) {
                        Text(
                            "Any command means full control. A shell can tap, open this app and change settings, so it can get around every other rule here.",
                            color = t.warnFg, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.Lock, t.pink, "Apps")
                SectionGap()
                Segmented(
                    listOf(Policy.FILTER_OFF to "All apps", Policy.FILTER_BLOCKLIST to "Block list", Policy.FILTER_ALLOWLIST to "Allow list"),
                    p.appFilter,
                ) { set(p.copy(appFilter = it)) }
                SectionGap(10.dp)
                Body(
                    when (p.appFilter) {
                        Policy.FILTER_OFF -> "The agent may use any app. The list below is kept but not used."
                        Policy.FILTER_BLOCKLIST -> "The agent can't open, see or touch these apps."
                        else -> "The agent can only use these apps, plus the home screen and system bars."
                    }
                )
                SectionGap(8.dp)
                if (p.packages.isEmpty()) Caption("No apps in the list.")
                p.packages.forEach { pkg ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(appLabel(pkg), color = t.g900, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Caption(pkg)
                        }
                        Icon(
                            Icons.Filled.Close, "Remove", tint = t.g400,
                            modifier = Modifier.clip(RCtrl).clickable { set(p.copy(packages = p.packages - pkg)) }.padding(6.dp),
                        )
                    }
                    Hairline()
                }
                SectionGap()
                SecondaryButton("Add apps") { picker = true }
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.DateRange, t.sky, "Session")
                SectionGap()
                Body("End the session when the agent does nothing for")
                SectionGap(8.dp)
                Segmented(listOf(60 to "1m", 300 to "5m", 900 to "15m", 1800 to "30m", 3600 to "60m"), p.idleTimeoutSec) {
                    set(p.copy(idleTimeoutSec = it))
                }
            }
        }
        item { AdbLockCard(core) }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.Star, t.green, "Always on")
                SectionGap(8.dp)
                Body("The agent can never see, touch or open ADB Warden itself, so it can't approve its own requests or change these rules.")
            }
        }
        item { Spacer(Modifier.height(BottomBarSpace)) }
    }

    if (picker) AppPicker(p.packages, onDismiss = { picker = false }) { set(p.copy(packages = it)) }
}

@Composable
private fun appLabel(pkg: String): String {
    val ctx = LocalContext.current
    return remember(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }
}

private data class AppEntry(val label: String, val pkg: String)

@Composable
private fun AppPicker(current: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val t = LocalTokens.current
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(current.toSet()) }
    val apps by produceState(emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }
    val q = query.trim().lowercase()
    val shown = apps.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
    val looksLikePkg = Regex("^[a-zA-Z][\\w]*(\\.[\\w]+)+$").matches(query.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = t.card,
        shape = RCard,
        title = { Text("Choose apps", color = t.g900, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, shape = RCtrl, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search or type a package name") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.indigo, unfocusedBorderColor = t.g200),
                )
                if (looksLikePkg && apps.none { it.pkg == query.trim() }) {
                    SectionGap(8.dp)
                    SecondaryButton("Add ${query.trim()}") { selected = selected + query.trim(); query = "" }
                }
                SectionGap(8.dp)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown, key = { it.pkg }) { a ->
                        val on = a.pkg in selected
                        Row(
                            Modifier.fillMaxWidth().clip(RCtrl)
                                .clickable { selected = if (on) selected - a.pkg else selected + a.pkg }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Checkbox(on, { selected = if (on) selected - a.pkg else selected + a.pkg },
                                colors = CheckboxDefaults.colors(checkedColor = t.violet))
                            Column {
                                Text(a.label, color = t.g900, fontSize = 14.sp)
                                Caption(a.pkg)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ onDone(current.filter { it in selected } + (selected - current.toSet()).sorted()); onDismiss() }) {
                Text("Save", color = t.violet, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = t.g500) } },
    )
}


@Composable
private fun AdbLockCard(core: Core) {
    val t = LocalTokens.current
    val p by core.policy.collectAsState()
    val st by core.adbLock.state.collectAsState()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        core.adbLock.refresh()
        onPauseOrDispose { }
    }
    val paused = st.pausedUntil > System.currentTimeMillis()
    SoftCard(Overlap) {
        CardTitle(Icons.Filled.Lock, t.violet, "ADB lock") {
            StatusPill(
                when {
                    !p.adbLock -> "Off"
                    !st.hasPermission -> "Needs permission"
                    paused -> "Paused"
                    else -> "On"
                },
                when {
                    !p.adbLock -> Status.Neutral
                    !st.hasPermission || paused -> Status.Warn
                    else -> Status.Ok
                },
            )
        }
        SectionGap(8.dp)
        Body("Any computer paired with this phone can use adb directly and skip every rule here. With the lock on, debugging stays off until you tap Allow, and turns off again when the session ends.")
        SectionGap(8.dp)
        if (!st.hasPermission) {
            Box(Modifier.fillMaxWidth().clip(RCtrl).background(t.warnBg).padding(12.dp)) {
                Column {
                    Text("One-time setup. With the phone connected over adb, run on the computer:", color = t.warnFg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    SectionGap(6.dp)
                    Text(com.wadjinny.adbwarden.AdbLock.GRANT_COMMAND, color = t.g900, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.5.sp)
                }
            }
            SectionGap(8.dp)
        }
        SwitchRow("Debugging only during sessions", "Wireless debugging turns on when you tap Allow", p.adbLock && st.hasPermission) {
            if (!it || st.hasPermission) core.setPolicy(p.copy(adbLock = it))
        }
        if (p.adbLock && st.hasPermission) {
            Hairline()
            SwitchRow("Include USB debugging", "Also turns USB debugging off outside sessions", p.adbLockUsb) {
                core.setPolicy(p.copy(adbLockUsb = it))
            }
            Hairline()
            SectionGap(8.dp)
            Caption("Now: wireless debugging ${if (st.wifi) "on" else "off"} · USB debugging ${if (st.usb) "on" else "off"}")
            SectionGap(10.dp)
            if (paused) {
                Body("Paused until ${formatDateTime(st.pausedUntil)} so you can use adb yourself.")
                SectionGap(8.dp)
                PrimaryButton("Resume lock") { core.adbLock.resume() }
            } else {
                SecondaryButton("Pause for 1 hour") { core.adbLock.pause(com.wadjinny.adbwarden.AdbLock.PAUSE_MS) }
            }
        }
    }
}
