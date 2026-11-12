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
    /** A chat a notification asked to open. */
    val openRequest = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openRequest.value = intent?.getStringExtra(EXTRA_CHAT)
        setContent { GbotTheme { AppRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_CHAT)?.let { openRequest.value = it }
    }

    companion object { const val EXTRA_CHAT = "chat" }
}
