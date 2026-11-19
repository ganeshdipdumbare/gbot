package dev.gbot.app.local

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.gbot.app.live.Alerts
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Apps that already live on this phone, used directly: no sign-in, no
 * server. Each one is off until the person turns it on and Android grants
 * its permission, and a bot only sees its tools while both hold. Revoking
 * the permission in system settings switches it off again.
 */
object PhoneApps {
    class App(
        val id: String,
        val name: String,
        val blurb: String,
        /** What the person is told before Android asks. */
        val why: String,
        val permissions: List<String>,
    )

    val apps = listOf(
        App(
            "calendar", "Calendar", "Read and add events",
            "Bots can read your upcoming events and add new ones, from every calendar synced to this phone, Google Calendar included.",
            listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        ),
        App(
            "contacts", "Contacts", "Look up people",
            "Bots can look up a name for a phone number or an email address. They can't change contacts.",
            listOf(Manifest.permission.READ_CONTACTS),
        ),
        App(
            "email", "Email", "Draft in your mail app",
            "Bots can open a draft in your mail app, Gmail included. You read it and tap Send yourself, so nothing goes out on its own.",
            emptyList(),
        ),
        App(
            "clock", "Alarms & timers", "Set them in the Clock app",
            "Bots can set an alarm or a timer in your phone's Clock app.",
            emptyList(),
        ),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gbot", Context.MODE_PRIVATE)

    fun enabled(ctx: Context, id: String) = prefs(ctx).getBoolean("phoneapp_$id", false)
    fun setEnabled(ctx: Context, id: String, on: Boolean) { prefs(ctx).edit().putBoolean("phoneapp_$id", on).apply() }

    fun granted(ctx: Context, app: App) =
        app.permissions.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    /** On means the person chose it and Android still allows it. */
    fun on(ctx: Context, id: String): Boolean {
        val app = apps.find { it.id == id } ?: return false
        if (!enabled(ctx, id)) return false
        if (granted(ctx, app)) return true
        // Android took the permission back: switch the app off, so it stays off if the permission returns.
        setEnabled(ctx, id, false)
        return false
    }

    /** Which app each tool belongs to. */
    private val appOf = mapOf(
        "calendar_list" to "calendar", "calendar_add" to "calendar", "contact_search" to "contacts",
        "email_draft" to "email", "alarm_set" to "clock", "timer_set" to "clock",
    )

    // ---- Tools ------------------------------------------------------------

    private fun p(vararg f: Any, required: List<String> = emptyList()) = LocalTools.params(*f, required = required)

    fun schemas(ctx: Context): List<ToolSchema> = buildList {
        if (on(ctx, "calendar")) {
            add(
                ToolSchema(
                    "calendar_list", "List the person's calendar events between two times, from every calendar on this phone.",
                    p(
                        "from", "string", "Start, like 2026-11-20 or 2026-11-20T09:00 (phone's time zone). Default: now",
                        "to", "string", "End, same format. Default: 7 days after from",
                        "query", "string", "Only events whose title contains this",
                    ),
                ),
            )
            add(
                ToolSchema(
                    "calendar_add", "Add an event to the person's calendar.",
                    p(
                        "title", "string", "Event title",
                        "start", "string", "Start, like 2026-11-20T15:00 (phone's time zone), or a date for an all-day event",
                        "end", "string", "End, same format. Default: an hour after start",
                        "location", "string", "Where",
                        "notes", "string", "Description",
                        required = listOf("title", "start"),
                    ),
                ),
            )
        }
        if (on(ctx, "contacts")) {
            add(
                ToolSchema(
                    "contact_search", "Look up people in the phone's contacts by name, phone number or email.",
                    p("query", "string", "A name, number or email, or part of one", required = listOf("query")),
                ),
            )
        }
        if (on(ctx, "email")) {
            add(
                ToolSchema(
                    "email_draft", "Open a draft in the person's mail app. They review it and press Send themselves.",
                    p(
                        "to", "string", "Recipient address",
                        "subject", "string", "Subject",
                        "body", "string", "Message text",
                        required = listOf("to", "subject"),
                    ),
                ),
            )
        }
        if (on(ctx, "clock")) {
            add(
                ToolSchema(
                    "alarm_set", "Set an alarm in the phone's Clock app.",
                    p(
                        "hour", "integer", "0 to 23", "minute", "integer", "0 to 59", "label", "string", "What it is for",
                        required = listOf("hour", "minute"),
                    ),
                ),
            )
            add(
                ToolSchema(
                    "timer_set", "Start a countdown timer in the phone's Clock app.",
                    p("seconds", "integer", "Length in seconds", "label", "string", "What it is for", required = listOf("seconds")),
                ),
            )
        }
    }

    /** Tools here that change something, so the person approves each call. */
    val needsApproval = setOf("calendar_add", "email_draft", "alarm_set", "timer_set")
    val names = setOf("calendar_list", "calendar_add", "contact_search", "email_draft", "alarm_set", "timer_set")

    fun describe(name: String, a: JsonObject): String? = when (name) {
        "calendar_add" -> "Add “${a.s("title")}” to your calendar at ${a.s("start")}"
        "email_draft" -> "Draft an email to ${a.s("to")}: ${a.s("subject")}"
        "alarm_set" -> "Set an alarm for %02d:%02d".format(a.n("hour"), a.n("minute")) + a.s("label").let { if (it.isEmpty()) "" else " ($it)" }
        "timer_set" -> "Start a ${a.n("seconds")}-second timer"
        else -> null
    }

    fun run(ctx: Context, name: String, a: JsonObject): String = try {
        // A model can name a tool it wasn't offered (an old chat, a guess, injected text), so check again here.
        val appId = appOf[name]
        if (appId == null || !on(ctx, appId)) return "That isn't turned on. The person can turn it on in Settings → Integrations → On this phone."
        when (name) {
            "calendar_list" -> calendarList(ctx, a)
            "calendar_add" -> calendarAdd(ctx, a)
            "contact_search" -> contactSearch(ctx, a.s("query"))
            "email_draft" -> emailDraft(ctx, a)
            "alarm_set" -> alarm(ctx, a)
            "timer_set" -> timer(ctx, a)
            else -> "Unknown tool $name."
        }
    } catch (e: SecurityException) {
        "Android has taken back the permission for this. Ask the person to turn it on again in Settings → Integrations."
    } catch (e: Exception) {
        "That didn't work: ${e.message ?: e.javaClass.simpleName}"
    }

    // Arguments from a hosted provider reach us still wrapped in quotes; plainText unwraps them.
    private fun JsonObject.s(k: String) = plainText(this[k]?.jsonPrimitive?.contentOrNull.orEmpty())
    private fun JsonObject.n(k: String) = plainText(this[k]?.jsonPrimitive?.contentOrNull.orEmpty()).toDoubleOrNull()?.toInt() ?: 0

    /** A time as the model wrote it: a date, or a date with a time, in this phone's zone. */
    internal fun parse(raw: String, zone: ZoneId = ZoneId.systemDefault(), allDayZone: ZoneId = ZoneId.of("UTC")): Pair<Long, Boolean>? {
        val s = raw.trim().replace(' ', 'T')
        if (s.isEmpty()) return null
        runCatching { return LocalDateTime.parse(s.removeSuffix("Z")).atZone(zone).toInstant().toEpochMilli() to false }
        runCatching { return LocalDate.parse(s).atStartOfDay(allDayZone).toInstant().toEpochMilli() to true }
        runCatching { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() to false }
        return null
    }

    // DateTimeFormatter is thread-safe, and bots run on several threads.
    private fun shown(ms: Long) = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
    private fun clock(ms: Long) = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
    /** All-day events are stored at midnight UTC, so read them back in UTC. */
    private fun day(ms: Long) = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))

    private fun calendarList(ctx: Context, a: JsonObject): String {
        val local = ZoneId.systemDefault()
        val from = parse(a.s("from"), local, local)?.first ?: System.currentTimeMillis()
        val to = parse(a.s("to"), local, local)?.first ?: (from + 7 * 24 * 3600 * 1000L)
        val query = a.s("query").lowercase()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from); ContentUris.appendId(it, to)
        }.build()
        val cols = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = mutableListOf<String>()
        ctx.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 40) {
                val title = c.getString(0).orEmpty().ifEmpty { "(no title)" }
                if (query.isNotEmpty() && !title.lowercase().contains(query)) continue
                val allDay = c.getInt(3) == 1
                val begin = c.getLong(1)
                val end = c.getLong(2)
                val whenText = if (allDay) day(begin) + " (all day)"
                else shown(begin) + "–" + clock(end)
                val place = c.getString(4).orEmpty()
                out += "• $whenText · $title" + (if (place.isNotEmpty()) " · $place" else "") + " [${c.getString(5).orEmpty()}]"
            }
        }
        return if (out.isEmpty()) "No events in that time." else out.joinToString("\n")
    }

    private fun calendarAdd(ctx: Context, a: JsonObject): String {
        val title = a.s("title").ifEmpty { return "Give the event a title." }
        val (start, allDay) = parse(a.s("start")) ?: return "Give the start like 2026-11-20T15:00."
        val end = parse(a.s("end"))?.first?.takeIf { it > start } ?: (start + if (allDay) 24 * 3600 * 1000L else 3600 * 1000L)
        var calId = -1L
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (calId < 0 || c.getInt(1) == 1) calId = c.getLong(0)
            }
        }
        if (calId < 0) return "No calendar on this phone can take new events. Sign in to one in the Calendar app first."
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
            a.s("location").takeIf { it.isNotEmpty() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            a.s("notes").takeIf { it.isNotEmpty() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: return "The calendar refused the event."
        return "Added “$title” on ${if (allDay) day(start) else shown(start)}."
    }

    private fun contactSearch(ctx: Context, q: String): String {
        if (q.isEmpty()) return "Give a name, number or email."
        val people = linkedMapOf<Long, Triple<String, MutableSet<String>, MutableSet<String>>>()
        fun person(id: Long, name: String) = people.getOrPut(id) { Triple(name, linkedSetOf(), linkedSetOf()) }
        val like = "%$q%"
        // Numbers are stored formatted, like +1 (555) 123-4567, so match the digits too.
        val digits = q.filter { it.isDigit() || it == '+' }
        val numberLike = if (digits.count { it.isDigit() } >= 3) "%$digits%" else like
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER} LIKE ?",
            arrayOf(like, like, numberLike), null,
        )?.use { c -> while (c.moveToNext() && people.size < 15) person(c.getLong(0), c.getString(1).orEmpty()).second += c.getString(2).orEmpty() }
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS),
            "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Email.ADDRESS} LIKE ?",
            arrayOf(like, like), null,
        )?.use { c -> while (c.moveToNext() && people.size < 15) person(c.getLong(0), c.getString(1).orEmpty()).third += c.getString(2).orEmpty() }
        if (people.isEmpty()) return "Nobody in the contacts matches “$q”."
        // A match on a name should show all that person's numbers and emails.
        people.forEach { (id, t) ->
            if (t.second.isEmpty()) ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(id.toString()), null,
            )?.use { c -> while (c.moveToNext()) t.second += c.getString(0).orEmpty() }
            if (t.third.isEmpty()) ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?", arrayOf(id.toString()), null,
            )?.use { c -> while (c.moveToNext()) t.third += c.getString(0).orEmpty() }
        }
        return people.values.joinToString("\n") { (name, phones, emails) ->
            "• $name" + (if (phones.isNotEmpty()) " · " + phones.joinToString(", ") else "") + (if (emails.isNotEmpty()) " · " + emails.joinToString(", ") else "")
        }
    }

    private fun inForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /**
     * Android silently blocks an activity started from the background, with no
     * error, so never report success from there. Leave a notification the
     * person can tap instead, and say so. Returns null when it opened for real.
     */
    private fun launch(ctx: Context, i: Intent, what: String): String? {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (inForeground()) return try { ctx.startActivity(i); null } catch (_: Exception) { "Nothing on this phone could open that." }
        val tap = PendingIntent.getActivity(ctx, System.currentTimeMillis().toInt(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, Alerts.CH_ALERTS)
            .setSmallIcon(dev.gbot.app.R.drawable.ic_notify)
            .setContentTitle("gbot needs a tap").setContentText(what)
            .setContentIntent(tap).setAutoCancel(true).build()
        val posted = runCatching { NotificationManagerCompat.from(ctx).notify(System.currentTimeMillis().toInt(), n) }.isSuccess
        return if (posted) "gbot is in the background, so Android won't let it open this. A notification is waiting; the person taps it to finish. It is NOT done yet — don't say it is."
        else "gbot is in the background and Android blocked it. Nothing was done; ask the person to open gbot and try again."
    }

    private fun emailDraft(ctx: Context, a: JsonObject): String {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(a.s("to")))
            putExtra(Intent.EXTRA_SUBJECT, a.s("subject"))
            putExtra(Intent.EXTRA_TEXT, a.s("body"))
        }
        return launch(ctx, i, "Open the email draft to ${a.s("to")}")
            ?: "The draft is open in the person's mail app. They will review it and press Send; nothing has been sent."
    }

    private fun alarm(ctx: Context, a: JsonObject): String {
        val h = a.n("hour"); val m = a.n("minute")
        if (h !in 0..23 || m !in 0..59) return "Give an hour from 0 to 23 and a minute from 0 to 59."
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.s("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(ctx, i, "Set the %02d:%02d alarm".format(h, m)) ?: "Alarm set for %02d:%02d.".format(h, m)
    }

    private fun timer(ctx: Context, a: JsonObject): String {
        val s = a.n("seconds")
        if (s !in 1..86400) return "Give a length from 1 second to 24 hours."
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_MESSAGE, a.s("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(ctx, i, "Start the $s-second timer") ?: "Timer started for $s seconds."
    }
}
