package dev.gbot.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.data.ChatKey
import dev.gbot.app.data.groupKey
import dev.gbot.app.data.isGroupKey
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Group
import dev.gbot.app.net.Message
import dev.gbot.app.net.QueueItem
import dev.gbot.app.net.s
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.Markdown
import dev.gbot.app.ui.R
import dev.gbot.app.ui.T
import dev.gbot.app.ui.TopBar
import dev.gbot.app.ui.attempt
import dev.gbot.app.ui.moodOf
import dev.gbot.app.ui.parseTime
import dev.gbot.app.ui.presenceText
import dev.gbot.app.ui.pressable
import dev.gbot.app.ui.screens.Stack
import dev.gbot.app.ui.stampOf
import dev.gbot.app.ui.tap
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.Duration

/** [round] is set on a round's first ask (all of them) and empty on the rest, which it draws. */
private data class ChatRow(val m: Message, val first: Boolean, val last: Boolean, val stamp: String?, val round: List<Message>? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavHostController, key: ChatKey, prefill: String = "") {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val group = if (isGroupKey(key)) s.groups.find { groupKey(it.id) == key } else null
    val bot = if (!isGroupKey(key)) s.bots.find { it.id == key } else null
    val messages = s.messages[key]
    val members = remember(group, s.bots) { group?.botIds?.mapNotNull { id -> s.bots.find { it.id == id } }.orEmpty() }
    var error by remember { mutableStateOf("") }
    var actions by remember { mutableStateOf<Message?>(null) }
    val clip = LocalClipboardManager.current

    // This chat is on screen: replies here aren't unread or notified.
    DisposableEffect(key) {
        app.store.openChat = key
        app.store.markRead(key)
        app.alerts.clear(key)
        onDispose { if (app.store.openChat == key) app.store.openChat = null }
    }
    LaunchedEffect(s.unread[key]) { if ((s.unread[key] ?: 0) > 0 && app.store.foreground) app.store.markRead(key) }
    LaunchedEffect(key) {
        runCatching { app.store.loadMessages(key) }.onFailure { error = it.message ?: "Couldn't load messages" }
        runCatching { app.store.loadQueue(key) }
        if (bot != null) runCatching { app.store.setComputer(bot.id, app.api.computer(bot.id)) }
    }
    // Read finished replies aloud for bots that have it on.
    LaunchedEffect(key) {
        app.live.events.collect { e ->
            if (e.s("type") != "message_done") return@collect
            val d = e["data"] as? kotlinx.serialization.json.JsonObject ?: return@collect
            if (d.s("role") != "bot") return@collect
            val k = d.s("groupId")?.let(::groupKey) ?: d.s("botId")
            if (k != key || !app.store.foreground) return@collect
            val b = app.store.now.bots.find { it.id == d.s("botId") }
            val content = d.s("content").orEmpty()
            if (b?.readAloud == true && content.isNotBlank()) runCatching { app.voice.speak(content, b.voice) }
        }
    }

    if (bot == null && group == null) {
        Column(Modifier.fillMaxSize().background(g.bg)) {
            TopBar("", { nav.popBackStack() })
            Text(if (s.loaded) "This chat isn't available anymore." else "Loading…", style = T.body, color = g.ink2, modifier = Modifier.fillMaxWidth().padding(40.dp), textAlign = TextAlign.Center)
        }
        return
    }

    val presence = s.presence
    val p = bot?.let { presence[it.id] }
    val busy = if (bot != null) p != null && p.state !in listOf("idle", "done", "waiting") else members.any { presence[it.id]?.state in listOf("thinking", "working") }
    val live = bot != null && (p?.live == true || s.computers[bot.id]?.state == "on")
    val job = if (bot != null) {
        if (p == null || p.state == "idle" || p.state == "done") bot.title
        else if (p.state == "working" && !p.detail.isNullOrEmpty()) p.detail else presenceText[p.state].orEmpty()
    } else members.joinToString(", ") { it.name }

    Column(Modifier.fillMaxSize().background(g.bg).imePadding()) {
        TopBar(
            bot?.name ?: group!!.name, { nav.popBackStack() }, subtitle = job,
            lead = {
                Box(Modifier.clip(CircleShape).tap { nav.navigate(if (bot != null) R.bot(bot.id) else R.group(group!!.id)) }) {
                    if (bot != null) Gbot(bot.look, bot.id, 34, mood = moodOf(p, bot), sharedKey = "bot:${bot.id}") else Stack(members, 24, presence)
                }
            },
        ) {
            if (bot != null) Box {
                IconBtn("screen", if (live) "Its computer is on" else "Computer and routines", { nav.navigate(R.computer(bot.id)) }, tint = if (live) g.live else g.ink2)
                if (live) Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(7.dp).clip(CircleShape).background(g.live))
            }
            IconBtn("gear", if (bot != null) "Bot settings" else "Group settings", { nav.navigate(if (bot != null) R.bot(bot.id) else R.group(group!!.id)) })
        }
        Box(Modifier.weight(1f)) {
            Transcript(key, bot, group, members, messages, error, onTakeover = { bot?.let { nav.navigate(R.computer(it.id, take = true)) } }, onLong = { actions = it })
        }
        QueueStrip(key, s.queue[key].orEmpty())
        Box(Modifier.navigationBarsPadding()) {
            Composer(
                placeholder = if (bot != null) "Message ${bot.name}…" else "Message ${group!!.name}…",
                busy = busy,
                prefill = prefill,
                mentionable = if (group != null) members else s.bots.filter { it.id != bot?.id },
                onSend = { t -> app.voice.stop(); scope.launch { app.store.sendMessage(key, t) } },
                onStop = { scope.attempt(fail = "Couldn't stop") { if (bot != null) app.api.stop(bot.id) else app.api.stopGroup(group!!.id) } },
            )
        }
    }

    actions?.let { m ->
        val author = m.botId?.let { id -> s.bots.find { it.id == id } }
        ModalBottomSheet(onDismissRequest = { actions = null }, containerColor = if (g.dark) Color(0xFF262626) else Color.White) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
                Text(m.content.take(140), style = T.small, color = g.ink2, maxLines = 3, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                SheetAction("copy", "Copy") { actions = null; clip.setText(AnnotatedString(m.content)); app.toast("Copied") }
                if (m.role == "bot") SheetAction("speaker", "Read aloud") {
                    actions = null
                    scope.launch { runCatching { app.voice.speak(m.content, author?.voice) }.onFailure { app.toast("Couldn't read aloud", true) } }
                }
                if (m.flag("failed")) SheetAction("refresh", "Retry") { actions = null; scope.launch { app.store.retry(key, m) } }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: String, label: String, onClick: () -> Unit) {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, size = 20.dp, color = g.ink2)
        Spacer(Modifier.width(14.dp))
        Text(label, style = T.body, color = g.ink)
    }
}

/** Only the messages waiting behind the running turn. */
@Composable
private fun QueueStrip(key: ChatKey, items: List<QueueItem>) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    val waiting = items.filter { it.status == "queued" || it.status == "paused" }
    if (waiting.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(g.surface).border(1.dp, g.line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (waiting.size == 1) "1 message waiting" else "${waiting.size} messages waiting", style = T.tiny.copy(fontWeight = FontWeight.Medium), color = g.ink2, modifier = Modifier.weight(1f))
            Text("Clear", style = T.tiny, color = g.ink3, modifier = Modifier.tap { scope.attempt(fail = "Couldn't change the queue") { app.store.clearQueue(key) } }.padding(4.dp))
        }
        Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
            waiting.forEach { q ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (q.status == "paused") g.ink3 else g.accent))
                    Spacer(Modifier.width(8.dp))
                    Text(q.prompt.ifEmpty { "(no text)" }, style = T.small, color = g.ink, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(if (q.status == "paused") "Resume" else "Pause", style = T.tiny, color = g.ink2, modifier = Modifier.tap { scope.attempt(fail = "Couldn't change the queue") { app.store.queueAction(key, q.id, if (q.status == "paused") "resume" else "pause") } }.padding(6.dp))
                    Text("Cancel", style = T.tiny, color = g.ink3, modifier = Modifier.tap { scope.attempt(fail = "Couldn't change the queue") { app.store.queueAction(key, q.id, "cancel") } }.padding(6.dp))
                }
            }
        }
    }
}

@Composable
private fun Transcript(key: ChatKey, bot: Bot?, group: Group?, members: List<Bot>, messages: List<Message>?, error: String, onTakeover: () -> Unit, onLong: (Message) -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val hasMore = s.hasMore[key] == true
    var loadingOlder by remember { mutableStateOf(false) }
    val byId = remember(bot, members) { (if (bot != null) listOf(bot) else members).associateBy { it.id } }

    val rows = remember(messages) {
        val all = messages.orEmpty()
        // A bot's asks from one turn show as one round card.
        val rounds = all.filter { it.role == "event" && it.kind == "botmsg" && it.botId == it.str("from") && it.str("round") != null }
            .groupBy { it.str("round") }.filterValues { it.size > 1 }
        all.mapIndexed { i, m ->
            val prev = all.getOrNull(i - 1); val next = all.getOrNull(i + 1)
            fun same(a: Message?) = a != null && a.role == m.role && a.botId == m.botId && a.str("from") == m.str("from") && a.role != "card" && a.role != "event"
            val gap = prev == null || run {
                val a = parseTime(prev.createdAt); val b = parseTime(m.createdAt)
                a == null || b == null || Duration.between(a, b).toMinutes() > 30
            }
            val round = rounds[m.str("round")]?.takeIf { m.role == "event" && m.botId == m.str("from") }
                ?.let { if (it.first().id == m.id) it else emptyList() }
            ChatRow(m, !same(prev) || gap, !same(next), if (gap) stampOf(m.createdAt) else null, round)
        }.asReversed()
    }
    val typing = (if (bot != null) listOf(bot.id) else members.map { it.id }).filter { id ->
        val st = s.presence[id]?.state
        // In a one-to-one chat the header already says what it is still
        // doing; a thinking row under a finished reply reads as stuck.
        val answered = messages.orEmpty().takeLastWhile { it.role != "user" }.any { it.role == "bot" && it.botId == id }
        (st == "thinking" || st == "working") && (group != null || !answered) && messages.orEmpty().none { it.streaming && it.botId == id }
    }

    // Reverse layout keeps the newest message at the bottom as text streams.
    val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex <= 1 } }
    var below by remember(key) { mutableIntStateOf(0) }
    val lastId = messages?.lastOrNull()?.id
    LaunchedEffect(lastId) { if (!atBottom && lastId != null) below++ else below = 0 }
    LaunchedEffect(atBottom) { if (atBottom) below = 0 }
    // Following the newest row: a drag hands control to the person, and
    // coming back to the bottom hands it back.
    var follow by remember(key) { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> follow = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> follow = !list.canScrollBackward
            }
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { !list.canScrollBackward }.collect { if (it) follow = true }
    }
    // A discrete arrival (a reply, the thinking row) animates into view; a
    // streamed token is followed at once so the text never outruns the view.
    var lastGrow by remember(key) { mutableLongStateOf(0L) }
    LaunchedEffect(lastId, messages?.lastOrNull()?.content?.length, typing.size) {
        if (!follow) return@LaunchedEffect
        val now = System.currentTimeMillis()
        val smooth = now - lastGrow > 250
        lastGrow = now
        if (smooth) list.animateScrollToItem(0) else list.scrollToItem(0)
    }
    LaunchedEffect(key, rows.size) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .filter { it >= list.layoutInfo.totalItemsCount - 3 }
            .collect {
                if (!loadingOlder && app.store.now.hasMore[key] == true) {
                    loadingOlder = true
                    runCatching { app.store.loadOlder(key) }
                    loadingOlder = false
                }
            }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            items(typing, key = { "typing$it" }) { id ->
                val b = byId[id] ?: return@items
                val pr = s.presence[id]
                Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Gbot(b.look, b.id, 32, mood = if (pr?.state == "working") "working" else "thinking")
                    Spacer(Modifier.width(10.dp))
                    Text((if (group != null) "${b.name} · " else "") + (pr?.detail ?: if (pr?.state == "working") "Working" else "Thinking"), style = T.small, color = g.ink3)
                }
            }
            items(rows, key = { it.m.id }) { r ->
                val m = r.m
                val from = m.str("from")?.let { f -> s.bots.find { it.id == f } }
                val author = from ?: m.botId?.let { byId[it] ?: s.bots.find { b -> b.id == it } }
                Column {
                    if (r.stamp != null) Text(r.stamp, style = T.tiny, color = g.ink3, modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp), textAlign = TextAlign.Center)
                    when {
                        m.role == "card" -> if (author != null) Card(m, author, onTakeover)
                        m.role == "event" && m.kind == "activity" -> Activity(m)
                        r.round?.isEmpty() == true -> Unit
                        r.round != null -> RoundCard(r.round, s.bots)
                        m.role == "event" && m.kind == "botmsg" -> Exchange(m, s.bots)
                        m.role == "event" -> EventLine(m, author)
                        else -> Bubble(m, author, r.first, r.last, group != null, onLong)
                    }
                }
            }
            if (messages != null && messages.isEmpty()) item("hello") { if (bot != null) Hello(bot) else GroupHello(group!!, members) }
            if (error.isNotEmpty()) item("err") { Text(error, style = T.small, color = g.danger, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            if (hasMore && !messages.isNullOrEmpty()) item("older") {
                Text(if (loadingOlder) "Loading earlier messages…" else "Load earlier messages", style = T.tiny, color = g.ink3, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().tap { scope.launch { loadingOlder = true; runCatching { app.store.loadOlder(key) }; loadingOlder = false } }.padding(10.dp))
            }
            if (messages == null) items(4) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = if (i % 2 == 0) Arrangement.End else Arrangement.Start) {
                    Box(Modifier.width(if (i % 2 == 0) 180.dp else 260.dp).heightIn(min = if (i % 2 == 0) 36.dp else 60.dp).clip(RoundedCornerShape(18.dp)).background(g.surface))
                }
            }
        }
        if (below > 0) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).shadow(8.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                .background(if (g.dark) Color(0xFF2C2C2C) else Color.White).tap { below = 0; scope.launch { list.animateScrollToItem(0) } }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon("down", size = 14.dp, color = g.ink2)
            Spacer(Modifier.width(6.dp))
            Text(if (below == 1) "1 new message" else "$below new messages", style = T.small, color = g.ink2)
        }
    }
}

@Composable
private fun Bubble(m: Message, author: Bot?, first: Boolean, last: Boolean, inGroup: Boolean, onLong: (Message) -> Unit) {
    val g = LocalG.current
    val fromBot = m.role == "user" && m.str("from") != null
    val mine = m.role == "user" && !fromBot
    val named = inGroup || fromBot
    val failed = m.flag("failed")
    val big = 18.dp; val small = 6.dp
    val shape = if (mine) RoundedCornerShape(big, if (first) big else small, if (last) big else small, big)
    else RoundedCornerShape(if (first) big else small, big, big, if (last) big else small)
    Row(Modifier.fillMaxWidth().padding(top = if (first) 10.dp else 2.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!mine && named) {
            Box(Modifier.width(30.dp)) { if (last && author != null) Gbot(author.look, author.id, 24, mood = if (m.streaming) "working" else "idle") }
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 320.dp)) {
            if (named && first && !mine && author != null) Text(author.name, style = T.tiny, color = g.ink3, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
            Box(
                Modifier.clip(shape).background(if (mine) g.bubbleMe.copy(alpha = if (m.pending) 0.6f else 1f) else g.surface)
                    .then(if (failed) Modifier.border(1.dp, g.danger, shape) else Modifier)
                    .pressable(onLong = { if (!m.pending) onLong(m) }) { if (failed) onLong(m) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Column {
                    if (mine) Text(m.content, style = T.body, color = g.bubbleMeInk)
                    else Markdown(m.content.ifEmpty { " " }, caret = m.streaming)
                    if (failed) Text("Not sent: ${m.str("error").orEmpty()} · Tap to retry", style = T.tiny, color = g.bubbleMeInk.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun Hello(bot: Bot) {
    val g = LocalG.current
    Column(Modifier.fillMaxWidth().padding(top = 80.dp, bottom = 30.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Gbot(bot.look, bot.id, 88, lively = true)
        Spacer(Modifier.size(10.dp))
        Text(bot.name, style = T.h2, color = g.ink)
        Spacer(Modifier.size(4.dp))
        Text("Tell ${bot.name} what its job is, in plain words. It remembers, uses its own computer, and asks before anything risky.", style = T.small, color = g.ink2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GroupHello(group: Group, members: List<Bot>) {
    val g = LocalG.current
    Column(Modifier.fillMaxWidth().padding(top = 80.dp, bottom = 30.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Stack(members, 48)
        Spacer(Modifier.size(10.dp))
        Text(group.name, style = T.h2, color = g.ink)
        Spacer(Modifier.size(4.dp))
        Text("${members.firstOrNull()?.name ?: "The first bot"} leads. @mention anyone to bring them in; they hand work to each other.", style = T.small, color = g.ink2, textAlign = TextAlign.Center)
    }
}

