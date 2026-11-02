package com.wadjinny.adbwarden.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wadjinny.adbwarden.BottomBarSpace
import com.wadjinny.adbwarden.Core
import com.wadjinny.adbwarden.LogRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LogsScreen(core: Core) {
    val t = LocalTokens.current
    val v by core.dbVersion.collectAsState()
    val logs by produceState(emptyList<LogRow>(), v) { value = withContext(Dispatchers.IO) { core.db.logs(500) } }
    var filter by remember { mutableStateOf("all") }
    var confirmClear by remember { mutableStateOf(false) }
    val export = rememberJsonExporter { core.db.exportLogs() }
    val shown = when (filter) {
        "problems" -> logs.filter { it.level != "info" }
        else -> logs
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            GradientHeader("Logs", "Connections, linking, pairing and service events") {
                GlassTile("${logs.size}", "Recent entries")
                GlassTile("${logs.count { it.level == "warn" }}", "Warnings")
                GlassTile("${logs.count { it.level == "error" }}", "Errors")
            }
        }
        item {
            SoftCard(Overlap) {
                CardTitle(Icons.AutoMirrored.Filled.List, t.indigo, "Events")
                SectionGap()
                Segmented(listOf("all" to "All", "problems" to "Warnings & errors"), filter) { filter = it }
                SectionGap()
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Export JSON", enabled = logs.isNotEmpty()) { export("adb-warden-logs-${stamp()}.json") }
                    if (logs.isNotEmpty()) DangerButton("Clear") { confirmClear = true }
                }
                SectionGap(8.dp)
                if (shown.isEmpty()) Body("Nothing to show.")
                shown.forEachIndexed { i, l ->
                    if (i > 0) Hairline()
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                        val c = when (l.level) { "error" -> t.errFg; "warn" -> t.warnFg; else -> t.okFg }
                        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(c))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(l.message, color = t.g900, fontSize = 13.sp)
                            Text("${formatDateTime(l.ts)}:${formatTime(l.ts).takeLast(2)} · ${l.level}", color = t.g400, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(BottomBarSpace)) }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        containerColor = t.card, shape = RCard,
        title = { Text("Clear logs?", color = t.g900, fontWeight = FontWeight.Bold) },
        text = { Body("This deletes every log entry on this phone.") },
        confirmButton = { TextButton({ core.db.clearLogs(); core.log("info", "Logs cleared"); confirmClear = false }) { Text("Clear", color = t.errFg, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel", color = t.g500) } },
    )
}
