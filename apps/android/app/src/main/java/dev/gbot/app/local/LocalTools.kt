package dev.gbot.app.local

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.gbot.app.live.Alerts
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Skill
import dev.gbot.app.net.VaultSecret
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * What a bot can do on this phone. Small on purpose: a window on the web,
 * a notification, its own memory, and pages fetched in the background.
 */
class LocalTools(
    private val ctx: Context,
    private val db: LocalDb,
    private val browser: BrowserController,
    private val botId: String,
    private val skills: List<Skill> = emptyList(),
    private val secret: (String) -> Pair<VaultSecret, String>? = { null },
    private val schedule: suspend (String, String, String) -> String = { _, _, _ -> "Scheduling isn't available." },
    /** The person's other bots, which this one may message. */
    private val peers: List<Bot> = emptyList(),
    private val messageBot: suspend (Bot, String) -> String = { _, _ -> "Messaging isn't available." },
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val schemas: List<ToolSchema> = listOf(
        ToolSchema(
            "browser_open", "Open a web address in the bot's own browser. Use this to look at a site.",
            params("url", "string", "Full web address, e.g. https://example.com", required = listOf("url")),
        ),
        ToolSchema(
            "browser_read", "Read the page that is open: its text, its address, and a numbered list of things you can click or fill.",
            params(),
        ),
        ToolSchema(
            "browser_click", "Click one of the numbered elements from browser_read.",
            params("id", "integer", "The element id from browser_read", required = listOf("id")),
        ),
        ToolSchema(
            "browser_fill", "Type into one of the numbered fields from browser_read. This replaces what is there.",
            params(
                "id", "integer", "The field id from browser_read",
                "value", "string", "What to type", required = listOf("id", "value"),
            ),
        ),
        ToolSchema(
            "browser_enter", "Press Enter in a numbered field, to submit a search or form.",
            params("id", "integer", "The field id from browser_read", required = listOf("id")),
        ),
        ToolSchema("browser_back", "Go back to the previous page.", params()),
        ToolSchema(
            "open_in_chrome", "Hand a page to the phone's real browser, for sites that need you signed in there.",
            params("url", "string", "Full web address", required = listOf("url")),
        ),
        ToolSchema(
            "notify", "Show a notification on this phone.",
            params("title", "string", "Short title", "text", "string", "One or two lines", required = listOf("title", "text")),
        ),
        ToolSchema(
            "remember", "Keep a fact for later conversations with this bot.",
            params("text", "string", "The fact, in one sentence", required = listOf("text")),
        ),
        ToolSchema(
            "recall", "Look through what this bot remembers and its past conversations.",
            params("query", "string", "Words to look for", required = listOf("query")),
        ),
        ToolSchema(
            "web_fetch", "Fetch a page's text without opening the browser. Good for articles and plain sites.",
            params("url", "string", "Full web address", required = listOf("url")),
        ),
        ToolSchema("profile", "The person's own details, for filling in forms (name, email, phone, links, about).", params()),
        ToolSchema(
            "use_skill", "Load the full instructions of one of the skills listed in your system prompt.",
            params("name", "string", "The skill's name", required = listOf("name")),
        ),
        ToolSchema(
            "routine_create",
            "Schedule a prompt to run for this person later. Use 5-field cron (minute hour day month weekday), or @daily/@weekly/@hourly.",
            params(
                "name", "string", "Short name",
                "prompt", "string", "What to do when it runs",
                "schedule", "string", "5-field cron, e.g. 0 8 * * *", required = listOf("name", "prompt", "schedule"),
            ),
        ),
        ToolSchema(
            "vault_type",
            "Type one of the person's saved secrets into a password field from browser_read. The value is never shown to you. Only works on the sites saved with that secret; submit presses Enter.",
            params(
                "name", "string", "The secret's name, e.g. LinkedIn",
                "id", "integer", "The field id from browser_read",
                "submit", "boolean", "true to press Enter after typing", required = listOf("name", "id"),
            ),
        ),
    ) + if (peers.isEmpty()) emptyList() else listOf(
        ToolSchema(
            "message_bot",
            "Send a message to another of the person's bots: " + peers.joinToString(", ") { it.name } +
                ". It does not wait for an answer; they reply in their own chat. " +
                "Use it when the person asks you to tell, ask or work with another bot.",
            params(
                "to", "string", "The bot's name",
                "message", "string", "What to tell or ask them, written to them", required = listOf("to", "message"),
            ),
        ),
    )

    /** Tools that change something out in the world, so they ask first. */
    fun needsApproval(name: String) = name in setOf("browser_click", "browser_fill", "browser_enter", "open_in_chrome", "vault_type", "routine_create")

    fun describe(name: String, args: JsonObject): String = when (name) {
        "browser_open" -> "Open ${args.str("url")}"
        "browser_click" -> "Click “${lastSnapshotLabel(args.int("id"))}”"
        "browser_fill" -> "Fill in “${lastSnapshotLabel(args.int("id"))}” with ${args.str("value").take(80)}"
        "browser_enter" -> "Submit the form"
        "open_in_chrome" -> "Open ${args.str("url")} in Chrome"
        "notify" -> "Show a notification: ${args.str("title")}"
        "use_skill" -> "Load the ${args.str("name")} skill"
        "vault_type" -> "Type the ${args.str("name")} secret into “${lastSnapshotLabel(args.int("id"))}”"
        "routine_create" -> "Schedule “${args.str("name")}”: ${args.str("schedule")} — ${args.str("prompt").take(120)}"
        "message_bot" -> "Message ${args.str("to")}: ${args.str("message").take(120)}"
        else -> name.replace('_', ' ')
    }

    // The element list from the last read, so an approval can name the button
    // instead of showing a number nobody understands.
    private val lastLabels = mutableMapOf<Int, String>()
    private fun lastSnapshotLabel(id: Int): String = lastLabels[id] ?: "#$id"

    suspend fun run(name: String, args: JsonObject): String = when (name) {
        "message_bot" -> {
            val want = args.str("to").removePrefix("@").trim()
            val target = peers.find { it.name.equals(want, ignoreCase = true) }
            when {
                target != null -> messageBot(target, args.str("message"))
                peers.isEmpty() -> "There are no other bots to message."
                else -> "No bot named “$want”. You can message " + peers.joinToString(", ") { it.name } + "."
            }
        }
        "browser_open" -> {
            val out = browser.open(args.str("url"))
            noteLabels(out)
            out
        }
        "browser_read" -> {
            val out = browser.snapshot()
            noteLabels(out)
            out
        }
        "browser_click" -> {
            val out = browser.click(args.int("id"))
            val snap = browser.snapshot()
            noteLabels(snap)
            out + "\n" + snap.take(6000)
        }
        "browser_fill" -> browser.fill(args.int("id"), args.str("value"))
        "browser_enter" -> {
            val out = browser.pressEnter(args.int("id"))
            val done = runCatching { browser.snapshot() }.getOrElse { "" }
            noteLabels(done)
            out + if (done.isBlank()) "" else "\n" + done.take(6000)
        }
        "browser_back" -> {
            val out = browser.back()
            noteLabels(out)
            out
        }
        "open_in_chrome" -> {
            openExternal(args.str("url"))
            "Opened in the phone's browser. The person can finish there."
        }
        "notify" -> {
            notify(args.str("title").ifBlank { "gbot" }, args.str("text"))
            "Notification shown."
        }
        "remember" -> {
            db.remember(botId, args.str("text"))
            "Remembered."
        }
        "recall" -> {
            val q = args.str("query")
            val notes = db.recall(botId, q)
            val past = db.searchMessages(botId, q).map { "${if (it.role == "user") "They said" else "I said"}: ${it.content.take(300)}" }
            (notes + past).take(8).joinToString("\n").ifBlank { "Nothing found." }
        }
        "web_fetch" -> fetch(args.str("url"))
        "profile" -> db.get("profile").orEmpty().ifBlank { "The person has not filled in their details." }
        "use_skill" -> skills.find { it.name.equals(args.str("name"), ignoreCase = true) }?.body
            ?: "No skill named ${args.str("name")}. Use one from the list in your system prompt."
        "vault_type" -> vaultType(args)
        "routine_create" -> schedule(args.str("name"), args.str("prompt"), args.str("schedule"))
        else -> "That tool does not exist on this phone."
    }

    /** Types a saved secret without ever returning its value to the model. */
    private suspend fun vaultType(args: JsonObject): String {
        val found = secret(args.str("name")) ?: return "No saved secret named ${args.str("name")}. Ask the person to add it in Settings → Vault."
        val host = runCatching { java.net.URI(browser.url.value).host.orEmpty().lowercase() }.getOrDefault("")
        val allowed = found.first.origins.any { o ->
            val clean = o.trim().lowercase().removePrefix("https://").removePrefix("http://").trim('/')
            clean.isNotEmpty() && (host == clean || host.endsWith(".$clean"))
        }
        if (host.isEmpty() || !allowed) return "That secret isn't saved for this site, so I won't type it here."
        val out = browser.fill(args.int("id"), found.second)
        if (args.bool("submit")) browser.pressEnter(args.int("id"))
        return out
    }

    private fun noteLabels(snapshot: String) {
        runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(snapshot).jsonObject
            (o["elements"] as? kotlinx.serialization.json.JsonArray).orEmpty().forEach { e ->
                val el = e.jsonObject
                val id = el["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@forEach
                lastLabels[id] = el["label"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { el["tag"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            }
        }
    }

    private fun openExternal(url: String) {
        val target = BrowserController.normalize(url)
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun notify(title: String, text: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val n = NotificationCompat.Builder(ctx, Alerts.CH_ALERTS)
            .setSmallIcon(dev.gbot.app.R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(System.currentTimeMillis().toInt(), n) }
    }

    private fun fetch(raw: String): String {
        val url = BrowserController.normalize(raw)
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) gbot").build()
        return http.newCall(req).execute().use { res ->
            val body = res.body.string()
            if (!res.isSuccessful) return@use "The page returned ${res.code}."
            val text = body
                .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?s)<[^>]+>"), " ")
                .replace(Regex("&nbsp;"), " ")
                .replace(Regex("&amp;"), "&")
                .replace(Regex("&lt;"), "<")
                .replace(Regex("&gt;"), ">")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
            text.take(6000).ifBlank { "That page had no readable text." }
        }
    }

    companion object {
        private fun params(vararg fields: Any, required: List<String> = emptyList()): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                var i = 0
                while (i < fields.size) {
                    val name = fields[i] as String
                    val type = fields[i + 1] as String
                    val desc = fields[i + 2] as String
                    put(name, buildJsonObject { put("type", type); put("description", desc) })
                    i += 3
                }
            })
            put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
        }
    }
}

private fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.int(k: String): Int = this[k]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: -1
private fun JsonObject.bool(k: String): Boolean = this[k]?.jsonPrimitive?.contentOrNull == "true"
