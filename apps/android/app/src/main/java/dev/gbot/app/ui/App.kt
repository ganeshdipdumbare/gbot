package dev.gbot.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.gbot.app.GbotApp
import dev.gbot.app.Mode
import dev.gbot.app.MainActivity
import dev.gbot.app.Shortcuts
import dev.gbot.app.net.ApiError
import dev.gbot.app.ui.chat.ChatScreen
import dev.gbot.app.ui.screens.BotSettingsScreen
import dev.gbot.app.ui.screens.ComputerScreen
import dev.gbot.app.ui.screens.GroupSettingsScreen
import dev.gbot.app.ui.screens.HatchScreen
import dev.gbot.app.ui.screens.HomeScreen
import dev.gbot.app.ui.screens.LoginScreen
import dev.gbot.app.ui.screens.NewGroupScreen
import dev.gbot.app.ui.screens.ServerScreen
import dev.gbot.app.ui.screens.SharePicker
import dev.gbot.app.ui.settings.SettingsHome
import dev.gbot.app.ui.settings.SettingsTab
import kotlinx.coroutines.delay

object R {
    const val SERVER = "server"
    const val LOGIN = "login"
    const val HOME = "home"
    fun chat(k: String, prefill: String = "") = "chat/${Uri.encode(k)}" + if (prefill.isEmpty()) "" else "?prefill=${Uri.encode(prefill)}"
    fun bot(id: String) = "bot/$id"
    fun group(id: String) = "group/$id"
    fun computer(id: String, take: Boolean = false) = "computer/$id?take=$take"
    const val HATCH = "hatch"
    const val NEW_GROUP = "newgroup"
    const val SETTINGS = "settings"
    fun settings(tab: String) = "settings/$tab"
}

fun NavHostController.home() = navigate(R.HOME) { popUpTo(0) { inclusive = true } }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(activity: MainActivity) {
    val app = GbotApp.instance
    val nav = rememberNavController()
    val start = when {
        app.mode == Mode.Local -> R.HOME
        app.http.server == null -> R.SERVER
        app.hasSession() -> R.HOME
        else -> R.LOGIN
    }

    // A session that ends anywhere goes back to sign in.
    LaunchedEffect(Unit) {
        app.http.signedOut.collect {
            if (nav.currentDestination?.route != R.LOGIN) nav.navigate(R.LOGIN) { popUpTo(0) { inclusive = true } }
        }
    }
    // Who am I: confirms the saved session and loads everything.
    LaunchedEffect(start) {
        if (start != R.HOME) return@LaunchedEffect
        if (app.mode == Mode.Local) { app.useLocal(); return@LaunchedEffect }
        try { app.signedIn(app.api.me()) }
        catch (e: ApiError) { if (e.status == 401) app.signedOut() }
        catch (_: Exception) { /* offline: keep what we have; the live connection retries */ }
    }
    // Notifications ask once, on Android 13+.
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val me by app.store.state.collectAsState()
    LaunchedEffect(me.me?.id) {
        if (me.me != null && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // The launcher icon's long-press menu follows the roster.
    val ctx = LocalContext.current
    LaunchedEffect(me.bots, me.hidden) {
        Shortcuts.publish(ctx, me.bots.filter { it.id !in me.hidden })
    }
    // Opening a chat from a notification.
    val req by activity.openRequest.collectAsState()
    LaunchedEffect(req, me.me?.id) {
        val k = req ?: return@LaunchedEffect
        if (!app.hasSession()) return@LaunchedEffect
        activity.openRequest.value = null
        nav.navigate(R.chat(k)) { launchSingleTop = true }
    }

    val g = LocalG.current
    NowTicker {
    Box(Modifier.fillMaxSize().background(g.bg)) {
        SharedTransitionLayout {
        val sharedScope = this
        NavHost(
            nav, start,
            // Short and decisive: the screen is there before the motion
            // has finished explaining itself.
            enterTransition = { slideInHorizontally(tween(190, easing = FastOutSlowInEasing)) { it / 10 } + fadeIn(tween(140)) },
            exitTransition = { fadeOut(tween(110)) },
            popEnterTransition = { fadeIn(tween(140)) },
            popExitTransition = { slideOutHorizontally(tween(190, easing = FastOutSlowInEasing)) { it / 10 } + fadeOut(tween(150)) },
        ) {
            composable(R.SERVER) { ServerScreen(
                onDone = {
                    if (app.mode == Mode.Local) nav.home()
                    else nav.navigate(R.LOGIN) { popUpTo(0) { inclusive = true } }
                },
                onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null,
            ) }
            composable(R.LOGIN) { LoginScreen(onSignedIn = { nav.home() }, onChangeServer = { nav.navigate(R.SERVER) }) }
            composable(R.HOME) {
                CompositionLocalProvider(LocalSharedScope provides sharedScope, LocalAnimScope provides this) {
                    HomeScreen(nav)
                }
            }
            composable(
                "chat/{key}?prefill={prefill}",
                arguments = listOf(navArgument("prefill") { type = NavType.StringType; defaultValue = "" }),
            ) { e ->
                CompositionLocalProvider(LocalSharedScope provides sharedScope, LocalAnimScope provides this) {
                    ChatScreen(
                        nav,
                        Uri.decode(e.arguments?.getString("key").orEmpty()),
                        prefill = e.arguments?.getString("prefill").orEmpty(),
                    )
                }
            }
            composable("bot/{id}") { e -> BotSettingsScreen(nav, e.arguments?.getString("id").orEmpty()) }
            composable("group/{id}") { e -> GroupSettingsScreen(nav, e.arguments?.getString("id").orEmpty()) }
            composable("computer/{id}?take={take}") { e -> ComputerScreen(nav, e.arguments?.getString("id").orEmpty(), e.arguments?.getString("take") == "true") }
            composable(R.HATCH) { HatchScreen(nav) }
            composable(R.NEW_GROUP) { NewGroupScreen(nav) }
            composable(R.SETTINGS) { SettingsHome(nav) }
            composable("settings/{tab}") { e -> SettingsTab(nav, e.arguments?.getString("tab").orEmpty()) }
        }
        }
        Toasts(Modifier.align(Alignment.BottomCenter))
    }
    val shared by activity.shareRequest.collectAsState()
    shared?.let { text ->
        if (app.hasSession()) SharePicker(
            bots = me.bots.filter { it.id !in me.hidden },
            text = text,
            onPick = { b -> activity.shareRequest.value = null; nav.navigate(R.chat(b.id, text)) },
            onDismiss = { activity.shareRequest.value = null },
        )
    }
    }
}

@Composable
private fun Toasts(modifier: Modifier) {
    val app = GbotApp.instance
    val g = LocalG.current
    var current by remember { mutableStateOf<GbotApp.Toast?>(null) }
    LaunchedEffect(Unit) {
        app.toasts.collect { current = it; delay(2400); if (current === it) current = null }
    }
    AnimatedVisibility(current != null, modifier.navigationBarsPadding().padding(bottom = 88.dp), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val t = current ?: return@AnimatedVisibility
        Text(
            t.text, style = T.small, color = if (t.error) g.danger else g.ink,
            modifier = Modifier.shadow(12.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                .background(if (g.dark) androidx.compose.ui.graphics.Color(0xFF2C2C2C) else androidx.compose.ui.graphics.Color.White)
                .padding(horizontal = 18.dp, vertical = 11.dp),
        )
    }
}
