package com.wadjinny.adbwarden

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wadjinny.adbwarden.ui.Body
import com.wadjinny.adbwarden.ui.Caption
import com.wadjinny.adbwarden.ui.LocalTokens
import com.wadjinny.adbwarden.ui.PrimaryButton
import com.wadjinny.adbwarden.ui.RCard
import com.wadjinny.adbwarden.ui.RCtrl
import com.wadjinny.adbwarden.ui.SecondaryButton
import com.wadjinny.adbwarden.ui.SectionGap
import com.wadjinny.adbwarden.ui.WardenTheme

/** Collects the wireless-debugging pairing code for the computer. */
class PairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = WardenApp.core(this)
        setContent {
            WardenTheme {
                val t = LocalTokens.current
                val p by core.pairing.collectAsState()
                var code by remember { mutableStateOf("") }
                var port by remember { mutableStateOf("") }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(p) { if (p == null) finish() }
                val req = p ?: return@WardenTheme
                val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.indigo, unfocusedBorderColor = t.g200)

                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).imePadding(), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(20.dp).fillMaxWidth().clip(RCard).background(t.card).verticalScroll(rememberScrollState())) {
                        Column(
                            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(t.headerStart, t.headerEnd))).padding(22.dp)
                        ) {
                            Text("Pairing code", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                            Text("${req.computerName} needs to pair with this phone", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                        }
                        Column(Modifier.padding(22.dp)) {
                            Body(req.reason, color = t.g900)
                            SectionGap()
                            Step(1, "Open Developer options → Wireless debugging and turn it on.")
                            Step(2, "Tap “Pair device with pairing code”.")
                            Step(3, "Type the 6-digit code into the notification (pull down the shade) so the pairing window stays open. Or come back here and type it below.")
                            SectionGap(8.dp)
                            SecondaryButton("Open Developer options") {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            SectionGap(16.dp)
                            OutlinedTextField(
                                code, { code = it.filter(Char::isDigit).take(6); error = null },
                                label = { Text("Pairing code") }, singleLine = true, shape = RCtrl, colors = fieldColors,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SectionGap(8.dp)
                            OutlinedTextField(
                                port, { port = it.filter(Char::isDigit).take(5) },
                                label = { Text("Port (optional)") }, singleLine = true, shape = RCtrl, colors = fieldColors,
                                supportingText = { Text("The number after the colon under the code, e.g. 192.168.1.5:37155") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            error?.let { Text(it, color = t.errFg, fontSize = 13.sp) }
                            SectionGap()
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SecondaryButton("Cancel", Modifier.weight(1f)) { core.cancelPairing() }
                                PrimaryButton("Send code", Modifier.weight(1f), enabled = code.length == 6) {
                                    val pt = port.toIntOrNull()?.takeIf { it in 1..65535 }
                                    if (!core.submitPairing(code, pt)) error = "The code must be 6 digits"
                                }
                            }
                            SectionGap(8.dp)
                            Caption("The code goes only to the linked computer, never to the agent.")
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Step(n: Int, text: String) {
    val t = LocalTokens.current
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$n", color = t.violet, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(end = 10.dp))
        Body(text, color = t.g700)
    }
}
