package dev.gbot.app.ui.bot3d

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.gbot.app.ui.Traits
import dev.gbot.app.ui.hues
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.CountDownLatch
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The web's 3D bot rig (packages/characters/src/gl.ts) on Android: the same
 * pillow bodies, the same three lights and the same MeshStandardMaterial
 * shading as three.js 0.187, so a bot here is the bot on the website.
 *
 * Like the web, one GL context serves every avatar. It lives on its own
 * thread; each paint renders one bot into a multisampled offscreen buffer
 * and reads it back into that avatar's pixels.
 */
internal object BotGL {
    private const val TAG = "BotGL"
    const val MAX_PX = 768
    private const val LIFT = 14f * DEG
    private val EYE = 0xFF16131CL

    private lateinit var handler: Handler

    /**
     * True when this phone can draw the bots in three dimensions. Set up on
     * first use; [warm] starts that at launch, so the UI rarely waits.
     */
    val ok: Boolean by lazy { start() }

    /** Start setting up in the background, from app launch. */
    fun warm() { Thread({ ok }, "gbot-gl-warm").start() }

    /**
     * Set when the GL rig can't be built at all, [TRIES] times in a row:
     * every bot then falls back to the 2D painter, rather than staying blank.
     * One bot's failed paints never set it; that bot falls back alone. Compose
     * state, so screens switch over as soon as it flips.
     */
    var gaveUp by androidx.compose.runtime.mutableStateOf(false)
        private set
    private const val TRIES = 3
    private var failures = 0

    private fun start(): Boolean {
        if (Build.FINGERPRINT == "robolectric") return false
        return try {
            val t = HandlerThread("gbot-gl").apply { start() }
            handler = Handler(t.looper)
            var ready = false
            val latch = CountDownLatch(1)
            handler.post {
                ready = try { setup() } catch (e: Throwable) { Log.w(TAG, "no 3D bots", e); false }
                latch.countDown()
            }
            latch.await()
            if (!ready) t.quitSafely()
            ready
        } catch (e: Throwable) {
            Log.w(TAG, "no 3D bots", e); false
        }
    }

    /** Paint on the GL thread, then hand the pixels back on [done]'s thread. */
    fun post(job: () -> Unit) { handler.post(job) }

    /** Run on the GL thread and wait for it: for an avatar's very first paint. */
    fun now(job: () -> Unit) {
        val latch = CountDownLatch(1)
        handler.post { try { job() } finally { latch.countDown() } }
        latch.await()
    }

    // ---------- EGL ----------

    // Nullable, so this object loads where EGL is only a stub (unit tests).
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var surface: EGLSurface? = null
    private var config: EGLConfig? = null

    private fun setup(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false
        val v = IntArray(2)
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) return false
        val attrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) || n[0] == 0) return false
        config = configs[0]
        return makeContext()
    }

    private fun makeContext(): Boolean {
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) return false
        surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) return false
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return false
        fbSize = 0; msFbo = 0; outFbo = 0; rb.fill(0)
        programs()
        meshes.clear(); flats.clear()
        return true
    }

    /** A lost context takes the whole rig with it: build a fresh one. */
    private var broken = false

    private fun ensure(): Boolean {
        if (!broken && EGL14.eglGetCurrentContext() == context && EGL14.eglGetError() != 0x300E /* EGL_CONTEXT_LOST */) return true
        Log.w(TAG, "GL context lost or failed; rebuilding")
        broken = true
        try {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        } catch (_: Throwable) {}
        return makeContext().also { broken = !it }
    }

    // ---------- shaders: MeshStandardMaterial as three.js 0.187 builds it ----------

    private const val BODY_VS = """#version 300 es
uniform mat4 uModelView; uniform mat4 uProj; uniform mat3 uNormalM;
in vec3 aPos; in vec3 aNormal; in float aShade;
out vec3 vViewPosition; out vec3 vNormal; out float vShade;
void main() {
  vec4 mv = uModelView * vec4(aPos, 1.0);
  vViewPosition = -mv.xyz;
  vNormal = normalize(uNormalM * aNormal);
  vShade = aShade;
  gl_Position = uProj * mv;
}"""

    private const val BODY_FS = """#version 300 es
precision highp float;
#define PI 3.141592653589793
#define RECIPROCAL_PI 0.3183098861837907
#define EPSILON 1e-6
uniform vec3 uColor;
uniform vec3 uSky; uniform vec3 uGround; uniform vec3 uHemiDir;
uniform vec3 uKeyDir; uniform vec3 uKeyColor; uniform vec3 uRimDir; uniform vec3 uRimColor;
uniform float uRoughness;
uniform sampler2D uDfg;
in vec3 vViewPosition; in vec3 vNormal; in float vShade;
out vec4 fragColor;
float pow2(float x) { return x * x; }
vec3 F_Schlick(vec3 f0, float f90, float dotVH) {
  float fresnel = exp2((-5.55473 * dotVH - 6.98316) * dotVH);
  return f0 * (1.0 - fresnel) + (f90 * fresnel);
}
float V_GGX_SmithCorrelated(float alpha, float dotNL, float dotNV) {
  float a2 = pow2(alpha);
  float gv = dotNL * sqrt(a2 + (1.0 - a2) * pow2(dotNV));
  float gl = dotNV * sqrt(a2 + (1.0 - a2) * pow2(dotNL));
  return 0.5 / max(gv + gl, EPSILON);
}
float D_GGX(float alpha, float dotNH) {
  float a2 = pow2(alpha);
  float denom = pow2(dotNH) * (a2 - 1.0) + 1.0;
  return RECIPROCAL_PI * a2 / pow2(denom);
}
vec3 BRDF_GGX(vec3 L, vec3 V, vec3 N, vec3 f0, float roughness) {
  float alpha = pow2(max(roughness, 0.045));
  vec3 H = normalize(L + V);
  float dotNL = clamp(dot(N, L), 0.0, 1.0);
  float dotNV = clamp(dot(N, V), 0.0, 1.0);
  float dotNH = clamp(dot(N, H), 0.0, 1.0);
  float dotVH = clamp(dot(V, H), 0.0, 1.0);
  return F_Schlick(f0, 1.0, dotVH) * (V_GGX_SmithCorrelated(alpha, dotNL, dotNV) * D_GGX(alpha, dotNH));
}
vec4 sRGBTransferOETF(vec4 value) {
  return vec4(mix(pow(value.rgb, vec3(0.41666)) * 1.055 - vec3(0.055), value.rgb * 12.92, vec3(lessThanEqual(value.rgb, vec3(0.0031308)))), value.a);
}
vec3 direct(vec3 L, vec3 color, vec3 N, vec3 V, vec3 diffuse, vec3 f0, float roughness, vec3 msc) {
  float dotNL = clamp(dot(N, L), 0.0, 1.0);
  vec3 irradiance = dotNL * color;
  vec3 spec = irradiance * BRDF_GGX(L, V, N, f0, roughness) * msc;
  vec3 H = normalize(L + V);
  vec3 F = F_Schlick(f0, 1.0, clamp(dot(V, H), 0.0, 1.0));
  return spec + irradiance * (RECIPROCAL_PI * diffuse) * (1.0 - F);
}
void main() {
  vec3 diffuseColor = uColor * vec3(vShade);
  vec3 normal = normalize(vNormal);
  vec3 dxy = max(abs(dFdx(normal)), abs(dFdy(normal)));
  float geometryRoughness = max(max(dxy.x, dxy.y), dxy.z);
  float roughness = min(max(uRoughness, 0.045) + geometryRoughness, 1.0);
  vec3 f0 = vec3(0.04);
  vec3 V = normalize(vViewPosition);
  float dotNV = clamp(dot(normal, V), 0.0, 1.0);
  vec2 dfg = texture(uDfg, vec2(roughness, dotNV)).rg;
  vec3 msc = 1.0 + f0 * (1.0 / (dfg.x + dfg.y) - 1.0);
  vec3 outgoing = direct(uKeyDir, uKeyColor, normal, V, diffuseColor, f0, roughness, msc)
                + direct(uRimDir, uRimColor, normal, V, diffuseColor, f0, roughness, msc);
  // The hemisphere light, as indirect diffuse with multi-scattering.
  vec3 irradiance = mix(uGround, uSky, 0.5 * dot(normal, uHemiDir) + 0.5);
  vec3 FssEss = f0 * dfg.x + dfg.y;
  float Ess = dfg.x + dfg.y;
  float Ems = 1.0 - Ess;
  vec3 Favg = f0 + (1.0 - f0) * 0.047619;
  vec3 Fms = FssEss * Favg / (1.0 - Ems * Favg);
  outgoing += irradiance * (RECIPROCAL_PI * diffuseColor) * (1.0 - FssEss - Fms * Ems);
  fragColor = sRGBTransferOETF(vec4(outgoing, 1.0));
}"""

    private const val FLAT_VS = """#version 300 es
uniform mat4 uMvp;
in vec2 aXy;
out vec2 vUv;
void main() { vUv = aXy + 0.5; gl_Position = uMvp * vec4(aXy, 0.0, 1.0); }"""

    private const val FLAT_FS = """#version 300 es
precision mediump float;
uniform vec4 uColor;
uniform float uShadow;
in vec2 vUv;
out vec4 fragColor;
void main() {
  float a = uColor.a;
  if (uShadow > 0.5) {
    // The web's shadow texture: a radial fade, 1 → .38 at .55 → 0 at the rim.
    float d = min(length(vUv - 0.5) / 0.5, 1.0);
    a *= d < 0.55 ? mix(1.0, 0.38, d / 0.55) : mix(0.38, 0.0, (d - 0.55) / 0.45);
  }
  fragColor = vec4(uColor.rgb, a);
}"""

    private var body = 0
    private var flat = 0
    private var dfgTex = 0
    private val loc = HashMap<String, Int>()

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }

    private fun program(vs: String, fs: String, attrs: List<String>): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        attrs.forEachIndexed { i, a -> GLES30.glBindAttribLocation(p, i, a) }
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private fun u(p: Int, name: String): Int = loc.getOrPut("$p:$name") { GLES30.glGetUniformLocation(p, name) }

    private fun programs() {
        loc.clear()
        body = program(BODY_VS, BODY_FS, listOf("aPos", "aNormal", "aShade"))
        flat = program(FLAT_VS, FLAT_FS, listOf("aXy"))
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        dfgTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTex)
        val data = ByteBuffer.allocateDirect(DFG_LUT.size * 2).order(ByteOrder.nativeOrder())
        for (v in DFG_LUT) data.putShort(v.toShort())
        data.flip()
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG16F, 16, 16, 0, GLES30.GL_RG, GLES30.GL_HALF_FLOAT, data)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    // ---------- buffers ----------

    private class Gpu(val vbo: Int, val ibo: Int, val count: Int)

    private val outlines = HashMap<String, Outline>()
    private val meshes = HashMap<String, Gpu>()
    private val flats = HashMap<String, Gpu>()

    fun outlineOf(shape: String): Outline = synchronized(outlines) { outlines.getOrPut(shape) { outline(shape) } }

    private fun upload(verts: FloatArray, index: ShortArray): Gpu {
        val b = IntArray(2)
        GLES30.glGenBuffers(2, b, 0)
        val fb: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        fb.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, fb, GLES30.GL_STATIC_DRAW)
        val sb: ShortBuffer = ByteBuffer.allocateDirect(index.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(index)
        sb.flip()
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, b[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, index.size * 2, sb, GLES30.GL_STATIC_DRAW)
        return Gpu(b[0], b[1], index.size)
    }

    private fun bodyMesh(shape: String) = meshes.getOrPut(shape) { pillow(outlineOf(shape)).let { upload(it.verts, it.index) } }
    private fun flatMesh(key: String, make: () -> Flat) = flats.getOrPut(key) { make().let { upload(it.xy, it.index) } }
    private val unitQuad by lazy { Flat(floatArrayOf(-0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f), shortArrayOf(0, 1, 2, 0, 2, 3)) }

    private var fbSize = 0
    private var msFbo = 0
    private var outFbo = 0
    private val rb = IntArray(3)

    private fun framebuffers(px: Int) {
        if (px <= fbSize) return
        val size = max(px, 256)
        if (msFbo != 0) {
            // The renderbuffers go with their framebuffers, or each bigger size leaks the last.
            GLES30.glDeleteFramebuffers(2, intArrayOf(msFbo, outFbo), 0)
            GLES30.glDeleteRenderbuffers(3, rb, 0)
        }
        val fb = IntArray(2)
        GLES30.glGenFramebuffers(2, fb, 0)
        GLES30.glGenRenderbuffers(3, rb, 0)
        val maxS = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_SAMPLES, maxS, 0)
        val samples = minOf(4, maxS[0])
        msFbo = fb[0]; outFbo = fb[1]
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[0])
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_RGBA8, size, size)
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[1])
        GLES30.glRenderbufferStorageMultisample(GLES30.GL_RENDERBUFFER, samples, GLES30.GL_DEPTH_COMPONENT24, size, size)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rb[0])
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, rb[1])
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rb[2])
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, size, size)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, rb[2])
        fbSize = size
    }

    // ---------- one paint ----------

    private fun srgbToLinear(c: Float) = if (c < 0.04045f) c * 0.0773993808f else ((c * 0.9478672986f + 0.0521327014f).toDouble().pow(2.4)).toFloat()
    private fun linear(argb: Long) = floatArrayOf(
        srgbToLinear(((argb shr 16) and 0xff) / 255f), srgbToLinear(((argb shr 8) and 0xff) / 255f), srgbToLinear((argb and 0xff) / 255f),
    )
    private fun norm(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return floatArrayOf(x / l, y / l, z / l) }

    private val ground = linear(0xFFCFC8BDL).map { it * 2.35f }.toFloatArray()
    private val keyDir = norm(-2.5f, 6f, 5f)
    private val rimDir = norm(4f, 2f, -3f)

    private val view = FloatArray(16); private val proj = FloatArray(16)
    private val model = FloatArray(16); private val mv = FloatArray(16); private val tmp = FloatArray(16); private val mvp = FloatArray(16)
    private val vp = FloatArray(16)
    private val v4 = FloatArray(4); private val o4 = FloatArray(4)

    private fun viewDir(d: FloatArray): FloatArray {
        v4[0] = d[0]; v4[1] = d[1]; v4[2] = d[2]; v4[3] = 0f
        Matrix.multiplyMV(o4, 0, view, 0, v4, 0)
        return norm(o4[0], o4[1], o4[2])
    }

    /**
     * Paint one bot at [px]×[px] into [out] (RGBA, premultiplied, top row
     * first). False when the GL work failed; the next paint rebuilds.
     */
    fun paint(look: Traits, f: Frame, px: Int, out: ByteBuffer): Boolean {
        val ok = try {
            draw(look, f, px, out)
        } catch (e: Throwable) {
            Log.w(TAG, "paint failed", e)
            false
        }
        if (ok) return true
        // Whatever broke — a throw or a GL error — start the next paint from
        // a fresh context. Whether that one bot then falls back to 2D is its
        // own call (see BotView); only a rig that can't be built at all turns
        // 3D off for every bot.
        broken = true
        return false
    }

    /** The rig came up, or couldn't: [TRIES] failed builds in a row turn 3D off app-wide. */
    private fun built(ok: Boolean): Boolean {
        if (ok) { failures = 0; return true }
        if (++failures >= TRIES && !gaveUp) {
            Log.w(TAG, "the 3D rig can't be built; using the 2D painter")
            android.os.Handler(android.os.Looper.getMainLooper()).post { gaveUp = true }
        }
        return false
    }

    /** Every pending GL error, cleared; glGetError reports only one per call. */
    private fun drainErrors(): Boolean {
        var clean = true
        while (true) {
            val e = GLES30.glGetError()
            if (e == GLES30.GL_NO_ERROR) return clean
            Log.w(TAG, "GL error 0x" + Integer.toHexString(e))
            clean = false
        }
    }

    private fun draw(look: Traits, f: Frame, px: Int, out: ByteBuffer): Boolean {
        if (!built(try { ensure() } catch (e: Throwable) { Log.w(TAG, "rebuild failed", e); false })) return false
        drainErrors() // left over from an earlier paint: not this one's fault
        val o = outlineOf(look.shape)
        framebuffers(px)

        // Fit the camera so the body fills the square, seen a little from above.
        val tall = o.height * cos(LIFT) + (2 * HALF + BEVEL) * sin(LIFT)
        val extent = max(tall, o.width + BEVEL) / 0.78f
        val dist = extent / (2 * tan(6.5f * DEG))
        val cy = o.height * 0.53f
        Matrix.setLookAtM(view, 0, 0f, cy + sin(LIFT) * dist, cos(LIFT) * dist, 0f, cy, 0f, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 13f, 1f, 0.1f, 200f)
        // Flip y, so the read-back rows come out top first.
        Matrix.scaleM(proj, 0, 1f, -1f, 1f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, msFbo)
        GLES30.glViewport(0, 0, px, px)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glFrontFace(GLES30.GL_CW) // the y flip mirrors the winding

        // The floor shadow first, without writing depth.
        GLES30.glUseProgram(flat)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFuncSeparate(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA, GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(false)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, 0.002f, 0f)
        Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, f.shadowW, f.shadowD, 1f)
        drawFlat(flatMesh("quad") { unitQuad }, 0f, 0f, 0f, f.shadowA, shadow = true)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        // The body.
        val pivot = FloatArray(16)
        Matrix.setIdentityM(pivot, 0)
        Matrix.translateM(pivot, 0, 0f, f.y, 0f)
        Matrix.rotateM(pivot, 0, f.rx / DEG, 1f, 0f, 0f)
        Matrix.rotateM(pivot, 0, f.ry / DEG, 0f, 1f, 0f)
        Matrix.rotateM(pivot, 0, f.rz / DEG, 0f, 0f, 1f)
        Matrix.scaleM(pivot, 0, f.sx, f.sy, f.sx)

        GLES30.glUseProgram(body)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        Matrix.multiplyMM(mv, 0, view, 0, pivot, 0)
        GLES30.glUniformMatrix4fv(u(body, "uModelView"), 1, false, mv, 0)
        GLES30.glUniformMatrix4fv(u(body, "uProj"), 1, false, proj, 0)
        Matrix.invertM(tmp, 0, mv, 0)
        val nm = floatArrayOf(tmp[0], tmp[4], tmp[8], tmp[1], tmp[5], tmp[9], tmp[2], tmp[6], tmp[10])
        GLES30.glUniformMatrix3fv(u(body, "uNormalM"), 1, false, nm, 0)
        val hue = hues[look.hue] ?: hues.getValue("mint")
        GLES30.glUniform3fv(u(body, "uColor"), 1, linear(hue.body), 0)
        GLES30.glUniform3f(u(body, "uSky"), 2.35f, 2.35f, 2.35f)
        GLES30.glUniform3fv(u(body, "uGround"), 1, ground, 0)
        GLES30.glUniform3fv(u(body, "uHemiDir"), 1, viewDir(floatArrayOf(0f, 1f, 0f)), 0)
        GLES30.glUniform3fv(u(body, "uKeyDir"), 1, viewDir(keyDir), 0)
        GLES30.glUniform3f(u(body, "uKeyColor"), 1.55f, 1.55f, 1.55f)
        GLES30.glUniform3fv(u(body, "uRimDir"), 1, viewDir(rimDir), 0)
        GLES30.glUniform3f(u(body, "uRimColor"), 0.5f, 0.5f, 0.5f)
        GLES30.glUniform1f(u(body, "uRoughness"), 0.52f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTex)
        GLES30.glUniform1i(u(body, "uDfg"), 0)
        val m = bodyMesh(look.shape)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, m.vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.ibo)
        for (a in 0..2) GLES30.glEnableVertexAttribArray(a)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE * 4, 0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE * 4, 12)
        GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, STRIDE * 4, 24)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, m.count, GLES30.GL_UNSIGNED_SHORT, 0)
        for (a in 1..2) GLES30.glDisableVertexAttribArray(a)

        // The face: two eyes, open or smiling shut.
        GLES30.glUseProgram(flat)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        val face = FloatArray(16)
        Matrix.translateM(face, 0, pivot, 0, f.faceX, f.faceY, HALF + 0.003f)
        Matrix.scaleM(face, 0, 1f, f.lids, 1f)
        val eye = flatMesh("e:${look.eyes}") { eyeGeometry(look.eyes) }
        val smile = flatMesh("smile") { smileGeometry() }
        val ec = srgb(EYE)
        val gap = 12 * f.eyeScale * U
        for (i in 0..1) {
            if (f.open) {
                Matrix.translateM(model, 0, face, 0, f.eyeX[i], f.eyeY[i], 0f)
                Matrix.rotateM(model, 0, f.eyeRot[i] / DEG, 0f, 0f, 1f)
                Matrix.scaleM(model, 0, f.eyeSX[i], f.eyeSY[i], 1f)
                drawFlat(eye, ec[0], ec[1], ec[2], 1f)
            }
            if (f.shut) {
                Matrix.translateM(model, 0, face, 0, (if (i == 0) -1 else 1) * gap, 0f, 0f)
                Matrix.scaleM(model, 0, f.eyeScale, f.eyeScale, 1f)
                drawFlat(smile, ec[0], ec[1], ec[2], 1f)
            }
        }

        // Resolve the samples and read the pixels back.
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, msFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, outFbo)
        GLES30.glBlitFramebuffer(0, 0, px, px, 0, 0, px, px, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, outFbo)
        out.clear()
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, px, px, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, out)
        out.rewind()
        return drainErrors()
    }

    private fun srgb(argb: Long) = floatArrayOf(((argb shr 16) and 0xff) / 255f, ((argb shr 8) and 0xff) / 255f, (argb and 0xff) / 255f)

    private fun drawFlat(g: Gpu, r: Float, gg: Float, b: Float, a: Float, shadow: Boolean = false) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES30.glUniformMatrix4fv(u(flat, "uMvp"), 1, false, mvp, 0)
        GLES30.glUniform4f(u(flat, "uColor"), r, gg, b, a)
        GLES30.glUniform1f(u(flat, "uShadow"), if (shadow) 1f else 0f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, g.vbo)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, g.ibo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, g.count, GLES30.GL_UNSIGNED_SHORT, 0)
    }
}
