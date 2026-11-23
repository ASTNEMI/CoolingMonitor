package dev.cooling.monitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    private fun frame(type: Int, value: Int) = ByteArray(12).apply {
        this[0] = 0xf5.toByte()
        this[1] = 0x55
        this[2] = type.toByte()
        this[3] = value.toByte()
    }

    @Test fun temperatureQueryMatchesApkConstant() {
        assertArrayEquals(byteArrayOf(-11, 85, -114, 0, 0, 0, 0, 0, 0, 0, 0, 0), CoolerProtocol.Query.Temperature.packet())
    }

    @Test fun checksAllReadOnlyRequestTypes() {
        assertEquals(listOf(0x8e, 0x8d, 0x83, 0x85, 0x82, 0x8a, 0x8b, 0x8c),
            CoolerProtocol.Query.entries.map { it.packet()[2].toInt() and 255 })
    }

    @Test fun interpretsTemperatureAsSignedCelsius() {
        listOf(0 to 0, 18 to 18, 127 to 127, 255 to -1, 128 to -128, 246 to -10).forEach { (wire, expected) ->
            assertEquals(Reading(Metric.Temperature, expected), CoolerProtocol.decode(frame(0x0e, wire)))
        }
    }

    @Test fun interpretsPowerAsUnsignedDeviceReport() {
        assertEquals(Reading(Metric.Power, 250), CoolerProtocol.decode(frame(0x0d, 250)))
    }

    @Test fun rejectsTruncatedUnrecognizedAndOutgoingPackets() {
        assertNull(CoolerProtocol.decode(frame(0x0e, 20).copyOf(11)))
        assertNull(CoolerProtocol.decode(frame(0x16, 20)))
        assertNull(CoolerProtocol.decode(CoolerProtocol.Query.Temperature.packet()))
    }

    @Test fun decoderDoesNotAssumeTheTelemetryEnvelopeMatchesTheRequest() {
        val candidate = frame(0x0e, 20).apply { this[0] = 0; this[1] = 0 }
        assertEquals(Reading(Metric.Temperature, 20), CoolerProtocol.decode(candidate))
    }

    @Test fun rejectsUnknownFanAndSwitchStates() {
        assertEquals(Reading(Metric.Fan, 6), CoolerProtocol.decode(frame(3, 6)))
        assertNull(CoolerProtocol.decode(frame(3, 7)))
        assertEquals(Reading(Metric.Running, 2), CoolerProtocol.decode(frame(2, 2)))
        assertNull(CoolerProtocol.decode(frame(2, 0)))
    }

    @Test fun staleReadingRespectsFreshnessAndSubscription() {
        val point = Sample(1L, 1_000L, Metric.Temperature, 21, 0)
        val state = MonitorState(phase = Phase.Live, subscribed = true, samples = mapOf(Metric.Temperature to point))
        assertEquals(point, state.current(Metric.Temperature, 7_999))
        assertNull(state.current(Metric.Temperature, 8_001))
        assertNull(state.copy(subscribed = false).current(Metric.Temperature, 2_000))
        assertNull(state.copy(phase = Phase.Retrying).current(Metric.Temperature, 2_000))
        assertNull(state.copy(currentSegment = 1).current(Metric.Temperature, 2_000))
    }
}
