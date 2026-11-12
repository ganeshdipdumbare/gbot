package dev.gbot.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// The server's JSON, mirroring apps/web/src/lib/api.ts. Every field has a
// default so an older or newer server never breaks decoding.

@Serializable
data class User(
    val id: String = "", val email: String = "", val name: String = "",
    val defaultModel: String = "", val approvalMode: String = "auto", val spaceId: String = "",
)

@Serializable
data class Message(
    val id: String = "", val botId: String? = null, val groupId: String? = null,
    val role: String = "bot", // user | bot | event | card
    val content: String = "", val data: JsonObject? = null, val runId: String? = null,
    val createdAt: String = "",
    // Client-only state.
    val pending: Boolean = false, val streaming: Boolean = false,
) {
    fun str(key: String): String? = (data?.get(key) as? JsonPrimitive)?.contentOrNull
    fun flag(key: String): Boolean = (data?.get(key) as? JsonPrimitive)?.booleanOrNull == true
    val kind: String? get() = str("kind")
}

@Serializable
data class Look(val shape: String? = null, val hue: String? = null, val eyes: String? = null, val seed: String? = null)

@Serializable
data class Bot(
    val id: String = "", val spaceId: String = "", val ownerId: String = "", val name: String = "",
    val title: String = "", val instructions: String = "", val model: String = "", val look: Look = Look(),
    val pinned: Boolean = false, val computerNetwork: Boolean = false, val allowedHosts: List<String> = emptyList(),
    val voice: String = "", val readAloud: Boolean = false, val visibility: String = "private",
    val role: String? = null, val createdAt: String = "", val lastMessage: Message? = null,
) {
    val canManage: Boolean get() = role == null || role == "owner" || role == "manager"
    val isOwner: Boolean get() = role == null || role == "owner"
}

@Serializable data class Member(val userId: String = "", val email: String = "", val name: String = "", val role: String = "")
@Serializable data class Sharing(val visibility: String = "private", val members: List<Member> = emptyList(), val spaceMembers: List<Member> = emptyList())
@Serializable data class Space(val id: String = "", val name: String = "", val role: String = "member")

@Serializable
data class Group(
    val id: String = "", val spaceId: String = "", val userId: String = "", val name: String = "",
    val botIds: List<String> = emptyList(), val createdAt: String = "", val lastMessage: Message? = null,
)

@Serializable
data class Presence(val state: String = "idle", val detail: String? = null, val queued: Int? = null, val live: Boolean? = null)

@Serializable
data class Model(
    val id: String = "", val label: String = "", val provider: String = "", val local: Boolean = false,
    val tools: Boolean = false, val caps: List<String>? = null, val best: Boolean = false,
)

@Serializable data class QueueItem(val id: String = "", val prompt: String = "", val status: String = "queued", val error: String? = null, val createdAt: String = "")
@Serializable data class CodexLogin(val available: Boolean = false, val allowed: Boolean = false)

@Serializable
data class Provider(
    val id: String = "", val name: String = "", val kind: String = "openai", val baseUrl: String = "",
    val hasKey: Boolean = false, val local: Boolean = false, val models: List<String> = emptyList(),
    val caps: Map<String, List<String>?>? = null, val enabled: Boolean = true, val status: String = "", val createdAt: String = "",
)

@Serializable
data class ComputerStatus(
    val state: String = "off", val controller: String = "bot", val network: Boolean = false,
    val available: Boolean = true, val backend: String = "docker",
)

@Serializable
data class Routine(
    val id: String = "", val botId: String = "", val name: String = "", val prompt: String = "",
    val kind: String = "schedule", val schedule: String? = null, val `when`: String = "", val active: Boolean = true,
    val nextRunAt: String? = null, val lastRunAt: String? = null, val webhookUrl: String? = null, val secret: String? = null,
)

@Serializable data class ToolRule(val tool: String = "", val rule: String = "allow")
@Serializable data class McpServer(val id: String = "", val name: String = "", val url: String = "", val hasAuth: Boolean = false, val oauth: Boolean = false, val enabled: Boolean = true, val status: String = "", val tools: List<String> = emptyList())
@Serializable data class Skill(val id: String = "", val name: String = "", val description: String = "", val body: String = "")
@Serializable data class VaultSecret(val id: String = "", val name: String = "", val origins: List<String> = emptyList(), val updatedAt: String = "")
@Serializable data class VoiceInfo(val transcribe: Boolean = false, val speak: Boolean = false, val transcribeBy: String? = null, val speakBy: String? = null)
@Serializable data class Setup(val fresh: Boolean = false, val signup: Boolean = false)

// Response wrappers.
@Serializable data class BotsResp(val bots: List<Bot> = emptyList(), val presence: Map<String, Presence> = emptyMap())
@Serializable data class GroupsResp(val groups: List<Group> = emptyList())
@Serializable data class MessagesResp(val messages: List<Message> = emptyList())
@Serializable data class QueueResp(val items: List<QueueItem> = emptyList())
@Serializable data class ModelsResp(val models: List<Model> = emptyList(), val ollamaHost: String = "")
@Serializable data class ProvidersResp(val providers: List<Provider> = emptyList(), val codex: CodexLogin? = null)
@Serializable data class DiscoverResp(val models: List<String> = emptyList(), val caps: Map<String, List<String>?>? = null)
@Serializable data class RulesResp(val rules: List<ToolRule> = emptyList())
@Serializable data class RoutinesResp(val routines: List<Routine> = emptyList())
@Serializable data class McpResp(val servers: List<McpServer> = emptyList())
@Serializable data class UrlResp(val url: String = "")
@Serializable data class VaultResp(val secrets: List<VaultSecret> = emptyList())
@Serializable data class SlackLink(val installId: String = "", val channelId: String = "", val channel: String = "", val botId: String = "", val createdAt: String = "")
@Serializable data class SlackInstall(val id: String = "", val teamId: String = "", val teamName: String = "", val createdAt: String = "", val links: List<SlackLink> = emptyList(), val status: String = "")
/** [socket]: this is the phone, which listens over Slack's Socket Mode instead of an event URL. */
@Serializable data class SlackResp(val installs: List<SlackInstall> = emptyList(), val eventUrl: String = "", val socket: Boolean = false)

@Serializable data class SkillsResp(val skills: List<Skill> = emptyList())
@Serializable data class SpacesResp(val spaces: List<Space> = emptyList(), val current: String = "")
@Serializable data class MembersResp(val members: List<Member> = emptyList())
@Serializable data class SpaceIdResp(val spaceId: String = "")
@Serializable data class TextResp(val text: String = "")
@Serializable data class PollResp(val events: List<JsonObject> = emptyList(), val next: Long = 0, val reset: Boolean = false)

fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
fun JsonObject.s(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
