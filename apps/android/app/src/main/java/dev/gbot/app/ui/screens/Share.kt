package dev.gbot.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gbot.app.net.Bot
import dev.gbot.app.ui.Gbot
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.T
import dev.gbot.app.ui.pressable

/**
 * Text arrived from another app's share sheet. Pick who gets it; the chat
 * opens with it already in the message box, so nothing is sent by mistake.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePicker(bots: List<Bot>, text: String, onPick: (Bot) -> Unit, onDismiss: () -> Unit) {
    val g = LocalG.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = if (g.dark) Color(0xFF262626) else Color.White) {
        Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            Text("Send to", style = T.h2, color = g.ink, modifier = Modifier.padding(start = 10.dp))
            Text(
                text.replace(Regex("\\s+"), " ").trim(),
                style = T.small, color = g.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 8.dp),
            )
            if (bots.isEmpty()) {
                Text("Hatch a bot first.", style = T.body, color = g.ink2, modifier = Modifier.padding(10.dp))
                return@Column
            }
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(bots, key = { it.id }) { b ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable { onPick(b) }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Gbot(b.look, b.id, 36)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.fillMaxWidth()) {
                            Text(b.name, style = T.body.copy(fontWeight = FontWeight.SemiBold), color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (b.title.isNotEmpty()) Text(b.title, style = T.small, color = g.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
