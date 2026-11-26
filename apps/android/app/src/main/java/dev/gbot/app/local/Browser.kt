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

    /** The bot's visible hand: the cursor, ring and labels. Same file as on the computer. */
    private val fxJs: String by lazy {
        runCatching { ctx.assets.open("gbot-fx.js").bufferedReader().use { it.readText() } }.getOrDefault("")
    }

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
                if (fxJs.isNotEmpty()) view.evaluateJavascript(fxJs, null)
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
        // Banners often arrive a moment after the page; give them time to show.
        kotlinx.coroutines.delay(1000)
        eval("window.__gbotFx && window.__gbotFx.note('Opened ' + (location.hostname || 'the page'))")
        return snapshot()
    }

    /**
     * Accepts cookie and consent banners so the window the person watches shows
     * the page. Up to three in a row, since sites often stack a second one.
     * The "already acted on" marks only guard this pass; they are removed at
     * the end, so a banner that survives can be handled by a later step.
     */
    private suspend fun clearBanners() {
        var clicked = false
        try {
            repeat(3) {
                val label = eval("window.__gbotBanners ? __gbotBanners() : ''")
                if (label.isBlank()) return
                clicked = true
                kotlinx.coroutines.delay(400)
            }
        } finally {
            // Marks are only set by a click, so a step with no clicks skips the page walk.
            if (clicked) eval("window.__gbotUnmark ? __gbotUnmark() : ''")
        }
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
        if (fxJs.isNotEmpty()) {
            eval("window.__gbotFx && window.__gbotFx.ring(null)")
            eval("window.__gbotFx && window.__gbotFx.move(innerWidth * 0.62, innerHeight * 0.55, 'Scrolling ${if (down) "↓" else "↑"}')")
        }
        // Smooth, so the person watching sees the page move instead of jump.
        eval("window.scrollBy({top: ${if (down) "" else "-"}Math.round(window.innerHeight * 0.85), behavior: 'smooth'})")
        kotlinx.coroutines.delay(if (fxJs.isNotEmpty()) 900 else 600)
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
        // Otherwise it reads as "still loading" and the model waits on a window that never opened.
        if (url.value.isBlank() && !loading.value) {
            return """{"ok":false,"url":"","error":"Nothing is open in the browser yet. Use browser_open with a web address first."}"""
        }
        clearBanners()
        val raw = eval("window.__gbotSnap ? __gbotSnap() : ''")
        return raw.ifBlank { """{"url":"${json(url.value)}","text":"the page has not finished loading","elements":[]}""" }
    }

    /** Scroll to the element, glide the cursor to it and ring it, so the step can be watched. */
    private suspend fun fxStep(id: Int, text: String, press: Boolean) {
        if (fxJs.isEmpty()) return
        // Out of view: scroll to it (the label says which way), and check again, up to three times.
        eval("window.__gbotFxTry = null")  // each step starts fresh: only a scroll made in this step counts
        for (attempt in 0 until 3) {
            if (!eval("__gbotFxReveal($id)").contains("1")) break
            kotlinx.coroutines.delay(750)
        }
        if (!eval("__gbotFxTo($id, ${quote(text)})").contains("1")) return
        kotlinx.coroutines.delay(520)
        if (press) {
            eval("__gbotFxClick()")
            kotlinx.coroutines.delay(150)
        }
    }

    suspend fun click(id: Int): String {
        fxStep(id, "Clicking", true)
        return eval("__gbotClick($id)")
    }

    /**
     * Fills a field. With [watch], short text is typed a key at a time so the person
     * sees it; saved secrets never are (they are set in one step, as on the computer).
     */
    suspend fun fill(id: Int, value: String, watch: Boolean = true): String {
        fxStep(id, "Typing", false)
        // The fill below makes sure the field ends up exact, and stops any typing still going.
        if (watch && fxJs.isNotEmpty() && value.length in 1..240 && '\n' !in value) {
            val ms = (1800 / value.length).coerceIn(8, 30)
            eval("__gbotFxType($id, ${quote(value)}, $ms)")
            kotlinx.coroutines.delay(value.length * ms + 80L)
        }
        return eval("__gbotFill($id, ${quote(value)})")
    }

    suspend fun pressEnter(id: Int): String {
        eval("window.__gbotFx && window.__gbotFx.say('Pressing Enter')")
        return eval("__gbotEnter($id)")
    }

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
  // Removes the data-gbot-done marks from the page and any open shadow roots.
  window.__gbotUnmark = function(){
    (function walk(root){
      var marked = root.querySelectorAll('[data-gbot-done]');
      for (var i = 0; i < marked.length; i++) marked[i].removeAttribute('data-gbot-done');
      var all = root.querySelectorAll('*');
      for (var j = 0; j < all.length; j++) if (all[j].shadowRoot) walk(all[j].shadowRoot);
    })(document);
    return '';
  };
  // Accepts a cookie or consent banner (reject or close only when nothing
  // accepts), so the page underneath is visible. Marketing copy is never clicked.
  window.__gbotBanners = function(){
    // Accept only whole cookie-accept labels, and only in a banner that says
    // "cookie". Substring matches would press "Disagree" or "OK" on a terms
    // or confirm dialog, which agrees for the person.
    var ACCEPT = ['accept', 'accept all', 'accept cookies', 'accept all cookies', 'allow', 'allow all', 'allow cookies', 'allow all cookies', 'got it'];
    // Once a control in a banner is clicked, the banner's container is marked
    // data-gbot-done. Later passes leave every control in a marked banner alone.
    function doneBanner(el){
      var n = el;
      while (n) {
        if (n.nodeType === 1 && n.hasAttribute('data-gbot-done')) return true;
        n = n.parentElement || (n.getRootNode && n.getRootNode().host) || null;
      }
      return false;
    }
    function markBanner(el){
      var n = el;
      while (n) {
        if (n.nodeType === 1 && overlay(n)) { n.setAttribute('data-gbot-done', '1'); return; }
        n = n.parentElement || (n.getRootNode && n.getRootNode().host) || null;
      }
      el.setAttribute('data-gbot-done', '1');
    }
    var LEAST = /(reject|decline|deny|refuse|only necessary|necessary only|essential only|use necessary|continue without)/i;
    var CLOSE = /(close|dismiss|×|✕)/i;
    var MARKET = /(subscribe|subscription|donate|donation|support us|membership|newsletter|offer|deal|trial|shop|buy|upgrade|premium)/i;
    function overlay(n){
      var s = getComputedStyle(n);
      return s.position === 'fixed' || s.position === 'sticky' || n.getAttribute('role') === 'dialog' || n.tagName === 'DIALOG';
    }
    // The keyword ("cookie", "consent" or "privacy") of the banner around el, or ''.
    function keywordOf(el){
      var n = el;
      while (n) {
        if (n.nodeType === 1 && overlay(n)) {
          var t = (n.innerText || '').toLowerCase();
          if (t.indexOf('cookie') >= 0) return 'cookie';
          if (t.indexOf('consent') >= 0 || t.indexOf('privacy') >= 0) return 'consent';
        }
        n = n.parentElement || (n.getRootNode && n.getRootNode().host) || null;
      }
      return '';
    }
    var cands = [];
    var btns = document.querySelectorAll('button,[role=button],input[type=button],input[type=submit],input[type=reset]');
    for (var i = 0; i < btns.length; i++) {
      var b = btns[i];
      var r = b.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) continue;
      // A banner already acted on is left alone, so a reject or close in the
      // same banner is not clicked after its accept.
      if (doneBanner(b)) continue;
      // A submit or reset control, or one inside a form, can send the form.
      // type="button" never submits, so it is always safe to click.
      var type = (b.getAttribute('type') || '').toLowerCase();
      if (type === 'submit' || type === 'reset') continue;
      if (type !== 'button' && b.closest('form')) continue;
      var t = String(b.innerText || b.value || b.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
      if (!t || t.length > 80 || MARKET.test(t)) continue;
      if (b.closest('a[href]')) continue;
      // Automatic clicks only touch cookie banners. A privacy or consent prompt
      // without the word "cookie" is left for the person.
      var kw = keywordOf(b);
      if (kw !== 'cookie') continue;
      cands.push({el: b, label: t, kw: kw});
    }
    function firstAccept(){
      for (var k = 0; k < cands.length; k++) {
        var c = cands[k];
        var label = c.label.toLowerCase().replace(/[.!]+/g, '').trim();
        if (c.kw === 'cookie' && ACCEPT.indexOf(label) >= 0) return c;
      }
      return null;
    }
    function first(re){
      for (var k = 0; k < cands.length; k++) if (re.test(cands[k].label)) return cands[k];
      return null;
    }
    var pick = firstAccept() || first(LEAST) || first(CLOSE);
    if (!pick) return '';
    pick.el.click();
    markBanner(pick.el);
    return pick.label;
  };
  // The bot's visible hand (gbot-fx.js): scroll to an element, glide to it, type into it.
  function byId(id){ return document.querySelector('[data-gbot="'+id+'"]'); }
  window.__gbotFxReveal = function(id){
    var el = byId(id);
    if (!el) return '';
    var r = el.getBoundingClientRect();
    // What the person sees is the visual viewport (smaller and offset when zoomed).
    var vv = window.visualViewport;
    var vl = vv ? vv.offsetLeft : 0, vt = vv ? vv.offsetTop : 0;
    var vr = vl + (vv ? vv.width : innerWidth), vb = vt + (vv ? vv.height : innerHeight);
    // Out of view means not fully visible, so a header or footer target that is on screen is never
    // "scrolled to". One bigger than the view only needs its middle in view: that is where the
    // cursor goes and where the tap lands.
    var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    var tall = r.height > vb - vt, wide = r.width > vr - vl;
    var up = tall ? cy < vt : r.top < vt, down = tall ? cy > vb : r.bottom > vb;
    var left = wide ? cx < vl : r.left < vl, right = wide ? cx > vr : r.right > vr;
    var way = (up ? '\u2191' : down ? '\u2193' : '') + (left ? '\u2190' : right ? '\u2192' : '');
    if (!way) { window.__gbotFxTry = null; return ''; }
    // The last scroll to this element changed nothing (it can't go further): don't announce another.
    var pos = r.top * 10000 + r.left, now = Date.now(), last = window.__gbotFxTry;
    window.__gbotFxTry = {id: id, pos: pos, at: now};
    if (last && last.id === id && last.pos === pos && now - last.at < 4000) return '';
    if (window.__gbotFx) { window.__gbotFx.ring(null); window.__gbotFx.move(vl + (vr - vl) * 0.62, vt + (vb - vt) * 0.55, 'Scrolling ' + way); }
    // Only the axis that needs it is centred, so carousels and tab strips aren't shifted needlessly.
    el.scrollIntoView({block: (up || down) ? 'center' : 'nearest', inline: (left || right) ? 'center' : 'nearest', behavior:'smooth'});
    return '1';
  };

  window.__gbotFxTo = function(id, text){
    var el = byId(id);
    if (!el || !window.__gbotFx) return '';
    window.__gbotFx.toElement(el, text);
    return '1';
  };
  window.__gbotFxClick = function(){
    if (window.__gbotFx) window.__gbotFx.click();
    return '';
  };
  window.__gbotFxStop = function(){
    if (window.__gbotFxTimer) { clearInterval(window.__gbotFxTimer); window.__gbotFxTimer = 0; }
  };
  window.__gbotFxType = function(id, text, ms){
    window.__gbotFxStop();
    var el = byId(id);
    // Only real text fields are typed into; anything else is filled in one step.
    if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA')) return '';
    el.focus();
    var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var set = Object.getOwnPropertyDescriptor(proto, 'value');
    var i = 0;
    window.__gbotFxTimer = setInterval(function(){
      i++;
      try {
        var v = text.slice(0, i);
        if (set && set.set) set.set.call(el, v); else el.value = v;
        el.dispatchEvent(new Event('input', {bubbles:true}));
      } catch (e) { i = text.length; }
      if (i >= text.length) window.__gbotFxStop();
    }, ms);
    return '';
  };
  window.__gbotClick = function(id){
    var el = document.querySelector('[data-gbot="'+id+'"]');
    if (!el) return JSON.stringify({ok:false, error:'no element '+id});
    el.scrollIntoView({block:'center'});
    el.click();
    return JSON.stringify({ok:true, clicked:id});
  };
  window.__gbotFill = function(id, v){
    if (window.__gbotFxStop) window.__gbotFxStop();
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
