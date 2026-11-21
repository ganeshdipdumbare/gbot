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

internal class Anim(lively: Boolean) {
    var still = false
    var mood = "idle"; private set
    private var gx = 0f; private var gy = 0f
    var gazeToX = 0f; var gazeToY = 0f
    private val gazeRate = rand(5f, 9f)
    private val phase = rand(0f, 10f)
    var t = 0f
    private var driftAt = rand(0.4f, 2.4f)
    private var y = 0f; private var vy = 0f; private var air = false
    var squash = 0f; var squashV = 0f
    private var lean = 0f; private var lift = 0f
    private var blink = 0f; private var nextBlink = rand(1f, 4f)
    private var happy = 0f
    private val etx = FloatArray(2); private val ety = FloatArray(2)
    private val esx = floatArrayOf(1f, 1f); private val esy = floatArrayOf(1f, 1f); private val erot = FloatArray(2)
    private var hopAt = -1f; private var hopPower = 3.2f
    private var first = true

    init { if (lively) driftAt = rand(0.4f, 1.4f) }

    /** A bot that just finished hops. */
    fun changeMood(m: String) {
        if (m == mood) return
        mood = m
        if (m == "done" && !air && hopAt < 0) { squashV += 2.4f; hopAt = 0.1f; hopPower = 3.2f }
    }

    /** A look change is a pop, not a cut. */
    fun pop() { squashV -= 3.2f }

    /** Where it looks: a slow wander of its own. */
    fun drift(dt: Float, lively: Boolean) {
        driftAt -= dt
        if (driftAt < 0) {
            val spread = if (lively) 2.5f else 1.6f
            driftAt = if (lively) rand(1.4f, 3.1f) else rand(2.8f, 5.2f)
            if (Random.nextFloat() < (if (lively) 0.35f else 0.45f)) { gazeToX = 0f; gazeToY = 0f }
            else { gazeToX = (Random.nextFloat() - 0.5f) * spread; gazeToY = (Random.nextFloat() - 0.5f) * spread * 0.6f }
        }
    }

    fun update(dt: Float, o: Outline, shadowBase: Float, f: Frame) {
        t += dt
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

        val g = ease(gazeRate, dt)
        gx += (gazeToX - gx) * g
        gy += (gazeToY - gy) * g

        val p = t + phase
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

        if (!still && mood != "sleepy") {
            nextBlink -= dt
            if (nextBlink < 0) {
                blink = 0.11f
                nextBlink = if (Random.nextFloat() < 0.2f) 0.26f else rand(2.4f, 6.5f)
            }
        }
        blink = max(0f, blink - dt)
        f.lids += ((if (blink > 0) 0.1f else 1f) - f.lids) * ease(60f, dt)

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
