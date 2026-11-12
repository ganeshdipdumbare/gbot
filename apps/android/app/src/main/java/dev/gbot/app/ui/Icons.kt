package dev.gbot.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The web app's icon set (24px, 1.75 stroke, round caps), drawn natively.

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"
private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float) =
    "M${x + rx} ${y}H${x + w - rx}A$rx $rx 0 0 1 ${x + w} ${y + rx}V${y + h - rx}A$rx $rx 0 0 1 ${x + w - rx} ${y + h}H${x + rx}A$rx $rx 0 0 1 $x ${y + h - rx}V${y + rx}A$rx $rx 0 0 1 ${x + rx} ${y}Z"

/** stroke paths, then filled paths. */
private class Glyph(val stroke: List<String>, val fill: List<String> = emptyList())

private val glyphs: Map<String, Glyph> = mapOf(
    "plus" to Glyph(listOf("M12 5v14M5 12h14")),
    "send" to Glyph(listOf("M5 12h13M12 5l7 7-7 7")),
    "stop" to Glyph(emptyList(), listOf(rect(7f, 7f, 10f, 10f, 3f))),
    "gear" to Glyph(listOf("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", circle(12f, 12f, 3f))),
    "search" to Glyph(listOf(circle(11f, 11f, 6.5f), "M20 20l-4.2-4.2")),
    "close" to Glyph(listOf("M6 6l12 12M18 6L6 18")),
    "menu" to Glyph(listOf("M4 7h16M4 12h16M4 17h10")),
    "dice" to Glyph(listOf(rect(4f, 4f, 16f, 16f, 5f)), listOf(circle(9f, 9f, 1.2f), circle(15f, 15f, 1.2f), circle(15f, 9f, 1.2f), circle(9f, 15f, 1.2f))),
    "pin" to Glyph(listOf("M14 4l6 6-3 1-4 4 .5 4-2.5.5L4 13l.5-2.5 4 .5 4-4zM4 20l5-5")),
    "unread" to Glyph(listOf(circle(12f, 12f, 8f)), listOf(circle(12f, 12f, 2.5f))),
    "eye" to Glyph(listOf("M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6S2.5 12 2.5 12z", circle(12f, 12f, 2.5f))),
    "eyeOff" to Glyph(listOf("M3 3l18 18M10.6 6.2A9.7 9.7 0 0112 6c6 0 9.5 6 9.5 6a15 15 0 01-3 3.5M6.5 7.6A15.5 15.5 0 002.5 12s3.5 6 9.5 6a9.8 9.8 0 004.2-.9")),
    "lock" to Glyph(listOf(rect(5f, 10f, 14f, 10f, 2.5f), "M8 10V7a4 4 0 018 0v3")),
    "chip" to Glyph(listOf(rect(6f, 6f, 12f, 12f, 3f), "M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3")),
    "logout" to Glyph(listOf("M15 4h3a2 2 0 012 2v12a2 2 0 01-2 2h-3M10 8l-4 4 4 4M6 12h10")),
    "screen" to Glyph(listOf(rect(3f, 4f, 18f, 12f, 3f), "M9 20h6M12 16v4")),
    "mic" to Glyph(listOf(rect(9f, 3f, 6f, 11f, 3f), "M5 11a7 7 0 0014 0M12 18v3")),
    "speaker" to Glyph(listOf("M4 10v4h3l5 4V6L7 10zM16 9a4 4 0 010 6M18.5 6.5a8 8 0 010 11")),
    "clock" to Glyph(listOf(circle(12f, 12f, 8.5f), "M12 7.5V12l3 2")),
    "plug" to Glyph(listOf("M8 3v5M16 3v5M6 8h12v3a6 6 0 01-12 0zM12 17v4")),
    "users" to Glyph(listOf(circle(9f, 9f, 3.5f), "M3 19a6 6 0 0112 0M16 5.5a3.5 3.5 0 010 7M18 19a6 6 0 00-2.5-4.9")),
    "spark" to Glyph(listOf("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 17l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z")),
    "globe" to Glyph(listOf(circle(12f, 12f, 8.5f), "M3.5 12h17M12 3.5c2.5 2.6 2.5 14.4 0 17M12 3.5c-2.5 2.6-2.5 14.4 0 17")),
    "play" to Glyph(listOf("M8 5.5v13l10-6.5z")),
    "expand" to Glyph(listOf("M14 4h6v6M10 20H4v-6M20 4l-6.5 6.5M4 20l6.5-6.5")),
    "control" to Glyph(listOf("M5 3.5l14 7-6.2 2.1L10.5 19z", "M13 13l5.5 5.5")),
    "moon" to Glyph(listOf("M19.5 14.5A7.5 7.5 0 019.5 4.5a7.5 7.5 0 1010 10z")),
    "power" to Glyph(listOf("M12 3.5V11M7.2 6.5a7.5 7.5 0 109.6 0")),
    "trash" to Glyph(listOf("M5 7h14M10 11v6M14 11v6M7 7l1 12h8l1-12M9.5 7V4.5h5V7")),
    "up" to Glyph(listOf("M12 19V5M5.5 11.5L12 5l6.5 6.5")),
    "down" to Glyph(listOf("M12 5v14M5.5 12.5L12 19l6.5-6.5")),
    "copy" to Glyph(listOf(rect(8f, 8f, 12f, 12f, 2.5f), "M16 8V6.5A2.5 2.5 0 0013.5 4h-7A2.5 2.5 0 004 6.5v7A2.5 2.5 0 006.5 16H8")),
    "check" to Glyph(listOf("M5 12.5l4.5 4.5L19 7.5")),
    "back" to Glyph(listOf("M15 5l-7 7 7 7")),
    "next" to Glyph(listOf("M9 5l7 7-7 7")),
    "more" to Glyph(emptyList(), listOf(circle(5.5f, 12f, 1.6f), circle(12f, 12f, 1.6f), circle(18.5f, 12f, 1.6f))),
    "keyboard" to Glyph(listOf(rect(2.5f, 6f, 19f, 12f, 3f), "M6.5 10h.01M10 10h.01M13.5 10h.01M17 10h.01M8 14h8")),
    "refresh" to Glyph(listOf("M20 11a8 8 0 00-14.3-4.3L4 8.5M4 4v4.5h4.5M4 13a8 8 0 0014.3 4.3L20 15.5M20 20v-4.5h-4.5")),
    "bell" to Glyph(listOf("M6 16V11a6 6 0 0112 0v5l1.5 2h-15zM10 20.5a2 2 0 004 0")),
    "pencil" to Glyph(listOf("M4 20l4.5-1 10-10a2.1 2.1 0 00-3-3l-10 10zM14 7l3 3")),
)

/**
 * Rewrites arc commands so their two flags are always separate tokens.
 * SVG lets them run together ("a4 4 0 018 0"), which Android's path parser
 * can misread.
 */
internal fun expandArcs(d: String): String {
    val out = StringBuilder()
    var i = 0
    fun skipSep() { while (i < d.length && (d[i] == ' ' || d[i] == ',' || d[i] == '\n')) i++ }
    fun number(): String? {
        skipSep()
        val start = i
        if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
        var dot = false
        while (i < d.length && (d[i].isDigit() || (d[i] == '.' && !dot))) { if (d[i] == '.') dot = true; i++ }
        if (i < d.length && (d[i] == 'e' || d[i] == 'E')) { i++; if (i < d.length && (d[i] == '-' || d[i] == '+')) i++; while (i < d.length && d[i].isDigit()) i++ }
        return if (i > start) d.substring(start, i) else null
    }
    fun flag(): String? { skipSep(); return if (i < d.length && (d[i] == '0' || d[i] == '1')) d[i++].toString() else null }
    while (i < d.length) {
        val c = d[i]
        if (c == 'a' || c == 'A') {
            i++
            out.append(c)
            while (true) {
                val save = i
                val rx = number(); val ry = number(); val rot = number(); val f1 = flag(); val f2 = flag(); val x = number(); val y = number()
                if (rx == null || ry == null || rot == null || f1 == null || f2 == null || x == null || y == null) { i = save; break }
                out.append(rx).append(' ').append(ry).append(' ').append(rot).append(' ').append(f1).append(' ').append(f2).append(' ').append(x).append(' ').append(y).append(' ')
            }
        } else { out.append(c); i++ }
    }
    return out.toString()
}

private val parsed = HashMap<String, Pair<List<Path>, List<Path>>>()
private fun paths(name: String) = parsed.getOrPut(name) {
    val g = glyphs[name] ?: Glyph(emptyList())
    g.stroke.map { PathParser().parsePathString(expandArcs(it)).toPath() } to g.fill.map { PathParser().parsePathString(expandArcs(it)).toPath() }
}

@Composable
fun Icon(name: String, modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = LocalG.current.ink) {
    val (stroke, fill) = paths(name)
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        withTransform({ scale(u, u, Offset.Zero) }) {
            val st = Stroke(width = 1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            stroke.forEach { drawPath(it, color, style = st) }
            fill.forEach { drawPath(it, color) }
        }
    }
}
