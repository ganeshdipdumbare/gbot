package dev.gbot.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Routine
import dev.gbot.app.net.s
import dev.gbot.app.ui.ErrorText
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.Label
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.Panel
import dev.gbot.app.ui.Screen
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.attempt
import dev.gbot.app.ui.pressable
import dev.gbot.app.ui.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream

/** Polls the screen about once a second, for the preview. */
@Composable
private fun rememberScreen(botId: String, on: Boolean): ImageBitmap? {
    val app = GbotApp.instance
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(botId, on) {
        if (!on) { img = null; return@LaunchedEffect }
        while (isActive) {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.http.client.newCall(app.http.request(app.api.screenPath(botId)).build()).execute().use { r ->
                        if (r.isSuccessful) BitmapFactory.decodeStream(r.body.byteStream())?.asImageBitmap() else null
                    }
                }
            }.getOrNull()?.let { img = it }
            delay(830)
        }
    }
    return img
}

/** One MJPEG connection renders every frame of the full-screen view. */
@Composable
private fun rememberStream(botId: String, on: Boolean): ImageBitmap? {
    val app = GbotApp.instance
    var img by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(botId, on) {
        if (!on) return@LaunchedEffect
        while (isActive) {
            runCatching {
                withContext(Dispatchers.IO) {
                    app.http.streaming.newCall(app.http.request(app.api.streamPath(botId)).build()).execute().use { r ->
                        if (!r.isSuccessful) return@use
                        val input = r.body.byteStream()
                        while (isActive) {
                            val frame = readPart(input) ?: break
                            BitmapFactory.decodeByteArray(frame, 0, frame.size)?.let { bmp: Bitmap -> img = bmp.asImageBitmap() }
                        }
                    }
                }
            }
            delay(1000)
        }
    }
    return img
}

private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    while (true) {
        val c = input.read()
        if (c < 0) return if (sb.isEmpty()) null else sb.toString()
        if (c == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(c.toChar())
    }
}

/** Reads the next "--gbotframe" part's JPEG bytes. */
private fun readPart(input: InputStream): ByteArray? {
    var len = -1
    while (true) {
        val line = readLine(input) ?: return null
        if (line.startsWith("Content-Length:", ignoreCase = true)) len = line.substringAfter(":").trim().toIntOrNull() ?: -1
        else if (line.isEmpty() && len > 0) break
    }
    val buf = ByteArray(len)
    var off = 0
    while (off < len) {
        val n = input.read(buf, off, len - off)
        if (n < 0) return null
        off += n
    }
    return buf
}

@Composable
fun ComputerScreen(nav: NavHostController, id: String, take: Boolean) {
    val app = GbotApp.instance
    if (app.mode == Mode.Local) { LocalComputerScreen(nav, id); return }
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val bot = s.bots.find { it.id == id } ?: run { Screen("Computer", { nav.popBackStack() }) {}; return }
    val status = s.computers[id]
    var viewing by remember { mutableStateOf(take) }
    var control by remember { mutableStateOf(take) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(id) { runCatching { app.store.setComputer(id, app.api.computer(id)) } }
    val on = status?.state == "on"
    val label = when (status?.state) { "on" -> if (status.controller == "user") "You have control" else "Running"; "starting" -> "Starting"; else -> "Asleep" }
    val src = rememberScreen(id, on && !viewing)

    fun act(a: String) {
        busy = true
        scope.launch {
            try { app.store.setComputer(id, app.api.computerAction(id, a)) }
            catch (e: Exception) { app.toast(e.message ?: if (a == "sleep") "Couldn't put it to sleep" else "Couldn't wake the computer", true) }
            finally { busy = false }
        }
    }

    Screen("${bot.name}'s computer", { nav.popBackStack() }, subtitle = label, actions = {
        if (on) {
            IconBtn("expand", "Watch full screen", { control = false; viewing = true })
            IconBtn("control", "Take control", { control = true; viewing = true })
            IconBtn("moon", "Sleep (keeps its windows and state)", { act("sleep") }, enabled = !busy)
        } else IconBtn("power", "Wake", { act(if (status?.state == "asleep") "wake" else "start") }, enabled = !busy && status?.state != "starting" && status?.available != false)
    }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(18.dp)).background(g.surface).pressable { if (on) { control = false; viewing = true } }, contentAlignment = Alignment.Center) {
            if (src != null) Image(src, "${bot.name}'s screen", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Gbot(bot.look, bot.id, 40, mood = if (status?.state == "starting") "working" else "sleepy")
                Spacer(Modifier.size(6.dp))
                Text(
                    when {
                        status?.available == false -> "The computer image isn't built yet. Run make computer."
                        status?.state == "starting" -> "Waking up…"
                        status?.state == "asleep" -> "Asleep — wake it from the top bar to keep going"
                        on -> "Connecting to the screen…"
                        else -> "Wakes up when it needs it"
                    }, style = T.small, color = g.ink2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        Text(
            (if (status?.backend == "microsandbox") "Its own microVM on the server." else "A container on the server.") + if (bot.computerNetwork) " Online." else " Offline until you allow internet in its settings.",
            style = T.tiny, color = g.ink3, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Routines(bot)
    }

    if (viewing) ComputerView(bot, control, onControl = { control = it }, onClose = { viewing = false; control = false })
}

private val keyRow = listOf("Enter" to "Return", "⌫" to "BackSpace", "Tab" to "Tab", "Esc" to "Escape", "←" to "Left", "↑" to "Up", "↓" to "Down", "→" to "Right", "Ctrl+C" to "ctrl+c", "Ctrl+V" to "ctrl+v", "Ctrl+A" to "ctrl+a")

/**
 * The screen, full size. Watching sends nothing, so the bot keeps working.
 * With control: tap clicks, double-tap double-clicks, long-press
 * right-clicks, dragging scrolls, and the keyboard types into it.
 */
@Composable
private fun ComputerView(bot: Bot, control: Boolean, onControl: (Boolean) -> Unit, onClose: () -> Unit) {
    val app = GbotApp.instance
    val s by app.store.state.collectAsState()
    val status = s.computers[bot.id]
    val scope = rememberCoroutineScope()
    var holding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(TextFieldValue("​", TextRange(1))) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val frame = rememberStream(bot.id, status?.state == "on")

    // Inputs go one at a time, in order.
    val queue = remember { Channel<JsonObject>(Channel.UNLIMITED) }
    val inFlight = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    LaunchedEffect(bot.id) {
        for (body in queue) {
            try { app.api.computerAction(bot.id, "input", body) } catch (e: Exception) { error = e.message ?: "Input failed" }
            inFlight.decrementAndGet()
        }
    }
    fun send(body: JsonObject) { inFlight.incrementAndGet(); queue.trySend(body) }
    var pendingText = remember { StringBuilder() }
    var textJob by remember { mutableStateOf<Job?>(null) }
    fun flushText() { textJob?.cancel(); textJob = null; if (pendingText.isNotEmpty()) { send(buildJsonObject { put("type", "type"); put("text", pendingText.toString()) }); pendingText = StringBuilder() } }
    fun queueText(t: String) { pendingText.append(t); if (textJob == null) textJob = scope.launch { delay(35); textJob = null; flushText() } }
    var scrollDy = remember { floatArrayOf(0f, 0.5f, 0.5f) }
    var scrollJob by remember { mutableStateOf<Job?>(null) }
    fun queueScroll(dy: Float, x: Float, y: Float) {
        scrollDy[0] += dy; scrollDy[1] = x; scrollDy[2] = y
        if (scrollJob == null) scrollJob = scope.launch {
            delay(55); scrollJob = null
            val d = scrollDy[0]; scrollDy[0] = 0f
            if (d != 0f) send(buildJsonObject { put("type", "scroll"); put("deltaY", d); put("x", scrollDy[1]); put("y", scrollDy[2]) })
        }
    }

    suspend fun takeControl() {
        error = ""
        try { app.store.setComputer(bot.id, app.api.computerAction(bot.id, "control", buildJsonObject { put("user", true) })); holding = true; onControl(true) }
        catch (e: Exception) { error = e.message ?: "Couldn't take control" }
    }
    fun release() {
        flushText()
        scope.launch {
            if (holding) {
                // Let the last input land before handing control back.
                var waited = 0
                while (inFlight.get() > 0 && waited < 3000) { delay(50); waited += 50 }
                runCatching { app.store.setComputer(bot.id, app.api.computerAction(bot.id, "control", buildJsonObject { put("user", false) })) }
            }
            holding = false
            onClose()
        }
    }
    LaunchedEffect(control) { if (control && !holding) takeControl() }
    DisposableEffect(Unit) { onDispose { queue.close() } }
    BackHandler { release() }

    Dialog(onDismissRequest = ::release, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xF00E0B12)).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Gbot(bot.look, bot.id, 30, mood = if (holding) "waiting" else "idle", live = true)
                Spacer(Modifier.width(8.dp))
                Text(if (holding) "You have control of ${bot.name}'s computer" else "Watching ${bot.name}'s computer", style = T.small.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 2, modifier = Modifier.weight(1f))
                if (holding) GButton("Hand back", ::release, small = true, variant = Variant.Soft)
                else GButton("Take control", { scope.launch { takeControl() } }, small = true, variant = Variant.Soft)
                Spacer(Modifier.width(6.dp))
                IconBtn("close", "Close", ::release, tint = Color.White)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(14.dp)).background(Color.Black)
                        .pointerInput(holding) {
                            if (!holding) return@pointerInput
                            detectTapGestures(
                                onTap = { o -> flushText(); send(buildJsonObject { put("type", "click"); put("x", o.x / size.width); put("y", o.y / size.height) }) },
                                onDoubleTap = { o -> flushText(); send(buildJsonObject { put("type", "dblclick"); put("x", o.x / size.width); put("y", o.y / size.height) }) },
                                onLongPress = { o -> flushText(); send(buildJsonObject { put("type", "rightclick"); put("x", o.x / size.width); put("y", o.y / size.height) }) },
                            )
                        }
                        .pointerInput(holding) {
                            if (!holding) return@pointerInput
                            detectVerticalDragGestures { change, dy -> queueScroll(-dy * 1.6f, change.position.x / size.width, change.position.y / size.height) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (frame != null) Image(frame, "${bot.name}'s screen", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    else Text(when (status?.state) { "starting" -> "Waking up…"; "on" -> "Connecting to the screen…"; else -> "Its computer is asleep" }, style = T.small, color = Color.White.copy(alpha = 0.6f))
                }
            }
            if (error.isNotEmpty()) Text(error, style = T.small, color = Color(0xFFFFA49A), modifier = Modifier.fillMaxWidth().padding(4.dp), textAlign = TextAlign.Center)
            if (holding) {
                // The phone keyboard types into the bot's computer through this hidden field.
                BasicTextField(
                    typed, { v ->
                        val t = v.text
                        when {
                            t.isEmpty() -> send(buildJsonObject { put("type", "key"); put("key", "BackSpace") })
                            t.length > 1 -> queueText(t.removePrefix("​"))
                        }
                        typed = TextFieldValue("​", TextRange(1))
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { flushText(); send(buildJsonObject { put("type", "key"); put("key", "Return") }) }),
                    modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus),
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyCap("⌨ Keyboard") { runCatching { focus.requestFocus() }; kb?.show() }
                    keyRow.forEach { (label, key) -> KeyCap(label) { flushText(); send(buildJsonObject { put("type", "key"); put("key", key) }) } }
                }
            } else Text("The bot keeps working while you watch.", style = T.tiny, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth().padding(10.dp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun KeyCap(label: String, onClick: () -> Unit) {
    Text(label, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.12f)).pressable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp))
}

private data class Preset(val label: String, val cron: String, val time: Boolean)
private val presets = listOf(Preset("Every hour", "0 * * * *", false), Preset("Every day", "0 {h} * * *", true), Preset("Weekdays", "0 {h} * * 1-5", true), Preset("Mondays", "0 {h} * * 1", true), Preset("Custom", "", false))

@Composable
internal fun Routines(bot: Bot) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Routine>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf<Routine?>(null) }
    val clip = LocalClipboardManager.current
    suspend fun load() { list = runCatching { app.api.routines(bot.id).routines }.getOrDefault(emptyList()) }
    LaunchedEffect(bot.id) { load() }
    LaunchedEffect(bot.id) { app.live.events.collect { e -> if (e.s("type") == "routine" && e.s("botId") == bot.id) load() } }

    Text("Routines", style = T.h2, color = g.ink, modifier = Modifier.padding(top = 8.dp))
    if (app.mode == Mode.Local) Text("On this phone they run while gbot is open, and a missed time catches up when you come back.", style = T.tiny, color = g.ink3)
    secret?.let { r ->
        Panel {
            Text("Webhook ready. Copy the key now; it won't be shown again.", style = T.small.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
            Text("POST ${r.webhookUrl}", style = T.tiny.copy(fontFamily = FontFamily.Monospace), color = g.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(g.bg).padding(8.dp))
            Text("Authorization: Bearer ${r.secret}", style = T.tiny.copy(fontFamily = FontFamily.Monospace), color = g.ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(g.bg).padding(8.dp))
            Row { GButton("Copy both", { clip.setText(AnnotatedString("POST ${r.webhookUrl}\nAuthorization: Bearer ${r.secret}")); app.toast("Copied") }, variant = Variant.Soft, small = true); Spacer(Modifier.width(8.dp)); GButton("Done", { secret = null }, variant = Variant.Ghost, small = true) }
        }
    }
    list?.forEach { r ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(g.surface).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon("clock", size = 16.dp, color = if (r.active) g.ink2 else g.ink3)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = T.body, color = if (r.active) g.ink else g.ink3, maxLines = 1)
                Text(if (r.active) r.`when` else "Paused", style = T.tiny, color = g.ink3, maxLines = 1)
            }
            IconBtn("play", "Run now", { scope.attempt("Running now") { app.api.runRoutine(bot.id, r.id) } }, size = 34.dp)
            IconBtn(if (r.active) "stop" else "refresh", if (r.active) "Pause" else "Resume", { scope.attempt(if (r.active) "Routine paused" else "Routine resumed") { app.api.updateRoutine(bot.id, r.id, !r.active); load() } }, size = 34.dp)
            IconBtn("trash", "Delete", { scope.attempt("Routine deleted") { app.api.deleteRoutine(bot.id, r.id); load() } }, size = 34.dp, tint = g.danger)
        }
    }
    if (adding) NewRoutine(bot) { r -> adding = false; if (r != null) { scope.launch { load() }; if (r.secret != null) secret = r } }
    else GButton("New routine", { adding = true }, variant = Variant.Soft, icon = "plus")
    if (list?.isEmpty() == true && !adding) Text("Or ask in chat: “every weekday at 8am, summarize the news.”", style = T.tiny, color = g.ink3)
}

@Composable
private fun NewRoutine(bot: Bot, onDone: (Routine?) -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("schedule") }
    var preset by remember { mutableStateOf(2) }
    var time by remember { mutableStateOf("08:00") }
    var custom by remember { mutableStateOf("0 9 * * *") }
    var error by remember { mutableStateOf("") }
    fun cron(): String {
        val p = presets[preset]
        if (p.cron.isEmpty()) return custom
        val (h, m) = time.split(":").map { it.trim().toIntOrNull() ?: 0 }.let { (it.getOrElse(0) { 8 }) to (it.getOrElse(1) { 0 }) }
        return p.cron.replace("0 {h}", "$m $h")
    }
    Panel {
        Column { Label("Name"); Field(name, { name = it.take(80) }, placeholder = "Morning briefing") }
        Column { Label("Instruction"); Field(prompt, { prompt = it }, singleLine = false, minLines = 3, placeholder = "Summarize what's new and what needs me today.") }
        Label("When")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("On a schedule", kind == "schedule", { kind = "schedule" })
            if (app.mode == Mode.Server) Chip("Webhook", kind == "webhook", { kind = "webhook" })
        }
        if (kind == "schedule") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { presets.forEachIndexed { i, p -> Chip(p.label, preset == i, { preset = i }) } }
            if (presets[preset].time) Column { Label("Time", "24-hour, like 08:00"); Field(time, { time = it.take(5) }, keyboard = KeyboardType.Number) }
            if (presets[preset].cron.isEmpty()) Column { Label("Cron schedule"); Field(custom, { custom = it }, mono = true) }
        } else Text("Any service can start it with a POST. Text it sends is treated as outside content, so changes still ask you first.", style = T.tiny, color = g.ink3)
        ErrorText(error)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            GButton("Cancel", { onDone(null) }, variant = Variant.Ghost, small = true)
            Spacer(Modifier.width(6.dp))
            GButton("Save", {
                error = ""
                scope.launch {
                    try { onDone(app.api.createRoutine(bot.id, name.trim(), prompt.trim(), kind, if (kind == "schedule") cron() else null)) }
                    catch (e: Exception) { error = e.message ?: "Couldn't save" }
                }
            }, small = true, enabled = name.isNotBlank() && prompt.isNotBlank())
        }
    }
}
