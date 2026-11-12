package dev.gbot.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.net.Bot
import dev.gbot.app.net.SlackInstall
import dev.gbot.app.net.SlackResp
import dev.gbot.app.ui.Confirm
import dev.gbot.app.ui.Field
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.Label
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.Panel
import dev.gbot.app.ui.Screen
import dev.gbot.app.ui.SectionTitle
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.attempt
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Connect a Slack workspace and say which channel each bot answers in.
 *
 * gbot does not host a Slack app, so there is no one-click install: you
 * create the app in your own workspace and paste its two secrets here.
 * They are sealed with the server key and never come back out.
 */
@Composable
fun SlackTab(back: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    val s by app.store.state.collectAsState()
    val clip = LocalClipboardManager.current
    var data by remember { mutableStateOf<SlackResp?>(null) }
    var disconnecting by remember { mutableStateOf<SlackInstall?>(null) }
    val local = app.mode == Mode.Local

    suspend fun reload() { data = app.api.slack() }
    LaunchedEffect(Unit) { runCatching { reload() } }
    // On the phone a workspace connects in the background: watch it come up.
    LaunchedEffect(data) {
        if (data?.installs?.any { it.status == "connecting" } == true) { kotlinx.coroutines.delay(1500); runCatching { reload() } }
    }

    Screen("Slack", back) {
        val d = data
        if (d == null) {
            Text("Loading…", style = T.small, color = g.ink3)
            return@Screen
        }

        if (d.socket) Panel {
            Text("Set up a Slack app for this phone", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
            Text("Slack can't reach a phone, so the phone reaches Slack instead (Socket Mode). In your Slack app:", style = T.small, color = g.ink2)
            Text(
                "1. Socket Mode → turn it on, and make an app-level token with connections:write (xapp-…).\n" +
                    "2. Event Subscriptions → subscribe to app_mention.\n" +
                    "3. OAuth & Permissions → add app_mentions:read and chat:write, install the app, and copy the bot token (xoxb-…).",
                style = T.tiny, color = g.ink3,
            )
        } else if (d.eventUrl.isEmpty()) Panel {
            Text("Set a public address first", style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
            Text("Slack delivers events over the internet, so the server needs GBOT_PUBLIC_URL set to the https:// address people open.", style = T.small, color = g.ink2)
        } else {
            Panel {
                Label("Request URL", "paste this into your Slack app")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.eventUrl, style = T.small.copy(fontFamily = FontFamily.Monospace), color = g.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconBtn("copy", "Copy request URL", { clip.setText(AnnotatedString(d.eventUrl)); app.toast("Copied") }, size = 34.dp)
                }
                Text("In Slack: Event Subscriptions → Request URL, then subscribe to app_mention. Give the app the chat:write scope and install it.", style = T.tiny, color = g.ink3)
            }
        }

        if (d.installs.isNotEmpty()) SectionTitle("Connected")
        d.installs.forEach { install ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(install.teamName.ifEmpty { "Slack workspace" }, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink)
                        Text(
                            install.teamId + when (install.status) { "listening" -> " · listening"; "connecting" -> " · connecting…"; else -> "" },
                            style = T.tiny.copy(fontFamily = FontFamily.Monospace), color = g.ink3,
                        )
                    }
                    GButton("Disconnect", { disconnecting = install }, variant = Variant.Danger, small = true)
                }
                if (install.links.isEmpty()) {
                    Text("No channels yet. A channel has to be linked before a bot answers in it.", style = T.small, color = g.ink2)
                } else {
                    install.links.forEach { link ->
                        val bot = s.bots.find { it.id == link.botId }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (bot != null) { Gbot(bot.look, bot.id, 24); Spacer(Modifier.width(8.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text("#" + link.channel.ifEmpty { link.channelId }, style = T.small.copy(fontWeight = FontWeight.Medium), color = g.ink)
                                Text(bot?.name ?: "a bot that is gone", style = T.tiny, color = g.ink3)
                            }
                            IconBtn("close", "Unlink channel", {
                                scope.attempt("Unlinked", "Couldn't unlink") { app.api.unlinkSlack(install.id, link.channelId); reload() }
                            }, size = 32.dp)
                        }
                    }
                }
                LinkForm(install, s.bots) { channelId, channel, botId ->
                    scope.attempt("Linked", "Couldn't link") {
                        app.api.linkSlack(install.id, buildJsonObject {
                            put("channelId", channelId); put("channel", channel); put("botId", botId)
                        })
                        reload()
                    }
                }
            }
        }

        SectionTitle(if (d.installs.isEmpty()) "Connect a workspace" else "Connect another")
        if (d.socket) {
            SocketForm { bot, appToken ->
                scope.attempt("Slack connected", "Couldn't connect") {
                    app.api.connectSlack(buildJsonObject { put("botToken", bot); put("appToken", appToken) })
                    reload()
                }
            }
            Text("Both tokens stay on this phone and are never shown to bots. While a workspace is connected, a quiet notification keeps gbot listening.", style = T.tiny, color = g.ink3)
        } else {
            ConnectForm { teamId, teamName, token, secret ->
                scope.attempt("Slack connected", "Couldn't connect") {
                    app.api.connectSlack(buildJsonObject {
                        put("teamId", teamId); put("teamName", teamName)
                        put("botToken", token); put("signingSecret", secret)
                    })
                    reload()
                }
            }
            Text("The bot token and signing secret are encrypted with the server key. Neither is ever sent back to this app.", style = T.tiny, color = g.ink3)
        }
    }

    disconnecting?.let { install ->
        Confirm(
            "Disconnect ${install.teamName.ifEmpty { "this workspace" }}?",
            "Its channel links go too. Your bots and their chats are untouched.",
            "Disconnect",
            onConfirm = { scope.attempt("Disconnected", "Couldn't disconnect") { app.api.disconnectSlack(install.id); reload() } },
            onDismiss = { disconnecting = null },
        )
    }
}

/** Phone mode: the workspace and its name come from the bot token. */
@Composable
private fun SocketForm(onConnect: (String, String) -> Unit) {
    var bot by remember { mutableStateOf("") }
    var appToken by remember { mutableStateOf("") }
    Panel {
        Label("Bot token", "starts with xoxb-")
        Field(bot, { bot = it.trim() }, placeholder = "xoxb-…", password = true)
        Label("App-level token", "starts with xapp-, with connections:write")
        Field(appToken, { appToken = it.trim() }, placeholder = "xapp-…", password = true)
        GButton("Connect", {
            onConnect(bot, appToken)
            bot = ""; appToken = ""
        }, Modifier.fillMaxWidth(), enabled = bot.startsWith("xoxb-") && appToken.startsWith("xapp-"), icon = "plug")
    }
}

@Composable
private fun ConnectForm(onConnect: (String, String, String, String) -> Unit) {
    var teamId by remember { mutableStateOf("") }
    var teamName by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val ready = teamId.isNotBlank() && token.isNotBlank() && secret.isNotBlank()
    Panel {
        Label("Workspace ID", "Slack calls it the Team ID, like T0123ABC")
        Field(teamId, { teamId = it.trim() }, placeholder = "T0123ABC")
        Label("Name", "just for this list")
        Field(teamName, { teamName = it }, placeholder = "Acme")
        Label("Bot token", "starts with xoxb-")
        Field(token, { token = it.trim() }, placeholder = "xoxb-…", password = true)
        Label("Signing secret", "from the app's Basic Information")
        Field(secret, { secret = it.trim() }, placeholder = "…", password = true)
        GButton("Connect", {
            onConnect(teamId, teamName, token, secret)
            teamId = ""; teamName = ""; token = ""; secret = ""
        }, Modifier.fillMaxWidth(), enabled = ready, icon = "plug")
    }
}

@Composable
private fun LinkForm(install: SlackInstall, bots: List<Bot>, onLink: (String, String, String) -> Unit) {
    val g = LocalG.current
    var channelId by remember(install.id) { mutableStateOf("") }
    var channel by remember(install.id) { mutableStateOf("") }
    var botId by remember(install.id) { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }
    val chosen = bots.find { it.id == botId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Add a channel", "the bot answers when mentioned there")
        Field(channelId, { channelId = it.trim() }, placeholder = "Channel ID, like C0123ABC")
        Field(channel, { channel = it.trim().removePrefix("#") }, placeholder = "Name, like build (optional)")
        Box {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GButton(chosen?.name ?: "Choose a bot", { open = true }, variant = Variant.Soft, small = true)
                Spacer(Modifier.width(8.dp))
                if (chosen != null) Gbot(chosen.look, chosen.id, 22)
            }
            DropdownMenu(open, { open = false }, containerColor = if (g.dark) androidx.compose.ui.graphics.Color(0xFF2A2A2A) else androidx.compose.ui.graphics.Color.White) {
                bots.forEach { b ->
                    DropdownMenuItem({ Text(b.name, style = T.body) }, { botId = b.id; open = false }, leadingIcon = { Gbot(b.look, b.id, 22) })
                }
            }
        }
        GButton("Link channel", {
            onLink(channelId, channel, botId)
            channelId = ""; channel = ""; botId = ""
        }, Modifier.fillMaxWidth(), enabled = channelId.isNotBlank() && botId.isNotBlank(), variant = Variant.Soft, icon = "plus")
    }
}
