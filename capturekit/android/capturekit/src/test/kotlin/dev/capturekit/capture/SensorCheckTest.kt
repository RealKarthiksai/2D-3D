package dev.capturekit.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR 0035: the shell side of the pre-scan sensor check (the verdict itself is tested in the core). */
class SensorCheckTest {
    // Shape of the core's ck_sensor_check output (keys sorted).
    private val warn = "{\"action\":\"finish\",\"message\":\"The motion sensor shows a small drift. Lay the phone on a table and re-check, or start the scan anyway.\"," +
        "\"sensor_check.accel_norm_mps2\":9.80123,\"sensor_check.accel_std_mps2\":0.0049,\"sensor_check.gyro_bias_rad_s\":0.05," +
        "\"sensor_check.gyro_std_rad_s\":0.005,\"sensor_check.n_accel\":401,\"sensor_check.n_gyro\":401," +
        "\"sensor_check.reasons\":\"GYRO_BIAS_HIGH\",\"sensor_check.result\":\"warn\",\"sensor_check.window_s\":2}"

    @Test fun parseKeepsActionMessageAndOnlyTheSummaryKeys() {
        val r = SensorCheckResult.parse(warn)!!
        assertEquals(SensorCheck.FINISH, r.action)
        assertEquals("warn", r.result)
        assertEquals("The motion sensor shows a small drift. Lay the phone on a table and re-check, or start the scan anyway.", r.message)
        assertEquals(setOf("accel_norm_mps2", "accel_std_mps2", "gyro_bias_rad_s", "gyro_std_rad_s", "n_accel", "n_gyro",
            "reasons", "result", "window_s").map { "sensor_check.$it" }.toSet(), r.config.keys)
        assertEquals(0.05, r.config["sensor_check.gyro_bias_rad_s"])
        assertEquals("GYRO_BIAS_HIGH", r.config["sensor_check.reasons"])
        assertEquals("", SensorCheckResult.parse(warn.replace("GYRO_BIAS_HIGH", ""))!!.config["sensor_check.reasons"])
    }

    @Test fun emptyOutputFromARejectedInputParsesToNull() {
        assertNull(SensorCheckResult.parse(""))
        assertNull(SensorCheckResult.parse("{\"sensor_check.result\":\"pass\"}"))
    }

    @Test fun sessionConfigAddsAttemptsAndArReset() {
        val c = SensorCheckResult.parse(warn)!!.toSessionConfig(attempts = 2, arReset = true)
        assertEquals(2, c["sensor_check.attempts"])
        assertEquals(1, c["sensor_check.ar_reset"])
        assertEquals("warn", c["sensor_check.result"])
        assertEquals(11, c.size)
        assertEquals(mapOf<String, Any>("sensor_check.result" to "skipped", "sensor_check.attempts" to 1, "sensor_check.ar_reset" to 1),
            SensorCheck.skipped(1))
    }

    @Test fun windowKeepsTheNewestSamplesInOrderAndTheLatestAccuracy() {
        val w = ImuWindow()
        val n = ImuWindow.CAPACITY + 5
        for (i in 0 until n) w.onImu(true, i.toLong(), i.toDouble(), 0.0, -i.toDouble(), if (i == n - 1) 0 else 3)
        val s = w.snapshot()
        assertEquals(ImuWindow.CAPACITY, s.gyroT.size)
        assertEquals(5L, s.gyroT.first()); assertEquals((n - 1).toLong(), s.gyroT.last())
        assertArrayEquals(doubleArrayOf(5.0, 0.0, -5.0), s.gyroV.copyOfRange(0, 3), 0.0)
        assertEquals(0, s.gyroAccuracy); assertEquals(-1, s.accelAccuracy); assertEquals(0, s.accelT.size)
        w.clear()
        assertEquals(0, w.snapshot().gyroT.size); assertEquals(-1, w.snapshot().gyroAccuracy)
    }

    /** Review fix: a count-only ring held < 2 s at 500 Hz, so the core could never pass ("No motion sensor data"). */
    @Test fun windowKeepsAtLeastTheCoreWindowAt500Hz() {
        val w = ImuWindow()
        val stepNs = 2_000_000L   // 500 Hz
        for (i in 0 until 5 * 500) w.onImu(true, 1_000_000_000L + i * stepNs, 0.0, 0.0, 0.0, 3)
        val t = w.snapshot().gyroT
        val spanS = (t.last() - t.first()) / 1e9
        assertTrue("span $spanS s", spanS >= 2.0 && spanS <= 3.0)
        assertTrue(t.size <= ImuWindow.CAPACITY)
    }
}
