package dev.cooling.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as CoolingApp
    private var afterPermission: (() -> Unit)? = null
    private var feedback by mutableStateOf("")
    private var allowed by mutableStateOf(false)
    private var overlayAllowed by mutableStateOf(false)
    private var firstResume = true

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = bluetoothAllowed()
        val next = afterPermission
        afterPermission = null
        if (allowed) next?.invoke()
        else feedback = "需要附近设备权限才能连接。可在系统应用设置中重新授权。"
    }
    private val enableBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val next = afterPermission
        afterPermission = null
        if (app.transport.enabled()) next?.invoke()
        else feedback = "蓝牙仍处于关闭状态。"
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        overlayAllowed = Settings.canDrawOverlays(this)
        if (overlayAllowed) send(MonitorService.Toggle) else feedback = "未授予悬浮窗权限。"
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) feedback = "通知权限未开启。仍可从应用内恢复触摸或停止监控。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        firstResume = savedInstanceState == null
        setContent {
            val settings by app.settings.config.collectAsStateWithLifecycle()
            val live by app.monitor.state.collectAsStateWithLifecycle()
            val layout by app.settings.layouts.layout.collectAsStateWithLifecycle()
            val custom by app.settings.layouts.presets.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            var screen by rememberSaveable { mutableStateOf(Screen.Dashboard) }
            var recent by remember { mutableStateOf(app.settings.saved()) }
            LaunchedEffect(feedback) {
                if (feedback.isNotBlank()) {
                    val message = feedback
                    feedback = ""
                    snackbar.showSnackbar(message)
                }
            }
            LaunchedEffect(live.selected) { recent = app.settings.saved() }
            BackHandler(screen != Screen.Dashboard) {
                screen = when (screen) {
                    Screen.Devices -> Screen.Dashboard
                    Screen.Diagnostics -> Screen.Settings
                    else -> Screen.Dashboard
                }
            }
            CoolingTheme(settings) {
                val dark = LocalAppearance.current.dark
                SideEffect {
                    val bar = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
                }
                val frame by produceState(live, settings.refresh) {
                    while (true) {
                        val current = app.monitor.state.value
                        if (value != current) value = current
                        delay((settings.refresh.takeIf { it != 0 } ?: 200).toLong())
                    }
                }
                val now by produceState(SystemClock.elapsedRealtime(), settings.refresh) {
                    while (true) {
                        value = SystemClock.elapsedRealtime()
                        delay((settings.refresh.takeIf { it != 0 } ?: 500).toLong())
                    }
                }
                AppShell(screen, settings, snackbar, onChange = { screen = it }) { current, wide ->
                    when (current) {
                        Screen.Dashboard -> Dashboard(frame.copy(phase = live.phase, detail = live.detail, subscribed = live.subscribed),
                            now, wide, { screen = Screen.Devices }, { screen = Screen.Overlay }, { screen = Screen.Diagnostics })
                        Screen.Devices -> DevicesScreen(live, recent, allowed, app.transport.supported(),
                            onScan = { withBluetooth { app.transport.scan() } },
                            onStopScan = { app.transport.stopScan() },
                            onConnect = { device -> withBluetooth { send(MonitorService.Connect, device); askNotification() } },
                            onStop = ::stopMonitoring,
                            onForget = { app.settings.forget(); recent = null },
                            onSettings = ::appPermissions)
                        Screen.Overlay -> OverlayEditor(layout, custom, live.overlay, overlayAllowed, frame, app.settings.layouts,
                            onToggle = {
                                withBluetooth {
                                    if (Settings.canDrawOverlays(this)) send(MonitorService.Toggle)
                                    else overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName")))
                                }
                            }, message = { feedback = it })
                        Screen.History -> HistoryScreen(app, message = { feedback = it })
                        Screen.Settings -> PreferencesScreen(settings, app.settings, live.phase.active,
                            ::stopMonitoring, { screen = Screen.Diagnostics }, ::appPermissions)
                        Screen.Diagnostics -> DiagnosticsScreen(live, settings, app.settings, { feedback = it })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        allowed = bluetoothAllowed()
        overlayAllowed = Settings.canDrawOverlays(this)
        if (firstResume) {
            firstResume = false
            val saved = app.settings.saved()
            if (saved != null && allowed && app.transport.enabled() && !app.monitor.state.value.phase.active) {
                lifecycleScope.launch {
                    if (app.settings.autoReconnectEnabled()) {
                        lifecycle.withResumed {
                            val phase = app.monitor.state.value.phase
                            if (!phase.active && phase != Phase.Scanning && app.settings.saved() == saved) {
                                send(MonitorService.Connect, saved)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        app.transport.stopScan()
        super.onStop()
    }

    @SuppressLint("MissingPermission")
    private fun withBluetooth(action: () -> Unit) {
        if (!app.transport.supported()) { feedback = "此设备没有 BLE 支持。"; return }
        if (!bluetoothAllowed()) {
            afterPermission = { withBluetooth(action) }
            bluetoothPermission.launch(bluetoothPermissions())
        } else if (!app.transport.enabled()) {
            afterPermission = action
            runCatching { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                .onFailure { feedback = "请在系统设置中开启蓝牙。" }
        } else action()
    }

    private fun askNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun appPermissions() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun send(action: String, device: Device? = null) {
        val intent = Intent(this, MonitorService::class.java).setAction(action)
        device?.let {
            intent.putExtra(MonitorService.Address, it.address)
            it.rssi?.let { strength -> intent.putExtra(MonitorService.Rssi, strength) }
        }
        runCatching { ContextCompat.startForegroundService(this, intent) }
            .onFailure { feedback = "系统无法启动监控服务。请检查蓝牙权限。" }
    }

    private fun stopMonitoring() {
        stopService(Intent(this, MonitorService::class.java))
        app.transport.stop()
    }
}

enum class Screen(val label: String, val icon: ImageVector? = null) {
    Dashboard("监控", Icons.Default.Home),
    Overlay("悬浮窗", Icons.Default.Build),
    History("历史", Icons.AutoMirrored.Filled.List),
    Settings("设置", Icons.Default.Settings),
    Devices("设备"),
    Diagnostics("诊断")
}

@Composable
private fun AppShell(screen: Screen, settings: Config, snackbar: SnackbarHostState, onChange: (Screen) -> Unit,
                     content: @Composable (Screen, Boolean) -> Unit) {
    StyleScene(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val tabs = Screen.entries.filter { it.icon != null }
            val selected = when (screen) {
                Screen.Devices -> Screen.Dashboard
                Screen.Diagnostics -> Screen.Settings
                else -> screen
            }
            Row(Modifier.fillMaxSize()) {
                if (wide) {
                    StyledSurface(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                        .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp).fillMaxHeight(),
                        shape = Design.navigation, color = MaterialTheme.colorScheme.surfaceContainer) {
                        NavigationRail(Modifier.fillMaxHeight(), containerColor = Color.Transparent) {
                            Spacer(Modifier.height(18.dp))
                            tabs.forEach { tab ->
                                NavigationRailItem(selected == tab, { onChange(tab) }, { Icon(tab.icon!!, null) },
                                    label = { Text(tab.label) })
                            }
                        }
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    snackbarHost = { SnackbarHost(snackbar) },
                    topBar = {
                        val insets = Modifier.fillMaxWidth().windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        ).padding(horizontal = 12.dp, vertical = 3.dp)
                        StyleControl(insets) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).heightIn(min = 60.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (screen == Screen.Devices || screen == Screen.Diagnostics) {
                                    IconButton({ onChange(selected) }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                                    }
                                } else {
                                    Image(painterResource(R.drawable.ic_loop), null, Modifier.padding(end = 10.dp).size(32.dp))
                                }
                                Text(if (screen == Screen.Dashboard) "回路" else screen.label, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    },
                    bottomBar = {
                        if (!wide) {
                            StyledSurface(
                                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 14.dp, vertical = 6.dp),
                                shape = Design.navigation, color = MaterialTheme.colorScheme.surface, tintAlpha = 0.68f
                            ) {
                                NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0.dp), tonalElevation = 0.dp) {
                                    tabs.forEach { tab ->
                                        NavigationBarItem(selected == tab, { onChange(tab) }, { Icon(tab.icon!!, null) },
                                            label = { Text(tab.label) })
                                    }
                                }
                            }
                        }
                    }
                ) { padding ->
                    val duration = when (settings.motion) { Motion.Full -> 185; Motion.Balanced -> 125; Motion.Reduced -> 0 }
                    AnimatedContent(screen, Modifier.fillMaxSize().padding(padding).imePadding(),
                        transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration / 2)) },
                        label = "页面切换") { current ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 1050.dp).fillMaxWidth().fillMaxHeight()
                                .padding(horizontal = if (wide) 32.dp else 18.dp)) { content(current, wide) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScrollingPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Design.space)) {
        Spacer(Modifier.height(1.dp))
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun SectionTitle(title: String, detail: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
