package com.wadjinny.adbwarden.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wadjinny.adbwarden.ActionRow
import com.wadjinny.adbwarden.BottomBarSpace
import com.wadjinny.adbwarden.Core
import com.wadjinny.adbwarden.Notifier
import com.wadjinny.adbwarden.SessionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(core: Core) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val sel = selected
    if (sel != null) {
        BackHandler { selected = null }
        SessionDetail(core, sel) { selected = null }
    } else SessionList(core) { selected = it }
}

@Composable
private fun SessionList(core: Core, onOpen: (String) -> Unit) {
    val t = LocalTokens.current
    val v by core.dbVersion.collectAsState()
    val sessions by produceState(emptyList<SessionRow>(), v) { value = withContext(Dispatchers.IO) { core.db.sessions() } }
    var confirmClear by remember { mutableStateOf(false) }
    val export = rememberJsonExporter { core.db.exportHistory() }
    val approved = sessions.count { it.status == "ended" || it.status == "active" }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            GradientHeader("History", "Every request and session, kept for 30 days") {
                GlassTile("$approved", "Sessions")
                GlassTile("${sessions.count { it.status == "denied" || it.status == "timeout" }}", "Refused")
                GlassTile("${sessions.sumOf { it.actions }}", "Actions")
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.DateRange, t.sky, "Sessions")
                SectionGap()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Export JSON", enabled = sessions.isNotEmpty()) { export("adb-warden-history-${stamp()}.json") }
                    if (sessions.isNotEmpty()) DangerButton("Clear") { confirmClear = true }
                }
                if (sessions.isEmpty()) { SectionGap(); Body("No sessions yet.") }
            }
        }
        items(sessions, key = { it.id }) { s ->
            SoftCard(Overlap, onClick = { onOpen(s.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.agent, color = t.g900, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Caption("${s.computerName} · ${formatDateTime(s.started)}")
                    }
                    SessionStatusPill(s)
                }
                SectionGap(8.dp)
                Body(s.reason, maxLines = 2)
                if (s.status == "ended" || s.status == "active") {
                    SectionGap(8.dp)
                    Caption(
                        listOfNotNull(
                            s.ended?.let { Notifier.formatDuration(it - s.started) },
                            "${s.actions} actions",
                            if (s.denied > 0) "${s.denied} denied" else null,
                        ).joinToString(" · ")
                    )
                }
            }
        }
        item { Spacer(Modifier.height(BottomBarSpace)) }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = t.card, shape = RCard,
        title = { Text("Clear history?", color = t.g900, fontWeight = FontWeight.Bold) },
        text = { Body("This deletes every finished session and its actions from this phone. The log on the computer is not affected.") },
        confirmButton = { TextButton({ core.db.clearHistory(); core.log("info", "History cleared"); confirmClear = false }) { Text("Clear", color = t.errFg, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel", color = t.g500) } },
    )
}

@Composable
fun SessionStatusPill(s: SessionRow) = when (s.status) {
    "active" -> StatusPill("Live", Status.Progress)
    "ended" -> when (s.endReason) {
        "stopped_by_user" -> StatusPill("Stopped", Status.Warn)
        "agent_ended" -> StatusPill("Done", Status.Ok)
        else -> StatusPill(Core.reasonLabel(s.endReason).replaceFirstChar { it.uppercase() }, Status.Neutral)
    }
    "denied" -> StatusPill("Denied", Status.Err)
    "timeout" -> StatusPill("No answer", Status.Neutral)
    else -> StatusPill(s.status.replaceFirstChar { it.uppercase() }, Status.Neutral)
}

@Composable
private fun SessionDetail(core: Core, id: String, onBack: () -> Unit) {
    val t = LocalTokens.current
    val v by core.dbVersion.collectAsState()
    val data by produceState<Pair<SessionRow?, List<ActionRow>>>(null to emptyList(), v, id) {
        value = withContext(Dispatchers.IO) { core.db.session(id) to core.db.actions(id) }
    }
    val (s, acts) = data
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            GradientHeader(s?.agent ?: "Session", s?.let { "${it.computerName} · ${formatDateTime(it.started)}" }) {
                GlassTile(s?.ended?.let { Notifier.formatDuration(it - s.started) } ?: "—", "Duration")
                GlassTile("${s?.actions ?: 0}", "Actions")
                GlassTile("${s?.denied ?: 0}", "Denied")
            }
        }
        item {
            SoftCard(Overlap) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = t.violet,
                        modifier = Modifier.clip(RCtrl).clickable(onClick = onBack).padding(6.dp),
                    )
                    Text("All sessions", color = t.violet, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onBack))
                    Spacer(Modifier.weight(1f))
                    s?.let { SessionStatusPill(it) }
                }
                if (s != null) {
                    SectionGap()
                    Caption("Reason given")
                    Body(s.reason, color = t.g900)
                    SectionGap(8.dp)
                    Caption("Outcome")
                    Body(
                        listOfNotNull(
                            Core.reasonLabel(s.endReason ?: s.status).replaceFirstChar { it.uppercase() },
                            s.ended?.let { "at ${formatTime(it)}" },
                        ).joinToString(" ")
                    )
                    if (!s.summary.isNullOrBlank()) {
                        SectionGap(8.dp); Caption("Agent's summary"); Body(s.summary, color = t.g900)
                    }
                }
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.Filled.Info, t.pink, "Actions")
                SectionGap(8.dp)
                if (acts.isEmpty()) Body("No actions were taken.")
                acts.forEachIndexed { i, a -> if (i > 0) Hairline(); ActionLine(a) }
            }
        }
        item { Spacer(Modifier.height(BottomBarSpace)) }
    }
}

