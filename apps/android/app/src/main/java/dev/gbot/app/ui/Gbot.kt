package dev.gbot.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gbot.app.net.Look
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.random.Random

private val pathCache = HashMap<String, Path>()
private fun bodyPath(shape: String): Path = pathCache.getOrPut(shape) { PathParser().parsePathString(bodies.getValue(shape).d).toPath() }

/** 0 → 1 → 0 smoothly over one period. */
private fun tri(p: Float) = ((1 - cos(2 * PI * p)) / 2).toFloat()

/** Piecewise-linear keyframes: (at, value) pairs. */
private fun keys(p: Float, vararg k: Pair<Float, Float>): Float {
    for (i in 1 until k.size) if (p <= k[i].first) {
        val (a, va) = k[i - 1]; val (b, vb) = k[i]
        return va + (vb - va) * ((p - a) / (b - a)).coerceIn(0f, 1f)
    }
    return k.last().second
}

/**
 * A gbot: one of eight geometric bodies, a bold color and two eyes. All
 * of its feelings are carried by its eyes and the way its body moves,
 * exactly like the web character.
 */
/**
 * The scopes a shared element needs, carried in context so every Gbot on
 * the way to a chat does not have to take them as parameters. Null outside
 * a SharedTransitionLayout -- in tests, for instance -- where [Gbot] simply
 * draws itself.
 */
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Gbot(
    look: Look?, seed: String, size: Int, modifier: Modifier = Modifier,
    mood: String = "idle", live: Boolean = false, lively: Boolean = false,
    sharedKey: String? = null,
) {
    val t = remember(look, seed) { resolveLook(look, seed) }
    val hue = hues.getValue(t.hue)
    val body = bodies.getValue(t.shape)
    val path = remember(t.shape) { bodyPath(t.shape) }
    val bounds = remember(path) { path.getBounds() }
    val bg = LocalG.current.bg
    val happy = mood == "done" || mood == "happy"
    val small = size < 48
    val simple = size < 56

    // Blinking, now and then.
    var blink by remember { mutableStateOf(false) }
    if (!happy && mood != "sleepy") LaunchedEffect(lively) {
        while (true) {
            delay(if (lively) 1500L + Random.nextLong(2400) else 2600L + Random.nextLong(4400))
            blink = true; delay(110); blink = false
            if (lively && Random.nextFloat() < 0.35f) { delay(190); blink = true; delay(95); blink = false }
        }
    }
    val blinkY by animateFloatAsState(if (blink) 0.1f else 1f, tween(80), label = "blink")

    // At rest the eyes wander: calm, slightly curious.
    var drift by remember { mutableStateOf(Offset.Zero) }
    if (mood == "idle" && !small) LaunchedEffect(lively) {
        while (true) {
            delay(if (lively) 1400L + Random.nextLong(1700) else 2800L + Random.nextLong(2400))
            val spread = if (lively) 2.5f else 1.6f
            drift = if (Random.nextFloat() < (if (lively) 0.35f else 0.45f)) Offset.Zero
            else Offset((Random.nextFloat() - 0.5f) * spread, (Random.nextFloat() - 0.5f) * (if (lively) 1.3f else 0.8f))
        }
    } else LaunchedEffect(Unit) { drift = Offset.Zero }
    val soft = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow)
    val gazeX by animateFloatAsState(drift.x, soft, label = "gx")
    val gazeY by animateFloatAsState(drift.y, soft, label = "gy")
    val gx = gazeX * 2.4f; val gy = gazeY * 1.6f
    val px = gazeX * 3f; val py = gazeY * 2f

    // Eye shapes per mood, springing between them.
    val bouncy = spring<Float>(dampingRatio = 0.5f, stiffness = 420f)
    val poses = listOf(-1, 1).map { pose(mood, it, gx, gy) }
    val l = poses[0]; val r = poses[1]
    val ltx by animateFloatAsState(l.tx, bouncy, label = "ltx"); val lty by animateFloatAsState(l.ty, bouncy, label = "lty")
    val lsx by animateFloatAsState(l.sx, bouncy, label = "lsx"); val lsy by animateFloatAsState(l.sy, bouncy, label = "lsy")
    val lrot by animateFloatAsState(l.rot, bouncy, label = "lrot")
    val rtx by animateFloatAsState(r.tx, bouncy, label = "rtx"); val rty by animateFloatAsState(r.ty, bouncy, label = "rty")
    val rsx by animateFloatAsState(r.sx, bouncy, label = "rsx"); val rsy by animateFloatAsState(r.sy, bouncy, label = "rsy")
    val rrot by animateFloatAsState(r.rot, bouncy, label = "rrot")
    val happyA by animateFloatAsState(if (happy) 1f else 0f, tween(180), label = "happy")

    // Body motion by mood; small idle bots hold still.
    val period = when {
        mood == "idle" && small -> 0
        mood == "sleepy" && small -> 0
        mood == "idle" -> if (lively) 3100 else 4200
        mood == "thinking" -> 3000
        mood == "working" -> 1150
        mood == "waiting" -> 2400
        mood == "blocked" -> 2600
        mood == "sleepy" -> 6000
        else -> 0
    }
    val phase = if (period > 0) {
        val inf = rememberInfiniteTransition(label = "body")
        val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        p
    } else 0f
    val hop = remember { Animatable(1f) }
    LaunchedEffect(happy) { if (happy) { hop.snapTo(0f); hop.animateTo(1f, tween(700)) } }
    val pulse = if (live) {
        val inf = rememberInfiniteTransition(label = "live")
        val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
        1f - 0.18f * tri(p)
    } else 1f

    val tilt = gazeX * 3f
    var rot = tilt; var ty = 0f; var sx = 1f; var sy = 1f; var scan = 0f
    val k = tri(phase)
    when {
        happy -> {
            val h = hop.value
            ty = keys(h, 0f to 0f, .2f to 0f, .5f to -.14f, .8f to 0f, 1f to 0f)
            sx = keys(h, 0f to 1f, .2f to 1.08f, .5f to .95f, .8f to 1.04f, 1f to 1f)
            sy = keys(h, 0f to 1f, .2f to .9f, .5f to 1.06f, .8f to .96f, 1f to 1f)
        }
        period == 0 -> {}
        mood == "idle" && lively -> { rot = tilt - 1.4f + 2.8f * k; ty = -0.016f * k; sx = 1 + 0.012f * k; sy = 1 - 0.012f * k }
        mood == "idle" || mood == "sleepy" -> { sx = 1 + 0.012f * k; sy = 1 - 0.015f * k }
        mood == "thinking" -> { rot = -4f + 8f * k; scan = -2f + 4f * k }
        mood == "working" -> { ty = -0.035f * k; sx = 1.018f - 0.028f * k; sy = 0.986f + 0.026f * k }
        mood == "waiting" -> rot = -7f + 3f * k
        mood == "blocked" -> rot = keys(phase, 0f to 0f, .7f to 0f, .74f to -5f, .78f to 5f, .82f to -4f, .86f to 3f, .9f to 0f, 1f to 0f)
    }

    val lit = Color(mix(hue.body, 0xFFFFFFFF, 0.2f)); val base = Color(hue.body); val shade = Color(mix(hue.body, 0xFF000000, 0.1f))
    val eye = Color(hue.eye)
    val (ew, eh) = eyeSizes.getValue(t.eyes)
    val s = body.eyeScale
    val w = ew * s; val h = eh * s; val gap = 12 * s

    // The same bot in the roster and in the chat header is one element
    // that moves and resizes, not two that cross-fade.
    val shared = LocalSharedScope.current
    val anim = LocalAnimScope.current
    val shaped = if (sharedKey != null && shared != null && anim != null) {
        with(shared) { modifier.sharedElement(rememberSharedContentState(sharedKey), anim) }
    } else {
        modifier
    }
    Canvas(shaped.size(size.dp).semantics { contentDescription = "bot" }) {
        val unit = this.size.minDimension / 106f
        withTransform({ scale(unit, unit, Offset.Zero); translate(3f, 3f) }) {
            // Contact shadow.
            withTransform({ scale(1f, 3.4f / 27f, Offset(50f, 95.5f)) }) {
                drawCircle(Brush.radialGradient(0f to Color(0x33000000), 0.6f to Color(0x12000000), 1f to Color.Transparent, center = Offset(50f, 95.5f), radius = 27f), 27f, Offset(50f, 95.5f))
            }
            val pivot = Offset(bounds.center.x, bounds.bottom)
            withTransform({
                rotate(rot, pivot)
                translate(0f, ty * bounds.height)
                scale(sx, sy, pivot)
            }) {
                val fill = Brush.radialGradient(
                    0f to lit, 0.55f to base, 1f to shade,
                    center = Offset(bounds.left + bounds.width * 0.5f, bounds.top + bounds.height * 0.12f),
                    radius = maxOf(bounds.width, bounds.height) * 0.95f,
                )
                // Thickness, as on the web: a rim that lifts at the top
                // and shades below, around a slightly smaller lit face.
                drawPath(path, Brush.verticalGradient(0f to lit, 0.5f to base, 1f to shade, startY = bounds.top, endY = bounds.bottom))
                clipPath(path) {
                    withTransform({ scale(0.972f, 0.972f, bounds.center) }) { drawPath(path, fill) }
                }
                if (!simple) clipPath(path) {
                    // Soft edges: a lit rim above, a shaded one below, and a diffuse highlight.
                    drawRect(Brush.verticalGradient(0f to Color(0x2EFFFFFF), 0.18f to Color.Transparent, 0.78f to Color.Transparent, 1f to Color(0x24000000), startY = bounds.top, endY = bounds.bottom))
                    withTransform({ scale(1f, 16f / 28f, Offset(42f - px * 0.3f, 24f - py * 0.3f)) }) {
                        drawCircle(Brush.radialGradient(0f to Color(0x47FFFFFF), 1f to Color.Transparent, center = Offset(42f - px * 0.3f, 24f - py * 0.3f), radius = 28f), 28f, Offset(42f - px * 0.3f, 24f - py * 0.3f))
                    }
                }
                withTransform({
                    translate(px + scan, py)
                    scale(1f, blinkY, Offset(body.faceX, body.faceY))
                }) {
                    for ((i, side) in listOf(-1, 1).withIndex()) {
                        val cx = body.faceX + side * gap
                        val cy = body.faceY
                        if (happyA < 1f) {
                            val tx = if (i == 0) ltx else rtx; val tyE = if (i == 0) lty else rty
                            val esx = if (i == 0) lsx else rsx; val esy = if (i == 0) lsy else rsy
                            val er = if (i == 0) lrot else rrot
                            withTransform({
                                translate(tx, tyE)
                                rotate(er, Offset(cx, cy))
                                scale(esx, esy, Offset(cx, cy))
                            }) {
                                drawRoundRect(eye.copy(alpha = 1f - happyA), Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(min(w, h) / 2))
                            }
                        }
                        if (happyA > 0f) {
                            val arc = Path().apply {
                                moveTo(cx - 7 * s, cy + 3)
                                quadraticTo(cx, cy - 7 * s, cx + 7 * s, cy + 3)
                            }
                            drawPath(arc, eye.copy(alpha = happyA), style = Stroke(width = 6 * s, cap = StrokeCap.Round))
                        }
                    }
                }
            }
            if (live) {
                withTransform({ scale(pulse, pulse, Offset(84f, 84f)) }) {
                    drawCircle(bg, 9f, Offset(84f, 84f))
                    drawCircle(Color(0xFF8B5CF6), 7.5f, Offset(84f, 84f))
                }
            }
        }
    }
}
