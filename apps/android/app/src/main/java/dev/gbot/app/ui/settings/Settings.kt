package dev.gbot.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.net.ToolRule
import dev.gbot.app.ui.Confirm
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.ModelPicker
import dev.gbot.app.ui.Panel
import dev.gbot.app.ui.R
import dev.gbot.app.ui.Screen
import dev.gbot.app.ui.SectionTitle
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Toggle
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.attempt
import dev.gbot.app.ui.pressable

@Composable
private fun Row(icon: String, label: String, value: String?, last: Boolean = false, onClick: () -> Unit) {
    val g = LocalG.current
    Column {
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, size = 19.dp, color = g.ink2)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = T.body.copy(fontWeight = FontWeight.Medium), color = g.ink)
                if (!value.isNullOrEmpty()) Text(value, style = T.small, color = g.ink2, maxLines = 1)
            }
            Icon("next", size = 16.dp, color = g.ink3)
        }
        if (!last) Box(Modifier.fillMaxWidth().padding(start = 47.dp).height(1.dp).background(g.line.copy(alpha = g.line.alpha * 0.7f)))
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    val g = LocalG.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, g.line, RoundedCornerShape(16.dp))) { content() }
}

@Composable
fun SettingsHome(nav: NavHostController) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var signingOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.store.loadModels() }
    val local = app.mode == Mode.Local
    val me = s.me
    val chosen = s.models?.find { it.id == me?.defaultModel }
    val best = s.models?.find { it.best }
    val initials = (me?.name ?: "").split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { "?" }
    Screen("Settings", { nav.popBackStack() }) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(g.surface2), contentAlignment = Alignment.Center) { Text(initials, color = g.ink2, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(me?.name.orEmpty().ifEmpty { "Signed in" }, style = T.h2, color = g.ink)
                Text(me?.email.orEmpty(), style = T.small, color = g.ink2)
            }
            if (!s.connected) Text(if (local) "On this phone" else "Reconnecting", style = T.tiny, color = if (local) g.ink3 else g.warning)
        }
        Group {
            Row("gear", "General", "Approvals and tool rules") { nav.navigate(R.settings("general")) }
            Row("bell", "Notifications", if (app.alertsEnabled) "On, even when gbot is closed" else "Only while gbot is open") { nav.navigate(R.settings("notifications")) }
            Row("chip", "Models", chosen?.let { "Default: ${it.label}" } ?: best?.let { "Default: best available (${it.label})" } ?: "Default model, providers and keys", last = true) { nav.navigate(R.settings("models")) }
        }
        if (local) {
            Group {
                Row("users", "Team", "Sharing between people needs a server") { nav.navigate(R.settings("team")) }
                Row("plug", "Integrations", "Apps your bots can use") { nav.navigate(R.settings("integrations")) }
                Row("lock", "Vault", "Secrets bots can type, never see", last = true) { nav.navigate(R.settings("vault")) }
            }
            Group {
                Row("spark", "Skills", "Know-how bots load when needed") { nav.navigate(R.settings("skills")) }
                Row("speaker", "Voice", "Dictation and read aloud", last = true) { nav.navigate(R.settings("voice")) }
            }
            Group {
                Row("pencil", "About you", "For forms the bot fills in", last = true) { nav.navigate(R.settings("profile")) }
            }
        } else {
            Group {
                Row("users", "Team", "People and sharing") { nav.navigate(R.settings("team")) }
                Row("plug", "Integrations", "Apps your bots can use") { nav.navigate(R.settings("integrations")) }
                Row("lock", "Vault", "Passwords your bots can type") { nav.navigate(R.settings("vault")) }
                Row("spark", "Skills", "Know-how bots load when needed") { nav.navigate(R.settings("skills")) }
                Row("speaker", "Voice", "Dictation and read aloud", last = true) { nav.navigate(R.settings("voice")) }
            }
        }
        Group {
            if (local) {
                Row("chip", "This phone", "Model, memory and browser stay here", last = true) {}
            } else {
                Row("globe", "Server", app.http.server?.removePrefix("http://")?.removePrefix("https://")) { nav.navigate(R.SERVER) }
                Row("chip", "Run on this phone", "Use a model here instead", last = true) {
                    app.useLocal()
                    nav.navigate(R.HOME) { popUpTo(0) { inclusive = true } }
                }
            }
        }
        if (local) {
            GButton("Connect my server", {
                app.useServer()
                nav.navigate(R.SERVER) { popUpTo(0) { inclusive = true } }
            }, Modifier.fillMaxWidth(), variant = Variant.Soft, icon = "globe")
        } else {
            GButton("Sign out", { signingOut = true }, Modifier.fillMaxWidth(), variant = Variant.Soft, icon = "logout")
        }
    }
    if (signingOut) Confirm("Sign out?", "You'll stop getting alerts on this phone until you sign in again.", "Sign out", onConfirm = {
        scope.attempt { runCatching { app.api.logout() }; app.signedOut(); nav.navigate(R.LOGIN) { popUpTo(0) { inclusive = true } } }
    }, onDismiss = { signingOut = false })
}

@Composable
fun SettingsTab(nav: NavHostController, tab: String) {
    val back = { nav.popBackStack(); Unit }
    when (tab) {
        "general" -> General(back)
        "notifications" -> Notifications(back)
        "models" -> Models(back)
        "team" -> Team(back)
        "integrations" -> Integrations(back)
        "vault" -> Vault(back)
        "skills" -> Skills(back)
        "voice" -> VoiceTab(back)
        "profile" -> ProfileTab(back)
        else -> Screen("Settings", back) {}
    }
}

private val modes = listOf(
    Triple("ask", "Ask", "Ask before anything that changes something."),
    Triple("auto", "Auto", "Ask only for risky steps, or once outside content is involved."),
    Triple("never", "Never ask", "Run everything without waiting. Blocked tools stay blocked."),
)

@Composable
private fun General(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<ToolRule>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { app.api.rules() }.onSuccess { rules = it.rules } }
    val mode = s.me?.approvalMode ?: "auto"
    Screen("General", back) {
        SectionTitle("Approvals")
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(g.surface2).padding(4.dp)) {
            modes.forEach { (id, label, _) ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (mode == id) g.bg else Color.Transparent).pressable { scope.attempt { app.store.setMe(app.api.updateMe(approvalMode = id)) } }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = T.small.copy(fontWeight = FontWeight.SemiBold), color = if (mode == id) g.ink else g.ink2)
                }
            }
        }
        Text(modes.find { it.first == mode }?.third.orEmpty(), style = T.small, color = g.ink2)
        SectionTitle("Tool rules")
        if (rules.isEmpty()) Text("None yet.", style = T.small, color = g.ink3)
        if (rules.isNotEmpty()) Panel(padding = 6.dp) {
            rules.forEach { r ->
                androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.rule.uppercase(), style = T.tiny.copy(fontWeight = FontWeight.Bold), color = if (r.rule == "allow") g.success else g.danger, modifier = Modifier.width(54.dp))
                    Text(r.tool.replace(Regex("^m[0-9a-f]{8}__"), ""), style = T.small, color = g.ink, modifier = Modifier.weight(1f), maxLines = 1)
                    IconBtn("close", "Remove rule", { scope.attempt("Rule removed") { rules = app.api.setRule(r.tool, "").rules } }, size = 34.dp)
                }
            }
        }
        Text("“Always allow” on an approval card adds a rule here.", style = T.tiny, color = g.ink3)
    }
}

@Composable
private fun Notifications(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(app.alertsEnabled) }
    var unrestricted by remember { mutableStateOf(true) }
    var allowed by remember { mutableStateOf(true) }
    val owner = LocalLifecycleOwner.current
    fun check() {
        unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        allowed = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
    LaunchedEffect(Unit) { check() }
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) check() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Screen("Notifications", back) {
        Panel {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alerts when gbot is closed", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
                    Text("Keeps a quiet connection to your server, so you hear when a bot needs you or is done. Android shows a small “gbot is connected” notification for this.", style = T.small, color = g.ink2)
                }
                Spacer(Modifier.width(8.dp))
                Toggle(on, { on = it; app.alertsEnabled = it })
            }
        }
        if (!allowed) Panel {
            Text("Notifications are off for gbot.", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
            GButton("Turn them on", {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }, variant = Variant.Soft, small = true)
        }
        if (on && !unrestricted) Panel {
            Text("Let gbot run in the background", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
            Text("Battery saving can pause the connection while the phone sleeps, which delays alerts.", style = T.small, color = g.ink2)
            GButton("Allow", {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                    .onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }, variant = Variant.Soft, small = true)
        }
        GButton("Notification categories", {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }, variant = Variant.Ghost, small = true)
        Text("You'll get: bots that need you (approve or decline right from the notification), bots that finish, and replies in groups.", style = T.tiny, color = g.ink3)
        if (Build.VERSION.SDK_INT < 26) Text("", style = T.tiny)
    }
}

@Composable
private fun Models(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { app.store.loadModels() }
    val hasLocal = s.models?.any { it.local } == true
    val localCount = s.models?.count { it.local } ?: 0
    val providerCount = s.models?.count { !it.local } ?: 0
    Screen("Models", back) {
        SectionTitle("Default model")
        ModelPicker(s.models.orEmpty(), s.me?.defaultModel ?: "", { id -> scope.attempt { app.store.setMe(app.api.updateMe(defaultModel = id)) } }, "Best available")
        Text("A bot uses its own model if you set one, otherwise this. If a model isn't connected, it falls back to the best available.", style = T.small, color = g.ink2)
        if (app.mode == Mode.Local) Text(
            when {
                providerCount > 0 && localCount == 0 -> "Using your provider. A model on this phone is optional."
                localCount > 0 -> "$localCount on this phone · $providerCount from providers"
                s.models == null -> "Loading models…"
                else -> "Nothing connected yet — add a provider, or download a phone model below."
            },
            style = T.tiny, color = if (providerCount > 0) g.success else g.ink3,
        )
        if (s.models != null && !hasLocal && app.mode == Mode.Server) Panel {
            Text("Connect a model provider", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
            Text("Run Ollama, LM Studio, vLLM or any OpenAI-compatible server, or add Anthropic or Gemini below. For Ollama, pull a small model with tools: ollama pull lfm3-omni", style = T.small, color = g.ink2)
        }
        Providers()
        if (app.mode == Mode.Local) OnDeviceModels()
    }
}
