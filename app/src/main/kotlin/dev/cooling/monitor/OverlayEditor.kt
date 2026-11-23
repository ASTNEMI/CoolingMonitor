package dev.cooling.monitor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private enum class EditTool(val label: String) { Move("移动"), Resize("缩放组件") }

private class EditHistory(start: OverlayLayout) {
    var draft by mutableStateOf(start)
    private val undo = mutableStateListOf<OverlayLayout>()
    private val redo = mutableStateListOf<OverlayLayout>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun commit(next: OverlayLayout) {
        if (next == draft) return
        push(draft)
        draft = next
    }

    fun completeGesture(before: OverlayLayout) {
        if (before != draft) push(before)
    }

    private fun push(before: OverlayLayout) {
        if (undo.size == 40) undo.removeAt(0)
        undo.add(before)
        redo.clear()
    }

    fun undo() {
        if (!canUndo) return
        redo.add(draft)
        draft = undo.removeAt(undo.lastIndex)
    }

    fun redo() {
        if (!canRedo) return
        undo.add(draft)
        draft = redo.removeAt(redo.lastIndex)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayEditor(active: OverlayLayout, custom: List<OverlayLayout>, shown: Boolean, permitted: Boolean,
                  state: MonitorState, store: LayoutStore, onToggle: () -> Unit, message: (String) -> Unit) {
    val context = LocalContext.current
    val io = rememberCoroutineScope()
    val editor = remember { EditHistory(active) }
    var baseline by remember { mutableStateOf(active) }
    var selected by remember { mutableStateOf(active.widgets.firstOrNull()?.id) }
    var tool by remember { mutableStateOf(EditTool.Move) }
    var grid by remember { mutableStateOf(true) }
    var add by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var style by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var serialized by remember { mutableStateOf("") }
    LaunchedEffect(active) {
        if (editor.draft == baseline) editor.draft = active
        baseline = active
    }
    val current = editor.draft
    val widget = current.widgets.firstOrNull { it.id == selected }

    fun exportMessage(text: String) = message(text)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) io.launch {
            val success = runCatching {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(serialized) }
                }
            }.isSuccess
            exportMessage(if (success) "布局已导出" else "无法导出布局")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val json = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { reader ->
                        val buffer = CharArray(100_001)
                        val count = reader.read(buffer, 0, buffer.size)
                        var used = count
                        while (used in 1 until buffer.size) {
                            val extra = reader.read(buffer, used, buffer.size - used)
                            if (extra < 1) break
                            used += extra
                        }
                        require(used in 1..100_000)
                        String(buffer, 0, used)
                    }
                    LayoutCodec.decode(json)
                }
            }.onSuccess {
                editor.commit(it.copy(mode = OverlayMode.Move))
                selected = it.widgets.firstOrNull()?.id
                exportMessage("布局已导入到画布")
            }.onFailure { exportMessage("无法读取布局，请检查 JSON 格式和版本") }
        }
    }
    fun change(transform: (OverlayLayout) -> OverlayLayout) = editor.commit(transform(editor.draft))
    fun selectLayout(layout: OverlayLayout) {
        change { layout }
        selected = layout.widgets.firstOrNull()?.id
    }
    fun changeWidget(update: (Widget) -> Widget) {
        change { l -> l.copy(widgets = l.widgets.map { if (it.id == selected) within(update(it), l) else it }) }
    }
    fun moveLayer(forward: Boolean) {
        val stack = current.widgets.toMutableList()
        val index = stack.indexOfFirst { it.id == selected }
        if (index < 0) return
        val next = index + if (forward) 1 else -1
        if (next in stack.indices) {
            val item = stack.removeAt(index)
            stack.add(next, item)
            change { it.copy(widgets = stack) }
        }
    }

    if (presets) {
        StyledDialog(onDismissRequest = { presets = false }, title = { Text("选择布局预设") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    LayoutPresets.stock.forEach { preset ->
                        TextButton({ selectLayout(preset); presets = false }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(preset.name) }
                    }
                    if (custom.isNotEmpty()) Text("自定义布局", style = MaterialTheme.typography.titleMedium)
                    custom.forEach { preset ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ selectLayout(preset); presets = false }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                                Text(preset.name)
                            }
                            TextButton({ store.deletePreset(preset.name) }, Modifier.heightIn(min = 48.dp)) { Text("移除") }
                        }
                    }
                }
            }, confirmButton = { TextButton({ presets = false }) { Text("关闭") } })
    }
    if (add) {
        StyledDialog(onDismissRequest = { add = false }, title = { Text("添加组件") },
            text = {
                Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                    WidgetType.entries.forEach { kind ->
                        TextButton({
                            val l = editor.draft
                            val item = within(Widget(type = kind, x = l.gap, y = l.gap), l)
                            change { it.copy(widgets = it.widgets + item) }
                            selected = item.id
                            add = false
                        }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(kind.label) }
                    }
                }
            }, confirmButton = { TextButton({ add = false }) { Text("取消") } })
    }
    if (saving) {
        StyledDialog(onDismissRequest = { saving = false }, title = { Text("保存自定义预设") },
            text = { OutlinedTextField(title, { title = it.take(32) }, label = { Text("预设名称") }, singleLine = true) },
            dismissButton = { TextButton({ saving = false }) { Text("取消") } },
            confirmButton = {
                TextButton({
                    val named = editor.draft.copy(name = title.trim())
                    editor.commit(named)
                    store.savePreset(named)
                    saving = false
                    message("已保存自定义预设")
                }, enabled = title.isNotBlank()) { Text("保存预设") }
            })
    }

    ScrollingPage {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("悬浮窗编辑", style = MaterialTheme.typography.headlineSmall)
                Text(current.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onToggle, shape = Design.control, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (shown) "关闭窗口" else if (!permitted) "授权并显示" else "显示窗口")
            }
        }
        if (active.mode == OverlayMode.Through) {
            FilledTonalButton({
                val restored = active.copy(mode = OverlayMode.Move)
                store.apply(restored)
                change { it.copy(mode = OverlayMode.Move) }
                message("已恢复窗口触摸")
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("恢复窗口触摸") }
        }
        EditorCanvas(editor, selected, tool, grid, state) { selected = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ store.apply(current); message("悬浮窗布局已应用") },
                enabled = current != active, modifier = Modifier.heightIn(min = 48.dp)) { Text("应用布局") }
            OutlinedButton({ presets = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("布局预设") }
            TextButton(editor::undo, enabled = editor.canUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text("撤销") }
            TextButton(editor::redo, enabled = editor.canRedo, modifier = Modifier.heightIn(min = 48.dp)) { Text("重做") }
        }
        SettingChoices("画布工具", EditTool.entries, tool, { it.label }) { tool = it }
        ToggleRow("画布网格与吸附", null, grid) { grid = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ add = true }, enabled = current.widgets.size < 32, modifier = Modifier.heightIn(min = 48.dp)) { Text("添加组件") }
            TextButton({ style = !style }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (style) "收起窗口设置" else "窗口设置") }
        }
        if (current.widgets.isNotEmpty()) {
            SettingChoices("组件图层", current.widgets.map { it.id }, selected.orEmpty(),
                { id -> current.widgets.first { it.id == id }.type.label }, { selected = it })
        }
        if (widget != null) {
            SectionTitle("组件属性", widget.type.label)
            if (widget.type in listOf(WidgetType.Value, WidgetType.Unit, WidgetType.Label)) {
                val metrics = Metric.entries.filter { it == Metric.Temperature || it == widget.metric || it in state.samples }
                SettingChoices("数据来源", metrics, widget.metric, { it.label }) { field -> changeWidget { it.copy(metric = field) } }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ moveLayer(false) }, enabled = current.widgets.firstOrNull()?.id != widget.id, modifier = Modifier.heightIn(min = 48.dp)) { Text("下移图层") }
                TextButton({ moveLayer(true) }, enabled = current.widgets.lastOrNull()?.id != widget.id, modifier = Modifier.heightIn(min = 48.dp)) { Text("上移图层") }
                TextButton({ changeWidget { it.copy(x = (current.width - it.width) / 2) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("水平居中") }
                TextButton({ changeWidget { it.copy(y = (current.height - it.height) / 2) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("垂直居中") }
                TextButton({
                    change { it.copy(widgets = it.widgets.filterNot { item -> item.id == widget.id }) }
                    selected = editor.draft.widgets.lastOrNull()?.id
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除组件") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DimField("横坐标", widget.x, Modifier.weight(1f)) { value -> changeWidget { it.copy(x = value) } }
                DimField("纵坐标", widget.y, Modifier.weight(1f)) { value -> changeWidget { it.copy(y = value) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DimField("组件宽度", widget.width, Modifier.weight(1f)) { value -> changeWidget { it.copy(width = value) } }
                DimField("组件高度", widget.height, Modifier.weight(1f)) { value -> changeWidget { it.copy(height = value) } }
            }
            EditSlider("字号", widget.font, 8f..52f, "sp") { value -> changeWidget { it.copy(font = value) } }
            EditSlider("圆角", widget.radius, 0f..40f, "dp") { value -> changeWidget { it.copy(radius = value) } }
            EditSlider("内边距", widget.padding, 0f..32f, "dp") { value -> changeWidget { it.copy(padding = value) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorField("文字颜色", widget.ink, Modifier.weight(1f)) { value -> changeWidget { it.copy(ink = value) } }
                ColorField("组件底色", widget.fill, Modifier.weight(1f)) { value -> changeWidget { it.copy(fill = value) } }
            }
            ToggleRow("粗体", null, widget.bold) { value -> changeWidget { it.copy(bold = value) } }
            ToggleRow("边框", null, widget.border) { value -> changeWidget { it.copy(border = value) } }
            ToggleRow("隐藏组件", null, widget.hidden) { value -> changeWidget { it.copy(hidden = value) } }
        }
        if (style) {
            SectionTitle("窗口设置")
            SettingChoices("触摸方式", OverlayMode.entries, current.mode, { it.label }) { mode -> change { it.copy(mode = mode) } }
            ToggleRow("跟随应用界面风格", "默认材质和颜色随外观设置切换。", current.followStyle) { value -> change { it.copy(followStyle = value) } }
            ToggleRow("窗口吸附屏幕边缘", null, current.snap) { value -> change { it.copy(snap = value) } }
            EditSlider("窗口透明度", current.opacity * 100, 20f..100f, "%") { value -> change { it.copy(opacity = value / 100) } }
            EditSlider("窗口比例", current.scale * 100, 60f..180f, "%") { value -> change { it.copy(scale = value / 100) } }
            EditSlider("窗口圆角", current.radius, 0f..44f, "dp") { value -> change { it.copy(radius = value) } }
            EditSlider("自动排列间距", current.gap, 0f..24f, "dp") { value -> change { it.copy(gap = value) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DimField("画布宽度", current.width, Modifier.weight(1f)) { value ->
                    change { a -> a.copy(width = value.coerceIn(72f, 440f)).let { b -> b.copy(widgets = b.widgets.map { within(it, b) }) } }
                }
                DimField("画布高度", current.height, Modifier.weight(1f)) { value ->
                    change { a -> a.copy(height = value.coerceIn(34f, 320f)).let { b -> b.copy(widgets = b.widgets.map { within(it, b) }) } }
                }
            }
            ColorField("窗口底色（ARGB）", current.fill, Modifier.fillMaxWidth()) { value -> change { it.copy(fill = value) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ change { arrange(it, true) } }, Modifier.heightIn(min = 48.dp)) { Text("横向排列") }
                TextButton({ change { arrange(it, false) } }, Modifier.heightIn(min = 48.dp)) { Text("纵向排列") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ title = current.name; saving = true }, Modifier.heightIn(min = 48.dp)) { Text("保存新预设") }
            TextButton({ importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                Modifier.heightIn(min = 48.dp)) { Text("导入布局") }
            TextButton({
                serialized = LayoutCodec.encode(current)
                exporter.launch("cooling-layout.json")
            }, Modifier.heightIn(min = 48.dp)) { Text("导出布局") }
            TextButton({ selectLayout(LayoutPresets.stock.first()) }, Modifier.heightIn(min = 48.dp)) { Text("恢复默认布局") }
        }
    }
}

private fun within(w: Widget, layout: OverlayLayout): Widget {
    val width = w.width.coerceIn(10f, layout.width)
    val height = w.height.coerceIn(8f, layout.height)
    return w.copy(width = width, height = height,
        x = w.x.coerceIn(0f, layout.width - width), y = w.y.coerceIn(0f, layout.height - height))
}

private fun arrange(layout: OverlayLayout, horizontal: Boolean): OverlayLayout {
    var cursor = layout.gap
    return layout.copy(widgets = layout.widgets.map { item ->
        val updated = if (horizontal) item.copy(x = cursor, y = layout.gap) else item.copy(x = layout.gap, y = cursor)
        cursor += if (horizontal) item.width + layout.gap else item.height + layout.gap
        within(updated, layout)
    })
}

@Composable
private fun EditorCanvas(editor: EditHistory, selected: String?, tool: EditTool, grid: Boolean, state: MonitorState, onSelect: (String) -> Unit) {
    val shape = Design.panel
    val drawing = remember { OverlayDrawing() }
    val font = LocalDensity.current.fontScale
    val surface = MaterialTheme.colorScheme.surfaceVariant
    val layout = editor.draft
    val appearance = LocalAppearance.current
    val styled = remember(layout, appearance) { layout.resolveAppearance(appearance) }
    val useGlass = appearance.glass && styled.followStyle && styled.fill != 0
    val currentSelect by rememberUpdatedState(onSelect)
    val currentSelected by rememberUpdatedState(selected)
    StyledSurface(shape = shape, color = surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val targetWidth = min(maxWidth.value, layout.width * 1.35f).coerceAtLeast(1f).dp
            val targetHeight = targetWidth * (layout.height / layout.width)
            Box(Modifier.width(targetWidth).height(targetHeight)) {
                if (useGlass) LiquidOverlayMaterial(Modifier.matchParentSize(), appearance, Color(styled.fill),
                    styled.radius * (targetWidth.value / styled.width), styled.opacity)
                Canvas(Modifier.fillMaxSize().clip(shape)
                .semantics { contentDescription = "悬浮窗实时预览。也可通过下面的图层与坐标控件编辑。" }
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        val l = editor.draft
                        val scale = size.width / l.width
                        l.widgets.lastOrNull { w -> at.x / scale in w.x..(w.x + w.width) && at.y / scale in w.y..(w.y + w.height) }
                            ?.let { currentSelect(it.id) }
                    }
                }
                .pointerInput(tool, grid) {
                    var before: OverlayLayout? = null
                    var item: Widget? = null
                    var deltaX = 0f
                    var deltaY = 0f
                    detectDragGestures(onDragStart = { start ->
                        val l = editor.draft
                        val scale = size.width / l.width
                        val x = start.x / scale
                        val y = start.y / scale
                        item = l.widgets.lastOrNull { x in it.x..(it.x + it.width) && y in it.y..(it.y + it.height) }
                        if (tool == EditTool.Resize) item = l.widgets.firstOrNull { it.id == currentSelected } ?: item
                        before = l
                        deltaX = 0f; deltaY = 0f
                        item?.let { currentSelect(it.id) }
                    }, onDragEnd = {
                        before?.let(editor::completeGesture)
                        before = null; item = null
                    }, onDragCancel = {
                        before?.let { editor.draft = it }
                        before = null; item = null
                    }) { change, delta ->
                        val origin = before ?: return@detectDragGestures
                        val target = item ?: return@detectDragGestures
                        change.consume()
                        val ratio = size.width / origin.width
                        deltaX += delta.x / ratio
                        deltaY += delta.y / ratio
                        fun fit(value: Float) = if (grid) (value / 8f).roundToInt() * 8f else value
                        val moved = if (tool == EditTool.Resize) target.copy(width = fit(target.width + deltaX), height = fit(target.height + deltaY))
                        else target.copy(x = fit(target.x + deltaX), y = fit(target.y + deltaY))
                        editor.draft = origin.copy(widgets = origin.widgets.map { if (it.id == target.id) within(moved, origin) else it })
                    }
                }) {
                    drawIntoCanvas {
                        drawing.draw(it.nativeCanvas, styled, state, size.width, font, selected, grid,
                            drawBackground = !useGlass, accent = appearance.accent.toArgb())
                    }
                }
            }
        }
    }
}

@Composable
private fun EditSlider(name: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, apply: (Float) -> Unit) {
    var preview by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$name  ${preview.roundToInt()} $unit", style = MaterialTheme.typography.labelLarge)
        Slider(preview.coerceIn(range.start, range.endInclusive), { preview = it }, valueRange = range,
            modifier = Modifier.semantics { contentDescription = name },
            onValueChangeFinished = { apply(preview) })
    }
}

@Composable
private fun DimField(name: String, value: Float, modifier: Modifier, apply: (Float) -> Unit) {
    var text by remember(value) { mutableStateOf(value.roundToInt().toString()) }
    OutlinedTextField(text, { text = it.filter(Char::isDigit).take(3) }, modifier, label = { Text(name) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        trailingIcon = {
            val target = text.toFloatOrNull()
            if (target != null && target != value.roundToInt().toFloat())
                TextButton({ apply(target) }, Modifier.heightIn(min = 48.dp)) { Text("应用") }
        }, singleLine = true)
}

@Composable
private fun ColorField(name: String, value: Int, modifier: Modifier, apply: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf("%08X".format(value)) }
    val parsed = text.takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt()
    OutlinedTextField(text, { text = it.filter { c -> c in "0123456789ABCDEFabcdef" }.take(8).uppercase() }, modifier,
        label = { Text(name) }, singleLine = true, prefix = { Text("#") },
        trailingIcon = {
            if (parsed != null && parsed != value)
                TextButton({ apply(parsed) }, Modifier.heightIn(min = 48.dp)) { Text("应用") }
        })
}
