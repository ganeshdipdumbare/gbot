package dev.gbot.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.ui.Dark
import dev.gbot.app.ui.Glow
import dev.gbot.app.ui.Light
import dev.gbot.app.ui.bodies
import dev.gbot.app.ui.box
import dev.gbot.app.ui.drawBody
import dev.gbot.app.ui.drawGround
import dev.gbot.app.ui.hues
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The body light, as the app paints it: thickness under a lit face, the key
 * on the top bevel, the rim down the right. Renders a contact sheet of every
 * shape in both themes, and checks the two things that make a bot read as an
 * object — light from above, and an edge that survives light mode, where a
 * cream bot on white paper has no silhouette of its own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class GlowTest {
    private val cell = 300

    private fun render(shape: String, hue: String, dark: Boolean): ImageBitmap {
        val image = ImageBitmap(cell, cell)
        val path = PathParser().parsePathString(bodies.getValue(shape).d).toPath()
        val bounds = path.getBounds()
        val g = if (dark) Dark else Light
        val paint = Glow.paint(hues.getValue(hue).body, bounds, dark)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(cell.toFloat(), cell.toFloat())) {
            drawRect(g.bg)
            box {
                drawGround(paint, lift = 0f, sx = 1f)
                drawBody(path, bounds, paint, flat = false, px = 0f, py = 0f)
            }
        }
        return image
    }

    private fun ImageBitmap.at(x: Float, y: Float): Int =
        asAndroidBitmap().getPixel((x * cell / 106f).toInt(), (y * cell / 106f).toInt())

    /** How far two colours are apart, as the eye roughly sees it. */
    private fun apart(a: Int, b: Int): Int =
        maxOf(abs((a shr 16 and 0xff) - (b shr 16 and 0xff)), abs((a shr 8 and 0xff) - (b shr 8 and 0xff)), abs((a and 0xff) - (b and 0xff)))

    @Test
    fun everyShapeIsLitFromAbove() {
        for (shape in bodies.keys) {
            val image = render(shape, "cobalt", dark = false)
            val meta = bodies.getValue(shape)
            // Points well inside any of the bodies, above and below the eye line.
            val top = image.at(3 + 50f, 3 + meta.faceY - 12)
            val bottom = image.at(3 + 50f, 3 + meta.faceY + 22)
            val lum = { c: Int -> (c shr 16 and 0xff) + (c shr 8 and 0xff) + (c and 0xff) }
            assertTrue("$shape should be lighter up top", lum(top) > lum(bottom) + 30)
        }
    }

    @Test
    fun paleBotsKeepAnEdgeOnLightPaper() {
        // The app's own mark is near-white on near-white chrome; without an
        // edge of its own it dissolves into the page.
        for (hue in listOf("white", "cream", "sun")) {
            val image = render("circle", hue, dark = false)
            val bg = Light.bg.toArgb()
            // Along the body's widest line, each side has to part from the
            // page somewhere — a contour, a rim, or both.
            val row = ((3 + 50f) * cell / 106f).toInt()
            val bmp = image.asAndroidBitmap()
            val mid = (53f * cell / 106f).toInt()
            for ((side, range) in listOf("left" to (0 until mid), "right" to (mid until cell))) {
                val edge = range.maxOf { apart(bmp.getPixel(it, row), bg) }
                assertTrue("$hue needs a $side edge, got $edge", edge > 20)
            }
        }
    }

    /** One hue per shape, as the website's crew wears them. */
    private val crew = listOf("cobalt", "mint", "sky", "coral", "bubblegum", "grape", "sun", "cream")

    @Test
    fun sheet() {
        val shapes = bodies.keys.toList()
        val sheet = Bitmap.createBitmap(cell * shapes.size, cell * 2, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sheet)
        canvas.drawColor(Color.Magenta.toArgb())
        shapes.forEachIndexed { i, shape ->
            for ((row, dark) in listOf(false, true).withIndex()) {
                canvas.drawBitmap(render(shape, crew[i % crew.size], dark).asAndroidBitmap(), (i * cell).toFloat(), (row * cell).toFloat(), null)
            }
        }
        val out = File("build/shots/shapes3d.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(out.exists() && out.length() > 1000)
    }
}
