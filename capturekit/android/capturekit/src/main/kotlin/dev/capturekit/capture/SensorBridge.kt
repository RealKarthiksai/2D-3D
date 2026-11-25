package dev.capturekit.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/** Receives one gyro (rad/s) or accel (m/s^2) sample with the OS accuracy flag ([SensorEvent.accuracy]). */
fun interface ImuSink {
    fun onImu(gyro: Boolean, tNs: Long, x: Double, y: Double, z: Double, accuracy: Int)
}

/**
 * Gyroscope + accelerometer at the fastest rate on a dedicated thread (never the AR/GL thread).
 * SensorEvent.timestamp is on the elapsedRealtimeNanos base; [clockOffsetNs] is the per-device offset
 * measured with the clock-check screen (docs/program-spec/clock-notes.md).
 */
class SensorBridge(
    context: Context,
    private val sink: ImuSink,
    private val clockOffsetNs: Long = 0L,
) : SensorEventListener {
    /** Feeds a capture session. */
    constructor(context: Context, session: SessionManager, clockOffsetNs: Long = 0L) : this(
        context,
        ImuSink { gyro, t, x, y, z, _ -> if (gyro) session.onGyro(t, x, y, z) else session.onAccel(t, x, y, z) },
        clockOffsetNs,
    )

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var thread: HandlerThread? = null

    fun start() {
        val t = HandlerThread("ck-sensors").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, IMU_PERIOD_US, h) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, IMU_PERIOD_US, h) }
    }

    fun stop() {
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp + clockOffsetNs
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> sink.onImu(true, t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), e.accuracy)
            Sensor.TYPE_ACCELEROMETER -> sink.onImu(false, t, v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), e.accuracy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        /** 200 Hz: the cap without HIGH_SAMPLING_RATE_SENSORS (targetSdk 31+). Spec needs >=100 Hz. */
        const val IMU_PERIOD_US = 5000
    }
}
