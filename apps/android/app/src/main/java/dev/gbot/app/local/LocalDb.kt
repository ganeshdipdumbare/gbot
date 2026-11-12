package dev.gbot.app.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Group
import dev.gbot.app.net.McpServer
import dev.gbot.app.net.Message
import dev.gbot.app.net.Provider
import dev.gbot.app.net.Routine
import dev.gbot.app.net.Skill
import dev.gbot.app.net.VaultSecret
import dev.gbot.app.net.json
import java.time.Instant
import java.util.UUID

/**
 * Everything a bot keeps on this phone: the roster, chats and groups,
 * routines, skills, integrations, saved secrets and providers. Rows hold the
 * same JSON the server sends, so the rest of the app needs no second model.
 */
class LocalDb(context: Context) : SQLiteOpenHelper(context, "gbot-local.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bots (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE msgs (id TEXT PRIMARY KEY, chat TEXT NOT NULL, at TEXT NOT NULL, json TEXT NOT NULL)")
        db.execSQL("CREATE INDEX msgs_chat ON msgs (chat, at)")
        db.execSQL("CREATE TABLE notes (id TEXT PRIMARY KEY, bot TEXT NOT NULL, at TEXT NOT NULL, text TEXT NOT NULL)")
        db.execSQL("CREATE INDEX notes_bot ON notes (bot, at)")
        db.execSQL("CREATE TABLE providers (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE groups (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE routines (id TEXT PRIMARY KEY, bot TEXT NOT NULL, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE skills (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE mcp (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE vault (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            // Chat rows used the bot id; groups add "g:" keys. Same column.
            db.execSQL("ALTER TABLE msgs RENAME TO msgs_old")
            db.execSQL("CREATE TABLE msgs (id TEXT PRIMARY KEY, chat TEXT NOT NULL, at TEXT NOT NULL, json TEXT NOT NULL)")
            db.execSQL("INSERT INTO msgs (id, chat, at, json) SELECT id, bot, at, json FROM msgs_old")
            db.execSQL("DROP TABLE msgs_old")
            db.execSQL("CREATE INDEX msgs_chat ON msgs (chat, at)")
            db.execSQL("CREATE TABLE groups (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE routines (id TEXT PRIMARY KEY, bot TEXT NOT NULL, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE skills (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE mcp (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
            db.execSQL("CREATE TABLE vault (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        }
    }

    // Bots

    @Synchronized fun bots(): List<Bot> = readableDatabase.rawQuery("SELECT json FROM bots ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { json.decodeFromString(Bot.serializer(), c.getString(0)) }.getOrNull()?.let(::add) }
    }

    @Synchronized fun bot(id: String): Bot? = readableDatabase.rawQuery("SELECT json FROM bots WHERE id = ?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) runCatching { json.decodeFromString(Bot.serializer(), c.getString(0)) }.getOrNull() else null
    }

    @Synchronized fun putBot(b: Bot) {
        writableDatabase.insertWithOnConflict("bots", null, ContentValues().apply {
            put("id", b.id)
            put("json", json.encodeToString(Bot.serializer(), b))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun removeBot(id: String) {
        writableDatabase.delete("bots", "id = ?", arrayOf(id))
        writableDatabase.delete("msgs", "chat = ?", arrayOf(id))
        writableDatabase.delete("notes", "bot = ?", arrayOf(id))
        writableDatabase.delete("routines", "bot = ?", arrayOf(id))
        groups().filter { id in it.botIds }.forEach { removeGroup(it.id) }
    }

    // Groups

    @Synchronized fun groups(): List<Group> = readableDatabase.rawQuery("SELECT json FROM groups ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { json.decodeFromString(Group.serializer(), c.getString(0)) }.getOrNull()?.let(::add) }
    }

    @Synchronized fun group(id: String): Group? = readableDatabase.rawQuery("SELECT json FROM groups WHERE id = ?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) runCatching { json.decodeFromString(Group.serializer(), c.getString(0)) }.getOrNull() else null
    }

    @Synchronized fun putGroup(g: Group) {
        writableDatabase.insertWithOnConflict("groups", null, ContentValues().apply {
            put("id", g.id)
            put("json", json.encodeToString(Group.serializer(), g))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun removeGroup(id: String) {
        writableDatabase.delete("groups", "id = ?", arrayOf(id))
        writableDatabase.delete("msgs", "chat = ?", arrayOf("g:$id"))
    }

    // Messages

    private fun chatOf(m: Message) = m.groupId?.let { "g:$it" } ?: m.botId.orEmpty()

    @Synchronized fun putMessage(m: Message) {
        writableDatabase.insertWithOnConflict("msgs", null, ContentValues().apply {
            put("id", m.id)
            put("chat", chatOf(m))
            put("at", m.createdAt)
            put("json", json.encodeToString(Message.serializer(), m.copy(pending = false, streaming = false)))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun patchMessage(m: Message) = putMessage(m)

    /** The newest [limit] messages for a chat key, oldest first, or the page before [before]. */
    @Synchronized fun messages(chat: String, before: String? = null, limit: Int = 100): List<Message> {
        val (where, args) = if (before == null) "chat = ?" to arrayOf(chat) else "chat = ? AND at < ?" to arrayOf(chat, before)
        val out = readableDatabase.rawQuery("SELECT json FROM msgs WHERE $where ORDER BY at DESC, rowid DESC LIMIT $limit", args).use { c ->
            buildList { while (c.moveToNext()) runCatching { json.decodeFromString(Message.serializer(), c.getString(0)) }.getOrNull()?.let(::add) }
        }
        return out.asReversed()
    }

    @Synchronized fun message(id: String): Message? = readableDatabase.rawQuery(
        "SELECT json FROM msgs WHERE id = ?", arrayOf(id),
    ).use { c -> if (c.moveToFirst()) runCatching { json.decodeFromString(Message.serializer(), c.getString(0)) }.getOrNull() else null }

    @Synchronized fun searchMessages(chat: String, query: String, limit: Int = 5): List<Message> {
        val q = "%${query.trim().replace("%", "")}%"
        return readableDatabase.rawQuery(
            "SELECT json FROM msgs WHERE chat = ? AND json LIKE ? ORDER BY at DESC LIMIT $limit",
            arrayOf(chat, q),
        ).use { c -> buildList { while (c.moveToNext()) runCatching { json.decodeFromString(Message.serializer(), c.getString(0)) }.getOrNull()?.let(::add) } }
    }

    @Synchronized fun lastMessage(chat: String): Message? = readableDatabase.rawQuery(
        "SELECT json FROM msgs WHERE chat = ? ORDER BY at DESC, rowid DESC LIMIT 1", arrayOf(chat),
    ).use { c -> if (c.moveToFirst()) runCatching { json.decodeFromString(Message.serializer(), c.getString(0)) }.getOrNull() else null }

    // Memory

    /**
     * Keep a fact. Saying the same thing again only refreshes it, so a
     * bot that repeats itself does not fill its memory with copies.
     */
    @Synchronized fun remember(bot: String, text: String): Note {
        val body = text.trim().take(2000)
        val at = Instant.now().toString()
        val same = memories(bot).firstOrNull { sameFact(it.text, body) }
        if (same != null) {
            writableDatabase.update("notes", ContentValues().apply { put("at", at) }, "id = ?", arrayOf(same.id))
            return same.copy(at = at)
        }
        val id = UUID.randomUUID().toString()
        writableDatabase.insert("notes", null, ContentValues().apply {
            put("id", id)
            put("bot", bot)
            put("at", at)
            put("text", body)
        })
        return Note(id, at, body)
    }

    @Synchronized fun memories(bot: String): List<Note> = readableDatabase.rawQuery(
        "SELECT id, at, text FROM notes WHERE bot = ? ORDER BY at DESC", arrayOf(bot),
    ).use { c -> buildList { while (c.moveToNext()) add(Note(c.getString(0), c.getString(1), c.getString(2))) } }

    /** Forget the facts that mention [text], or every fact when it's empty. */
    @Synchronized fun forget(bot: String, text: String) {
        val wanted = text.trim()
        if (wanted.isEmpty()) {
            writableDatabase.delete("notes", "bot = ?", arrayOf(bot))
            return
        }
        for (n in memories(bot)) {
            if (sameFact(n.text, wanted) || normalizeFact(n.text).contains(normalizeFact(wanted))) {
                writableDatabase.delete("notes", "id = ?", arrayOf(n.id))
            }
        }
    }

    // Providers

    @Synchronized fun providers(): List<Provider> = readableDatabase.rawQuery("SELECT json FROM providers ORDER BY rowid", null).use { c ->
        buildList { while (c.moveToNext()) runCatching { json.decodeFromString(Provider.serializer(), c.getString(0)) }.getOrNull()?.let(::add) }
    }

    @Synchronized fun putProvider(p: Provider) {
        writableDatabase.insertWithOnConflict("providers", null, ContentValues().apply {
            put("id", p.id)
            put("json", json.encodeToString(Provider.serializer(), p))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun removeProvider(id: String) { writableDatabase.delete("providers", "id = ?", arrayOf(id)) }

    // Routines

    @Synchronized fun routines(bot: String): List<Routine> = rawList("routines", "bot = ?", arrayOf(bot), Routine.serializer())
    @Synchronized fun allRoutines(): List<Routine> = rawList("routines", null, null, Routine.serializer())
    @Synchronized fun putRoutine(r: Routine) { putJson("routines", r.id, extra = "bot" to r.botId, json = json.encodeToString(Routine.serializer(), r)) }
    @Synchronized fun removeRoutine(id: String) { writableDatabase.delete("routines", "id = ?", arrayOf(id)) }

    // Skills

    @Synchronized fun skills(): List<Skill> = rawList("skills", null, null, Skill.serializer())
    @Synchronized fun putSkill(s: Skill) { putJson("skills", s.id, json = json.encodeToString(Skill.serializer(), s)) }
    @Synchronized fun removeSkill(id: String) { writableDatabase.delete("skills", "id = ?", arrayOf(id)) }

    // MCP integrations

    @Synchronized fun mcp(): List<McpServer> = rawList("mcp", null, null, McpServer.serializer())
    @Synchronized fun putMcp(m: McpServer) { putJson("mcp", m.id, json = json.encodeToString(McpServer.serializer(), m)) }
    @Synchronized fun removeMcp(id: String) { writableDatabase.delete("mcp", "id = ?", arrayOf(id)) }

    // Vault (the values live in private preferences, never here)

    @Synchronized fun vault(): List<VaultSecret> = rawList("vault", null, null, VaultSecret.serializer())
    @Synchronized fun putVault(v: VaultSecret) { putJson("vault", v.id, json = json.encodeToString(VaultSecret.serializer(), v)) }
    @Synchronized fun removeVault(id: String) { writableDatabase.delete("vault", "id = ?", arrayOf(id)) }

    // Small settings

    @Synchronized fun get(k: String): String? = readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    @Synchronized fun set(k: String, v: String) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun putJson(table: String, id: String, extra: Pair<String, String>? = null, json: String) {
        writableDatabase.insertWithOnConflict(table, null, ContentValues().apply {
            put("id", id)
            extra?.let { put(it.first, it.second) }
            put("json", json)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun <T> rawList(table: String, where: String?, args: Array<String>?, serializer: kotlinx.serialization.KSerializer<T>): List<T> =
        readableDatabase.rawQuery("SELECT json FROM $table${where?.let { " WHERE $it" }.orEmpty()} ORDER BY rowid", args).use { c ->
            buildList { while (c.moveToNext()) runCatching { json.decodeFromString(serializer, c.getString(0)) }.getOrNull()?.let(::add) }
        }
}
