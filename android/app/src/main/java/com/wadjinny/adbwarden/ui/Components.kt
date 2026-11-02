package com.wadjinny.adbwarden.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val RCard = RoundedCornerShape(20.dp)
val RCtrl = RoundedCornerShape(14.dp)
val RPill = RoundedCornerShape(999.dp)
val RGlass = RoundedCornerShape(16.dp)

fun gradient(t: Tokens) = Brush.horizontalGradient(listOf(t.gradStart, t.gradEnd))

/** Gradient band with white title, decorative circles and optional glass tiles. Covers the status bar. */
@Composable
fun GradientHeader(title: String, subtitle: String? = null, tiles: (@Composable RowScope.() -> Unit)? = null) {
    val t = LocalTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(t.headerStart, t.headerEnd)))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.10f), radius = size.width * 0.32f, center = Offset(size.width * 0.95f, size.height * 0.05f))
                drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.22f, center = Offset(size.width * 0.78f, size.height * 1.0f))
            }
            .statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 46.dp)
    ) {
        Column {
            Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            if (tiles != null) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = tiles)
            }
        }
    }
}

@Composable
fun RowScope.GlassTile(value: String, caption: String, dot: Color? = null) {
    Column(
        Modifier
            .weight(1f)
            .clip(RGlass)
            .background(Color.White.copy(alpha = 0.16f))
            .border(1.dp, Color.White.copy(alpha = 0.22f), RGlass)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dot != null) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp))
            }
            Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(caption, color = Color.White.copy(alpha = 0.85f), fontSize = 11.5.sp, maxLines = 1)
    }
}

/** Content below the header is pulled up to overlap the band. */
val Overlap = Modifier.offset(y = (-26).dp)

@Composable
fun SoftCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    var m = modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
    if (!t.dark) m = m.shadow(14.dp, RCard, ambientColor = t.indigo.copy(alpha = 0.10f), spotColor = t.indigo.copy(alpha = 0.16f))
    m = m.clip(RCard).background(t.card)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(20.dp), content = content)
}

@Composable
fun CardTitle(icon: ImageVector, accent: Color, title: String, trailing: (@Composable () -> Unit)? = null) {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(34.dp).clip(RCtrl).background(accent.copy(alpha = if (t.dark) 0.22f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = accent, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(title, color = t.g900, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalTokens.current
    var m = modifier
    if (enabled && !t.dark) m = m.shadow(10.dp, RPill, ambientColor = t.violet.copy(alpha = .28f), spotColor = t.violet.copy(alpha = .28f))
    m = m.clip(RPill)
    m = if (enabled) m.background(gradient(t)).clickable(onClick = onClick) else m.background(t.g200)
    Box(m.padding(horizontal = 22.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color.White else t.g400, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalTokens.current
    var m = modifier.clip(RPill).background(if (enabled) t.violetSoft else t.g100)
    if (enabled) m = m.clickable(onClick = onClick)
    Box(m.padding(horizontal = 18.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) t.violetText else t.g300, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
    }
}

/** Rose tinted button for blocking actions (Stop, Deny, Remove). */
@Composable
fun DangerButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(
        modifier.clip(RPill).background(t.errBg).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = t.errFg, fontWeight = FontWeight.Bold, fontSize = 13.5.sp) }
}

enum class Status { Ok, Warn, Err, Progress, Neutral }

@Composable
fun StatusPill(text: String, status: Status) {
    val t = LocalTokens.current
    val (bg, fg) = when (status) {
        Status.Ok -> t.okBg to t.okFg
        Status.Warn -> t.warnBg to t.warnFg
        Status.Err -> t.errBg to t.errFg
        Status.Progress -> t.indigoSoft to t.indigo
        Status.Neutral -> t.g100 to t.g500
    }
    Text(
        text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RPill).background(bg).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
fun Hairline() = HorizontalDivider(color = LocalTokens.current.g100, thickness = 1.dp)

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = t.g900, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = t.g500, fontSize = 12.5.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = t.violet, checkedThumbColor = Color.White,
                uncheckedTrackColor = t.g100, uncheckedThumbColor = t.g400, uncheckedBorderColor = t.g200,
            ),
        )
    }
}

/** Pill segmented choice. Selected = brand gradient. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val t = LocalTokens.current
    Row(
        Modifier.fillMaxWidth().clip(RPill).background(t.g100).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val sel = value == selected
            Box(
                Modifier.weight(1f).clip(RPill)
                    .then(if (sel) Modifier.background(gradient(t)) else Modifier)
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) Color.White else t.g500, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, color = LocalTokens.current.g400, fontSize = 12.sp, modifier = modifier)

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color? = null, maxLines: Int = Int.MAX_VALUE) =
    Text(text, color = color ?: LocalTokens.current.g500, fontSize = 13.5.sp, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun SectionGap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

fun formatTime(ts: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(ts))
fun formatDateTime(ts: Long): String = java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
