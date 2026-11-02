package com.wadjinny.adbwarden.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wadjinny.adbwarden.ActionRow
import com.wadjinny.adbwarden.BottomBarSpace
import com.wadjinny.adbwarden.ClientConn
import com.wadjinny.adbwarden.ConsentActivity
import com.wadjinny.adbwarden.Core
import com.wadjinny.adbwarden.Notifier
import com.wadjinny.adbwarden.PairingActivity
import com.wadjinny.adbwarden.SessionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(core: Core, onOpenSetup: () -> Unit) {
    val t = LocalTokens.current
    val ctx = LocalContext.current
    val running by core.serverRunning.collectAsState()
    val serverError by core.serverError.collectAsState()
    val conns by core.connections.collectAsState()
    val session by core.session.collectAsState()
    val consent by core.consent.collectAsState()
    val pairing by core.pairing.collectAsState()
    val computers by core.computers.collectAsState()
    val v by core.dbVersion.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    val feed by produceState<Pair<SessionRow?, List<ActionRow>>>(null to emptyList(), v, session?.id) {
        value = withContext(Dispatchers.IO) {
            val s = session?.id?.let { core.db.session(it) } ?: core.db.lastRunSession()
            s to (s?.let { core.db.actions(it.id).asReversed().take(150) } ?: emptyList())
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            GradientHeader("ADB Warden", ClientConn.deviceName()) {
                GlassTile(if (running) "Running" else "Stopped", "Server", dot = if (running) t.okFg else t.errFg)
                GlassTile("${conns.size}", if (conns.size == 1) "Computer online" else "Computers online")
                GlassTile(if (session != null) "In control" else "Idle", "Agent", dot = if (session != null) Color(0xFFC084FC) else null)
            }
        }
        consent?.let { p ->
            item {
                SoftCard(Overlap) {
                    CardTitle(Icons.Filled.Notifications, t.amber, "Control request waiting") { StatusPill("Waiting", Status.Warn) }
                    SectionGap()
                    Body("${p.agent} on ${p.computerName}: ${p.reason}")
                    SectionGap()
                    PrimaryButton("Review request") {
                        ctx.startActivity(Intent(ctx, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        pairing?.let { p ->
            item {
                SoftCard(Overlap) {
                    CardTitle(Icons.Filled.Refresh, t.sky, "Pairing code needed") { StatusPill("Waiting", Status.Warn) }
                    SectionGap()
                    Body("${p.computerName}: ${p.reason}")
                    SectionGap()
                    PrimaryButton("Enter pairing code") {
                        ctx.startActivity(Intent(ctx, PairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
        item {
            SoftCard(Overlap) {
                val s = session
                if (s != null) {
                    CardTitle(Icons.Filled.PlayArrow, t.violet, "Agent in control") { StatusPill("Live", Status.Progress) }
                    SectionGap()
                    Text(s.agent, color = t.g900, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Body("on ${s.computerName}")
                    SectionGap(6.dp)
                    Body("“${s.reason}”", color = t.g700)
                    SectionGap()
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Stat(Notifier.formatDuration(now - s.startedAt), "Elapsed")
                        Stat("${s.actions}", "Actions")
                        Stat("${s.denied}", "Denied")
                    }
                    SectionGap()
                    DangerButton("Stop now", Modifier.fillMaxWidth()) { core.stopSession() }
                } else {
                    CardTitle(Icons.Filled.PlayArrow, t.violet, "No agent in control")
                    SectionGap(8.dp)
                    Body("When an agent asks to control the phone you will be asked first. A border and a pill show while it works.")
                }
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.Phone, t.green, "Computers")
                SectionGap(8.dp)
                if (!running && serverError != null) {
                    Body("Server error: $serverError", color = t.errFg); SectionGap(8.dp)
                }
                if (conns.isEmpty()) {
                    Body(if (computers.isEmpty()) "No computer linked yet." else "No linked computer is connected right now.")
                    if (computers.isEmpty()) {
                        SectionGap(); SecondaryButton("Link a computer", onClick = onOpenSetup)
                    }
                } else conns.forEachIndexed { i, c ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.computerName, color = t.g900, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Caption("${c.ip} · since ${formatTime(c.since)}")
                        }
                        StatusPill("Online", Status.Ok)
                    }
                }
            }
        }
        item {
            SoftCard(Overlap) {
                val (fs, acts) = feed
                CardTitle(Icons.Filled.Info, t.pink, if (session != null) "Live activity" else "Last session")
                SectionGap(8.dp)
                if (fs == null) Body("Nothing yet.")
                else {
                    if (session == null) {
                        Caption("${fs.agent} · ${formatDateTime(fs.started)} · ${Core.reasonLabel(fs.endReason)}")
                        SectionGap(6.dp)
                    }
                    if (acts.isEmpty()) Body("No actions yet.")
                    acts.forEachIndexed { i, a -> if (i > 0) Hairline(); ActionLine(a) }
                }
            }
        }
        item { Spacer(Modifier.height(BottomBarSpace)) }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val t = LocalTokens.current
    Column {
        Text(value, color = t.g900, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
        Caption(label)
    }
}

@Composable
fun ActionLine(a: ActionRow, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatTime(a.ts), color = t.g400, fontSize = 11.5.sp, modifier = Modifier.width(62.dp))
        Column(Modifier.weight(1f)) {
            Text(
                a.summary, fontSize = 13.5.sp, maxLines = 2,
                color = if (a.status == "denied") t.errFg else t.g900,
                fontWeight = if (a.status == "denied") FontWeight.SemiBold else FontWeight.Normal,
            )
            if (!a.detail.isNullOrBlank()) Caption(a.detail.take(160))
        }
        when (a.status) {
            "denied" -> StatusPill("Denied", Status.Err)
            "error" -> StatusPill("Error", Status.Warn)
            else -> a.durationMs?.let { Caption("${it} ms") }
        }
    }
}
