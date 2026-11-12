package dev.gbot.app.local

import java.time.Instant

/**
 * One thing a bot remembers, as stored on the phone. Facts are kept whole
 * (not per turn), stamped, and ranked when read. No embeddings: a small
 * model's facts are short and keyword overlap plus recency is what a
 * vector index would mostly reproduce, without a second model on the
 * phone.
 */
data class Note(val id: String, val at: String, val text: String)

private val junk = Regex("[^a-z0-9 ]+")
private val spaces = Regex("\\s+")
private val stops = setOf(
    "the", "and", "for", "with", "that", "this", "you", "your", "have", "has", "was", "were",
    "are", "not", "but", "they", "them", "from", "what", "when", "where", "which", "who", "how",
    "about", "into", "over", "then", "than", "also", "just", "can", "his", "her", "their", "our",
)

/** A fact reduced to comparable words. */
fun normalizeFact(s: String): String = s.lowercase().replace(junk, " ").replace(spaces, " ").trim()

/**
 * Whether two facts say the same thing. Exact after normalising, or one
 * stated the other with an extra clause ("likes espresso" inside "likes
 * espresso in the morning"). Short strings must match exactly: "tea" and
 * "green tea" are different facts.
 */
fun sameFact(a: String, b: String): Boolean {
    val na = normalizeFact(a)
    val nb = normalizeFact(b)
    if (na.isEmpty() || nb.isEmpty()) return false
    if (na == nb) return true
    return minOf(na.length, nb.length) >= 24 && (na.contains(nb) || nb.contains(na))
}

private fun queryWords(query: String): List<String> {
    val all = normalizeFact(query).split(' ').filter { it.length > 2 }
    val kept = all.filter { it !in stops }
    return (kept.ifEmpty { all }).distinct()
}

/** 1 for something learned today, tending to 0 as it ages. */
private fun recency(at: String, now: Long): Double {
    val t = runCatching { Instant.parse(at).toEpochMilli() }.getOrDefault(now)
    val days = (now - t).coerceAtLeast(0) / 86_400_000.0
    return 1.0 / (1.0 + days)
}

/**
 * The facts worth putting in front of the model for this query: words in
 * common first, fresher ones breaking ties. With no query, newest first.
 *
 * shortcut: no contradiction test, so "likes tea" and "likes coffee" both
 * stay; recency is what makes the newer one win. Upgrade when the model
 * starts acting on stale facts.
 */
fun rankNotes(notes: List<Note>, query: String, limit: Int, now: Long = System.currentTimeMillis()): List<Note> {
    val words = queryWords(query)
    return notes
        .map { n ->
            val text = normalizeFact(n.text)
            val hits = if (words.isEmpty()) 0 else words.count { text.contains(it) }
            n to (hits * 10.0 + recency(n.at, now))
        }
        .sortedWith(compareByDescending<Pair<Note, Double>> { it.second }.thenByDescending { it.first.at })
        .take(limit)
        .map { it.first }
}

/** What always rides along in the prompt: the freshest facts, newest first. */
fun promptNotes(notes: List<Note>, limit: Int): List<Note> = notes.sortedByDescending { it.at }.take(limit)

/** The day a fact was learned, for the prompt line. */
fun noteDay(at: String): String = at.take(10)
