package dev.capturekit.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ar.core.ArCoreApk

/**
 * Launcher: first-run onboarding with the camera/motion rationale (permission is requested only after it is
 * shown), then a home screen with the guided scan as the primary action. The raw recorder and clock check
 * (Phase 0 tools) live under "Developer tools".
 */
class MainActivity : Activity() {
    private var permLine: TextView? = null
    private var arLine: TextView? = null
    private var grant: Button? = null
    private var home = false
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ck_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(0xFF101418.toInt())
        if (prefs.getBoolean(KEY_ONBOARDED, false)) showHome() else showOnboarding(0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(bottom: Int = 12) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(bottom) }

    private fun title(text: String) = TextView(this).apply {
        this.text = text; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
    }

    private fun body(text: String) = TextView(this).apply { this.text = text; textSize = 17f; setTextColor(0xFFCFD8DC.toInt()) }

    private fun setScreen(root: LinearLayout) {
        setContentView(ScrollView(this).apply {
            addView(root)
            UiKit.applyInsets(this, 24)
        })
    }

    // ---- onboarding (3 pages) ----
    private fun showOnboarding(page: Int) {
        home = false; permLine = null; arLine = null; grant = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val last = 2
        when (page) {
            0 -> {
                root.addView(title("Scan a room with your phone"), lp(12))
                root.addView(body("CaptureKit guides you step by step while you walk around a room, speaks short prompts, and saves a complete scan bundle on this phone."), lp(12))
                root.addView(body("1. Press Start and move slowly.\n2. Follow the spoken and on-screen prompts.\n3. Press Finish and review the result."), lp(24))
            }
            1 -> {
                root.addView(title("Why we ask for the camera"), lp(12))
                root.addView(body("Camera: needed to see the room and to track how the phone moves. Images stay on this phone."), lp(12))
                root.addView(body("Motion sensors: used to notice shaky movement. Android does not ask for permission for these."), lp(12))
                root.addView(body("Nothing is uploaded: this build has no network access."), lp(24))
            }
            else -> {
                root.addView(title("Before you start"), lp(12))
                root.addView(body("- Use a well lit room.\n- Hold the phone in landscape and walk slowly.\n- Turn the volume up for spoken guidance (you can switch it off at any time)."), lp(24))
            }
        }
        val next = UiKit.bigButton(this, if (page == last) (if (CameraPermission.granted(this)) "Continue" else "Allow camera and continue") else "Next").apply {
            setOnClickListener {
                if (page < last) showOnboarding(page + 1)
                else if (CameraPermission.granted(this@MainActivity)) finishOnboarding()
                else CameraPermission.request(this@MainActivity)
            }
        }
        root.addView(next, lp(8))
        if (page > 0) root.addView(UiKit.secondaryButton(this, "Back").apply { setOnClickListener { showOnboarding(page - 1) } }, lp(8))
        root.addView(body("Step ${page + 1} of ${last + 1}").apply { gravity = Gravity.CENTER; textSize = 14f })
        setScreen(root)
    }

    private fun finishOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        showHome()
    }

    // ---- home ----
    private fun showHome() {
        home = true
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (t: Throwable) { "?" }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(title("CaptureKit"), lp(4))
        root.addView(body("Guided room scan  v$version").apply { textSize = 15f }, lp(16))
        permLine = UiKit.statusText(this).also { root.addView(it, lp(8)) }
        arLine = UiKit.statusText(this).also { root.addView(it, lp(8)) }
        grant = UiKit.bigButton(this, "Allow camera", UiKit.AMBER).apply {
            setOnClickListener { CameraPermission.retry(this@MainActivity) }
        }.also { root.addView(it, lp(16)) }

        root.addView(UiKit.bigButton(this, "Start guided scan").apply {
            setOnClickListener {
                if (CameraPermission.granted(this@MainActivity)) startActivity(Intent(this@MainActivity, CaptureActivity::class.java))
                else showOnboarding(1)
            }
        }, lp(4))
        root.addView(body("Voice and on-screen prompts, quality checks, and a saved bundle").apply { textSize = 14f }, lp(16))

        val voice = UiKit.secondaryButton(this, "")
        fun paintVoice() { voice.text = if (SpeechRenderer.savedEnabled(this)) "Voice guidance: on" else "Voice guidance: off" }
        paintVoice()
        voice.setOnClickListener { SpeechRenderer.saveEnabled(this, !SpeechRenderer.savedEnabled(this)); paintVoice() }
        root.addView(voice, lp(24))

        root.addView(body("Developer tools").apply { textSize = 14f; setTextColor(0xFF78909C.toInt()) }, lp(6))
        for ((label, cls) in listOf("Raw recorder" to RecorderActivity::class.java, "Clock check" to ClockCheckActivity::class.java)) {
            root.addView(UiKit.secondaryButton(this, label).apply {
                setOnClickListener { startActivity(Intent(this@MainActivity, cls)) }
            }, lp(8))
        }
        setScreen(root)
        refresh()
    }

    private fun refresh() {
        if (!home) return
        val ok = CameraPermission.granted(this)
        permLine?.let { UiKit.setChip(it, if (ok) "Camera permission: granted" else
            "Camera permission: NOT granted. The camera is needed to see the room and track motion.", if (ok) UiKit.GREEN else UiKit.RED) }
        grant?.apply { visibility = if (ok) View.GONE else View.VISIBLE; text = CameraPermission.retryLabel() }
        val av = try { ArCoreApk.getInstance().checkAvailability(this) } catch (t: Throwable) { null }
        val (text, color) = when {
            av == null -> "ARCore: unknown" to UiKit.AMBER
            av.isTransient -> "ARCore: checking..." to UiKit.AMBER
            av == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "ARCore: install Google Play Services for AR (will be requested)" to UiKit.AMBER
            av == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "ARCore: Google Play Services for AR needs an update" to UiKit.AMBER
            av.isSupported -> "ARCore: ready" to UiKit.GREEN
            else -> "ARCore: not supported on this phone" to UiKit.RED
        }
        arLine?.let { UiKit.setChip(it, text, color) }
        if (av?.isTransient == true) ui.postDelayed({ refresh() }, 500)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) UiKit.immersive(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CameraPermission.onResult(this, grantResults)
        if (!home) finishOnboarding() else refresh()   // rationale was shown first; Home explains how to fix a refusal
    }

    override fun onPause() { ui.removeCallbacksAndMessages(null); super.onPause() }

    private companion object { const val KEY_ONBOARDED = "onboarded" }
}
