package dev.gbot.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.net.CodexLogin
import dev.gbot.app.net.Provider
import dev.gbot.app.ui.Confirm
import dev.gbot.app.ui.ErrorText
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Icon
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.Label
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.ModelList
import dev.gbot.app.ui.Panel
import dev.gbot.app.ui.SectionTitle
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Toggle
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.Chip
import dev.gbot.app.ui.attempt
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private data class Kind(val id: String, val label: String, val hint: String, val placeholder: String)
private val kinds = listOf(
    Kind("openai", "OpenAI", "vLLM, LM Studio, llama.cpp, LiteLLM…", "http://localhost:1234/v1"),
    Kind("anthropic", "Anthropic", "Claude models", "https://api.anthropic.com"),
    Kind("gemini", "Gemini", "Google AI Studio", "https://generativelanguage.googleapis.com/v1beta"),
)

private data class Edit(
    val id: String? = null, val name: String = "", val kind: String = "openai", val baseUrl: String = "", val apiKey: String = "",
    val removeKey: Boolean = false, val local: Boolean = true, val models: List<String> = emptyList(), val caps: Map<String, List<String>?> = emptyMap(),
)

@Composable
fun Providers() {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Provider>?>(null) }
    var codex by remember { mutableStateOf<CodexLogin?>(null) }
    var edit by remember { mutableStateOf<Edit?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Provider?>(null) }
    var loadError by remember { mutableStateOf("") }
    suspend fun load() {
        runCatching { app.api.providers() }.onSuccess { list = it.providers; codex = it.codex; loadError = "" }
            .onFailure { if (list == null) list = emptyList(); loadError = it.message ?: "Couldn't load providers" }
        app.store.loadModels()
    }
    LaunchedEffect(Unit) { load() }

    SectionTitle("Providers")
    val e = edit
    if (e != null) {
        val kind = kinds.first { it.id == e.kind }
        Panel {
            Column { Label("Name"); Field(e.name, { edit = e.copy(name = it.take(40)) }, placeholder = "My GPU box") }
            Label("Protocol")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { kinds.forEach { k -> Chip(k.label, e.kind == k.id, { edit = e.copy(kind = k.id, baseUrl = "", local = k.id == "openai") }) } }
            Column {
                Label("Endpoint", if (e.kind == "openai") kind.hint else "${kind.hint}; leave blank for the default")
                Field(e.baseUrl, { edit = e.copy(baseUrl = it) }, placeholder = kind.placeholder, keyboard = KeyboardType.Uri)
            }
            Column {
                Label("API key", if (e.id != null && !e.removeKey) "leave blank to keep" else "optional for local servers")
                Field(e.apiKey, { edit = e.copy(apiKey = it) }, placeholder = if (e.id != null) "••••••••" else "sk-…", password = true, enabled = !e.removeKey)
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Runs on this machine", style = T.small, color = g.ink2, modifier = Modifier.weight(1f)); Toggle(e.local, { edit = e.copy(local = it) }) }
            if (e.id != null) Row(verticalAlignment = Alignment.CenterVertically) { Text("Remove stored key", style = T.small, color = g.ink2, modifier = Modifier.weight(1f)); Toggle(e.removeKey, { edit = e.copy(removeKey = it, apiKey = "") }) }
            Label("Models", "search, filter by what they can do, or add one by name")
            ModelList(e.models, e.caps) { edit = e.copy(models = it) }
            if (notice.isNotEmpty()) Text(notice, style = T.small, color = g.live)
            ErrorText(error)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                GButton("Cancel", { edit = null }, variant = Variant.Ghost, small = true)
                Spacer(Modifier.weight(1f))
                GButton(if (busy) "Checking…" else "Discover", {
                    busy = true; error = ""; notice = ""
                    scope.launch {
                        try {
                            val r = app.api.discoverProvider(buildJsonObject {
                                e.id?.let { put("id", it) }; put("kind", e.kind); put("baseUrl", e.baseUrl.trim()); if (e.apiKey.isNotEmpty()) put("apiKey", e.apiKey)
                            })
                            edit = (edit ?: e).copy(models = r.models, caps = r.caps.orEmpty())
                            notice = if (r.models.isNotEmpty()) "Found ${r.models.size} models." else "No models returned."
                        } catch (x: Exception) { error = x.message ?: "Couldn't list models" } finally { busy = false }
                    }
                }, variant = Variant.Soft, small = true, enabled = !busy && (e.kind != "openai" || e.baseUrl.isNotBlank()))
                GButton(if (busy) "Saving…" else if (e.id != null) "Save" else "Connect", {
                    busy = true; error = ""; notice = ""
                    scope.launch {
                        try {
                            val body = buildJsonObject {
                                put("name", e.name.trim()); put("kind", e.kind); put("baseUrl", e.baseUrl.trim()); put("local", e.local)
                                putJsonArray("models") { e.models.forEach { add(JsonPrimitive(it)) } }
                                if (e.apiKey.isNotEmpty()) put("apiKey", e.apiKey)
                                if (e.id != null) put("removeKey", e.removeKey)
                            }
                            if (e.id != null) app.api.updateProvider(e.id, body) else app.api.createProvider(body)
                            app.toast(if (e.id != null) "Provider updated" else "Provider connected")
                            edit = null
                            load()
                        } catch (x: Exception) { error = x.message ?: "Couldn't save" } finally { busy = false }
                    }
                }, small = true, enabled = !busy && e.name.isNotBlank())
            }
        }
        return
    }
    Text(if (app.mode == Mode.Local) "Connect any OpenAI-compatible server, Anthropic or Gemini. Keys stay on this phone; models are only used here." else "Connect any OpenAI-compatible server, Anthropic or Gemini. Keys are encrypted and only your account can use the models.", style = T.small, color = g.ink2)
    val c = codex
    if (c?.allowed == true && list != null && list!!.none { it.kind == "codex" }) Panel {
        Text("OpenAI with your Codex login", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
        Text(if (c.available) "Use the ChatGPT account Codex is signed in to on the server. No API key needed." else "Sign in to Codex on the server (codex login) to use your ChatGPT plan here.", style = T.small, color = g.ink2)
        GButton("Use Codex login", { scope.attempt("Codex models connected") { app.api.createProvider(buildJsonObject { put("name", "Codex (ChatGPT)"); put("kind", "codex"); put("baseUrl", "") }); load() } }, variant = Variant.Soft, small = true, enabled = c.available)
    }
    ErrorText(loadError)
    if (list?.isEmpty() == true && loadError.isEmpty()) Text("No providers yet.", style = T.small, color = g.ink3)
    list?.forEach { p ->
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon("plug", color = if (p.enabled) g.live else g.ink2)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).then(if (p.kind != "codex") Modifier.background(g.bg.copy(alpha = 0f)) else Modifier)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                        Text("  " + p.kind.uppercase(), style = T.tiny.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, fontSize = 10.5.sp), color = g.ink3)
                    }
                    Text(if (p.kind == "codex") "Uses the server's Codex sign-in" else p.baseUrl, style = T.tiny, color = g.ink2, maxLines = 1)
                }
                Toggle(p.enabled, { v -> scope.attempt(if (v) "${p.name} on" else "${p.name} off") { app.api.updateProvider(p.id, buildJsonObject { put("enabled", v) }); load() } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (p.status) {
                        "connected" -> "${p.models.size} models · ready"
                        "no models" -> "No models found — press refresh"
                        "error", "unreachable" -> "Couldn't reach it — check the endpoint and key"
                        "connecting" -> "Checking…"
                        "" -> "Not connected yet"
                        else -> p.status
                    },
                    style = T.small,
                    color = if (p.status == "connected") g.ink2 else if (p.status == "off") g.ink3 else g.danger,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
                if (p.models.isNotEmpty()) GButton(if (open == p.id) "Hide" else "Show", { open = if (open == p.id) null else p.id }, variant = Variant.Ghost, small = true)
                if (p.kind != "codex") IconBtn("pencil", "Edit ${p.name}", {
                    error = ""; notice = ""
                    edit = Edit(p.id, p.name, p.kind, p.baseUrl, "", false, p.local, p.models, p.caps.orEmpty())
                }, size = 34.dp)
                IconBtn("refresh", "Refresh ${p.name} models", { scope.attempt("Models refreshed") { app.api.refreshProvider(p.id); load() } }, size = 34.dp)
                IconBtn("trash", "Remove ${p.name}", { removing = p }, size = 34.dp, tint = g.danger)
            }
            if (open == p.id) ModelList(p.models, p.caps)
        }
    }
    GButton("Add provider", { error = ""; notice = ""; edit = Edit() }, Modifier.fillMaxWidth().padding(top = 4.dp), variant = Variant.Soft, icon = "plus")
    removing?.let { p ->
        Confirm("Remove ${p.name}?", "Bots using its models will need another model.", "Remove", onConfirm = {
            scope.attempt("${p.name} removed") { app.api.deleteProvider(p.id); load() }
        }, onDismiss = { removing = null })
    }
}
