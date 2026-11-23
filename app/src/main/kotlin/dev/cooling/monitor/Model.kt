package dev.cooling.monitor

import android.app.Application
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Phase(val label: String) {
    Idle("未连接"), Scanning("正在搜索"), Connecting("正在连接"), Negotiating("协商 MTU"),
    Discovering("发现服务"), Subscribing("订阅通知"), Waiting("等待设备上报"),
    Live("监控中"), Retrying("等待重连"), Failed("连接中断");
    val active get() = this in Connecting..Retrying
}

data class Device(val address: String, val name: String, val rssi: Int? = null)
data class Sample(val time: Long, val elapsed: Long, val metric: Metric, val value: Int, val segment: Int)
data class LogEntry(val time: Long, val text: String, val raw: String? = null)
data class MonitorState(
    val phase: Phase = Phase.Idle,
    val detail: String = "",
    val selected: Device? = null,
    val discovered: List<Device> = emptyList(),
    val samples: Map<Metric, Sample> = emptyMap(),
    val currentSegment: Int = 0,
    val trend: List<Sample> = emptyList(),
    val services: List<String> = emptyList(),
    val mtu: Int? = null,
    val subscribed: Boolean = false,
    val received: Long = 0,
    val unknown: Long = 0,
    val repeated: Long = 0,
    val retries: Int = 0,
    val rssi: Int? = null,
    val queryLatencyMs: Long? = null,
    val log: List<LogEntry> = emptyList(),
    val overlay: Boolean = false
) {
    fun current(metric: Metric, now: Long): Sample? = samples[metric]?.takeIf {
        subscribed && phase == Phase.Live && it.segment == currentSegment && now - it.elapsed in 0..CoolerProtocol.staleMs
    }
}

class CoolingApp : Application() {
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this, processScope)
        history = HistoryStore(this, processScope)
        monitor = MonitorModel(history)
        transport = BleTransport(this, monitor, settings)
    }

    val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: SettingsStore
        private set
    lateinit var history: HistoryStore
        private set
    lateinit var monitor: MonitorModel
        private set
    lateinit var transport: BleTransport
        private set
}

class MonitorModel(private val history: HistoryStore) {
    private val mutable = MutableStateFlow(MonitorState())
    val state = mutable.asStateFlow()
    private var segment = 0
    private var session: String? = null
    private val persisted = mutableMapOf<Metric, Sample>()

    fun update(edit: (MonitorState) -> MonitorState) = mutable.update(edit)
    fun begin(device: Device) {
        session = history.begin(device, System.currentTimeMillis())
        segment = 0
        persisted.clear()
        update { MonitorState(selected = device, overlay = it.overlay) }
    }

    fun interrupt() {
        segment++
        update { it.copy(subscribed = false, currentSegment = segment, rssi = null, queryLatencyMs = null) }
    }

    fun record(reading: Reading, latency: Long?) {
        val sample = Sample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), reading.metric, reading.value, segment)
        val previous = state.value.samples[reading.metric]
        if (previous != null && sample.segment == previous.segment && previous.value == sample.value &&
            sample.elapsed - previous.elapsed < 250) {
            update { it.copy(repeated = it.repeated + 1) }
            return
        }
        update {
            val next = if (reading.metric == Metric.Temperature) {
                (it.trend + sample).filter { point -> sample.elapsed - point.elapsed <= 30 * 60_000L }.takeLast(1_000)
            } else it.trend
            it.copy(
                phase = Phase.Live,
                detail = "",
                samples = it.samples + (sample.metric to sample),
                trend = next,
                queryLatencyMs = latency ?: it.queryLatencyMs
            )
        }
        session?.let { id ->
            val saved = persisted[sample.metric]
            val interval = if (sample.metric == Metric.Temperature) 10_000L else 30_000L
            if (saved == null || saved.segment != sample.segment || saved.value != sample.value ||
                sample.elapsed - saved.elapsed >= interval) {
                history.insert(id, sample)
                persisted[sample.metric] = sample
            }
        }
    }

    fun log(text: String, bytes: ByteArray? = null) {
        val raw = bytes?.take(64)?.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
        update { it.copy(log = (it.log + LogEntry(System.currentTimeMillis(), text, raw)).takeLast(120)) }
    }

    fun end() {
        session?.let { history.end(it, System.currentTimeMillis()) }
        session = null
    }
}
