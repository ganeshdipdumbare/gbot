package dev.gbot.app.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A tool call read out of text the on-device engine refused to parse. */
data class SalvagedCall(val name: String, val arguments: JsonObject)

/** How the small on-device models write a string in a tool call. */
private const val QUOTE = "<|\"|>"

/**
 * True when the on-device engine gave up on the model's tool-call syntax
 * rather than on the work. A small model writes the call slightly wrong —
 * several values under one field, a missing bracket — and the engine's
 * grammar throws, which used to end the whole run with the parser's own
 * words. The call itself is usually still readable, so a run should not
 * die over punctuation.
 */
fun isToolCallSyntaxFailure(e: Throwable): Boolean {
    val m = throwableText(e).ifEmpty { return false }
    return m.contains("parse tool calls", ignoreCase = true) ||
        m.contains("parse FC tool calls", ignoreCase = true) ||
        (m.contains("tool_call", ignoreCase = true) && m.contains("parse", ignoreCase = true))
}

/** Everything the failure says, including what wrapped it: the engine
 *  reports the model's own text, which is where the call can be read. */
fun throwableText(e: Throwable): String {
    val seen = mutableListOf<String>()
    var at: Throwable? = e
    var depth = 0
    while (at != null && depth++ < 8) {
        at.message?.takeIf { it.isNotBlank() }?.let { seen.add(it) }
        at = at.cause.takeIf { it !== at }
    }
    return seen.joinToString("\n")
}

/**
 * Read a tool call out of whatever the model wrote. Only fields the tool
 * really has count as fields, so a colon inside a sentence stays part of
 * the sentence, and several values crammed into one field come back as
 * one value per line — which is what the field asked for anyway.
 */
fun salvageToolCall(raw: String, schemas: List<ToolSchema>): SalvagedCall? {
    val byName = schemas.associateBy { it.name }
    val (schema, start) = Regex("""call:\s*([A-Za-z_][A-Za-z0-9_.\-]*)\s*\{""")
        .findAll(raw)
        .mapNotNull { m -> byName[m.groupValues[1]]?.let { it to m.range.last + 1 } }
        .firstOrNull() ?: return null
    val end = raw.lastIndexOf('}').takeIf { it > start } ?: raw.length
    val body = raw.substring(start, end)

    val props = schema.parameters["properties"]?.jsonObject ?: JsonObject(emptyMap())
    val fields = props.keys
        .mapNotNull { key ->
            Regex("""(?<![A-Za-z0-9_])${Regex.escape(key)}["']?\s*:""").find(body)?.let { key to it.range }
        }
        .sortedBy { it.second.first }
    if (fields.isEmpty()) return if (props.isEmpty()) SalvagedCall(schema.name, JsonObject(emptyMap())) else null

    val args = buildJsonObject {
        fields.forEachIndexed { i, (key, at) ->
            val stop = fields.getOrNull(i + 1)?.second?.first ?: body.length
            val value = fieldText(body.substring(at.last + 1, stop))
            if (value.isNotEmpty()) put(key, typed(value, props[key]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull))
        }
    }
    return if (args.isEmpty()) null else SalvagedCall(schema.name, args)
}

/** The text of a field, however the model fenced it: one value per line. */
private fun fieldText(text: String): String {
    val parts = text.split(QUOTE)
    // A fence missed on one side leaves a separator inside the piece, so
    // every piece loses its leading comma; the words themselves are kept.
    if (parts.size > 1) {
        return parts
            .mapIndexedNotNull { i, p ->
                val one = if (i % 2 == 1) p.trimStart().trimStart(',').trimStart() else bare(p)
                one.takeIf { it.isNotBlank() }
            }
            .joinToString("\n")
            .trim()
    }
    // Nothing fenced the model's way: plain JSON, or a bare word.
    val one = text.trim().removeSuffix(",").trim()
    val quoted = Regex("\"([^\"]*)\"").findAll(one).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
    return if (quoted.isNotEmpty()) quoted.joinToString("\n") else bare(one)
}

private fun bare(s: String): String = s.trim().trim(',', '[', ']', '"', '\'').trim()

private fun typed(value: String, type: String?): JsonPrimitive = when (type) {
    "boolean" -> value.toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
    "integer" -> value.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
    "number" -> value.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
    else -> JsonPrimitive(value)
}
