package dev.gbot.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiError(val status: Int, message: String) : IOException(message)

val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = false }

enum class SetupProbe { Ok, NeedsBbSignIn, Unreachable, NotGbot }

/**
 * The connection to your gbot server: its address, the session cookie
 * (kept across restarts), and the CSRF header every write needs.
 */
class Http(context: Context) {
    private val prefs = context.getSharedPreferences("gbot", Context.MODE_PRIVATE)
    private val cookies = PersistentCookies(context)

    /** e.g. "http://192.168.1.2:8080", or null before setup. */
    var server: String?
        get() = prefs.getString("server", null)
        set(v) { prefs.edit().putString("server", v).apply() }

    private val _signedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val signedOut = _signedOut.asSharedFlow()

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    /** For long-lived streams: no read timeout. */
    val streaming: OkHttpClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    fun url(path: String): String = (server ?: "http://localhost") + path

    fun request(path: String): Request.Builder = Request.Builder().url(url(path)).header("User-Agent", "gbot-android/3")

    suspend fun call(method: String, path: String, body: JsonElement? = null): String = withContext(Dispatchers.IO) {
        val b = request(path)
        if (method == "GET") b.get() else {
            b.header("X-GBot", "1")
            b.method(method, (body?.toString() ?: "{}").toRequestBody(JSON))
        }
        client.newCall(b.build()).execute().use { res -> read(res, path) }
    }

    suspend fun raw(method: String, path: String, body: RequestBody): Response = withContext(Dispatchers.IO) {
        client.newCall(request(path).header("X-GBot", "1").method(method, body).build()).execute()
    }

    fun read(res: Response, path: String): String {
        val text = res.body.string()
        if (!res.isSuccessful) {
            if (res.code == 401 && !path.startsWith("/api/auth/") && path != "/api/me") _signedOut.tryEmit(Unit)
            val msg = runCatching { json.parseToJsonElement(text).jsonObject.s("error") }.getOrNull()
            throw ApiError(res.code, msg ?: friendlyHttpError(res.code, text))
        }
        if (text.isNotEmpty() && text[0] != '{' && text[0] != '[') {
            throw ApiError(res.code, friendlyHttpError(res.code, text))
        }
        return text
    }

    /** Copy cookies from a WebView CookieManager header into OkHttp (for bb connect). */
    fun importBrowserCookies(pageUrl: String, header: String?) {
        if (header.isNullOrBlank()) return
        val url = pageUrl.toHttpUrlOrNull() ?: return
        val parsed = header.split(';').mapNotNull { part ->
            val bit = part.trim()
            if (bit.isEmpty() || !bit.contains('=')) return@mapNotNull null
            Cookie.parse(url, "$bit; Path=/")
        }
        if (parsed.isNotEmpty()) cookies.merge(url, parsed)
    }

    /** Drop only the gbot session; keep bb connect cookies so the tunnel still works. */
    fun clearSession() = cookies.removeNamed("gbot_session")

    suspend fun probeSetup(base: String): SetupProbe = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder().url("$base/api/setup").header("User-Agent", "gbot-android/3").get().build(),
            ).execute().use { r ->
                val body = r.body.string()
                when {
                    looksLikeBbGate(body) -> SetupProbe.NeedsBbSignIn
                    !r.isSuccessful -> if (r.code == 401 || r.code == 403) SetupProbe.NeedsBbSignIn else SetupProbe.Unreachable
                    body.trimStart().startsWith("{") -> SetupProbe.Ok
                    else -> SetupProbe.NotGbot
                }
            }
        }.getOrDefault(SetupProbe.Unreachable)
    }

    private fun friendlyHttpError(code: Int, body: String): String {
        if (looksLikeBbGate(body)) {
            return "Sign in to bb first so this phone can use your getbb.app link"
        }
        return "Something went wrong ($code)"
    }

    companion object {
        val JSON = "application/json".toMediaType()
        fun isBbHost(host: String?): Boolean {
            val h = host.orEmpty().lowercase()
            return h.endsWith("getbb.app") || h.endsWith("bb.app") || h.endsWith("vibecodethis.site")
        }
        fun looksLikeBbGate(body: String): Boolean {
            val lower = body.lowercase()
            return lower.contains("bb connect") || lower.contains("sign in at getbb.app") ||
                (lower.contains("<!doctype html") && lower.contains("getbb.app"))
        }
    }
}

// Decoding belongs off the main thread too: a page of messages is easily
// hundreds of kilobytes, and parsing it in a frame is a visible freeze.
suspend inline fun <reified T> Http.get(path: String): T =
    withContext(Dispatchers.IO) { json.decodeFromString(call("GET", path)) }

suspend inline fun <reified T> Http.send(method: String, path: String, body: JsonElement? = null): T =
    withContext(Dispatchers.IO) { json.decodeFromString(call(method, path, body ?: JsonObject(emptyMap()))) }

/** Cookies survive restarts, so you stay signed in. */
private class PersistentCookies(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("gbot.cookies", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, List<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = merge(url, cookies)

    @Synchronized
    fun merge(url: HttpUrl, incoming: List<Cookie>) {
        val host = url.host
        val now = System.currentTimeMillis()
        val kept = load(host).filter { old -> incoming.none { it.name == old.name } } +
            incoming.filter { it.expiresAt > now }
        cache[host] = kept
        prefs.edit().putStringSet(host, kept.map { it.toString() }.toSet()).apply()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        // BB shares need cookies for both the share host and the apex getbb.app account.
        val hosts = linkedSetOf(url.host)
        if (Http.isBbHost(url.host)) {
            val parts = url.host.split('.')
            if (parts.size >= 2) hosts += parts.takeLast(2).joinToString(".")
            hosts += "getbb.app"
        }
        return hosts.flatMap { load(it) }.filter { it.expiresAt > now && it.matches(url) }
            .distinctBy { it.name + it.domain + it.path }
    }

    private fun load(host: String): List<Cookie> = cache.getOrPut(host) {
        val base = "https://$host/".toHttpUrlOrNull() ?: "http://$host/".toHttpUrlOrNull() ?: return@getOrPut emptyList()
        prefs.getStringSet(host, emptySet())!!.mapNotNull { Cookie.parse(base, it) }
    }

    @Synchronized
    fun removeNamed(name: String) {
        val next = cache.mapValues { (_, list) -> list.filter { it.name != name } }
        cache.clear(); cache.putAll(next)
        val ed = prefs.edit()
        for (host in prefs.all.keys) {
            val kept = prefs.getStringSet(host, emptySet())!!.mapNotNull { raw ->
                val base = "https://$host/".toHttpUrlOrNull() ?: return@mapNotNull raw
                val c = Cookie.parse(base, raw) ?: return@mapNotNull raw
                if (c.name == name) null else raw
            }.toSet()
            if (kept.isEmpty()) ed.remove(host) else ed.putStringSet(host, kept)
        }
        ed.apply()
    }

    @Synchronized
    fun clear() { cache.clear(); prefs.edit().clear().apply() }
}
