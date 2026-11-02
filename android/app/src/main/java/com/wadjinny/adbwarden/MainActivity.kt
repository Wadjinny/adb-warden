package com.wadjinny.adbwarden

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wadjinny.adbwarden.ui.HistoryScreen
import com.wadjinny.adbwarden.ui.HomeScreen
import com.wadjinny.adbwarden.ui.LocalTokens
import com.wadjinny.adbwarden.ui.LogsScreen
import com.wadjinny.adbwarden.ui.RPill
import com.wadjinny.adbwarden.ui.RulesScreen
import com.wadjinny.adbwarden.ui.SetupScreen
import com.wadjinny.adbwarden.ui.WardenTheme
import com.wadjinny.adbwarden.ui.gradient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val core = WardenApp.core(this)
        WardenService.start(this)
        setContent {
            WardenTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val t = LocalTokens.current
                Box(Modifier.fillMaxSize().background(t.bg)) {
                    Box(Modifier.fillMaxSize()) {
                        when (tab) {
                            0 -> HomeScreen(core, onOpenSetup = { tab = 4 })
                            1 -> RulesScreen(core)
                            2 -> HistoryScreen(core)
                            3 -> LogsScreen(core)
                            else -> SetupScreen(core)
                        }
                    }
                    BottomBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("Home", Icons.Filled.Home),
    TabItem("Rules", Icons.Filled.Lock),
    TabItem("History", Icons.Filled.DateRange),
    TabItem("Logs", Icons.AutoMirrored.Filled.List),
    TabItem("Setup", Icons.Filled.Settings),
)

/** Height reserved at the bottom of every screen so content can scroll above the floating bar. */
val BottomBarSpace = 110.dp

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(24.dp)
    var m = modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth()
    if (!t.dark) m = m.shadow(16.dp, shape, ambientColor = t.indigo.copy(alpha = .12f), spotColor = t.indigo.copy(alpha = .18f))
    Row(
        m.clip(shape).background(t.card).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEachIndexed { i, item ->
            val sel = i == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onSelect(i) }.padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RPill).then(if (sel) Modifier.background(gradient(t)) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    Icon(item.icon, item.label, tint = if (sel) Color.White else t.g400, modifier = Modifier.size(20.dp))
                }
                Text(
                    item.label, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    color = if (sel) t.violet else t.g400, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
