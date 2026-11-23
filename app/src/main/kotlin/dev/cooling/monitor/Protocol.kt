package dev.cooling.monitor

import java.util.UUID

enum class Metric(val label: String, val unit: String = "") {
    Temperature("设备温度", "°C"),
    Power("上报功率", "W"),
    Fan("风扇档位"),
    Level("功率档位"),
    Running("设备开关"),
    TemperatureMode("温控"),
    Boost("超频"),
    Quiet("静音");

    fun text(value: Int): String = when (this) {
        Running -> if (value == 1) "开启" else "关闭"
        Fan, Level -> if (value == 0) "关闭" else "档位 $value"
        TemperatureMode, Boost, Quiet -> if (value == 0) "关闭" else "开启"
        else -> if (unit.isEmpty()) "$value" else "$value $unit"
    }
}

data class Reading(val metric: Metric, val value: Int)

object CoolerProtocol {
    const val name = "NiShen-CX2-pro"
    const val pollMs = 2_000L
    const val staleMs = 7_000L
    private const val size = 12
    private fun uuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    val service = uuid("ff20")
    val write = uuid("ff21")
    val notify = uuid("ff22")
    val cccd = uuid("2902")

    enum class Query(val code: Int, val metric: Metric) {
        Temperature(0x0e, Metric.Temperature),
        Power(0x0d, Metric.Power),
        Fan(0x03, Metric.Fan),
        Level(0x05, Metric.Level),
        Running(0x02, Metric.Running),
        TemperatureMode(0x0a, Metric.TemperatureMode),
        Boost(0x0b, Metric.Boost),
        Quiet(0x0c, Metric.Quiet);

        fun packet() = ByteArray(size).apply {
            this[0] = 0xf5.toByte()
            this[1] = 0x55
            this[2] = (0x80 or code).toByte()
        }
    }

    private val queries = Query.entries.associateBy { it.code }

    fun decode(notification: ByteArray): Reading? {
        if (notification.size < size) return null
        val query = queries[notification[2].toInt() and 0xff] ?: return null
        val byte = notification[3]
        val raw = byte.toInt() and 0xff
        val value = when (query) {
            Query.Temperature -> byte.toInt()
            Query.Running -> raw.takeIf { it in 1..2 } ?: return null
            Query.Fan -> raw.takeIf { it in 0..6 } ?: return null
            else -> raw
        }
        return Reading(query.metric, value)
    }
}
