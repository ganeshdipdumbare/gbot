package dev.gbot.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import dev.gbot.app.GbotApp
import dev.gbot.app.local.LocalApi
import dev.gbot.app.ui.GButton
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.T
import dev.gbot.app.ui.Variant
import dev.gbot.app.ui.TopBar
import kotlinx.coroutines.launch

/**
 * The bot's computer on this phone: a browser in a screen-shaped frame.
 * The same WebView is the small view and the full-size one, so taking over
 * is just touching the page.
 */
@Composable
fun LocalComputerScreen(nav: NavHostController, id: String) {
    val app = GbotApp.instance
    val g = LocalG.current
    val s by app.store.state.collectAsState()
    val bot = s.bots.find { it.id == id } ?: run {
        TopBar("Computer", { nav.popBackStack() })
        return
    }
    val local = app.api as? LocalApi ?: return
    val browser = local.browser
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val url by browser.url.collectAsState()
    val title by browser.title.collectAsState()
    val loading by browser.loading.collectAsState()
    val error by browser.error.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<((Array<Uri>?) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pending?.invoke(if (uri == null) null else arrayOf(uri))
        pending = null
    }

    DisposableEffect(Unit) {
        browser.chooseFiles = { accept, cb ->
            pending = cb
            picker.launch(accept.firstOrNull()?.substringBefore(';')?.ifBlank { null } ?: "*/*")
        }
        onDispose { browser.chooseFiles = null }
    }

    Column(Modifier.fillMaxSize().background(g.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(
            "${bot.name}'s computer",
            { nav.popBackStack() },
            subtitle = if (expanded) "Its browser, full size" else "A browser on this phone",
            actions = {
                IconBtn("back", "Back a page", { scope.launch { browser.back() } })
                IconBtn("next", "Forward a page", { scope.launch { browser.forward() } })
                IconBtn("refresh", "Reload", { browser.view().reload() })
                IconBtn("globe", "Open in Chrome", {
                    val target = browser.url.value
                    if (target.isNotBlank()) runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
                IconBtn(
                    if (expanded) "control" else "expand",
                    if (expanded) "Back to the small screen" else "Full size",
                    { expanded = !expanded },
                )
            },
        )

        // One WebView, one parent: only its box changes size.
        val frame = if (expanded) {
            Modifier.fillMaxWidth().weight(1f).padding(12.dp)
        } else {
            Modifier.fillMaxWidth().aspectRatio(1.6f).padding(start = 16.dp, end = 16.dp, top = 12.dp)
        }
        ScreenFrame(frame) {
            AndroidView(factory = { browser.view() }, modifier = Modifier.fillMaxSize())

            if (url.isBlank() || error != null) {
                Column(
                    Modifier.fillMaxSize().background(g.surface).padding(22.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Gbot(bot.look, bot.id, 52, mood = if (error != null) "blocked" else "sleepy")
                    Spacer(Modifier.height(8.dp))
                    Text(if (error != null) "Couldn't open that page" else "Nothing open yet", style = T.body, color = g.ink)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        error ?: "Ask ${bot.name} to look something up and it appears here.",
                        style = T.small, color = if (error != null) g.danger else g.ink2, textAlign = TextAlign.Center,
                    )
                    if (error != null) {
                        Spacer(Modifier.height(10.dp))
                        GButton("Try again", { scope.launch { browser.view().reload() } }, variant = Variant.Soft, small = true)
                    }
                }
            }

            if (!expanded) Row(
                Modifier.fillMaxWidth().align(Alignment.TopStart).background(g.chrome.copy(alpha = 0.94f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Gbot(bot.look, bot.id, 18, mood = if (loading) "working" else "idle")
                Spacer(Modifier.width(8.dp))
                Text(title.ifBlank { "Its browser" }, style = T.tiny, color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            val note = when {
                error != null -> "Couldn't load that page"
                loading -> "Loading…"
                else -> ""
            }
            if (note.isNotEmpty()) Text(
                note,
                style = T.tiny,
                color = if (error != null) g.danger else g.ink2,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                    .clip(RoundedCornerShape(50)).background(g.chrome.copy(alpha = 0.9f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }

        if (!expanded) {
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(url.ifBlank { "Nothing open yet — ask it to look something up." }, style = T.tiny, color = g.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("The bot is driving this window. Touch anything to take over; full size is in the top bar.", style = T.tiny, color = g.ink3)
                Routines(bot)
            }
        }
    }
}

@Composable
private fun ScreenFrame(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    val g = LocalG.current
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(g.surface).border(1.dp, g.line, RoundedCornerShape(18.dp)),
        content = content,
    )
}
