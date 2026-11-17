package dev.gbot.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import dev.gbot.app.ui.Motion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gbot.app.Dictation
import dev.gbot.app.GbotApp
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Skill
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.T
import dev.gbot.app.ui.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object SkillCache {
    private var list: List<Skill>? = null
    suspend fun get(): List<Skill> = list ?: runCatching { GbotApp.instance.api.skills().skills }.getOrDefault(emptyList()).also { list = it }
    fun invalidate() { list = null }
}

private data class Suggestion(val key: String, val label: String, val hint: String?, val insert: String, val bot: Bot?)

/**
 * The message box: "@" names a bot, "/" at the start picks a skill, the
 * mic dictates, and the button sends (or stops a reply in progress).
 */
@Composable
fun Composer(placeholder: String, busy: Boolean, mentionable: List<Bot>, onSend: (String) -> Unit, onStop: () -> Unit, prefill: String = "") {
    val app = GbotApp.instance
    val g = LocalG.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var skills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { skills = SkillCache.get() }

    fun setText(t: String) { value = TextFieldValue(t, TextRange(t.length)) }

    // Text shared in from another app. It waits in the box: sending is
    // still the person's call.
    LaunchedEffect(prefill) { if (prefill.isNotEmpty() && value.text.isEmpty()) setText(prefill) }

    val dictation = remember { Dictation(ctx, app.voice, app.api) { t -> val x = value.text; setText((if (x.isNotEmpty()) x.trimEnd() + " " else "") + t) } }
    var mode by remember { mutableStateOf(Dictation.Mode.None) }
    LaunchedEffect(Unit) { mode = dictation.prepare() }
    DisposableEffect(Unit) { onDispose { dictation.cancel() } }
    val dState by dictation.state.collectAsState()
    val level by dictation.level.collectAsState()
    LaunchedEffect(dState) { while (dState == Dictation.St.Listening) { dictation.sample(); delay(80) } }
    val listening = dState == Dictation.St.Listening

    fun toggleMic() {
        when (dState) {
            Dictation.St.Listening -> scope.launch { runCatching { dictation.stop() }.onFailure { app.toast(it.message ?: "Couldn't transcribe", true) } }
            Dictation.St.Idle -> runCatching { dictation.start() }.onFailure { app.toast("Couldn't start dictation", true) }
            else -> {}
        }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) toggleMic() else app.toast("Dictation needs the microphone", true) }
    fun mic() {
        if (dState == Dictation.St.Idle && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPerm.launch(Manifest.permission.RECORD_AUDIO)
        else toggleMic()
    }

    val text = value.text
    val suggestions = remember(text, mentionable, skills) {
        val at = Regex("(^|\\s)@([^@\\n]*)$").find(text)
        if (at != null && mentionable.isNotEmpty()) {
            val q = at.groupValues[2].lowercase()
            mentionable.filter { it.name.lowercase().startsWith(q) }.map {
                Suggestion(it.id, it.name, it.title, text.dropLast(at.groupValues[2].length + 1) + "@" + it.name + " ", it)
            }
        } else {
            val slash = Regex("^/([\\w-]*)$").find(text)
            if (slash != null) skills.filter { it.name.startsWith(slash.groupValues[1].lowercase()) }.map { Suggestion(it.id, "/" + it.name, it.description, "Use the ${it.name} skill: ", null) }
            else emptyList()
        }
    }

    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        setText("")
        onSend(t)
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp, top = 4.dp)) {
        if (suggestions.isNotEmpty()) Column(
            Modifier.fillMaxWidth().padding(bottom = 6.dp).heightIn(max = 240.dp).clip(RoundedCornerShape(16.dp))
                .background(if (g.dark) Color(0xFF2A2A2A) else Color.White).border(1.dp, g.line, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(4.dp),
        ) {
            suggestions.forEach { s ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).tap { setText(s.insert) }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (s.bot != null) Gbot(s.bot.look, s.bot.id, 28) else Icon("spark", size = 18.dp, color = g.ink2)
                    Spacer(Modifier.width(10.dp))
                    Text(s.label, style = T.body, color = g.ink)
                    if (!s.hint.isNullOrEmpty()) Text("  " + s.hint, style = T.small, color = g.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(g.surface).border(1.dp, g.line, RoundedCornerShape(26.dp)).padding(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                Box(Modifier.size(36.dp).clip(CircleShape).border(1.dp, g.line, CircleShape).tap { menu = true }, contentAlignment = Alignment.Center) { Icon("plus", size = 18.dp, color = g.ink2) }
                DropdownMenu(menu, { menu = false }, containerColor = if (g.dark) Color(0xFF2A2A2A) else Color.White) {
                    DropdownMenuItem({ Text("Use a skill", style = T.body) }, { menu = false; setText("/") }, leadingIcon = { Icon("spark", color = g.ink2) }, trailingIcon = { Text("/", color = g.ink3) })
                    if (mentionable.isNotEmpty()) DropdownMenuItem({ Text("Mention a bot", style = T.body) }, { menu = false; setText(if (text.isNotEmpty()) text.trimEnd() + " @" else "@") }, leadingIcon = { Icon("users", color = g.ink2) }, trailingIcon = { Text("@", color = g.ink3) })
                    if (mode != Dictation.Mode.None) DropdownMenuItem({ Text("Dictate", style = T.body) }, { menu = false; mic() }, leadingIcon = { Icon("mic", color = g.ink2) })
                }
            }
            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)) {
                if (text.isEmpty()) Text(if (listening) "Listening…" else placeholder, style = T.body, color = g.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value, { value = it }, textStyle = T.body.copy(color = g.ink), cursorBrush = SolidColor(g.live),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                )
            }
            if (mode != Dictation.Mode.None) {
                val ring by animateFloatAsState(1f + level * 0.6f, Motion.snap(), label = "level")
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    if (listening) Box(Modifier.size(36.dp).scale(ring).clip(CircleShape).background(g.live.copy(alpha = 0.3f)))
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (listening) g.live else Color.Transparent).tap { mic() }, contentAlignment = Alignment.Center) {
                        if (dState == Dictation.St.Working) CircularProgressIndicator(Modifier.size(16.dp), color = g.ink2, strokeWidth = 2.dp)
                        else Icon("mic", size = 18.dp, color = if (listening) Color.White else g.ink3)
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            AnimatedContent(
                busy && text.isBlank(), label = "send",
                transitionSpec = { (scaleIn(Motion.pressUp, initialScale = 0.6f) + fadeIn(Motion.fast())) togetherWith (scaleOut(Motion.fast(), targetScale = 0.6f) + fadeOut(Motion.fast())) },
            ) { stop ->
                if (stop) Box(Modifier.size(36.dp).clip(CircleShape).background(g.emphasis).tap(onClick = onStop), contentAlignment = Alignment.Center) { Icon("stop", size = 16.dp, color = g.onEmphasis) }
                else {
                    val can = text.isNotBlank()
                    Box(Modifier.size(36.dp).clip(CircleShape).background(if (can) g.emphasis else g.surface2).tap(can) { send() }, contentAlignment = Alignment.Center) {
                        Icon("up", size = 18.dp, color = if (can) g.onEmphasis else g.ink3)
                    }
                }
            }
        }
    }
}
