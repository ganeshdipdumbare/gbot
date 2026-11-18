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

/** Models sometimes wrap a value in quotes; keep the words, not the quotes. */
internal fun unquote(s: String): String = plainText(s).removeSurrounding("'").trim()

/**
 * A value as the person should read it. A model sometimes hands one back
 * still encoded — wrapped in quotes, or with \n where the line breaks
 * belong, or JSON-encoded a second time. Unwrap it here, so nothing the
 * person reads is left carrying the escapes.
 */
fun plainText(raw: String): String {
    var s = raw.trim()
    repeat(2) {
        if (s.length < 2 || s.first() != '"' || s.last() != '"') return@repeat
        val inner = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(s) as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull() ?: return@repeat
        s = inner.trim()
    }
    if (!s.contains('\n')) s = s.replace("\\r\\n", "\n").replace("\\n", "\n")
    return s.replace("\\t", "\t").trim()
}

/**
 * The lines of a list a model wrote: the choices for a question, the
 * checks of a contract. One per line, however it packed them — as lines,
 * as a JSON array, or as one escaped string.
 */
fun choiceLines(raw: String, max: Int): List<String> {
    val text = plainText(raw)
    val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }.getOrNull()
    val array = parsed as? kotlinx.serialization.json.JsonArray
    val parts = array?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: text.split("\n")
    return parts
        .map { plainText(it).trimStart('-', '*', '•', '·', ' ').trim().removeSurrounding("\"").trim() }
        .filter { line -> line.any { it.isLetterOrDigit() } }
        .distinct()
        .take(max)
}

/**
 * What a bot can do on this phone. Small on purpose: a window on the web,
 * a notification, its own memory, and pages fetched in the background.
 */class LocalTools(
    private val ctx: Context,
    private val db: LocalDb,
    private val browser: BrowserController,
    private val botId: String,
    private val skills: List<Skill> = emptyList(),
    private val secret: (String) -> Pair<VaultSecret, String>? = { null },
    private val schedule: suspend (String, String, String) -> String = { _, _, _ -> "Scheduling isn't available." },
    /** The person's other bots, which this one may message. */
    private val peers: List<Bot> = emptyList(),
    /** Sends a message to another bot: (to, message, "ask" or "tell"). */
    private val messageBot: suspend (Bot, String, String) -> String = { _, _, _ -> "Messaging isn't available." },
    /** The roster tools, offered to the main bot only. */
    private val roster: suspend () -> List<Bot> = { emptyList() },
    private val createBot: suspend (String, String) -> Bot = { _, _ -> error("Hatching isn't available.") },
    private val changeBot: suspend (String, JsonObject) -> Bot = { _, _ -> error("Changing bots isn't available.") },
    private val archiveBot: suspend (String) -> Unit = { error("Archiving isn't available.") },
    /** What only the running brain can do: ask the person, routines, and checking the work. */
    private val host: ToolHost? = null,
) {
    /** The run behind these tools. */
    interface ToolHost {
        suspend fun ask(question: String, options: List<String>): String
        suspend fun routines(): List<dev.gbot.app.net.Routine>
        suspend fun setRoutine(id: String, active: Boolean)
        suspend fun deleteRoutine(id: String)
        suspend fun runRoutine(id: String)
        fun contract(goal: String, checks: List<String>): String
        suspend fun verify(): String
        /** The first browser step of this run: where the person can watch. */
        fun watching(host: String)
    }

    /** The bot's own folder on this phone: notes, drafts and files it makes. */
    private val folder: java.io.File by lazy { java.io.File(ctx.filesDir, "bots/$botId/files").apply { mkdirs() }.canonicalFile }

    /** Only the person's main bot looks after the roster. */
    private val main = botId == MAIN_BOT

    /** The current task's steps, kept for this run only. */
    private var plan: List<String> = emptyList()

    /** Set once a browser step has told the person where to watch. */
    private var watchPosted = false
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    val schemas: List<ToolSchema> = listOf(
        ToolSchema(
            "browser_open",
            "Open a web address in the bot's own browser, which the person can watch. Use it for anything that needs a sign-in, a form, a click, or where the person should see it happen.",
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
        ToolSchema("browser_forward", "Go forward again after browser_back.", params()),
        ToolSchema("browser_reload", "Reload the page that is open.", params()),
        ToolSchema(
            "browser_scroll", "Scroll the page, then read it again. Use it when what you need is further down or loads as you scroll.",
            params("direction", "string", "down (default) or up"),
        ),
        ToolSchema(
            "browser_wait", "Wait for a slow page to finish, then read it again.",
            params("seconds", "integer", "1 to 10, default 3"),
        ),
        ToolSchema(
            "open_in_chrome", "Hand a page to the phone's real browser, for sites that need you signed in there.",
            params("url", "string", "Full web address", required = listOf("url")),
        ),
        ToolSchema(
            "notify", "Show a notification on this phone.",
            params("title", "string", "Short title", "text", "string", "One or two lines", required = listOf("title", "text")),
        ),
        ToolSchema(
            "remember", "Keep a fact about the person for later conversations: a preference, a name, a decision, how they like things.",
            params("text", "string", "The fact, in one sentence", required = listOf("text")),
        ),
        ToolSchema(
            "forget", "Drop something you remembered, when it is wrong or they asked you to forget it.",
            params("text", "string", "What to forget; leave empty to forget everything", required = emptyList()),
        ),
        ToolSchema(
            "recall", "Look through what this bot remembers and its past conversations.",
            params("query", "string", "Words to look for", required = listOf("query")),
        ),
        ToolSchema(
            "web_fetch", "Read a page's text without opening the browser. For information where the person only wants the answer: it has no sign-ins, cannot click, and the person sees nothing while it runs.",
            params("url", "string", "Full web address", required = listOf("url")),
        ),
        ToolSchema("profile", "The person's own details, for filling in forms (name, email, phone, links, about).", params()),
        ToolSchema(
            "plan",
            "Write down the steps of a task before starting, then work through them in order. Internal: the person never sees it.",
            params("steps", "string", "The steps, one per line, in order", required = listOf("steps")),
        ),
        ToolSchema(
            "ask_user",
            "Ask the person a question when you need information or a decision only they can give. They answer on a card in the chat; " +
                "don't ask in plain text when this fits. Give 2-4 short options when the answer is a choice.",
            params(
                "question", "string", "One short, specific question",
                "options", "string", "Choices, one per line (optional)", required = listOf("question"),
            ),
        ),
        ToolSchema(
            "show_results",
            "Show a list of 2 to 12 things to compare as cards in the chat, e.g. hotels, products or places. Use it when the person would scan the list better than read it. " +
                "Each item comes from a tool answer you have read; links and pictures must be https addresses from that answer. Then reply in prose about what matters.",
            resultsParams(),
        ),
        ToolSchema("file_list", "List the files in your own folder on this phone.", params()),
        ToolSchema(
            "file_read", "Read one of the files in your folder.",
            params("path", "string", "The file's name, as file_list shows it", required = listOf("path")),
        ),
        ToolSchema(
            "file_write", "Write a file in your folder: notes, drafts, lists or data to keep between messages. Replaces the file if it exists.",
            params("path", "string", "A file name like notes/trip.md", "content", "string", "The whole file", required = listOf("path", "content")),
        ),
        ToolSchema(
            "task_contract",
            "Before a task with a deliverable, agree a short contract: the goal and 3-6 concrete checks the person can verify. The person approves it on a card.",
            params("goal", "string", "What done means, in one line", "checks", "string", "The checks, one per line", required = listOf("goal", "checks")),
        ),
        ToolSchema(
            "task_verify",
            "Check what you actually did against the agreed contract, independently, and add a Verification receipt to your answer. " +
                "Call it before saying the task is done. If a check fails, fix it or say what remains.",
            params(),
        ),
        ToolSchema("routine_list", "List your routines, with their schedules and whether they are on.", params()),
        ToolSchema(
            "routine_update", "Turn one of your routines on or off.",
            params("name", "string", "The routine's name", "active", "boolean", "true to turn it on, false to pause it", required = listOf("name", "active")),
        ),
        ToolSchema("routine_delete", "Delete one of your routines.", params("name", "string", "The routine's name", required = listOf("name"))),
        ToolSchema("routine_run", "Run one of your routines now.", params("name", "string", "The routine's name", required = listOf("name"))),
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
    ) + (if (peers.isEmpty()) emptyList() else listOf(
        ToolSchema(
            "message_bot",
            "Ask or tell another of the person's bots something: " + peers.joinToString(", ") { it.name } +
                ". It doesn't wait: send every ask you need, then finish your turn, and the answers come back to you together once they're all in. " +
                "Use it when the person asks you to tell, ask or work with another bot, or when their request clearly needs one.",
            params(
                "to", "string", "The bot's name",
                "message", "string", "What to tell or ask them, written to them",
                "expect", "string", "ask (default): you need their answer; tell: you're only passing something on, and nothing comes back",
                required = listOf("to", "message"),
            ),
        ),
    )) + (if (!main) emptyList() else listOf(
        ToolSchema("bot_list", "List the person's bots with their titles and models. Look before you change one.", params()),
        ToolSchema(
            "bot_create", "Hatch a new bot for the person.",
            params(
                "name", "string", "Short name, e.g. Scout",
                "title", "string", "What it is for, one line",
                "instructions", "string", "Standing instructions for it",
                required = listOf("name"),
            ),
        ),
        ToolSchema(
            "bot_update", "Change one of the person's bots: its title, instructions or model. Only what you pass changes.",
            params(
                "bot", "string", "The bot's name",
                "title", "string", "New title",
                "instructions", "string", "New standing instructions",
                "model", "string", "New model id, as bot_list shows it",
                required = listOf("bot"),
            ),
        ),
        ToolSchema(
            "bot_archive", "Archive one of the person's bots. The main bot can't be archived.",
            params("bot", "string", "The bot's name", required = listOf("bot")),
        ),
    ))

    /** A path inside the bot's folder, or null if it would leave it. */
    private fun fileIn(path: String): java.io.File? {
        val clean = path.trim().trimStart('/').ifEmpty { return null }
        val f = java.io.File(folder, clean).canonicalFile
        return if (f.path.startsWith(folder.path + java.io.File.separator)) f else null
    }

    /** Tools that change something out in the world, so they ask first. */
    fun needsApproval(name: String) = name in setOf(
        "browser_open", "browser_click", "browser_fill", "browser_enter", "open_in_chrome", "vault_type",
        "routine_create", "web_fetch", "bot_create", "bot_update", "bot_archive",
        "routine_update", "routine_delete", "task_contract",
    )

    /** A call that can't work, refused before the person is asked to approve it. */
    fun precheck(name: String, args: JsonObject): String? = when {
        name in setOf("browser_click", "browser_fill", "browser_enter") && args.int("id") < 0 ->
            "Give the element's number from browser_read as id."
        else -> null
    }

    fun describe(name: String, args: JsonObject): String = when (name) {
        "browser_open" -> "Open ${args.str("url")}"
        "browser_click" -> "Click “${lastSnapshotLabel(args.int("id"))}”"
        "browser_fill" -> "Fill in “${lastSnapshotLabel(args.int("id"))}” with ${args.str("value").take(80)}" +
            if (args.str("submit") == "true") ", then submit the form" else ""
        "browser_enter" -> "Submit the form"
        "open_in_chrome" -> "Open ${args.str("url")} in Chrome"
        "notify" -> "Show a notification: ${args.str("title")}"
        "use_skill" -> "Load the ${args.str("name")} skill"
        "vault_type" -> "Type the ${args.str("name")} secret into “${lastSnapshotLabel(args.int("id"))}”"
        "routine_create" -> "Schedule “${args.str("name")}”: ${args.str("schedule")} — ${args.str("prompt").take(120)}"
        "routine_update" -> (if (args.str("active") == "true") "Turn on “" else "Pause “") + args.str("name") + "”"
        "routine_delete" -> "Delete the routine “${args.str("name")}”"
        "routine_run" -> "Run “${args.str("name")}” now"
        "task_contract" -> "Agree on the task: ${plainText(args.str("goal"))}\n" + choiceLines(args.str("checks"), 6).joinToString("\n") { "• $it" }
        "task_verify" -> "Check the work"
        "ask_user" -> plainText(args.str("question"))
        "file_write" -> "Write ${args.str("path")}"
        "message_bot" -> (if (args.str("expect").lowercase() == "tell") "Tell " else "Ask ") + "${args.str("to")}: ${args.str("message").take(120)}"
        "plan" -> "Plan the task"
        "bot_list" -> "List the bots"
        "bot_create" -> "Hatch a bot: ${args.str("name")}"
        "bot_update" -> "Change ${args.str("bot")}"
        "bot_archive" -> "Archive ${args.str("bot")}"
        else -> name.replace('_', ' ')
    }

    // The element list from the last read, so an approval can name the button
    // instead of showing a number nobody understands.
    private val lastLabels = mutableMapOf<Int, String>()
    private fun lastSnapshotLabel(id: Int): String = lastLabels[id] ?: "#$id"

    suspend fun run(name: String, args: JsonObject): String {
        val out = runTool(name, args)
        // The first browser step that has a page open says where the person
        // can watch; before that the window would only say "Nothing open yet".
        if (!watchPosted && name.startsWith("browser_") && browser.url.value.isNotBlank()) {
            watchPosted = true
            host?.watching(hostOf(browser.url.value))
        }
        return out
    }

    private suspend fun runTool(name: String, args: JsonObject): String = when (name) {
        "plan" -> {
            val steps = planSteps(args.str("steps"))
            if (steps.isEmpty()) "Give the plan as one step per line."
            else {
                plan = steps
                "Plan kept internal (the person does not see it):\n" +
                    steps.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n") +
                    "\nWork through it in order, one step at a time. Adjust it with plan if the task changes. Don't tell the person the plan."
            }
        }
        "bot_list" -> rosterSummary(roster())
        "bot_create" -> {
            val newName = unquote(args.str("name"))
            if (newName.isEmpty()) "Give the new bot a name." else {
                val b = createBot(newName, unquote(args.str("title")))
                val notes = unquote(args.str("instructions"))
                if (notes.isNotEmpty()) changeBot(b.id, buildJsonObject { put("instructions", notes) })
                "Hatched ${b.name}."
            }
        }
        "bot_update" -> {
            val raw = unquote(args.str("bot"))
            val target = pickPeer(raw, roster())
            val patch = buildJsonObject {
                if (args.str("title").isNotBlank()) put("title", unquote(args.str("title")))
                if (args.str("instructions").isNotBlank()) put("instructions", unquote(args.str("instructions")))
                if (args.str("model").isNotBlank()) put("model", args.str("model").trim())
            }
            when {
                target == null -> noBotNamed(raw)
                patch.isEmpty() -> "Say what to change about ${target.name}: title, instructions or model."
                else -> {
                    changeBot(target.id, patch)
                    "Updated ${target.name}."
                }
            }
        }
        "bot_archive" -> {
            val raw = unquote(args.str("bot"))
            val target = pickPeer(raw, roster())
            when {
                target == null -> noBotNamed(raw)
                target.id == MAIN_BOT -> "The main bot can't be archived."
                else -> {
                    archiveBot(target.id)
                    "Archived ${target.name}."
                }
            }
        }
        "message_bot" -> {
            val raw = args.str("to")
            val target = pickPeer(raw, peers)
            when {
                target != null -> messageBot(target, args.str("message"), args.str("expect").lowercase().ifBlank { "ask" })
                peers.isEmpty() -> "There are no other bots to message."
                else -> "No bot named " + normalizeName(raw).ifEmpty { "that" } +
                    ". Use exactly one of these names: " + peers.joinToString(", ") { it.name } + "."
            }
        }
        "browser_open" -> {
            val target = args.str("url")
            if (target.isBlank()) return "Give browser_open a url."
            val out = browser.open(target)
            noteLabels(out)
            out
        }
        "browser_read" -> {
            val out = browser.snapshot()
            noteLabels(out)
            out
        }
        "browser_click", "browser_fill", "browser_enter" -> precheck(name, args) ?: element(name, args)
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
            val note = db.remember(botId, args.str("text"))
            "Remembered: ${note.text.take(160)}"
        }
        "forget" -> {
            val what = args.str("text").trim()
            db.forget(botId, what)
            if (what.isEmpty()) "Forgot everything." else "Forgot anything about “${what.take(80)}”."
        }
        "recall" -> {
            val q = args.str("query")
            val notes = rankNotes(db.memories(botId), q, 6).map { "Remembered ${noteDay(it.at)}: ${it.text.take(300)}" }
            val past = db.searchMessages(botId, q).map { "${if (it.role == "user") "They said" else "I said"}: ${it.content.take(300)}" }
            (notes + past).take(8).joinToString("\n").ifBlank { "Nothing found." }
        }
        "web_fetch" -> fetch(args.str("url"))
        "profile" -> db.get("profile").orEmpty().ifBlank { "The person has not filled in their details." }
        "use_skill" -> skills.find { it.name.equals(args.str("name"), ignoreCase = true) }?.body
            ?: "No skill named ${args.str("name")}. Use one from the list in your system prompt."
        "vault_type" -> vaultType(args)
        "routine_create" -> schedule(args.str("name"), args.str("prompt"), args.str("schedule"))
        "browser_forward" -> browser.forward().also(::noteLabels)
        "browser_reload" -> browser.reload().also(::noteLabels)
        "browser_scroll" -> browser.scroll(args.str("direction").lowercase() != "up").also(::noteLabels)
        "browser_wait" -> browser.waitFor(args.int("seconds").takeIf { it > 0 } ?: 3).also(::noteLabels)
        "ask_user" -> host?.ask(plainText(args.str("question")), choiceLines(args.str("options"), 4))
            ?: "Asking isn't available here."
        "file_list" -> folder.walkTopDown().filter { it.isFile }.map { it.relativeTo(folder).path + " (${it.length()} bytes)" }.toList()
            .ifEmpty { listOf("Your folder is empty.") }.joinToString("\n")
        "file_read" -> fileIn(args.str("path"))?.takeIf { it.isFile }?.let { f -> f.readText().take(20_000).ifEmpty { "(empty file)" } }
            ?: "No such file. Use file_list to see what is there."
        "file_write" -> fileIn(args.str("path"))?.let { f ->
            val content = args.str("content")
            if (content.length > 200_000) return "That's too big for one file; split it up."
            f.parentFile?.mkdirs()
            f.writeText(content)
            "Saved ${f.relativeTo(folder).path} (${content.length} characters)."
        } ?: "Use a plain file name inside your folder, like notes/trip.md."
        "task_contract" -> host?.contract(plainText(args.str("goal")), choiceLines(args.str("checks"), 6))
            ?: "Contracts aren't available here."
        "task_verify" -> host?.verify() ?: "Checking isn't available here."
        "routine_list" -> host?.routines()?.let { list ->
            if (list.isEmpty()) "You have no routines."
            else list.joinToString("\n") { "- ${it.name}: ${it.`when`.ifEmpty { it.schedule.orEmpty() }}${if (it.active) "" else " (paused)"} — ${it.prompt.take(120)}" }
        } ?: "Routines aren't available here."
        "routine_update", "routine_delete", "routine_run" -> {
            val h = host ?: return "Routines aren't available here."
            val wanted = args.str("name").trim()
            val r = h.routines().find { it.name.equals(wanted, ignoreCase = true) }
                ?: return "No routine named “$wanted”. You have: " + h.routines().joinToString(", ") { it.name }.ifEmpty { "none" } + "."
            when (name) {
                "routine_update" -> { val on = args.str("active") != "false"; h.setRoutine(r.id, on); if (on) "Turned on “${r.name}”." else "Paused “${r.name}”." }
                "routine_delete" -> { h.deleteRoutine(r.id); "Deleted “${r.name}”." }
                else -> { h.runRoutine(r.id); "Started “${r.name}”. Its answer arrives in this chat." }
            }
        }
        else -> if (name.startsWith("browser_"))
            "There is no $name on this phone. Your browser tools are browser_open (with a url), then browser_read, browser_click, browser_fill and browser_enter."
        else "That tool does not exist on this phone."
    }

    /** Click, fill or press Enter on a numbered element from the last read. */
    private suspend fun element(name: String, args: JsonObject): String = when (name) {
        "browser_click" -> {
            val out = browser.click(args.int("id"))
            val snap = browser.snapshot()
            noteLabels(snap)
            out + "\n" + snap.take(6000)
        }
        "browser_fill" -> {
            val out = browser.fill(args.int("id"), args.str("value"))
            // The server's browser_type can submit in the same step.
            if (args.str("submit") != "true" || !out.contains("\"ok\":true")) out
            else out + "\n" + element("browser_enter", args)
        }
        else -> {
            val out = browser.pressEnter(args.int("id"))
            val done = runCatching { browser.snapshot() }.getOrElse { "" }
            noteLabels(done)
            out + if (done.isBlank()) "" else "\n" + done.take(6000)
        }
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

        /** The show_results input: a title and a list of records, each with a title. */
        private fun resultsParams(): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("title", buildJsonObject { put("type", "string"); put("description", "A short heading for the list") })
                put("items", buildJsonObject {
                    put("type", "array")
                    put("description", "2 to 12 records, each taken from a tool answer you have read")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("title", buildJsonObject { put("type", "string"); put("description", "The name of the thing") })
                            put("subtitle", buildJsonObject { put("type", "string"); put("description", "A place, a date or a short description") })
                            put("link", buildJsonObject { put("type", "string"); put("description", "An https page, exactly as a tool gave it") })
                            put("image", buildJsonObject { put("type", "string"); put("description", "An https picture URL, exactly as a tool gave it") })
                            put("fields", buildJsonObject {
                                put("type", "array")
                                put("description", "Up to 6 label and value lines, e.g. price, rating")
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("label", buildJsonObject { put("type", "string") })
                                        put("value", buildJsonObject { put("type", "string") })
                                    })
                                })
                            })
                        })
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"))))
                    })
                })
            })
            put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("items"))))
        }
    }
}

private fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.int(k: String): Int = this[k]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt() ?: -1
private fun JsonObject.bool(k: String): Boolean = this[k]?.jsonPrimitive?.contentOrNull == "true"

// A small model does not always hand back the name it was given. It may
// quote it, curly-quote it, keep the @, add a full stop, paste the whole
// list, or slip in a non-breaking or zero-width space. All of those look
// identical on screen, which is how "No bot named Taro. You can message
// Taro." happened. Matching forgives the decoration rather than the name.

private val invisible = Regex("[\\u200B-\\u200D\\uFEFF]")
private val edges = charArrayOf('@', '"', '\'', '\u201C', '\u201D', '\u2018', '\u2019', '`', '*', '_')

/** A name with the decoration taken off. */
fun normalizeName(s: String): String = s
    .replace(invisible, "")
    .replace('\u00A0', ' ')
    .trim()
    .trim(*edges)
    .trim()
    .trimEnd('.', ',', ':', ';', '!', '?')
    .trim(*edges)
    .trim()

/**
 * Which bot the model meant. Exact names first, across the whole string
 * and its first word, then an unambiguous partial. Never guesses between
 * two bots that both fit.
 */
fun pickPeer(raw: String, peers: List<Bot>): Bot? {
    if (peers.isEmpty()) return null
    val tries = listOf(raw, raw.substringBefore(','), raw.substringBefore(' ')).map(::normalizeName).filter { it.isNotEmpty() }
    for (want in tries) {
        peers.firstOrNull { normalizeName(it.name).equals(want, ignoreCase = true) }?.let { return it }
    }
    for (want in tries) {
        val hits = peers.filter {
            val n = normalizeName(it.name)
            n.isNotEmpty() && (n.startsWith(want, ignoreCase = true) || want.startsWith(n, ignoreCase = true))
        }
        if (hits.size == 1) return hits[0]
    }
    return null
}

/** The roster as the main bot reads it: one line per bot. */
fun rosterSummary(bots: List<Bot>): String {
    if (bots.isEmpty()) return "There are no bots yet."
    return bots.joinToString("\n") { b ->
        "- ${b.name} — ${b.title.trim().ifEmpty { "no title" }} (${b.model.trim().ifEmpty { "default model" }})"
    }
}

/** The one line a failed name match gets, in every roster tool. */
fun noBotNamed(raw: String): String = "No bot named " + normalizeName(raw).ifEmpty { "that" } + "."

/**
 * The steps of a plan, from the model's one-per-line answer. Numbering and
 * bullets are decoration; the order is what matters.
 */
fun planSteps(raw: String): List<String> = plainText(raw).lines()
    .map { it.trim().trimStart('-', '*', '•', '·', ' ', '—', '–').trim() }
    .map { it.replace(Regex("^\\d+[.):]\\s*"), "") }
    .filter { line -> line.any { it.isLetterOrDigit() } }

/** The site a URL points at, for a status line or a "watch me" note. */
fun hostOf(url: String): String = runCatching { java.net.URI(url).host.orEmpty() }
    .getOrDefault("").removePrefix("www.")
