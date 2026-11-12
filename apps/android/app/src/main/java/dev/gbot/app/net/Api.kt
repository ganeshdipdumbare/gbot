package dev.gbot.app.net

import kotlinx.serialization.json.JsonObject

/** Everything the app asks of a brain: your server, or one running on this phone. */
interface Api {
    // Account
    suspend fun setup(): Setup
    suspend fun signup(email: String, password: String, name: String): User
    suspend fun login(email: String, password: String): User
    suspend fun logout()
    suspend fun me(): User
    suspend fun updateMe(defaultModel: String? = null, approvalMode: String? = null): User
    suspend fun models(): ModelsResp
    suspend fun rules(): RulesResp
    suspend fun setRule(tool: String, rule: String): RulesResp

    // Providers
    suspend fun providers(): ProvidersResp
    suspend fun createProvider(b: JsonObject): Provider
    suspend fun updateProvider(id: String, b: JsonObject): Provider
    suspend fun deleteProvider(id: String)
    suspend fun refreshProvider(id: String): Provider
    suspend fun discoverProvider(b: JsonObject): DiscoverResp

    // Bots and chat
    suspend fun bots(): BotsResp
    suspend fun createBot(name: String, title: String, look: Look): Bot
    suspend fun updateBot(id: String, b: JsonObject): Bot
    suspend fun archiveBot(id: String)
    suspend fun messages(id: String, before: String? = null): MessagesResp
    suspend fun send(id: String, text: String): Message
    suspend fun stop(id: String)
    suspend fun queue(id: String): QueueResp
    suspend fun groupQueue(id: String): QueueResp
    suspend fun queueAction(qid: String, action: String): QueueResp
    suspend fun clearQueue(id: String): QueueResp
    /** Delete this chat with a bot and start it fresh; [memory] also forgets what the bot learned. */
    suspend fun clearChat(id: String, memory: Boolean)
    suspend fun clearGroupQueue(id: String): QueueResp
    suspend fun answer(cardId: String, decision: String, text: String? = null, always: Boolean? = null): Message

    // Computer
    suspend fun computer(id: String): ComputerStatus
    suspend fun computerAction(id: String, action: String, body: JsonObject = JsonObject(emptyMap())): ComputerStatus
    fun screenPath(id: String): String
    fun streamPath(id: String): String

    // Sharing and teams
    suspend fun sharing(id: String): Sharing
    suspend fun setVisibility(id: String, v: String): Sharing
    suspend fun addBotMember(id: String, email: String, role: String): Sharing
    suspend fun removeBotMember(id: String, userId: String): Sharing
    suspend fun spaces(): SpacesResp
    suspend fun createSpace(name: String): Space
    suspend fun selectSpace(id: String): SpaceIdResp
    suspend fun spaceMembers(id: String): MembersResp
    suspend fun addSpaceMember(id: String, email: String): MembersResp
    suspend fun removeSpaceMember(id: String, userId: String): MembersResp

    // Slack
    suspend fun slack(): SlackResp
    suspend fun connectSlack(b: JsonObject): SlackInstall
    suspend fun disconnectSlack(id: String)
    suspend fun linkSlack(id: String, b: JsonObject): SlackLink
    suspend fun unlinkSlack(id: String, channelId: String)

    // Routines
    suspend fun routines(id: String): RoutinesResp
    suspend fun createRoutine(id: String, name: String, prompt: String, kind: String, schedule: String?): Routine
    suspend fun updateRoutine(id: String, rid: String, active: Boolean): Routine
    suspend fun deleteRoutine(id: String, rid: String)
    suspend fun runRoutine(id: String, rid: String)

    // Integrations, vault, skills
    suspend fun mcp(): McpResp
    suspend fun startMcpOAuth(name: String, url: String): UrlResp
    suspend fun addMcp(name: String, url: String, token: String?): McpServer
    suspend fun toggleMcp(id: String, enabled: Boolean): McpServer
    suspend fun deleteMcp(id: String)
    suspend fun vault(): VaultResp
    suspend fun addVault(name: String, value: String, origins: List<String>): VaultSecret
    suspend fun updateVault(id: String, name: String, origins: List<String>, value: String?): VaultSecret
    suspend fun deleteVault(id: String)
    suspend fun skills(): SkillsResp
    suspend fun saveSkill(name: String, description: String, body: String): Skill
    suspend fun deleteSkill(id: String)

    // Groups
    suspend fun groups(): GroupsResp
    suspend fun createGroup(name: String, botIds: List<String>): Group
    suspend fun archiveGroup(id: String)
    suspend fun groupMessages(id: String, before: String? = null): MessagesResp
    suspend fun sendGroup(id: String, text: String): Message
    suspend fun stopGroup(id: String)

    // Voice
    suspend fun voice(): VoiceInfo
    suspend fun transcribe(audio: ByteArray, mime: String): String
    suspend fun speak(text: String, voice: String?): ByteArray
}
