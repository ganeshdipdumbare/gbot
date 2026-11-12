package dev.gbot.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gbot.app.GbotApp
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Presence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = clickable(enabled = enabled, role = Role.Button, onClick = onClick)

/**
 * Press-to-shrink, like the web's whileTap.
 *
 * Both branches go through [combinedClickable] so the press reaches [src]
 * and the shrink actually plays. A hand-rolled detectTapGestures does not
 * emit press interactions, which left every long-pressable row -- the whole
 * roster -- flat on touch and silent to TalkBack.
 */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onLong: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, label = "press")
    val haptic = LocalHapticFeedback.current
    return this.scale(s).combinedClickable(
        interactionSource = src,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onLongClick = onLong?.let { f -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); f() } },
        onClick = onClick,
    )
}

enum class Variant { Primary, Soft, Ghost, Danger }

@Composable
fun GButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, variant: Variant = Variant.Primary, enabled: Boolean = true, icon: String? = null, small: Boolean = false) {
    val g = LocalG.current
    val (bg, fg) = when (variant) {
        Variant.Primary -> g.emphasis to g.onEmphasis
        Variant.Soft -> g.surface to g.ink
        Variant.Ghost -> Color.Transparent to g.ink2
        Variant.Danger -> g.danger.copy(alpha = 0.1f) to g.danger
    }
    Row(
        modifier.heightIn(min = if (small) 32.dp else 42.dp).clip(RoundedCornerShape(50)).background(bg)
            .then(if (variant == Variant.Soft) Modifier.border(1.dp, g.line, RoundedCornerShape(50)) else Modifier)
            .pressable(enabled, onClick = onClick).padding(horizontal = if (small) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        val c = if (enabled) fg else fg.copy(alpha = 0.4f)
        if (icon != null) { Icon(icon, size = if (small) 14.dp else 16.dp, color = c); Spacer(Modifier.width(6.dp)) }
        Text(text, color = c, fontSize = if (small) 13.sp else 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun IconBtn(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = LocalG.current.ink2, filled: Boolean = false, size: Dp = 40.dp, enabled: Boolean = true) {
    val g = LocalG.current
    Box(
        modifier.size(size).clip(CircleShape).background(if (filled) g.surface2 else Color.Transparent)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, size = if (size < 36.dp) 17.dp else 19.dp, color = if (enabled) tint else tint.copy(alpha = 0.35f)) }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "",
    singleLine: Boolean = true, minLines: Int = 1, password: Boolean = false, mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text, ime: ImeAction = ImeAction.Default, onDone: (() -> Unit)? = null, enabled: Boolean = true,
) {
    val g = LocalG.current
    var show by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val style = TextStyle(color = g.ink, fontSize = 15.sp, lineHeight = 21.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    BasicTextField(
        value, onChange, enabled = enabled, singleLine = singleLine, minLines = minLines, textStyle = style,
        cursorBrush = SolidColor(g.live),
        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ime, autoCorrectEnabled = !password && !mono && keyboard == KeyboardType.Text),
        keyboardActions = KeyboardActions(onAny = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth(),
        interactionSource = src,
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(RoundedCornerShape(12.dp)).background(g.surface)
                    .border(1.dp, if (focused) g.ink3 else g.line, RoundedCornerShape(12.dp))
                    .padding(start = 12.dp, end = if (password) 4.dp else 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = style.copy(color = g.ink3))
                    inner()
                }
                if (password) Box(Modifier.size(28.dp).clip(CircleShape).clickable { show = !show }, contentAlignment = Alignment.Center) {
                    Icon(if (show) "eyeOff" else "eye", size = 18.dp, color = g.ink2)
                }
            }
        },
    )
}

@Composable
fun Label(text: String, hint: String? = null) {
    val g = LocalG.current
    Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, style = T.label, color = g.ink)
        if (hint != null) Text("  $hint", style = T.tiny, color = g.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val g = LocalG.current
    Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = g.live, checkedThumbColor = Color.White, uncheckedTrackColor = g.line, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
}

@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalG.current
    Box(
        modifier.height(30.dp).clip(RoundedCornerShape(50)).background(if (on) g.emphasis else g.surface)
            .border(1.dp, if (on) Color.Transparent else g.line, RoundedCornerShape(50)).pressable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) g.onEmphasis else g.ink2, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalG.current
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, g.line, RoundedCornerShape(16.dp)).padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 2.dp, top = 10.dp), style = T.small.copy(fontWeight = FontWeight.SemiBold), color = LocalG.current.ink2)
}

@Composable
fun ErrorText(text: String?) { if (!text.isNullOrBlank()) Text(text, style = T.small, color = LocalG.current.danger) }

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, lead: (@Composable () -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val g = LocalG.current
    Column(Modifier.fillMaxWidth().background(g.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconBtn("back", "Back", onBack, tint = g.ink)
            else Spacer(Modifier.width(12.dp))
            if (lead != null) { lead(); Spacer(Modifier.width(8.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = T.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-0.2).sp), color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = T.tiny, color = g.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(g.line.copy(alpha = g.line.alpha * 0.7f)))
    }
}

/**
 * The gbot mark: the character from the app icon, white on black. Tap it
 * and it hops and smiles; at rest it blinks and its eyes wander.
 */
@Composable
fun GbotLogo(size: Int = 22, onClick: (() -> Unit)? = null) {
    var happy by remember { mutableStateOf(false) }
    LaunchedEffect(happy) {
        if (happy) { delay(900); happy = false }
    }
    Box(Modifier.size(44.dp).pressable { happy = true; onClick?.invoke() }.semantics { contentDescription = "gbot" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            Gbot(
                dev.gbot.app.net.Look(shape = "circle", hue = "white", eyes = "pill"),
                "gbot", size, mood = if (happy) "happy" else "idle", lively = true,
            )
        }
    }
}

/** A titled screen with a scrolling body that stays clear of the keyboard. */
@Composable
fun Screen(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val g = LocalG.current
    Column(Modifier.fillMaxSize().background(g.bg).imePadding()) {
        TopBar(title, onBack, subtitle, actions = actions)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp), content = content,
        )
        if (footer != null) Column(Modifier.fillMaxWidth().background(g.bg)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(g.line.copy(alpha = g.line.alpha * 0.7f)))
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) { footer() }
        }
        else Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
fun Empty(text: String, seed: String = "empty-int") {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Gbot(dev.gbot.app.net.Look(shape = "cloud", hue = "cream", eyes = "dot"), seed, 52, mood = "sleepy")
        Spacer(Modifier.height(8.dp))
        Text(text, style = T.small, color = LocalG.current.ink2)
    }
}

@Composable
fun Confirm(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    val g = LocalG.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = T.h2) },
        text = { Text(text, style = T.body, color = g.ink2) },
        confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text(confirm, color = if (danger) g.danger else g.ink, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = g.ink2) } },
        containerColor = if (g.dark) Color(0xFF2A2A2A) else Color.White,
    )
}

/** Runs a server call; shows a toast on success (if given) and on failure. */
fun CoroutineScope.attempt(ok: String? = null, fail: String? = null, block: suspend () -> Unit) = launch {
    try { block(); if (ok != null) GbotApp.instance.toast(ok) }
    catch (e: Exception) { GbotApp.instance.toast(e.message ?: fail ?: "Something went wrong", error = true) }
}

// Presence and time, as the web shows them.
val presenceText = mapOf("idle" to "", "thinking" to "Thinking…", "working" to "Working…", "waiting" to "Needs you", "blocked" to "Stuck", "done" to "Done!")

fun moodOf(p: Presence?, bot: Bot? = null, nowMs: Long = System.currentTimeMillis()): String {
    val s = p?.state ?: "idle"
    if (s != "idle") return s
    val last = bot?.lastMessage?.createdAt ?: bot?.createdAt
    val at = last?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (at != null && Duration.between(at, Instant.ofEpochMilli(nowMs)).toHours() > 12) return "sleepy"
    return "idle"
}

fun parseTime(iso: String?): Instant? = iso?.let { runCatching { Instant.parse(it) }.getOrNull() ?: runCatching { java.time.OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/** Now, in millis, nudged forward every 30s by [NowTicker]. */
val LocalNow = compositionLocalOf { System.currentTimeMillis() }

/** Keeps every relative time under it honest without a timer per row. */
@Composable
fun NowTicker(content: @Composable () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    CompositionLocalProvider(LocalNow provides now, content = content)
}

/** "2m" that becomes "3m" on its own. */
@Composable
fun timeAgoLive(iso: String?): String {
    val now = LocalNow.current
    return remember(iso, now) { timeAgo(iso, now) }
}

fun timeAgo(iso: String?, nowMs: Long = System.currentTimeMillis()): String {
    val at = parseTime(iso) ?: return ""
    val s = Duration.between(at, Instant.ofEpochMilli(nowMs)).seconds
    return when {
        s < 60 -> "now"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        s < 604800 -> "${s / 86400}d"
        else -> DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault()).format(at)
    }
}

fun stampOf(iso: String): String {
    val at = parseTime(iso) ?: return ""
    val z = ZoneId.systemDefault()
    val today = at.atZone(z).toLocalDate() == java.time.LocalDate.now(z)
    return if (today) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(z).format(at)
    else DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a").withZone(z).format(at)
}

val PaddingNone = PaddingValues(0.dp)
