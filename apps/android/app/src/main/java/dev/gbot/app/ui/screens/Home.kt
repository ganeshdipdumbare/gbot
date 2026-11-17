package dev.gbot.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import dev.gbot.app.ui.Motion
import dev.gbot.app.ui.shimmer
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.data.groupKey
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Group
import dev.gbot.app.net.Look
import dev.gbot.app.net.Presence
import dev.gbot.app.ui.Confirm
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.GbotLogo
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.R
import dev.gbot.app.ui.SectionTitle
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.attempt
import dev.gbot.app.ui.hues
import dev.gbot.app.ui.moodOf
import dev.gbot.app.ui.presenceText
import dev.gbot.app.ui.pressable
import dev.gbot.app.ui.resolveLook
import dev.gbot.app.ui.timeAgoLive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalTime

fun hueOf(b: Bot): Color = Color(hues.getValue(resolveLook(b.look, b.id).hue).body)
fun isBusy(p: Presence?) = p != null && p.state != "idle" && p.state != "done"
fun statusOf(p: Presence) = if (p.state == "working" && !p.detail.isNullOrEmpty()) p.detail else presenceText[p.state].orEmpty()
fun preview(s: String?) = s?.replace(Regex("[#*_`>]"), "")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

private fun greeting(): String {
    val h = LocalTime.now().hour
    return when { h < 5 -> "Up late"; h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
}

/** A stack of up to three faces, for groups. */
@Composable
fun Stack(bots: List<Bot>, size: Int, presence: Map<String, Presence>? = null) {
    val shown = bots.take(3)
    Box(Modifier.width((size + (shown.size - 1).coerceAtLeast(0) * size * 0.55f).dp).height(size.dp)) {
        shown.forEachIndexed { i, b ->
            Gbot(b.look, b.id, size, Modifier.offset(x = (i * size * 0.55f).dp), mood = presence?.let { moodOf(it[b.id], b) } ?: "idle")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Bot?>(null) }
    var renaming by remember { mutableStateOf<Bot?>(null) }
    var deleting by remember { mutableStateOf<Bot?>(null) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    val clip = LocalClipboardManager.current

    // Coming back from a chat should not refetch the roster: the live
    // stream keeps it fresh, and a reload mid-transition is what makes a
    // pop stutter. Only the first visit loads.
    LaunchedEffect(Unit) {
        if (!app.store.now.loaded) runCatching { app.store.loadAll() }.onFailure { app.toast("Couldn't load your bots", true) }
        if (app.store.now.models.isNullOrEmpty()) app.store.loadModels()
    }

    val initials = (s.me?.name ?: "").split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { (s.me?.email?.firstOrNull()?.uppercaseChar() ?: '?').toString() }
    val visible = s.bots.filter { it.id !in s.hidden }
    val mainBot = s.bots.find { it.isMain }
    val hidden = s.bots.filter { it.id in s.hidden }
    val needle = q.trim().lowercase()
    fun match(vararg xs: String?) = needle.isEmpty() || xs.any { it?.lowercase()?.contains(needle) == true }
    val bots = visible.filter { match(it.name, it.title) }
    val groups = s.groups.filter { match(it.name) }
    val open = { k: String -> nav.navigate(R.chat(k)) }
    val hair = g.line.copy(alpha = g.line.alpha * 0.7f)

    Column(Modifier.fillMaxSize().background(g.chrome).statusBarsPadding()) {
        // Header, as the web sidebar: name, new group, new bot.
        Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                GbotLogo(38) { mainBot?.let { nav.navigate(R.chat(it.id)) } }
                Text("gbot", style = T.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.3).sp), color = g.ink)
            }
            IconBtn("users", "New group", { nav.navigate(R.NEW_GROUP) })
            IconBtn("plus", "New bot", { nav.navigate(R.HATCH) }, tint = g.ink)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp).height(40.dp).clip(RoundedCornerShape(12.dp))
                .background(g.surface).border(1.dp, g.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon("search", size = 16.dp, color = g.ink3)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search", style = T.body.copy(fontSize = 14.5.sp), color = g.ink3)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = T.body.copy(color = g.ink, fontSize = 14.5.sp), cursorBrush = SolidColor(g.live), modifier = Modifier.fillMaxWidth())
            }
            if (q.isNotEmpty()) IconBtn("close", "Clear search", { q = "" }, size = 28.dp)
        }
        PullToRefreshBox(refreshing, onRefresh = { refreshing = true; scope.attempt { try { app.store.loadAll() } finally { refreshing = false } } }, Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 12.dp)) {
                if (!s.loaded) items(5) { SkeletonRow() }
                if (s.loaded && s.bots.isEmpty() && s.groups.isEmpty()) item("empty") { EmptyRoster { nav.navigate(R.HATCH) } }
                if (s.loaded && needle.isNotEmpty() && bots.isEmpty() && groups.isEmpty()) item("nomatch") {
                    Text("Nothing matches “$q”.", style = T.small, color = g.ink2, modifier = Modifier.fillMaxWidth().padding(top = 32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                items(bots, key = { it.id }) { b ->
                    // A bot that starts working moves up the roster; let it
                    // travel instead of jumping.
                    Box(Modifier.animateItem(fadeInSpec = Motion.quick(), placementSpec = Motion.placement, fadeOutSpec = Motion.fast())) {
                        BotRow(b, s.presence[b.id], s.computers[b.id]?.state == "on", s.unread[b.id] ?: 0, onClick = { open(b.id) }, onLong = { menu = b })
                    }
                }
                if (groups.isNotEmpty()) item("h:groups") {
                    Text("Groups", style = T.tiny, color = g.ink3, modifier = Modifier.padding(start = 10.dp, top = 16.dp, bottom = 4.dp))
                }
                items(groups, key = { "g:" + it.id }) { gr ->
                    Box(Modifier.animateItem(fadeInSpec = Motion.quick(), placementSpec = Motion.placement, fadeOutSpec = Motion.fast())) {
                        GroupRow(gr, s.bots, s.presence, s.unread[groupKey(gr.id)] ?: 0, onClick = { open(groupKey(gr.id)) }, onLong = { nav.navigate(R.group(gr.id)) })
                    }
                }
            }
        }
        // Footer, as the web sidebar: hidden bots, integrations, you.
        Box(Modifier.fillMaxWidth().height(1.dp).background(hair))
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (hidden.isNotEmpty()) FooterRow({ Icon("eye", size = 15.dp, color = g.ink2) }, "Hidden (${hidden.size})") { showHidden = true }
            FooterRow({ Icon("plug", size = 15.dp, color = g.ink2) }, "Integrations") { nav.navigate(R.settings("integrations")) }
            Row(Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(12.dp)).pressable { nav.navigate(R.SETTINGS) }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(g.surface2), contentAlignment = Alignment.Center) {
                    Text(initials, style = T.tiny.copy(fontWeight = FontWeight.Medium), color = g.ink2)
                }
                Spacer(Modifier.width(12.dp))
                Text(s.me?.name.orEmpty().ifEmpty { s.me?.email.orEmpty() }, style = T.body.copy(fontSize = 14.5.sp), color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!s.connected && s.loaded) Text("Reconnecting", style = T.tiny, color = g.warning, modifier = Modifier.padding(end = 8.dp))
                Icon("gear", size = 17.dp, color = g.ink3)
            }
        }
    }

    menu?.let { b ->
        ModalBottomSheet(onDismissRequest = { menu = null }, containerColor = if (g.dark) Color(0xFF262626) else Color.White) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Gbot(b.look, b.id, 44)
                    Spacer(Modifier.width(10.dp))
                    Column { Text(b.name, style = T.h2, color = g.ink); if (b.title.isNotEmpty()) Text(b.title, style = T.small, color = g.ink2) }
                }
                Spacer(Modifier.height(6.dp))
                SheetItem("pin", if (b.pinned) "Unpin" else "Pin") {
                    menu = null; scope.attempt(fail = "Couldn't pin") { app.store.upsertBot(app.api.updateBot(b.id, buildJsonObject { put("pinned", !b.pinned) })) }
                }
                SheetItem("unread", "Mark as unread") { menu = null; app.store.markUnread(b.id) }
                SheetItem("pencil", "Rename") { menu = null; renaming = b }
                SheetItem("gear", "Settings") { menu = null; nav.navigate(R.bot(b.id)) }
                SheetItem("screen", "Computer and routines") { menu = null; nav.navigate(R.computer(b.id)) }
                SheetItem("copy", "Copy conversation ID") { menu = null; clip.setText(AnnotatedString(b.id)); app.toast("Copied conversation ID") }
                SheetItem("eyeOff", "Hide") { menu = null; app.store.setHidden(s.hidden + b.id) }
                if (b.isOwner && !b.isMain) SheetItem("trash", "Delete", danger = true) { menu = null; deleting = b }
            }
        }
    }
    renaming?.let { b ->
        var text by remember(b.id) { mutableStateOf(b.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename bot", style = T.h2) },
            text = { Field(text, { text = it.take(40) }, placeholder = "Name") },
            confirmButton = {
                androidx.compose.material3.TextButton({
                    val name = text.trim()
                    if (name.isNotEmpty()) scope.attempt("Renamed", "Couldn't rename") { app.store.upsertBot(app.api.updateBot(b.id, buildJsonObject { put("name", name) })); renaming = null }
                }) { Text("Save", color = g.ink, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { androidx.compose.material3.TextButton({ renaming = null }) { Text("Cancel", color = g.ink2) } },
            containerColor = if (g.dark) Color(0xFF2A2A2A) else Color.White,
        )
    }
    deleting?.let { b ->
        Confirm("Delete ${b.name}?", "Their chat is kept.", "Delete", onConfirm = {
            scope.attempt("${b.name} deleted", "Couldn't delete") { app.api.archiveBot(b.id); app.store.removeBot(b.id) }
        }, onDismiss = { deleting = null })
    }
    if (showHidden) ModalBottomSheet(onDismissRequest = { showHidden = false }, containerColor = if (g.dark) Color(0xFF262626) else Color.White) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hidden bots", style = T.h2, color = g.ink)
            if (hidden.isEmpty()) Text("No hidden bots.", style = T.body, color = g.ink2)
            hidden.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Gbot(b.look, b.id, 44, mood = moodOf(s.presence[b.id], b))
                    Spacer(Modifier.width(10.dp))
                    Text(b.name, style = T.body, color = g.ink, modifier = Modifier.weight(1f))
                    GButton("Show", { app.store.setHidden(s.hidden - b.id) }, variant = Variant.Soft, small = true)
                }
            }
        }
    }
}

@Composable
private fun FooterRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(CircleShape).border(1.dp, g.line, CircleShape), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(12.dp))
        Text(label, style = T.body.copy(fontSize = 14.5.sp), color = g.ink2)
    }
}

@Composable
private fun SheetItem(icon: String, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, size = 20.dp, color = if (danger) g.danger else g.ink2)
        Spacer(Modifier.width(14.dp))
        Text(label, style = T.body, color = if (danger) g.danger else g.ink)
    }
}

@Composable
private fun Badge(n: Int) {
    val g = LocalG.current
    if (n <= 0) return
    Box(Modifier.widthIn(min = 18.dp).height(18.dp).clip(RoundedCornerShape(50)).background(g.live).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Text(if (n > 99) "99+" else n.toString(), color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RowShell(onClick: () -> Unit, onLong: () -> Unit, avatar: @Composable () -> Unit, name: String, pinned: Boolean, time: String, line: String, lineColor: Color, bold: Boolean, unread: Int) {
    val g = LocalG.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onLong = onLong, onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) { avatar() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = T.body.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp), color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (pinned) { Spacer(Modifier.width(5.dp)); Icon("pin", size = 12.dp, color = g.ink3) }
                }
                if (time.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text(time, style = T.tiny, color = g.ink3) }
            }
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    line,
                    transitionSpec = { (fadeIn(Motion.quick()) + slideInVertically(Motion.quick()) { it / 3 }) togetherWith fadeOut(Motion.fast()) },
                    label = "status",
                    modifier = Modifier.weight(1f),
                ) { text ->
                    Text(text, style = T.small.copy(fontSize = 13.5.sp, fontWeight = if (bold) FontWeight.Medium else null), color = lineColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (unread > 0) { Spacer(Modifier.width(8.dp)); Badge(unread) }
            }
        }
    }
}

@Composable
fun BotRow(b: Bot, p: Presence?, computerOn: Boolean, unread: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val g = LocalG.current
    val busy = isBusy(p)
    val blocked = p?.state == "blocked"
    RowShell(
        onClick, onLong, { Gbot(b.look, b.id, 52, mood = moodOf(p, b), live = p?.live == true || computerOn, sharedKey = "bot:${b.id}") },
        b.name, b.pinned, timeAgoLive(b.lastMessage?.createdAt),
        if (busy) statusOf(p!!) else preview(b.lastMessage?.content).ifEmpty { b.title.ifEmpty { "General-purpose bot" } },
        if (busy) (if (blocked) g.danger else g.live) else if (unread > 0) g.ink else g.ink2, busy || unread > 0, unread,
    )
}

@Composable
private fun GroupRow(gr: Group, bots: List<Bot>, presence: Map<String, Presence>, unread: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val g = LocalG.current
    val members = gr.botIds.mapNotNull { id -> bots.find { it.id == id } }
    val working = members.find { presence[it.id]?.state in listOf("thinking", "working") }
    RowShell(
        onClick, onLong, { Stack(members, 28, presence) }, gr.name, false, timeAgoLive(gr.lastMessage?.createdAt),
        if (working != null) "${working.name} is ${presence[working.id]?.state}…" else preview(gr.lastMessage?.content).ifEmpty { members.joinToString(", ") { it.name } },
        if (working != null) g.live else if (unread > 0) g.ink else g.ink2, working != null || unread > 0, unread,
    )
}

@Composable
private fun SkeletonRow() {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).shimmer().clip(CircleShape).background(g.surface2))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.shimmer(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(110.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(g.surface2))
            Box(Modifier.width(180.dp).height(10.dp).clip(RoundedCornerShape(6.dp)).background(g.surface2))
        }
    }
}

@Composable
private fun EmptyRoster(onHatch: () -> Unit) {
    val g = LocalG.current
    Column(Modifier.fillMaxWidth().padding(top = 48.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Gbot(Look(shape = "cloud", hue = "sky", eyes = "wide"), "hello", 120, mood = "happy", lively = true)
        Text("Meet your first bot", style = T.title.copy(fontSize = 22.sp), color = g.ink)
        Text("Give it a name and a job. It remembers, uses its own computer, and asks before anything risky.", style = T.body, color = g.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        GButton("Hatch a bot", onHatch)
    }
}
