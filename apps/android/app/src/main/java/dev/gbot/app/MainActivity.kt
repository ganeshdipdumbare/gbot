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
        // A restored activity, or one reopened from Recents, gets the intent
        // it was first started with again: an old notification or shortcut
        // would reopen a chat that may since have gone. Only a fresh launch
        // reads it.
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) read(intent)
        setContent { GbotTheme { AppRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    /** A notification or shortcut names a chat; a share carries text. */
    private fun read(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_CHAT)?.let { chat ->
            // Read once: the same intent must not open the chat again later.
            intent.removeExtra(EXTRA_CHAT)
            if (chat.isNotBlank()) openRequest.value = chat
            return
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val text = listOf(subject, body).filter { it.isNotBlank() }.joinToString("\n\n")
            if (text.isNotBlank()) shareRequest.value = text
            return
        }
        val link = intent.data
        if (intent.action == Intent.ACTION_VIEW && link?.scheme == "gbot" && link.host == "oauth") {
            // Back from signing in to an app: gbot://oauth/callback?code=…&state=…
            // (gbot://oauth/done only brings the app back after a loopback sign-in).
            if (link.path == "/callback") {
                val params = link.queryParameterNames.associateWith { link.getQueryParameter(it).orEmpty() }
                GbotApp.instance.finishMcpOAuth(params)
            }
            return
        }
        if (intent.action == Intent.ACTION_VIEW && link?.scheme == "gbot") {
            // gbot://chat/<id>, or gbot://<id>.
            val id = link.pathSegments?.lastOrNull().orEmpty().ifEmpty { link.host.orEmpty() }
            if (id.isNotEmpty()) openRequest.value = id
        }
    }

    companion object { const val EXTRA_CHAT = "chat" }
}
