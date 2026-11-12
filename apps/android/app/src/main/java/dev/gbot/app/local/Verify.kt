package dev.gbot.app.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/*
 * Proof before "done", as on the server (server/internal/runs/verify.go):
 * agree a contract (a goal and a few checks the person can verify), keep
 * what the run actually did, then have the model judge that evidence in a
 * separate call with no tools. It can only say what the evidence supports.
 */

data class Contract(val goal: String, val checks: List<String>)

/** What one run agreed and did: the evidence the verifier sees, not the bot's own claims. */
class RunBook {
    @Volatile var contract: Contract? = null
    @Volatile var receipt: String = ""
    private val evidence = ArrayList<Pair<String, String>>()

    @Synchronized fun add(tool: String, result: String) {
        if (tool in QUIET) return
        evidence.add(tool to result.trim().take(800))
        while (evidence.size > 24) evidence.removeAt(0)
    }

    @Synchronized fun evidence(): List<Pair<String, String>> = evidence.toList()

    private companion object {
        /** Tools that prove nothing about the work, or carry secrets. */
        val QUIET = setOf(
            "ask_user", "plan", "remember", "recall", "forget", "profile", "message_bot", "vault_type", "use_skill",
            "task_contract", "task_verify", "routine_list", "routine_run", "routine_create", "routine_update", "routine_delete",
        )
    }
}

fun verifyPrompt(asked: String, c: Contract, evidence: List<Pair<String, String>>): String = buildString {
    append("You are an independent verifier. Do not trust the agent's claims; judge only the evidence below.\n\n")
    append("The person asked:\n${asked.take(2000)}\n\n")
    append("Agreed goal:\n${c.goal.take(500)}\n\nChecks:\n")
    c.checks.forEachIndexed { i, ch -> append("${i + 1}. $ch\n") }
    append("\nEvidence (tool calls and their output):\n")
    if (evidence.isEmpty()) append("(none)\n")
    evidence.forEach { (tool, result) -> append("- $tool: $result\n") }
    append(
        "\nFor each check, answer pass, fail or unverified (unverified means the evidence cannot show it, such as a submission the agent cannot see). " +
            "Give a short evidence quote for each. Reply as JSON only:\n" +
            """{"checks":[{"check":"...","status":"pass|fail|unverified","evidence":"..."}],"summary":"..."}""",
    )
}

/** The verifier's JSON, as the Verification receipt the person sees. */
fun receipt(raw: String): String {
    val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    val o = runCatching { Json.parseToJsonElement(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1)).jsonObject }.getOrNull()
    val checks = (o?.get("checks") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    if (checks.isEmpty()) {
        return "**Verification**\nCould not verify automatically. Check the work before relying on it."
    }
    fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    var passed = 0
    var failed = 0
    val lines = checks.map { ch ->
        val icon = when (ch.s("status").lowercase()) {
            "pass" -> { passed++; "✅" }
            "fail" -> { failed++; "❌" }
            else -> "⚠️"
        }
        "- $icon ${ch.s("check").take(160)}" + ch.s("evidence").takeIf { it.isNotEmpty() }?.let { " — ${it.take(240)}" }.orEmpty()
    }
    return buildString {
        append("**Verification**\n")
        append(lines.joinToString("\n"))
        o?.let { it.s("summary") }?.takeIf { it.isNotEmpty() }?.let { append("\n\n${it.take(400)}") }
        when {
            failed > 0 -> append("\n\nNot every check passed; do not claim the task is done.")
            passed == 0 -> append("\n\nNo check could be confirmed from the evidence.")
        }
    }
}
