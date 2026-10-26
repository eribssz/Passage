package dev.eribssz.passage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Pal {
    val Bg = Color(0xFF070B14)
    val Surface = Color(0xFF0F1626)
    val SurfaceHi = Color(0xFF151E33)
    val Line = Color(0xFF1F2A42)
    val Text = Color(0xFFE8EDF7)
    val Muted = Color(0xFF8592AD)
    val Faint = Color(0xFF55617C)
    val Mint = Color(0xFF34E0A1)
    val Sky = Color(0xFF6EA8FF)
    val Amber = Color(0xFFFFB547)
    val Coral = Color(0xFFFF6B6B)
    val Idle = Color(0xFF6F7FA3)
}

private val scheme = darkColorScheme(
    primary = Pal.Mint,
    onPrimary = Color(0xFF04140D),
    secondary = Pal.Amber,
    background = Pal.Bg,
    onBackground = Pal.Text,
    surface = Pal.Surface,
    onSurface = Pal.Text,
    surfaceVariant = Pal.SurfaceHi,
    onSurfaceVariant = Pal.Muted,
    surfaceContainerLow = Pal.Surface,
    surfaceContainer = Pal.Surface,
    surfaceContainerHigh = Pal.SurfaceHi,
    outline = Pal.Line,
    outlineVariant = Pal.Line,
    error = Pal.Coral,
)

private val type = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
)

@Composable
fun PassageTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
