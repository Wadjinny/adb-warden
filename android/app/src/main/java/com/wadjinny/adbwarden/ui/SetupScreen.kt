package com.wadjinny.adbwarden.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.wadjinny.adbwarden.BottomBarSpace
import com.wadjinny.adbwarden.Core
import com.wadjinny.adbwarden.LinkedComputer
import com.wadjinny.adbwarden.Net
import com.wadjinny.adbwarden.WardenService
import kotlinx.coroutines.delay

@Composable
fun SetupScreen(core: Core) {
    val t = LocalTokens.current
    val ctx = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val notif = remember(resumeTick) { hasNotif(ctx) }
    val overlay = remember(resumeTick) { Settings.canDrawOverlays(ctx) }
    val battery = remember(resumeTick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumeTick++ }
    val link by core.link.collectAsState()
    val computers by core.computers.collectAsState()
    val conns by core.connections.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(link) {
        while (link != null) {
            now = System.currentTimeMillis()
            if (link!!.expiresAt < now) core.cancelLink()
            delay(500)
        }
    }
    var removing by remember { mutableStateOf<LinkedComputer?>(null) }
    var portText by remember { mutableStateOf(core.prefs.port.toString()) }
    var startOnBoot by remember { mutableStateOf(core.prefs.startOnBoot) }
    val ips = remember(resumeTick, link) { Net.ipv4Addresses() }
    val ready = listOf(notif, overlay, battery).count { it }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            GradientHeader("Setup", "Permissions, linked computers and the server") {
                GlassTile("$ready of 3", "Permissions", dot = if (ready == 3) t.okFg else t.warnFg)
                GlassTile("${computers.size}", "Linked computers")
                GlassTile(ips.firstOrNull() ?: "No Wi-Fi", "Phone address")
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.CheckCircle, t.green, "Permissions")
                SectionGap(4.dp)
                PermRow("Notifications", "Control requests, pairing code entry and the done summary", notif) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }
                Hairline()
                PermRow("Display over other apps", "Shows the border and Stop pill, and opens the consent prompt", overlay) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                Hairline()
                PermRow("No battery limits", "Keeps the server running in the background", battery) {
                    @SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    ctx.startActivity(i)
                }
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.Share, t.violet, "Link a computer")
                SectionGap(8.dp)
                val lm = link
                if (lm == null) {
                    Body("Install the proxy on your computer, then link it once. It finds this phone on the same Wi-Fi.")
                    SectionGap()
                    PrimaryButton("Link a computer", Modifier.fillMaxWidth()) { core.startLink() }
                } else {
                    Body("On the computer, run:")
                    SectionGap(8.dp)
                    val cmd = "npx adb-warden link ${lm.code}"
                    Box(
                        Modifier.fillMaxWidth().clip(RCtrl).background(t.g100).clickable { copy(ctx, cmd) }.padding(14.dp)
                    ) { Text(cmd, color = t.g900, fontFamily = FontFamily.Monospace, fontSize = 13.5.sp) }
                    SectionGap()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Caption("Link code")
                            Text(lm.code.chunked(3).joinToString(" "), color = t.g900, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        }
                        StatusPill("${((lm.expiresAt - now) / 1000).coerceAtLeast(0)} s left", Status.Progress)
                    }
                    SectionGap(8.dp)
                    Caption(
                        if (ips.isEmpty()) "No network address — connect to Wi-Fi."
                        else "If the computer can't find the phone: npx adb-warden link ${lm.code} ${ips.first()}:${core.prefs.port}"
                    )
                    SectionGap()
                    SecondaryButton("Cancel") { core.cancelLink() }
                }
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.Phone, t.sky, "Linked computers")
                SectionGap(4.dp)
                if (computers.isEmpty()) { SectionGap(4.dp); Body("None yet.") }
                computers.forEachIndexed { i, c ->
                    if (i > 0) Hairline()
                    val online = conns.any { it.computerId == c.id }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = t.g900, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Caption("Linked ${formatDateTime(c.linkedAt)} · last seen ${formatDateTime(c.lastSeen)}")
                        }
                        if (online) { StatusPill("Online", Status.Ok); Spacer(Modifier.padding(4.dp)) }
                        DangerButton("Remove") { removing = c }
                    }
                }
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.Settings, t.amber, "Server")
                SectionGap()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        portText, { portText = it.filter(Char::isDigit).take(5) }, singleLine = true, shape = RCtrl,
                        label = { Text("Port") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.indigo, unfocusedBorderColor = t.g200),
                    )
                    val p = portText.toIntOrNull()
                    SecondaryButton("Apply", enabled = p != null && p in 1024..65535 && p != core.prefs.port) {
                        core.prefs.port = p!!
                        core.log("info", "Server port changed to $p")
                        WardenService.restart(ctx)
                        Toast.makeText(ctx, "Server restarted on port $p. Link the computer again if it used the old port.", Toast.LENGTH_LONG).show()
                    }
                }
                SectionGap(4.dp)
                SwitchRow("Start when the phone starts", null, startOnBoot) { startOnBoot = it; core.prefs.startOnBoot = it }
                Hairline()
                SectionGap(8.dp)
                SecondaryButton("Restart server") { WardenService.restart(ctx) }
            }
        }
        item { Spacer(Modifier.height(BottomBarSpace)) }
    }

    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = t.card, shape = RCard,
            title = { Text("Remove ${c.name}?", color = t.g900, fontWeight = FontWeight.Bold) },
            text = { Body("Its key stops working right away. To use it again, link it again.") },
            confirmButton = { TextButton({ core.removeComputer(c.id); removing = null }) { Text("Remove", color = t.errFg, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel", color = t.g500) } },
        )
    }
}

@Composable
private fun PermRow(title: String, subtitle: String, granted: Boolean, onGrant: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = t.g900, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = t.g500, fontSize = 12.5.sp)
        }
        Spacer(Modifier.padding(4.dp))
        if (granted) StatusPill("Ready", Status.Ok) else SecondaryButton("Allow", onClick = onGrant)
    }
}

private fun hasNotif(ctx: Context) = com.wadjinny.adbwarden.Notifier.canPost(ctx)

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("command", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
