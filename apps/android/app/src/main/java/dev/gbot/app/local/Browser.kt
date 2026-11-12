package dev.gbot.app.local

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * The bot's window on the web: one real Chromium WebView that it drives
 * through the DOM. The same view is what you see on the Browser screen, so
 * taking over is just touching the page.
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserController(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var page: CompletableDeferred<Unit>? = null

    val url = MutableStateFlow("")
    val title = MutableStateFlow("")
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** Set by the Browser screen so file pickers (a résumé, say) open. */
    var chooseFiles: ((Array<String>, (Array<Uri>?) -> Unit) -> Unit)? = null

    fun view(): WebView {
        web?.let { return it }
        val w = WebView(ctx)
        // AndroidView won't size a view that has no layout params for us.
        w.layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = true
        w.settings.userAgentString = w.settings.userAgentString
            ?.replace("; wv", "") // some sites treat the WebView token as a bot
            ?.replace(Regex("Version/\\d+\\.\\d+ "), "")
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) {
                loading.value = true
                error.value = null
                if (!u.isNullOrEmpty()) url.value = u
            }

            override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest?, err: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame != true) return
                val text = err?.description?.toString()?.ifBlank { null } ?: "The page couldn't be loaded"
                error.value = text
                Log.w("gbot-browser", "load failed: ${request.url} — $text")
            }

            override fun onPageFinished(view: WebView, u: String?) {
                loading.value = false
                if (!u.isNullOrEmpty()) url.value = u
                title.value = view.title.orEmpty()
                view.evaluateJavascript(HELPER, null)
                page?.complete(Unit)
            }
        }
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: android.webkit.ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                val pick = chooseFiles ?: return false
                pick(params.acceptTypes ?: arrayOf("*/*")) { uris -> callback.onReceiveValue(uris) }
                return true
            }
        }
        web = w
        return w
    }

    fun destroy() {
        web?.destroy()
        web = null
        url.value = ""
        title.value = ""
        loading.value = false
        error.value = null
    }

    suspend fun open(raw: String): String {
        ensure()
        val target = normalize(raw)
        error.value = null
        Log.i("gbot-browser", "open $target")
        val done = CompletableDeferred<Unit>()
        page = done
        main.post { web?.loadUrl(target) }
        withTimeoutOrNull(30_000) { done.await() }
        error.value?.let { e ->
            return """{"ok":false,"url":"${json(target)}","error":"${json(e)}"}"""
        }
        return snapshot()
    }

    suspend fun back(): String {
        ensure()
        eval("history.back()")
        val done = CompletableDeferred<Unit>()
        page = done
        withTimeoutOrNull(15_000) { done.await() }
        return snapshot()
    }

    suspend fun forward(): String {
        ensure()
        eval("history.forward()")
        val done = CompletableDeferred<Unit>()
        page = done
        withTimeoutOrNull(15_000) { done.await() }
        return snapshot()
    }

    suspend fun reload(): String {
        ensure()
        val done = CompletableDeferred<Unit>()
        page = done
        main.post { web?.reload() }
        withTimeoutOrNull(30_000) { done.await() }
        return snapshot()
    }

    /** Scroll most of a screen, let lazy content load, then read again. */
    suspend fun scroll(down: Boolean): String {
        ensure()
        eval("window.scrollBy(0, ${if (down) "" else "-"}Math.round(window.innerHeight * 0.85))")
        kotlinx.coroutines.delay(600)
        return snapshot()
    }

    suspend fun waitFor(seconds: Int): String {
        kotlinx.coroutines.delay(seconds.coerceIn(1, 10) * 1000L)
        return snapshot()
    }

    /** Title, URL, visible text and the things that can be clicked or typed into. */
    suspend fun snapshot(): String {
        ensure()
        error.value?.let { e ->
            return """{"ok":false,"url":"${json(url.value)}","error":"${json(e)}"}"""
        }
        val raw = eval("window.__gbotSnap ? __gbotSnap() : ''")
        return raw.ifBlank { """{"url":"${json(url.value)}","text":"the page has not finished loading","elements":[]}""" }
    }

    suspend fun click(id: Int): String = eval("__gbotClick($id)")
    suspend fun fill(id: Int, value: String): String = eval("__gbotFill($id, ${quote(value)})")
    suspend fun pressEnter(id: Int): String = eval("__gbotEnter($id)")

    private suspend fun ensure() {
        if (web != null) return
        val ready = CompletableDeferred<Unit>()
        main.post { runCatching { view() }; ready.complete(Unit) }
        ready.await()
    }

    private suspend fun eval(js: String): String = withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { cont ->
            main.post {
                val w = web
                if (w == null) { cont.resume(""); return@post }
                w.evaluateJavascript(if (js.startsWith("(") || js.startsWith("window.")) js else "($js)") { result ->
                    if (cont.isActive) cont.resume(unquote(result))
                }
            }
        }
    } ?: ""

    private fun json(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unquote(result: String?): String {
        if (result.isNullOrEmpty() || result == "null") return ""
        return runCatching { Json.parseToJsonElement(result).jsonPrimitive.content }.getOrElse { result }
    }

    companion object {
        fun normalize(raw: String): String {
            val t = sanitize(raw)
            return when {
                t.isEmpty() -> "about:blank"
                t.startsWith("http://") || t.startsWith("https://") || t.startsWith("about:") -> t
                t.startsWith("//") -> "https:$t"
                t.contains(' ') || !t.contains('.') -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
                else -> "https://$t"
            }
        }

        /** Undo the quoting and near-miss schemes models copy from their own text. */
        private fun sanitize(raw: String): String {
            var t = raw.trim()
            t = t.replace("%22", "").replace("%27", "").replace("%3C", "").replace("%3E", "").replace("%3c", "").replace("%3e", "")
            t = t.replace("\"", "").replace("“", "").replace("”", "")
            t = t.trim('\'', '`', '<', '>', '‘', '’', ' ', '\n', '\t', '.', ',', ')', ']')
            // https//host, https:host and https:///host all mean https://host.
            t = t.replace(Regex("(?i)^(https?)[:/]+"), "$1://")
            return t.trim()
        }

        fun quote(s: String): String = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(s))

        // Runs in the page. Marks what can be acted on so the model can point
        // at it by a small number instead of guessing selectors.
        const val HELPER = """
(function(){
  if (window.__gbotSnap) return;
  var n = 0;
  function visible(el){
    var r = el.getBoundingClientRect();
    var s = getComputedStyle(el);
    return r.width > 4 && r.height > 4 && s.visibility !== 'hidden' && s.display !== 'none' && r.bottom > -200 && r.top < 4000;
  }
  function mark(){
    var all = document.querySelectorAll('a,button,input,textarea,select,[role=button],[contenteditable=true],label');
    for (var i=0;i<all.length;i++){ if(!all[i].dataset.gbot) all[i].dataset.gbot = String(++n); }
  }
  function label(el){
    var t = el.innerText || el.value || el.getAttribute('aria-label') || el.placeholder || el.name || '';
    t = String(t).replace(/\s+/g,' ').trim();
    return t.slice(0,140);
  }
  window.__gbotSnap = function(){
    mark();
    var els = document.querySelectorAll('[data-gbot]');
    var out = [];
    for (var i=0;i<els.length && out.length<80;i++){
      var el = els[i];
      if (!visible(el)) continue;
      out.push({id:+el.dataset.gbot, tag:el.tagName.toLowerCase(), label:label(el), type:el.type||'', href:el.href||''});
    }
    var body = document.body ? document.body.innerText : '';
    return JSON.stringify({title:document.title, url:location.href, text:String(body).replace(/\n{3,}/g,'\n\n').slice(0,6000), elements:out});
  };
  window.__gbotClick = function(id){
    var el = document.querySelector('[data-gbot="'+id+'"]');
    if (!el) return JSON.stringify({ok:false, error:'no element '+id});
    el.scrollIntoView({block:'center'});
    el.click();
    return JSON.stringify({ok:true, clicked:id});
  };
  window.__gbotFill = function(id, v){
    var el = document.querySelector('[data-gbot="'+id+'"]');
    if (!el) return JSON.stringify({ok:false, error:'no element '+id});
    el.scrollIntoView({block:'center'});
    el.focus();
    if (el.tagName === 'SELECT') { el.value = v; }
    else {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      var set = Object.getOwnPropertyDescriptor(proto, 'value');
      if (set && set.set) set.set.call(el, v); else el.value = v;
    }
    el.dispatchEvent(new Event('input', {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
    return JSON.stringify({ok:true, filled:id});
  };
  window.__gbotEnter = function(id){
    var el = document.querySelector('[data-gbot="'+id+'"]');
    if (!el) return JSON.stringify({ok:false, error:'no element '+id});
    el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
    if (el.form) el.form.requestSubmit();
    return JSON.stringify({ok:true});
  };
})()
"""
    }
}
