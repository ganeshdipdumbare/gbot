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
