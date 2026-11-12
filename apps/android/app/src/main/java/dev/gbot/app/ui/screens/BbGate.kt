package dev.gbot.app.ui.screens

import android.annotation.SuppressLint
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.gbot.app.GbotApp
import dev.gbot.app.net.SetupProbe
import dev.gbot.app.ui.IconBtn
import dev.gbot.app.ui.LocalG
import dev.gbot.app.ui.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * BB Connect share links need a getbb.app session in the HTTP client.
 * This WebView runs that sign-in, then copies cookies into OkHttp so
 * the rest of the app can call gbot through the bb URL.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BbSignInGate(server: String, onDone: () -> Unit, onCancel: () -> Unit) {
    val app = GbotApp.instance
    val g = LocalG.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("Sign in to bb to open your gbot link") }
    var finishing by remember { mutableStateOf(false) }

    val startUrl = remember(server) {
        val returnTo = URLEncoder.encode("$server/api/setup", "UTF-8")
        "https://getbb.app/dashboard?returnTo=$returnTo"
    }

    fun syncCookies() {
        val cm = CookieManager.getInstance()
        cm.flush()
        // Share host + apex: tunnel auth may live on either.
        app.http.importBrowserCookies(server, cm.getCookie(server))
        app.http.importBrowserCookies("$server/", cm.getCookie("$server/"))
        app.http.importBrowserCookies("https://getbb.app", cm.getCookie("https://getbb.app"))
        app.http.importBrowserCookies("https://getbb.app/", cm.getCookie("https://getbb.app/"))
        val host = android.net.Uri.parse(server).host.orEmpty()
        if (host.contains('.')) {
            val apex = host.split('.').takeLast(2).joinToString(".")
            app.http.importBrowserCookies("https://$apex", cm.getCookie("https://$apex"))
        }
    }

    fun tryFinish(from: String) {
        if (finishing) return
        finishing = true
        status = "Checking gbot…"
        scope.launch {
            syncCookies()
            // Give the tunnel a moment after the dashboard redirect.
            delay(400)
            syncCookies()
            var probe = app.http.probeSetup(server)
            if (probe != SetupProbe.Ok) {
                delay(800)
                syncCookies()
                probe = app.http.probeSetup(server)
            }
            if (probe == SetupProbe.Ok) {
                status = "Connected"
                onDone()
            } else {
                finishing = false
                status = if (from.contains("getbb.app/dashboard")) {
                    "Still needs bb sign-in — finish signing in above"
                } else {
                    "Couldn't reach gbot through bb yet. Stay signed in and wait a moment."
                }
            }
        }
    }

    BackHandler(onBack = onCancel)

    Column(Modifier.fillMaxSize().background(g.bg).statusBarsPadding()) {
        IconBtn("close", "Cancel", onCancel, tint = g.ink, modifier = Modifier.padding(4.dp))
        Text(status, style = T.small, color = g.ink2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (loading || finishing) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    if (Build.VERSION.SDK_INT >= 21) {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                        override fun onPageFinished(view: WebView, url: String) {
                            loading = false
                            syncCookies()
                            val u = url.lowercase()
                            // Back on the share host after dashboard login, or setup JSON rendered.
                            if (u.startsWith(server.lowercase()) || u.contains("/api/setup")) {
                                tryFinish(url)
                            }
                        }
                    }
                    loadUrl(startUrl)
                }
            },
            modifier = Modifier.fillMaxSize().weight(1f),
        )
    }
}
