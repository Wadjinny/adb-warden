package com.wadjinny.adbwarden

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wadjinny.adbwarden.ui.Body
import com.wadjinny.adbwarden.ui.Caption
import com.wadjinny.adbwarden.ui.DangerButton
import com.wadjinny.adbwarden.ui.LocalTokens
import com.wadjinny.adbwarden.ui.PrimaryButton
import com.wadjinny.adbwarden.ui.RCard
import com.wadjinny.adbwarden.ui.RPill
import com.wadjinny.adbwarden.ui.SectionGap
import com.wadjinny.adbwarden.ui.WardenTheme
import com.wadjinny.adbwarden.ui.gradient
import kotlinx.coroutines.delay

/** Full-screen consent prompt shown when an agent asks to control the phone. */
class ConsentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val core = WardenApp.core(this)
        setContent {
            WardenTheme {
                val t = LocalTokens.current
                val p by core.consent.collectAsState()
                val policy by core.policy.collectAsState()
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(250); now = System.currentTimeMillis() } }
                LaunchedEffect(p) { if (p == null) finish() }
                val req = p ?: return@WardenTheme
                val left = ((req.createdAt + Core.CONSENT_TIMEOUT_MS - now) / 1000).coerceAtLeast(0)
                val frac = (left / (Core.CONSENT_TIMEOUT_MS / 1000f)).coerceIn(0f, 1f)

                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(20.dp).fillMaxWidth().clip(RCard).background(t.card)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(
                            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(t.headerStart, t.headerEnd)))
                                .padding(22.dp)
                        ) {
                            Text("Allow control?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                            Text("${req.agent} on ${req.computerName}", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                        }
                        Column(Modifier.padding(22.dp)) {
                            Caption("Why")
                            Text(req.reason, color = t.g900, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            SectionGap(16.dp)
                            Caption("With your current rules it can")
                            SectionGap(6.dp)
                            policy.summary().forEach { (ok, text) ->
                                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (ok) Icons.Filled.Check else Icons.Filled.Close, null,
                                        tint = if (ok) t.okFg else t.errFg, modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Body(if (ok) text else "Not: ${text.replaceFirstChar { it.lowercase() }}", color = t.g700)
                                }
                            }
                            SectionGap(16.dp)
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RPill).background(t.g100)) {
                                Box(Modifier.fillMaxWidth(frac).height(6.dp).clip(RPill).background(gradient(t)))
                            }
                            SectionGap(6.dp)
                            Caption("Denied automatically in $left s. You can press Stop at any time.")
                            SectionGap(18.dp)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                DangerButton("Deny", Modifier.weight(1f)) { core.decideConsent(req.key, false) }
                                PrimaryButton("Allow", Modifier.weight(1f)) { allow(core, req.key) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun allow(core: Core, key: String) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            // Require unlocking before an agent gets control.
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { core.decideConsent(key, true) }
            })
        } else core.decideConsent(key, true)
    }
}
