package dev.gbot.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import dev.gbot.app.ui.Bots3D
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

// The 3D rig is ported geometry: render it large once, so a seam or a
// folded outline is visible without a phone.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class Bots3DTest {
    @Test
    fun everyShapeBuilds() {
        val shapes = listOf("circle", "square", "triangle", "hexagon", "cloud", "pill", "arch", "diamond")
        val cell = 300
        val sheet = Bitmap.createBitmap(cell * 4, cell * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xfffdfcfb.toInt())
        shapes.forEachIndexed { i, shape ->
            val bmp = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(cell / 106f, cell / 106f)
            Bots3D.draw(c, shape, 0xff3b6cf6, cell.toFloat(), RectF(0f, 0f, 106f, 106f))
            canvas.drawBitmap(bmp, ((i % 4) * cell).toFloat(), ((i / 4) * cell).toFloat(), null)
            println(Bots3D.stats(shape))
        }
        val out = File("build/shots/shapes3d.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(true)
    }
}
