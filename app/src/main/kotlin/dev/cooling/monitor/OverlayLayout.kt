package dev.cooling.monitor

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val DefaultOverlayPanel = 0xff242832.toInt()
private const val DefaultOverlayInk = 0xffeef2fa.toInt()
private const val HudOverlayPanel = 0xdd242832.toInt()
private const val LegacyOverlayPanel = 0xff173130.toInt()
private const val LegacyHudPanel = 0xdd102422.toInt()
private const val LegacyOverlayInk = 0xffecfaf6.toInt()

enum class WidgetType(val label: String) {
    Value("数据值"), Unit("单位"), Label("字段名称"), Status("连接状态"), Indicator("状态指示"),
    Divider("分隔线"), Trend("温度曲线"), Container("容器")
}
enum class OverlayMode(val label: String) { Move("可移动"), Locked("锁定位置"), Through("触摸穿透") }

data class Widget(
    val id: String = UUID.randomUUID().toString(),
    val type: WidgetType,
    val metric: Metric = Metric.Temperature,
    val x: Float = 10f,
    val y: Float = 10f,
    val width: Float = 104f,
    val height: Float = 32f,
    val font: Float = 16f,
    val bold: Boolean = false,
    val ink: Int = DefaultOverlayInk,
    val fill: Int = 0,
    val border: Boolean = false,
    val radius: Float = 8f,
    val padding: Float = 4f,
    val hidden: Boolean = false
)

data class OverlayLayout(
    val name: String,
    val width: Float,
    val height: Float,
    val fill: Int = DefaultOverlayPanel,
    val opacity: Float = 0.98f,
    val scale: Float = 1f,
    val radius: Float = 16f,
    val gap: Float = 8f,
    val snap: Boolean = true,
    val mode: OverlayMode = OverlayMode.Move,
    val followStyle: Boolean = true,
    val widgets: List<Widget>
)

object LayoutCodec {
    private fun JSONObject.number(key: String, default: Float, min: Float, max: Float): Float {
        val value = optDouble(key, default.toDouble()).toFloat()
        return if (value.isFinite()) value.coerceIn(min, max) else default
    }

    fun encode(layout: OverlayLayout): String = JSONObject().apply {
        put("schema", 1)
        put("name", layout.name)
        put("width", layout.width.toDouble()); put("height", layout.height.toDouble())
        put("fill", layout.fill); put("opacity", layout.opacity.toDouble())
        put("scale", layout.scale.toDouble()); put("radius", layout.radius.toDouble())
        put("gap", layout.gap.toDouble()); put("snap", layout.snap); put("mode", layout.mode.name)
        put("followStyle", layout.followStyle)
        put("widgets", JSONArray().apply {
            layout.widgets.forEach { item ->
                put(JSONObject().apply {
                    put("kind", item.type.name); put("metric", item.metric.name)
                    put("x", item.x.toDouble()); put("y", item.y.toDouble())
                    put("width", item.width.toDouble()); put("height", item.height.toDouble())
                    put("font", item.font.toDouble()); put("bold", item.bold)
                    put("ink", item.ink); put("fill", item.fill); put("border", item.border)
                    put("radius", item.radius.toDouble()); put("padding", item.padding.toDouble())
                    put("hidden", item.hidden)
                })
            }
        })
    }.toString(2)

    fun decode(json: String): OverlayLayout {
        require(json.length <= 100_000) { "布局文件过大" }
        val root = JSONObject(json)
        require(root.getInt("schema") == 1) { "布局版本不受支持" }
        val width = root.number("width", 180f, 72f, 440f)
        val height = root.number("height", 80f, 34f, 320f)
        val widgets = root.getJSONArray("widgets")
        require(widgets.length() <= 32) { "最多支持 32 个组件" }
        return OverlayLayout(
            name = root.getString("name").trim().take(32).ifBlank { "导入布局" },
            width = width, height = height,
            fill = root.optInt("fill", DefaultOverlayPanel),
            opacity = root.number("opacity", 0.98f, 0.2f, 1f), scale = root.number("scale", 1f, 0.6f, 1.8f),
            radius = root.number("radius", 16f, 0f, 44f), gap = root.number("gap", 8f, 0f, 24f),
            snap = root.optBoolean("snap", true),
            followStyle = root.optBoolean("followStyle", true),
            mode = OverlayMode.entries.firstOrNull { it.name == root.optString("mode") } ?: OverlayMode.Move,
            widgets = List(widgets.length()) { index ->
                val item = widgets.getJSONObject(index)
                val w = item.number("width", 104f, 10f, width)
                val h = item.number("height", 32f, 8f, height)
                Widget(
                    type = WidgetType.valueOf(item.getString("kind")),
                    metric = Metric.entries.firstOrNull { it.name == item.optString("metric") } ?: Metric.Temperature,
                    x = item.number("x", 0f, 0f, width).coerceAtMost(width - w),
                    y = item.number("y", 0f, 0f, height).coerceAtMost(height - h),
                    width = w, height = h, font = item.number("font", 16f, 8f, 52f),
                    bold = item.optBoolean("bold"), ink = item.optInt("ink", DefaultOverlayInk),
                    fill = item.optInt("fill", 0), border = item.optBoolean("border"),
                    radius = item.number("radius", 8f, 0f, 40f),
                    padding = item.number("padding", 4f, 0f, 32f), hidden = item.optBoolean("hidden")
                )
            }
        )
    }
}

object LayoutPresets {
    private fun item(kind: WidgetType, x: Int, y: Int, w: Int, h: Int, size: Int = 14,
                     metric: Metric = Metric.Temperature, bold: Boolean = false) = Widget(
        type = kind, x = x.toFloat(), y = y.toFloat(), width = w.toFloat(), height = h.toFloat(),
        font = size.toFloat(), metric = metric, bold = bold
    )

    val stock = listOf(
        OverlayLayout("极简数字", 115f, 50f, widgets = listOf(item(WidgetType.Value, 8, 4, 100, 42, 25, bold = true))),
        OverlayLayout("横向胶囊", 244f, 46f, radius = 23f, widgets = listOf(
            item(WidgetType.Indicator, 5, 7, 21, 32), item(WidgetType.Value, 25, 7, 73, 32, 18, bold = true),
            item(WidgetType.Status, 103, 7, 125, 32, 14))),
        OverlayLayout("竖向数据卡片", 172f, 154f, widgets = listOf(
            item(WidgetType.Value, 12, 10, 135, 42, 27, bold = true),
            item(WidgetType.Divider, 14, 56, 144, 8),
            item(WidgetType.Label, 12, 66, 78, 30, 13, Metric.Power),
            item(WidgetType.Value, 91, 66, 75, 30, 15, Metric.Power),
            item(WidgetType.Label, 12, 103, 81, 29, 13, Metric.Fan),
            item(WidgetType.Value, 96, 103, 72, 29, 15, Metric.Fan))),
        OverlayLayout("游戏 HUD", 230f, 47f, fill = HudOverlayPanel, radius = 8f, widgets = listOf(
            item(WidgetType.Value, 10, 5, 74, 37, 19, bold = true),
            item(WidgetType.Trend, 86, 7, 131, 33))),
        OverlayLayout("迷你曲线", 214f, 106f, widgets = listOf(
            item(WidgetType.Value, 12, 6, 101, 36, 22, bold = true),
            item(WidgetType.Trend, 12, 43, 190, 55))),
        OverlayLayout("自由组合", 218f, 113f, widgets = listOf(
            item(WidgetType.Label, 12, 6, 150, 26, 13),
            item(WidgetType.Value, 12, 36, 111, 38, 24, bold = true),
            item(WidgetType.Status, 12, 78, 187, 27, 13))),
        OverlayLayout("超紧凑", 103f, 35f, radius = 10f, widgets = listOf(
            item(WidgetType.Indicator, 2, 1, 23, 33),
            item(WidgetType.Value, 26, 1, 74, 33, 15, bold = true))),
        OverlayLayout("透明文字", 119f, 45f, fill = 0, widgets = listOf(
            item(WidgetType.Value, 4, 4, 111, 36, 24, bold = true)))
    )
}

class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val active = MutableStateFlow(read(prefs.getString("active", null)) ?: LayoutPresets.stock.first())
    val layout = active.asStateFlow()
    private val custom = MutableStateFlow(readCustom())
    val presets = custom.asStateFlow()

    private fun read(json: String?) = json?.let { runCatching { LayoutCodec.decode(it) }.getOrNull() }

    private fun readCustom(): List<OverlayLayout> = runCatching {
        val array = JSONArray(prefs.getString("custom", "[]"))
        List(array.length()) { read(array.optString(it)) }.filterNotNull()
    }.getOrDefault(emptyList())

    fun apply(layout: OverlayLayout) {
        active.value = layout
        prefs.edit().putString("active", LayoutCodec.encode(layout)).apply()
    }

    fun savePreset(layout: OverlayLayout) {
        val next = (custom.value.filterNot { it.name == layout.name } + layout).takeLast(24)
        custom.value = next
        writePresets(next)
        apply(layout)
    }

    fun deletePreset(name: String) {
        val next = custom.value.filterNot { it.name == name }
        custom.value = next
        writePresets(next)
    }

    private fun writePresets(items: List<OverlayLayout>) {
        val array = JSONArray()
        items.forEach { array.put(LayoutCodec.encode(it)) }
        prefs.edit().putString("custom", array.toString()).apply()
    }

    fun position(landscape: Boolean): Pair<Float, Float> {
        val key = if (landscape) "landscape" else "portrait"
        return prefs.getFloat("${key}_x", 0.94f) to prefs.getFloat("${key}_y", 0.18f)
    }

    fun position(landscape: Boolean, x: Float, y: Float) {
        val key = if (landscape) "landscape" else "portrait"
        prefs.edit().putFloat("${key}_x", x).putFloat("${key}_y", y).apply()
    }
}

fun OverlayLayout.resolveAppearance(appearance: Appearance): OverlayLayout {
    if (!followStyle) return this
    val standardFill = fill in listOf(DefaultOverlayPanel, HudOverlayPanel, LegacyOverlayPanel, LegacyHudPanel)
    val face = (if (appearance.dark) 0x242832 else 0xf7f9fc) or (fill and 0xff000000.toInt())
    val ink = if (appearance.dark || !standardFill) DefaultOverlayInk else 0xff171a24.toInt()
    return copy(
        fill = if (standardFill) face else fill,
        widgets = widgets.map { widget ->
            if (widget.ink == DefaultOverlayInk || widget.ink == LegacyOverlayInk) widget.copy(ink = ink) else widget
        }
    )
}
