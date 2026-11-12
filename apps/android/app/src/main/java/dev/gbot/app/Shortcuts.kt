package dev.gbot.app

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.gbot.app.net.Bot

/** The bots on the launcher icon's long-press menu. */
object Shortcuts {
    private const val MAX = 4

    /** Pinned bots first, then the most recently active. */
    fun publish(ctx: Context, bots: List<Bot>) {
        val top = bots
            .sortedWith(compareByDescending<Bot> { it.pinned }.thenByDescending { it.lastMessage?.createdAt.orEmpty() })
            .take(MAX)
        val shortcuts = top.map { b ->
            ShortcutInfoCompat.Builder(ctx, "bot:${b.id}")
                .setShortLabel(b.name.ifBlank { "Bot" })
                .setLongLabel(b.title.ifBlank { b.name.ifBlank { "Bot" } })
                .setIcon(IconCompat.createWithResource(ctx, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(ctx, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .putExtra(MainActivity.EXTRA_CHAT, b.id),
                )
                .build()
        }
        // A full shortcut list, a read-only launcher or a rate limit are all
        // the launcher's business, not something to interrupt a chat for.
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(ctx, shortcuts) }
    }
}
