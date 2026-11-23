package dev.cooling.monitor

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun DevicesScreen(state: MonitorState, recent: Device?, permitted: Boolean, supported: Boolean,
                  onScan: () -> Unit, onStopScan: () -> Unit, onConnect: (Device) -> Unit,
                  onStop: () -> Unit, onForget: () -> Unit, onSettings: () -> Unit) {
    ScrollingPage {
        SectionTitle("连接散热器", "搜索已通电的 ${CoolerProtocol.name}。")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhaseChip(state.phase)
            Text(state.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (!supported) Text("此 Android 设备没有 BLE 支持。", color = MaterialTheme.colorScheme.error)
        if (!permitted && supported) {
            Text(if (Build.VERSION.SDK_INT >= 31) "搜索与连接需要附近设备权限。"
                else "此 Android 版本要求授予位置权限并开启系统定位才能扫描 BLE。")
            TextButton(onSettings, Modifier.heightIn(min = 48.dp)) { Text("应用权限设置") }
        }
        when {
            state.phase.active -> OutlinedButton(onStop, Modifier.heightIn(min = 48.dp)) { Text("停止监控") }
            state.phase == Phase.Scanning -> OutlinedButton(onStopScan, Modifier.heightIn(min = 48.dp)) { Text("停止搜索") }
            else -> Button(onScan, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = supported, shape = Design.control) {
                Text(if (permitted) "搜索附近散热器" else "授权并搜索")
            }
        }
        if (state.phase == Phase.Scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.discovered.isNotEmpty()) {
            Text("附近设备", style = MaterialTheme.typography.titleLarge)
            state.discovered.forEach { device ->
                DeviceRow(device, state.phase.active, onConnect)
            }
        }
        if (recent != null) {
            Text("最近设备", style = MaterialTheme.typography.titleLarge)
            DeviceRow(recent, state.phase.active, onConnect)
            TextButton(onForget, Modifier.heightIn(min = 48.dp)) { Text("移除最近设备") }
        }
        if (state.phase == Phase.Failed) {
            Text("如果官方 App 正在连接，请先在官方 App 断开，再重新搜索。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DeviceRow(device: Device, active: Boolean, connect: (Device) -> Unit) {
    StyledSurface(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Design.space, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text("尾号 ${device.address.takeLast(5)}" + (device.rssi?.let { " · $it dBm" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton({ connect(device) }, enabled = !active, modifier = Modifier.heightIn(min = 48.dp)) { Text("连接") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreferencesScreen(config: Config, store: SettingsStore, monitoring: Boolean, stop: () -> Unit,
                      diagnostics: () -> Unit, permissions: () -> Unit) {
    var interval by remember { mutableStateOf(config.refresh.toString()) }
    LaunchedEffect(config.refresh) { interval = config.refresh.toString() }
    ScrollingPage {
        SectionTitle("外观")
        PreferenceGroup {
            Text("界面风格", style = MaterialTheme.typography.titleMedium)
            StyleChoiceRows(config.interfaceStyle, store::interfaceStyle)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingChoices("颜色模式", ThemeChoice.entries, config.theme, { it.label }, store::theme)
            SettingChoices("强调色", Design.accents.indices.toList(), if (config.customAccent == null) config.accent else -1,
                { Design.accents[it] }, store::accent)
            AccentHexField(store)
            SettingChoices("动效", Motion.entries, config.motion, { it.label }, store::motion)
        }
        Spacer(Modifier.height(7.dp))
        SectionTitle("监控与存储")
        PreferenceGroup {
            ToggleRow("自动连接与重连", "启动时尝试连接最近设备，意外断线后有限重试。", config.reconnect, store::reconnect)
            val refreshes = listOf(0, 200, 500, 1_000, 2_000)
            SettingChoices("界面刷新", refreshes, config.refresh, { if (it == 0) "自动" else "$it ms" }, store::refresh)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f),
                    label = { Text("自定义 UI 间隔（毫秒）") }, supportingText = { Text("200–5000 ms，采样由设备决定") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                val input = interval.toIntOrNull()
                FilledTonalButton({ if (input != null) store.refresh(input) }, enabled = input in 200..5000 && input != config.refresh,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("应用") }
            }
            SettingChoices("历史保留时间", listOf(1, 7, 30, 90), config.days, { "$it 天" }, store::days)
        }
        TextButton(diagnostics, Modifier.heightIn(min = 48.dp)) { Text("通信诊断") }
        TextButton(permissions, Modifier.heightIn(min = 48.dp)) { Text("系统权限设置") }
        if (monitoring) OutlinedButton(stop, Modifier.heightIn(min = 48.dp)) { Text("停止后台监控") }
    }
}

@Composable
private fun PreferenceGroup(content: @Composable ColumnScope.() -> Unit) {
    StyledSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(Design.space), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun StyleChoiceRows(current: InterfaceStyle, select: (InterfaceStyle) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        InterfaceStyle.entries.forEach { style ->
            val selected = current == style
            val enabled = style != InterfaceStyle.LiquidGlass || Build.VERSION.SDK_INT >= 33
            val description = when {
                style == InterfaceStyle.ColorOS -> "中性色分组、圆角控件与蓝色强调"
                enabled -> "全应用折射材质，覆盖导航、面板和弹窗"
                else -> "需要 Android 13 或更新版本"
            }
            StyledSurface(Modifier.fillMaxWidth(), shape = Design.control,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                tintAlpha = if (selected) 0.91f else 0.67f) {
                Row(Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton,
                    onClick = { select(style) }).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(style.label, style = MaterialTheme.typography.titleMedium)
                        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected, onClick = null, enabled = enabled)
                }
            }
        }
    }
}

@Composable
private fun AccentHexField(store: SettingsStore) {
    val accent = LocalAppearance.current.accent.toArgb() and 0xffffff
    var text by remember(accent) { mutableStateOf("%06X".format(accent)) }
    val parsed = text.takeIf { it.length == 6 }?.toIntOrNull(16)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(text,
            { text = it.filter { digit -> digit in "0123456789ABCDEFabcdef" }.take(6).uppercase() },
            label = { Text("自定义强调色") }, prefix = { Text("#") }, modifier = Modifier.weight(1f), singleLine = true)
        FilledTonalButton({ parsed?.let(store::customAccent) }, enabled = parsed != null && parsed != accent,
            modifier = Modifier.heightIn(min = 48.dp)) { Text("应用") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingChoices(title: String, options: List<T>, selected: T, label: (T) -> String, select: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { item ->
                FilterChip(item == selected, { select(item) }, { Text(label(item)) }, Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, detail: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}

@Composable
fun DiagnosticsScreen(state: MonitorState, config: Config, settings: SettingsStore, message: (String) -> Unit) {
    val request = rememberCoroutineScope()
    val context = LocalContext.current
    var payload by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) request.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(payload) }
                }
            }.isSuccess
            message(if (saved) "诊断日志已保存" else "无法写入诊断日志")
        }
    }
    fun report() = buildString {
        appendLine("回路 · 通信诊断")
        appendLine("设备=${state.selected?.name ?: "未连接"}")
        appendLine("状态=${state.phase.label} MTU=${state.mtu} RSSI=${state.rssi} 订阅=${state.subscribed}")
        appendLine("接收=${state.received} 未识别=${state.unknown} 重复=${state.repeated} 重连=${state.retries}")
        appendLine("最近查询耗时毫秒=${state.queryLatencyMs}")
        state.services.forEach(::appendLine)
        state.log.forEach { event ->
            val header = event.raw?.takeUnless { event.text.startsWith("未识别") }
                ?.split(' ')?.take(4)?.joinToString(" ")
            val prefix = header?.let { " [起始字节 $it · 其余已省略]" }.orEmpty()
            appendLine("${Instant.ofEpochMilli(event.time)} ${event.text}$prefix")
        }
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle("通信诊断", "显示当前会话。导出省略地址与原始负载，仅保留已识别帧的起始字段。") }
        item {
            PreferenceGroup {
                Text(state.selected?.name ?: "未选择设备", style = MaterialTheme.typography.titleMedium)
                Text("${state.phase.label} · MTU ${state.mtu ?: "—"} · RSSI ${state.rssi?.let { "$it dBm" } ?: "—"}")
                Text("通知 ${if (state.subscribed) "已订阅" else "未订阅"} · 接收 ${state.received} · 重连 ${state.retries}")
                Text("未知 ${state.unknown} · 重复 ${state.repeated} · 查询 ${state.queryLatencyMs?.let { "$it ms" } ?: "—"}")
            }
        }
        item {
            PreferenceGroup {
                ToggleRow("详细帧记录", "仅在当前进程内保留最近 120 条记录。", config.raw, settings::raw)
                FilledTonalButton({ payload = report(); export.launch("cooling-diagnostics.txt") },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("导出诊断") }
            }
        }
        if (state.services.isNotEmpty()) {
            item { Text("已发现服务与特征", style = MaterialTheme.typography.titleLarge) }
            items(state.services) { service ->
                PreferenceGroup { Text(service, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item { Text("最近事件", style = MaterialTheme.typography.titleLarge) }
        items(state.log.asReversed()) { log ->
            PreferenceGroup {
                Text("${clockText(log.time)}  ${log.text}", style = MaterialTheme.typography.bodyMedium)
                if (config.raw && !log.raw.isNullOrBlank()) {
                    Text(log.raw, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

data class Dates(val from: Long, val to: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(app: CoolingApp, message: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rangeDays by remember { mutableIntStateOf(1) }
    var custom by remember { mutableStateOf<Dates?>(null) }
    var currentDevice by remember { mutableStateOf<String?>(null) }
    var showDates by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var exportArgs by remember { mutableStateOf<Triple<Long, Long, String?>?>(null) }
    val latest by app.monitor.state.collectAsState()
    val sampleBucket = (latest.samples[Metric.Temperature]?.time ?: 0L) / 5_000
    val dates = remember(custom, rangeDays, sampleBucket, revision) {
        custom ?: run {
            val zone = ZoneId.systemDefault()
            val start = LocalDate.now(zone).minusDays(rangeDays - 1L).atStartOfDay(zone).toInstant().toEpochMilli()
            Dates(start, System.currentTimeMillis())
        }
    }
    val devices by produceState(emptyList<HistoryDevice>(), revision, latest.selected) {
        runCatching { app.history.devices() }.onSuccess { value = it }.onFailure { message("无法读取设备历史") }
    }
    val page by produceState<HistoryPage?>(null, dates, currentDevice, revision) {
        runCatching { app.history.page(dates.from, dates.to, currentDevice) }
            .onSuccess { value = it }.onFailure { message("无法读取历史记录") }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val args = exportArgs
        if (uri != null && args != null) scope.launch {
            val success = runCatching {
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { writer ->
                        app.history.csv(args.first, args.second, args.third, writer)
                    }
                }
            }.isSuccess
            message(if (success) "已保存 CSV" else "无法写入 CSV")
        }
    }
    if (showDates) {
        val datePicker = rememberDateRangePickerState()
        Dialog(onDismissRequest = { showDates = false }) {
            StyleDialogContent(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    DateRangePicker(datePicker, modifier = Modifier.heightIn(max = 420.dp),
                        colors = DatePickerDefaults.colors(containerColor = Color.Transparent))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton({ showDates = false }) { Text("取消") }
                        TextButton({
                            val zone = ZoneId.systemDefault()
                            fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            val from = local(requireNotNull(datePicker.selectedStartDateMillis)).atStartOfDay(zone).toInstant().toEpochMilli()
                            val end = local(requireNotNull(datePicker.selectedEndDateMillis)).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                            custom = Dates(from, end)
                            showDates = false
                        }, enabled = datePicker.selectedStartDateMillis != null && datePicker.selectedEndDateMillis != null) { Text("应用日期") }
                    }
                }
            }
        }
    }
    if (showDelete) {
        StyledDialog(onDismissRequest = { showDelete = false }, title = { Text("删除筛选范围内的记录？") },
            text = { Text("将删除当前设备与日期筛选范围内的记录。") },
            dismissButton = { TextButton({ showDelete = false }) { Text("取消") } },
            confirmButton = {
                TextButton({
                    showDelete = false
                    scope.launch {
                        runCatching { app.history.delete(dates.from, dates.to, currentDevice) }
                            .onSuccess { revision++; message("已删除筛选范围内的记录") }
                            .onFailure { message("删除失败") }
                    }
                }) { Text("删除记录") }
            })
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle("监控历史") }
        item {
            SettingChoices("日期", listOf(1, 7, 30, 0), if (custom == null) rangeDays else 0, {
                when (it) { 1 -> "今天"; 0 -> "选择日期"; else -> "近 $it 天" }
            }) {
                if (it == 0) showDates = true else { rangeDays = it; custom = null }
            }
            if (custom != null) {
                val zone = ZoneId.systemDefault()
                Text("${Instant.ofEpochMilli(dates.from).atZone(zone).toLocalDate()} 至 ${Instant.ofEpochMilli(dates.to).atZone(zone).toLocalDate()}")
            }
        }
        if (devices.isNotEmpty()) item {
            SettingChoices("设备", listOf<String?>(null) + devices.map { it.id }, currentDevice,
                { id -> devices.firstOrNull { it.id == id }?.let { "${it.name} · ${it.id.takeLast(4)}" } ?: "全部设备" },
                { currentDevice = it })
        }
        item {
            PreferenceGroup {
                val result = page
                Text(if (result == null) "正在读取记录" else "${result.count} 条读数 · ${result.sessions} 次会话", style = MaterialTheme.typography.titleMedium)
                if (result?.average != null) Text("温度最低 ${result.min} °C · 最高 ${result.max} °C · 平均 ${"%.1f".format(result.average)} °C")
                if (result?.count == 0) Text("监控会话收到设备数据后，读数会出现在这里。")
                if ((result?.count ?: 0) > 160) Text("列表显示最新 160 条。导出包含筛选范围内的全部读数。", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({
                        exportArgs = Triple(dates.from, dates.to, currentDevice)
                        csv.launch("cooling-history.csv")
                    }, enabled = (result?.count ?: 0) > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("导出 CSV") }
                    TextButton({ showDelete = true }, enabled = (result?.count ?: 0) > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("删除记录") }
                }
            }
        }
        items(page?.rows ?: emptyList()) { row ->
            StyledSurface(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Design.space, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(row.metric.label, style = MaterialTheme.typography.titleMedium)
                    Text("${Instant.ofEpochMilli(row.time).atZone(ZoneId.systemDefault()).toLocalDate()} · ${clockText(row.time)} · 会话 ${row.session.take(5)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                Text(row.metric.text(row.value), style = MaterialTheme.typography.titleMedium)
            }
            }
        }
    }
}
