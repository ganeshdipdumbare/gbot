package dev.gbot.app.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The OAuth side of remote MCP servers, on the phone: discovery, dynamic
 * client registration, PKCE and token refresh. The same flow as the
 * server's (server/internal/mcp/oauth.go), so signing in to an app is one
 * tap and needs no provider-specific keys.
 */
object McpOAuth {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()
    private const val MAX_METADATA_BYTES = 1L * 1024 * 1024

    /** What a server's authorization metadata tells us. */
    @Serializable
    data class Config(
        val resource: String = "",
        val authorizationEndpoint: String = "",
        val tokenEndpoint: String = "",
        val registrationEndpoint: String = "",
        val scopes: List<String> = emptyList(),
    )

    /** The registration this phone got from a provider. */
    @Serializable
    data class Client(val id: String, val secret: String = "")

    /** Kept with the integration: the refresh grant needs the client and endpoint that issued them. */
    @Serializable
    data class Tokens(
        val access: String,
        val refresh: String = "",
        val expiresAt: Long = 0,
        val clientId: String = "",
        val clientSecret: String = "",
        val tokenEndpoint: String = "",
        val resource: String = "",
        val scopes: List<String> = emptyList(),
    ) {
        /** A minute of room, so a call never starts with a token that dies mid-flight. */
        fun expired(now: Long = System.currentTimeMillis() / 1000): Boolean = expiresAt > 0 && now + 60 >= expiresAt
    }

    class OAuthException(message: String) : Exception(message)

    private fun getJson(url: String): JsonObject? = runCatching {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { res ->
            if (res.code != 200) null else json.parseToJsonElement(res.body.cappedString(MAX_METADATA_BYTES)).jsonObject
        }
    }.getOrNull()

    private fun JsonObject.str(k: String): String = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.strings(k: String): List<String> =
        (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }

    /**
     * Read the resource and authorization metadata. Falls back to the
     * server's own origin for providers that publish authorization metadata
     * without a protected-resource document.
     */
    fun discover(serverUrl: String): Config {
        val u = serverUrl.toHttpUrlOrNull() ?: throw OAuthException("That doesn't look like a server address.")
        val metaUrl = probeMetadataUrl(serverUrl) ?: wellKnown(serverUrl, "oauth-protected-resource")
        val doc = getJson(metaUrl)
        var servers = doc?.strings("authorization_servers").orEmpty()
        if (servers.isEmpty()) servers = listOf("${u.scheme}://${u.host}${if (u.port != defaultPort(u.scheme)) ":${u.port}" else ""}")
        val resource = doc?.str("resource")?.ifEmpty { null } ?: serverUrl
        val scopes = doc?.strings("scopes_supported").orEmpty()
        for (issuer in servers.distinct()) {
            val cfg = authMetadata(issuer) ?: continue
            return cfg.copy(resource = resource, scopes = scopes.ifEmpty { cfg.scopes })
        }
        throw OAuthException("This app didn't offer a sign-in. Add it with a token instead.")
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    /** The exact place of the resource document, from the server's WWW-Authenticate challenge. */
    private fun probeMetadataUrl(serverUrl: String): String? = runCatching {
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"gbot","version":"3"}}}"""
        client.newCall(
            Request.Builder().url(serverUrl)
                .header("Accept", "application/json, text/event-stream")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { res -> headerValue(res.header("WWW-Authenticate").orEmpty(), "resource_metadata") }
    }.getOrNull()?.ifEmpty { null }

    /** One quoted or bare parameter from a challenge header. */
    fun headerValue(h: String, key: String): String {
        val i = h.indexOf("$key=")
        if (i < 0) return ""
        var v = h.substring(i + key.length + 1).trim()
        if (v.startsWith("\"")) {
            v = v.substring(1)
            val j = v.indexOf('"')
            return if (j >= 0) v.substring(0, j) else v
        }
        val j = v.indexOfAny(charArrayOf(',', ' '))
        return if (j >= 0) v.substring(0, j) else v
    }

    /** The well-known segment inserted before the path. */
    fun wellKnown(url: String, name: String): String {
        val u = url.toHttpUrlOrNull() ?: return url
        val origin = url.substring(0, url.indexOf(u.host) + u.host.length) + (if (u.port != defaultPort(u.scheme)) ":${u.port}" else "")
        return "$origin/.well-known/$name${u.encodedPath.trimEnd('/')}"
    }

    /** Both RFC 8414 placements: issuer plus the well-known path, and the segment before an issuer path. */
    private fun authMetadata(issuer: String): Config? {
        val candidates = listOf(
            issuer.trimEnd('/') + "/.well-known/oauth-authorization-server",
            wellKnown(issuer, "oauth-authorization-server"),
            issuer.trimEnd('/') + "/.well-known/openid-configuration",
        ).distinct()
        for (c in candidates) {
            val doc = getJson(c) ?: continue
            val auth = doc.str("authorization_endpoint")
            val token = doc.str("token_endpoint")
            // Codes and tokens travel to these addresses, so a metadata document
            // naming a plain-http endpoint is refused, as the server refuses it.
            if (auth.isEmpty() || token.isEmpty() || !safeSignInEndpoint(auth) || !safeSignInEndpoint(token)) continue
            val reg = doc.str("registration_endpoint").takeIf { safeSignInEndpoint(it) }.orEmpty()
            return Config(
                authorizationEndpoint = auth, tokenEndpoint = token,
                registrationEndpoint = reg, scopes = doc.strings("scopes_supported"),
            )
        }
        return null
    }

    /**
     * The server accepts sign-in endpoints only over https, or plain http to
     * this machine. The phone applies the same rule, since it sends codes and
     * tokens to these addresses.
     */
    fun safeSignInEndpoint(raw: String): Boolean {
        val u = raw.toHttpUrlOrNull() ?: return false
        val local = u.host == "localhost" || u.host == "127.0.0.1" || u.host == "::1"
        return u.scheme == "https" || (u.scheme == "http" && local)
    }

    /** Register this phone as a client for [redirect]. Apps without dynamic registration can't use this. */
    fun register(cfg: Config, redirect: String): Client {
        if (cfg.registrationEndpoint.isEmpty() || !safeSignInEndpoint(cfg.registrationEndpoint)) {
            throw OAuthException("This app needs a pre-registered client. Add it with a token instead.")
        }
        val body = buildJsonObject {
            put("client_name", "gbot")
            putJsonArray("redirect_uris") { add(kotlinx.serialization.json.JsonPrimitive(redirect)) }
            putJsonArray("grant_types") { add(kotlinx.serialization.json.JsonPrimitive("authorization_code")); add(kotlinx.serialization.json.JsonPrimitive("refresh_token")) }
            putJsonArray("response_types") { add(kotlinx.serialization.json.JsonPrimitive("code")) }
            put("token_endpoint_auth_method", "none")
            put("application_type", if (redirect.startsWith("http")) "web" else "native")
        }
        client.newCall(
            Request.Builder().url(cfg.registrationEndpoint)
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { res ->
            val text = res.body.cappedString(MAX_METADATA_BYTES)
            if (res.code / 100 != 2) throw OAuthException("The app refused to register gbot (${res.code}): ${text.take(200)}")
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw OAuthException("The app's registration made no sense.")
            val id = o.str("client_id").ifEmpty { throw OAuthException("The app returned no client id.") }
            return Client(id, o.str("client_secret"))
        }
    }

    /** Where the person signs in. The resource binds the token to this one server (RFC 8707). */
    fun authUrl(cfg: Config, clientId: String, redirect: String, state: String, verifier: String): String {
        if (!safeSignInEndpoint(cfg.authorizationEndpoint)) throw OAuthException("The app's sign-in address must use https.")
        val b = cfg.authorizationEndpoint.toHttpUrlOrNull()?.newBuilder() ?: throw OAuthException("The app's sign-in address is broken.")
        b.addQueryParameter("response_type", "code")
        b.addQueryParameter("client_id", clientId)
        b.addQueryParameter("redirect_uri", redirect)
        b.addQueryParameter("state", state)
        b.addQueryParameter("code_challenge", challenge(verifier))
        b.addQueryParameter("code_challenge_method", "S256")
        if (cfg.resource.isNotEmpty()) b.addQueryParameter("resource", cfg.resource)
        if (cfg.scopes.isNotEmpty()) b.addQueryParameter("scope", cfg.scopes.joinToString(" "))
        return b.build().toString()
    }

    private fun tokenRequest(endpoint: String, form: Map<String, String>): Tokens {
        if (!safeSignInEndpoint(endpoint)) throw OAuthException("The app's token address must use https.")
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        client.newCall(Request.Builder().url(endpoint).header("Accept", "application/json").post(body).build()).execute().use { res ->
            val text = res.body.cappedString(MAX_METADATA_BYTES)
            val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: throw OAuthException("The app's token answer made no sense (${res.code}).")
            o.str("error").takeIf { it.isNotEmpty() }?.let { throw OAuthException(o.str("error_description").ifEmpty { it }) }
            val access = o.str("access_token").ifEmpty { throw OAuthException("The app returned no access token.") }
            val expiresIn = (o["expires_in"] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: 0
            return Tokens(
                access = access, refresh = o.str("refresh_token"),
                expiresAt = if (expiresIn > 0) System.currentTimeMillis() / 1000 + expiresIn else 0,
            )
        }
    }

    /** Turn the callback code into tokens. */
    fun exchange(cfg: Config, c: Client, code: String, verifier: String, redirect: String): Tokens {
        val form = buildMap {
            put("grant_type", "authorization_code")
            put("code", code)
            put("redirect_uri", redirect)
            put("client_id", c.id)
            put("code_verifier", verifier)
            if (c.secret.isNotEmpty()) put("client_secret", c.secret)
            if (cfg.resource.isNotEmpty()) put("resource", cfg.resource)
        }
        return tokenRequest(cfg.tokenEndpoint, form).copy(
            clientId = c.id, clientSecret = c.secret, tokenEndpoint = cfg.tokenEndpoint, resource = cfg.resource, scopes = cfg.scopes,
        )
    }

    /** Renew an access token. Without a refresh token the person has to sign in again. */
    fun refresh(t: Tokens): Tokens {
        if (t.refresh.isEmpty()) throw OAuthException("The sign-in expired. Connect the app again.")
        val form = buildMap {
            put("grant_type", "refresh_token")
            put("refresh_token", t.refresh)
            put("client_id", t.clientId)
            if (t.clientSecret.isNotEmpty()) put("client_secret", t.clientSecret)
            if (t.resource.isNotEmpty()) put("resource", t.resource)
        }
        val next = tokenRequest(t.tokenEndpoint, form)
        return next.copy(
            refresh = next.refresh.ifEmpty { t.refresh },
            expiresAt = if (next.expiresAt == 0L) t.expiresAt else next.expiresAt,
            clientId = t.clientId, clientSecret = t.clientSecret, tokenEndpoint = t.tokenEndpoint, resource = t.resource, scopes = t.scopes,
        )
    }

    /** The S256 PKCE challenge. */
    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    /** A URL-safe verifier or state. */
    fun random(n: Int = 32): String = ByteArray(n).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun encode(t: Tokens): String = json.encodeToString(Tokens.serializer(), t)
    fun decode(s: String?): Tokens? = s?.let { runCatching { json.decodeFromString(Tokens.serializer(), it) }.getOrNull() }
}
