package dev.gbot.app.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The bots' light, mirrored from the web rig in packages/characters/src/gl.ts:
 * a hemisphere fill (white sky, warm #CFC8BD ground), a key light from the
 * upper left and front, and a faint rim from the right behind. There the body
 * is a bevelled pillow with real thickness; here the same rig is baked into
 * brushes — the thickness showing under the front face, a lit face, a bevel
 * that catches the key along the top and the rim down the right, and a shaded
 * under-edge.
 *
 * One rig serves both themes. Light mode only firms up the edges and holds
 * the highlight back, so a pale bot still reads against white paper instead
 * of dissolving into it.
 */

/** The hemisphere light's ground colour: what bounces back up from below. */
private const val GROUND = 0xFFCFC8BDL

private fun chan(c: Long, s: Int) = ((c shr s) and 0xff).toInt()

private fun lumOf(c: Long): Float =
    (0.299f * chan(c, 16) + 0.587f * chan(c, 8) + 0.114f * chan(c, 0)) / 255f

/** Scale a colour's light: above 1 lifts toward white, below 1 just darkens. */
private fun shade(tint: Long, f: Float, warm: Float = 0f): Color {
    val warmed = if (warm > 0f) mix(tint, GROUND, warm) else tint
    if (f >= 1f) return Color(mix(warmed, 0xFFFFFFFFL, ((f - 1f) * 0.9f).coerceIn(0f, 1f)))
    var out = 0xFF000000L
    for (s in listOf(16, 8, 0)) {
        val v = (chan(warmed, s) * f).toInt().coerceIn(0, 255)
        out = out or (v.toLong() shl s)
    }
    return Color(out)
}

private fun smooth(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun mix3(a: Color, b: Color, c: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    val (x, y, s) = if (u < 0.5f) Triple(a, b, smooth(u / 0.5f)) else Triple(b, c, smooth((u - 0.5f) / 0.5f))
    return Color(x.red + (y.red - x.red) * s, x.green + (y.green - x.green) * s, x.blue + (y.blue - x.blue) * s, 1f)
}

/** Everything one bot needs to paint itself, in the 100-unit body box. */
@Immutable
internal class BotPaint(
    /** The body's thickness, showing just under the front face. */
    val wall: Color,
    val face: Brush,
    /** Key light along the top bevel. */
    val crown: Brush,
    /** Rim light down the right bevel. */
    val rim: Brush,
    /** The under-edge easing into shade. */
    val under: Brush,
    val sheen: Brush,
    val sheenAt: Offset,
    /** A hairline silhouette, so pale bots keep an edge on light paper. */
    val contour: Color,
    val shadow: Brush,
)

internal object Glow {
    /** The specular catch, a little up and left of centre. */
    private fun sheenAt(b: Rect) = Offset(b.left + b.width * 0.34f, b.top + b.height * 0.21f)

    fun paint(tint: Long, b: Rect, dark: Boolean): BotPaint {
        val l = lumOf(tint)
        // A bright hue can take a brighter highlight; cream would go white.
        val lift = (if (dark) 0.36f else 0.24f) * (1f - 0.5f * l)
        val low = if (dark) 0.58f - 0.10f * l else 0.70f - 0.18f * l
        val inner = shade(tint, 1f + lift)
        val mid = shade(tint, 1f + lift * 0.12f)
        val edge = shade(tint, low, warm = 0.22f)

        val n = 20
        val stops = Array(n + 1) { i ->
            val u = i / n.toFloat()
            // Pack the stops near the rim, where the form turns fastest.
            (1f - (1f - u) * (1f - u)) to mix3(inner, mid, edge, u)
        }
        val hi = Offset(b.left + b.width * 0.32f, b.top + b.height * 0.13f)
        val face = Brush.radialGradient(
            colorStops = stops, center = hi, radius = maxOf(b.width, b.height) * 1.02f,
        )

        val crown = Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.46f else 0.38f),
            0.5f to Color.White.copy(alpha = if (dark) 0.10f else 0.07f),
            1f to Color.Transparent,
            startY = b.top, endY = b.top + b.height * 0.46f,
        )
        val rim = Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to Color.White.copy(alpha = if (dark) 0.30f else 0.24f),
            startX = b.left + b.width * 0.42f, endX = b.right,
        )
        val under = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (dark) 0.26f else 0.20f),
            startY = b.bottom - b.height * 0.40f, endY = b.bottom,
        )
        val sheen = Brush.radialGradient(
            0f to Color.White.copy(alpha = if (dark) 0.40f else 0.30f),
            0.4f to Color.White.copy(alpha = if (dark) 0.14f else 0.10f),
            1f to Color.Transparent,
            center = sheenAt(b), radius = maxOf(b.width, b.height) * 0.30f,
        )

        // On white paper a cream bot has no silhouette of its own; give it
        // one, in its own colour, scaled by how pale it is.
        val contourA = if (dark) 0.06f + 0.10f * l * l else 0.09f + 0.40f * l * l * l
        val contour = shade(tint, 0.50f, warm = 0.1f).copy(alpha = contourA)

        val shadow = Brush.radialGradient(
            0f to Color.Black.copy(alpha = if (dark) 0.26f else 0.20f),
            0.5f to Color.Black.copy(alpha = if (dark) 0.11f else 0.08f),
            1f to Color.Transparent,
            center = Offset(50f, 95.5f), radius = 30f,
        )

        return BotPaint(
            wall = shade(tint, if (dark) 0.52f else 0.60f, warm = 0.3f),
            face = face, crown = crown, rim = rim, under = under,
            sheen = sheen, sheenAt = sheenAt(b), contour = contour, shadow = shadow,
        )
    }
}
