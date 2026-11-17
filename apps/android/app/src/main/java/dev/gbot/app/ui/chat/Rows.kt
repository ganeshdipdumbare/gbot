package dev.gbot.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import dev.gbot.app.ui.Motion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.gbot.app.GbotApp
import dev.gbot.app.data.keyOf
import dev.gbot.app.local.choiceLines
import dev.gbot.app.local.plainText
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Message
import dev.gbot.app.net.json
import dev.gbot.app.net.s
import dev.gbot.app.ui.ErrorText
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.Markdown
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.pressable
import dev.gbot.app.ui.tap
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the bot did, as a quiet log above its reply; long runs fold. */
@Composable
fun Activity(m: Message) {
    val g = LocalG.current
    val steps = (m.data?.get("steps") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val long = steps.size > 4
    var open by remember(m.id) { mutableStateOf(!long) }
    val failed = steps.count { (it["ok"] as? JsonPrimitive)?.booleanOrNull == false }
    val rot by animateFloatAsState(if (open) 90f else 0f, Motion.snap(), label = "chev")
    Column(Modifier.padding(top = 8.dp).widthIn(max = 560.dp)) {
        if (long) Row(Modifier.clip(RoundedCornerShape(6.dp)).tap { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Worked through ${steps.size} steps" + if (failed > 0) " · $failed failed" else "", style = T.small.copy(fontSize = 13.5.sp), color = g.ink3)
            Text(" ›", style = T.small, color = g.ink3, modifier = Modifier.rotate(rot))
        }
        AnimatedVisibility(open, enter = expandVertically(Motion.sizeSpring) + fadeIn(Motion.quick()), exit = shrinkVertically(Motion.sizeSpring) + fadeOut(Motion.fast())) {
            Column {
                steps.forEach { st ->
                    val ok = (st["ok"] as? JsonPrimitive)?.booleanOrNull != false
                    val label = st.s("label").orEmpty()
                    val detail = st.s("detail")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (ok) g.ink3 else g.danger))
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = T.small.copy(fontSize = 13.5.sp), color = g.ink2)
                        if (!detail.isNullOrEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            Text(detail, style = T.small.copy(fontSize = if (label == "Ran") 12.5.sp else 13.5.sp, fontFamily = if (label == "Ran") FontFamily.Monospace else FontFamily.Default), color = g.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One bot messaging another: a centered divider; tap to read the message.
 * Messaging is async, so the card is only about the message itself — sent
 * here, received there. An answer is never folded back into it: the asking
 * bot speaks it in its own chat when it comes.
 *
 * It shows no status: both cards are written when the message is handed
 * over, so "Sent" and "Received" hold from the first paint. What becomes of
 * the answer after that is the asking bot's business, and it says so in its
 * own words; "failed" in particular is not a delivery failure, only the
 * catch-all for no answer coming back.
 */
@Composable
fun Exchange(m: Message, bots: List<Bot>) {
    val g = LocalG.current
    var open by remember { mutableStateOf(false) }
    val fromId = m.str("from"); val toId = m.str("to")
    val from = bots.find { it.id == fromId }; val to = bots.find { it.id == toId }
    val outbound = m.botId == fromId
    val peer = if (outbound) to else from
    // The bot whose chat this is always sits on the left; the arrow carries
    // the direction, so a received message reads "Me ← Them".
    val left = if (outbound) from else to
    val right = if (outbound) to else from
    val tell = m.str("expect") == "tell"
    val label = when {
        outbound && tell -> "Told ${to?.name ?: "a bot"}"
        outbound -> "Asked ${to?.name ?: "a bot"}"
        else -> "Message from ${from?.name ?: "a bot"}"
    }
    val line = if (outbound) "Sent" else "Received"
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.clip(RoundedCornerShape(50)).tap { open = true }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (peer != null) { Gbot(peer.look, peer.id, 22); Spacer(Modifier.width(6.dp)) }
            Text(label, style = T.small, color = g.ink2)
        }
        Text(line, style = T.tiny.copy(fontSize = 12.5.sp), color = g.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        Column(Modifier.clip(RoundedCornerShape(22.dp)).background(if (g.dark) androidx.compose.ui.graphics.Color(0xFF262626) else androidx.compose.ui.graphics.Color.White).padding(18.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(50)).background(g.surface).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (left != null) Gbot(left.look, left.id, 24)
                Text(" ${left?.name ?: "A bot"}  ${if (outbound) "→" else "←"}  ", style = T.small.copy(fontWeight = FontWeight.Medium), color = g.ink)
                if (right != null) Gbot(right.look, right.id, 24)
                Text(" ${right?.name ?: "A bot"}", style = T.small.copy(fontWeight = FontWeight.Medium), color = g.ink)
            }
            Turn(from) { Text(m.str("message").orEmpty(), style = T.body, color = g.ink) }
            GButton("Close", { open = false }, Modifier.align(Alignment.CenterHorizontally), variant = Variant.Soft)
        }
    }
}

/** "Pip", "Pip and Taro", "Pip, Taro and 2 more". */
private fun names(bots: List<Bot?>): String {
    val n = bots.map { it?.name ?: "a bot" }
    return when {
        n.size <= 1 -> n.firstOrNull().orEmpty()
        n.size == 2 -> "${n[0]} and ${n[1]}"
        n.size == 3 -> "${n[0]}, ${n[1]} and ${n[2]}"
        else -> "${n[0]}, ${n[1]} and ${n.size - 2} more"
    }
}

/**
 * A round: the messages one bot sent in one turn, as one line; tap to read
 * them. The answers come back to that bot on their own; they are not shown
 * here.
 */
@Composable
fun RoundCard(ms: List<Message>, bots: List<Bot>) {
    val g = LocalG.current
    var open by remember { mutableStateOf(false) }
    val from = bots.find { it.id == ms.first().str("from") }
    val tos = ms.map { m -> bots.find { it.id == m.str("to") } }
    val line = "Sent to ${ms.size} bots"
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.clip(RoundedCornerShape(50)).tap { open = true }.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            tos.filterNotNull().take(3).forEachIndexed { i, b ->
                Box(Modifier.padding(start = if (i == 0) 0.dp else 2.dp)) { Gbot(b.look, b.id, 22) }
            }
            Spacer(Modifier.width(6.dp))
            Text("Asked ${names(tos)}", style = T.small, color = g.ink2)
        }
        Text(line, style = T.tiny.copy(fontSize = 12.5.sp), color = g.ink3)
    }
    if (open) Dialog(onDismissRequest = { open = false }) {
        Column(Modifier.clip(RoundedCornerShape(22.dp)).background(if (g.dark) androidx.compose.ui.graphics.Color(0xFF262626) else androidx.compose.ui.graphics.Color.White).padding(18.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(50)).background(g.surface).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (from != null) Gbot(from.look, from.id, 24)
                Text(" ${from?.name ?: "A bot"} messaged ${ms.size} bots", style = T.small.copy(fontWeight = FontWeight.Medium), color = g.ink)
            }
            ms.forEachIndexed { i, m ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 1.dp, max = 1.dp).background(g.surface2))
                Turn(tos[i], "To ${tos[i]?.name ?: "a bot"}") { Text(m.str("message").orEmpty(), style = T.body, color = g.ink) }
            }
            GButton("Close", { open = false }, Modifier.align(Alignment.CenterHorizontally), variant = Variant.Soft)
        }
    }
}

@Composable
private fun Turn(bot: Bot?, label: String? = null, content: @Composable () -> Unit) {
    val g = LocalG.current
    Row {
        Box(Modifier.width(40.dp).padding(top = 2.dp)) { if (bot != null) Gbot(bot.look, bot.id, 36) }
        Column(Modifier.weight(1f)) {
            Text(label ?: bot?.name ?: "Removed bot", style = T.tiny, color = g.ink3, modifier = Modifier.padding(bottom = 4.dp))
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(g.surface).padding(horizontal = 14.dp, vertical = 8.dp)) { content() }
        }
    }
}

private val cardStatus = mapOf("approved" to "Approved", "denied" to "Declined", "answered" to "Answered", "done" to "Handed back", "skipped" to "Skipped")

private fun argsPreview(tool: String, args: JsonObject?): String? {
    if (args == null) return null
    fun a(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull ?: args[k]?.toString().orEmpty()
    return when (tool) {
        "computer_shell" -> a("command")
        "computer_write_file" -> "${a("path")}\n\n${a("content").take(600)}"
        "browser_open", "browser_read" -> a("url")
        "routine_create" -> "${a("name")}\n${a("schedule")}\n\n${a("prompt")}"
        else -> {
            val s = json.encodeToString(JsonObject.serializer(), args)
            if (s == "{}") null else prettyJson(args)
        }
    }
}

private fun prettyJson(o: JsonObject): String = kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o)

/** Where a bot asks for something: approval, an answer, or your hand on its computer. */
@Composable
fun Card(m: Message, bot: Bot, onTakeover: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    val kind = m.kind
    val st = m.str("status").orEmpty()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun answer(decision: String, value: String? = null, always: Boolean? = null) {
        busy = true; error = ""
        scope.launch {
            try {
                val upd = app.api.answer(m.id, decision, value, always)
                app.store.patchMessage(keyOf(m), m.id) { upd }
                app.alerts.answered(m.id, keyOf(m), "")
            } catch (e: Exception) { error = e.message ?: "Couldn't answer" } finally { busy = false }
        }
    }

    if (st != "pending") {
        val ok = st != "denied" && st != "skipped"
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(if (ok) g.success.copy(alpha = 0.16f) else g.surface2), contentAlignment = Alignment.Center) {
                Icon(if (ok) "check" else "close", size = 11.dp, color = if (ok) g.success else g.ink3)
            }
            Spacer(Modifier.width(6.dp))
            Text(cardStatus[st] ?: st, style = T.small, color = g.ink)
            Spacer(Modifier.width(6.dp))
            val answerText = m.str("answer")
            val said = if (kind == "question" && !answerText.isNullOrEmpty()) plainText(answerText) else plainText(m.content)
            Text(if (kind == "question" && !answerText.isNullOrEmpty()) "“$said”" else said, style = T.small, color = g.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
        }
        return
    }

    // A model may pack every choice into one string. Whatever arrived, the
    // person sees one choice per row.
    val options = (m.data?.get("options") as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .flatMap { choiceLines(it, 4) }
        .distinct()
        .take(4)
    val preview = if (kind == "approval") argsPreview(m.str("tool").orEmpty(), m.data?.get("args") as? JsonObject) else null
    val label = when (kind) { "approval" -> "Needs approval"; "question" -> "Question"; else -> "Needs you" }

    Column(Modifier.padding(vertical = 10.dp).widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(g.surface).border(1.dp, g.warning.copy(alpha = 0.25f), RoundedCornerShape(18.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Gbot(bot.look, bot.id, 24, mood = "waiting")
            Spacer(Modifier.width(8.dp))
            Text(bot.name, style = T.small.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp), color = g.ink, modifier = Modifier.weight(1f), maxLines = 1)
            Row(Modifier.clip(RoundedCornerShape(50)).background(g.warning.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(g.warning))
                Spacer(Modifier.width(5.dp))
                Text(label, style = T.tiny, color = g.warning)
            }
        }
        Text(plainText(m.content).ifEmpty { "Take over for a moment" }, style = T.body.copy(fontSize = 14.5.sp), color = g.ink)
        if (preview != null) Box(Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(10.dp)).background(g.bg).border(1.dp, g.line, RoundedCornerShape(10.dp)).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(preview, style = T.tiny.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp), color = g.ink2)
        }
        when (kind) {
            "approval" -> Row(verticalAlignment = Alignment.CenterVertically) {
                GButton("Approve", { answer("approve") }, enabled = !busy, small = true)
                Spacer(Modifier.width(6.dp))
                GButton("Decline", { answer("reject") }, enabled = !busy, small = true, variant = Variant.Soft)
                Spacer(Modifier.weight(1f))
                Text("Always allow", style = T.tiny, color = g.ink3, modifier = Modifier.clip(RoundedCornerShape(6.dp)).tap(!busy) { answer("approve", null, true) }.padding(6.dp))
            }
            "question" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { o ->
                    Text(o, style = T.body.copy(fontSize = 14.sp), color = g.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(g.bg).border(1.dp, g.line, RoundedCornerShape(10.dp)).pressable(!busy) { answer("answer", o) }.padding(horizontal = 12.dp, vertical = 10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(text, { text = it }, Modifier.weight(1f), placeholder = if (options.isNotEmpty()) "Something else…" else "Your answer", onDone = { if (text.isNotBlank()) answer("answer", text.trim()) })
                    Spacer(Modifier.width(6.dp))
                    GButton("Send", { answer("answer", text.trim()) }, enabled = !busy && text.isNotBlank(), small = true)
                }
            }
            else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GButton("Take control", onTakeover, small = true)
                GButton("I'm done", { answer("answer") }, enabled = !busy, small = true, variant = Variant.Soft)
            }
        }
        ErrorText(error)
    }
}

/** A centered system line: errors, routine runs, notes. */
@Composable
fun EventLine(m: Message, author: Bot?, onTap: (() -> Unit)? = null) {
    val g = LocalG.current
    val err = m.kind == "error"
    val row = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp).let { if (onTap != null) it.tap(onClick = onTap) else it }
    Row(row, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (author != null) { Gbot(author.look, author.id, 22, mood = if (err) "blocked" else "idle"); Spacer(Modifier.width(6.dp)) }
        if (m.kind == "routine") { Icon("clock", size = 14.dp, color = g.ink2); Spacer(Modifier.width(4.dp)) }
        if (m.kind == "browser") { Icon("screen", size = 14.dp, color = g.ink2); Spacer(Modifier.width(4.dp)) }
        Markdown(m.content, color = if (err) g.danger else g.ink2, style = T.small.copy(textAlign = TextAlign.Center))
    }
}
