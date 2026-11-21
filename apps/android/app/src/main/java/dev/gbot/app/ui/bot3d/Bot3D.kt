package dev.gbot.app.ui.bot3d

import android.graphics.Bitmap
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.gbot.app.ui.Traits
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** One on-screen bot: its own clock and motion, painted by the shared rig. */
private class BotView(lively: Boolean) {
    val anim = Anim(lively)
    val frame = Frame()
    var look: Traits? = null
    var mood = "idle"
    var lively = lively
    var still = false
    var size = 48
    var px = 0
    var dirty = true
    private var painted = 0L
    private var last = 0L
    private var busy = false
    /** Paints that failed in a row for this bot, and when it may try again. */
    private var fails = 0
    private var retryAt = 0L
    /** This bot gave up on 3D (its paints keep failing): it is drawn in 2D. */
    val flat = androidx.compose.runtime.mutableStateOf(false)

    /**
     * A failed paint backs off before the next try, so a broken bot doesn't
     * rebuild the shared rig every frame; after [TRIES] in a row this bot
     * alone drops to the 2D painter. Other bots succeeding doesn't hide it.
     */
    private fun failed(now: Long) {
        fails++
        if (fails >= TRIES) flat.value = true
        else { retryAt = now + 250_000_000L * fails; dirty = true }
    }
    private var gone = false
    private var pixels: ByteBuffer? = null
    var bitmap: Bitmap? = null
    var image: ImageBitmap? = null
    val version = mutableIntStateOf(0)

    private fun advance(now: Long) {
        val look = look ?: return
        val dt = if (last == 0L) 1f / 60 else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        anim.still = still
        // Where it looks: a slow wander of its own, as on the web.
        anim.drift(dt, lively)
        anim.changeMood(mood)
        anim.update(dt, BotGL.outlineOf(look.shape), shadowBase(look.shape), frame)
    }

    private fun buffer(): ByteBuffer {
        val need = px * px * 4
        val b = pixels?.takeIf { it.capacity() >= need } ?: ByteBuffer.allocateDirect(need).order(ByteOrder.nativeOrder())
        pixels = b
        b.limit(need)
        return b
    }

    private fun show(paintedPx: Int, b: ByteBuffer) {
        var bmp = bitmap
        if (bmp == null || bmp.width != paintedPx) {
            bmp = Bitmap.createBitmap(paintedPx, paintedPx, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            image = bmp.asImageBitmap()
        }
        b.rewind()
        bmp.copyPixelsFromBuffer(b)
        version.intValue++
    }

    /**
     * The first paint waits for the rig, so a bot never flashes in empty —
     * but only while this frame's [Budget] lasts; past it, bots paint on the
     * next frame instead of holding up the UI.
     */
    fun paintNow() {
        val look = look ?: return
        if (!Budget.open()) return
        val start = SystemClock.uptimeMillis()
        advance(System.nanoTime())
        val p = px; val b = buffer()
        var ok = false
        BotGL.now { ok = BotGL.paint(look, frame, p, b) }
        Budget.spend(SystemClock.uptimeMillis() - start)
        if (ok) show(p, b)
        painted = System.nanoTime(); dirty = false
        if (ok) fails = 0 else failed(painted)
    }

    fun step(now: Long) {
        if (busy || gone || flat.value || now < retryAt) return
        val look = look ?: return
        val interval = when {
            still -> Long.MAX_VALUE
            size >= 64 -> 0L
            size >= 32 -> 50_000_000L
            else -> 90_000_000L
        }
        if (!dirty && now - painted < interval) return
        advance(now)
        busy = true
        val p = px; val b = buffer()
        BotGL.post {
            // paint() never throws; a failed paint just isn't shown, and the
            // bot tries again next frame.
            val ok = BotGL.paint(look, frame, p, b)
            main.post {
                busy = false
                if (!ok) failed(System.nanoTime())
                else { fails = 0; if (!gone) show(p, b) }
            }
        }
        painted = now
        dirty = false
    }

    fun dispose() { gone = true }

    companion object {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
    }
}

/** How long first paints may hold up one frame, so a full screen of bots opens smoothly. */
private object Budget {
    private const val MS = 6L
    private var frame = 0L
    private var used = 0L
    fun open(): Boolean {
        val now = SystemClock.uptimeMillis() / 16
        if (now != frame) { frame = now; used = 0 }
        return used < MS
    }
    fun spend(ms: Long) { used += ms }
}

private const val TAU = (PI * 2).toFloat()
private const val TRIES = 3

@Composable
internal fun Gbot3D(
    t: Traits, size: Int, modifier: Modifier, mood: String, live: Boolean, lively: Boolean,
    bg: Color, liveColor: Color,
    /** The 2D bot, drawn instead if this one's 3D paints keep failing. */
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val view = remember { BotView(lively) }
    // Reduced motion: a still bot, as the web does for prefers-reduced-motion.
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val px = (size * density).roundToInt().coerceIn(8, BotGL.MAX_PX)
    val m = if (mood == "happy") "done" else mood
    val prev = view.look
    if (prev != null && prev != t) view.anim.pop()
    if (prev != t || view.mood != m || view.px != px) view.dirty = true
    view.look = t; view.mood = m; view.lively = lively; view.size = size; view.px = px; view.still = still
    remember(view) { view.paintNow(); true }
    if (view.flat.value) return fallback()

    LaunchedEffect(view) {
        while (true) withFrameNanos { view.step(it) }
    }
    DisposableEffect(view) { onDispose { view.dispose() } }

    Canvas(modifier) {
        view.version.intValue
        val img = view.image ?: return@Canvas
        drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset.Zero,
            IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.Low)
        if (live) {
            // The web's live dot: a page-coloured ring, a pulsing purple dot.
            val unit = this.size.minDimension / 106f
            val c = Offset(87f * unit, 87f * unit)
            val r = 7.5f * unit
            val pulse = if (still) 1f else 1f - 0.18f * ((1 - cos(view.anim.t * TAU / 1.8f)) / 2)
            drawCircle(bg, r * 1.9f, c)
            drawCircle(liveColor, r * pulse, c)
        }
    }
}
