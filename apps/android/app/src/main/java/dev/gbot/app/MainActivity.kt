package dev.gbot.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import dev.gbot.app.ui.AppRoot
import dev.gbot.app.ui.GbotTheme

class MainActivity : ComponentActivity() {
    /** A chat a notification, shortcut or link asked to open. */
    val openRequest = MutableStateFlow<String?>(null)

    /** Text shared from another app, waiting for a bot to send it to. */
    val shareRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        read(intent)
        setContent { GbotTheme { AppRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    /** A notification or shortcut names a chat; a share carries text. */
    private fun read(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_CHAT)?.let { openRequest.value = it; return }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val text = listOf(subject, body).filter { it.isNotBlank() }.joinToString("\n\n")
            if (text.isNotBlank()) shareRequest.value = text
            return
        }
        val link = intent.data
        if (intent.action == Intent.ACTION_VIEW && link?.scheme == "gbot") {
            // gbot://chat/<id>, or gbot://<id>.
            val id = link.pathSegments?.lastOrNull().orEmpty().ifEmpty { link.host.orEmpty() }
            if (id.isNotEmpty()) openRequest.value = id
        }
    }

    companion object { const val EXTRA_CHAT = "chat" }
}
