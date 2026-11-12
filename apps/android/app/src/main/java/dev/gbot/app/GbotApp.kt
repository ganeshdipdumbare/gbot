package dev.gbot.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.gbot.app.data.Store
import dev.gbot.app.live.Alerts
import dev.gbot.app.live.Events
import dev.gbot.app.live.Live
import dev.gbot.app.live.LiveService
import dev.gbot.app.local.LocalApi
import dev.gbot.app.local.LocalDb
import dev.gbot.app.net.Api
import dev.gbot.app.net.Http
import dev.gbot.app.net.RemoteApi
import dev.gbot.app.net.User
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Where a bot's mind lives: your server, or this phone. */
enum class Mode { Server, Local }

class GbotApp : Application() {
    val scope = MainScope()
    lateinit var http: Http; private set
    lateinit var remote: RemoteApi; private set
    lateinit var api: Api; private set
    lateinit var store: Store; private set
    lateinit var alerts: Alerts; private set
    lateinit var events: Events; private set
    lateinit var live: Live; private set
    lateinit var voice: Voice; private set

    private var localApi: LocalApi? = null
    private var backgroundUnload: Job? = null
    private val prefs by lazy { getSharedPreferences("gbot", Context.MODE_PRIVATE) }

    var mode: Mode
        get() = if (prefs.getString("mode", "server") == "local") Mode.Local else Mode.Server
        private set(v) { prefs.edit().putString("mode", v.name.lowercase()).apply() }

    /** Background alerts: on unless the person turns them off. */
    var alertsEnabled: Boolean
        get() = prefs.getBoolean("alerts", true)
        set(v) {
            prefs.edit().putBoolean("alerts", v).apply()
            if (v && mode == Mode.Server && hasSession()) LiveService.start(this) else LiveService.stop(this)
        }

    /**
     * Light or dark: "system", "light" or "dark". Lazy, because field
     * initializers run before the Application has a context to read prefs
     * from.
     */
    private val _theme by lazy { MutableStateFlow(prefs.getString("theme", "system") ?: "system") }
    val theme: StateFlow<String> get() = _theme

    fun setTheme(v: String) {
        prefs.edit().putString("theme", v).apply()
        _theme.value = v
    }

    fun hasSession() = mode == Mode.Local || prefs.getBoolean("signedIn", false)

    private val _toasts = MutableSharedFlow<Toast>(extraBufferCapacity = 8)
    val toasts = _toasts.asSharedFlow()
    data class Toast(val text: String, val error: Boolean)
    fun toast(text: String, error: Boolean = false) { _toasts.tryEmit(Toast(text, error)) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        http = Http(this)
        remote = RemoteApi(http)
        api = remote
        store = Store(remote, this)
        alerts = Alerts(this, store)
        events = Events(store, alerts)
        live = Live(http, store, scope, alerts, events)
        voice = Voice(this)

        if (mode == Mode.Local) useLocal(load = false)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                backgroundUnload?.cancel()
                backgroundUnload = null
                store.foreground = true
                store.openChat?.let(alerts::clear)
                if (!hasSession()) return
                if (mode == Mode.Server) {
                    if (alertsEnabled) LiveService.start(this@GbotApp)
                    live.start()
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                store.foreground = false
                if (!alertsEnabled) live.stop()
                // Leaving the app shouldn't keep 4 GB of weights around; a
                // short grace keeps a quick switch from forcing a reload.
                if (mode == Mode.Local) {
                    backgroundUnload?.cancel()
                    backgroundUnload = scope.launch {
                        delay(25_000)
                        localApi?.trimIfIdle()
                    }
                }
            }
        })

        scope.launch { http.signedOut.collect { if (mode == Mode.Server) signedOut() } }
    }

    fun signedIn(u: User) {
        prefs.edit().putBoolean("signedIn", true).apply()
        store.setMe(u)
        if (mode == Mode.Server) {
            if (alertsEnabled) LiveService.start(this)
            live.restart()
        }
    }

    fun signedOut() {
        prefs.edit().putBoolean("signedIn", false).apply()
        LiveService.stop(this)
        live.stop()
        http.clearSession()
        store.reset()
    }

    /** Point the app at another server: everything starts fresh. */
    fun changeServer(url: String) {
        signedOut()
        http.server = url
    }

    /** Use the model, memory and browser on this phone. */
    fun useLocal(load: Boolean = true) {
        mode = Mode.Local
        val local = localApi ?: LocalApi(this, LocalDb(this), events, scope).also { localApi = it }
        localApi = local
        api = local
        store.use(local)
        runCatching { live.stop() }
        LiveService.stop(this)
        store.setConnected(true)
        local.start()
        prefs.edit().putBoolean("signedIn", true).apply()
        if (!load) return
        scope.launch {
            runCatching { store.setMe(local.me()) }
            runCatching { store.loadAll() }
            runCatching { store.loadModels() }
        }
    }

    /** Go back to a gbot server. */
    fun useServer(url: String? = null) {
        localApi?.stopLocal()
        mode = Mode.Server
        api = remote
        store.use(remote)
        store.reset()
        store.setConnected(false)
        if (url != null) http.server = url
        prefs.edit().putBoolean("signedIn", false).apply()
    }

    /** Low memory: let go of the model when no reply is in flight. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (mode == Mode.Local && level >= TRIM_MEMORY_RUNNING_LOW) localApi?.trimIfIdle()
    }

    companion object {
        lateinit var instance: GbotApp; private set
    }
}
