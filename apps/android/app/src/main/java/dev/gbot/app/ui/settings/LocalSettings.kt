package dev.gbot.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.gbot.app.GbotApp
import dev.gbot.app.local.LocalApi
import dev.gbot.app.local.LocalModels
import dev.gbot.app.local.ModelMemory
import dev.gbot.app.ui.Chip
import dev.gbot.app.ui.Confirm
import dev.gbot.app.ui.ErrorText
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.Label
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.Panel
import dev.gbot.app.ui.Screen
import dev.gbot.app.ui.SectionTitle
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.attempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** Download and choose the model that runs on this phone. */
@Composable
fun OnDeviceModels() {
    val app = GbotApp.instance
    val g = LocalG.current
    val ctx = LocalContext.current
    val local = app.api as? LocalApi ?: return
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(LocalModels.files(ctx)) }
    var parts by remember { mutableStateOf(LocalModels.parts(ctx)) }
    var loaded by remember { mutableStateOf(local.loadedModelId()) }
    var idle by remember { mutableStateOf(local.modelIdleMinutes()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<File?>(null) }
    var removingPart by remember { mutableStateOf<File?>(null) }
    val chosen = app.store.now.me?.defaultModel

    // The idle timer runs inside the brain; show where it got to.
    LaunchedEffect(Unit) {
        while (true) {
            loaded = runCatching { local.loadedModelId() }.getOrNull()
            delay(1500)
        }
    }

    fun refresh() {
        files = LocalModels.files(ctx)
        parts = LocalModels.parts(ctx)
        loaded = runCatching { local.loadedModelId() }.getOrNull()
        scope.launch { runCatching { app.store.loadModels() } }
    }

    fun removeModel(f: File) {
        val id = LocalModels.id(f)
        scope.launch {
            if (local.loadedModelId() == id && !local.unloadModel()) {
                error = "It's answering right now. Wait for the reply, then remove it."
                removing = null
                return@launch
            }
            LocalModels.delete(ctx, id)
            if (app.store.now.me?.defaultModel == id) runCatching { app.store.setMe(app.api.updateMe(defaultModel = "")) }
            notice = "${f.nameWithoutExtension} removed"
            error = ""
            refresh()
            removing = null
        }
    }

    val providerCount = app.store.now.models?.count { !it.local } ?: 0
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(if (providerCount > 0) "On this phone (optional)" else "On this phone")

        if (files.isNotEmpty()) Panel {
            Text("Memory", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
            val loadedName = loaded?.removePrefix("local:")
            Text(
                if (loadedName == null) "No model is loaded right now. The next message loads one."
                else if (idle == ModelMemory.FOREVER) "Loaded: $loadedName. It stays until you switch models."
                else "Loaded: $loadedName. It gives the RAM back after $idle minutes idle.",
                style = T.small, color = g.ink2,
            )
            if (loadedName != null) GButton("Unload now", {
                scope.launch {
                    notice = if (local.unloadModel()) "Model unloaded" else "It's answering right now — try again when it's done"
                    loaded = local.loadedModelId()
                }
            }, variant = Variant.Soft, small = true)
            Text("Keep the model ready", style = T.tiny, color = g.ink3)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(2, 10, ModelMemory.FOREVER).forEach { m ->
                    Chip(if (m == ModelMemory.FOREVER) "Until I switch" else "$m min", idle == m, {
                        idle = m
                        local.setModelIdleMinutes(m)
                        notice = ModelMemory.label(m)
                    })
                }
            }
        }

        files.forEach { f ->
            val id = LocalModels.id(f)
            val isLoaded = loaded == id
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.nameWithoutExtension, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
                        Text(
                            "${f.length() / (1024 * 1024)} MB" + if (isLoaded) " · loaded" else "",
                            style = T.tiny, color = if (isLoaded) g.success else g.ink3,
                        )
                    }
                    if (chosen == id) Text("Default", style = T.tiny, color = g.success)
                    else GButton("Use", {
                        scope.attempt { app.store.setMe(app.api.updateMe(defaultModel = id)) }
                    }, small = true, variant = Variant.Soft)
                    IconBtn("trash", "Remove ${f.nameWithoutExtension}", { error = ""; removing = f }, size = 34.dp, tint = g.danger)
                }
            }
        }
        if (files.isEmpty()) Text(
            if (providerCount > 0) "You're using $providerCount provider models. Downloading one here makes replies work offline."
            else "No model yet. Download one below — it stays on this phone.",
            style = T.small, color = g.ink2,
        )

        parts.forEach { p ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Incomplete download", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
                        Text("${p.name.removeSuffix(".part")} · ${p.length() / (1024 * 1024)} MB so far", style = T.tiny, color = g.ink3)
                    }
                    IconBtn("trash", "Remove incomplete download", { removingPart = p }, size = 34.dp, tint = g.danger)
                }
            }
        }

        LocalModels.catalog.filter { e -> files.none { it.name == e.file } }.forEach { e ->
            Panel {
                Text(e.label, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
                Text(e.note, style = T.small, color = g.ink2)
                Text("${e.bytes / (1024 * 1024)} MB", style = T.tiny, color = g.ink3)
                if (busy == e.label) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}% — keep gbot open", style = T.tiny, color = g.ink2)
                } else {
                    GButton("Download over Wi‑Fi", {
                        busy = e.label; progress = 0f; error = ""
                        scope.launch {
                            runCatching {
                                val stat = android.os.StatFs(ctx.filesDir.path)
                                val free = stat.availableBytes
                                if (free < e.bytes * 6 / 5) throw IllegalStateException("Not enough space: needs ${e.bytes / (1024 * 1024)} MB free")
                                LocalModels.download(ctx, e) { done, total ->
                                    progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                }
                            }.onSuccess {
                                notice = "${e.label} is ready"
                                refresh()
                            }.onFailure { error = it.message ?: "Download failed" }
                            busy = null
                        }
                    }, Modifier.fillMaxWidth(), small = true)
                }
            }
        }
        ErrorText(error)
        if (notice.isNotEmpty()) Text(notice, style = T.small, color = g.success)
        Text(
            "The model runs here, on the phone's GPU (CPU if the GPU can't). Loading it takes a few seconds; unloading gives the RAM back.",
            style = T.tiny, color = g.ink3,
        )
    }

    removing?.let { f ->
        Confirm(
            "Remove ${f.nameWithoutExtension}?",
            "${f.length() / (1024 * 1024)} MB will be deleted from this phone. You can download it again later.",
            "Remove",
            onConfirm = { removeModel(f) },
            onDismiss = { removing = null },
        )
    }
    removingPart?.let { p ->
        Confirm(
            "Remove the incomplete download?",
            "${p.length() / (1024 * 1024)} MB will be freed. You can start it again when you like.",
            "Remove",
            onConfirm = {
                LocalModels.deletePart(ctx, p.name)
                removingPart = null
                refresh()
            },
            onDismiss = { removingPart = null },
        )
    }
}

/** Name, contact and history, so the bot can fill forms instead of guessing. */
@Composable
fun ProfileTab(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val local = app.api as? LocalApi ?: return
    val saved = remember { local.db.get("profile")?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).let { j -> j as JsonObject } }.getOrNull() } ?: JsonObject(emptyMap()) }
    var name by remember { mutableStateOf(saved["name"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
    var email by remember { mutableStateOf(saved["email"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
    var phone by remember { mutableStateOf(saved["phone"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
    var links by remember { mutableStateOf(saved["links"]?.jsonPrimitive?.contentOrNull.orEmpty()) }
    var about by remember { mutableStateOf(saved["about"]?.jsonPrimitive?.contentOrNull.orEmpty()) }

    fun save() {
        val o = buildJsonObject {
            if (name.isNotBlank()) put("name", name)
            if (email.isNotBlank()) put("email", email)
            if (phone.isNotBlank()) put("phone", phone)
            if (links.isNotBlank()) put("links", links)
            if (about.isNotBlank()) put("about", about)
        }
        local.db.set("profile", o.toString())
        app.toast("Saved")
    }

    Screen("About you", back) {
        Text("Your bot uses this to fill in forms — applications, sign-ups, enquiries — without inventing anything.", style = T.small, color = g.ink2)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Name"); Field(name, { name = it }, placeholder = "Your name")
            Label("Email"); Field(email, { email = it }, placeholder = "you@example.com")
            Label("Phone"); Field(phone, { phone = it }, placeholder = "+1 555…")
            Label("Links"); Field(links, { links = it }, placeholder = "portfolio, GitHub, LinkedIn — one per line")
            Label("About"); Field(about, { about = it }, placeholder = "A short summary of your work and experience")
        }
        GButton("Save", ::save, Modifier.fillMaxWidth())
    }
}
