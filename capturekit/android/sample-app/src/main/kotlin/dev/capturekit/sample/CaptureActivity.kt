package dev.capturekit.sample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.capturekit.capture.ARAdapter
import dev.capturekit.capture.CoreEvent
import dev.capturekit.capture.Policy
import dev.capturekit.capture.SensorBridge
import dev.capturekit.capture.SessionManager
import dev.capturekit.platform.PlatformSignals
import dev.capturekit.platform.ThermalAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 6 capture screen: a real core session (quality, keyframe selection, governor) with live
 * quality and policy readout. Bundles land in getExternalFilesDir("bundles"); interrupted sessions
 * are recovered on the next launch. Pull with: adb pull /sdcard/Android/data/dev.capturekit.sample/files/bundles
 */
class CaptureActivity : Activity(), SessionManager.Listener {
    private lateinit var ar: ArHost
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private var session: SessionManager? = null
    private var adapter: ARAdapter? = null
    private var sensors: SensorBridge? = null
    private var thermal: ThermalAdapter? = null
    @Volatile private var lastQuality = "-"
    @Volatile private var lastPolicy: Policy? = null
    @Volatile private var keyframes = 0
    @Volatile private var lastGovernor = "-"
    @Volatile private var instruction = ""
    private var frames = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "recovering interrupted sessions..." }
        toggle = Button(this).apply { text = "Start"; setOnClickListener { if (session == null) start() else stop() } }
        ar = ArHost(this) { frame ->
            adapter?.onFrame(frame)
            if (++frames % 15 == 0L) runOnUiThread { render(frame.camera.trackingState.name) }
        }
        setContentView(FrameLayout(this).apply {
            addView(ar.view)
            addView(LinearLayout(this@CaptureActivity).apply { orientation = LinearLayout.VERTICAL; addView(status); addView(toggle) })
        })
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        Thread {
            val recovered = SessionManager.recoverInterrupted(this)
            runOnUiThread { status.text = if (recovered.isEmpty()) "ready" else "recovered ${recovered.size} interrupted session(s)" }
        }.start()
    }

    private fun start() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir("bundles"), "scan_$stamp")
        val s = SessionManager(this, dir, listener = this)
        if (!s.start()) { status.text = "could not start session"; return }
        session = s
        adapter = ARAdapter(s)
        sensors = SensorBridge(this, s).also { it.start() }
        thermal = ThermalAdapter(this) { p -> pushPlatform(s, p) }.also { it.start() }
        keyframes = 0; frames = 0
        toggle.text = "Finish"
    }

    private fun pushPlatform(s: SessionManager, p: PlatformSignals) =
        s.onPlatform(SystemClock.elapsedRealtimeNanos(), p.thermalLevel, p.lowPowerMode, p.batteryPct, p.charging, 0.0)

    private fun stop() {
        val s = session ?: return
        sensors?.stop(); thermal?.stop(); adapter = null
        toggle.isEnabled = false
        Thread {
            val st = s.finish()
            runOnUiThread { status.text = "saved ${s.dir} (status $st)"; toggle.text = "Start"; toggle.isEnabled = true; session = null }
        }.start()
    }

    private fun render(tracking: String) {
        val p = lastPolicy
        status.text = "tracking=$tracking  keyframes=$keyframes  quality=$lastQuality\n" +
            "policy=${p?.levelName ?: "-"} kf x${p?.keyframeRateScale ?: 1.0} res tier ${p?.resolutionTier ?: 0}" +
            (if (p?.mustPause == true) "  PAUSED: phone too hot/low battery" else "") +
            "\ngovernor: $lastGovernor  dropped@edge=${session?.droppedAtEdge?.get() ?: 0}\n» $instruction"
    }

    override fun onEvent(e: CoreEvent) {
        when (e) {
            is CoreEvent.FrameQuality -> lastQuality = "%.2f%s".format(e.quality, if (e.reasons.isEmpty()) "" else " " + e.reasons.joinToString(","))
            is CoreEvent.KeyframeRequest -> keyframes++
            is CoreEvent.Instruction -> instruction = e.speakText
            is CoreEvent.Governor -> lastGovernor = "${e.from}->${e.to} (${e.reason})"
            else -> {}
        }
    }
    override fun onPolicy(p: Policy) { lastPolicy = p }

    override fun onResume() { super.onResume(); ar.resume() }
    override fun onPause() { ar.pause(); super.onPause() }
}
