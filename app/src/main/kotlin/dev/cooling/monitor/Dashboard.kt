package dev.cooling.monitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
fun clockText(time: Long) = clockFormat.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

@Composable
fun Dashboard(state: MonitorState, now: Long, wide: Boolean, devices: () -> Unit, overlay: () -> Unit, diagnostics: () -> Unit) {
    ScrollingPage {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text("散热器监控", style = MaterialTheme.typography.headlineLarge)
                Text(state.selected?.name ?: CoolerProtocol.name, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            PhaseChip(state.phase)
        }
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Design.space)) {
                Column(Modifier.width(285.dp), verticalArrangement = Arrangement.spacedBy(Design.space)) {
                    Thermometer(state, now)
                    QuickActions(state, devices, overlay, diagnostics)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Design.space)) {
                    ChartPanel(state, now)
                    DeviceParameters(state, now)
                }
            }
        } else {
            Thermometer(state, now)
            if (!state.phase.active) Button(devices, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = Design.control) {
                Text(if (state.selected == null) "搜索散热器" else "管理设备")
            }
            ChartPanel(state, now)
            DeviceParameters(state, now)
            QuickActions(state, devices, overlay, diagnostics, showDevice = state.phase.active)
        }
    }
}

@Composable
fun PhaseChip(phase: Phase) {
    val colors = MaterialTheme.colorScheme
    val color = when (phase) { Phase.Live -> colors.primary; Phase.Failed -> colors.error; else -> colors.onSurfaceVariant }
    StyledSurface(color = colors.surfaceVariant, shape = Design.control) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(7.dp).background(color, androidx.compose.foundation.shape.CircleShape))
            Text(phase.label, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Thermometer(state: MonitorState, now: Long) {
    val value = state.current(Metric.Temperature, now)
    val previous = state.samples[Metric.Temperature]
    StyledSurface(color = MaterialTheme.colorScheme.surface, shape = Design.panel) {
        Column(Modifier.fillMaxWidth().padding(Design.space), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("设备温度", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics { contentDescription = value?.let { "设备温度 ${it.value} 摄氏度" } ?: "设备温度暂无实时读数" }) {
                Text(value?.value?.toString() ?: "—", style = Design.readout, color = MaterialTheme.colorScheme.primary)
                Text("°C", style = Design.measurement, modifier = Modifier.padding(bottom = 9.dp))
            }
            val caption = when {
                value != null -> "上报于 ${clockText(value.time)}"
                previous != null -> "上次读数 ${previous.value} °C · ${clockText(previous.time)} · 已过期"
                state.subscribed -> "等待设备上报温度。"
                state.detail.isNotBlank() -> state.detail
                else -> "连接散热器后读取温度。"
            }
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickActions(state: MonitorState, devices: () -> Unit, overlay: () -> Unit, diagnostics: () -> Unit,
                         showDevice: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (showDevice) FilledTonalButton(devices, shape = Design.control, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (state.phase.active) "管理设备" else "搜索散热器")
        }
        OutlinedButton(overlay, shape = Design.control, modifier = Modifier.heightIn(min = 48.dp)) { Text("编辑悬浮窗") }
        TextButton(diagnostics, modifier = Modifier.heightIn(min = 48.dp)) { Text("通信诊断") }
    }
}

@Composable
private fun DeviceParameters(state: MonitorState, now: Long) {
    val metrics = Metric.entries.filter { it != Metric.Temperature && it in state.samples }
    if (metrics.isEmpty()) return
    Column {
        Text("设备参数", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
        metrics.forEach { metric ->
            val latest = state.current(metric, now)
            val historic = state.samples.getValue(metric)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(metric.label, style = MaterialTheme.typography.bodyLarge)
                Text(latest?.let { metric.text(it.value) } ?: "${metric.text(historic.value)} · 已过期",
                    color = if (latest == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private val periods = listOf(2, 5, 15, 30)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartPanel(state: MonitorState, now: Long) {
    var minutes by remember { mutableIntStateOf(5) }
    var paused by remember { mutableStateOf<Pair<Long, List<Sample>>?>(null) }
    var selected by remember { mutableLongStateOf(-1L) }
    val edge = paused?.first ?: now
    val start = edge - minutes * 60_000L
    val series = paused?.second ?: state.trend
    val points = remember(series, edge, start) { series.filter { it.elapsed in start..edge } }
    val low = points.minOfOrNull { it.value }
    val high = points.maxOfOrNull { it.value }
    val chosen = points.firstOrNull { it.elapsed == selected }
    StyledSurface(shape = Design.panel, color = Design.scope, contentColor = Color.White, tintAlpha = 0.94f) {
        Column(Modifier.fillMaxWidth().padding(Design.space), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text("温度走势", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton({
                    paused = if (paused == null) now to state.trend else null
                    selected = -1L
                }, colors = ButtonDefaults.textButtonColors(contentColor = Design.chartLine), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (paused != null) "继续跟随" else "暂停观察")
                }
            }
            val caption = chosen?.let { "${clockText(it.time)} · ${it.value} °C" }
                ?: if (points.isEmpty()) "等待设备上报" else "点按曲线查看测量时间"
            Text(caption, color = Design.scopeText, style = MaterialTheme.typography.bodyMedium)
            Plot(points, start, edge, selected, modifier = Modifier.fillMaxWidth().height(143.dp)) { selected = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$minutes 分钟前", color = Design.scopeText, style = MaterialTheme.typography.labelMedium)
                Text(if (paused != null) clockText(paused!!.second.lastOrNull()?.time ?: System.currentTimeMillis()) else "现在",
                    color = Design.scopeText, style = MaterialTheme.typography.labelMedium)
            }
            if (low != null && high != null) {
                val mean = "%.1f".format(points.map { it.value }.average())
                Text("最低 $low °C   最高 $high °C   平均 $mean °C", color = Design.scopeText, style = MaterialTheme.typography.bodyMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                periods.forEach { choice ->
                    FilterChip(minutes == choice, { minutes = choice; selected = -1L }, { Text("$choice 分钟") },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = Design.scopeText, selectedContainerColor = Design.chartLine,
                            selectedLabelColor = Design.scope
                        ))
                }
            }
        }
    }
}

@Composable
private fun Plot(points: List<Sample>, from: Long, to: Long, selected: Long, modifier: Modifier, select: (Long) -> Unit) {
    val measurer = rememberTextMeasurer()
    val current by rememberUpdatedState(Triple(points, from, to))
    val choose by rememberUpdatedState(select)
    val hint = if (points.isEmpty()) "温度图暂无数据" else "温度图，共 ${points.size} 个测量点"
    Canvas(modifier.semantics { contentDescription = hint }.pointerInput(Unit) {
        detectTapGestures { tap ->
            val (values, start, end) = current
            val left = 38.dp.toPx()
            val at = (start + (tap.x - left).coerceAtLeast(0f) / (size.width - left).coerceAtLeast(1f) * (end - start)).toLong()
            values.minByOrNull { abs(it.elapsed - at) }?.let { choose(it.elapsed) }
        }
    }) {
        val left = 38.dp.toPx()
        val width = (size.width - left - 5.dp.toPx()).coerceAtLeast(1f)
        val top = 10.dp.toPx()
        val height = (size.height - top * 2).coerceAtLeast(1f)
        val minimum = points.minOfOrNull { it.value }?.minus(1) ?: 0
        val maximum = points.maxOfOrNull { it.value }?.plus(1) ?: 1
        repeat(5) { grid ->
            val y = top + height * grid / 4
            drawLine(Design.scopeLine, Offset(left, y), Offset(left + width, y))
            if (points.isNotEmpty() && (grid == 0 || grid == 4)) {
                drawText(measurer, if (grid == 0) "$maximum°" else "$minimum°",
                    topLeft = Offset(0f, y - 8.dp.toPx()), style = TextStyle(color = Design.scopeText, fontSize = 12.sp))
            }
        }
        repeat(5) { grid ->
            val x = left + width * grid / 4
            drawLine(Design.scopeLine, Offset(x, top), Offset(x, top + height))
        }
        fun position(value: Sample) = Offset(
            left + (value.elapsed - from).toFloat() / (to - from).coerceAtLeast(1) * width,
            top + (maximum - value.value).toFloat() / (maximum - minimum) * height
        )
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            if (a.segment == b.segment && b.elapsed - a.elapsed in 1..CoolerProtocol.staleMs)
                drawLine(Design.chartLine, position(a), position(b), strokeWidth = 2.3.dp.toPx())
            else drawCircle(Design.chartLine, 2.1.dp.toPx(), position(a))
        }
        points.lastOrNull()?.let { drawCircle(Design.chartLine, 3.7.dp.toPx(), position(it)) }
        points.firstOrNull { it.elapsed == selected }?.let {
            val pos = position(it)
            drawLine(Color.White, Offset(pos.x, top), Offset(pos.x, top + height))
            drawCircle(Color.White, 4.dp.toPx(), pos)
        }
    }
}
