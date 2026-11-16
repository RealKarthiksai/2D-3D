package dev.capturekit.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import dev.capturekit.capture.ARAdapter
import dev.capturekit.capture.CoreEvent
import dev.capturekit.capture.Policy
import dev.capturekit.capture.SensorBridge
import dev.capturekit.capture.SessionManager
import dev.capturekit.nativebridge.Status
import dev.capturekit.platform.PlatformSignals
import dev.capturekit.platform.ThermalAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guided scan screen: a real core session (quality, keyframe selection, governor, guidance) over the live
 * camera image with a friendly HUD. Core instructions are shown (speak_text as coaching, long_text as
 * subtitle), spoken verbatim by [SpeechRenderer] and mapped to haptics. Finish hands over to [ReviewActivity].
 * Bundles land in getExternalFilesDir("bundles"); interrupted sessions are recovered on the next launch.
 * Pull with: adb pull /sdcard/Android/data/dev.capturekit.sample/files/bundles
 */
class CaptureActivity : Activity(), SessionManager.Listener {
    private lateinit var ar: ArHost
    private lateinit var hud: Hud
    private lateinit var speech: SpeechRenderer
    private var session: SessionManager? = null
    @Volatile private var adapter: ARAdapter? = null
    @Volatile private var arActive = true
    private var sensors: SensorBridge? = null
    private var thermal: ThermalAdapter? = null
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var lastQuality = -1.0
    @Volatile private var lastReasons: List<String> = emptyList()
    @Volatile private var lastPolicy: Policy? = null
    @Volatile private var keyframes = 0
    @Volatile private var lastGovernor = "-"
    @Volatile private var instruction = ""
    @Volatile private var instructionLong = ""
    @Volatile private var overlay: CoreEvent.Overlay? = null
    @Volatile private var trackState = "?"
    @Volatile private var trackFailure = "NONE"
    @Volatile private var corePaused = false
    private var recStartMs = 0L
    private var pausedAtMs = 0L
    private var pausedTotalMs = 0L
    private var recording = false
    private var finishing = false
    private var reviewDialog: android.app.Dialog? = null
    private var arMessage: String? = null
    private var recovering = true
    private var recoveredNote: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speech = SpeechRenderer(this)
        ar = ArHost(this) { frame ->
            if (!arActive) return@ArHost   // no Frame use once onPause started tearing the Session down
            adapter?.onFrame(frame)
            trackState = frame.camera.trackingState.name
            trackFailure = frame.camera.trackingFailureReason.name
        }
        ar.onError = { msg -> arMessage = msg; refresh() }
        hud = Hud(this)
        hud.pauseEnabled = true
        hud.button.setOnClickListener { if (session == null) start() else stop() }
        hud.pauseButton.setOnClickListener { togglePause() }
        hud.onVoiceToggle = { speech.enabled = !speech.enabled; hud.setVoice(speech.enabled) }
        hud.setVoice(speech.enabled)
        hud.setCoaching("Press Start, then move slowly around the room")
        setContentView(FrameLayout(this).apply {
            addView(ar.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(hud.root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        if (!CameraPermission.granted(this)) CameraPermission.request(this)
        hud.button.isEnabled = false
        hud.setDetail("Checking for interrupted sessions...")
        Thread {
            val recovered = SessionManager.recoverInterrupted(this)
            runOnUiThread {
                recovering = false
                recoveredNote = if (recovered.isEmpty()) null else "Recovered ${recovered.size} interrupted session(s)"
                hud.setDetail(recoveredNote)
                hud.button.isEnabled = session == null
            }
        }.start()
    }

    private fun start() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir("bundles"), "scan_$stamp")
        val s = SessionManager(this, dir, listener = this)
        // 8 core frame slots (default 4): absorbs ~800 ms worker stalls at 10 candidates/s before ErrFull.
        if (!s.start(mapOf("frame_slots" to 8))) {
            hud.setBanner("Could not start the capture session. Check free storage and try again.", UiKit.Kind.ERROR)
            return
        }
        session = s
        ar.stats = s.stats
        ar.recording = true
        adapter = ARAdapter(s, 0L) { ar.session }
        sensors = SensorBridge(this, s).also { it.start() }
        thermal = ThermalAdapter(this) { p -> pushPlatform(s, p) }.also { it.start() }
        keyframes = 0; instruction = ""; instructionLong = ""; overlay = null; lastQuality = -1.0; lastReasons = emptyList()
        corePaused = false; pausedTotalMs = 0; pausedAtMs = 0
        recStartMs = SystemClock.elapsedRealtime(); recording = true
        hud.setResult(null)
        hud.setRecording(true)
        hud.setDetail(null)
        hud.setCoaching("Move slowly and keep the camera steady")
        hud.haptic(false)
    }

    private fun pushPlatform(s: SessionManager, p: PlatformSignals) =
        s.onPlatform(SystemClock.elapsedRealtimeNanos(), p.thermalLevel, p.lowPowerMode, p.batteryPct, p.charging, 0.0)

    private fun togglePause() {
        val s = session ?: return
        if (!recording) return
        if (!corePaused) {
            if (s.pause() == Status.OK) {
                speech.stopNow()
                hud.setCoaching("Paused. Press Resume when you are ready")
                hud.haptic(false)
            }
        } else {
            val st = s.resume()
            if (st == Status.OK) hud.setCoaching("Move slowly and keep the camera steady")
            else hud.setBanner("Cannot resume yet: the phone needs to cool down or charge (status ${UiKit.statusName(st)}).", UiKit.Kind.WARN)
        }
    }

    /**
     * Finish pressed: when a wall is weak, pause the core and show [ReviewSheet] before finalize ("Scan the gaps"
     * resumes the same bundle, "Finish anyway" finalizes); otherwise finish straight away.
     */
    private fun stop() {
        val s = session ?: return
        if (finishing || reviewDialog != null) return
        val room = overlay
        if (!recording || room == null || !ReviewSheet.needed(room)) { finishScan(); return }
        if (!corePaused) {
            if (s.pause() != Status.OK) {
                hud.setBanner("Could not pause the scan to review it. Press Finish again.", UiKit.Kind.WARN)
                return
            }
            corePaused = true   // synchronously, so onPause does not pause a second time
            pausedAtMs = SystemClock.elapsedRealtime()
        }
        speech.stopNow()
        hud.haptic(false)
        reviewDialog = ReviewSheet.show(this, room,
            onGaps = {
                if (reviewDialog == null) return@show   // one-shot: resume runs once
                reviewDialog = null
                val st = s.resume()
                if (st == Status.OK) hud.setCoaching("Move slowly and keep the camera steady")
                else hud.setBanner("Cannot resume yet: the phone needs to cool down or charge (status ${UiKit.statusName(st)}). Press Resume when it is ready.", UiKit.Kind.WARN)
            },
            onFinish = { reviewDialog = null; finishScan() })
    }

    private fun finishScan() {
        val s = session ?: return
        if (finishing) return
        finishing = true
        speech.stopNow()
        sensors?.stop(); thermal?.stop(); adapter = null
        hud.button.isEnabled = false
        hud.pauseButton.isEnabled = false
        val elapsed = elapsedMs()
        recording = false
        ar.recording = false
        hud.setRecording(false)
        hud.setDetail(null)
        hud.setResult("Saving the scan...", UiKit.Kind.INFO)
        hud.setCoaching("Saving. Keep the app open")
        hud.haptic(true)
        val kf = keyframes
        // Last room snapshot for the Review screen (Intent extras only; no bundle schema change).
        val room = overlay
        val roomSurfaces = room?.let { RoomView.encodeSurfaces(it.surfaces) }
        Thread {
            val st = s.finish()
            runOnUiThread {
                session = null
                startActivity(Intent(this, ReviewActivity::class.java)
                    .putExtra(ReviewActivity.EXTRA_DIR, s.dir.absolutePath)
                    .putExtra(ReviewActivity.EXTRA_STATUS, st)
                    .putExtra(ReviewActivity.EXTRA_ELAPSED_MS, elapsed)
                    .putExtra(ReviewActivity.EXTRA_KEYFRAMES, kf)
                    .apply {
                        if (room != null) {
                            putExtra(ReviewActivity.EXTRA_SURFACES, roomSurfaces)
                            putExtra(ReviewActivity.EXTRA_FLOOR, room.floorFound)
                            putExtra(ReviewActivity.EXTRA_CEILING, room.ceilingFound)
                            putExtra(ReviewActivity.EXTRA_WALLS_FOUND, room.walls)
                        }
                    })
                finish()
            }
        }.start()
    }

    private fun elapsedMs(): Long {
        val now = if (corePaused && pausedAtMs != 0L) pausedAtMs else SystemClock.elapsedRealtime()
        return now - recStartMs - pausedTotalMs
    }

    /** UI refresh at ~4 Hz while resumed: reads volatile state written by the AR/core threads. */
    private val tick = object : Runnable {
        override fun run() {
            ar.checkStall()
            refresh()
            ui.postDelayed(this, 250)
        }
    }

    private fun refresh() {
        val p = lastPolicy
        // One pill: tracking + latest frame-quality reasons + thermal level (warming/hot no longer use the banner).
        hud.setStatus(trackState, trackFailure, if (recording && !corePaused && lastQuality >= 0) lastReasons else emptyList(),
            if (recording && p != null) p.level else 0, ar.live() || ar.lastFrameMs == 0L || ar.state != ArState.READY)
        if (hud.debugOn) session?.let { hud.setDebug(it.stats.hudLine()) }
        hud.setUnderstanding(if (recording) overlay else null)
        if (recording) {
            hud.setPaused(corePaused)
            hud.setRecInfo(elapsedMs())
            if (!corePaused && instruction.isNotEmpty()) hud.setCoaching(instruction, instructionLong)
        }
        // Banner is for errors only: a forced thermal/battery pause blocks capture, so it stays here.
        val thermalMsg = if (recording && p != null && p.mustPause) "Capture paused: the phone is too hot or the battery is low. Let it rest." else null
        val am = arMessage
        when {
            am != null && ar.state == ArState.NO_PERMISSION ->
                hud.setBanner("Camera permission is needed to scan the room.", UiKit.Kind.ERROR, CameraPermission.retryLabel()) { CameraPermission.retry(this) }
            am != null && ar.state != ArState.READY ->
                hud.setBanner(am, UiKit.Kind.ERROR, "Retry") { arMessage = null; ar.retry(); refresh() }
            !speech.available && recording -> hud.setBanner("Voice guidance is unavailable: no offline text-to-speech voice. On-screen prompts still work.", UiKit.Kind.INFO)
            thermalMsg != null -> hud.setBanner(thermalMsg, UiKit.Kind.WARN)
            else -> hud.setBanner(null)
        }
    }

    // Core event thread (not the AR thread).
    override fun onEvent(e: CoreEvent) {
        when (e) {
            is CoreEvent.FrameQuality -> { lastQuality = e.quality; lastReasons = e.reasons }
            is CoreEvent.KeyframeRequest -> keyframes++
            is CoreEvent.Instruction -> {
                instruction = e.speakText; instructionLong = e.longText
                if (!corePaused) {
                    speech.onInstruction(e.id, e.speakText, e.priority)
                    hud.hapticFor(e.haptic, e.priority)
                }
            }
            is CoreEvent.Overlay -> overlay = e
            is CoreEvent.Governor -> lastGovernor = "${e.from}->${e.to} (${e.reason})"
            else -> {}
        }
    }
    override fun onPolicy(p: Policy) { lastPolicy = p }

    override fun onState(state: Int) {
        val nowPaused = state == STATE_PAUSED
        if (nowPaused == corePaused) return
        if (nowPaused) { pausedAtMs = SystemClock.elapsedRealtime(); speech.stopNow() }
        else if (pausedAtMs != 0L) { pausedTotalMs += SystemClock.elapsedRealtime() - pausedAtMs; pausedAtMs = 0 }
        corePaused = nowPaused
    }

    override fun onResume() {
        super.onResume()
        arMessage = null
        ar.resume()
        arActive = true
        ui.post(tick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) UiKit.immersive(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (CameraPermission.onResult(this, grantResults)) { arMessage = null; ar.resume() }
        else { arMessage = "Camera permission is needed to scan the room."; refresh() }
    }

    override fun onPause() {
        val t0 = SystemClock.elapsedRealtime()
        ui.removeCallbacks(tick)
        // Leaving the screen mid-scan: pause the core session so no gap is recorded as tracking data.
        if (recording && !corePaused && !finishing) session?.pause()
        speech.stopNow()
        val t1 = SystemClock.elapsedRealtime()
        arActive = false
        ar.pause()
        val t2 = SystemClock.elapsedRealtime()
        super.onPause()
        Log.i(TAG, "timing Activity.onPause total=${SystemClock.elapsedRealtime() - t0}ms core+speech=${t1 - t0}ms ar.pause=${t2 - t1}ms")
    }

    override fun onDestroy() {
        // Destroyed mid-scan (Back, swipe-away, system): finish the session so the work is kept as a bundle and
        // nothing leaks (native handle, ck-events thread, sensor/thermal listeners). finish() writes a complete
        // bundle; if the process dies before it ends, the DB still says "recording" and next launch recovers it.
        reviewDialog?.dismiss(); reviewDialog = null
        val s = session
        if (s != null && !finishing) {
            finishing = true
            ar.recording = false
            sensors?.stop(); thermal?.stop(); adapter = null
            session = null
            Log.w(TAG, "destroyed mid-scan: finishing ${s.dir.name} so the scan is kept")
            Thread {
                val st = s.finish()
                Log.w(TAG, "mid-scan finish of ${s.dir.name}: status ${UiKit.statusName(st)}")
            }.start()
        }
        hud.release()
        speech.release()
        ar.close()
        super.onDestroy()
    }

    private companion object { const val STATE_PAUSED = 3; const val TAG = "CKCapture" }
}
