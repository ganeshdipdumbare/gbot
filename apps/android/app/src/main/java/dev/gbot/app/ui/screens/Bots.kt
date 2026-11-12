package dev.gbot.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.data.groupKey
import dev.gbot.app.local.MAIN_BOT
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Look
import dev.gbot.app.net.Sharing
import dev.gbot.app.net.json
import dev.gbot.app.ui.Confirm
import dev.gbot.app.ui.ErrorText
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.Label
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.LookPicker
import dev.gbot.app.ui.ModelPicker
import dev.gbot.app.ui.Panel
import dev.gbot.app.ui.R
import dev.gbot.app.ui.Screen
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Toggle
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.attempt
import dev.gbot.app.ui.home
import dev.gbot.app.ui.pressable
import dev.gbot.app.ui.randomSeed
import dev.gbot.app.ui.surpriseLook
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Composable
fun BotSettingsScreen(nav: NavHostController, id: String) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val bot = s.bots.find { it.id == id }
    if (bot == null) { Screen("Bot", { nav.popBackStack() }) { Text("This bot isn't available anymore.", style = T.body, color = g.ink2) }; return }
    val scope = rememberCoroutineScope()
    var name by remember(id) { mutableStateOf(bot.name) }
    var title by remember(id) { mutableStateOf(bot.title) }
    var instructions by remember(id) { mutableStateOf(bot.instructions) }
    var model by remember(id) { mutableStateOf(bot.model) }
    var look by remember(id) { mutableStateOf(bot.look) }
    var network by remember(id) { mutableStateOf(bot.computerNetwork) }
    var hosts by remember(id) { mutableStateOf(bot.allowedHosts.joinToString("\n")) }
    var readAloud by remember(id) { mutableStateOf(bot.readAloud) }
    var voice by remember(id) { mutableStateOf(bot.voice) }
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    var serverVoice by remember { mutableStateOf(false) }
    var voiceMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf(false) }
    val canManage = bot.canManage

    LaunchedEffect(Unit) {
        app.store.loadModels()
        serverVoice = app.voice.info().speak
        if (!serverVoice) voices = app.voice.systemVoices().map { it.name }
    }
    // A stored model that is gone (deleted file, removed provider) shows as
    // the account default, which is what the bot actually uses.
    LaunchedEffect(s.models) {
        val list = s.models ?: return@LaunchedEffect
        if (model.isNotBlank() && list.none { it.id == model }) model = ""
    }

    fun save() {
        error = ""
        scope.launch {
            try {
                val body = buildJsonObject {
                    put("name", name.trim()); put("title", title.trim()); put("instructions", instructions); put("model", model)
                    put("look", json.encodeToJsonElement(Look.serializer(), look)); put("computerNetwork", network)
                    putJsonArray("allowedHosts") { hosts.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } }
                    put("readAloud", readAloud); put("voice", voice)
                }
                app.store.upsertBot(app.api.updateBot(bot.id, body))
                app.toast("Saved")
                saved = true; delay(1400); saved = false
            } catch (e: Exception) { error = e.message ?: "Couldn't save" }
        }
    }

    Screen(bot.name, { nav.popBackStack() }, footer = if (canManage) ({
        Row(verticalAlignment = Alignment.CenterVertically) {
            GButton("Save changes", ::save, Modifier.weight(1f), enabled = name.isNotBlank())
            Spacer(Modifier.width(8.dp))
            GButton(if (bot.pinned) "Unpin" else "Pin", {
                scope.attempt(if (bot.pinned) "Unpinned" else "Pinned") { app.store.upsertBot(app.api.updateBot(bot.id, buildJsonObject { put("pinned", !bot.pinned) })) }
            }, variant = Variant.Soft, icon = "pin")
        }
    }) else null) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Gbot(look, bot.id, 96, mood = if (saved) "happy" else "idle", lively = true) }
        if (!canManage) Panel { Text("Shared with you. Only its owner and managers can change how it works.", style = T.small, color = g.ink2) }
        Column { Label("Name"); Field(name, { name = it.take(40) }, enabled = canManage) }
        Column { Label("Job"); Field(title, { title = it.take(80) }, placeholder = "What they do", enabled = canManage) }
        Column { Label("Instructions", "You can also just tell them in chat"); Field(instructions, { instructions = it }, singleLine = false, minLines = 5, placeholder = "e.g. \"You help me plan my week. Keep answers short and bubbly.\"", enabled = canManage) }
        Column {
            Label("Model", "Account default follows Settings → Models")
            ModelPicker(s.models.orEmpty(), model, { model = it }, "Account default", fallback = s.me?.defaultModel, enabled = canManage)
        }
        Panel {
            if (app.mode == Mode.Server) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon("globe", color = g.ink2)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Internet on its computer", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
                        Text("Off keeps its computer fully offline.", style = T.small, color = g.ink2)
                    }
                    Toggle(network, { network = it }, enabled = canManage)
                }
                if (network) Column {
                    Label("Only these sites", "One per site; subdomains included")
                    Field(hosts, { hosts = it }, singleLine = false, minLines = 3, placeholder = "linkedin.com\nindeed.com", keyboard = KeyboardType.Uri, enabled = canManage)
                    Text("Empty allows the whole internet. This checks the pages it visits, not the ads, CDNs or assets a page loads. Shell commands still use the network.", style = T.tiny, color = g.ink3, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon("speaker", color = g.ink2)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Read replies aloud", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
                    Text(if (serverVoice) "Uses the server's voice." else "Uses this phone's voices.", style = T.small, color = g.ink2)
                }
                Toggle(readAloud, { readAloud = it }, enabled = canManage)
            }
            if (!serverVoice && voices.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon("mic", color = g.ink2)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    Text(voice.ifEmpty { "Default voice" }, style = T.body, color = g.ink, maxLines = 1, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(g.surface2).pressable(canManage) { voiceMenu = true }.padding(12.dp))
                    DropdownMenu(voiceMenu, { voiceMenu = false }) {
                        DropdownMenuItem({ Text("Default voice") }, { voice = ""; voiceMenu = false })
                        voices.forEach { v -> DropdownMenuItem({ Text(v) }, { voice = v; voiceMenu = false }) }
                    }
                }
                Spacer(Modifier.width(6.dp))
                GButton("Try", { scope.launch { runCatching { app.voice.speak("Hi, I'm $name.", voice) } } }, variant = Variant.Soft, small = true, icon = "play")
            }
        }
        if (canManage) Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Look", style = T.label, color = g.ink, modifier = Modifier.weight(1f))
                GButton("Surprise me", { look = surpriseLook() }, variant = Variant.Ghost, small = true, icon = "dice")
            }
            Spacer(Modifier.size(8.dp))
            LookPicker(look, bot.id) { look = it }
        }
        ErrorText(error)
        if (canManage && app.mode == Mode.Server) ShareSection(bot)
        if (bot.isOwner && bot.id != MAIN_BOT) GButton("Archive bot", { archiving = true }, variant = Variant.Danger)
    }
    if (archiving) Confirm("Archive ${bot.name}?", "Their chat is kept.", "Archive", onConfirm = {
        scope.attempt("${bot.name} archived") { app.api.archiveBot(bot.id); app.store.removeBot(bot.id); nav.home() }
    }, onDismiss = { archiving = false })
}

private val visibilities = listOf(
    Triple("private", "Only me", "Only you can use this bot."),
    Triple("team", "Whole team", "Everyone on the team can chat with it. Each chat stays private."),
    Triple("invited", "People you invite", "Only the people you add below can chat with it."),
)

/** Who can use a bot, for its owner and managers. */
@Composable
private fun ShareSection(bot: Bot) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Sharing?>(null) }
    var email by remember { mutableStateOf("") }
    var asRole by remember { mutableStateOf("collaborator") }
    LaunchedEffect(bot.id) { runCatching { app.api.sharing(bot.id) }.onSuccess { data = it } }
    val d = data ?: return
    fun run(ok: String, fn: suspend () -> Sharing) = scope.attempt(ok) { data = fn() }
    Panel {
        Text("Who can use it", style = T.label, color = g.ink)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            visibilities.forEach { (id, label, _) ->
                Text(label, style = T.small.copy(fontWeight = if (d.visibility == id) FontWeight.SemiBold else null), color = if (d.visibility == id) g.ink else g.ink2,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (d.visibility == id) g.surface2 else g.bg).pressable { run("Sharing updated") { app.api.setVisibility(bot.id, id) } }.padding(10.dp))
            }
        }
        Text(visibilities.find { it.first == d.visibility }?.third.orEmpty(), style = T.tiny, color = g.ink2)
        if (d.visibility == "invited") {
            Text("People · managers can change the bot, collaborators can only chat", style = T.tiny, color = g.ink3)
            if (d.members.isEmpty()) Text("Nobody yet.", style = T.small, color = g.ink3)
            d.members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(m.name, style = T.body, color = g.ink); Text("${m.email} · ${m.role}", style = T.tiny, color = g.ink3) }
                    if (bot.isOwner || m.role != "manager") GButton("Remove", { run("Removed") { app.api.removeBotMember(bot.id, m.userId) } }, variant = Variant.Ghost, small = true)
                }
            }
            val notMembers = d.spaceMembers.filter { sm -> sm.role != "owner" && d.members.none { it.userId == sm.userId } }
            if (notMembers.isNotEmpty()) {
                Text("Team members you can add", style = T.tiny, color = g.ink2)
                notMembers.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(m.name, style = T.body, color = g.ink); Text(m.email, style = T.tiny, color = g.ink3) }
                        GButton("Add", { run("Added ${m.name}") { app.api.addBotMember(bot.id, m.email, asRole) } }, variant = Variant.Soft, small = true)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("collaborator" to "Collaborator", "manager" to "Manager").forEach { (r, l) -> dev.gbot.app.ui.Chip(l, asRole == r, { asRole = r }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Field(email, { email = it }, Modifier.weight(1f), placeholder = "teammate@example.com", keyboard = KeyboardType.Email)
                Spacer(Modifier.width(8.dp))
                GButton("Add", { val e = email.trim(); scope.attempt("Added") { data = app.api.addBotMember(bot.id, e, asRole); email = "" } }, variant = Variant.Soft, small = true, enabled = email.isNotBlank())
            }
        }
    }
}

@Composable
fun GroupSettingsScreen(nav: NavHostController, id: String) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var archiving by remember { mutableStateOf(false) }
    val group = s.groups.find { it.id == id }
    if (group == null) { Screen("Group", { nav.popBackStack() }) { Text("This group isn't available anymore.", style = T.body, color = g.ink2) }; return }
    val members = group.botIds.mapNotNull { b -> s.bots.find { it.id == b } }
    Screen(group.name, { nav.popBackStack() }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Stack(members, 56) }
        Panel {
            members.forEachIndexed { i, b ->
                Row(Modifier.clip(RoundedCornerShape(12.dp)).pressable { nav.navigate(R.bot(b.id)) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Gbot(b.look, b.id, 36)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row { Text(b.name, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink); if (i == 0) Text("  LEAD", style = T.tiny.copy(fontWeight = FontWeight.Bold), color = g.live) }
                        Text(b.title.ifEmpty { "Teammate" }, style = T.small, color = g.ink2)
                    }
                }
            }
        }
        Text("Each bot keeps its own memory. The group's chat is shared by everyone in it.", style = T.small, color = g.ink2)
        GButton("Archive group", { archiving = true }, variant = Variant.Danger)
    }
    if (archiving) Confirm("Archive this group?", "Its chat is kept.", "Archive", onConfirm = {
        scope.attempt("Group archived") { app.api.archiveGroup(group.id); app.store.removeGroup(group.id); nav.home() }
    }, onDismiss = { archiving = false })
}

private val names = listOf("Pip", "Nori", "Juno", "Otto", "Kiwi", "Momo", "Taro", "Ziggy", "Remy", "Bo", "Luma", "Sol", "Echo", "Pixel", "Mika")
private val jobs = listOf("Inbox helper", "Research", "Writing", "Code reviews", "Trip planning", "Study coach")

@Composable
fun HatchScreen(nav: NavHostController) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val seed = remember { randomSeed() }
    var look by remember { mutableStateOf(Look(seed = seed)) }
    var name by remember { mutableStateOf(names.random()) }
    var title by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf("idle") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var poke by remember { mutableStateOf(0) }
    LaunchedEffect(poke) { if (poke > 0) { mood = "happy"; delay(700); mood = "idle" } }

    fun create() {
        busy = true; error = ""
        scope.launch {
            try {
                val b = app.api.createBot(name.trim(), title.trim(), look.copy(seed = look.seed ?: seed))
                app.store.upsertBot(b)
                nav.navigate(R.chat(b.id)) { popUpTo(R.HOME) }
            } catch (e: Exception) { error = e.message ?: "Couldn't create it"; mood = "blocked" } finally { busy = false }
        }
    }

    Screen("New bot", { nav.popBackStack() }, footer = { GButton(if (busy) "Creating…" else "Create ${name.trim().ifEmpty { "bot" }}", ::create, Modifier.fillMaxWidth(), enabled = !busy && name.isNotBlank()) }) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.clip(CircleShape).pressable { poke++ }) { Gbot(look, seed, 132, mood = mood, lively = true) }
            GButton("Surprise me", { look = surpriseLook(); poke++ }, Modifier.align(Alignment.TopEnd), variant = Variant.Ghost, small = true, icon = "dice")
        }
        Column { Label("Name"); Field(name, { name = it.take(40); poke++ }, ime = ImeAction.Next) }
        Column { Label("Job", "optional"); Field(title, { title = it.take(80); poke++ }, placeholder = jobs[name.length % jobs.size]) }
        LookPicker(look, seed) { look = it; poke++ }
        LaunchedEffect(Unit) { app.store.loadModels() }
        val models = s.models
        if (app.mode == Mode.Local && models != null && models.isEmpty()) Panel {
            Text("No model on this phone yet", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
            Text("Download one in Settings → Models, or connect a provider. Your bot can be created now and will answer once a model is ready.", style = T.small, color = g.ink2)
            GButton("Open models", { nav.navigate(R.settings("models")) }, variant = Variant.Soft, small = true)
        }
        ErrorText(error)
    }
}

@Composable
fun NewGroupScreen(nav: NavHostController) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf("") }
    val chosen = picked.mapNotNull { id -> s.bots.find { it.id == id } }

    Screen("New group", { nav.popBackStack() }, footer = {
        GButton("Create group", {
            error = ""
            scope.launch {
                try {
                    val gr = app.api.createGroup(name.trim().ifEmpty { chosen.joinToString(", ") { it.name } }, picked)
                    app.store.upsertGroup(gr)
                    nav.navigate(R.chat(groupKey(gr.id))) { popUpTo(R.HOME) }
                } catch (e: Exception) { error = e.message ?: "Couldn't create the group" }
            }
        }, Modifier.fillMaxWidth(), enabled = picked.size >= 2)
    }) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            if (chosen.isNotEmpty()) Stack(chosen, 56) else Icon("users", size = 40.dp, color = g.ink3)
        }
        Column { Label("Name", "optional"); Field(name, { name = it.take(40) }, placeholder = "Launch team") }
        Label("Bots", "${picked.size}/6 · the first one leads")
        if (s.bots.size < 2) Text("Create at least two bots first.", style = T.body, color = g.ink2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.bots.forEach { b ->
                val i = picked.indexOf(b.id)
                Box(
                    Modifier.width(96.dp).clip(RoundedCornerShape(18.dp)).background(if (i >= 0) g.surface2 else g.surface)
                        .pressable { picked = if (i >= 0) picked - b.id else if (picked.size >= 6) picked else picked + b.id }.padding(10.dp),
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Gbot(b.look, b.id, 44, mood = if (i >= 0) "happy" else "idle")
                        Text(b.name, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = g.ink, maxLines = 1)
                    }
                    if (i >= 0) Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(g.emphasis), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = T.tiny.copy(fontWeight = FontWeight.Bold), color = g.onEmphasis)
                    }
                }
            }
        }
        ErrorText(error)
    }
}

