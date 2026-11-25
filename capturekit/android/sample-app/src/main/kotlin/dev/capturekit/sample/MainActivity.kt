package dev.capturekit.sample

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import dev.capturekit.capture.ResearchReport
import dev.capturekit.capture.SessionManager
import dev.capturekit.consent.ConsentStore
import dev.capturekit.nativebridge.Status
import dev.capturekit.sample.upload.UploadGate
import dev.capturekit.upload.UploadPlan
import dev.capturekit.upload.UploadPolicy
import dev.capturekit.upload.UploadSettings
import dev.capturekit.upload.UploadState
import dev.capturekit.upload.UploadStatus
import dev.capturekit.upload.UploadWorker
import dev.capturekit.verdict.ScanInfo
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Entry point. First run: one onboarding page, the camera permission, then the consent screen. Afterwards Home is the
 * library ("My scans"): every bundle on this phone, newest first, with its check result and (field flavour) the
 * upload stage with Upload / Retry; inline alerts for camera, AR and a recovered scan; the gear opens Settings and the
 * sticky "Scan a room" starts a scan. Disk and WorkManager reads run on a worker thread; rows are updated in place
 * (keyed by folder), refreshed every 2 s while an upload is active. The standard flavour has no upload UI.
 */
class MainActivity : Activity() {
    private var home = false
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ck_prefs", MODE_PRIVATE) }
    private val consent by lazy { ConsentStore.get(this) }
    private val debuggable get() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private var permAlert: InlineAlert? = null
    private var arAlert: InlineAlert? = null
    private var recoveryAlert: InlineAlert? = null
    private var uploadOffAlert: InlineAlert? = null
    private var skeleton: View? = null
    private var empty: View? = null
    private var countHeader: TextView? = null
    private var list: LinearLayout? = null
    private val rowViews = LinkedHashMap<String, ScanRowView>()
    @Volatile private var recoveryNote: String? = null

    private var wifiOnly = true
    private var generation = 0
    private val thumbs = ConcurrentHashMap<String, Bitmap>()
    @Volatile private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Features.prefetch(this)   // ADR 0032: debug-build switches read off the main thread before the capture screen
        val forceOnboarding = debuggable && intent.getBooleanExtra(EXTRA_SHOW_ONBOARDING, false)
        if (prefs.getBoolean(KEY_ONBOARDED, false) && !forceOnboarding) showHome() else showOnboarding()
        if (savedInstanceState == null) checkLastExit()
    }

    private fun checkLastExit() {
        Thread {
            val reason = lastExitReason()
            val recovered = try { SessionManager.recoverInterrupted(this) } catch (t: Throwable) { Log.w(TAG, "recovery failed", t); emptyList() }
            if (reason != null) Log.i(TAG, "last process exit: $reason; recovered ${recovered.size} scan(s)")
            if (recovered.isEmpty()) return@Thread
            runOnUiThread {
                recoveryNote = "Your last scan ended unexpectedly. It was recovered and saved."
                paintRecovery()
                if (home) startLoop()
            }
        }.start()
    }

    private fun lastExitReason(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = getSystemService(ActivityManager::class.java) ?: return null
            val info = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return null
            if (info.timestamp <= prefs.getLong(KEY_EXIT_TS, 0L)) return null
            val text = "${exitName(info.reason)} status=${info.status} (${info.description ?: "-"})"
            prefs.edit().putLong(KEY_EXIT_TS, info.timestamp).putString(KEY_EXIT_REASON, text).apply()
            text
        } catch (t: Throwable) { Log.w(TAG, "exit reasons unavailable", t); null }
    }

    private fun exitName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "REASON_$r"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(bottom: Int = Tokens.S12) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(bottom) }

    /** Scrolling [content] above an opaque bottom bar holding [primary]. */
    private fun setScreen(content: View, primary: Button) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(content)
            UiKit.applyInsets(this, Tokens.SCREEN_MARGIN, bottom = false)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.c(this@MainActivity).bg)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(UiKit.bottomActionBar(this@MainActivity, primary), lp(0))
        })
    }

    // ---- First run: one page ----
    private fun showOnboarding() {
        home = false
        clearHomeRefs()
        val p = UiKit.c(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(UiKit.largeTitle(this, "Scan a room in 3D"), lp(Tokens.S32))
        for ((icon, title, body) in listOf(
            Triple(R.drawable.ic_cube, "Walk around the room", "Move slowly and keep the phone steady. CaptureKit tracks what you have covered."),
            Triple(R.drawable.ic_voice, "Follow the cues", "Spoken and on-screen prompts tell you what to scan next and what to revisit."),
            Triple(R.drawable.ic_lock, "Stays on this phone", "Your scan is stored here unless you choose to upload it."),
        )) root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(UiKit.iconImage(context, icon, p.label), LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(Tokens.S16); topMargin = dp(2) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(UiKit.headline(context, title))
                addView(UiKit.callout(context, body), lp(0).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, lp(Tokens.S24))
        val next = UiKit.primaryButton(this, if (CameraPermission.granted(this)) "Continue" else "Allow camera and continue").apply {
            setOnClickListener {
                if (CameraPermission.granted(this@MainActivity)) finishOnboarding()
                else CameraPermission.request(this@MainActivity)
            }
        }
        setScreen(root, next)
    }

    private fun finishOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
        showHome()
        if (!consent.hasConsent()) startActivity(Intent(this, ConsentActivity::class.java))
    }

    private fun clearHomeRefs() {
        permAlert = null; arAlert = null; recoveryAlert = null; uploadOffAlert = null
        skeleton = null; empty = null; countHeader = null; list = null
        rowViews.clear()
    }

    // ---- Home: the library ----
    private fun showHome() {
        home = true
        clearHomeRefs()
        wifiOnly = UploadSettings.unmeteredOnly(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiKit.largeTitle(this@MainActivity, "My scans"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(UiKit.iconButton(this@MainActivity, R.drawable.ic_settings, "Settings").apply {
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }, LinearLayout.LayoutParams(dp(Tokens.TOUCH_MIN), dp(Tokens.TOUCH_MIN)))
        }, lp(Tokens.S24))

        recoveryAlert = InlineAlert(this).also { root.addView(it, lp()) }
        paintRecovery()
        permAlert = InlineAlert(this).also { root.addView(it, lp()) }
        arAlert = InlineAlert(this).also { root.addView(it, lp()) }
        uploadOffAlert = InlineAlert(this).also { root.addView(it, lp()) }

        countHeader = UiKit.sectionHeader(this, "").apply { visibility = View.GONE }.also { root.addView(it) }
        skeleton = UiKit.group(this).apply { repeat(3) { addView(UiKit.skeletonRow(context)) } }.also { root.addView(it, lp(Tokens.S24)) }
        empty = UiKit.emptyState(this, R.drawable.ic_cube, "No scans yet", "Scan a room and it shows up here with its check result.")
            .apply { visibility = View.GONE }.also { root.addView(it, lp(Tokens.S24)) }
        list = UiKit.group(this).apply { visibility = View.GONE }.also { root.addView(it, lp(Tokens.S24)) }

        val scan = UiKit.primaryButton(this, "Scan a room").apply { setOnClickListener { startScan() } }
        setScreen(root, scan)
        refresh()
        if (visible) startLoop()
    }

    private fun startScan() {
        when {
            !CameraPermission.granted(this) -> CameraPermission.request(this)
            !consent.hasConsent() -> startActivity(Intent(this, ConsentActivity::class.java).putExtra(ConsentActivity.EXTRA_START_SCAN, true))
            else -> startActivity(Intent(this, CaptureActivity::class.java))
        }
    }

    private fun paintRecovery() {
        val a = recoveryAlert ?: return
        val n = recoveryNote
        a.set(n, UiKit.Kind.OK, "Dismiss", { recoveryNote = null; paintRecovery() }, title = "Scan recovered")
    }

    private fun refresh() {
        if (!home) return
        val ok = CameraPermission.granted(this)
        permAlert?.set(if (ok) null else "CaptureKit uses the camera to see the room and track your movement.", UiKit.Kind.ERROR,
            CameraPermission.retryLabel(), { CameraPermission.retry(this) }, title = "Camera access needed")

        val av = try { ArCoreApk.getInstance().checkAvailability(this) } catch (t: Throwable) { Log.w(TAG, "AR availability check failed", t); null }
        val arText = when {
            av == null -> "AR status unknown"
            av.isTransient -> null
            av == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> "Install Google Play Services for AR"
            av == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> "Update Google Play Services for AR"
            av.isSupported -> null
            else -> "This phone does not support AR"
        }
        arAlert?.set(arText?.let { "$it. CaptureKit needs it to track the phone's position in the room." }, UiKit.Kind.WARN,
            "Check again", { refresh() }, title = "Google Play Services for AR needed")
        if (av?.isTransient == true) ui.postDelayed({ refresh() }, 500)
    }

    // ---- Library loading (worker thread) and in-place rendering ----
    private data class Row(
        val dir: File, val timeMs: Long, val room: String?, val verdict: String?, val durationMs: Long?, val size: Long,
        val upload: UploadStatus, val thumb: Bitmap?,
    )

    /** Re-reads the library on a worker thread; keeps polling every 2 s while any upload is active and the screen is visible. */
    private fun startLoop() {
        val gen = ++generation
        Thread {
            while (visible && gen == generation) {
                val rows = loadRows()
                runOnUiThread { if (!isDestroyed && gen == generation && home) render(rows) }
                val active = rows.any { it.upload.state == UploadState.WAITING || it.upload.state == UploadState.UPLOADING || it.upload.state == UploadState.RETRYING }
                if (!active) break
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    private fun loadRows(): List<Row> {
        val uploads = UploadGate.available
        val rows = ScanLibrary.bundleDirs(this).map { d ->
            val parsed = ScanLibrary.manifestOf(d)
            val research = try { ResearchReport.fileFor(d).takeIf { it.exists() }?.readText() } catch (t: Throwable) { Log.w(TAG, "research file unreadable: ${d.name}", t); null }
            Row(d, ScanInfo.timeMs(d.name, d.lastModified()), ScanInfo.roomLabel(research), parsed.verdict, parsed.durationMs,
                try { UiKit.dirSizeBytes(d) } catch (t: Throwable) { 0L },
                if (uploads) UploadWorker.status(applicationContext, d) else UploadStatus(UploadState.NONE), thumbFor(d))
        }.sortedByDescending { it.timeMs }
        val alive = rows.mapTo(HashSet()) { it.dir.absolutePath }
        thumbs.keys.retainAll(alive)   // deleted scans release their thumbnails
        return rows
    }

    /** Middle keyframe, decoded small once per scan (worker thread). */
    private fun thumbFor(d: File): Bitmap? {
        thumbs[d.absolutePath]?.let { return it }
        return try {
            val jpgs = File(d, "keyframes").listFiles { f -> f.name.endsWith(".jpg", true) || f.name.endsWith(".jpeg", true) }?.sortedBy { it.name }
            val f = jpgs?.getOrNull(jpgs.size / 2) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 168 && bounds.outHeight / (sample * 2) >= 168) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.also { thumbs[d.absolutePath] = it }
        } catch (t: Throwable) { Log.w(TAG, "thumbnail failed: ${d.name}", t); null }
    }

    private fun render(rows: List<Row>) {
        val group = list ?: return
        skeleton?.visibility = View.GONE
        empty?.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        group.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        countHeader?.let { it.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE; it.text = (if (rows.size == 1) "1 scan" else "${rows.size} scans").uppercase() }
        val consentUpload = UploadGate.available && consent.uploadAllowed()
        uploadOffAlert?.set(if (UploadGate.available && !consentUpload && rows.isNotEmpty()) "Uploading is off. You did not allow uploads in the consent screen." else null, UiKit.Kind.INFO)

        val keep = HashSet<String>()
        for ((i, r) in rows.withIndex()) {
            val key = r.dir.absolutePath
            keep.add(key)
            val v = rowViews.getOrPut(key) { ScanRowView(this) }
            if (group.getChildAt(i) !== v) { (v.parent as? ViewGroup)?.removeView(v); group.addView(v, i) }
            v.bind(r, friendlyDate(r.timeMs), consentUpload)
        }
        val it = rowViews.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (e.key !in keep) { group.removeView(e.value); it.remove() } }
    }

    private fun queue(dir: File, verdict: String?) {
        Thread {
            try { UploadGate.enqueueIfAllowed(applicationContext, dir, verdict, replace = false) }
            catch (t: Throwable) { Log.w(TAG, "upload enqueue failed: ${dir.name}", t) }
            runOnUiThread { if (!isDestroyed) startLoop() }
        }.start()
    }

    /** "Today, 12:07", "Yesterday, 21:58", "17 Nov, 21:35", "17 Nov 2025" (phone's own time format). */
    private fun friendlyDate(ms: Long): String {
        val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = ms }
        val time = android.text.format.DateFormat.getTimeFormat(this).format(Date(ms))
        val days = (startOfDay(now) - startOfDay(c)) / 86_400_000L
        return when {
            days == 0L -> "Today, $time"
            days == 1L -> "Yesterday, $time"
            now.get(Calendar.YEAR) == c.get(Calendar.YEAR) -> android.text.format.DateFormat.format("d MMM", c).toString() + ", $time"
            else -> android.text.format.DateFormat.format("d MMM yyyy", c).toString()
        }
    }

    private fun startOfDay(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** One library row, created once per scan folder and re-bound in place. */
    private inner class ScanRowView(ctx: Context) : LinearLayout(ctx) {
        private val p = UiKit.c(ctx)
        private val thumb = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val title = UiKit.text(ctx, "", Tokens.BODY, p.label).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val subtitle = UiKit.footnote(ctx, "", p.label2).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; fontFeatureSettings = "tnum" }
        private val badge = StatusBadge(ctx)
        private val stage = StageRow(ctx).apply { visibility = GONE }
        private val action = UiKit.textButton(ctx, "").apply { visibility = GONE }
        private val note = UiKit.footnote(ctx, "").apply { visibility = GONE }
        private var shownThumb: Bitmap? = null
        private var row: Row? = null

        init {
            orientation = VERTICAL
            setPadding(dp(Tokens.S16), dp(Tokens.S12), dp(Tokens.S16), dp(Tokens.S12))
            minimumHeight = dp(Tokens.ROW_MIN)
            isClickable = true; isFocusable = true
            foreground = UiKit.rowRipple(ctx)
            val frame = FrameLayout(ctx).apply {
                background = UiKit.rounded(p.surface2, dp(Tokens.R_THUMB).toFloat())
                clipToOutline = true
                addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val info = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(title)
                addView(subtitle, lp(Tokens.S4).apply { topMargin = dp(2) })
                addView(badge)
            }
            addView(LinearLayout(ctx).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(frame, LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(Tokens.S12) })
                addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(UiKit.iconImage(ctx, R.drawable.ic_chevron_right, p.label3), LayoutParams(dp(20), dp(20)))
            })
            addView(stage, lp(0).apply { topMargin = dp(Tokens.S12); leftMargin = dp(56 + Tokens.S12) })
            addView(note, lp(0).apply { topMargin = dp(Tokens.S8); leftMargin = dp(56 + Tokens.S12) })
            addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Tokens.S4); leftMargin = dp(56) })
            setOnClickListener {
                val r = row ?: return@setOnClickListener
                startActivity(Intent(this@MainActivity, ReviewActivity::class.java).putExtra(ReviewActivity.EXTRA_DIR, r.dir.absolutePath).putExtra(ReviewActivity.EXTRA_STATUS, Status.OK))
            }
            action.setOnClickListener {
                val r = row ?: return@setOnClickListener
                action.isEnabled = false
                UiKit.paintButton(action, UiKit.ButtonStyle.TEXT)
                queue(r.dir, r.verdict)
            }
        }

        fun bind(r: Row, date: String, consentUpload: Boolean) {
            row = r
            if (r.thumb !== shownThumb) { shownThumb = r.thumb; thumb.setImageBitmap(r.thumb) }
            setText(title, r.room ?: date)
            val sub = listOfNotNull(if (r.room != null) date else null, r.durationMs?.takeIf { it > 0 }?.let { UiKit.mmss(it) }).joinToString(" · ")
            setText(subtitle, sub.ifEmpty { UiKit.humanSize(r.size) })
            val verdictText = ScanInfo.badgeText(r.verdict)
            badge.set(verdictText, ScanUi.verdictKind(r.verdict), hollow = r.verdict == null)
            var spoken = "Scan $date${r.room?.let { ", $it" } ?: ""}. Check: $verdictText."
            if (UploadGate.available) {
                stage.visibility = VISIBLE
                stage.set(r.upload, wifiOnly)
                spoken += " ${ScanUi.stageText(ScanUi.stageOf(r.upload), wifiOnly)}."
                val canUpload = consentUpload && UploadPolicy.eligible(true, r.verdict)
                val label = when {
                    canUpload && r.upload.state == UploadState.NONE -> "Upload"
                    canUpload && r.upload.state == UploadState.FAILED -> "Retry upload"
                    else -> null
                }
                action.visibility = if (label != null) VISIBLE else GONE
                if (label != null) {
                    setText(action, label)
                    action.contentDescription = "$label for the scan from $date"
                    if (!action.isEnabled) { action.isEnabled = true; UiKit.paintButton(action, UiKit.ButtonStyle.TEXT) }
                }
                val noteText = if (label == null && r.verdict != "PASS" && r.verdict != "WARN" && r.upload.state == UploadState.NONE)
                    (if (r.verdict == "FAIL") "A failed scan is never uploaded." else "Not finalized: this scan cannot be uploaded.") else null
                note.visibility = if (noteText != null) VISIBLE else GONE
                if (noteText != null) setText(note, noteText)
            }
            contentDescription = "$spoken Double tap for details."
        }

        private fun setText(v: TextView, s: String) { if (v.text.toString() != s) v.text = s }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (home) {
            wifiOnly = UploadSettings.unmeteredOnly(this)   // may have changed in Settings
            refresh()
            startLoop()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CameraPermission.onResult(this, grantResults)
        if (!home) finishOnboarding() else refresh()
    }

    override fun onPause() {
        visible = false
        ui.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        generation++
        thumbs.clear()
        super.onDestroy()
    }

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_EXIT_TS = "last_exit_ts"
        const val KEY_EXIT_REASON = "last_exit_reason"
        /** Debug builds only: `adb shell am start -n dev.capturekit.sample/.MainActivity --ez ck_show_onboarding true`. */
        const val EXTRA_SHOW_ONBOARDING = "ck_show_onboarding"
        const val TAG = "CKMain"
    }
}

/** The scan bundles on this phone (blocking disk reads: worker thread). */
object ScanLibrary {
    fun bundleDirs(ctx: Context): List<File> =
        ctx.getExternalFilesDir("bundles")?.listFiles { f -> f.isDirectory }?.toList().orEmpty()

    fun manifestOf(dir: File): ReviewActivity.Parsed =
        ReviewActivity.parseManifest(try { File(dir, UploadPlan.MANIFEST).readText() } catch (t: Throwable) { "" })
}
