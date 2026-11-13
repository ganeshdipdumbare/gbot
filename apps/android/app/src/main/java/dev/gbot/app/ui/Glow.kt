package dev.gbot.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Grok-style body light: one radial fill whose stops do the “3D”.
 * Inner is bright, mid is the brand colour, rim falls off soft and dense
 * near the edge — the same idea as their glow lab’s 49-stop sphere.
 */
internal object Glow {
    private fun smooth(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun mix3(a: Color, b: Color, c: Color, t: Float): Color {
        val u = t.coerceIn(0f, 1f)
        return if (u < 0.5f) {
            val s = smooth(u / 0.5f)
            Color(
                a.red + (b.red - a.red) * s,
                a.green + (b.green - a.green) * s,
                a.blue + (b.blue - a.blue) * s,
                1f,
            )
        } else {
            val s = smooth((u - 0.5f) / 0.5f)
            Color(
                b.red + (c.red - b.red) * s,
                b.green + (c.green - b.green) * s,
                b.blue + (c.blue - b.blue) * s,
                1f,
            )
        }
    }

    private fun darken(c: Color, t: Float): Color {
        // Edge press: 0.9 + 0.1*(1-t²)^0.225 — keeps the rim from going black.
        val k = 0.9f + 0.1f * (1f - t * t).toDouble().pow(0.225).toFloat()
        return Color(c.red * k, c.green * k, c.blue * k, 1f)
    }

    /** Spherical body fill. [tint] is the brand body colour as ARGB long. */
    fun body(tint: Long, center: Offset, radius: Float): Brush {
        val inner = Color(mix(tint, 0xFFFFFFFF, 0.55f))
        val mid = Color(mix(tint, 0xFFFFFFFF, 0.08f))
        val rim = Color(mix(tint, 0xFF000000, 0.42f))
        val n = 24
        val stops = Array(n + 1) { i ->
            val u = i / n.toFloat()
            // Pack stops near the edge the way Grok does.
            val offset = 1f - (1f - u) * (1f - u)
            offset to darken(mix3(inner, mid, rim, u), u)
        }
        return Brush.radialGradient(colorStops = stops, center = center, radius = radius)
    }

    /** Soft even-odd halo colour: rim ink that fades out. */
    fun halo(tint: Long): Color = Color(mix(tint, 0xFFFFFFFF, 0.35f)).copy(alpha = 0.28f)

    /** Specular catch near the top-left. */
    fun sheen(center: Offset, radius: Float): Brush = Brush.radialGradient(
        0f to Color(0x66FFFFFF),
        0.35f to Color(0x28FFFFFF),
        1f to Color.Transparent,
        center = center,
        radius = radius,
    )
}
