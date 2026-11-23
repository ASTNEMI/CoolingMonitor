package dev.cooling.monitor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test fun defaultAppearanceUsesColorOsBlueInBothTones() {
        assertEquals(Appearance(false, InterfaceStyle.ColorOS, Color(0xff0080ff)), Appearance.resolve(Config(), false))
        assertEquals(Appearance(true, InterfaceStyle.ColorOS, Color(0xff1a8cff)), Appearance.resolve(Config(), true))
    }

    @Test fun customColorAndGlassSurviveExplicitDarkTheme() {
        val color = 0xffd28b36.toInt()
        val selected = Appearance.resolve(Config(theme = ThemeChoice.Dark, interfaceStyle = InterfaceStyle.LiquidGlass,
            customAccent = color), false)
        assertEquals(Appearance(true, InterfaceStyle.LiquidGlass, Color(color)), selected)
    }

    @Test fun lightTextActionAccentReachesContrastTarget() {
        val surface = Color(0xfff7f8fa)
        val adjusted = contrastTone(Color(0xff0080ff), surface, Color.Black, 5.0)
        val ratio = (surface.luminance() + 0.05) / (adjusted.luminance() + 0.05)
        assertTrue(ratio >= 5.0)
    }

    @Test fun styledOverlayMigratesStockColorsAndPreservesExplicitStyleOverride() {
        val stock = LayoutPresets.stock.first()
        val light = Appearance.resolve(Config(), false)
        val old = stock.copy(fill = 0xff173130.toInt())
        assertEquals(0xfff7f9fc.toInt(), old.resolveAppearance(light).fill)
        val custom = stock.copy(fill = 0xffc8795d.toInt(), followStyle = false)
        assertEquals(custom, custom.resolveAppearance(light))
        val textOnly = LayoutPresets.stock.last()
        assertEquals(0, textOnly.resolveAppearance(light).fill)
    }
}
