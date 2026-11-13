package dev.capturekit.sample

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.capturekit.raw.RawRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0 raw recorder screen: Start/Stop writes a session folder under
 * getExternalFilesDir("sessions"). Pull with: adb pull /sdcard/Android/data/dev.capturekit.sample/files/sessions
 */
open class RecorderActivity : Activity(), SensorEventListener {
    protected open val clockCheck = false
    private lateinit var ar: ArHost
    private lateinit var status: TextView
    private lateinit var sensors: SensorManager
    @Volatile protected var recorder: RawRecorder? = null
    private var frames = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        ar = ArHost(this) { frame ->
            val r = recorder ?: return@ArHost
            if (clockCheck) r.onClockSample("ar", frame.timestamp) else r.onArFrame(frame)
            if (++frames % 30 == 0L) runOnUiThread {
                status.text = "frames=$frames dropped=${r.droppedCount} tracking=${frame.camera.trackingState}"
            }
        }
        status = TextView(this).apply { text = if (clockCheck) "Clock check" else "Raw recorder" }
        val toggle = Button(this).apply { text = "Start" }
        toggle.setOnClickListener {
            if (recorder == null) { start(); toggle.text = "Stop" } else { stop(); toggle.text = "Start" }
        }
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status); addView(toggle)
        }
        setContentView(FrameLayout(this).apply { addView(ar.view); addView(overlay) })
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    private fun start() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = if (clockCheck) "clock" else "raw"
        val dir = File(getExternalFilesDir("sessions"), "${prefix}_$stamp")
        recorder = RawRecorder(dir).also { it.writeDevice() }
        frames = 0
        for (type in intArrayOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ACCELEROMETER)) {
            sensors.getDefaultSensor(type)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        }
    }

    private fun stop() {
        sensors.unregisterListener(this)
        recorder?.close()
        status.text = "saved ${recorder?.dir}"
        recorder = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val r = recorder ?: return
        val type = if (e.sensor.type == Sensor.TYPE_GYROSCOPE) "gyro" else "accel"
        if (clockCheck) r.onClockSample(type, e.timestamp)
        else r.onImu(type, e.timestamp, e.values[0], e.values[1], e.values[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) ar.resume()
    }

    override fun onPause() {
        super.onPause()
        if (recorder != null) stop()
        ar.pause()
    }

    override fun onDestroy() {
        ar.close()
        super.onDestroy()
    }
}

/** Logs raw AR-frame and IMU timestamps beside elapsedRealtimeNanos (clock-notes.md). */
class ClockCheckActivity : RecorderActivity() {
    override val clockCheck = true
}
