package dev.gbot.app.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Canvas.VertexMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// The web's 3D rig, on the phone: the same outline extruded into a
// beveled token, lit from above, and projected by the same camera, then
// handed to Skia as one triangle mesh with a colour per vertex. No GL
// context, no dependency, and the result is the same body the site draws.
object Bots3D {
    private const val U = 0.01f        // one unit of the 100-box, in world units
    private const val HALF = 0.19f     // half a body's thickness
    private const val BEVEL = 0.1f     // how round its edge is
    private val LIFT = (14 * PI / 180).toFloat()
    private const val TAN_HALF = 0.1139f // tan(6.5°), the site's lens

    private typealias Pt = Pair<Float, Float>

    // ---------- outlines, exactly as on the web ----------

    private fun arc(cx: Float, cy: Float, r: Float, from: Float, to: Float, steps: Int) =
        (0..steps).map { i -> val a = from + (to - from) * i / steps; Pt(cx + r * cos(a), cy + r * sin(a)) }

    private fun quad(a: Pt, c: Pt, b: Pt, steps: Int) = (0..steps).map { i ->
        val t = i.toFloat() / steps; val u = 1 - t
        Pt(u * u * a.first + 2 * u * t * c.first + t * t * b.first, u * u * a.second + 2 * u * t * c.second + t * t * b.second)
    }

    private fun cubic(a: Pt, c1: Pt, c2: Pt, b: Pt, steps: Int) = (0..steps).map { i ->
        val t = i.toFloat() / steps; val u = 1 - t
        Pt(
            u * u * u * a.first + 3 * u * u * t * c1.first + 3 * u * t * t * c2.first + t * t * t * b.first,
            u * u * u * a.second + 3 * u * u * t * c1.second + 3 * u * t * t * c2.second + t * t * t * b.second,
        )
    }

    private fun line(a: Pt, b: Pt, steps: Int = 6) = (0..steps).map { i ->
        Pt(a.first + (b.first - a.first) * i / steps, a.second + (b.second - a.second) * i / steps)
    }

    private fun rounded(points: List<Pt>, r: Float): List<Pt> {
        val n = points.size
        fun toward(a: Pt, b: Pt): Pt {
            val dx = a.first - b.first; val dy = a.second - b.second
            val t = min(r / hypot(dx, dy), 0.5f)
            return Pt(b.first + dx * t, b.second + dy * t)
        }
        val out = ArrayList<Pt>()
        var prev: Pt? = null; var first: Pt? = null
        for (i in 0 until n) {
            val p0 = points[(i - 1 + n) % n]; val p1 = points[i]; val p2 = points[(i + 1) % n]
            val a = toward(p0, p1); val b = toward(p2, p1)
            if (prev != null) out += line(prev, a) else first = a
            out += quad(a, p1, b, 12)
            prev = b
        }
        out += line(prev!!, first!!)
        return out
    }

    private fun regular(sides: Int, radius: Float, cx: Float, cy: Float, rot: Float) = (0 until sides).map { i ->
        val a = rot + i * 2 * PI.toFloat() / sides; Pt(cx + radius * cos(a), cy + radius * sin(a))
    }

    private fun flower(petals: Int, cx: Float, cy: Float, ring: Float): List<Pt> {
        val pts = regular(petals, ring, cx, cy, -PI.toFloat() / 2)
        val out = ArrayList<Pt>()
        for (i in 0 until petals) {
            val a = pts[i]; val b = pts[(i + 1) % petals]
            val mx = (a.first + b.first) / 2; val my = (a.second + b.second) / 2
            val start = kotlin.math.atan2(a.second - my, a.first - mx)
            out += arc(mx, my, hypot(a.first - b.first, a.second - b.second) / 2, start, start + PI.toFloat(), 16)
        }
        return out
    }

    private fun raw(shape: String): List<Pt> = when (shape) {
        "circle" -> arc(50f, 50f, 41f, 0f, 2 * PI.toFloat(), 72)
        "square" -> cubic(Pt(50f, 10f), Pt(80f, 10f), Pt(90f, 20f), Pt(90f, 50f), 20) +
            cubic(Pt(90f, 50f), Pt(90f, 80f), Pt(80f, 90f), Pt(50f, 90f), 20) +
            cubic(Pt(50f, 90f), Pt(20f, 90f), Pt(10f, 80f), Pt(10f, 50f), 20) +
            cubic(Pt(10f, 50f), Pt(10f, 20f), Pt(20f, 10f), Pt(50f, 10f), 20)
        "triangle" -> rounded(listOf(Pt(50f, 10f), Pt(93f, 86f), Pt(7f, 86f)), 15f)
        "hexagon" -> rounded(regular(6, 44f, 50f, 50f, -PI.toFloat() / 2), 11f)
        "cloud" -> flower(7, 50f, 51f, 30f)
        "pill" -> line(Pt(30f, 22f), Pt(70f, 22f)) +
            arc(70f, 50f, 28f, -PI.toFloat() / 2, PI.toFloat() / 2, 28) +
            line(Pt(70f, 78f), Pt(30f, 78f)) +
            arc(30f, 50f, 28f, PI.toFloat() / 2, 3 * PI.toFloat() / 2, 28)
        "arch" -> arc(50f, 52f, 38f, PI.toFloat(), 2 * PI.toFloat(), 40) +
            line(Pt(88f, 52f), Pt(88f, 80f)) +
            quad(Pt(88f, 80f), Pt(88f, 90f), Pt(78f, 90f), 10) +
            line(Pt(78f, 90f), Pt(22f, 90f)) +
            quad(Pt(22f, 90f), Pt(12f, 90f), Pt(12f, 80f), 10) +
            line(Pt(12f, 80f), Pt(12f, 52f))
        "diamond" -> rounded(listOf(Pt(50f, 7f), Pt(93f, 50f), Pt(50f, 93f), Pt(7f, 50f)), 13f)
        else -> arc(50f, 50f, 41f, 0f, 2 * PI.toFloat(), 72)
    }

    private fun dedupe(pts: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>()
        for (p in pts) {
            val q = out.lastOrNull()
            if (q == null || hypot(p.first - q.first, p.second - q.second) > 0.05f) out += p
        }
        val a = out.first(); val b = out.last()
        if (hypot(a.first - b.first, a.second - b.second) <= 0.05f) out.removeAt(out.size - 1)
        return out
    }

    /** The outline standing upright: y up, feet on the floor, centered on x. */
    private class Outline(val pts: List<Pt>, val height: Float, val width: Float)

    private fun outline(shape: String): Outline {
        val pts = dedupe(raw(shape))
        var floor = -Float.MAX_VALUE
        for (p in pts) floor = max(floor, p.second)
        val flat = pts.map { p -> Pt((p.first - 50f) * U, (floor - p.second) * U) }
        var area = 0f
        for (i in flat.indices) {
            val a = flat[i]; val c = flat[(i + 1) % flat.size]
            area += a.first * c.second - c.first * a.second
        }
        val ordered = if (area < 0) flat.reversed() else flat
        var height = 0f; var width = 0f
        for (p in ordered) { height = max(height, p.second); width = max(width, abs(p.first)) }
        return Outline(ordered, height, width * 2)
    }

    /** A flat-faced token with a fully rounded rim, shaded top to bottom. */
    private class Mesh(
        val x: FloatArray, val y: FloatArray, val z: FloatArray,
        val nx: FloatArray, val ny: FloatArray, val nz: FloatArray,
        val shade: FloatArray, val index: ShortArray, val count: Int, val n: Int,
    )

    /** Geometry stats, for the unit test that guards the outline. */
    internal fun stats(shape: String): String {
        val o = outline(shape)
        var minEdge = Float.MAX_VALUE
        for (i in o.pts.indices) {
            val a = o.pts[i]; val b = o.pts[(i + 1) % o.pts.size]
            val d = hypot(b.first - a.first, b.second - a.second)
            if (d < minEdge) minEdge = d
        }
        return "shape=$shape pts=${o.pts.size} minEdge=$minEdge height=${o.height} width=${o.width}"
    }

    private fun pillow(o: Outline, shape: String): Mesh {
        val pts = o.pts
        val n = pts.size
        val steps = 7
        val dir = pts.mapIndexed { i, p ->
            val a = pts[(i - 1 + n) % n]; val c = pts[(i + 1) % n]
            var e1x = p.first - a.first; var e1y = p.second - a.second
            var e2x = c.first - p.first; var e2y = c.second - p.second
            val l1 = hypot(e1x, e1y).takeIf { it > 0f } ?: 1f
            val l2 = hypot(e2x, e2y).takeIf { it > 0f } ?: 1f
            e1x /= l1; e1y /= l1; e2x /= l2; e2y /= l2
            var mx = e1y + e2y; var my = -e1x - e2x
            val lm = hypot(mx, my).takeIf { it > 0f } ?: 1f
            mx /= lm; my /= lm
            var k = 1f / max(0.72f, mx * e1y + my * -e1x)
            // A petal cusp is a sharp reflex corner: offsetting it as
            // deeply as a smooth edge would cut a notch into the face.
            if (e1x * e2y - e1y * e2x < -0.05f) k *= 0.3f
            floatArrayOf(mx, my, k)
        }

        val profile = ArrayList<FloatArray>()
        for (j in 0..steps) {
            val a = (PI.toFloat() / 2) * (1 - j.toFloat() / steps)
            profile += floatArrayOf(BEVEL * (cos(a) - 1), HALF - BEVEL + BEVEL * sin(a), cos(a), sin(a))
        }
        for (j in 0..steps) {
            val a = (-PI.toFloat() / 2) * (j.toFloat() / steps)
            profile += floatArrayOf(BEVEL * (cos(a) - 1), -(HALF - BEVEL) + BEVEL * sin(a), cos(a), sin(a))
        }

        val rings = profile.size
        val total = rings * n + 2
        val x = FloatArray(total); val y = FloatArray(total); val z = FloatArray(total)
        val nx = FloatArray(total); val ny = FloatArray(total); val nz = FloatArray(total)
        val shade = FloatArray(total)
        for (j in 0 until rings) {
            val r = profile[j][0]; val zz = profile[j][1]; val nr = profile[j][2]; val nzz = profile[j][3]
            for (i in 0 until n) {
                val o3 = j * n + i
                val d = dir[i]
                x[o3] = pts[i].first + d[0] * d[2] * r
                y[o3] = pts[i].second + d[1] * d[2] * r
                z[o3] = zz
                nx[o3] = d[0] * nr; ny[o3] = d[1] * nr; nz[o3] = nzz
                val t = (y[o3] / o.height).coerceIn(0f, 1f)
                shade[o3] = 0.8f + 0.2f * t * (2 - t)
            }
        }
        // Two cap centers: the front face and the back.
        val front = rings * n
        var sx = 0f; var sy = 0f
        for (i in 0 until n) { sx += pts[i].first; sy += pts[i].second }
        sx /= n; sy /= n
        x[front] = sx; y[front] = sy; z[front] = HALF
        nx[front] = 0f; ny[front] = 0f; nz[front] = 1f
        shade[front] = 0.9f + 0.1f * (sy / o.height).coerceIn(0f, 1f)
        x[front + 1] = sx; y[front + 1] = sy; z[front + 1] = -HALF
        nx[front + 1] = 0f; ny[front + 1] = 0f; nz[front + 1] = -1f
        shade[front + 1] = 0.72f

        val idx = ArrayList<Short>(rings * n * 6 + n * 6)
        // Back cap first, then the rim, then the lit face: painter's order.
        val back = (rings - 1) * n
        for (i in 0 until n) {
            idx += (front + 1).toShort()
            idx += (back + (i + 1) % n).toShort()
            idx += (back + i).toShort()
        }
        for (j in 0 until rings - 1) {
            for (i in 0 until n) {
                val a = (j * n + i).toShort()
                val b = (j * n + (i + 1) % n).toShort()
                val c = ((j + 1) * n + i).toShort()
                val d = ((j + 1) * n + (i + 1) % n).toShort()
                idx += a; idx += c; idx += d
                idx += a; idx += d; idx += b
            }
        }
        for (i in 0 until n) {
            idx += front.toShort()
            idx += i.toShort()
            idx += ((i + 1) % n).toShort()
        }
        return Mesh(x, y, z, nx, ny, nz, shade, idx.toShortArray(), total, n)
    }

    // ---------- camera, as on the site ----------

    private class Camera(val ex: Float, val ey: Float, val ez: Float, val rx: Float, val ry: Float, val rz: Float,
                         val ux: Float, val uy: Float, val uz: Float, val fx: Float, val fy: Float, val fz: Float)

    private fun camera(o: Outline): Camera {
        val tall = o.height * cos(LIFT) + (2 * HALF + BEVEL) * sin(LIFT)
        val extent = max(tall, o.width + BEVEL) / 0.78f
        val dist = extent / (2 * TAN_HALF)
        val cy = o.height * 0.53f
        val ex = 0f; val ey = cy + sin(LIFT) * dist; val ez = cos(LIFT) * dist
        // Forward toward the body, then a right vector and an up vector.
        var fx = 0f - ex; var fy = cy - ey; var fz = 0f - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= fl; fy /= fl; fz /= fl
        // right = forward × up, with up = (0, 1, 0), so the body is not mirrored
        var rx = -fz; var ry = 0f; var rz = fx
        val rl = sqrt(rx * rx + ry * ry + rz * rz)
        rx /= rl; ry /= rl; rz /= rl
        val ux = ry * fz - rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy - ry * fx
        return Camera(ex, ey, ez, rx, ry, rz, ux, uy, uz, fx, fy, fz)
    }

    // ---------- painting ----------

    private class Call(val verts: FloatArray, val colors: IntArray, val count: Int)

    private class Body3D(
        val calls: List<Call>, val paint: Paint, val face: android.graphics.Path, val silhouette: android.graphics.Path, val facePaint: Paint,
    )

    private val bodies = HashMap<Long, Body3D>()
    private val bitmaps = HashMap<Long, android.graphics.Bitmap>()

    private fun toVertices(shape: String, tint: Long): Body3D {
        val key = (shape.hashCode().toLong() shl 40) xor (tint shl 8)
        bodies[key]?.let { return it }
        val o = outline(shape)
        val m = pillow(o, shape)
        val c = camera(o)
        val srcVerts = FloatArray(m.count * 2)
        for (i in 0 until m.count) {
            val dx = m.x[i] - c.ex; val dy = m.y[i] - c.ey; val dz = m.z[i] - c.ez
            val vx = dx * c.rx + dy * c.ry + dz * c.rz
            val vy = dx * c.ux + dy * c.uy + dz * c.uz
            val vz = dx * c.fx + dy * c.fy + dz * c.fz
            val ta = vz * TAN_HALF
            srcVerts[i * 2] = (vx / ta * 0.5f + 0.5f) * 106f
            srcVerts[i * 2 + 1] = (0.5f - vy / ta * 0.5f) * 106f
        }
        val r0 = ((tint shr 16) and 0xff).toFloat() / 255f
        val g0 = ((tint shr 8) and 0xff).toFloat() / 255f
        val b0 = (tint and 0xff).toFloat() / 255f
        // Key light from the upper left, a soft rim from behind, and a
        // hemisphere that brightens the top — the site's three lights.
        val kx = -2.5f; val ky = 6f; val kz = 5f
        val kl = sqrt(kx * kx + ky * ky + kz * kz)
        val lx = 4f; val ly = 2f; val lz = -3f
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val srcColors = IntArray(m.count)
        for (i in 0 until m.count) {
            val d1 = max(0f, (m.nx[i] * kx + m.ny[i] * ky + m.nz[i] * kz) / kl)
            val d2 = max(0f, (m.nx[i] * lx + m.ny[i] * ly + m.nz[i] * lz) / ll)
            // Soft, high-key light like the site's: bright on top, never black below.
            val hemi = 0.78f + 0.14f * m.ny[i]
            val lit = (hemi + 0.42f * d1 + 0.12f * d2) * m.shade[i]
            fun ch(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
            srcColors[i] = (0xff shl 24) or (ch(r0 * lit) shl 16) or (ch(g0 * lit) shl 8) or ch(b0 * lit)
        }

        // The rim as plain triangles; each cap as one fan around its own
        // center, so the lit face lands last and covers the back.
        val n = m.n
        val rings = (m.count - 2) / n
        val rimFrom = n * 3
        val rimTo = m.index.size - n * 3
        fun build(from: Int, to: Int): Call {
            val count = to - from
            val verts = FloatArray(count * 2)
            val colors = IntArray(count)
            for (k in 0 until count) {
                val v = m.index[from + k].toInt()
                verts[k * 2] = srcVerts[v * 2]
                verts[k * 2 + 1] = srcVerts[v * 2 + 1]
                colors[k] = srcColors[v]
            }
            return Call(verts, colors, count)
        }
        // The face: the front ring as one flat lit plane, softened from
        // bright at the top to a little darker at the bottom.
        val face = android.graphics.Path()
        for (i in 0 until n) {
            val x = srcVerts[i * 2]; val y = srcVerts[i * 2 + 1]
            if (i == 0) face.moveTo(x, y) else face.lineTo(x, y)
        }
        face.close()
        var top = Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        for (i in 0 until n) { val y = srcVerts[i * 2 + 1]; if (y < top) top = y; if (y > bottom) bottom = y }
        val faceLit = srcColors[rings * n]
        val dim = 0xff shl 24 or
            ((((faceLit shr 16) and 0xff) * 4 / 5) shl 16) or
            ((((faceLit shr 8) and 0xff) * 4 / 5) shl 8) or
            (((faceLit and 0xff) * 4 / 5))
        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, top, 0f, bottom, faceLit, dim, android.graphics.Shader.TileMode.CLAMP)
        }

        // The widest ring, filled behind the rim: where the bevel folds at
        // a tight corner, what shows through is the body, not the page.
        val silhouette = android.graphics.Path()
        val wide = rings / 2 - 1
        for (i in 0 until n) {
            val x = srcVerts[(wide * n + i) * 2]; val y = srcVerts[(wide * n + i) * 2 + 1]
            if (i == 0) silhouette.moveTo(x, y) else silhouette.lineTo(x, y)
        }
        silhouette.close()
        val calls = listOf(build(rimFrom, rimTo))
        val body = Body3D(calls, Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG), face, silhouette, facePaint)
        if (bodies.size > 200) bodies.clear()
        bodies[key] = body
        return body
    }

    /** Draw the body in the flat rig's 106-unit box; the caller scales it. */
    fun draw(canvas: Canvas, shape: String, tint: Long, px: Float, dest: android.graphics.RectF) {
        val pxInt = px.toInt().coerceIn(8, 512)
        val key = (shape.hashCode().toLong() shl 40) xor (tint shl 8) xor pxInt.toLong()
        val bmp = bitmaps[key] ?: run {
            val b = toVertices(shape, tint)
            val out = android.graphics.Bitmap.createBitmap(pxInt, pxInt, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val scale = pxInt / 106f
            c.scale(scale, scale)
            // The face tucks a hair under the rim, so the two never leave
            // a pale seam where their antialiased edges meet.
            val fb = android.graphics.RectF()
            b.face.computeBounds(fb, true)
            c.drawPath(b.silhouette, b.facePaint)
            // The rim, then the face over it: the back of the bevel would
            // otherwise paint across the top of the face.
            b.calls.getOrNull(0)?.let { call ->
                c.drawVertices(VertexMode.TRIANGLES, call.count, call.verts, 0, null, 0, call.colors, 0, null, 0, 0, b.paint)
            }
            c.drawPath(b.face, b.facePaint)
            if (bitmaps.size > 120) { for (v in bitmaps.values) v.recycle(); bitmaps.clear() }
            bitmaps[key] = out
            out
        }
        canvas.drawBitmap(bmp, null, dest, null)
    }
}
