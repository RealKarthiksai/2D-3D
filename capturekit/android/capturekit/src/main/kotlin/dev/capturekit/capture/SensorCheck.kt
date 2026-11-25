package dev.capturekit.capture

import dev.capturekit.nativebridge.CaptureKitNative

/**
 * Pre-scan sensor check (ADR 0035): collects the IMU while the user holds the phone still. All judgement (stats,
 * verdict, restart/finish action, user sentence) is in the core (`ck_sensor_check`); the shell only collects samples,
 * shows the message and follows the action. Bounded: keeps the newest [KEEP_NS] of samples per sensor, at most [CAPACITY].
 */
class ImuWindow : ImuSink {
    private class Ring {
        val t = LongArray(CAPACITY)
        val v = DoubleArray(3 * CAPACITY)
        var start = 0
        var size = 0
        var accuracy = -1

        fun add(tNs: Long, x: Double, y: Double, z: Double) {
            val i = (start + size) % CAPACITY
            if (size == CAPACITY) start = (start + 1) % CAPACITY else size++
            t[i] = tNs; v[3 * i] = x; v[3 * i + 1] = y; v[3 * i + 2] = z
            // Trim by time, not count, so the core sees >= its 2 s window at any sensor rate.
            while (size > 1 && tNs - t[start] > KEEP_NS) { start = (start + 1) % CAPACITY; size-- }
        }

        fun times() = LongArray(size) { t[(start + it) % CAPACITY] }
        fun values() = DoubleArray(3 * size) { v[3 * ((start + it / 3) % CAPACITY) + it % 3] }
    }

    /** Chronological copy of one window: times + flat [x,y,z] values per sensor, and the latest OS accuracy (-1 unknown). */
    class Snapshot(
        val gyroT: LongArray, val gyroV: DoubleArray, val accelT: LongArray, val accelV: DoubleArray,
        val gyroAccuracy: Int, val accelAccuracy: Int,
    )

    private val gyro = Ring()
    private val accel = Ring()

    @Synchronized override fun onImu(gyro: Boolean, tNs: Long, x: Double, y: Double, z: Double, accuracy: Int) {
        val r = if (gyro) this.gyro else accel
        r.add(tNs, x, y, z)
        r.accuracy = accuracy
    }

    @Synchronized fun clear() {
        for (r in listOf(gyro, accel)) { r.start = 0; r.size = 0; r.accuracy = -1 }
    }

    @Synchronized fun snapshot() =
        Snapshot(gyro.times(), gyro.values(), accel.times(), accel.values(), gyro.accuracy, accel.accuracy)

    companion object {
        /** Kept span: the core's 2 s window + 1 s, so the collected span is still visible to it. */
        const val KEEP_NS = 3_000_000_000L
        /** Hard bound per sensor (3 s at ~1365 Hz); normal rates are trimmed by [KEEP_NS] first. */
        const val CAPACITY = 4096
    }
}

/** One core verdict. [config] holds only the `sensor_check.*` summary keys (String or Double values). */
data class SensorCheckResult(val action: String, val message: String, val config: Map<String, Any>) {
    val result: String get() = config["sensor_check.result"] as? String ?: ""

    /** Session config keys for the manifest (`capture_settings.sensor_check`). */
    fun toSessionConfig(attempts: Int, arReset: Boolean): Map<String, Any> =
        config + mapOf("sensor_check.attempts" to attempts, "sensor_check.ar_reset" to if (arReset) 1 else 0)

    companion object {
        private val KEY = Regex("\"(sensor_check\\.[a-z0-9_]+)\":")

        /** Parses the core JSON; null when it is empty or malformed (the core rejected the input). */
        fun parse(json: String): SensorCheckResult? {
            val action = EventParser.str(json, "action") ?: return null
            val message = EventParser.str(json, "message") ?: return null
            val config = LinkedHashMap<String, Any>()
            for (m in KEY.findAll(json)) {
                val k = m.groupValues[1]
                val v: Any = EventParser.str(json, Regex.escape(k)) ?: EventParser.num(json, Regex.escape(k)) ?: continue
                config[k] = v
            }
            return SensorCheckResult(action, message, config)
        }
    }
}

object SensorCheck {
    const val CONTINUE = "continue"
    const val RESTART = "restart"
    const val FINISH = "finish"

    /** Session config when the user started the scan during the check (Start = skip). */
    fun skipped(attempts: Int): Map<String, Any> =
        mapOf("sensor_check.result" to "skipped", "sensor_check.attempts" to attempts,
            "sensor_check.ar_reset" to if (attempts > 0) 1 else 0)

    /** Native call: elapsedS = seconds since this attempt started; restarts = RESTART actions taken in this attempt. */
    fun evaluate(w: ImuWindow, elapsedS: Double, restarts: Int): SensorCheckResult? {
        val s = w.snapshot()
        val input = "{\"gyro_accuracy\":${s.gyroAccuracy},\"accel_accuracy\":${s.accelAccuracy}," +
            "\"elapsed_s\":$elapsedS,\"restarts\":$restarts}"
        return SensorCheckResult.parse(CaptureKitNative.sensorCheck(s.gyroT, s.gyroV, s.accelT, s.accelV, input))
    }
}
