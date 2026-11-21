package dev.gbot.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.gbot.app.net.Look
import dev.gbot.app.ui.bot3d.BotGL
import dev.gbot.app.ui.bot3d.Gbot3D
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.random.Random

private val pathCache = HashMap<String, Path>()
private fun bodyPath(shape: String): Path =
    pathCache.getOrPut(shape) { PathParser().parsePathString(bodies.getValue(shape).d).toPath() }

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

val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * A gbot: one of eight geometric bodies, a bold colour and two eyes. Drawn by
 * the same 3D rig as the website (bot3d/), so it looks and moves the same on
 * both: a bevelled pillow under three.js's lights, breathing, blinking,
 * looking around and hopping when its work is done.
 */

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Gbot(
    look: Look?, seed: String, size: Int, modifier: Modifier = Modifier,
    mood: String = "idle", live: Boolean = false, lively: Boolean = false,
    sharedKey: String? = null,
) {
    // The real 3D rig, as on the web; the painted one only where a phone has no GL.
    if (!BotGL.ok) return GbotFlat(look, seed, size, modifier, mood, live, lively, sharedKey)
    val t = remember(look, seed) { resolveLook(look, seed) }
    val g = LocalG.current
    val shared = LocalSharedScope.current
    val anim = LocalAnimScope.current
    val shaped = if (sharedKey != null && shared != null && anim != null) {
        with(shared) { modifier.sharedElement(rememberSharedContentState(sharedKey), anim, boundsTransform = Motion.avatarFly) }
    } else {
        modifier
    }
    Gbot3D(t, size, shaped.size(size.dp).semantics { contentDescription = "bot" }, mood, live, lively, g.bg, g.live)
}

/** The bot painted in 2D, for phones that cannot run the 3D rig. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun GbotFlat(
    look: Look?, seed: String, size: Int, modifier: Modifier = Modifier,
    mood: String = "idle", live: Boolean = false, lively: Boolean = false,
    sharedKey: String? = null,
) {
    val t = remember(look, seed) { resolveLook(look, seed) }
    val hue = hues.getValue(t.hue)
    val meta = bodies.getValue(t.shape)
    val path = remember(t.shape) { bodyPath(t.shape) }
    val bounds = remember(path) { path.getBounds() }
    val g = LocalG.current
    val happy = mood == "done" || mood == "happy"
    val small = size < 48
    val simple = size < 56
    // Below this the avatar is a dot in a list: one flat layer, no perspective.
    val flat = size < 30

    // Each bot keeps its own clock — phase, speed and how much it moves — so
    // a roster never breathes in lockstep, and no two bots feel alike at rest.
    val own = remember(seed) {
        val r = rng("beat:$seed")
        floatArrayOf(r().toFloat(), 0.88f + 0.24f * r().toFloat(), 0.85f + 0.3f * r().toFloat())
    }

    var blink by remember { mutableStateOf(false) }
    if (!happy && mood != "sleepy") LaunchedEffect(lively) {
        while (true) {
            delay(if (lively) 1500L + Random.nextLong(2400) else 2600L + Random.nextLong(4400))
            blink = true; delay(110); blink = false
            if (lively && Random.nextFloat() < 0.35f) { delay(190); blink = true; delay(95); blink = false }
        }
    }
    val blinkY by animateFloatAsState(if (blink) 0.1f else 1f, tween(70), label = "blink")

    var drift by remember { mutableStateOf(Offset.Zero) }
    if (mood == "idle" && !small) LaunchedEffect(lively) {
        while (true) {
            delay(if (lively) 1400L + Random.nextLong(1700) else 2800L + Random.nextLong(2400))
            val spread = if (lively) 2.5f else 1.6f
            drift = if (Random.nextFloat() < (if (lively) 0.35f else 0.45f)) Offset.Zero
            else Offset((Random.nextFloat() - 0.5f) * spread, (Random.nextFloat() - 0.5f) * (if (lively) 1.3f else 0.8f))
        }
    } else LaunchedEffect(Unit) { drift = Offset.Zero }
    val soft = spring<Float>(dampingRatio = 0.62f, stiffness = 260f)
    val gazeX by animateFloatAsState(drift.x, soft, label = "gx")
    val gazeY by animateFloatAsState(drift.y, soft, label = "gy")
    val gx = gazeX * 2.4f; val gy = gazeY * 1.6f
    val px = gazeX * 3f; val py = gazeY * 2f
    // The web turns the whole body toward what it looks at; so does this one.
    val yaw = if (flat) 0f else gazeX.coerceIn(-1.2f, 1.2f) * 13f
    val pitch = if (flat) 0f else -gazeY.coerceIn(-1.2f, 1.2f) * 8f

    // Eyes land fast: a mood should be legible before the body finishes moving.
    val bouncy = spring<Float>(dampingRatio = 0.52f, stiffness = 700f)
    val poses = listOf(-1, 1).map { pose(mood, it, gx, gy) }
    val l = poses[0]; val r = poses[1]
    val ltx by animateFloatAsState(l.tx, bouncy, label = "ltx"); val lty by animateFloatAsState(l.ty, bouncy, label = "lty")
    val lsx by animateFloatAsState(l.sx, bouncy, label = "lsx"); val lsy by animateFloatAsState(l.sy, bouncy, label = "lsy")
    val lrot by animateFloatAsState(l.rot, bouncy, label = "lrot")
    val rtx by animateFloatAsState(r.tx, bouncy, label = "rtx"); val rty by animateFloatAsState(r.ty, bouncy, label = "rty")
    val rsx by animateFloatAsState(r.sx, bouncy, label = "rsx"); val rsy by animateFloatAsState(r.sy, bouncy, label = "rsy")
    val rrot by animateFloatAsState(r.rot, bouncy, label = "rrot")
    val happyA by animateFloatAsState(if (happy) 1f else 0f, tween(150), label = "happy")

    val period = ((when {
        mood == "idle" && small -> 0
        mood == "sleepy" && small -> 0
        mood == "idle" -> if (lively) 3100 else 4200
        mood == "thinking" -> 3000
        mood == "working" -> 1150
        mood == "waiting" -> 2400
        mood == "blocked" -> 2600
        mood == "sleepy" -> 6000
        else -> 0
    }) * own[1]).toInt()
    val phase = if (period > 0) {
        val inf = rememberInfiniteTransition(label = "body")
        val p by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        (p + own[0]) % 1f
    } else 0f

    // A mood change lands with a little weight behind it.
    val pop = remember { Animatable(0f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(mood) {
        if (settled && !flat) { pop.snapTo(1f); pop.animateTo(0f, spring(dampingRatio = 0.34f, stiffness = 900f)) }
        settled = true
    }
    val hop = remember { Animatable(1f) }
    LaunchedEffect(happy) { if (happy) { hop.snapTo(0f); hop.animateTo(1f, tween(640, easing = LinearEasing)) } }
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
            // A hop with weight: a quick crouch, a gravity arc, a landing squash.
            val h = hop.value
            val air = ((h - 0.10f) / 0.62f).coerceIn(0f, 1f)
            ty = if (h > 0.10f && h < 0.72f) -0.21f * (4f * air * (1f - air)) else 0f
            val squash = keys(h, 0f to 0f, .09f to 1f, .2f to 0f, .7f to 0f, .78f to 1f, 1f to 0f)
            val stretch = if (h > 0.10f && h < 0.72f) 1f - abs(2f * air - 1f) else 0f
            sx = 1f + 0.11f * squash - 0.05f * stretch
            sy = 1f - 0.11f * squash + 0.06f * stretch
        }
        period == 0 -> {}
        mood == "idle" && lively -> {
            val a = own[2]
            rot = tilt - 1.4f * a + 2.8f * a * k; ty = -0.016f * a * k
            sx = 1 + 0.012f * a * k; sy = 1 - 0.012f * a * k
        }
        mood == "idle" || mood == "sleepy" -> { sx = 1 + 0.012f * own[2] * k; sy = 1 - 0.015f * own[2] * k }
        mood == "thinking" -> { rot = -4f + 8f * k; scan = -2f + 4f * k }
        mood == "working" -> { ty = -0.035f * k; sx = 1.018f - 0.028f * k; sy = 0.986f + 0.026f * k }
        mood == "waiting" -> rot = -7f + 3f * k
        mood == "blocked" -> rot = keys(phase, 0f to 0f, .7f to 0f, .74f to -5f, .78f to 5f, .82f to -4f, .86f to 3f, .9f to 0f, 1f to 0f)
    }
    if (!happy) { sx += 0.05f * pop.value; sy -= 0.05f * pop.value }

    val eye = Color(hue.eye)
    val (ew, eh) = eyeSizes.getValue(t.eyes)
    val es = meta.eyeScale
    val w = ew * es; val h = eh * es; val gap = 12 * es

    val shared = LocalSharedScope.current
    val anim = LocalAnimScope.current
    val shaped = if (sharedKey != null && shared != null && anim != null) {
        with(shared) { modifier.sharedElement(rememberSharedContentState(sharedKey), anim, boundsTransform = Motion.avatarFly) }
    } else {
        modifier
    }

    val paint = remember(hue.body, bounds, g.dark) { Glow.paint(hue.body, bounds, g.dark) }
    // Off the ground the shadow shrinks and fades, as it does in the web rig.
    val lift = (-ty / 0.21f).coerceIn(0f, 1f)
    val bg = g.bg

    val body: DrawScope.() -> Unit = {
        box {
            withTransform({
                rotate(rot, Offset(bounds.center.x, bounds.bottom))
                translate(0f, ty * bounds.height)
                scale(sx, sy, Offset(bounds.center.x, bounds.bottom))
            }) {
                drawBody(path, bounds, paint, simple, flat, px, py)
                withTransform({
                    translate(px + scan, py)
                    scale(1f, blinkY, Offset(meta.faceX, meta.faceY))
                }) {
                    for ((i, side) in listOf(-1, 1).withIndex()) {
                        val cx = meta.faceX + side * gap
                        val cy = meta.faceY
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
                                moveTo(cx - 7 * es, cy + 3)
                                quadraticTo(cx, cy - 7 * es, cx + 7 * es, cy + 3)
                            }
                            drawPath(arc, eye.copy(alpha = happyA), style = Stroke(width = 6 * es, cap = StrokeCap.Round))
                        }
                    }
                }
            }
        }
    }
    val ground: DrawScope.() -> Unit = {
        box { drawGround(paint, lift, sx) }
    }
    val dot: DrawScope.() -> Unit = {
        if (live) box {
            withTransform({ scale(pulse, pulse, Offset(84f, 84f)) }) {
                drawCircle(bg, 9f, Offset(84f, 84f))
                drawCircle(g.live, 7.5f, Offset(84f, 84f))
            }
        }
    }

    val canvas = shaped.size(size.dp).semantics { contentDescription = "bot" }
    if (flat) {
        Canvas(canvas) { ground(); body(); dot() }
    } else {
        // The body turns in its own layer, so the shadow stays on the floor.
        val pivotY = (3f + bounds.bottom) / 106f
        Box(canvas) {
            Canvas(Modifier.fillMaxSize()) { ground() }
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    rotationY = yaw
                    rotationX = pitch
                    cameraDistance = 5f
                    transformOrigin = TransformOrigin(0.5f, pivotY)
                },
            ) { body() }
            if (live) Canvas(Modifier.fillMaxSize()) { dot() }
        }
    }
}

/** Draw in the 106-unit box the bodies are cut for, margin included. */
internal inline fun DrawScope.box(crossinline content: DrawScope.() -> Unit) {
    val unit = size.minDimension / 106f
    withTransform({ scale(unit, unit, Offset.Zero); translate(3f, 3f) }) { content() }
}

internal fun DrawScope.drawGround(paint: BotPaint, lift: Float, sx: Float) {
    val w = (1f - lift * 0.35f) * sx
    withTransform({ scale(w, w * 3.4f / 30f, Offset(50f, 95.5f)) }) {
        drawCircle(paint.shadow, 30f, Offset(50f, 95.5f), alpha = 1f - lift * 0.75f)
    }
}

internal fun DrawScope.drawBody(
    path: Path, b: Rect, paint: BotPaint, simple: Boolean, flat: Boolean, px: Float, py: Float,
) {
    // The body's thickness: the outline stepped down, far edge first, so the
    // side shades smoothly into the face like the web rig's extrusion.
    if (!flat) for (i in Glow.DEPTH downTo 1) {
        withTransform({ translate(0f, i.toFloat()) }) { drawPath(path, paint.wall) }
    }
    drawPath(path, paint.face)
    clipPath(path) {
        withTransform({ translate(-px * 0.25f, -py * 0.25f) }) { drawRect(paint.dome, b.topLeft, b.size) }
        if (!flat) {
            drawRect(paint.falloff, b.topLeft, b.size)
            // A rounded bevel: a blurred stroke, so the light rolls over the
            // edge instead of drawing a ring.
            val native = path.asAndroidPath()
            drawIntoCanvas { c ->
                c.nativeCanvas.drawPath(native, paint.crown)
                c.nativeCanvas.drawPath(native, paint.under)
            }
        }
    }
    drawPath(path, paint.contour, style = Stroke(if (flat) 1.8f else 1.2f))
}
