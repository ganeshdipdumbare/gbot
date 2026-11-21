package dev.gbot.app.ui

import dev.gbot.app.net.Look
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

// The character rig's platform-free parts, ported from packages/characters:
// the same seed always gives the same bot as on the web.

val shapes = listOf("circle", "square", "triangle", "hexagon", "cloud", "pill", "arch", "diamond")
val eyeStyles = listOf("dot", "pill", "wide")

data class Hue(val body: Long, val eye: Long = 0xFF16131C)

val hues = linkedMapOf(
    "cobalt" to Hue(0xFF3B6CF6), "coral" to Hue(0xFFFF6A4D), "sun" to Hue(0xFFFFC233), "mint" to Hue(0xFF2EC78E),
    "grape" to Hue(0xFF8B5CF6), "bubblegum" to Hue(0xFFFF66B3), "sky" to Hue(0xFF3DBEF5), "cream" to Hue(0xFFF1E7D6),
    // The app's own mark only; kept out of hueNames so bot looks stay
    // stable and the pickers don't offer it.
    "white" to Hue(0xFFF7F7F4),
)
val hueNames = hues.keys.filter { it != "white" }

data class Traits(val shape: String, val hue: String, val eyes: String)

/** cyrb53's low 32 bits: what the web's rng seeds from. */
fun hashLow(str: String): Int {
    var h1 = 0xdeadbeef.toInt()
    var h2 = 0x41c6ce57
    for (ch in str) {
        val c = ch.code
        h1 = (h1 xor c) * 2654435761L.toInt()
        h2 = (h2 xor c) * 1597334677
    }
    h1 = ((h1 xor (h1 ushr 16)) * 2246822507L.toInt()) xor ((h2 xor (h2 ushr 13)) * 3266489909L.toInt())
    return h1
}

/** mulberry32, as the web's rng(). */
fun rng(seed: String): () -> Double {
    var a = hashLow(seed)
    return {
        a += 0x6d2b79f5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = t xor (t + (t xor (t ushr 7)) * (t or 61))
        ((t xor (t ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }
}

private fun <T> pick(r: () -> Double, list: List<T>) = list[floor(r() * list.size).toInt()]

fun traitsFromSeed(seed: String): Traits {
    val r = rng(seed)
    return Traits(pick(r, shapes), pick(r, hueNames), pick(r, eyeStyles))
}

private val otherShapes = mapOf("blob" to "circle", "squircle" to "square", "drop" to "diamond", "mochi" to "circle", "bun" to "arch", "bean" to "pill", "ghost" to "cloud")
private val otherHues = mapOf(
    "blue" to "cobalt", "orange" to "coral", "teal" to "mint", "red" to "coral", "pink" to "bubblegum", "purple" to "grape",
    "amber" to "sun", "brown" to "cream", "peach" to "coral", "lilac" to "grape", "butter" to "sun", "rose" to "bubblegum", "ink" to "grape",
)
private val otherEyes = mapOf("round" to "dot", "tall" to "pill", "sparkle" to "dot")

fun resolveLook(look: Look?, fallbackSeed: String): Traits {
    var t = traitsFromSeed(look?.seed?.takeIf { it.isNotEmpty() } ?: fallbackSeed)
    look?.shape?.let { s -> (otherShapes[s] ?: s).takeIf { it in shapes }?.let { t = t.copy(shape = it) } }
    look?.hue?.let { h -> (otherHues[h] ?: h).takeIf { it in hues }?.let { t = t.copy(hue = it) } }
    look?.eyes?.let { e -> (otherEyes[e] ?: e).takeIf { it in eyeStyles }?.let { t = t.copy(eyes = it) } }
    return t
}

fun randomSeed(): String = java.lang.Long.toString((Math.random() * 3.6e15).toLong(), 36).take(10)

// Bodies in a 100x100 box, each with its eye-line center and eye scale.
data class Body(val d: String, val faceX: Float, val faceY: Float, val eyeScale: Float)

private typealias Pt = Pair<Double, Double>

private fun f(p: Pt) = "%.2f %.2f".format(java.util.Locale.US, p.first, p.second)

private fun rounded(points: List<Pt>, r: Double): String {
    val n = points.size
    val sb = StringBuilder()
    for (i in 0 until n) {
        val p0 = points[(i - 1 + n) % n]; val p1 = points[i]; val p2 = points[(i + 1) % n]
        fun toward(a: Pt, b: Pt): Pt {
            val dx = a.first - b.first; val dy = a.second - b.second
            val t = min(r / hypot(dx, dy), 0.5)
            return Pt(b.first + dx * t, b.second + dy * t)
        }
        val a = toward(p0, p1); val b = toward(p2, p1)
        sb.append(if (i == 0) "M" else "L").append(f(a)).append("Q").append(f(p1)).append(" ").append(f(b))
    }
    return sb.append("Z").toString()
}

private fun regular(sides: Int, radius: Double, cx: Double, cy: Double, rot: Double): List<Pt> =
    (0 until sides).map { i -> val a = rot + i * 2 * PI / sides; Pt(cx + radius * cos(a), cy + radius * sin(a)) }

private fun flower(petals: Int, cx: Double, cy: Double, ring: Double, petal: Double): String {
    val pts = regular(petals, ring, cx, cy, -PI / 2)
    val sb = StringBuilder("M").append(f(pts[0]))
    for (i in 1..petals) {
        val p = pts[i % petals]
        sb.append("A$petal $petal 0 0 1 ").append(f(p))
    }
    return sb.append("Z").toString()
}

val bodies = mapOf(
    "circle" to Body("M50 9a41 41 0 1 1 0 82a41 41 0 1 1 0-82Z", 50f, 48f, 1f),
    "square" to Body("M50 10C80 10 90 20 90 50C90 80 80 90 50 90C20 90 10 80 10 50C10 20 20 10 50 10Z", 50f, 48f, 1f),
    "triangle" to Body(rounded(listOf(Pt(50.0, 10.0), Pt(93.0, 86.0), Pt(7.0, 86.0)), 15.0), 50f, 62f, 0.9f),
    "hexagon" to Body(rounded(regular(6, 44.0, 50.0, 50.0, -PI / 2), 11.0), 50f, 49f, 1f),
    "cloud" to Body(flower(7, 50.0, 51.0, 30.0, 11.5), 50f, 50f, 0.95f),
    "pill" to Body("M30 22H70A28 28 0 0 1 70 78H30A28 28 0 0 1 30 22Z", 50f, 49f, 0.95f),
    "arch" to Body("M12 52A38 38 0 0 1 88 52V80Q88 90 78 90H22Q12 90 12 80Z", 50f, 54f, 1f),
    "diamond" to Body(rounded(listOf(Pt(50.0, 7.0), Pt(93.0, 50.0), Pt(50.0, 93.0), Pt(7.0, 50.0)), 13.0), 50f, 49f, 0.85f),
)

val eyeSizes = mapOf("pill" to (10.5f to 21f), "dot" to (11f to 14f), "wide" to (14f to 10.5f))

data class EyePose(val tx: Float, val ty: Float, val sx: Float, val sy: Float, val rot: Float)

fun pose(mood: String, side: Int, gx: Float, gy: Float): EyePose = when (mood) {
    "thinking" -> EyePose(3f, -4.5f, 1f, 0.82f, 0f)
    "working" -> EyePose(gx * 0.4f, 1.2f, 1.05f, 0.5f, 0f)
    "waiting" -> EyePose(0f, -0.6f, 1.18f, 1.18f, 0f)
    "blocked" -> EyePose(0f, 1.5f, 1.25f, 0.42f, side * 24f)
    "sleepy" -> EyePose(0f, 3f, 1.1f, 0.16f, 0f)
    else -> EyePose(gx, gy, 1f, 1f, 0f)
}

fun mix(a: Long, b: Long, t: Float): Long {
    fun ch(c: Long, s: Int) = ((c shr s) and 0xff).toInt()
    var out = 0xFF000000
    for (s in listOf(16, 8, 0)) {
        val v = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
        out = out or (v.toLong() shl s)
    }
    return out
}
