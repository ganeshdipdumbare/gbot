package dev.gbot.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gbot.app.local.Mcp
import dev.gbot.app.local.McpOAuth
import dev.gbot.app.local.OAuthLoopback
import dev.gbot.app.net.McpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Collections

/** Signing in to an app on the phone, against a fake MCP server with its own OAuth. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class McpOAuthTest {
    private val server = MockWebServer()
    private val seen = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var valid = "at-1"
    @Volatile private var issued = 0

    private fun ok(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val base = server.url("/").toString().trimEnd('/')
                seen.add("${request.method} $path")
                return when {
                    path == "/mcp" && request.headers["Authorization"] != "Bearer $valid" ->
                        MockResponse.Builder().code(401)
                            .addHeader("WWW-Authenticate", """Bearer resource_metadata="$base/.well-known/oauth-protected-resource/mcp"""")
                            .build()
                    path == "/mcp" -> {
                        val body = request.body?.utf8().orEmpty()
                        val method = Json.parseToJsonElement(body).jsonObject["method"]?.jsonPrimitive?.content
                        when (method) {
                            "initialize" -> ok("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","serverInfo":{"name":"fake"}}}""")
                            "tools/list" -> ok("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search","description":"Search","inputSchema":{"type":"object"}}]}}""")
                            else -> MockResponse.Builder().code(202).build()
                        }
                    }
                    path == "/.well-known/oauth-protected-resource/mcp" ->
                        ok("""{"resource":"$base/mcp","authorization_servers":["$base/auth"],"scopes_supported":["read"]}""")
                    path == "/auth/.well-known/oauth-authorization-server" ->
                        ok("""{"authorization_endpoint":"$base/auth/authorize","token_endpoint":"$base/auth/token","registration_endpoint":"$base/auth/register"}""")
                    path == "/auth/register" -> {
                        val redirects = request.body?.utf8().orEmpty()
                        // Like some apps: web redirects only.
                        if (redirects.contains("gbot://")) MockResponse.Builder().code(400).body("""{"error":"invalid_redirect_uri"}""").build()
                        else ok("""{"client_id":"client-1"}""")
                    }
                    path == "/auth/token" -> {
                        val form = request.body?.utf8().orEmpty()
                        issued++
                        valid = "at-${issued + 1}"
                        if (form.contains("grant_type=refresh_token")) ok("""{"access_token":"$valid","expires_in":3600}""")
                        else ok("""{"access_token":"$valid","refresh_token":"rt-1","expires_in":3600}""")
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After fun tearDown() { server.close() }

    @Test
    fun `an app's sign-in is found, registered, exchanged and renewed`() {
        val mcpUrl = server.url("/mcp").toString()
        val cfg = McpOAuth.discover(mcpUrl)
        assertTrue(cfg.authorizationEndpoint.endsWith("/auth/authorize"))
        assertEquals(mcpUrl, cfg.resource)
        assertEquals(listOf("read"), cfg.scopes)

        // The app refuses gbot://, so the loopback redirect is used.
        val lb = OAuthLoopback({})
        val client = runCatching { McpOAuth.register(cfg, "gbot://oauth/callback") }.getOrElse { McpOAuth.register(cfg, lb.redirect) }
        lb.close()
        assertEquals("client-1", client.id)

        val verifier = McpOAuth.random()
        val auth = McpOAuth.authUrl(cfg, client.id, lb.redirect, "st", verifier).toHttpUrl()
        assertEquals(McpOAuth.challenge(verifier), auth.queryParameter("code_challenge"))
        assertEquals("S256", auth.queryParameter("code_challenge_method"))
        assertEquals(mcpUrl, auth.queryParameter("resource"))

        val tokens = McpOAuth.exchange(cfg, client, "code-1", verifier, lb.redirect)
        assertEquals(valid, tokens.access)
        assertEquals("rt-1", tokens.refresh)
        assertTrue(tokens.expiresAt > 0)

        // The phone's MCP client uses the token, and renews it after a 401.
        val mcp = Mcp(ApplicationProvider.getApplicationContext())
        val s = McpServer(id = "abc123", name = "Fake", url = mcpUrl, oauth = true)
        mcp.putOAuth(s.id, tokens)
        valid = "at-rotated" // the server dropped the old token
        issued = 99
        val probed = runBlocking { mcp.probe(s) }
        assertEquals("connected", probed.status)
        assertEquals(listOf("search"), probed.tools)
        assertEquals(valid, mcp.oauth(s.id)!!.access)
        assertEquals("rt-1", mcp.oauth(s.id)!!.refresh)
        assertTrue(seen.contains("POST /auth/token"))
    }

    @Test
    fun `an expired token is renewed before the call`() {
        val t = McpOAuth.Tokens(access = "old", refresh = "rt", expiresAt = System.currentTimeMillis() / 1000 + 30)
        assertTrue(t.expired())
        assertTrue(!t.copy(expiresAt = 0).expired())
    }

    @Test
    fun `a challenge header and a well-known address are read like the server does`() {
        assertEquals("https://x.dev/meta", McpOAuth.headerValue("""Bearer error="invalid_token", resource_metadata="https://x.dev/meta"""", "resource_metadata"))
        assertEquals("https://x.dev/m", McpOAuth.headerValue("Bearer resource_metadata=https://x.dev/m, scope=a", "resource_metadata"))
        assertEquals("https://mcp.x.dev/.well-known/oauth-protected-resource/mcp", McpOAuth.wellKnown("https://mcp.x.dev/mcp", "oauth-protected-resource"))
        // S256 = base64url(sha256(verifier)), checked against `openssl dgst -sha256`.
        assertEquals("MvvY_5TF_UDiV2f0p0wET9d0VJ9OIC4mNVuOCuEM6RY", McpOAuth.challenge("dBjftJeZ4CVP-mB92K1uhbF15p1bJXHgjHB0gYOsQJ8"))
    }

    @Test
    fun `the loopback listener hands the callback over and bounces back to the app`() {
        var got: Map<String, String>? = null
        val lb = OAuthLoopback({ got = it })
        lb.start()
        val res = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("${lb.redirect}?code=c%201&state=s").build()).execute()
        val page = res.body.string()
        assertTrue(page.contains("gbot://oauth/done"))
        Thread.sleep(100)
        assertEquals(mapOf("code" to "c 1", "state" to "s"), got)
    }
}
