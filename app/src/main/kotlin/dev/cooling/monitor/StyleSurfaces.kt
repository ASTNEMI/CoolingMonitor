package dev.cooling.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

private val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun StyleScene(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val appearance = LocalAppearance.current
    val background = MaterialTheme.colorScheme.background
    if (!appearance.glass) {
        Box(modifier.background(background), content = content)
        return
    }
    val backdrop = rememberLayerBackdrop()
    Box(modifier) {
        Canvas(Modifier.matchParentSize().layerBackdrop(backdrop)) {
            drawStyleScene(background, appearance.accent, appearance.dark)
        }
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
            content()
        }
    }
}

private fun DrawScope.drawStyleScene(base: Color, accent: Color, dark: Boolean) {
    drawRect(base)
    if (size.minDimension == 0f) return
    val wash = accent.copy(alpha = if (dark) 0.20f else 0.17f)
    drawRect(Brush.radialGradient(
        listOf(wash, wash.copy(alpha = 0f)), center = Offset(size.width * 0.88f, size.height * 0.13f),
        radius = maxOf(size.width, size.height) * 0.72f
    ))
    drawRect(Brush.radialGradient(
        listOf(wash.copy(alpha = if (dark) 0.13f else 0.09f), wash.copy(alpha = 0f)),
        center = Offset(size.width * 0.07f, size.height * 0.68f),
        radius = maxOf(size.width, size.height) * 0.52f
    ))
}

private fun Modifier.glass(
    backdrop: Backdrop, shape: Shape, tint: Color, tintAlpha: Float
): Modifier = drawBackdrop(
    backdrop = backdrop, shape = { shape },
    effects = {
        vibrancy()
        blur(8.dp.toPx())
        lens(10.dp.toPx(), 15.dp.toPx(), depthEffect = true)
    },
    shadow = null,
    onDrawSurface = { drawRect(tint.copy(alpha = tintAlpha)) }
)

@Composable
fun StyledSurface(
    modifier: Modifier = Modifier,
    shape: Shape = Design.panel,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tintAlpha: Float = 0.77f,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalBackdrop.current
    val surface = if (backdrop != null) modifier.glass(backdrop, shape, color, tintAlpha)
        else modifier.clip(shape).background(color)
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(surface, content = content)
    }
}

@Composable
fun StyleControl(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    if (LocalAppearance.current.glass) {
        StyledSurface(modifier, Design.control, tintAlpha = 0.44f, content = content)
    } else Box(modifier, content = content)
}

@Composable
fun StyledDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    if (!LocalAppearance.current.glass) {
        AlertDialog(onDismissRequest, confirmButton, title = title, text = text, dismissButton = dismissButton)
        return
    }
    Dialog(onDismissRequest) {
        StyleDialogContent(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                text()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
fun StyleDialogContent(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = Design.navigation
    StyleScene(modifier.clip(shape)) {
        StyledSurface(Modifier.fillMaxWidth(), shape, tintAlpha = 0.84f, content = content)
    }
}

@Composable
fun LiquidOverlayMaterial(modifier: Modifier, appearance: Appearance, fill: Color, radius: Float, opacity: Float) {
    val shape = RoundedOverlayShape(radius)
    val backdrop = rememberCanvasBackdrop {
        drawStyleScene(fill.copy(alpha = 1f), appearance.accent, appearance.dark)
    }
    Box(modifier.graphicsLayer { alpha = opacity * fill.alpha }.glass(backdrop, shape, fill, 0.61f))
}

private fun RoundedOverlayShape(radius: Float): Shape = androidx.compose.foundation.shape.RoundedCornerShape(radius.dp)
