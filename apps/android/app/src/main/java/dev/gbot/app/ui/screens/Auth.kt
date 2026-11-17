package dev.gbot.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.gbot.app.GbotApp
import dev.gbot.app.net.Http
import dev.gbot.app.net.Look
import dev.gbot.app.net.SetupProbe
import dev.gbot.app.ui.ErrorText
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.Label
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Variant
import kotlinx.coroutines.launch

/** Adds http:// when missing and drops any path; null when unusable. */
fun normalizeServer(raw: String): String? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "http://$s"
    val u = Uri.parse(s)
    if (u.host.isNullOrEmpty() || (u.scheme != "http" && u.scheme != "https")) return null
    return "${u.scheme}://${u.encodedAuthority}"
}

@Composable
private fun AuthFrame(onBack: (() -> Unit)?, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val g = LocalG.current
    Column(Modifier.fillMaxSize().background(g.bg).statusBarsPadding().imePadding()) {
        if (onBack != null) IconBtn("back", "Back", onBack, tint = g.ink, modifier = Modifier.padding(4.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
fun ServerScreen(onDone: () -> Unit, onBack: (() -> Unit)?) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(app.http.server ?: "") }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var bbGate by remember { mutableStateOf<String?>(null) }

    fun finish(base: String) {
        if (base != app.http.server) app.changeServer(base)
        onDone()
    }

    fun connect() {
        val base = normalizeServer(url) ?: run { error = "That doesn't look like an address."; return }
        val host = Uri.parse(base).host.orEmpty()
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") {
            error = "localhost only works on this computer. On your phone use a LAN address or your bb share link."
            return
        }
        busy = true; error = ""; status = "Checking $base…"
        scope.launch {
            val probe = app.http.probeSetup(base)
            busy = false; status = ""
            when (probe) {
                SetupProbe.Ok -> finish(base)
                SetupProbe.NeedsBbSignIn -> {
                    if (Http.isBbHost(host)) bbGate = base
                    else error = "Couldn't reach gbot (${probe.name}). Is the server running?"
                }
                SetupProbe.NotGbot -> error = "That URL isn't a gbot server"
                SetupProbe.Unreachable -> error = "Couldn't reach it. Check the address, or try your bb share link / Wi‑Fi address."
            }
        }
    }

    if (bbGate != null) {
        BbSignInGate(
            server = bbGate!!,
            onDone = { val base = bbGate!!; bbGate = null; finish(base) },
            onCancel = { bbGate = null },
        )
        return
    }

    AuthFrame(onBack) {
        Gbot(Look(shape = "pill", hue = "grape", eyes = "pill"), "brand", 116, mood = if (busy) "thinking" else "idle")
        Text("Connect to gbot", style = T.title, color = g.ink, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(
            "Use your computer's Wi‑Fi address, or your bb share link (https://…--8080.getbb.app). For bb links you'll sign in to bb once first.",
            style = T.body, color = g.ink2, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Field(url, { url = it }, placeholder = "https://ganeshdip--8080.getbb.app", keyboard = KeyboardType.Uri, ime = ImeAction.Go, onDone = ::connect)
        if (status.isNotEmpty()) Text(status, style = T.small, color = g.ink2)
        ErrorText(error)
        GButton(if (busy) "Checking…" else "Connect", ::connect, Modifier.fillMaxWidth(), enabled = !busy && url.isNotBlank())
        Text("or", style = T.small, color = g.ink3)
        GButton("Use a model on this phone", {
            app.useLocal()
            onDone()
        }, Modifier.fillMaxWidth(), variant = Variant.Soft, icon = "chip")
        Text(
            "Runs everything here: the model, the chat, and a browser the bot can use. No server, no account.",
            style = T.tiny, color = g.ink3, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun LoginScreen(onSignedIn: () -> Unit, onChangeServer: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var signup by remember { mutableStateOf(false) }
    var canSignUp by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var bbGate by remember { mutableStateOf(false) }

    fun loadSetup() {
        scope.launch {
            val base = app.http.server
            if (base != null && Http.isBbHost(Uri.parse(base).host)) {
                when (app.http.probeSetup(base)) {
                    SetupProbe.NeedsBbSignIn -> { bbGate = true; return@launch }
                    SetupProbe.Ok -> {}
                    else -> {}
                }
            }
            runCatching { app.api.setup() }.onSuccess { canSignUp = it.signup; signup = it.fresh }
            ready = true
        }
    }

    LaunchedEffect(Unit) { loadSetup() }

    if (bbGate) {
        val server = app.http.server ?: return
        BbSignInGate(
            server = server,
            onDone = { bbGate = false; loadSetup() },
            onCancel = { bbGate = false; onChangeServer() },
        )
        return
    }

    fun go() {
        busy = true; error = ""
        scope.launch {
            try {
                // If the bb session expired, send them through the gate again.
                val base = app.http.server
                if (base != null && Http.isBbHost(Uri.parse(base).host) && app.http.probeSetup(base) == SetupProbe.NeedsBbSignIn) {
                    busy = false; bbGate = true; return@launch
                }
                val u = if (signup) app.api.signup(email.trim(), password, name.trim()) else app.api.login(email.trim(), password)
                app.signedIn(u)
                onSignedIn()
            } catch (e: Exception) { error = e.message ?: "Couldn't sign in" } finally { busy = false }
        }
    }

    AuthFrame(null) {
        Gbot(Look(shape = "pill", hue = "grape", eyes = "pill"), "brand", 116, mood = if (error.isNotEmpty()) "blocked" else if (busy) "thinking" else "idle")
        Text(if (signup) "Create your gbot account" else "Sign in to gbot", style = T.title.copy(fontSize = 24.sp), color = g.ink, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        if (!ready) {
            Text("Checking connection…", style = T.small, color = g.ink2)
        } else {
            if (signup) Column { Label("Name"); Field(name, { name = it }, placeholder = "Your name", ime = ImeAction.Next) }
            Column { Label("Email"); Field(email, { email = it }, placeholder = "you@example.com", keyboard = KeyboardType.Email, ime = ImeAction.Next) }
            Column { Label("Password"); Field(password, { password = it }, placeholder = if (signup) "At least 8 characters" else "", password = true, ime = ImeAction.Go, onDone = ::go) }
            ErrorText(error)
            GButton(if (busy) (if (signup) "Creating…" else "Signing in…") else if (signup) "Create account" else "Sign in", ::go, Modifier.fillMaxWidth(), enabled = !busy && email.isNotBlank() && password.isNotBlank())
            if (canSignUp) GButton(if (signup) "I already have an account" else "Create an account", { signup = !signup; error = "" }, Modifier.fillMaxWidth(), variant = Variant.Ghost)
        }
        Text(
            "Connected to ${app.http.server?.removePrefix("http://")?.removePrefix("https://")}",
            style = T.tiny, color = g.ink3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        GButton("Change server", onChangeServer, Modifier.fillMaxWidth(), variant = Variant.Ghost, small = true)
    }
}
