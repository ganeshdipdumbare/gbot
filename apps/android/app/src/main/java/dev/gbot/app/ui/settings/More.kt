package dev.gbot.app.ui.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.net.McpServer
import dev.gbot.app.net.Member
import dev.gbot.app.net.Skill
import dev.gbot.app.net.Space
import dev.gbot.app.net.VaultSecret
import dev.gbot.app.net.VoiceInfo
import dev.gbot.app.ui.Chip
import dev.gbot.app.ui.Confirm
import dev.gbot.app.ui.Empty
import dev.gbot.app.ui.ErrorText
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.Label
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.Panel
import dev.gbot.app.ui.Popup
import dev.gbot.app.ui.Screen
import dev.gbot.app.ui.SectionTitle
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Toggle
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.attempt
import dev.gbot.app.ui.chat.SkillCache
import dev.gbot.app.ui.pressable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Team ---------------------------------------------------------------

@Composable
fun Team(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var spaces by remember { mutableStateOf<List<Space>>(emptyList()) }
    var current by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<Member>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    suspend fun load() { runCatching { app.api.spaces() }.onSuccess { spaces = it.spaces; current = it.current } }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(current) { if (current.isNotEmpty()) members = runCatching { app.api.spaceMembers(current).members }.getOrDefault(emptyList()) }
    val active = spaces.find { it.id == current }
    // A team comes with its own bots, so switching reloads everything.
    suspend fun switchTo(id: String) {
        app.api.selectSpace(id)
        app.store.reset()
        app.signedIn(app.api.me())
        app.store.loadAll()
        load()
        app.toast("Switched team")
    }
    Screen("Team", back) {
        if (app.mode == Mode.Local) {
            Panel {
                Text("Teams are for sharing bots between people, so they need your gbot server. Everything on this phone is yours alone.", style = T.small, color = g.ink2)
                GButton("Connect my server", { app.useServer(); back() }, variant = Variant.Soft, small = true, icon = "globe")
            }
            return@Screen
        }
        SectionTitle("Your teams")
        Text("Bots shared with a team are visible to its members.", style = T.small, color = g.ink2)
        spaces.forEach { sp ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(sp.name, style = T.body, color = g.ink); Text(sp.role, style = T.tiny, color = g.ink3) }
                if (sp.id == current) Text("Current", style = T.small, color = g.ink2)
                else GButton("Switch", { scope.attempt { switchTo(sp.id) } }, variant = Variant.Soft, small = true)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(name, { name = it.take(40) }, Modifier.weight(1f), placeholder = "New team name")
            Spacer(Modifier.width(8.dp))
            GButton("Create", { val n = name.trim(); scope.attempt { val sp = app.api.createSpace(n); name = ""; switchTo(sp.id) } }, variant = Variant.Soft, small = true, enabled = name.isNotBlank())
        }
        if (active != null) {
            SectionTitle("Members of ${active.name}")
            if (active.role == "owner") Text("You can add anyone who has signed up.", style = T.small, color = g.ink2)
            members.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(m.name, style = T.body, color = g.ink); Text("${m.email} · ${m.role}", style = T.tiny, color = g.ink3) }
                    if (active.role == "owner" && m.role != "owner") GButton("Remove", { scope.attempt("Removed") { members = app.api.removeSpaceMember(current, m.userId).members } }, variant = Variant.Ghost, small = true)
                }
            }
            if (active.role == "owner") Row(verticalAlignment = Alignment.CenterVertically) {
                Field(email, { email = it }, Modifier.weight(1f), placeholder = "teammate@example.com", keyboard = KeyboardType.Email)
                Spacer(Modifier.width(8.dp))
                GButton("Add", { val e = email.trim(); scope.attempt("Added") { members = app.api.addSpaceMember(current, e).members; email = "" } }, variant = Variant.Soft, small = true, enabled = email.isNotBlank())
            }
        }
    }
}

// ---- Integrations -------------------------------------------------------

private data class CatalogApp(val name: String, val blurb: String, val url: String, val tint: Long, val category: String, val token: Boolean = false, val open: Boolean = false)
private val categories = listOf("Work", "Design", "Data", "Developer", "Finance")
private val catalog = listOf(
    CatalogApp("Notion", "Pages, databases and notes", "https://mcp.notion.com/mcp", 0xFF1F1F1F, "Work"),
    CatalogApp("Linear", "Issues, projects and cycles", "https://mcp.linear.app/mcp", 0xFF5E6AD2, "Work"),
    CatalogApp("Jira", "Issues and Confluence pages", "https://mcp.atlassian.com/v1/mcp", 0xFF0052CC, "Work"),
    CatalogApp("Monday", "Boards, items and timelines", "https://mcp.monday.com/mcp", 0xFF6161FF, "Work"),
    CatalogApp("ClickUp", "Tasks, docs and goals", "https://mcp.clickup.com/mcp", 0xFF7B68EE, "Work"),
    CatalogApp("Airtable", "Bases, records and views", "https://mcp.airtable.com/mcp", 0xFFF82B60, "Work"),
    CatalogApp("Attio", "CRM records and pipelines", "https://mcp.attio.com/mcp", 0xFF2B2B2B, "Work"),
    CatalogApp("Zapier", "Automate thousands of apps", "https://mcp.zapier.com/api/v1/connect", 0xFFFF4F00, "Work"),
    CatalogApp("Calendly", "Schedule meetings", "https://mcp.calendly.com/", 0xFF006BFF, "Work"),
    CatalogApp("Buffer", "Schedule social posts", "https://mcp.buffer.com/mcp", 0xFF168EEA, "Work"),
    CatalogApp("Canva", "Designs, folders and assets", "https://mcp.canva.com/mcp", 0xFF00C4CC, "Design"),
    CatalogApp("Miro", "Boards, frames and stickies", "https://mcp.miro.com/mcp", 0xFF4262FF, "Design"),
    CatalogApp("Webflow", "Sites, pages and CMS items", "https://mcp.webflow.com/mcp", 0xFF146EF5, "Design"),
    CatalogApp("Figma", "Inspect designs and files", "https://mcp.figma.com/mcp", 0xFFF24E1E, "Design"),
    CatalogApp("Wix", "Manage your Wix site", "https://mcp.wix.com/mcp", 0xFF0C6EFC, "Design"),
    CatalogApp("Gamma", "Generate presentations", "https://mcp.gamma.app/mcp", 0xFF6C3BF2, "Design"),
    CatalogApp("HeyGen", "Create AI videos", "https://mcp.heygen.com/mcp", 0xFF6D5DF6, "Design"),
    CatalogApp("Excalidraw", "Sketch diagrams", "https://mcp.excalidraw.com/mcp", 0xFF6965DB, "Design", open = true),
    CatalogApp("Supabase", "Projects, tables and SQL", "https://mcp.supabase.com/mcp", 0xFF1F9D6B, "Data"),
    CatalogApp("Amplitude", "Events, charts and cohorts", "https://mcp.amplitude.com/mcp", 0xFF1E61F0, "Data"),
    CatalogApp("Mixpanel", "Query product analytics", "https://mcp.mixpanel.com/mcp", 0xFF7856FF, "Data"),
    CatalogApp("Metricool", "Social and ad analytics", "https://ai.metricool.com/mcp", 0xFF6F4EF2, "Data"),
    CatalogApp("Parallel", "Web search and research", "https://mcp.parallel.ai/mcp", 0xFF1F2937, "Data", open = true),
    CatalogApp("GitHub", "Repos, issues and PRs", "https://api.githubcopilot.com/mcp/", 0xFF24292F, "Developer", token = true),
    CatalogApp("Sentry", "Errors, projects and releases", "https://mcp.sentry.dev/mcp", 0xFF362D59, "Developer"),
    CatalogApp("Postman", "Collections and APIs", "https://mcp.postman.com/mcp", 0xFFFF6C37, "Developer"),
    CatalogApp("Vercel", "Manage your deploys", "https://mcp.vercel.com/", 0xFF000000, "Developer"),
    CatalogApp("Netlify", "Manage your deploys", "https://mcp.netlify.com/mcp", 0xFF00C7B7, "Developer"),
    CatalogApp("Datadog", "Metrics and logs", "https://mcp.datadoghq.com/api/unstable/mcp-server/mcp", 0xFF632CA6, "Developer"),
    CatalogApp("Stripe", "Payments, customers and invoices", "https://mcp.stripe.com", 0xFF635BFF, "Finance"),
    CatalogApp("PayPal", "Transactions and invoices", "https://mcp.paypal.com/mcp", 0xFF003087, "Finance"),
    CatalogApp("Finary", "Track your portfolio", "https://public-api.finary.com/mcp", 0xFF1A1A2E, "Finance"),
    CatalogApp("Scalable Capital", "Track your investments", "https://mcp.scalable.capital/mcp", 0xFF00B389, "Finance"),
    CatalogApp("Whop", "Communities and payments", "https://mcp.whop.com/mcp", 0xFFFF6243, "Finance"),
    CatalogApp("Daloopa", "Financial model data", "https://mcp.daloopa.com/mcp", 0xFF1F3A93, "Finance"),
)
private const val PHONE = "On this phone"
private fun sameUrl(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')
private fun iconRes(name: String) = "ic_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

/** The app's own logo on a white rounded tile, or its first letter. */
@Composable
private fun AppIcon(name: String, tint: Long, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val id = remember(name) { ctx.resources.getIdentifier(iconRes(name), "drawable", ctx.packageName) }
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color.White), contentAlignment = Alignment.Center) {
        if (id != 0) Image(painterResource(id), contentDescription = null, modifier = Modifier.size(size * 0.68f))
        else Text(name.take(1), color = Color(tint), fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun Integrations(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<McpServer>?>(null) }
    var search by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("All") }
    var connecting by remember { mutableStateOf<CatalogApp?>(null) }
    var authUrl by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) } // name, url, fromCatalog
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    suspend fun load() { list = runCatching { app.api.mcp().servers }.getOrDefault(list ?: emptyList()) }
    LaunchedEffect(Unit) { load() }
    // "On this phone": the apps already here, switched on one by one.
    var phoneTick by remember { mutableStateOf(0) }
    var pendingPhone by remember { mutableStateOf<dev.gbot.app.local.PhoneApps.App?>(null) }
    val askPermissions = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        pendingPhone?.let { a ->
            if (dev.gbot.app.local.PhoneApps.granted(ctx, a)) { dev.gbot.app.local.PhoneApps.setEnabled(ctx, a.id, true); app.toast("${a.name} is on") }
            else app.toast("${a.name} stays off without the permission")
        }
        pendingPhone = null; phoneTick++
    }
    // Coming back from system settings, where a permission may have been revoked.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) phoneTick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    fun turnOn(a: dev.gbot.app.local.PhoneApps.App) {
        if (a.permissions.isEmpty() || dev.gbot.app.local.PhoneApps.granted(ctx, a)) { dev.gbot.app.local.PhoneApps.setEnabled(ctx, a.id, true); phoneTick++ }
        else pendingPhone = a
    }
    LaunchedEffect(pendingPhone) { pendingPhone?.let { askPermissions.launch(it.permissions.toTypedArray()) } }
    // Servers connect in the background: poll while any is connecting.
    LaunchedEffect(list, connecting) {
        val waiting = connecting != null || list?.any { it.status == "connecting" || (it.status == "off" && it.enabled) } == true
        if (!waiting) return@LaunchedEffect
        delay(900); load()
        val c = connecting
        if (c != null && list?.any { sameUrl(it.url, c.url) } == true) { app.toast("${c.name} connected"); connecting = null; authUrl = "" }
    }
    LaunchedEffect(connecting) { if (connecting != null) { delay(300_000); connecting = null } }

    fun openAuth(url: String) = runCatching { CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(url)) }
    fun connect(a: CatalogApp) {
        error = ""
        if (a.token) { adding = Triple(a.name, a.url, true); token = ""; return }
        if (a.open) {
            scope.launch {
                try { app.api.addMcp(a.name, a.url, null); app.toast("${a.name} connected"); load() }
                catch (e: Exception) { error = e.message ?: "Couldn't connect" }
            }
            return
        }
        connecting = a
        scope.launch {
            try { val r = app.api.startMcpOAuth(a.name, a.url); authUrl = r.url; openAuth(r.url) }
            catch (e: Exception) { error = e.message ?: "Couldn't start sign-in" }
        }
    }

    val q = search.trim().lowercase()
    val apps = catalog.filter { (cat == "All" || it.category == cat) && "${it.name} ${it.blurb}".lowercase().contains(q) }
    Screen("Integrations", back, actions = { GButton("Custom", { adding = Triple("", "", false); token = ""; error = "" }, variant = Variant.Soft, small = true, icon = "plus"); Spacer(Modifier.width(8.dp)) }) {
        Text("Let your bots work in the apps you already use. Each account stays yours, and you can remove an app any time.", style = T.small, color = g.ink2)
        Field(search, { search = it }, placeholder = "Search apps")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { ((listOf("All") + (if (app.mode == Mode.Local) listOf(PHONE) else emptyList())) + categories).forEach { c -> Chip(c, cat == c, { cat = c }) } }
        if (adding == null && connecting == null) ErrorText(error)
        val mine = if (app.mode == Mode.Local && (cat == "All" || cat == PHONE)) dev.gbot.app.local.PhoneApps.apps.filter { "${it.name} ${it.blurb}".lowercase().contains(q) } else emptyList()
        if (mine.isNotEmpty()) {
            run {
                if (cat == "All") SectionTitle(PHONE)
                Text("Apps already on this phone. Nothing is shared with anyone: a bot can use one only while it's on here and Android allows it.", style = T.tiny, color = g.ink3)
                phoneTick.let { }
                mine.forEach { a ->
                    val on = dev.gbot.app.local.PhoneApps.on(ctx, a.id)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, g.line, RoundedCornerShape(16.dp)).background(if (on) g.surface2 else Color.Transparent)
                            .pressable(true) { if (on) { dev.gbot.app.local.PhoneApps.setEnabled(ctx, a.id, false); phoneTick++ } else turnOn(a) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(a.name, 0xFF3B5BDB, 34.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.why, style = T.tiny, color = g.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (on) Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon("check", size = 14.dp, color = g.live); Spacer(Modifier.width(4.dp)); Text("On · turn off", style = T.tiny, color = g.ink3)
                        } else Text(
                            "Turn on", style = T.tiny.copy(fontWeight = FontWeight.Medium), color = g.ink,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(g.surface).border(1.dp, g.line, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        categories.forEach { c ->
            val inCat = apps.filter { it.category == c }
            if (inCat.isEmpty()) return@forEach
            if (cat == "All") SectionTitle(c)
            inCat.forEach { a ->
                val on = list?.any { sameUrl(it.url, a.url) } == true
                val waiting = connecting?.name == a.name
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, g.line, RoundedCornerShape(16.dp)).background(if (on) g.surface2 else Color.Transparent)
                        .pressable(!on && connecting == null) { connect(a) }.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(a.name, a.tint, 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.blurb, style = T.tiny, color = g.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        on -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon("check", size = 14.dp, color = g.live)
                            Spacer(Modifier.width(4.dp))
                            Text("Added", style = T.tiny, color = g.ink3)
                        }
                        waiting -> Text("Signing in…", style = T.tiny, color = g.ink3)
                        else -> Text(
                            "Connect",
                            style = T.tiny.copy(fontWeight = FontWeight.Medium),
                            color = g.ink,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(g.surface).border(1.dp, g.line, RoundedCornerShape(50))
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        if (apps.isEmpty() && mine.isEmpty()) Text("No apps match “$search”. You can add it as a custom server above.", style = T.small, color = g.ink2)
        SectionTitle("Connected")
        if (list?.isEmpty() == true) Text("Nothing connected yet.", style = T.small, color = g.ink3)
        list?.forEach { m -> ConnectedServer(m) { scope.launch { load() } } }
    }
    connecting?.let { c ->
        Popup({ connecting = null; authUrl = "" }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(c.name, c.tint, 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Signing in to ${c.name}", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(g.live))
                        Spacer(Modifier.width(6.dp))
                        Text(if (authUrl.isNotEmpty()) "Finish in the browser, then come back here." else "Preparing the sign-in…", style = T.small, color = g.ink2)
                    }
                }
            }
            ErrorText(error)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                GButton("Cancel", { connecting = null; authUrl = "" }, variant = Variant.Ghost, small = true)
                if (authUrl.isNotEmpty()) { Spacer(Modifier.width(6.dp)); GButton("Open sign-in again", { openAuth(authUrl) }, variant = Variant.Soft, small = true) }
            }
        }
    }
    adding?.let { (n0, u0, fromCatalog) ->
        var name by remember(n0) { mutableStateOf(n0) }
        var url by remember(u0) { mutableStateOf(u0) }
        Popup({ if (!busy) adding = null }, dismissable = !busy) {
                Text("Connect ${name.ifEmpty { "a server" }}", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
                Text(if (fromCatalog) "$name connects with a personal access token. Make one in its settings and paste it here." else "Give it a name and URL, and a token if it needs one.", style = T.small, color = g.ink2)
                Column { Label("Name"); Field(name, { name = it }, placeholder = "GitHub") }
                Column { Label("URL"); Field(url, { url = it }, placeholder = "https://mcp.example.com/mcp", keyboard = KeyboardType.Uri) }
                Column { Label("Token", if (fromCatalog) null else "optional"); Field(token, { token = it }, placeholder = "Personal access token", password = true) }
                Text(if (app.mode == Mode.Local) "Tokens stay on this phone and are never shown to bots." else "Tokens are encrypted on the server and never shown to bots.", style = T.tiny, color = g.ink3)
                ErrorText(error)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    GButton("Cancel", { adding = null }, variant = Variant.Ghost, small = true)
                    Spacer(Modifier.width(6.dp))
                    GButton(if (busy) "Connecting…" else "Connect", {
                        busy = true; error = ""
                        scope.launch {
                            try { app.api.addMcp(name.trim(), url.trim(), token.ifEmpty { null }); adding = null; load() }
                            catch (e: Exception) { error = e.message ?: "Couldn't connect" } finally { busy = false }
                        }
                    }, small = true, enabled = !busy && name.isNotBlank() && url.isNotBlank() && (!fromCatalog || token.isNotBlank()))
                }
        }
    }
}

@Composable
private fun ConnectedServer(m: McpServer, reload: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }

    // "connected" on the server, "ok" on the phone: both mean working.
    val live = m.status == "connected" || m.status == "ok"
    val waiting = m.enabled && !live && (m.status.isEmpty() || m.status == "connecting" || m.status == "off")
    val tone = when {
        !m.enabled -> g.ink3
        live -> g.live
        waiting -> g.warning
        else -> g.danger
    }
    val state = when {
        !m.enabled -> "Off"
        live -> if (m.tools.size == 1) "1 tool" else "${m.tools.size} tools"
        waiting -> "Connecting\u2026"
        m.status == "unreachable" -> "Not answering"
        else -> m.status
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, g.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(m.name, 0xFF6B7280, 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(m.name, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    shortUrl(m.url) + if (m.oauth) " \u00b7 signed in" else "",
                    style = T.tiny, color = g.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Toggle(m.enabled, { v -> scope.attempt(if (v) "${m.name} on" else "${m.name} off") { app.api.toggleMcp(m.id, v); reload() } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val showTools = live && m.tools.isNotEmpty()
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.12f))
                    .pressable(showTools) { open = !open }.padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(6.dp))
                Text(state, style = T.tiny, color = tone, maxLines = 1)
                if (showTools) { Spacer(Modifier.width(5.dp)); Icon(if (open) "up" else "down", size = 11.dp, color = tone) }
            }
            Spacer(Modifier.weight(1f))
            IconBtn("trash", "Remove ${m.name}", { removing = true }, size = 32.dp, tint = g.ink3)
        }
        if (open && m.tools.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 160.dp).clip(RoundedCornerShape(12.dp)).background(g.bg)
                    .verticalScroll(rememberScrollState()).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                m.tools.forEach {
                    Text(it.replace(Regex("^m[0-9a-f]{8}__"), ""), style = T.tiny, color = g.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (removing) Confirm("Remove ${m.name}?", "Bots lose its tools.", "Remove", onConfirm = { scope.attempt("${m.name} removed") { app.api.deleteMcp(m.id); reload() } }, onDismiss = { removing = false })
}

/** The part of a server address worth showing: its host and little else. */
private fun shortUrl(url: String): String {
    val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.")
    return host.ifEmpty { url.removePrefix("https://").removePrefix("http://").trimEnd('/') }
}

// ---- Vault --------------------------------------------------------------

@Composable
fun Vault(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<VaultSecret>?>(null) }
    var form by remember { mutableStateOf<Array<String>?>(null) } // id, name, origins, value
    var error by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<VaultSecret?>(null) }
    suspend fun load() { list = runCatching { app.api.vault().secrets }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { load() }
    Screen("Vault", back) {
        Panel {
            Row {
                Icon("lock", color = g.ink2)
                Spacer(Modifier.width(10.dp))
                Text(if (app.mode == Mode.Local) "Values stay on this phone and are never shown again. A bot can type one into a password field on the sites you list; it never sees the value." else "Values are sealed on the server and never shown again. A bot can type one into a password field on the sites you list; it never sees the value.", style = T.small, color = g.ink2)
            }
        }
        list?.forEach { v ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, g.line, RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(g.surface2), contentAlignment = Alignment.Center) { Icon("lock", size = 16.dp, color = g.ink2) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(v.name, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink); Text(v.origins.joinToString(", "), style = T.tiny, color = g.ink3, maxLines = 1) }
                GButton("Edit", { error = ""; form = arrayOf(v.id, v.name, v.origins.joinToString("\n"), "") }, variant = Variant.Ghost, small = true)
                IconBtn("trash", "Delete ${v.name}", { deleting = v }, size = 34.dp, tint = g.danger)
            }
        }
        if (list?.isEmpty() == true && form == null) Empty("No saved secrets yet.")
        val f = form
        if (f != null) Panel {
            Column { Label("Name"); Field(f[1], { form = f.copyOf().also { a -> a[1] = it.take(60) } }, placeholder = "LinkedIn") }
            Column { Label("Sites", "One per line; subdomains are included"); Field(f[2], { form = f.copyOf().also { a -> a[2] = it } }, singleLine = false, minLines = 2, placeholder = "linkedin.com\nindeed.com", keyboard = KeyboardType.Uri) }
            Column { Label(if (f[0].isNotEmpty()) "New value" else "Value", if (f[0].isNotEmpty()) "Leave blank to keep the current value" else null); Field(f[3], { form = f.copyOf().also { a -> a[3] = it } }, placeholder = if (f[0].isNotEmpty()) "••••••••" else "Password or token", password = true) }
            ErrorText(error)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                GButton("Cancel", { form = null; error = "" }, variant = Variant.Ghost, small = true)
                Spacer(Modifier.width(6.dp))
                GButton("Save secret", {
                    val origins = f[2].split(Regex("[\\n,]+")).map { it.trim() }.filter { it.isNotEmpty() }
                    if (f[1].isBlank() || origins.isEmpty() || (f[0].isEmpty() && f[3].isEmpty())) { error = "Add a name, the value, and at least one site."; return@GButton }
                    error = ""
                    scope.launch {
                        try {
                            if (f[0].isNotEmpty()) app.api.updateVault(f[0], f[1].trim(), origins, f[3].ifEmpty { null }) else app.api.addVault(f[1].trim(), f[3], origins)
                            form = null; load(); app.toast("Secret saved")
                        } catch (e: Exception) { error = e.message ?: "Couldn't save" }
                    }
                }, small = true)
            }
        } else GButton("Add secret", { error = ""; form = arrayOf("", "", "", "") }, variant = Variant.Soft, icon = "plus")
    }
    deleting?.let { v -> Confirm("Delete the ${v.name} secret?", "Bots won't be able to use it anymore.", "Delete", onConfirm = { scope.attempt("Secret deleted") { app.api.deleteVault(v.id); load() } }, onDismiss = { deleting = null }) }
}

// ---- Skills -------------------------------------------------------------

private fun parseSkillMd(text: String): Skill? {
    val m = Regex("^---\\n([\\s\\S]*?)\\n---\\n?([\\s\\S]*)$").find(text.trim()) ?: return null
    val meta = m.groupValues[1].lines().mapNotNull { l -> l.split(Regex(":\\s*"), 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
    val name = meta["name"] ?: return null
    return Skill(name = name, description = meta["description"].orEmpty(), body = m.groupValues[2].trim())
}

@Composable
fun Skills(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Skill>?>(null) }
    var edit by remember { mutableStateOf<Skill?>(null) }
    var error by remember { mutableStateOf("") }
    suspend fun load() { list = runCatching { app.api.skills().skills }.getOrDefault(emptyList()) }
    LaunchedEffect(Unit) { load() }
    val e = edit
    if (e != null) {
        Screen(if (e.id.isEmpty()) "New skill" else "/${e.name}", { edit = null }, footer = {
            GButton("Save skill", {
                error = ""
                scope.launch {
                    try { app.api.saveSkill(e.name.trim(), e.description.trim(), e.body); SkillCache.invalidate(); app.toast("Skill saved"); edit = null; load() }
                    catch (x: Exception) { error = x.message ?: "Couldn't save" }
                }
            }, Modifier.fillMaxWidth(), enabled = e.name.isNotBlank() && e.body.isNotBlank())
        }) {
            Column { Label("Name"); Field(e.name, { edit = e.copy(name = it) }, placeholder = "weekly-report", mono = true) }
            Column { Label("When to use it"); Field(e.description, { edit = e.copy(description = it) }, placeholder = "Writing the Friday team update") }
            Column {
                Label("Instructions", "Markdown, or paste a SKILL.md")
                Field(e.body, { t -> edit = parseSkillMd(t)?.copy(id = e.id) ?: e.copy(body = t) }, singleLine = false, minLines = 12, mono = true)
            }
            ErrorText(error)
        }
        return
    }
    Screen("Skills", back, actions = { GButton("New", { edit = Skill() }, variant = Variant.Soft, small = true, icon = "plus"); Spacer(Modifier.width(8.dp)) }) {
        Text("Know-how your bots load when a task needs it. Type / in a chat to use one.", style = T.small, color = g.ink2)
        if (list?.isEmpty() == true) Empty("No skills yet.")
        list?.forEach { k ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, g.line, RoundedCornerShape(16.dp)).pressable { edit = k }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon("spark", color = g.live)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("/${k.name}", style = T.body.copy(fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace), color = g.ink)
                    Text(k.description.ifEmpty { "No description" }, style = T.tiny, color = g.ink2, maxLines = 1)
                }
                IconBtn("trash", "Delete ${k.name}", { scope.attempt("Skill deleted") { app.api.deleteSkill(k.id); SkillCache.invalidate(); load() } }, size = 34.dp, tint = g.danger)
            }
        }
    }
}

// ---- Voice --------------------------------------------------------------

@Composable
fun VoiceTab(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val ctx = LocalContext.current
    var v by remember { mutableStateOf<VoiceInfo?>(null) }
    LaunchedEffect(Unit) { v = app.voice.info() }
    val phoneSR = android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)
    Screen("Voice", back) {
        StatusRow("Dictation", v?.transcribe == true || phoneSR,
            if (v?.transcribe == true) "Transcribed by ${v?.transcribeBy}." else if (phoneSR) "Using this phone's speech recognition." else "Not available on this phone.")
        StatusRow("Read aloud", true, if (v?.speak == true) "Spoken by ${v?.speakBy}." else "Using this phone's voices. Pick one per bot in its settings.")
        Panel {
            Text("To keep speech entirely on your machine, run an OpenAI-compatible speech server (for example faster-whisper or Kokoro) and set GBOT_STT_URL and GBOT_TTS_URL on the gbot server.", style = T.small, color = g.ink2)
        }
    }
}

@Composable
private fun StatusRow(title: String, ok: Boolean, detail: String) {
    val g = LocalG.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, g.line, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (ok) g.success else g.line), contentAlignment = Alignment.Center) { Icon(if (ok) "check" else "close", size = 14.dp, color = Color.White) }
        Spacer(Modifier.width(10.dp))
        Column { Text(title, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink); Text(detail, style = T.small, color = g.ink2) }
    }
}
