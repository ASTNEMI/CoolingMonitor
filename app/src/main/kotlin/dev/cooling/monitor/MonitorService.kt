package dev.cooling.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MonitorService : Service() {
    private val app get() = application as CoolingApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlay: OverlayWindow
    private lateinit var notifications: NotificationManager
    private var foreground = false
    private val bluetooth = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val next = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (next == BluetoothAdapter.STATE_OFF || next == BluetoothAdapter.STATE_TURNING_OFF) app.transport.bluetoothOff()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(channel, "散热器监控", NotificationManager.IMPORTANCE_LOW))
        overlay = OverlayWindow(windowContext(), app.settings.layouts) { enabled ->
            app.monitor.update { it.copy(overlay = enabled) }
        }
        ContextCompat.registerReceiver(this, bluetooth, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        scope.launch {
            app.settings.layouts.layout.collect { overlay.updateLayout(it) }
        }
        scope.launch {
            app.settings.config.collect { overlay.updateAppearance(it) }
        }
        scope.launch {
            app.monitor.state.map { it.phase to it.overlay }.distinctUntilChanged().collect {
                if (foreground) notifications.notify(id, notification(it.first, it.second))
            }
        }
        scope.launch {
            while (true) {
                val config = app.settings.config.value
                val state = app.monitor.state.value
                if (state.overlay) overlay.update(state)
                delay(if (state.overlay) (config.refresh.takeIf { it != 0 } ?: 250).toLong() else 500)
            }
        }
        scope.launch {
            app.settings.config.map { it.days }.distinctUntilChanged().collect { app.history.prune(it) }
        }
        scope.launch {
            while (true) {
                delay(3_600_000)
                app.history.prune(app.settings.config.value.days)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun windowContext(): Context {
        if (Build.VERSION.SDK_INT < 30) return this
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return this
        return createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == Stop || !bluetoothAllowed()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foreground) {
            val state = app.monitor.state.value
            val started = runCatching {
                ServiceCompat.startForeground(
                    this, id, notification(state.phase, state.overlay),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
                )
            }.isSuccess
            if (!started) {
                app.monitor.log("系统拒绝启动前台服务，请检查蓝牙权限")
                stopSelf()
                return START_NOT_STICKY
            }
            foreground = true
        }
        when (action) {
            Connect -> intent.getStringExtra(Address)?.let { address ->
                if (android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) {
                    val rssi = intent.getIntExtra(Rssi, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
                    val device = Device(address, CoolerProtocol.name, rssi)
                    app.settings.save(device)
                    app.transport.connect(device)
                }
            }
            Toggle -> if (!overlay.toggle()) app.monitor.log("悬浮窗无法显示，请检查显示在其他应用上层的权限")
            Restore -> app.settings.layouts.apply(app.settings.layouts.layout.value.copy(mode = OverlayMode.Move))
        }
        return START_NOT_STICKY
    }

    private fun command(action: String, request: Int): PendingIntent =
        PendingIntent.getService(this, request, Intent(this, MonitorService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(phase: Phase, visible: Boolean): Notification {
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("回路 · 散热器监控")
            .setContentText(if (visible) "${phase.label} · 悬浮窗显示中" else phase.label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(0, "恢复悬浮窗触摸", command(Restore, 1))
            .addAction(0, "停止监控", command(Stop, 2))
            .build()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay.reposition()
    }

    override fun onDestroy() {
        unregisterReceiver(bluetooth)
        scope.cancel()
        overlay.hide()
        app.transport.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val Connect = "dev.cooling.monitor.CONNECT"
        const val Toggle = "dev.cooling.monitor.OVERLAY"
        const val Restore = "dev.cooling.monitor.RESTORE_TOUCH"
        const val Stop = "dev.cooling.monitor.STOP"
        const val Address = "address"
        const val Rssi = "rssi"
        private const val id = 17
        private const val channel = "monitor"
    }
}
