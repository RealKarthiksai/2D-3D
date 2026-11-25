package dev.capturekit.sample

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.capturekit.capture.EventParser
import dev.capturekit.guidance.ObjectSummary
import dev.capturekit.nativebridge.Status
import dev.capturekit.sample.upload.UploadGate
import dev.capturekit.upload.UploadState
import dev.capturekit.upload.UploadStatus
import dev.capturekit.upload.UploadWorker
import dev.capturekit.verdict.ScanInfo
import dev.capturekit.verdict.VerdictText
import java.io.File
import kotlin.math.max

/**
 * Slots on the Review screen that the upload / processing bridge fills in: room measurements, the processing stage
 * and Share. Each stays GONE until wired (today only the field flavour's upload stage is).
 */
class ReviewLayout(val summary: RoomSummaryCard, val stage: StageRow, val share: Button) {
    fun showShare(onShare: () -> Unit) {
        share.setOnClickListener { onShare() }
        share.visibility = View.VISIBLE
    }
}

/**
 * Review screen after Finish (portrait, follows light/dark): the room drawing, a plain headline with the validator
 * verdict from manifest.json as a dot + words, the room parts, the [ReviewLayout] slots, the check notes as a grouped
 * list and a collapsed Details section. Field flavour: queues the upload when the user allowed it and the verdict is
 * not FAIL, and shows its stage. Standard flavour has no INTERNET permission: nothing is queued and no upload is shown.
 */
class ReviewActivity : Activity() {
    data class Parsed(val verdict: String?, val keyframes: Int?, val issues: List<String>, val durationMs: Long? = null)

    /** Bridge slots; null until the screen is built. */
    var reviewLayout: ReviewLayout? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(intent.getStringExtra(EXTRA_DIR) ?: "")
        val status = intent.getIntExtra(EXTRA_STATUS, Status.STATE)
        val elapsed = intent.getLongExtra(EXTRA_ELAPSED_MS, 0L)
        val kfLive = intent.getIntExtra(EXTRA_KEYFRAMES, 0)
        if (status != Status.OK) Log.w(TAG, "scan not saved: ${UiKit.statusName(status)} (${dir.name})")
        setContentView(UiKit.page(this, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Checking the scan"
            addView(UiKit.group(this@ReviewActivity).apply { repeat(2) { addView(UiKit.skeletonRow(context)) } })
            UiKit.applyInsets(this, Tokens.SCREEN_MARGIN)
        }))
        // Disk reads (manifest, bundle size) stay off the UI thread.
        Thread {
            val text = try { File(dir, "manifest.json").readText() } catch (t: Throwable) { Log.w(TAG, "manifest unreadable: ${dir.name}", t); "" }
            val parsed = parseManifest(text)
            val issues = VerdictText.issuesOf(text)
            val vt = ScanUi.verdictText(applicationContext)
            val size = try { UiKit.dirSizeBytes(dir) } catch (t: Throwable) { 0L }
            // Upload only with the user's upload consent and a non-FAIL verdict (field flavour; a no-op in standard).
            if (status == Status.OK && UploadGate.available) try {
                // Only a never-queued scan is queued here; a failed one waits for the explicit Retry on Home.
                if (UploadWorker.status(applicationContext, dir).state == UploadState.NONE) UploadGate.enqueueIfAllowed(applicationContext, dir, parsed.verdict)
            } catch (t: Throwable) { Log.w(TAG, "upload enqueue failed: ${dir.name}", t) }
            runOnUiThread { if (!isDestroyed) build(dir, status, elapsed, kfLive, parsed, size, vt, issues) }
        }.start()
    }

    /** Polls WorkManager off the main thread until the upload reaches a final state or the screen closes. */
    private fun watchUpload(dir: File, row: StageRow) {
        Thread {
            while (!isDestroyed) {
                val st = UploadWorker.status(applicationContext, dir)
                runOnUiThread { if (!isDestroyed) row.set(st) }
                if (st.state == UploadState.VERIFIED || st.state == UploadState.FAILED || st.state == UploadState.NONE) break
                try { Thread.sleep(2000) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    private fun build(dir: File, status: Int, elapsed: Long, kfLive: Int, manifest: Parsed, size: Long, vt: VerdictText, issueList: List<VerdictText.Issue>) {
        val p = UiKit.c(this)
        val verdict = if (status == Status.OK) manifest.verdict else null
        val kf = manifest.keyframes ?: kfLive
        val ok = status == Status.OK
        val ready = ok && verdict != "FAIL"
        val features = Features.get(this)   // ADR 0032: room drawing + parts = guidance, "Found" = detector
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1. Room drawing (full width, 4:3) from the last live snapshot, when guidance ran.
        val checklist: ChecklistView? = if (features.guidance && intent.hasExtra(EXTRA_SURFACES)) {
            val segs = EventParser.parseSurfaces(intent.getStringExtra(EXTRA_SURFACES) ?: "")
            val floor = intent.getBooleanExtra(EXTRA_FLOOR, false)
            val ceiling = intent.getBooleanExtra(EXTRA_CEILING, false)
            val shell = EventParser.parseShell(intent.getStringExtra(EXTRA_SHELL) ?: "")
            val closed = intent.getBooleanExtra(EXTRA_SHELL_CLOSED, false)
            val spans = EventParser.parseSpans(intent.getStringExtra(EXTRA_SPANS) ?: "")
            val objects = EventParser.parseObjects(intent.getStringExtra(EXTRA_OBJECTS) ?: "")
            val openings = EventParser.parseOpenings(intent.getStringExtra(EXTRA_OPENINGS) ?: "")
            if (segs.isNotEmpty() || shell.isNotEmpty()) col.addView(Aspect43(this).apply {
                background = UiKit.surface(context)
                val pad = dp(Tokens.S8)
                setPadding(pad, pad, pad, pad)
                addView(RoomView(this@ReviewActivity).also { it.setSnapshot(segs, floor, ceiling, shell, closed, spans, objects, openings) },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }, lp(Tokens.S24))
            val found = max(intent.getIntExtra(EXTRA_WALLS_FOUND, 0), 0)
            ChecklistView(this).apply { set(RoomProgress.from(floor, ceiling, RoomProgress.pcts(segs.map { it.pct }, found), closed)) }
        } else null

        // 2. Headline, verdict badge and one plain sentence.
        col.addView(UiKit.title(this, when {
            !ok -> "Couldn't save this scan"
            verdict == "FAIL" -> "Scan needs another pass"
            else -> "Room saved"
        }), lp(Tokens.S8))
        col.addView(StatusBadge(this).apply {
            if (!ok) set("Not saved", UiKit.Kind.ERROR) else set(ScanInfo.badgeText(verdict), ScanUi.verdictKind(verdict), hollow = verdict == null)
        }, lp(Tokens.S12))
        col.addView(UiKit.body(this, when {
            !ok -> "Something went wrong while saving. Scan again."
            !ready -> "The check failed. Scan again, moving slowly and covering the whole room."
            verdict != null -> vt.headline(verdict)
            else -> "The scan is stored on this phone."
        }), lp(Tokens.S24))

        // 3. Room parts.
        if (checklist != null) col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.surface(context)
            val pad = dp(Tokens.CARD_PAD)
            setPadding(pad, pad, pad, pad)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "Room parts. ${checklist.description}"
            addView(checklist)
        }, lp(Tokens.S16))

        // 4. Bridge slots: measurements, stage, Share (GONE until wired).
        val summary = RoomSummaryCard(this).also { col.addView(it, lp(Tokens.S16)) }
        val stage = StageRow(this).apply { visibility = View.GONE }.also { col.addView(it, lp(Tokens.S16)) }
        val share = UiKit.secondaryButton(this, "Share").apply {
            visibility = View.GONE
            setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_share)?.mutate()?.apply { setTint(p.label) }, null, null, null)
            compoundDrawablePadding = dp(Tokens.S8)
        }.also { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(Tokens.S16) }) }
        reviewLayout = ReviewLayout(summary, stage, share)
        if (ready && UploadGate.available) {
            stage.set(UploadStatus(UploadState.NONE))
            stage.visibility = View.VISIBLE
            watchUpload(dir, stage)
        }

        // 5. Check notes as a grouped list (errors first, as the verdict text orders them).
        if (ok && verdict != null) {
            val notes = vt.describe(issueList)
            if (notes.isNotEmpty()) {
                col.addView(UiKit.sectionHeader(this, "Notes"))
                col.addView(UiKit.group(this).apply {
                    for (n in notes) {
                        val error = n.severity == "ERROR"
                        addView(UiKit.listRow(this@ReviewActivity, if (error) R.drawable.ic_error else R.drawable.ic_warning, n.title, "${n.text} ${n.action}".trim()).apply {
                            (getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(if (error) p.red else p.orange)
                            contentDescription = "${if (error) "Problem" else "Note"}: ${n.title}. ${n.text} ${n.action}"
                        })
                    }
                }, lp(Tokens.SECTION_GAP))
            }
        }

        // 6. Details, collapsed.
        val details = UiKit.group(this).apply { visibility = View.GONE }
        val durationMs = if (elapsed > 0) elapsed else manifest.durationMs   // live time from a scan, else the bundle's clock span
        if (durationMs != null && durationMs > 0) details.addView(detailRow("Duration", UiKit.mmss(durationMs)))
        details.addView(detailRow("Photos", kf.toString()))
        if (features.detector) {
            val found = ObjectSummary.text(
                EventParser.parseObjects(intent.getStringExtra(EXTRA_OBJECTS) ?: ""),
                EventParser.parseOpenings(intent.getStringExtra(EXTRA_OPENINGS) ?: ""),
            )
            if (found.isNotEmpty()) details.addView(detailRow("Found", found).apply { contentDescription = "Found in the room: $found" })
            else if (intent.getBooleanExtra(EXTRA_OBJECTS_EXPECTED, false)) details.addView(detailRow("Found", "No objects recognised"))
        }
        details.addView(detailRow("Size", UiKit.humanSize(size)))
        details.addView(detailRow("Folder", dir.name))
        details.addView(UiKit.pathText(this, dir.absolutePath).apply { val pad = dp(Tokens.S16); setPadding(pad, dp(Tokens.S12), pad, dp(Tokens.S12)) })
        val toggle = UiKit.listRow(this, null, "Details", null) {}.apply { contentDescription = "Details, collapsed. Double tap to show." }
        toggle.setOnClickListener {
            val open = details.visibility != View.VISIBLE
            details.visibility = if (open) View.VISIBLE else View.GONE
            toggle.contentDescription = if (open) "Details, expanded. Double tap to hide." else "Details, collapsed. Double tap to show."
        }
        col.addView(UiKit.group(this).apply { addView(toggle) }, lp(Tokens.S8))
        col.addView(details, lp(Tokens.S24))

        // Bottom bar: Done when the scan is usable, else Scan again first; Done is always reachable.
        val scanAgain = { startActivity(Intent(this, CaptureActivity::class.java)); finish() }
        val primary = if (ready) UiKit.primaryButton(this, "Done").apply { setOnClickListener { finish() } }
            else UiKit.primaryButton(this, "Scan again").apply { setOnClickListener { scanAgain() } }
        val secondary = if (ready) UiKit.secondaryButton(this, "New scan").apply { setOnClickListener { scanAgain() } }
            else UiKit.secondaryButton(this, "Done").apply { setOnClickListener { finish() } }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(col)
            UiKit.applyInsets(this, Tokens.SCREEN_MARGIN, bottom = false)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(UiKit.bottomActionBar(this@ReviewActivity, primary, secondary), lp(0))
        })
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom) }

    /** Label + value row inside the Details group (value wraps, never clipped). */
    private fun detailRow(label: String, value: String): View = LinearLayout(this).apply {
        val p = UiKit.c(context)
        orientation = LinearLayout.HORIZONTAL
        minimumHeight = dp(Tokens.TOUCH_MIN)
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(dp(Tokens.S16), dp(Tokens.S12), dp(Tokens.S16), dp(Tokens.S12))
        addView(UiKit.text(context, label, Tokens.BODY, p.label2), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(UiKit.text(context, value, Tokens.BODY, p.label).apply { fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** Keeps a 4:3 frame at the full available width. */
    private class Aspect43(ctx: Context) : FrameLayout(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(w * 3 / 4, MeasureSpec.EXACTLY))
        }
    }

    companion object {
        private const val TAG = "CKReview"
        const val EXTRA_DIR = "dir"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_KEYFRAMES = "keyframes"
        /** Last overlay snapshot from the scan: surfaces string ("x0,z0,x1,z1,pct;...") + flags. Absent when no overlay arrived. */
        const val EXTRA_SURFACES = "room_surfaces"
        const val EXTRA_FLOOR = "room_floor"
        const val EXTRA_CEILING = "room_ceiling"
        const val EXTRA_WALLS_FOUND = "room_walls_found"
        /** Layout outline and details of the last overlay (real-room-fix T3): same string formats as the overlay v3 keys. */
        const val EXTRA_SHELL = "room_shell"
        const val EXTRA_SHELL_CLOSED = "room_shell_closed"
        const val EXTRA_SPANS = "room_spans"
        const val EXTRA_OBJECTS = "room_objects"
        const val EXTRA_OPENINGS = "room_openings"
        /** True when the scan lifted detections to objects (detector + understanding on): "No objects recognised" is then meaningful. */
        const val EXTRA_OBJECTS_EXPECTED = "room_objects_expected"

        /** manifest.json: validation.verdict, counts.keyframes and WARN/ERROR messages from validation.issues (INFO hidden). */
        fun parseManifest(text: String): Parsed = try {
            val root = org.json.JSONObject(text)
            val validation = root.optJSONObject("validation")
            val verdict = validation?.optString("verdict", "")?.takeIf { it == "PASS" || it == "WARN" || it == "FAIL" }
            val kf = root.optJSONObject("counts")?.let { if (it.has("keyframes")) it.optInt("keyframes", -1).takeIf { n -> n >= 0 } else null }
            val arr = validation?.optJSONArray("issues")
            val issues = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sev = o.optString("severity", "")
                val msg = o.optString("message", "")
                if ((sev == "WARN" || sev == "ERROR") && msg.isNotEmpty()) issues.add(msg)
            }
            val clock = root.optJSONObject("clock")
            val dur = if (clock != null && clock.has("t_start_ns") && clock.has("t_end_ns"))
                ((clock.optLong("t_end_ns") - clock.optLong("t_start_ns")) / 1_000_000L).takeIf { it > 0 } else null
            Parsed(verdict, kf, issues, dur)
        } catch (t: Throwable) { Parsed(null, null, emptyList()) }   // missing/corrupt manifest: show "not available"
    }
}
