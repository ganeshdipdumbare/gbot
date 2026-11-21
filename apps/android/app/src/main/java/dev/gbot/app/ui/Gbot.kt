package dev.gbot.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gbot.app.net.Look
import dev.gbot.app.ui.bot3d.BotGL
import dev.gbot.app.ui.bot3d.Gbot3D

val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * A gbot: one of eight geometric bodies, a bold colour and two eyes. Drawn by
 * the same 3D rig as the website (bot3d/), so it looks and moves the same on
 * both: a bevelled pillow under three.js's lights, breathing, blinking,
 * looking around and hopping when its work is done.
 *
 * There is no 2D version. Where a phone can't run the rig (no OpenGL ES 3,
 * or unit tests), the bot's space is kept but left empty.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Gbot(
    look: Look?, seed: String, size: Int, modifier: Modifier = Modifier,
    mood: String = "idle", live: Boolean = false, lively: Boolean = false,
    sharedKey: String? = null,
) {
    val shared = LocalSharedScope.current
    val anim = LocalAnimScope.current
    val shaped = if (sharedKey != null && shared != null && anim != null) {
        with(shared) { modifier.sharedElement(rememberSharedContentState(sharedKey), anim, boundsTransform = Motion.avatarFly) }
    } else {
        modifier
    }
    val box = shaped.size(size.dp).semantics { contentDescription = "bot" }
    if (!BotGL.ok) return Box(box)
    val t = remember(look, seed) { resolveLook(look, seed) }
    val g = LocalG.current
    Gbot3D(t, size, box, mood, live, lively, g.bg, g.live)
}
