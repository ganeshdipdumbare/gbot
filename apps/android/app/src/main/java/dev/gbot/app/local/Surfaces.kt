package dev.gbot.app.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * show_ui: the bot builds a small screen in the chat from A2UI parts
 * (https://a2ui.org): a list to compare, a choice, a short form, a
 * confirmation. This is the phone's check of what the bot sent, before any of
 * it is drawn. Nothing the bot writes runs: a surface is data, drawn from our
 * own catalog of parts (ui/chat/Surface.kt), and only these rules let a part
 * through:
 *
 * - only the parts in [PARTS], with only their own properties;
 * - every child a part names exists, one is "root", and nothing loops;
 * - a picture is an https address a tool gave or the person wrote, fetched
 *   once and kept on the phone; the surface then names the kept file, never
 *   the site, so drawing it later reaches nothing;
 * - a button either sends the person's tap back to the bot (an event) or
 *   opens an https link; no other local function can be called.
 *
 * The result is stored on the chat message as `surface`, and turned into
 * A2UI v0.9 messages for the renderer by [messages].
 */
object Surfaces {
    /** Our catalog: the basic catalog's names, a subset of its parts, and our own Image. */
    const val CATALOG_ID = "https://gbot.dev/a2ui/catalogs/phone/v1"
    const val VERSION = "v0.9"
    const val ROOT = "root"
    const val MAX_PARTS = 80
    const val MAX_TEXT = 600
    const val MAX_DATA = 16_000
    const val MAX_PICTURES = 12

    /** A picture the phone kept, as a surface names it. */
    const val FILE_SCHEME = "gbot-file:"

    /** A picture the server keeps as a blob (cards a server-mode bot made). */
    const val BLOB_SCHEME = "gbot-blob:"

    private val COMMON = setOf("id", "component", "weight", "accessibility")

    /** Each part we draw and the properties it may carry. */
    val PARTS: Map<String, Set<String>> = mapOf(
        "Text" to setOf("text", "variant"),
        "Image" to setOf("url", "description", "fit", "variant"),
        "Row" to setOf("children", "justify", "align"),
        "Column" to setOf("children", "justify", "align"),
        "List" to setOf("children", "direction", "align"),
        "Card" to setOf("child"),
        "Divider" to setOf("axis"),
        "Button" to setOf("child", "variant", "action", "checks"),
        "TextField" to setOf("label", "value", "variant", "validationRegexp", "checks"),
        "CheckBox" to setOf("label", "value", "checks"),
        "ChoicePicker" to setOf("label", "options", "value", "variant", "displayStyle", "filterable", "checks"),
        "Slider" to setOf("label", "min", "max", "value", "checks"),
    )

    /** What the bot gets back when a surface can't be shown, so it can fix it. */
    class Rejected(val reason: String) : Exception(reason)

    /**
     * A checked surface. [interactive] when a button sends the person's
     * answer back, so the run waits for it like an ask_user card.
     */
    data class Surface(val title: String, val spec: JsonObject, val interactive: Boolean, val dropped: Int)

    /**
     * Checks a show_ui call. [keep] fetches an allowed https picture and
     * returns the kept file's path, or null when it may not be shown.
     */
    fun build(args: JsonObject, keep: (String) -> String?): Surface {
        val title = LocalResults.text(args, "title")?.let { clipTo(it, 80) }.orEmpty()
        val list = LocalResults.array(args["components"])
            ?: throw Rejected("Give components: a list of parts, each with an id and a component type; one has the id \"root\".")
        if (list.isEmpty()) throw Rejected("components is empty.")
        if (list.size > MAX_PARTS) throw Rejected("Use at most $MAX_PARTS components.")

        val parts = LinkedHashMap<String, JsonObject>()
        for (el in list) {
            val o = el as? JsonObject ?: throw Rejected("Each component is an object with an id and a component type.")
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (!ID.matches(id)) throw Rejected("Component ids are short names of letters, digits, _ or -; got \"${id.take(40)}\".")
            if (id in parts) throw Rejected("Two components have the id \"$id\".")
            val type = (o["component"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val allowed = PARTS[type] ?: throw Rejected("\"$id\" is a \"${type.take(40)}\"; use one of ${PARTS.keys.joinToString(", ")}.")
            parts[id] = JsonObject(o.filterKeys { it in COMMON || it in allowed }.mapValues { (k, v) -> if (k == "id") JsonPrimitive(id) else clipStrings(v) })
        }
        if (ROOT !in parts) throw Rejected("One component needs the id \"root\": the outermost part.")

        // Children must exist and the tree must not loop; parts nothing
        // reaches from the root are dropped.
        val reached = LinkedHashSet<String>()
        fun walk(id: String, path: Set<String>) {
            if (id in path) throw Rejected("\"$id\" contains itself.")
            if (!reached.add(id)) return
            val p = parts[id] ?: return
            for (child in childrenOf(id, p)) {
                if (child !in parts) throw Rejected("\"$id\" names a child \"$child\" that isn't in components.")
                walk(child, path + id)
            }
        }
        walk(ROOT, emptySet())

        var dropped = 0
        var pictures = 0
        var interactive = false
        val files = LinkedHashSet<String>()
        val out = buildJsonArray {
            for (id in reached) {
                var p = parts.getValue(id)
                when (p.str("component")) {
                    "Image" -> {
                        val url = (p["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                            ?: throw Rejected("Image \"$id\" needs url as an https address written out, not a data path.")
                        val kept = if (pictures < MAX_PICTURES && LocalResults.isHttps(url)) keep(url) else null
                        pictures++
                        if (kept == null) {
                            // Not shown: a picture from somewhere no tool named.
                            dropped++
                            p = JsonObject(mapOf("id" to JsonPrimitive(id), "component" to JsonPrimitive("Column"), "children" to JsonArray(emptyList())))
                        } else {
                            files.add(kept)
                            p = JsonObject(p + ("url" to JsonPrimitive(FILE_SCHEME + kept)))
                        }
                    }
                    "Button" -> {
                        p["child"] ?: throw Rejected("Button \"$id\" needs child: the id of a Text with its label.")
                        if (checkAction(id, p["action"])) interactive = true
                    }
                }
                // A function in a value runs when the part is drawn, so only
                // the plain ones may appear; a link opens from a tap alone.
                for ((k, v) in p) if (!(k == "action" && p.str("component") == "Button")) checkCalls(id, v)
                add(p)
            }
        }

        val data = args["data"]?.takeUnless { it is JsonNull }?.let { d ->
            // Some models send a nested object as its JSON text.
            val o = when (d) {
                is JsonObject -> d
                is JsonPrimitive -> if (d.isString) runCatching { kotlinx.serialization.json.Json.parseToJsonElement(d.content) as? JsonObject }.getOrNull() else null
                else -> null
            } ?: throw Rejected("data is an object of starting values, e.g. {\"name\": \"\"}.")
            if (o.toString().length > MAX_DATA) throw Rejected("data is too big; keep it under ${MAX_DATA / 1000} kB.")
            o
        }

        val spec = buildJsonObject {
            put("components", out)
            if (data != null) put("data", data)
            if (files.isNotEmpty()) put("files", JsonArray(files.map { JsonPrimitive(it) }))
        }
        return Surface(title, spec, interactive, dropped)
    }

    /**
     * Cards from a list of records (show_ui's items, or an older
     * show_results row): a Card per item with its picture, title, subtitle,
     * fields and a link button. [items] are already checked by LocalResults
     * on the phone or results.go on the server.
     */
    fun fromItems(items: List<JsonObject>): JsonObject {
        val parts = mutableListOf<JsonObject>()
        val files = LinkedHashSet<String>()
        val cards = mutableListOf<String>()
        fun part(id: String, type: String, vararg props: Pair<String, JsonElement>) {
            parts.add(JsonObject(mapOf("id" to JsonPrimitive(id), "component" to JsonPrimitive(type)) + props))
        }
        fun ids(vararg s: String) = JsonArray(s.map { JsonPrimitive(it) })
        fun lit(s: String) = JsonPrimitive(s)

        items.forEachIndexed { i, o ->
            val title = LocalResults.text(o, "title") ?: return@forEachIndexed
            val body = mutableListOf<String>()
            part("t$i", "Text", "text" to lit(title), "variant" to lit("h5"))
            body.add("t$i")
            LocalResults.text(o, "subtitle")?.let { part("s$i", "Text", "text" to lit(it), "variant" to lit("caption")); body.add("s$i") }
            (o["fields"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.forEachIndexed { j, f ->
                val label = LocalResults.text(f, "label") ?: return@forEachIndexed
                val value = LocalResults.text(f, "value") ?: return@forEachIndexed
                part("f${i}_${j}l", "Text", "text" to lit(label), "variant" to lit("caption"))
                part("f${i}_${j}v", "Text", "text" to lit(value), "variant" to lit("body"), "weight" to JsonPrimitive(1))
                part("f${i}_$j", "Row", "children" to ids("f${i}_${j}l", "f${i}_${j}v"), "align" to lit("center"))
                body.add("f${i}_$j")
            }
            LocalResults.text(o, "link")?.takeIf { LocalResults.isHttps(it) }?.let { link ->
                part("ol$i", "Text", "text" to lit("Open"))
                part(
                    "o$i", "Button", "child" to lit("ol$i"), "variant" to lit("borderless"),
                    "action" to buildJsonObject { put("functionCall", buildJsonObject { put("call", "openUrl"); put("args", buildJsonObject { put("url", link) }) }) },
                )
                body.add("o$i")
            }
            part("b$i", "Column", "children" to JsonArray(body.map { lit(it) }), "weight" to JsonPrimitive(1))
            val file = LocalResults.text(o, "file")
            val blob = LocalResults.text(o, "image")
            val pic = when {
                file != null -> FILE_SCHEME + file.also { files.add(it) }
                blob != null && !blob.contains(':') -> BLOB_SCHEME + blob
                else -> null
            }
            if (pic != null) {
                part("i$i", "Image", "url" to lit(pic), "description" to lit(title), "fit" to lit("cover"), "variant" to lit("smallFeature"))
                part("r$i", "Row", "children" to ids("i$i", "b$i"), "align" to lit("center"))
            } else {
                part("r$i", "Row", "children" to ids("b$i"), "align" to lit("center"))
            }
            part("c$i", "Card", "child" to lit("r$i"))
            cards.add("c$i")
        }
        part(ROOT, "Column", "children" to JsonArray(cards.map { lit(it) }))
        return buildJsonObject {
            put("components", JsonArray(parts))
            if (files.isNotEmpty()) put("files", JsonArray(files.map { JsonPrimitive(it) }))
        }
    }

    /** A checked surface as the A2UI v0.9 messages that draw it. */
    fun messages(surfaceId: String, spec: JsonObject): List<String> = buildList {
        add(envelope("createSurface", buildJsonObject { put("surfaceId", surfaceId); put("catalogId", CATALOG_ID) }))
        add(envelope("updateComponents", buildJsonObject { put("surfaceId", surfaceId); put("components", spec["components"] ?: JsonArray(emptyList())) }))
        (spec["data"] as? JsonObject)?.let { d ->
            add(envelope("updateDataModel", buildJsonObject { put("surfaceId", surfaceId); put("path", "/"); put("value", d) }))
        }
    }

    /** The files a surface may draw: only the pictures kept for it. */
    fun files(spec: JsonObject): Set<String> =
        (spec["files"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()

    /** What the bot reads back after the person taps a button that sends an event. */
    fun answerLine(answer: String?): String {
        if (answer.isNullOrBlank()) return "The person closed it without choosing. Carry on with your best judgement, or ask differently."
        val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(answer) as? JsonObject }.getOrNull()
            ?: return "The person answered: $answer"
        val name = o.str("event").orEmpty()
        val context = (o["context"] as? JsonObject)?.takeIf { it.isNotEmpty() }
        return "The person tapped \"$name\"" + (context?.let { " with $it" } ?: "") + "."
    }

    private fun envelope(kind: String, body: JsonObject): String =
        buildJsonObject { put("version", VERSION); put(kind, body) }.toString()

    /** The ids a part refers to as its children. */
    private fun childrenOf(id: String, p: JsonObject): List<String> {
        val out = mutableListOf<String>()
        p.str("child")?.let(out::add)
        when (val c = p["children"]) {
            null -> Unit
            is JsonArray -> c.forEach { out.add((it as? JsonPrimitive)?.contentOrNull ?: throw Rejected("\"$id\" lists children by id, as strings.")) }
            is JsonObject -> {
                // A template: one copy of componentId per entry in a data list.
                out.add(c.str("componentId") ?: throw Rejected("\"$id\"'s children template needs componentId and path."))
                c.str("path") ?: throw Rejected("\"$id\"'s children template needs componentId and path.")
            }
            else -> throw Rejected("\"$id\" lists children as an array of ids.")
        }
        return out
    }

    /** True for a button that answers the bot; throws for anything but an event or an https link. */
    private fun checkAction(id: String, action: JsonElement?): Boolean {
        val a = action as? JsonObject ?: throw Rejected("Button \"$id\" needs an action: {\"event\": {\"name\": …}} or an openUrl link.")
        (a["event"] as? JsonObject)?.let { e ->
            val name = e.str("name")?.trim().orEmpty()
            if (name.isEmpty() || name.length > 60) throw Rejected("Button \"$id\"'s event needs a short name.")
            if (e["context"] != null && e["context"] !is JsonObject) throw Rejected("Button \"$id\"'s event context is an object.")
            e["context"]?.let { checkCalls(id, it) }
            return true
        }
        val call = a["functionCall"] as? JsonObject
            ?: throw Rejected("Button \"$id\"'s action is an event or a functionCall to openUrl.")
        if (call.str("call") != "openUrl") throw Rejected("Buttons can only call openUrl; send an event to get the person's answer.")
        val url = ((call["args"] as? JsonObject)?.get("url") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (url == null || !LocalResults.isHttps(url)) throw Rejected("Button \"$id\" opens only an https address, written out.")
        return false
    }

    /** The basic catalog's functions that only compute a value. */
    val PURE_CALLS = setOf(
        "and", "or", "not", "email", "required", "regex", "length", "numeric",
        "formatString", "formatNumber", "formatCurrency", "formatDate", "pluralize",
    )

    private fun checkCalls(id: String, v: JsonElement) {
        when (v) {
            is JsonObject -> {
                v.str("call")?.let { if (it !in PURE_CALLS) throw Rejected("\"$id\" calls $it; only a Button's action may open a link.") }
                v.values.forEach { checkCalls(id, it) }
            }
            is JsonArray -> v.forEach { checkCalls(id, it) }
            else -> Unit
        }
    }

    private fun clipStrings(v: JsonElement): JsonElement = when (v) {
        is JsonPrimitive -> if (v.isString && v.content.length > MAX_TEXT) JsonPrimitive(clipTo(v.content, MAX_TEXT)) else v
        is JsonArray -> JsonArray(v.map(::clipStrings))
        is JsonObject -> JsonObject(v.mapValues { (_, x) -> clipStrings(x) })
    }

    private fun clipTo(s: String, n: Int): String {
        val t = s.trim()
        return if (t.length <= n) t else t.take(n - 1) + "…"
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
}
