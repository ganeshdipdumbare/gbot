package dev.gbot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gbot.app.net.Look
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.GbotLogo
import dev.gbot.app.ui.GbotTheme
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.T
import dev.gbot.app.ui.hueNames
import dev.gbot.app.ui.shapes
import kotlinx.coroutines.delay

/**
 * Debug-only contact sheet: every body, hue, mood and size on one screen, so
 * the rig can be judged in both themes without hunting through the app.
 *
 *     adb shell am start -n dev.gbot.app/.GbotPreviewActivity
 */
class GbotPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GbotTheme { Sheet() } }
    }
}

private val moods = listOf("idle", "thinking", "working", "waiting", "blocked", "sleepy", "done")

@Composable
private fun Sheet() {
    val g = LocalG.current
    Column(
        Modifier.fillMaxSize().background(g.bg).verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GbotLogo(30)
            Text("  gbot", style = T.title, color = g.ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            for (s in listOf(28, 44, 72, 108)) GbotLogo(s)
        }
        Text("hues x shapes", style = T.small, color = g.ink2)
        for (shape in shapes) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (hue in hueNames + listOf("white")) Gbot(Look(shape, hue, "dot"), "p-$shape-$hue", 42)
        }
        Text("moods (cobalt circle / cream pill)", style = T.small, color = g.ink2)
        for (hue in listOf("cobalt", "cream")) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (m in moods) Gbot(Look(if (hue == "cobalt") "circle" else "pill", hue, "pill"), "m-$m-$hue", 48, mood = m)
        }
        Text("mood cycle", style = T.small, color = g.ink2)
        Cycle()
        Text("sizes", style = T.small, color = g.ink2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            for (s in listOf(16, 22, 28, 34, 48, 64, 88)) Gbot(Look("circle", "grape", "pill"), "s-$s", s)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            for (s in listOf(16, 22, 28, 34, 48, 64, 88)) Gbot(Look("cloud", "cream", "dot"), "s2-$s", s, live = true)
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** The same three bots walking the moods, for watching the transitions. */
@Composable
private fun Cycle() {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(1600); i = (i + 1) % moods.size }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (hue in listOf("cobalt", "coral", "cream")) Gbot(Look("circle", hue, "pill"), "c-$hue", 72, mood = moods[i])
    }
}
