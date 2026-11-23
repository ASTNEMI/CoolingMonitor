@file:Suppress("DEPRECATION")
package dev.cooling.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun Context.bluetoothAllowed() = bluetoothPermissions().all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
class BleTransport(context: Context, private val model: MonitorModel, private val settings: SettingsStore) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val state get() = model.state.value
    private var scan: ScanCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanTimeout: Runnable? = null
    private var link: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var target: Device? = null
    private var timer: Runnable? = null
    private var retry: Runnable? = null
    private var poll: Runnable? = null
    private var operationTimeout: Runnable? = null
    private var round = 0
    private var queryIndex = 0
    private var writeInFlight = false
    private var rssiInFlight = false
    private var lastValid = 0L
    private var subscribedAt = 0L
    private var consecutiveFailures = 0
    private var functionalAt = 0L
    private val issued = mutableMapOf<Metric, Long>()

    fun enabled() = runCatching { manager?.adapter?.isEnabled == true }.getOrDefault(false)
    fun supported() = manager?.adapter != null && app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    fun scan() {
        if (state.phase.active) return
        stopScan()
        val adapter = manager?.adapter
        if (!app.bluetoothAllowed() || !enabled()) {
            transition(Phase.Failed, "请开启蓝牙并授予附近设备权限")
            return
        }
        val source = adapter?.bluetoothLeScanner
        if (source == null) {
            transition(Phase.Failed, "此设备没有可用的 BLE 扫描器")
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                main.post {
                    if (scan !== this) return@post
                    val name = result.scanRecord?.deviceName ?: return@post
                    if (name != CoolerProtocol.name) return@post
                    val device = Device(result.device.address, name, result.rssi)
                    model.update { current ->
                        current.copy(discovered = (current.discovered.filterNot { it.address == device.address } + device)
                            .sortedByDescending { it.rssi })
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    if (scan !== this) return@post
                    stopScan()
                    transition(Phase.Failed, "扫描失败（$errorCode），请稍后再试")
                }
            }
        }
        model.update { it.copy(discovered = emptyList()) }
        scan = callback
        scanner = source
        transition(Phase.Scanning, "正在搜索 ${CoolerProtocol.name}")
        val filter = ScanFilter.Builder().setDeviceName(CoolerProtocol.name).build()
        val options = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { source.startScan(listOf(filter), options, callback) }.onFailure {
            stopScan()
            transition(Phase.Failed, "系统拒绝扫描，请检查蓝牙和权限")
            return
        }
        scanTimeout = Runnable { stopScan() }.also { main.postDelayed(it, 12_000) }
    }

    fun stopScan() {
        scanTimeout?.let(main::removeCallbacks)
        scanTimeout = null
        scan?.let { runCatching { scanner?.stopScan(it) } }
        scan = null
        scanner = null
        if (state.phase == Phase.Scanning) transition(
            Phase.Idle, if (state.discovered.isEmpty()) "未发现散热器。请确认已通电并靠近本机。" else "请选择要连接的设备"
        )
    }

    fun connect(device: Device) {
        stop()
        target = device
        model.begin(device)
        consecutiveFailures = 0
        open()
    }

    fun stop() {
        stopScan()
        retry?.let(main::removeCallbacks)
        retry = null
        target = null
        closeLink()
        model.end()
        if (state.phase != Phase.Idle) transition(Phase.Idle, "监控已停止")
    }

    fun bluetoothOff() {
        if (scan != null) stopScan()
        if (target != null) fail("蓝牙已关闭", recover = false)
    }

    private fun open() {
        val device = target ?: return
        if (!app.bluetoothAllowed() || !enabled()) {
            fail("蓝牙已关闭或权限已撤回", recover = false)
            return
        }
        transition(Phase.Connecting, "正在建立 BLE 连接")
        val adapter = manager?.adapter
        link = runCatching { adapter?.getRemoteDevice(device.address)?.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (link == null) {
            fail("系统无法创建 GATT 连接")
            return
        }
        deadline(12_000, "连接超时。请确认散热器可达，必要时先断开官方 App。")
    }

    private fun closeLink() {
        clearDeadline()
        clearOperationTimeout()
        poll?.let(main::removeCallbacks)
        poll = null
        writeInFlight = false
        rssiInFlight = false
        writeCharacteristic = null
        issued.clear()
        round = 0
        queryIndex = 0
        lastValid = 0
        functionalAt = 0
        val previous = link
        link = null
        previous?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        model.interrupt()
    }

    private fun fail(reason: String, recover: Boolean = true) {
        closeLink()
        model.log(reason)
        if (!recover || target == null || !enabled() || !settings.config.value.reconnect || consecutiveFailures >= 5) {
            target = null
            model.end()
            transition(Phase.Failed, reason)
            return
        }
        val wait = listOf(2L, 4L, 8L, 15L, 30L)[consecutiveFailures++]
        model.update { it.copy(retries = it.retries + 1) }
        transition(Phase.Retrying, "$reason · ${wait} 秒后第 $consecutiveFailures 次重连")
        retry = Runnable { retry = null; open() }.also { main.postDelayed(it, wait * 1000) }
    }

    private fun transition(phase: Phase, detail: String) {
        model.update { it.copy(phase = phase, detail = detail) }
        model.log(phase.label)
    }

    private fun deadline(ms: Long, reason: String) {
        clearDeadline()
        timer = Runnable { fail(reason) }.also { main.postDelayed(it, ms) }
    }

    private fun clearDeadline() {
        timer?.let(main::removeCallbacks)
        timer = null
    }

    private fun clearOperationTimeout() {
        operationTimeout?.let(main::removeCallbacks)
        operationTimeout = null
    }

    private fun discover(gatt: BluetoothGatt) {
        if (state.phase != Phase.Negotiating) return
        transition(Phase.Discovering, "正在枚举 GATT 服务")
        if (runCatching { gatt.discoverServices() }.getOrDefault(false)) deadline(7_000, "发现服务超时")
        else fail("系统拒绝发现 GATT 服务")
    }

    private fun schedule(ms: Long, block: () -> Unit) {
        poll?.let(main::removeCallbacks)
        poll = Runnable { poll = null; block() }.also { main.postDelayed(it, ms) }
    }

    private fun beginRound() {
        val now = SystemClock.elapsedRealtime()
        val reference = lastValid.takeIf { it != 0L } ?: subscribedAt
        if (now - reference > 10_000) {
            fail("已订阅，但连续 10 秒没有可解析数据")
            return
        }
        round++
        queryIndex = 0
        nextQuery()
    }

    private fun nextQuery() {
        val gatt = link ?: return
        val characteristic = writeCharacteristic ?: return
        val query = CoolerProtocol.Query.entries.getOrNull(queryIndex++)
        if (query == null) {
            if (round % 5 == 0 && runCatching { gatt.readRemoteRssi() }.getOrDefault(false)) {
                rssiInFlight = true
                operationDeadline("读取信号强度超时")
            } else schedule(CoolerProtocol.pollMs - 50L * CoolerProtocol.Query.entries.size) { beginRound() }
            return
        }
        val mode = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val packet = query.packet()
        val accepted = runCatching {
            if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(characteristic, packet, mode) == BluetoothStatusCodes.SUCCESS
            else {
                characteristic.writeType = mode
                characteristic.value = packet
                gatt.writeCharacteristic(characteristic)
            }
        }.getOrDefault(false)
        if (!accepted) {
            fail("查询写入被系统拒绝")
            return
        }
        issued[query.metric] = SystemClock.elapsedRealtime()
        writeInFlight = true
        operationDeadline("查询写入回调超时")
    }

    private fun operationDeadline(reason: String) {
        clearOperationTimeout()
        operationTimeout = Runnable { fail(reason) }.also { main.postDelayed(it, 4_000) }
    }

    private fun receive(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        val value = bytes.copyOf()
        onLink(gatt) {
            if (!state.subscribed || characteristic.uuid != CoolerProtocol.notify) return@onLink
            val reading = CoolerProtocol.decode(value)
            model.update { it.copy(received = it.received + 1, unknown = it.unknown + if (reading == null) 1 else 0) }
            if (settings.config.value.raw) {
                model.log(reading?.let { "${it.metric.label} ${it.metric.text(it.value)}" } ?: "未识别通知（${value.size} 字节）", value)
            }
            reading?.let {
                val now = SystemClock.elapsedRealtime()
                lastValid = now
                if (functionalAt == 0L) functionalAt = now
                if (now - functionalAt >= 12_000) consecutiveFailures = 0
                val latency = issued.remove(it.metric)?.let { sent -> (now - sent).takeIf { age -> age in 0..2_000 } }
                model.record(it, latency)
            }
        }
    }

    private fun onLink(gatt: BluetoothGatt, block: () -> Unit) { main.post { if (link === gatt) block() } }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            onLink(gatt) {
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("BLE 已断开（GATT $status）")
                } else if (newState == BluetoothProfile.STATE_CONNECTED && state.phase == Phase.Connecting) {
                    clearDeadline()
                    transition(Phase.Negotiating, "请求 MTU 512")
                    model.update { it.copy(mtu = 23, services = emptyList(), rssi = target?.rssi) }
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    if (runCatching { gatt.requestMtu(512) }.getOrDefault(false)) {
                        timer = Runnable {
                            model.log("MTU 回调超时，尝试默认 MTU")
                            discover(gatt)
                        }.also { main.postDelayed(it, 2_500) }
                    } else discover(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            onLink(gatt) {
                if (status == BluetoothGatt.GATT_SUCCESS) model.update { it.copy(mtu = mtu) }
                if (state.phase == Phase.Negotiating) discover(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            onLink(gatt) {
                if (state.phase != Phase.Discovering) return@onLink
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("服务发现失败（$status）")
                    return@onLink
                }
                val inventory = gatt.services.map { service ->
                    "${service.uuid}\n" + service.characteristics.joinToString("\n") { c ->
                        "  ${c.uuid}  0x${c.properties.toString(16)}" +
                            c.descriptors.joinToString("") { "\n    ${it.uuid}" }
                    }
                }
                model.update { it.copy(services = inventory) }
                val service = gatt.getService(CoolerProtocol.service)
                val receive = service?.getCharacteristic(CoolerProtocol.notify)
                val write = service?.getCharacteristic(CoolerProtocol.write)
                val descriptor = receive?.getDescriptor(CoolerProtocol.cccd)
                val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                if (receive == null || write == null || descriptor == null ||
                    receive.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 ||
                    write.properties and writeMask == 0) {
                    fail("设备缺少 FF20 / FF21 / FF22 或通知描述符", recover = false)
                    return@onLink
                }
                writeCharacteristic = write
                transition(Phase.Subscribing, "正在启用 FF22 通知")
                val accepted = runCatching {
                    if (!gatt.setCharacteristicNotification(receive, true)) false
                    else if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                    } else {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                }.getOrDefault(false)
                if (accepted) deadline(6_000, "通知描述符写入超时") else fail("无法启用通知")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            onLink(gatt) {
                if (state.phase != Phase.Subscribing || descriptor.uuid != CoolerProtocol.cccd ||
                    descriptor.characteristic.uuid != CoolerProtocol.notify) return@onLink
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("订阅未完成（GATT $status）")
                    return@onLink
                }
                clearDeadline()
                subscribedAt = SystemClock.elapsedRealtime()
                model.update { it.copy(subscribed = true) }
                transition(Phase.Waiting, "通知已订阅，正在发起只读查询")
                beginRound()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(gatt, characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            characteristic.value?.let { receive(gatt, characteristic, it) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onLink(gatt) {
                if (!writeInFlight || characteristic.uuid != CoolerProtocol.write) return@onLink
                writeInFlight = false
                clearOperationTimeout()
                if (status == BluetoothGatt.GATT_SUCCESS) schedule(50) { nextQuery() }
                else fail("查询写入失败（GATT $status）")
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            onLink(gatt) {
                if (!rssiInFlight) return@onLink
                rssiInFlight = false
                clearOperationTimeout()
                if (status == BluetoothGatt.GATT_SUCCESS) model.update { it.copy(rssi = rssi) }
                schedule(CoolerProtocol.pollMs - 50L * CoolerProtocol.Query.entries.size) { beginRound() }
            }
        }
    }
}
