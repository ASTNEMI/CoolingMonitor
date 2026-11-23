package dev.cooling.monitor

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Design {
    val space = 16.dp
    val gutter = 24.dp
    val group = 32.dp
    val panel = RoundedCornerShape(22.dp)
    val navigation = RoundedCornerShape(28.dp)
    val control = RoundedCornerShape(16.dp)
    val scope = Color(0xff1a2537)
    val scopeLine = Color(0xff40506a)
    val scopeText = Color(0xffc5d2e6)
    val chartLine = Color(0xff86baff)
    val accents = listOf("系统蓝", "石墨", "暮紫", "陶红")
    private val numeric = FontFamily(Font(R.font.barlow_condensed_medium, FontWeight.Medium))
    val readout = TextStyle(fontFamily = numeric, fontWeight = FontWeight.Medium, fontSize = 68.sp,
        lineHeight = 72.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = "'tnum'")
    val measurement = TextStyle(fontFamily = numeric, fontWeight = FontWeight.Medium, fontSize = 26.sp,
        lineHeight = 30.sp, fontFeatureSettings = "'tnum'")
}

private val accentColors = listOf(0xff0080ff, 0xff636b79, 0xff7666ab, 0xffbe4d4f)
val LocalAppearance = staticCompositionLocalOf { Appearance(false, InterfaceStyle.ColorOS, Color(0xff0080ff)) }

data class Appearance(val dark: Boolean, val style: InterfaceStyle, val accent: Color) {
    val glass get() = style == InterfaceStyle.LiquidGlass && Build.VERSION.SDK_INT >= 33

    companion object {
        fun resolve(config: Config, systemDark: Boolean): Appearance {
            val dark = when (config.theme) {
                ThemeChoice.System -> systemDark
                ThemeChoice.Light -> false
                ThemeChoice.Dark -> true
            }
            val accent = config.customAccent?.let { Color(it) } ?: when {
                config.accent == 0 && dark -> Color(0xff1a8cff)
                else -> Color(accentColors[config.accent])
            }
            return Appearance(dark, config.interfaceStyle, accent)
        }
    }
}

internal fun contrastTone(color: Color, against: Color, toward: Color, minimum: Double): Color {
    val reference = against.luminance()
    for (step in 0..20) {
        val candidate = lerp(color, toward, step / 20f)
        val tone = candidate.luminance()
        val ratio = (maxOf(reference, tone) + 0.05) / (minOf(reference, tone) + 0.05)
        if (ratio >= minimum) return candidate
    }
    return toward
}

@Composable
fun CoolingTheme(config: Config, content: @Composable () -> Unit) {
    val appearance = Appearance.resolve(config, isSystemInDarkTheme())
    val dark = appearance.dark
    val darkSurface = Color(0xff191a1d)
    val lightSurface = Color(0xfff7f8fa)
    val primary = if (dark) contrastTone(appearance.accent, darkSurface, Color.White, 5.0)
        else contrastTone(appearance.accent, lightSurface, Color.Black, 5.0)
    val colors = if (dark) darkColorScheme(
        primary = primary, onPrimary = Color(0xff0d1016),
        primaryContainer = lerp(darkSurface, appearance.accent, 0.19f), onPrimaryContainer = Color(0xffedf3ff),
        background = Color(0xff101113), onBackground = Color(0xfff3f3f5),
        surface = darkSurface, onSurface = Color(0xfff3f3f5),
        surfaceVariant = Color(0xff23252a), onSurfaceVariant = Color(0xffbabcc5),
        outline = Color(0xff818591), outlineVariant = Color(0xff3b3e45),
        secondaryContainer = lerp(darkSurface, appearance.accent, 0.24f), onSecondaryContainer = Color(0xfff1f5fd),
        error = Color(0xffffb3af), surfaceContainer = Color(0xff202126)
    ) else lightColorScheme(
        primary = primary, onPrimary = Color.White,
        primaryContainer = lerp(Color.White, appearance.accent, 0.13f), onPrimaryContainer = Color(0xff182637),
        background = Color(0xfff2f3f5), onBackground = Color(0xff17191f),
        surface = Color.White, onSurface = Color(0xff17191f),
        surfaceVariant = Color(0xffe9ebf0), onSurfaceVariant = Color(0xff545963),
        outline = Color(0xff747985), outlineVariant = Color(0xffd8dbe2),
        secondaryContainer = lerp(Color.White, appearance.accent, 0.15f), onSecondaryContainer = Color(0xff192c43),
        error = Color(0xffac3540), surfaceContainer = lightSurface
    )
    val type = Typography(
        headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
        headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    )
    CompositionLocalProvider(LocalAppearance provides appearance) {
        MaterialTheme(colorScheme = colors, typography = type,
            shapes = Shapes(small = Design.control, medium = Design.panel, large = Design.navigation), content = content)
    }
}
