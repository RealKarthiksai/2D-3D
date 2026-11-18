package dev.capturekit.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.capturekit.capture.ResearchReport
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
import java.text.DateFormat
import java.util.Date

/**
 * "My scans": every bundle on this phone, newest first, with date, room label (field trials), the validator verdict
 * as a coloured badge with words, and (field flavour only) the upload state with progress, Upload / Retry actions and
 * the "Wi-Fi only" switch. All disk and WorkManager reads run on a worker thread; the list refreshes every 2 s while
 * an upload is active. The standard flavour has no INTERNET permission, so it shows no upload UI at all.
 */
class ScansActivity : Activity() {
    private data class Row(val dir: File, val timeMs: Long, val room: String?, val verdict: String?, val size: Long, val upload: UploadStatus)

    private lateinit var list: LinearLayout
    private var wifiOnly = true
    private var generation = 0
    @Volatile private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiOnly = UploadSettings.unmeteredOnly(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "My scans"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
                if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(UiKit.secondaryButton(context, "Back").apply {
                contentDescription = "Back to home"; setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header, lp(12))
        if (UploadGate.available) root.addView(settingsPanel(), lp(12))
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        UiKit.applyInsets(root, 16)
        setContentView(root)
        showMessage("Loading your scans...")
    }

    override fun onResume() {
        super.onResume()
        visible = true
        startLoop()
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    /** Re-reads everything on a worker thread; keeps polling every 2 s while any upload is active and the screen is visible. */
    private fun startLoop() {
        val gen = ++generation
        Thread {
            while (visible && gen == generation) {
                val rows = loadRows()
                runOnUiThread { if (!isDestroyed && gen == generation) render(rows) }
                val active = rows.any { it.upload.state == UploadState.WAITING || it.upload.state == UploadState.UPLOADING || it.upload.state == UploadState.RETRYING }
                if (!active) break
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    private fun loadRows(): List<Row> {
        val root = getExternalFilesDir("bundles") ?: return emptyList()
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val uploads = UploadGate.available
        return dirs.map { d ->
            val manifest = try { File(d, UploadPlan.MANIFEST).readText() } catch (t: Throwable) { "" }
            val research = try { ResearchReport.fileFor(d).takeIf { it.exists() }?.readText() } catch (t: Throwable) { null }
            Row(d, ScanInfo.timeMs(d.name, d.lastModified()), ScanInfo.roomLabel(research), ReviewActivity.parseManifest(manifest).verdict,
                try { UiKit.dirSizeBytes(d) } catch (t: Throwable) { 0L },
                if (uploads) UploadWorker.status(applicationContext, d) else UploadStatus(UploadState.NONE))
        }.sortedByDescending { it.timeMs }
    }

    private fun showMessage(msg: String) {
        list.removeAllViews()
        list.addView(TextView(this).apply { text = msg; textSize = 18f; setTextColor(0xFFB0BEC5.toInt()); gravity = Gravity.CENTER }, lp(0))
    }

    private fun render(rows: List<Row>) {
        list.removeAllViews()
        if (rows.isEmpty()) { renderEmpty(); return }
        val consentUpload = UploadGate.available && ConsentStore.get(this).uploadAllowed()
        if (UploadGate.available && !consentUpload) {
            list.addView(UiKit.banner(this, "Uploading is off. You did not allow uploads in the consent screen.", UiKit.Kind.WARN).apply { textSize = 15f }, lp(12))
        }
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (r in rows) list.addView(card(r, fmt.format(Date(r.timeMs)), consentUpload), lp(12))
    }

    private fun renderEmpty() {
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(16), dp(48), dp(16), dp(16))
            addView(TextView(context).apply {
                text = "No scans yet"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            }, lp(8))
            addView(TextView(context).apply {
                text = "Scan a room and it will show up here with its check result."
                textSize = 16f; setTextColor(0xFFB0BEC5.toInt()); gravity = Gravity.CENTER
            }, lp(24))
            addView(UiKit.bigButton(context, "Start a scan").apply {
                setOnClickListener { startActivity(Intent(this@ScansActivity, CaptureActivity::class.java)); finish() }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun card(r: Row, date: String, consentUpload: Boolean): View {
        val badge = UiKit.chip(this).also { UiKit.setChip(it, ScanInfo.badgeText(r.verdict), ScanUi.verdictColor(r.verdict)) }
        val title = TextView(this).apply {
            text = if (r.room != null) "$date  -  ${r.room}" else date
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
            addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(0xFF1B222A.toInt(), dp(14).toFloat())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(top, lp(4))
            addView(TextView(context).apply {
                text = "${UiKit.humanSize(r.size)}  -  ${r.dir.name}"; textSize = 14f; setTextColor(0xFFB0BEC5.toInt())
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            }, lp(8))
        }
        var spoken = "Scan $date${r.room?.let { ", $it" } ?: ""}. Check: ${ScanInfo.badgeText(r.verdict)}."
        if (UploadGate.available) {
            val chip = UiKit.chip(this).also { ScanUi.paintUpload(it, r.upload, wifiOnly) }
            col.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            if (r.upload.state == UploadState.UPLOADING) col.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = r.upload.percent.coerceIn(0, 100)
                contentDescription = "Upload progress ${r.upload.percent} percent"
            }, lp(8))
            spoken += " ${ScanUi.uploadText(r.upload, wifiOnly).replace("%", " percent")}."
            val canUpload = consentUpload && UploadPolicy.eligible(true, r.verdict)
            val action = when {
                canUpload && r.upload.state == UploadState.NONE -> "Upload"
                canUpload && r.upload.state == UploadState.FAILED -> "Retry upload"
                else -> null
            }
            if (action != null) col.addView(UiKit.secondaryButton(this, action).apply {
                contentDescription = "$action for the scan from $date"
                setOnClickListener { isEnabled = false; queue(r.dir, r.verdict, replace = false) }
            }, lp(0))
            else if (r.verdict != "PASS" && r.verdict != "WARN" && r.upload.state == UploadState.NONE) col.addView(TextView(this).apply {
                text = if (r.verdict == "FAIL") "A failed scan is never uploaded." else "Not finalized: this scan cannot be uploaded."; textSize = 14f; setTextColor(0xFFB0BEC5.toInt())
            }, lp(0))
        }
        col.isClickable = true
        col.contentDescription = spoken + " Double tap for details."
        col.setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java).putExtra(ReviewActivity.EXTRA_DIR, r.dir.absolutePath).putExtra(ReviewActivity.EXTRA_STATUS, Status.OK))
        }
        return col
    }

    private fun queue(dir: File, verdict: String?, replace: Boolean) {
        Thread {
            try { UploadGate.enqueueIfAllowed(applicationContext, dir, verdict, replace) } catch (t: Throwable) { }
            runOnUiThread { if (!isDestroyed) startLoop() }
        }.start()
    }

    private fun settingsPanel(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = UiKit.rounded(0xFF1B222A.toInt(), dp(14).toFloat())
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(Switch(context).apply {
            text = "Upload on Wi-Fi only"; textSize = 16f; setTextColor(Color.WHITE)
            isChecked = wifiOnly; minHeight = dp(48)
            contentDescription = "Upload on Wi-Fi only. Turn off to also upload on mobile data."
            setOnCheckedChangeListener { _, on ->
                wifiOnly = on
                Thread {
                    UploadSettings.setUnmeteredOnly(applicationContext, on)
                    // Queued uploads keep the old network rule, so restart the ones that have not finished.
                    for (r in loadRows()) if (r.upload.state == UploadState.WAITING || r.upload.state == UploadState.RETRYING || r.upload.state == UploadState.UPLOADING)
                        try { UploadGate.enqueueIfAllowed(applicationContext, r.dir, r.verdict, replace = true) } catch (t: Throwable) { }
                    runOnUiThread { if (!isDestroyed) startLoop() }
                }.start()
            }
        })
        addView(TextView(context).apply {
            text = "Scans are large. Off means mobile data may be used."; textSize = 13f; setTextColor(0xFFB0BEC5.toInt())
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom) }
}
