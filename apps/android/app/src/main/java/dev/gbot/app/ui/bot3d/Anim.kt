package dev.gbot.app.ui.bot3d

import dev.gbot.app.ui.pose
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// One avatar's motion, ported from update() in packages/characters/src/gl.ts:
// a spring squash, a gravity hop when work is done, breathing, mood leans,
// blinks and a wandering gaze. It produces a Frame the renderer draws.

private const val GRAVITY = 17f
private const val TAU = (PI * 2).toFloat()
internal const val DEG = (PI / 180).toFloat()

private fun ease(rate: Float, dt: Float) = 1f - exp(-rate * dt)
private fun rand(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
private fun clamp(v: Float, a: Float = -1f, b: Float = 1f) = max(a, min(b, v))

/** Where everything sits this frame, in the rig's world units. */
internal class Frame {
    var sx = 1f; var sy = 1f; var y = 0f
    var rx = 0f; var ry = 0f; var rz = 0f
    var shadowW = 0.78f; var shadowD = 0.78f * 0.8f; var shadowA = 0.2f
    var faceX = 0f; var faceY = 0f
    var lids = 1f
    val eyeX = FloatArray(2); val eyeY = FloatArray(2); val eyeRot = FloatArray(2)
    val eyeSX = FloatArray(2); val eyeSY = FloatArray(2)
    var open = true; var shut = false
    var eyeScale = 1f
}

/**
 * One bot's clock, shared by every copy of it on screen: where it is
 * looking, when it blinks and where it is in its breath. The header, the
 * chat and the home list all read the same one, so a bot moves as one bot
 * wherever it shows up, and a screen change doesn't restart its motion.
 * It runs on wall time, so copies painted at different rates still agree.
 */
internal class Pulse {
    /** Where it looks, from -1 to 1 each way; each copy scales it by its reach. */
    var gazeX = 0f; private set
    var gazeY = 0f; private set
    val gazeRate = rand(5f, 9f)
    val phase = rand(0f, 10f)
    var t = 0f; private set
    /** Seconds left of the blink under way; 0 when the eyes are open. */
    var blink = 0f; private set
    private var driftAt = rand(0.4f, 2.4f)
    private var nextBlink = rand(1f, 4f)
    private var last = 0L

    fun advance(now: Long) {
        if (now <= last) return
        val dt = if (last == 0L) 0f else (now - last) / 1e9f
        last = now
        t += dt
        driftAt -= dt
        if (driftAt < 0) {
            driftAt = rand(2.2f, 4.6f)
            if (Random.nextFloat() < 0.3f) { gazeX = 0f; gazeY = 0f }
            else { gazeX = rand(-1f, 1f); gazeY = rand(-1f, 1f) }
        }
        blink = max(0f, blink - dt)
        nextBlink -= dt
        if (nextBlink < 0) {
            blink = 0.11f
            nextBlink = if (Random.nextFloat() < 0.2f) 0.26f else rand(2.4f, 6.5f)
        }
    }

    companion object {
        private val all = HashMap<String, Pulse>()
        fun of(seed: String): Pulse = all.getOrPut(seed) { Pulse() }
    }
}

internal class Anim(var pulse: Pulse) {
    var still = false
    var mood = "idle"; private set
    private var gx = 0f; private var gy = 0f
    var gazeToX = 0f; var gazeToY = 0f
    val t: Float get() = pulse.t
    private var y = 0f; private var vy = 0f; private var air = false
    var squash = 0f; var squashV = 0f
    private var lean = 0f; private var lift = 0f
    private var happy = 0f
    private val etx = FloatArray(2); private val ety = FloatArray(2)
    private val esx = floatArrayOf(1f, 1f); private val esy = floatArrayOf(1f, 1f); private val erot = FloatArray(2)
    private var hopAt = -1f; private var hopPower = 3.2f
    private var first = true

    /** A bot that just finished hops. */
    fun changeMood(m: String) {
        if (m == mood) return
        mood = m
        if (m == "done" && !air && hopAt < 0) { squashV += 2.4f; hopAt = 0.1f; hopPower = 3.2f }
    }

    /** A look change is a pop, not a cut. */
    fun pop() { squashV -= 3.2f }

    /**
     * Where it looks: its [Pulse]'s slow wander. A phone has no pointer to
     * follow, so it turns side to side, tips back to show its top and leans
     * in to look from below. A lively copy reaches further (±1.25 side to
     * side, ±1 up and down) than a quiet one (±0.8 each way).
     */
    fun drift(now: Long, lively: Boolean) {
        pulse.advance(now)
        val wide = if (lively) 1.25f else 0.8f
        val tall = if (lively) 1f else 0.8f
        gazeToX = pulse.gazeX * wide; gazeToY = pulse.gazeY * tall
    }

    fun update(dt: Float, o: Outline, shadowBase: Float, f: Frame) {
        if (hopAt >= 0) {
            hopAt -= dt
            if (hopAt < 0) { vy = hopPower; air = true; squashV -= 3f }
        }
        if (air) {
            vy -= GRAVITY * dt
            y += vy * dt
            if (y <= 0) { squashV += min(-vy, 9f) * 0.5f; y = 0f; vy = 0f; air = false }
        }
        // A steady spring, stepped in halves so a long frame cannot bounce it.
        repeat(2) {
            val h = dt / 2
            squashV += (-210 * squash - 13 * squashV) * h
            squash += squashV * h
        }

        val g = ease(pulse.gazeRate, dt)
        gx += (gazeToX - gx) * g
        gy += (gazeToY - gy) * g

        val p = pulse.t + pulse.phase
        var leanTo = 0f; var liftTo = 0f; var breathe = 0f; var scan = 0f
        if (!still) {
            when (mood) {
                "idle", "done" -> breathe = sin(p * TAU / 4.2f)
                "sleepy" -> breathe = sin(p * TAU / 6f) * 1.3f
                "thinking" -> { leanTo = sin(p * TAU / 3f) * 4 * DEG; scan = sin(p * TAU / 3f) * 2 * U }
                "working" -> liftTo = sin(p * PI.toFloat() / 1.15f).pow(2)
                "waiting" -> leanTo = (5.5f + 1.5f * sin(p * TAU / 2.4f)) * DEG
                "blocked" -> {
                    val c = (p % 2.6f) / 2.6f
                    if (c > 0.7f && c < 0.92f) leanTo = sin((c - 0.7f) / 0.22f * TAU * 2.5f) * 5 * DEG * (1 - (c - 0.7f) / 0.22f)
                }
            }
        } else if (mood == "waiting") leanTo = 6 * DEG
        lean += (leanTo - lean) * ease(if (mood == "blocked") 40f else 7f, dt)
        lift += (liftTo - lift) * ease(18f, dt)

        val q = squash - lift * 0.014f
        f.sy = 1 - q - breathe * 0.012f
        f.sx = 1 + q * 0.62f + breathe * 0.009f
        f.y = y + lift * 0.035f * o.height
        f.rx = -gy * 0.2f; f.ry = gx * 0.3f; f.rz = lean - gx * 0.03f

        val hh = clamp(y / 1.6f, 0f, 1f)
        val w = shadowBase * (1 - hh * 0.35f) * f.sx
        f.shadowW = w; f.shadowD = w * 0.8f
        f.shadowA = 0.2f * (1 - hh * 0.75f)

        f.faceX = o.faceX + gx * 3 * U + scan
        f.faceY = o.faceY + gy * 2 * U

        val blinking = !still && mood != "sleepy" && pulse.blink > 0
        f.lids += ((if (blinking) 0.1f else 1f) - f.lids) * ease(60f, dt)

        val isHappy = mood == "done"
        happy += ((if (isHappy) 1f else 0f) - happy) * ease(16f, dt)
        val s = o.eyeScale
        f.eyeScale = s
        val k = if (first) 1f else ease(if (still) 1000f else 15f, dt)
        first = false
        val gap = 12 * s * U
        for (i in 0..1) {
            val side = if (i == 0) -1 else 1
            val to = pose(mood, side, gx * 2.4f, gy * 1.6f)
            etx[i] += (to.tx - etx[i]) * k
            ety[i] += (to.ty - ety[i]) * k
            esx[i] += (to.sx - esx[i]) * k
            esy[i] += (to.sy - esy[i]) * k
            erot[i] += (to.rot - erot[i]) * k
            f.eyeX[i] = side * gap + (etx[i] + gx * 2.4f) * U
            f.eyeY[i] = -(ety[i] - gy * 1.6f) * U
            f.eyeRot[i] = -erot[i] * DEG
            f.eyeSX[i] = esx[i] * s
            f.eyeSY[i] = esy[i] * s * (1 - happy)
        }
        f.open = happy < 0.96f
        f.shut = happy > 0.5f
    }
}

/** How wide the floor shadow is under each body, as the web rig sets it. */
internal fun shadowBase(shape: String) = when (shape) {
    "pill" -> 0.86f
    "triangle", "arch" -> 0.92f
    else -> 0.78f
}
