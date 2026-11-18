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
import dev.capturekit.capture.DetectedBox
import dev.capturekit.capture.LiteRtKeyframeDetector
import dev.capturekit.capture.Policy
import dev.capturekit.capture.SensorBridge
import dev.capturekit.capture.SessionManager
import dev.capturekit.guidance.Recalibration
import dev.capturekit.guidance.ScanStateSnapshot
import dev.capturekit.nativebridge.Status
import dev.capturekit.platform.PlatformSignals
import dev.capturekit.platform.ThermalAdapter
import dev.capturekit.capture.DebugFlags
import dev.capturekit.sample.research.ResearchLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guided scan screen: a real core session (quality, keyframe selection, governor, guidance) over the live
 * camera image with a friendly HUD. The 2D object detector runs by default when its model is packaged (ADR 0030):
 * its boxes are drawn over the camera feed ([DetectionOverlayView], "Boxes" chip) and the core lifts them to the
 * labelled objects of the mini-map and the review screen. Core instructions are shown (speak_text as coaching, long_text as
 * subtitle), spoken verbatim by [SpeechRenderer] and mapped to haptics. Finish hands over to [ReviewActivity].
 * Bundles land in getExternalFilesDir("bundles"); interrupted sessions are recovered on the next launch.
 * Pull with: adb pull /sdcard/Android/data/dev.capturekit.sample/files/bundles
 */
class CaptureActivity : Activity(), SessionManager.Listener {
    private lateinit var ar: ArHost
    private lateinit var hud: Hud
    private lateinit var boxView: DetectionOverlayView
    private lateinit var speech: SpeechRenderer
    private lateinit var voice: VoiceController
    private lateinit var research: ResearchLog
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
    @Volatile private var instructionType = ""
    /** Issue #1 / ADR 0031: tracking recalibration prompt (immutable; replaced on the core event thread, read on the UI thread). */
    @Volatile private var recal = Recalibration()
    /**
     * Guards the read-modify-write updates of [recal] and of the instruction fields: the core event thread and the UI
     * thread (scan start / finish) both write them, so a late event must not overwrite a reset. Reads stay lock-free.
     */
    private val recalLock = Any()
    @Volatile private var overlay: CoreEvent.Overlay? = null
    @Volatile private var trackState = "?"
    @Volatile private var trackFailure = "NONE"
    @Volatile private var corePaused = false
    /** This scan lifts detections to objects (detector running AND understanding >= 1): the review may say "none found". */
    private var objectsExpected = false
    private var recStartMs = 0L
    private var pausedAtMs = 0L
    private var pausedTotalMs = 0L
    @Volatile private var recording = false   // also read on the detector thread (onDetections)
    @Volatile private var finishing = false
    private var reviewDialog: android.app.Dialog? = null
    private var arMessage: String? = null
    private var recovering = true
    private var recoveredNote: String? = null

    /** What voice commands may do on this screen (main thread only). */
    private val voiceHost = object : VoiceController.Host {
        override fun isPaused() = corePaused
        override fun pauseScan() { if (recording && !corePaused) togglePause() }
        override fun resumeScan() { if (recording && corePaused) togglePause() }
        override fun finishConfirmed() { stop() }   // same path as the Finish button (review sheet when a wall is weak)
        override fun snapshot() = overlay?.let { ScanStateSnapshot.fromOverlay(it) }
        override fun feedback(text: String?) { hud.setVoiceLine(text) }
        override fun voiceStateChanged() = refreshMic()
    }

    private fun refreshMic() = hud.setMic(if (voice.available && recording && !finishing && !research.unguided) voice.micState else null)

    /** Trial chip (field flavour, not while recording) and the voice chip (hidden in the unguided arm). */
    private fun applyTrial() {
        hud.setTrial(if (research.enabled && !recording) research.label else null)
        hud.setVoice(if (research.unguided) null else speech.enabled)
        refreshMic()
    }

    private fun scanPrompt() = if (research.unguided) "Scan the room" else "Move slowly and keep the camera steady"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        speech = SpeechRenderer(this)
        boxView = DetectionOverlayView(this)   // before ArHost: its frame callback maps the boxes
        ar = ArHost(this) { frame ->
            if (!arActive) return@ArHost   // no Frame use once onPause started tearing the Session down
            adapter?.onFrame(frame)
            trackState = frame.camera.trackingState.name
            trackFailure = frame.camera.trackingFailureReason.name
            boxView.onArFrame(frame)   // cheap no-op unless a new keyframe's boxes arrived
        }
        ar.onError = { msg -> arMessage = msg; refresh() }
        hud = Hud(this)
        hud.pauseEnabled = true
        hud.button.setOnClickListener { if (session == null) start() else stop() }
        hud.pauseButton.setOnClickListener { togglePause() }
        hud.onVoiceToggle = { speech.enabled = !speech.enabled; hud.setVoice(speech.enabled) }
        hud.onBoxesToggle = { boxView.showBoxes = !boxView.showBoxes; hud.setBoxes(boxView.showBoxes) }
        research = ResearchLog(this)
        voice = VoiceController(this, speech, voiceHost)
        voice.onIntent = { research.onIntent(it.name) }
        hud.onMicClick = { voice.onMicTapped() }
        hud.onTrialClick = { if (!recording) research.showSetup { applyTrial() } }
        applyTrial()
        hud.setCoaching("Press Start, then move slowly around the room")
        setContentView(FrameLayout(this).apply {
            addView(ar.view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(boxView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)   // same frame as ar.view
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

    /** Start pressed: ask for the microphone first (rationale), then begin; a refusal only turns voice commands off. */
    private fun start() = voice.ensurePermissionThen { startScan() }

    /** One `getprop` call (a short child process; streams closed, process destroyed). Debug builds only, read once per activity. */
    private fun sysProp(n: String): String {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(arrayOf("getprop", n))
            p.inputStream.bufferedReader().use { it.readText().trim() }
        } catch (t: Throwable) { "" } finally { p?.destroy() }
    }

    /**
     * (understanding override or null, detector on). Properties are read once, only in a debuggable build; elsewhere
     * there is no override and the detector is on (ADR 0030: on by default; it still needs the packaged model).
     */
    private val debugFlags: Pair<Int?, Boolean> by lazy {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) null to true
        else DebugFlags.understandingLevel(sysProp(DebugFlags.PROP_UNDERSTANDING)) to DebugFlags.detectorOn(sysProp(DebugFlags.PROP_DETECTOR))
    }

    private fun startScan() {
        if (session != null || finishing) return
        // Consent keys (consent_version + consent_at + redaction_mode) are always passed together; no consent, no scan.
        val consentCfg = dev.capturekit.consent.ConsentStore.get(this).sessionConfig()
        if (consentCfg == null) {
            startActivity(Intent(this, ConsentActivity::class.java).putExtra(ConsentActivity.EXTRA_START_SCAN, true))
            finish()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(getExternalFilesDir("bundles"), "scan_$stamp")
        // Detector on by default; debug builds can override (U11-A): `adb shell setprop debug.capturekit.detector 0` and
        // `debug.capturekit.understanding 0|1|2`. With no understanding override, a running detector gets level 1 (objects).
        val (understandingOverride, detector) = debugFlags
        val detectorWillRun = detector && LiteRtKeyframeDetector.available(this)
        // The unguided trial arm is the no-guidance control: no understanding by default (detections are still recorded).
        val understanding = DebugFlags.understandingFor(understandingOverride, detectorWillRun && !research.unguided)
        val s = SessionManager(this, dir, listener = this, detectorEnabled = detector)
        // 8 core frame slots (default 4): absorbs ~800 ms worker stalls at 10 candidates/s before ErrFull.
        if (!s.start(mapOf<String, Any>("frame_slots" to 8) + consentCfg + DebugFlags.extraConfig(understanding))) {
            hud.setBanner("Could not start the capture session. Check free storage and try again.", UiKit.Kind.ERROR)
            return
        }
        session = s
        ar.stats = s.stats
        objectsExpected = s.detectorActive && (understanding ?: 0) >= 1
        if (detectorWillRun && !s.detectorActive) Log.w(TAG, "detector model packaged but the detector did not start")
        boxView.clear()
        hud.setBoxes(if (s.detectorActive && !research.unguided) boxView.showBoxes else null)
        ar.recording = true
        adapter = ARAdapter(s, 0L) { ar.session }
        sensors = SensorBridge(this, s).also { it.start() }
        thermal = ThermalAdapter(this) { p -> pushPlatform(s, p) }.also { it.start() }
        keyframes = 0; overlay = null; lastQuality = -1.0; lastReasons = emptyList()
        synchronized(recalLock) { instruction = ""; instructionLong = ""; instructionType = ""; recal = Recalibration() }
        corePaused = false; pausedTotalMs = 0; pausedAtMs = 0
        recStartMs = SystemClock.elapsedRealtime(); recording = true
        speech.stats.reset(); speech.stats.begin(recStartMs)
        research.begin(s.stats, speech.stats, recStartMs)
        applyTrial()
        hud.setResult(null)
        hud.setRecording(true)
        hud.setDetail(null)
        hud.setCoaching(scanPrompt())
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
                boxView.clear()
                hud.setCoaching("Paused. Press Resume when you are ready")
                hud.haptic(false)
            }
        } else {
            val st = s.resume()
            if (st == Status.OK) hud.setCoaching(scanPrompt())
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
        // Unguided trial arm: no review sheet (it is guidance); finish straight away.
        if (!recording || research.unguided || !ReviewSheet.needed(room)) { finishScan(); return }
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
                if (st == Status.OK) hud.setCoaching(scanPrompt())
                else hud.setBanner("Cannot resume yet: the phone needs to cool down or charge (status ${UiKit.statusName(st)}). Press Resume when it is ready.", UiKit.Kind.WARN)
            },
            onFinish = { reviewDialog = null; finishScan() })
    }

    private fun finishScan() {
        val s = session ?: return
        if (finishing) return
        finishing = true
        voice.onPause()
        speech.stopNow()
        sensors?.stop(); thermal?.stop(); adapter = null
        hud.button.isEnabled = false
        hud.pauseButton.isEnabled = false
        boxView.clear(); hud.setBoxes(null)
        synchronized(recalLock) { recal = Recalibration() }
        hud.setRecalibrate(null)
        val elapsed = elapsedMs()
        recording = false
        ar.recording = false
        hud.setRecording(false)
        hud.setDetail(null)
        hud.setVoiceLine(null)
        hud.setResult("Saving the scan...", UiKit.Kind.INFO)
        hud.setCoaching("Saving. Keep the app open")
        hud.haptic(true)
        val kf = keyframes
        // Last room snapshot for the Review screen (Intent extras only; no bundle schema change).
        val room = overlay
        val roomSurfaces = room?.let { RoomView.encodeSurfaces(it.surfaces) }
        Thread {
            val st = s.finish()
            research.end(s.dir, st, kf, elapsed, SystemClock.elapsedRealtime())   // field only; sibling file, after the bundle is closed
            runOnUiThread {
                session = null
                startActivity(Intent(this, ReviewActivity::class.java)
                    .putExtra(ReviewActivity.EXTRA_DIR, s.dir.absolutePath)
                    .putExtra(ReviewActivity.EXTRA_STATUS, st)
                    .putExtra(ReviewActivity.EXTRA_ELAPSED_MS, elapsed)
                    .putExtra(ReviewActivity.EXTRA_KEYFRAMES, kf)
                    .putExtra(ReviewActivity.EXTRA_OBJECTS_EXPECTED, objectsExpected)
                    .apply {
                        if (room != null) {
                            putExtra(ReviewActivity.EXTRA_SURFACES, roomSurfaces)
                            putExtra(ReviewActivity.EXTRA_FLOOR, room.floorFound)
                            putExtra(ReviewActivity.EXTRA_CEILING, room.ceilingFound || room.ceilingSeen)
                            putExtra(ReviewActivity.EXTRA_WALLS_FOUND, ChecklistView.foundWalls(room))
                            putExtra(ReviewActivity.EXTRA_SHELL, dev.capturekit.capture.EventParser.encodeShell(room.shell))
                            putExtra(ReviewActivity.EXTRA_SHELL_CLOSED, room.shellClosed)
                            putExtra(ReviewActivity.EXTRA_SPANS, dev.capturekit.capture.EventParser.encodeSpans(room.spans))
                            putExtra(ReviewActivity.EXTRA_OBJECTS, dev.capturekit.capture.EventParser.encodeObjects(room.objects))
                            putExtra(ReviewActivity.EXTRA_OPENINGS, dev.capturekit.capture.EventParser.encodeOpenings(room.openings))
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
        hud.setUnderstanding(if (recording && !research.unguided) overlay else null)
        // Recalibration card (issue #1): only while scanning; the unguided trial arm gets no guidance, but the core still
        // pauses keyframes there. A user pause hides it (the core keeps tracking the recovery and resumes it on Resume).
        val r = recal
        hud.setRecalibrate(if (recording && !corePaused && !finishing && !research.unguided && r.active) r.prompt else null)
        refreshMic()
        if (recording) {
            hud.setPaused(corePaused)
            hud.setRecInfo(elapsedMs())
            if (!corePaused && instruction.isNotEmpty() && !research.unguided) hud.setCoaching(instruction, instructionLong)
        }
        // Banner is for errors only: a forced thermal/battery pause blocks capture, so it stays here.
        val thermalMsg = if (recording && p != null && p.mustPause) "Capture paused: the phone is too hot or the battery is low. Let it rest." else null
        val am = arMessage
        when {
            am != null && ar.state == ArState.NO_PERMISSION ->
                hud.setBanner("Camera permission is needed to scan the room.", UiKit.Kind.ERROR, CameraPermission.retryLabel()) { CameraPermission.retry(this) }
            am != null && ar.state != ArState.READY ->
                hud.setBanner(am, UiKit.Kind.ERROR, "Retry") { arMessage = null; ar.retry(); refresh() }
            !speech.available && recording && !research.unguided -> hud.setBanner("Voice guidance is unavailable: no offline text-to-speech voice. On-screen prompts still work.", UiKit.Kind.INFO)
            thermalMsg != null -> hud.setBanner(thermalMsg, UiKit.Kind.WARN)
            else -> hud.setBanner(null)
        }
    }

    // Core event thread (not the AR thread).
    override fun onEvent(e: CoreEvent) {
        // Pure reducer (same instance for unrelated events); after finishScan() reset it, a late event is ignored.
        synchronized(recalLock) { if (!finishing) recal = recal.on(e) }
        when (e) {
            is CoreEvent.FrameQuality -> { lastQuality = e.quality; lastReasons = e.reasons }
            is CoreEvent.KeyframeRequest -> keyframes++
            is CoreEvent.TrackingLost -> {
                Log.i(TAG, "tracking lost for ${e.degradedS} s (state ${e.trackingState}, reason ${e.reason}): recalibrating")
                ui.post { refresh() }   // show the card now, not at the next 250 ms tick
            }
            is CoreEvent.TrackingReset -> {
                Log.i(TAG, "tracking reset after ${e.lostS} s, drift ${e.driftM} m / ${e.driftDeg} deg")
                // The prompt auto-dismisses; a stale "turn in a circle" line must not stay up until the next instruction.
                val wasRecal = synchronized(recalLock) {
                    (instructionType == Recalibration.RECALIBRATE).also { if (it) { instruction = ""; instructionType = "" } }
                }
                ui.post {
                    if (recording && !corePaused && !finishing && !research.unguided) {
                        if (wasRecal) hud.setCoaching(scanPrompt())
                        hud.haptic(true)   // confirm: back on track
                    }
                    refresh()
                }
            }
            is CoreEvent.Instruction -> {
                synchronized(recalLock) { instruction = e.speakText; instructionLong = e.longText; instructionType = e.type }
                research.onInstruction()
                if (!corePaused && !research.unguided) {
                    speech.onInstruction(e.id, e.speakText, e.priority)
                    hud.hapticFor(e.haptic, e.priority)
                }
            }
            is CoreEvent.Overlay -> overlay = e
            is CoreEvent.Governor -> lastGovernor = "${e.from}->${e.to} (${e.reason})"
            else -> {}
        }
    }
    override fun onPolicy(p: Policy) { lastPolicy = p; research.onThermal(p.level, SystemClock.elapsedRealtime()) }

    // Detector thread: one keyframe's boxes (already sent to the core) for the camera-feed overlay.
    override fun onDetections(frameId: Int, tNs: Long, boxes: List<DetectedBox>) {
        if (recording && !corePaused && !finishing && !research.unguided) boxView.setDetections(boxes)
    }

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
        if (requestCode == VoiceController.REQ_CODE) { voice.onPermissionResult(grantResults); return }
        if (CameraPermission.onResult(this, grantResults)) { arMessage = null; ar.resume() }
        else { arMessage = "Camera permission is needed to scan the room."; refresh() }
    }

    override fun onPause() {
        val t0 = SystemClock.elapsedRealtime()
        ui.removeCallbacks(tick)
        // Leaving the screen mid-scan: pause the core session so no gap is recorded as tracking data.
        if (recording && !corePaused && !finishing) session?.pause()
        voice.onPause()
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
            val kf = keyframes
            val elapsed = elapsedMs()
            val r = research
            Thread {
                val st = s.finish()
                r.end(s.dir, st, kf, elapsed, SystemClock.elapsedRealtime())
                Log.w(TAG, "mid-scan finish of ${s.dir.name}: status ${UiKit.statusName(st)}")
            }.start()
        }
        hud.release()
        voice.release()
        speech.release()
        ar.close()
        super.onDestroy()
    }

    private companion object { const val STATE_PAUSED = 3; const val TAG = "CKCapture" }
}
