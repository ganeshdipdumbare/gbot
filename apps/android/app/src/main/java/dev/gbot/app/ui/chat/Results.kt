package dev.gbot.app.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gbot.app.net.Bot
import dev.gbot.app.net.Message
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.Pictures
import dev.gbot.app.ui.T
import dev.gbot.app.ui.pressable
import dev.gbot.app.ui.shimmer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A list a tool handed back (hotels, products, places), drawn as cards
 * instead of a block of text. It mirrors the web card: every field is what
 * the server kept from the tool's data, the link is https only, and the
 * picture is one gbot fetched and stores as a blob.
 */
@Composable
fun ResultsRow(m: Message, author: Bot?) {
    val g = LocalG.current
    val items = (m.data?.get("items") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(40.dp)) { if (author != null) Gbot(author.look, author.id, 34) }
        Column(Modifier.widthIn(max = 340.dp)) {
            val title = (m.data?.get("title") as? JsonPrimitive)?.contentOrNull ?: m.content
            if (title.isNotEmpty()) Text(title, style = T.tiny, color = g.ink3, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
            items.forEachIndexed { i, it -> ResultCard(m.id + "#" + i, it) }
        }
    }
}

@Composable
private fun ResultCard(key: String, o: JsonObject) {
    val g = LocalG.current
    val uri = LocalUriHandler.current
    val title = text(o, "title") ?: return
    val subtitle = text(o, "subtitle")
    val link = text(o, "link")?.takeIf { it.startsWith("https://") }
    // A picture is a server blob (image) or a file the phone kept (file).
    val blob = text(o, "image")
    val file = text(o, "file")
    val hasPic = blob != null || file != null
    val fields = (o["fields"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    var img by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var missing by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        img = if (hasPic) Pictures.load(key, file, blob) else null
        missing = hasPic && img == null
    }

    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(shape).background(g.surface).border(1.dp, g.line, shape)
            .then(if (link != null) Modifier.pressable { uri.openUri(link) } else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasPic) {
                val pic = img
                // Loading shows a shimmer; a picture that can't be loaded
                // leaves the plain tile, so the card keeps its shape.
                Box(Modifier.size(84.dp).background(g.surface2)) {
                    when {
                        pic != null -> Image(pic, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        !missing -> Box(Modifier.fillMaxSize().shimmer())
                    }
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(title, style = T.body, color = g.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = T.small, color = g.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                fields.forEach { f ->
                    val label = text(f, "label") ?: return@forEach
                    val value = text(f, "value") ?: return@forEach
                    Row {
                        Text(label, style = T.small, color = g.ink3)
                        Spacer(Modifier.width(8.dp))
                        Text(value, style = T.small, color = g.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (link != null) Text("›", style = T.body, color = g.ink3, modifier = Modifier.padding(end = 14.dp))
        }
    }
}

private fun text(o: JsonObject, key: String): String? =
    (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
