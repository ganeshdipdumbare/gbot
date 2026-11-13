package dev.gbot.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.ui.Glow
import dev.gbot.app.ui.bodies
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.pow

// Grok-style glow on the clean bezier bodies: one sheet so the fill reads
// as volume without a dark rim.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class GlowTest {
    @Test
    fun everyShapeGlows() {
        val shapes = listOf("circle", "square", "triangle", "hexagon", "cloud", "pill", "arch", "diamond")
        val cell = 300
        val sheet = Bitmap.createBitmap(cell * 4, cell * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xfff7f5f2.toInt())
        val tint = 0xFF3B6CF6L
        shapes.forEachIndexed { i, shape ->
            val path = PathParser().parsePathString(bodies.getValue(shape).d).toPath()
            val b = path.getBounds()
            val bmp = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val scale = cell / 106f
            c.translate(3f * scale, 3f * scale)
            c.scale(scale, scale)
            val halo = android.graphics.Path(path.asAndroidPath())
            val m = Matrix()
            m.setScale(1.055f, 1.055f, b.center.x, b.center.y)
            halo.transform(m)
            val ring = android.graphics.Path(halo).apply {
                fillType = android.graphics.Path.FillType.EVEN_ODD
                addPath(path.asAndroidPath())
            }
            c.drawPath(ring, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x38335ecf })
            val cx = b.left + b.width * 0.5f
            val cy = b.top + b.height * 0.18f
            val r = maxOf(b.width, b.height) * 0.98f
            val n = 24
            val stops = FloatArray(n + 1)
            val colors = IntArray(n + 1)
            fun mixLong(a: Long, bb: Long, t: Float): Long {
                fun ch(col: Long, s: Int) = ((col shr s) and 0xff).toInt()
                var out = 0xFF000000L
                for (s in listOf(16, 8, 0)) {
                    val v = (ch(a, s) + (ch(bb, s) - ch(a, s)) * t).toInt().coerceIn(0, 255)
                    out = out or (v.toLong() shl s)
                }
                return out
            }
            fun smooth(t: Float): Float { val x = t.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }
            val inner = mixLong(tint, 0xFFFFFFFF, 0.42f)
            val mid = mixLong(tint, 0xFFFFFFFF, 0.06f)
            val rim = mixLong(tint, 0xFF000000, 0.34f)
            fun mix3(u: Float): Long = if (u < 0.5f) mixLong(inner, mid, smooth(u / 0.5f))
            else mixLong(mid, rim, smooth((u - 0.5f) / 0.5f))
            for (k in 0..n) {
                val u = k / n.toFloat()
                stops[k] = 1f - (1f - u) * (1f - u)
                val kEdge = 0.9f + 0.1f * (1f - u * u).toDouble().pow(0.225).toFloat()
                val c0 = mix3(u)
                fun ch(col: Long, s: Int) = (((col shr s) and 0xff) * kEdge).toInt().coerceIn(0, 255)
                colors[k] = (0xff shl 24) or (ch(c0, 16) shl 16) or (ch(c0, 8) shl 8) or ch(c0, 0)
            }
            c.drawPath(path.asAndroidPath(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, r, colors, stops, Shader.TileMode.CLAMP)
            })
            assertTrue(Glow.body(tint, Offset(cx, cy), r).toString().isNotEmpty())
            canvas.drawBitmap(bmp, ((i % 4) * cell).toFloat(), ((i / 4) * cell).toFloat(), null)
        }
        val out = File("build/shots/shapes3d.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.exists() && out.length() > 1000)
    }
}
