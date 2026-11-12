package dev.gbot.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.gbot.app.GbotApp

// packages/ui-tokens, as Compose colors.
@Immutable
data class G(
    val chrome: Color, val bg: Color, val surface: Color, val surface2: Color, val hover: Color, val selected: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val line: Color, val bubbleMe: Color, val bubbleMeInk: Color,
    val emphasis: Color, val onEmphasis: Color, val accent: Color, val success: Color, val warning: Color, val danger: Color,
    val live: Color, val liveSoft: Color, val dark: Boolean,
)

val Light = G(
    chrome = Color(0xFFF7F7F6), bg = Color(0xFFFCFCFC), surface = Color(0x0A101000), surface2 = Color(0x12101000),
    hover = Color(0x0A0A0A0A), selected = Color(0x0F0A0A0A), ink = Color(0xFF0A0A0A), ink2 = Color(0x8C000000),
    ink3 = Color(0x59000000), line = Color(0x1A000000), bubbleMe = Color(0xFF111110), bubbleMeInk = Color.White,
    emphasis = Color(0xFF111110), onEmphasis = Color.White, accent = Color(0xFF0A84FF), success = Color(0xFF1F8A65),
    warning = Color(0xFFB26A00), danger = Color(0xFFC0362C), live = Color(0xFF8B5CF6), liveSoft = Color(0x1F8B5CF6), dark = false,
)

val Dark = G(
    chrome = Color(0xFF141414), bg = Color(0xFF1F1F1F), surface = Color(0x0FFFFFFF), surface2 = Color(0x1AFFFFFF),
    hover = Color(0x0DFFFFFF), selected = Color(0x13FFFFFF), ink = Color(0xFFF2F2F2), ink2 = Color(0x94FFFFFF),
    ink3 = Color(0x5CFFFFFF), line = Color(0x1FFFFFFF), bubbleMe = Color(0xFFECECE8), bubbleMeInk = Color(0xFF141414),
    emphasis = Color(0xFFF0F0F0), onEmphasis = Color(0xFF141414), accent = Color(0xFF64B5FF), success = Color(0xFF4CC79B),
    warning = Color(0xFFE8A33D), danger = Color(0xFFFF6B5E), live = Color(0xFF8B5CF6), liveSoft = Color(0x2E8B5CF6), dark = true,
)

val LocalG = staticCompositionLocalOf { Light }

object T {
    val title = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val h2 = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val tiny = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val label = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun GbotTheme(content: @Composable () -> Unit) {
    val pref by GbotApp.instance.theme.collectAsState()
    val dark = when (pref) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val g = if (dark) Dark else Light
    val scheme = if (g.dark) darkColorScheme(
        primary = g.emphasis, onPrimary = g.onEmphasis, background = g.bg, onBackground = g.ink,
        surface = g.bg, onSurface = g.ink, surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = g.ink2,
        surfaceContainer = Color(0xFF262626), surfaceContainerHigh = Color(0xFF2B2B2B), surfaceContainerLow = Color(0xFF222222),
        outline = g.line, error = g.danger, secondary = g.live, tertiary = g.live,
    ) else lightColorScheme(
        primary = g.emphasis, onPrimary = g.onEmphasis, background = g.bg, onBackground = g.ink,
        surface = g.bg, onSurface = g.ink, surfaceVariant = Color(0xFFF0F0EF), onSurfaceVariant = g.ink2,
        surfaceContainer = Color(0xFFF4F4F3), surfaceContainerHigh = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF9F9F8),
        outline = g.line, error = g.danger, secondary = g.live, tertiary = g.live,
    )
    CompositionLocalProvider(LocalG provides g) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
