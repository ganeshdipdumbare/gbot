package dev.gbot.app.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

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
    /** The body's thickness under the front face, darkening toward its far edge. */
    val wall: Brush,
    val face: Brush,
    /** A broad, soft lift on the upper face: the sky light on a rounded top. */
    val dome: Brush,
    /** Edge falloff: the pillow turning away from the light all round. */
    val falloff: Brush,
    /** Key light rolling over the upper bevel: a soft, blurred stroke. */
    val crown: android.graphics.Paint,
    /** The lower bevel turning into shade. */
    val under: android.graphics.Paint,
    /** A hairline silhouette, so pale bots keep an edge on light paper. */
    val contour: Color,
    val shadow: Brush,
)

internal object Glow {
    /** How thick the body is, in body units; the web rig's 0.38 at its scale. */
    const val DEPTH = 5

    fun paint(tint: Long, b: Rect, dark: Boolean): BotPaint {
        val l = lumOf(tint)
        // Matte clay, as the web's standard material: a gentle top-to-bottom
        // turn, not a glossy spot. Pale hues take less lift, or cream goes white.
        val top = shade(tint, 1f + (if (dark) 0.16f else 0.12f) * (1f - 0.6f * l))
        val bottom = shade(tint, if (dark) 0.80f else 0.84f, warm = 0.12f)
        val face = Brush.verticalGradient(
            0f to top, 0.45f to Color(tint), 1f to bottom,
            startY = b.top, endY = b.bottom,
        )
        val dome = Brush.radialGradient(
            0f to Color.White.copy(alpha = (if (dark) 0.16f else 0.13f) * (1f - 0.5f * l)),
            1f to Color.Transparent,
            center = Offset(b.center.x, b.top + b.height * 0.22f), radius = maxOf(b.width, b.height) * 0.55f,
        )
        val falloff = Brush.radialGradient(
            0f to Color.Transparent, 0.72f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (dark) 0.16f else 0.12f),
            center = Offset(b.center.x, b.center.y - b.height * 0.06f), radius = maxOf(b.width, b.height) * 0.60f,
        )
        fun bevel(from: Color, to: Color, y0: Float, y1: Float) = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 14f
            shader = android.graphics.LinearGradient(0f, y0, 0f, y1, from.toArgb(), to.toArgb(), android.graphics.Shader.TileMode.CLAMP)
            // In body units: the canvas scale carries it to pixels.
            maskFilter = android.graphics.BlurMaskFilter(4.5f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        val crown = bevel(Color.White.copy(alpha = if (dark) 0.42f else 0.38f), Color.Transparent, b.top, b.top + b.height * 0.6f)
        val under = bevel(Color.Transparent, Color.Black.copy(alpha = if (dark) 0.34f else 0.26f), b.bottom - b.height * 0.6f, b.bottom)

        // The side wall: one gradient by height, so the stacked steps read as one surface.
        val far = shade(tint, if (dark) 0.50f else 0.56f, warm = 0.25f)
        val near = shade(tint, if (dark) 0.70f else 0.76f, warm = 0.18f)
        val wall = Brush.verticalGradient(0f to near, 1f to far, startY = b.bottom - b.height * 0.35f, endY = b.bottom + DEPTH)

        // On white paper a cream bot has no silhouette of its own; give it
        // one, in its own colour, scaled by how pale it is.
        val contourA = if (dark) 0.04f + 0.08f * l * l else 0.04f + 0.42f * l * l * l
        val contour = shade(tint, 0.50f, warm = 0.1f).copy(alpha = contourA)

        val shadow = Brush.radialGradient(
            0f to Color.Black.copy(alpha = if (dark) 0.34f else 0.24f),
            0.45f to Color.Black.copy(alpha = if (dark) 0.15f else 0.10f),
            1f to Color.Transparent,
            center = Offset(50f, 95.5f), radius = 30f,
        )

        return BotPaint(wall, face, dome, falloff, crown, under, contour, shadow)
    }
}
