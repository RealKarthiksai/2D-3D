package dev.capturekit.sample

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dev.capturekit.guidance.SpeechScheduler
import java.util.Locale

/**
 * Speaks core guidance templates verbatim with the platform TextToSpeech (offline voice only, no network
 * path). Scheduling (priority preemption, repeat suppression, budget) lives in [SpeechScheduler]; this class
 * owns the engine, audio focus ducking and the latency log (tag CK/Voice). Never called from the AR thread:
 * instructions arrive on the core event thread. All public methods are safe from any thread.
 */
class SpeechRenderer(
    context: Context,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val speakTimeoutMs: Long = 10_000,
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sched = SpeechScheduler(SpeechScheduler.Config(speakTimeoutMs = speakTimeoutMs))
    private val main = Handler(Looper.getMainLooper())
    private var released = false
    private val watchdog = Runnable { onWatchdog() }
    private val prefs = app.getSharedPreferences("ck_prefs", Context.MODE_PRIVATE)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var focus: AudioFocusRequest? = null
    private val issuedAt = HashMap<String, Long>()
    private val latencies = ArrayList<Long>()
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    /** Off-switch, persisted. */
    var enabled: Boolean
        get() = sched.enabled
        @Synchronized set(v) {
            sched.enabled = v
            prefs.edit().putBoolean(KEY_ENABLED, v).apply()
            if (!v) stopNow()
        }

    /** False when the device has no usable TTS engine/voice (reported once; we never fall back to cloud TTS). */
    @Volatile var available = true
        private set

    init {
        sched.enabled = prefs.getBoolean(KEY_ENABLED, true)
        // Assigned under the lock: onInit (synchronized) can never observe tts == null from another thread.
        synchronized(this) { tts = TextToSpeech(app) { status -> onInit(status) } }
    }

    @Synchronized private fun onInit(status: Int) {
        val t = tts
        if (t == null) {   // callback ran before the constructor returned: retry on the next loop turn
            if (!released) main.post { onInit(status) }
            return
        }
        if (status != TextToSpeech.SUCCESS) { available = false; Log.w(TAG, "TTS init failed: $status"); return }
        val r = t.setLanguage(Locale.US)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            available = false; Log.w(TAG, "TTS has no offline en-US voice (result $r)"); return
        }
        t.setAudioAttributes(attrs)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = started(utteranceId)
            override fun onDone(utteranceId: String) = ended(utteranceId)
            @Deprecated("deprecated in API 21") override fun onError(utteranceId: String) = ended(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) { Log.w(TAG, "utterance $utteranceId error $errorCode"); ended(utteranceId) }
            override fun onStop(utteranceId: String, interrupted: Boolean) = ended(utteranceId)
        })
        ready = true
        Log.i(TAG, "TTS ready, engine=${t.defaultEngine}")
    }

    /** Entry point for CoreEvent.Instruction (speakText is the template output, spoken as is). */
    @Synchronized fun onInstruction(id: Int, speakText: String, priority: Int) {
        if (!ready) { Log.i(TAG, "instruction $id not spoken: TTS not ready"); return }
        val now = clock()
        when (val a = sched.onInstruction(id, speakText, priority, now)) {
            is SpeechScheduler.Action.Speak -> play(a, now)
            SpeechScheduler.Action.Queued -> Log.d(TAG, "instruction $id queued")
            is SpeechScheduler.Action.Drop -> Log.i(TAG, "instruction $id dropped: ${a.reason}")
        }
    }

    private fun play(a: SpeechScheduler.Action.Speak, now: Long) {
        val t = tts ?: return
        issuedAt[a.utteranceId] = now
        requestFocus()
        val mode = if (a.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (t.speak(a.text, mode, null, a.utteranceId) != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak() failed for ${a.utteranceId}")
            sched.onDone(a.utteranceId, clock()); issuedAt.remove(a.utteranceId); abandonFocus()
        } else {
            main.removeCallbacks(watchdog); main.postDelayed(watchdog, speakTimeoutMs + 250)
        }
    }

    /** Engine never called onStart/onDone/onError: forget the utterance, stop the engine, drop audio focus. */
    @Synchronized private fun onWatchdog() {
        if (released || !sched.isSpeaking()) return
        val now = clock()
        if (sched.expireStuck(now)) {
            Log.w(TAG, "TTS gave no callback for ${speakTimeoutMs} ms: clearing the stuck utterance")
            tts?.stop(); issuedAt.clear()
            val next = sched.nextPending(now)
            if (next != null) play(next, now) else abandonFocus()
        } else main.postDelayed(watchdog, 1000)   // started recently: keep watching
    }

    @Synchronized private fun started(uid: String) {
        val now = clock()
        sched.onStart(uid, now)
        val t0 = issuedAt.remove(uid) ?: return
        val ms = now - t0
        latencies.add(ms)
        val sorted = latencies.sorted()
        Log.i(TAG, "instruction -> TTS onStart $ms ms ($uid) n=${latencies.size} median=${sorted[sorted.size / 2]} ms")
    }

    @Synchronized private fun ended(uid: String) {
        val now = clock()
        sched.onDone(uid, now)
        issuedAt.remove(uid)
        val next = sched.nextPending(now)
        if (next != null) play(next, now) else if (!sched.isSpeaking()) { main.removeCallbacks(watchdog); abandonFocus() }
    }

    /** Stops speaking immediately and forgets queued lines (pause, finish, mute). */
    @Synchronized fun stopNow() {
        main.removeCallbacks(watchdog)
        tts?.stop()
        issuedAt.clear()
        sched.reset()
        abandonFocus()
    }

    @Synchronized fun release() {
        released = true
        stopNow()
        ready = false
        tts?.shutdown(); tts = null
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs).setWillPauseWhenDucked(false).build()
        if (audio.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = req
    }

    private fun abandonFocus() { focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null }

    companion object {
        const val TAG = "CK/Voice"
        const val KEY_ENABLED = "voice_enabled"

        fun savedEnabled(context: Context) =
            context.getSharedPreferences("ck_prefs", Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        fun saveEnabled(context: Context, v: Boolean) =
            context.getSharedPreferences("ck_prefs", Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply()
    }
}
