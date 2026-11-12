package dev.gbot.app.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** Every call the web app makes, one to one. */
class RemoteApi(val http: Http) : Api {
    private fun bot(id: String) = "/api/bots/$id"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) when (v) {
            null -> {}
            is String -> put(k, v)
            is Boolean -> put(k, v)
            is Number -> put(k, v)
            is JsonElement -> put(k, v)
            is List<*> -> putJsonArray(k) { v.forEach { add(JsonPrimitive(it.toString())) } }
            else -> error("unsupported $k")
        }
    }

    // Account
    override suspend fun setup(): Setup = http.get("/api/setup")
    override suspend fun signup(email: String, password: String, name: String): User = http.send("POST", "/api/auth/signup", obj("email" to email, "password" to password, "name" to name))
    override suspend fun login(email: String, password: String): User = http.send("POST", "/api/auth/login", obj("email" to email, "password" to password))
    override suspend fun logout() { http.call("POST", "/api/auth/logout", JsonObject(emptyMap())) }
    override suspend fun me(): User = http.get("/api/me")
    override suspend fun updateMe(defaultModel: String?, approvalMode: String?): User =
        http.send("PATCH", "/api/me", obj("defaultModel" to defaultModel, "approvalMode" to approvalMode))
    override suspend fun models(): ModelsResp = http.get("/api/models")
    override suspend fun rules(): RulesResp = http.get("/api/settings/rules")
    override suspend fun setRule(tool: String, rule: String): RulesResp = http.send("PUT", "/api/settings/rules", obj("tool" to tool, "rule" to rule))

    // Providers
    override suspend fun providers(): ProvidersResp = http.get("/api/providers")
    override suspend fun createProvider(b: JsonObject): Provider = http.send("POST", "/api/providers", b)
    override suspend fun updateProvider(id: String, b: JsonObject): Provider = http.send("PATCH", "/api/providers/$id", b)
    override suspend fun deleteProvider(id: String) { http.call("DELETE", "/api/providers/$id", JsonObject(emptyMap())) }
    override suspend fun refreshProvider(id: String): Provider = http.send("POST", "/api/providers/$id/refresh")
    override suspend fun discoverProvider(b: JsonObject): DiscoverResp = http.send("POST", "/api/providers/discover", b)

    // Bots and chat
    override suspend fun bots(): BotsResp = http.get("/api/bots")
    override suspend fun createBot(name: String, title: String, look: Look): Bot =
        http.send("POST", "/api/bots", obj("name" to name, "title" to title, "look" to json.encodeToJsonElement(look)))
    override suspend fun updateBot(id: String, b: JsonObject): Bot = http.send("PATCH", bot(id), b)
    override suspend fun archiveBot(id: String) { http.call("DELETE", bot(id), JsonObject(emptyMap())) }
    override suspend fun messages(id: String, before: String?): MessagesResp = http.get("${bot(id)}/messages${before?.let { "?before=${enc(it)}" } ?: ""}")
    override suspend fun send(id: String, text: String): Message = http.send("POST", "${bot(id)}/messages", obj("text" to text))
    override suspend fun stop(id: String) { http.call("POST", "${bot(id)}/stop", JsonObject(emptyMap())) }
    override suspend fun queue(id: String): QueueResp = http.get("${bot(id)}/queue")
    override suspend fun groupQueue(id: String): QueueResp = http.get("/api/groups/$id/queue")
    override suspend fun queueAction(qid: String, action: String): QueueResp = http.send("POST", "/api/queue/$qid", obj("action" to action))
    override suspend fun clearQueue(id: String): QueueResp = http.send("POST", "${bot(id)}/queue/clear")
    override suspend fun clearChat(id: String, memory: Boolean) { http.call("DELETE", "${bot(id)}/messages" + if (memory) "?memory=1" else "") }
    override suspend fun clearGroupQueue(id: String): QueueResp = http.send("POST", "/api/groups/$id/queue/clear")
    override suspend fun answer(cardId: String, decision: String, text: String?, always: Boolean?): Message =
        http.send("POST", "/api/cards/$cardId", obj("decision" to decision, "text" to text, "always" to always))

    // Computer
    override suspend fun computer(id: String): ComputerStatus = http.get("${bot(id)}/computer")
    override suspend fun computerAction(id: String, action: String, body: JsonObject): ComputerStatus =
        http.send("POST", "${bot(id)}/computer/$action", body)
    override fun screenPath(id: String) = "${bot(id)}/computer/screen?t=${System.currentTimeMillis()}"
    override fun streamPath(id: String) = "${bot(id)}/computer/stream?t=${System.currentTimeMillis()}"

    // Sharing and teams
    override suspend fun sharing(id: String): Sharing = http.get("${bot(id)}/sharing")
    override suspend fun setVisibility(id: String, v: String): Sharing = http.send("PUT", "${bot(id)}/sharing", obj("visibility" to v))
    override suspend fun addBotMember(id: String, email: String, role: String): Sharing = http.send("POST", "${bot(id)}/sharing/members", obj("email" to email, "role" to role))
    override suspend fun removeBotMember(id: String, userId: String): Sharing = http.send("DELETE", "${bot(id)}/sharing/members/$userId")
    override suspend fun slack(): SlackResp = http.get("/api/slack")
    override suspend fun connectSlack(b: JsonObject): SlackInstall = http.send("POST", "/api/slack", b)
    override suspend fun disconnectSlack(id: String) { http.call("DELETE", "/api/slack/$id", JsonObject(emptyMap())) }
    override suspend fun linkSlack(id: String, b: JsonObject): SlackLink = http.send("PUT", "/api/slack/$id/links", b)
    override suspend fun unlinkSlack(id: String, channelId: String) { http.call("DELETE", "/api/slack/$id/links/$channelId", JsonObject(emptyMap())) }

    override suspend fun spaces(): SpacesResp = http.get("/api/spaces")
    override suspend fun createSpace(name: String): Space = http.send("POST", "/api/spaces", obj("name" to name))
    override suspend fun selectSpace(id: String): SpaceIdResp = http.send("PUT", "/api/me/space", obj("spaceId" to id))
    override suspend fun spaceMembers(id: String): MembersResp = http.get("/api/spaces/$id/members")
    override suspend fun addSpaceMember(id: String, email: String): MembersResp = http.send("POST", "/api/spaces/$id/members", obj("email" to email))
    override suspend fun removeSpaceMember(id: String, userId: String): MembersResp = http.send("DELETE", "/api/spaces/$id/members/$userId")

    // Routines
    override suspend fun routines(id: String): RoutinesResp = http.get("${bot(id)}/routines")
    override suspend fun createRoutine(id: String, name: String, prompt: String, kind: String, schedule: String?): Routine =
        http.send("POST", "${bot(id)}/routines", obj("name" to name, "prompt" to prompt, "kind" to kind, "schedule" to schedule))
    override suspend fun updateRoutine(id: String, rid: String, active: Boolean): Routine = http.send("PATCH", "${bot(id)}/routines/$rid", obj("active" to active))
    override suspend fun deleteRoutine(id: String, rid: String) { http.call("DELETE", "${bot(id)}/routines/$rid", JsonObject(emptyMap())) }
    override suspend fun runRoutine(id: String, rid: String) { http.call("POST", "${bot(id)}/routines/$rid/run", JsonObject(emptyMap())) }

    // Integrations, vault, skills
    override suspend fun mcp(): McpResp = http.get("/api/mcp")
    override suspend fun startMcpOAuth(name: String, url: String): UrlResp = http.send("POST", "/api/mcp/oauth/start", obj("name" to name, "url" to url))
    override suspend fun addMcp(name: String, url: String, token: String?): McpServer = http.send("POST", "/api/mcp", obj("name" to name, "url" to url, "token" to token))
    override suspend fun toggleMcp(id: String, enabled: Boolean): McpServer = http.send("PATCH", "/api/mcp/$id", obj("enabled" to enabled))
    override suspend fun deleteMcp(id: String) { http.call("DELETE", "/api/mcp/$id", JsonObject(emptyMap())) }
    override suspend fun vault(): VaultResp = http.get("/api/vault")
    override suspend fun addVault(name: String, value: String, origins: List<String>): VaultSecret = http.send("POST", "/api/vault", obj("name" to name, "value" to value, "origins" to origins))
    override suspend fun updateVault(id: String, name: String, origins: List<String>, value: String?): VaultSecret =
        http.send("PATCH", "/api/vault/$id", obj("name" to name, "origins" to origins, "value" to value))
    override suspend fun deleteVault(id: String) { http.call("DELETE", "/api/vault/$id", JsonObject(emptyMap())) }
    override suspend fun skills(): SkillsResp = http.get("/api/skills")
    override suspend fun saveSkill(name: String, description: String, body: String): Skill = http.send("POST", "/api/skills", obj("name" to name, "description" to description, "body" to body))
    override suspend fun deleteSkill(id: String) { http.call("DELETE", "/api/skills/$id", JsonObject(emptyMap())) }

    // Groups
    override suspend fun groups(): GroupsResp = http.get("/api/groups")
    override suspend fun createGroup(name: String, botIds: List<String>): Group = http.send("POST", "/api/groups", obj("name" to name, "botIds" to botIds))
    override suspend fun archiveGroup(id: String) { http.call("DELETE", "/api/groups/$id", JsonObject(emptyMap())) }
    override suspend fun groupMessages(id: String, before: String?): MessagesResp = http.get("/api/groups/$id/messages${before?.let { "?before=${enc(it)}" } ?: ""}")
    override suspend fun sendGroup(id: String, text: String): Message = http.send("POST", "/api/groups/$id/messages", obj("text" to text))
    override suspend fun stopGroup(id: String) { http.call("POST", "/api/groups/$id/stop", JsonObject(emptyMap())) }

    // Voice
    override suspend fun voice(): VoiceInfo = http.get("/api/voice")
    override suspend fun transcribe(audio: ByteArray, mime: String): String {
        val res = http.raw("POST", "/api/voice/transcribe", audio.toRequestBody(mime.toMediaType()))
        return res.use { json.decodeFromString<TextResp>(http.read(it, "/api/voice/transcribe")).text }
    }
    override suspend fun speak(text: String, voice: String?): ByteArray {
        val res = http.raw("POST", "/api/voice/speak", obj("text" to text, "voice" to voice).toString().toRequestBody(Http.JSON))
        return res.use { if (!it.isSuccessful) throw ApiError(it.code, "Couldn't read aloud"); it.body.bytes() }
    }
}
