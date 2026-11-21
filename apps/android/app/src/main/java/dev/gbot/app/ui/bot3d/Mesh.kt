package dev.gbot.app.ui.bot3d

import dev.gbot.app.ui.eyeSizes
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The bots' geometry, ported line for line from the web rig in
// packages/characters/src/gl.ts: each flat outline inflated into a soft,
// bevelled token, the same pill eyes and the same closed smile.

internal const val U = 0.01f       // one unit of the 100-box, in world units
internal const val HALF = 0.19f    // half a body's thickness
internal const val BEVEL = 0.1f    // how round its edge is
private const val TAU = PI * 2

private typealias Pt = DoubleArray

private fun p(x: Double, y: Double) = doubleArrayOf(x, y)

private fun arc(cx: Double, cy: Double, r: Double, from: Double, to: Double, steps: Int): List<Pt> =
    (0..steps).map { i -> val a = from + (to - from) * i / steps; p(cx + r * cos(a), cy + r * sin(a)) }

private fun quad(a: Pt, c: Pt, b: Pt, steps: Int): List<Pt> = (0..steps).map { i ->
    val t = i.toDouble() / steps; val u = 1 - t
    p(u * u * a[0] + 2 * u * t * c[0] + t * t * b[0], u * u * a[1] + 2 * u * t * c[1] + t * t * b[1])
}

private fun cubic(a: Pt, c1: Pt, c2: Pt, b: Pt, steps: Int): List<Pt> = (0..steps).map { i ->
    val t = i.toDouble() / steps; val u = 1 - t
    p(
        u * u * u * a[0] + 3 * u * u * t * c1[0] + 3 * u * t * t * c2[0] + t * t * t * b[0],
        u * u * u * a[1] + 3 * u * u * t * c1[1] + 3 * u * t * t * c2[1] + t * t * t * b[1],
    )
}

private fun straight(a: Pt, b: Pt, steps: Int = 6): List<Pt> =
    (0..steps).map { i -> p(a[0] + (b[0] - a[0]) * i / steps, a[1] + (b[1] - a[1]) * i / steps) }

private fun rounded(points: List<Pt>, r: Double): List<Pt> {
    val n = points.size
    fun toward(a: Pt, b: Pt): Pt {
        val dx = a[0] - b[0]; val dy = a[1] - b[1]
        val t = min(r / hypot(dx, dy), 0.5)
        return p(b[0] + dx * t, b[1] + dy * t)
    }
    val out = ArrayList<Pt>()
    var prev: Pt? = null; var first: Pt? = null
    for (i in 0 until n) {
        val p0 = points[(i - 1 + n) % n]; val p1 = points[i]; val p2 = points[(i + 1) % n]
        val a = toward(p0, p1); val b = toward(p2, p1)
        if (prev != null) out += straight(prev, a) else first = a
        out += quad(a, p1, b, 12)
        prev = b
    }
    return out + straight(prev!!, first!!)
}

private fun regular(sides: Int, radius: Double, cx: Double, cy: Double, rot: Double): List<Pt> =
    (0 until sides).map { i -> val a = rot + i * TAU / sides; p(cx + radius * cos(a), cy + radius * sin(a)) }

private fun flower(petals: Int, cx: Double, cy: Double, ring: Double): List<Pt> {
    val pts = regular(petals, ring, cx, cy, -PI / 2)
    val out = ArrayList<Pt>()
    for (i in 0 until petals) {
        val a = pts[i]; val b = pts[(i + 1) % petals]
        val mx = (a[0] + b[0]) / 2; val my = (a[1] + b[1]) / 2
        val start = atan2(a[1] - my, a[0] - mx)
        out += arc(mx, my, hypot(a[0] - b[0], a[1] - b[1]) / 2, start, start + PI, 16)
    }
    return out
}

private class Raw(val pts: List<Pt>, val face: Pt, val eyeScale: Float)

private val raw: Map<String, Raw> = mapOf(
    "circle" to Raw(arc(50.0, 50.0, 41.0, 0.0, TAU, 72), p(50.0, 48.0), 1f),
    "square" to Raw(
        cubic(p(50.0, 10.0), p(80.0, 10.0), p(90.0, 20.0), p(90.0, 50.0), 20) +
            cubic(p(90.0, 50.0), p(90.0, 80.0), p(80.0, 90.0), p(50.0, 90.0), 20) +
            cubic(p(50.0, 90.0), p(20.0, 90.0), p(10.0, 80.0), p(10.0, 50.0), 20) +
            cubic(p(10.0, 50.0), p(10.0, 20.0), p(20.0, 10.0), p(50.0, 10.0), 20),
        p(50.0, 48.0), 1f,
    ),
    "triangle" to Raw(rounded(listOf(p(50.0, 10.0), p(93.0, 86.0), p(7.0, 86.0)), 15.0), p(50.0, 62.0), 0.9f),
    "hexagon" to Raw(rounded(regular(6, 44.0, 50.0, 50.0, -PI / 2), 11.0), p(50.0, 49.0), 1f),
    "cloud" to Raw(flower(7, 50.0, 51.0, 30.0), p(50.0, 50.0), 0.95f),
    "pill" to Raw(
        straight(p(30.0, 22.0), p(70.0, 22.0)) +
            arc(70.0, 50.0, 28.0, -PI / 2, PI / 2, 28) +
            straight(p(70.0, 78.0), p(30.0, 78.0)) +
            arc(30.0, 50.0, 28.0, PI / 2, 3 * PI / 2, 28),
        p(50.0, 49.0), 0.95f,
    ),
    "arch" to Raw(
        arc(50.0, 52.0, 38.0, PI, TAU, 40) +
            straight(p(88.0, 52.0), p(88.0, 80.0)) +
            quad(p(88.0, 80.0), p(88.0, 90.0), p(78.0, 90.0), 10) +
            straight(p(78.0, 90.0), p(22.0, 90.0)) +
            quad(p(22.0, 90.0), p(12.0, 90.0), p(12.0, 80.0), 10) +
            straight(p(12.0, 80.0), p(12.0, 52.0)),
        p(50.0, 54.0), 1f,
    ),
    "diamond" to Raw(rounded(listOf(p(50.0, 7.0), p(93.0, 50.0), p(50.0, 93.0), p(7.0, 50.0)), 13.0), p(50.0, 49.0), 0.85f),
)

private fun dedupe(pts: List<Pt>): MutableList<Pt> {
    val out = ArrayList<Pt>()
    for (q in pts) {
        val last = out.lastOrNull()
        if (last == null || hypot(q[0] - last[0], q[1] - last[1]) > 0.05) out += q
    }
    val a = out.first(); val b = out.last()
    if (hypot(a[0] - b[0], a[1] - b[1]) <= 0.05) out.removeAt(out.size - 1)
    return out
}

/** A body outline standing upright: y up, feet on the floor, centred on x. */
internal class Outline(
    val xs: FloatArray, val ys: FloatArray,
    val height: Float, val width: Float,
    val faceX: Float, val faceY: Float, val eyeScale: Float,
)

internal fun outline(shape: String): Outline {
    val b = raw[shape] ?: raw.getValue("circle")
    val pts = dedupe(b.pts)
    val floor = pts.maxOf { it[1] }
    val flat = pts.map { p((it[0] - 50) * U, (floor - it[1]) * U) }.toMutableList()
    var area = 0.0
    for (i in flat.indices) {
        val a = flat[i]; val c = flat[(i + 1) % flat.size]
        area += a[0] * c[1] - c[0] * a[1]
    }
    if (area < 0) flat.reverse()
    var height = 0.0; var width = 0.0
    for (q in flat) { height = max(height, q[1]); width = max(width, abs(q[0])) }
    return Outline(
        FloatArray(flat.size) { flat[it][0].toFloat() }, FloatArray(flat.size) { flat[it][1].toFloat() },
        height.toFloat(), (width * 2).toFloat(),
        ((b.face[0] - 50) * U).toFloat(), ((floor - b.face[1]) * U).toFloat(), b.eyeScale,
    )
}

/** Interleaved position(3) normal(3) shade(1), plus triangle indices. */
internal class MeshData(val verts: FloatArray, val index: ShortArray)

internal const val STRIDE = 7

/** A flat-faced token with a fully rounded rim, as the web's pillow(). */
internal fun pillow(o: Outline): MeshData {
    val n = o.xs.size; val steps = 7
    val dir = Array(n) { i ->
        val ax = o.xs[(i - 1 + n) % n]; val ay = o.ys[(i - 1 + n) % n]
        val cx = o.xs[(i + 1) % n]; val cy = o.ys[(i + 1) % n]
        var e1x = o.xs[i] - ax; var e1y = o.ys[i] - ay; var e2x = cx - o.xs[i]; var e2y = cy - o.ys[i]
        val l1 = hypot(e1x, e1y).takeIf { it != 0f } ?: 1f; val l2 = hypot(e2x, e2y).takeIf { it != 0f } ?: 1f
        e1x /= l1; e1y /= l1; e2x /= l2; e2y /= l2
        var mx = e1y + e2y; var my = -e1x - e2x
        val lm = hypot(mx, my).takeIf { it != 0f } ?: 1f
        mx /= lm; my /= lm
        val k = 1f / max(0.72f, mx * e1y + my * -e1x)
        floatArrayOf(mx, my, k)
    }

    val profile = ArrayList<FloatArray>()
    for (j in 0..steps) {
        val a = (PI / 2 * (1 - j.toDouble() / steps))
        profile += floatArrayOf((BEVEL * (cos(a) - 1)).toFloat(), (HALF - BEVEL + BEVEL * sin(a)).toFloat(), cos(a).toFloat(), sin(a).toFloat())
    }
    for (j in 0..steps) {
        val a = (-PI / 2 * (j.toDouble() / steps))
        profile += floatArrayOf((BEVEL * (cos(a) - 1)).toFloat(), (-(HALF - BEVEL) + BEVEL * sin(a)).toFloat(), cos(a).toFloat(), sin(a).toFloat())
    }

    val rings = profile.size
    val v = FloatArray(rings * n * STRIDE)
    for (j in 0 until rings) {
        val (r, z, nr, nz) = profile[j].let { listOf(it[0], it[1], it[2], it[3]) }
        for (i in 0 until n) {
            val off = (j * n + i) * STRIDE
            val (mx, my, k) = dir[i].let { Triple(it[0], it[1], it[2]) }
            v[off] = o.xs[i] + mx * k * r
            v[off + 1] = o.ys[i] + my * k * r
            v[off + 2] = z
            v[off + 3] = mx * nr
            v[off + 4] = my * nr
            v[off + 5] = nz
            // Light from above, easing into shade below, as on the flat rig.
            val t = (v[off + 1] / o.height).coerceIn(0f, 1f)
            v[off + 6] = 0.8f + 0.2f * t * (2 - t)
        }
    }

    val index = ArrayList<Int>()
    for (j in 0 until rings - 1) for (i in 0 until n) {
        val a = j * n + i; val b = j * n + (i + 1) % n; val c = (j + 1) * n + i; val d = (j + 1) * n + (i + 1) % n
        index += listOf(a, c, d, a, d, b)
    }
    val fx = FloatArray(n) { v[it * STRIDE] }; val fy = FloatArray(n) { v[it * STRIDE + 1] }
    val back = (rings - 1) * n
    for (tri in triangulate(fx, fy)) {
        val (a, b, c) = tri
        val ccw = (fx[b] - fx[a]) * (fy[c] - fy[a]) - (fy[b] - fy[a]) * (fx[c] - fx[a]) > 0
        val (pp, q, r) = if (ccw) Triple(a, b, c) else Triple(a, c, b)
        index += listOf(pp, q, r, back + pp, back + r, back + q)
    }
    return MeshData(v, ShortArray(index.size) { index[it].toShort() })
}

/** Ear clipping for one simple polygon; returns triangles of vertex indices. */
internal fun triangulate(xs: FloatArray, ys: FloatArray): List<IntArray> {
    val n = xs.size
    var area = 0f
    for (i in 0 until n) { val j = (i + 1) % n; area += xs[i] * ys[j] - xs[j] * ys[i] }
    val ring = (0 until n).toMutableList()
    if (area < 0) ring.reverse()
    val out = ArrayList<IntArray>()
    fun cross(a: Int, b: Int, c: Int) = (xs[b] - xs[a]) * (ys[c] - ys[a]) - (ys[b] - ys[a]) * (xs[c] - xs[a])
    fun inside(pi: Int, a: Int, b: Int, c: Int): Boolean =
        cross(a, b, pi) >= 0 && cross(b, c, pi) >= 0 && cross(c, a, pi) >= 0
    var guard = 0
    while (ring.size > 3 && guard++ < n * n) {
        var clipped = false
        for (k in ring.indices) {
            val a = ring[(k - 1 + ring.size) % ring.size]; val b = ring[k]; val c = ring[(k + 1) % ring.size]
            val cr = cross(a, b, c)
            if (cr <= 1e-9f) continue
            var ear = true
            for (q in ring) {
                if (q == a || q == b || q == c) continue
                if (inside(q, a, b, c)) { ear = false; break }
            }
            if (!ear) continue
            out += intArrayOf(a, b, c)
            ring.removeAt(k)
            clipped = true
            break
        }
        if (!clipped) {
            // Only collinear runs are left: drop the flattest vertex.
            val k = ring.indices.minBy { abs(cross(ring[(it - 1 + ring.size) % ring.size], ring[it], ring[(it + 1) % ring.size])) }
            ring.removeAt(k)
        }
    }
    if (ring.size == 3) out += intArrayOf(ring[0], ring[1], ring[2])
    return out
}

/** A flat 2D mesh in the face plane: xy pairs, triangles. */
internal class Flat(val xy: FloatArray, val index: ShortArray)

private fun fan(xs: List<Float>, ys: List<Float>): Flat {
    // Centre first, then the rim; convex shapes only.
    val n = xs.size
    val xy = FloatArray((n + 1) * 2)
    xy[0] = xs.average().toFloat(); xy[1] = ys.average().toFloat()
    for (i in 0 until n) { xy[2 + i * 2] = xs[i]; xy[3 + i * 2] = ys[i] }
    val idx = ShortArray(n * 3)
    for (i in 0 until n) { idx[i * 3] = 0; idx[i * 3 + 1] = (1 + i).toShort(); idx[i * 3 + 2] = (1 + (i + 1) % n).toShort() }
    return Flat(xy, idx)
}

/** The open eye: a rounded rect, as the web's eyeGeometry (10 curve segments). */
internal fun eyeGeometry(style: String): Flat {
    val (ew, eh) = eyeSizes[style] ?: eyeSizes.getValue("dot")
    val w = ew * U; val h = eh * U
    val r = min(w, h) / 2; val x = w / 2; val y = h / 2
    val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
    fun corner(cx: Float, cy: Float, from: Double) {
        for (s in 0..10) {
            val a = from + (PI / 2) * s / 10
            xs += cx + r * cos(a).toFloat(); ys += cy + r * sin(a).toFloat()
        }
    }
    corner(x - r, -y + r, -PI / 2)
    corner(x - r, y - r, 0.0)
    corner(-x + r, y - r, PI / 2)
    corner(-x + r, -y + r, PI)
    return fan(xs, ys)
}

private const val SMILE_R = 7.4f * U
private const val SMILE_T = 3f * U
private val SMILE_A = 19 * PI / 180

/** The closed, smiling eye: an arc with round caps, already lowered 5.4 units. */
internal fun smileGeometry(): Flat {
    val xy = ArrayList<Float>(); val idx = ArrayList<Short>()
    val drop = -5.4f * U
    // The ring sector, 24 segments.
    val segs = 24
    val start = xy.size / 2
    for (s in 0..segs) {
        val a = SMILE_A + (PI - 2 * SMILE_A) * s / segs
        for (rr in listOf(SMILE_R - SMILE_T, SMILE_R + SMILE_T)) {
            xy += rr * cos(a).toFloat(); xy += rr * sin(a).toFloat() + drop
        }
    }
    for (s in 0 until segs) {
        val i0 = start + s * 2
        idx += listOf(i0, i0 + 1, i0 + 3, i0, i0 + 3, i0 + 2).map { it.toShort() }
    }
    // Two round caps.
    for (a in listOf(SMILE_A, PI - SMILE_A)) {
        val cx = cos(a).toFloat() * SMILE_R; val cy = sin(a).toFloat() * SMILE_R + drop
        val c = xy.size / 2
        xy += cx; xy += cy
        for (s in 0 until 16) {
            val t = TAU * s / 16
            xy += cx + SMILE_T * cos(t).toFloat(); xy += cy + SMILE_T * sin(t).toFloat()
        }
        for (s in 0 until 16) idx += listOf(c, c + 1 + s, c + 1 + (s + 1) % 16).map { it.toShort() }
    }
    return Flat(xy.toFloatArray(), idx.toShortArray())
}
