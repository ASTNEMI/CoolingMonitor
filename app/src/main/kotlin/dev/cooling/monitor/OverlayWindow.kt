package dev.cooling.monitor

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class OverlayWindow(private val context: Context, private val store: LayoutStore, private val onVisibility: (Boolean) -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var host: FrameLayout? = null
    private var renderer: OverlayView? = null
    private var glassView: ComposeView? = null
    private var glassOwner: OverlayComposeOwner? = null
    private var material by mutableStateOf<OverlayMaterial?>(null)
    private var layout = store.layout.value
    private var state = MonitorState()
    private var config = Config()
    private var folded = false
    private var animator: ValueAnimator? = null
    private val density get() = context.resources.displayMetrics.density
    private val landscape get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val displayed get() = if (folded) compact(layout) else layout
    private val appearance get() = Appearance.resolve(
        config, context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    )
    private var themed = displayed.resolveAppearance(appearance)
    private val params = WindowManager.LayoutParams(
        1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    fun toggle(): Boolean {
        if (host != null) { hide(); return true }
        if (!Settings.canDrawOverlays(context) || wm == null) return false
        val view = OverlayView(context)
        val frame = FrameLayout(context).apply { addView(view, FrameLayout.LayoutParams(-1, -1)) }
        host = frame
        renderer = view
        folded = false
        return runCatching {
            configure()
            wm.addView(frame, params)
        }.fold(
            onSuccess = {
                refreshMaterial()
                onVisibility(true)
                true
            },
            onFailure = {
                host = null
                renderer = null
                onVisibility(false)
                false
            }
        )
    }

    fun hide() {
        animator?.cancel()
        val frame = host ?: return
        removeGlass()
        glassOwner?.close()
        glassOwner = null
        host = null
        renderer = null
        runCatching { wm?.removeViewImmediate(frame) }
        onVisibility(false)
    }

    fun updateLayout(newLayout: OverlayLayout) {
        layout = newLayout
        animator?.cancel()
        reposition()
    }

    fun update(newState: MonitorState) {
        state = newState
        if (renderer != null && !Settings.canDrawOverlays(context)) hide()
        renderer?.invalidate()
    }

    fun updateAppearance(next: Config) {
        config = next
        refreshMaterial()
        renderer?.invalidate()
    }

    fun reposition() {
        if (renderer == null) return
        configure()
        apply()
        refreshMaterial()
    }

    private fun refreshMaterial() {
        val frame = host ?: return
        val active = appearance
        themed = displayed.resolveAppearance(active)
        if (!active.glass || !themed.followStyle || (themed.fill ushr 24) == 0) {
            removeGlass()
            return
        }
        val radius = themed.radius * params.width / (density * themed.width)
        val opacity = if (themed.mode == OverlayMode.Through && Build.VERSION.SDK_INT >= 31) 1f else themed.opacity
        material = OverlayMaterial(active, themed.fill, radius, opacity)
        if (glassView != null) return
        if (glassOwner == null) {
            val owner = OverlayComposeOwner()
            frame.setViewTreeLifecycleOwner(owner)
            frame.setViewTreeSavedStateRegistryOwner(owner)
            glassOwner = owner
        }
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setContent {
                material?.let {
                    LiquidOverlayMaterial(Modifier.fillMaxSize(), it.appearance, Color(it.fill), it.radius, it.opacity)
                }
            }
        }
        runCatching { frame.addView(view, 0, FrameLayout.LayoutParams(-1, -1)) }
            .onSuccess { glassView = view }
            .onFailure { view.disposeComposition() }
    }

    private fun removeGlass() {
        val view = glassView ?: return
        glassView = null
        view.disposeComposition()
        host?.removeView(view)
        material = null
    }

    private fun safeBounds(): Rect {
        if (wm == null) return Rect()
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val rect = Rect(metrics.bounds)
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            rect.left += insets.left; rect.top += insets.top
            rect.right -= insets.right; rect.bottom -= insets.bottom
            return rect
        }
        @Suppress("DEPRECATION")
        val size = Point().also { wm.defaultDisplay.getSize(it) }
        val resource = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return Rect(0, if (resource > 0) context.resources.getDimensionPixelSize(resource) else 0, size.x, size.y)
    }

    private fun configure() {
        val visible = displayed
        val bounds = safeBounds()
        val factor = (density * visible.scale).coerceAtMost(
            min(bounds.width() / visible.width, bounds.height() / visible.height).coerceAtLeast(0.01f)
        )
        params.width = (visible.width * factor).roundToInt().coerceAtLeast(1)
        params.height = (visible.height * factor).roundToInt().coerceAtLeast(1)
        val (x, y) = store.position(landscape)
        params.x = bounds.left + (x * (bounds.width() - params.width).coerceAtLeast(0)).roundToInt()
        params.y = bounds.top + (y * (bounds.height() - params.height).coerceAtLeast(0)).roundToInt()
        val through = layout.mode == OverlayMode.Through
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.flags = if (through) base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else base
        params.alpha = if (through && Build.VERSION.SDK_INT >= 31) {
            min(visible.opacity, context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: 0.8f)
        } else 1f
        renderer?.syncAccessibility()
    }

    private fun apply() {
        val frame = host ?: return
        if (frame.isAttachedToWindow) {
            runCatching { wm?.updateViewLayout(frame, params) }.onFailure { hide() }
        }
        renderer?.invalidate()
    }

    private fun savePosition() {
        val bounds = safeBounds()
        val x = (params.x - bounds.left).toFloat() / (bounds.width() - params.width).coerceAtLeast(1)
        val y = (params.y - bounds.top).toFloat() / (bounds.height() - params.height).coerceAtLeast(1)
        store.position(landscape, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    private fun settle() {
        if (!layout.snap) { savePosition(); return }
        val bounds = safeBounds()
        val edge = if (params.x + params.width / 2 < bounds.centerX()) bounds.left else bounds.right - params.width
        if (config.motion == Motion.Reduced || !ValueAnimator.areAnimatorsEnabled() || params.x == edge) {
            params.x = edge; savePosition(); apply()
            return
        }
        animator = ValueAnimator.ofInt(params.x, edge).apply {
            duration = if (config.motion == Motion.Full) 230L else 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                params.x = it.animatedValue as Int
                apply()
                if (it.animatedFraction >= 1f) savePosition()
            }
            start()
        }
    }

    private inner class OverlayView(context: Context) : View(context) {
        private val drawing = OverlayDrawing()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var pressedX = 0f
        private var pressedY = 0f
        private var originX = 0
        private var originY = 0
        private var moved = false
        private var scaled = false
        private var scale = 1f
        private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (layout.mode != OverlayMode.Move) return false
                scaled = true
                scale = layout.scale
                savePosition()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scale = (scale * detector.scaleFactor).coerceIn(0.6f, 1.8f)
                layout = layout.copy(scale = scale)
                reposition()
                return true
            }
        })

        init {
            isClickable = true
            contentDescription = "散热器监控悬浮窗，双击以折叠或展开"
        }

        fun syncAccessibility() {
            importantForAccessibility = if (layout.mode == OverlayMode.Through)
                IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val visible = if (layout.mode == OverlayMode.Through && Build.VERSION.SDK_INT >= 31) themed.copy(opacity = 1f) else themed
            drawing.draw(canvas, visible, state, width.toFloat(), resources.configuration.fontScale,
                drawBackground = glassView == null, accent = appearance.accent.toArgb())
        }

        override fun performClick(): Boolean {
            super.performClick()
            savePosition()
            folded = !folded
            reposition()
            return true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    animator?.cancel()
                    pressedX = event.rawX; pressedY = event.rawY
                    originX = params.x; originY = params.y
                    moved = false; scaled = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaled || event.pointerCount != 1 || layout.mode != OverlayMode.Move) return true
                    val dx = event.rawX - pressedX
                    val dy = event.rawY - pressedY
                    if (moved || abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        val bounds = safeBounds()
                        params.x = (originX + dx).roundToInt().coerceIn(bounds.left, (bounds.right - params.width).coerceAtLeast(bounds.left))
                        params.y = (originY + dy).roundToInt().coerceIn(bounds.top, (bounds.bottom - params.height).coerceAtLeast(bounds.top))
                        apply()
                    }
                }
                MotionEvent.ACTION_UP -> when {
                    scaled -> store.apply(layout)
                    moved -> settle()
                    else -> performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (scaled) store.apply(layout)
                    else if (moved) savePosition()
                }
            }
            return true
        }
    }
}

private data class OverlayMaterial(val appearance: Appearance, val fill: Int, val radius: Float, val opacity: Float)

private class OverlayComposeOwner : SavedStateRegistryOwner {
    override val lifecycle = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = controller.savedStateRegistry

    init {
        controller.performAttach()
        controller.performRestore(null)
        lifecycle.currentState = Lifecycle.State.RESUMED
    }

    fun close() { lifecycle.currentState = Lifecycle.State.DESTROYED }
}
