package dev.capturekit.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import dev.capturekit.capture.EventParser
import kotlin.math.max
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.capturekit.nativebridge.Status
import java.io.File

/**
 * Review screen after Finish: save status, the validator verdict the core wrote into manifest.json
 * (PASS / WARN / FAIL), keyframes, size, duration and the bundle path. Upload is not offered: it needs the
 * INTERNET permission and the user's consent, neither of which this build has.
 */
class ReviewActivity : Activity() {
    data class Parsed(val verdict: String?, val keyframes: Int?, val issues: List<String>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(intent.getStringExtra(EXTRA_DIR) ?: "")
        val status = intent.getIntExtra(EXTRA_STATUS, Status.STATE)
        val elapsed = intent.getLongExtra(EXTRA_ELAPSED_MS, 0L)
        val kfLive = intent.getIntExtra(EXTRA_KEYFRAMES, 0)
        setContentView(TextView(this).apply {
            text = "Checking the scan..."; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        })
        // Disk reads (manifest, bundle size) stay off the UI thread.
        Thread {
            val parsed = parseManifest(try { File(dir, "manifest.json").readText() } catch (t: Throwable) { "" })
            val size = try { UiKit.dirSizeBytes(dir) } catch (t: Throwable) { 0L }
            runOnUiThread { if (!isDestroyed) build(dir, status, elapsed, kfLive, parsed, size) }
        }.start()
    }

    private fun build(dir: File, status: Int, elapsed: Long, kfLive: Int, manifest: Parsed, size: Long) {
        val verdict = if (status == Status.OK) manifest.verdict else null
        val kf = manifest.keyframes ?: kfLive
        val ok = status == Status.OK
        val ready = ok && verdict != "FAIL"

        // Two columns so the whole card, including the buttons, fits a landscape screen.
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UiKit.rounded(0xFF1B222A.toInt(), dp(16).toFloat())
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(24) })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        // Final room: the last live snapshot (walls inked, floor/ceiling) and the parts checklist, from Intent extras.
        if (intent.hasExtra(EXTRA_SURFACES)) {
            val segs = EventParser.parseSurfaces(intent.getStringExtra(EXTRA_SURFACES) ?: "")
            val floor = intent.getBooleanExtra(EXTRA_FLOOR, false)
            val ceiling = intent.getBooleanExtra(EXTRA_CEILING, false)
            var done = 0; var scanning = 0
            for (w in segs) if (w.pct >= RoomView.DONE_PCT) done++ else if (w.pct >= ChecklistView.SCANNING_PCT) scanning++
            val checklist = ChecklistView(this).apply {
                set(floor, ceiling, max(intent.getIntExtra(EXTRA_WALLS_FOUND, 0), segs.size), done, scanning, -1)
            }
            val mid = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription = "Room parts. ${checklist.description}"
                background = UiKit.glass(this@ReviewActivity)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                if (segs.isNotEmpty()) addView(RoomView(this@ReviewActivity).also { it.setSnapshot(segs, floor, ceiling) }, LinearLayout.LayoutParams(dp(170), dp(130)))
                addView(checklist, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            }
            card.addView(mid, 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(24); gravity = Gravity.CENTER_VERTICAL })
        }
        left.addView(TextView(this).apply {
            text = if (ok) "Scan saved" else "Save failed"
            textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }, lp(8))
        val (vText, vColor) = when {
            !ok -> "Not saved (status ${UiKit.statusName(status)})" to UiKit.RED
            verdict == "PASS" -> "Check passed" to UiKit.GREEN
            verdict == "WARN" -> "Check: usable, with warnings" to UiKit.AMBER
            verdict == "FAIL" -> "Check failed" to UiKit.RED
            else -> "Check: not available" to 0xFF546E7A.toInt()
        }
        left.addView(UiKit.chip(this).also { UiKit.setChip(it, vText, vColor) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        left.addView(row("Duration", UiKit.mmss(elapsed)), lp(2))
        left.addView(row("Keyframes", kf.toString()), lp(2))
        left.addView(row("Size", UiKit.humanSize(size)), lp(2))
        left.addView(row("Folder", dir.name), lp(2))
        left.addView(UiKit.pathText(this, dir.absolutePath, 2), lp(12))
        val issues = manifest.issues
        if (issues.isNotEmpty()) right.addView(TextView(this).apply {
            text = issues.joinToString("\n") { "- $it" }
            textSize = 15f; setTextColor(0xFFFFCC80.toInt()); maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, lp(12))
        right.addView(TextView(this).apply {
            text = when {
                ready -> "Ready for upload. Upload is turned off in this build: it needs network access and your consent. The bundle stays on this phone."
                ok -> "Not ready: the check failed. Scan again, moving slowly and covering the whole room."
                else -> "Something went wrong while saving. Scan again."
            }
            textSize = 16f; setTextColor(Color.WHITE)
        }, lp(16))

        val newScan = UiKit.bigButton(this, "New scan").apply {
            setOnClickListener { startActivity(Intent(this@ReviewActivity, CaptureActivity::class.java)); finish() }
        }
        val home = UiKit.secondaryButton(this, "Home").apply { setOnClickListener { finish() } }
        right.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(home, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
            addView(newScan, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            UiKit.applyInsets(this, 16)
        }
        setContentView(scroll)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom) }

    private fun row(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(TextView(context).apply { text = label; textSize = 16f; setTextColor(0xFFB0BEC5.toInt()) }, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(context).apply {
            text = value; textSize = 16f; setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) UiKit.immersive(this)
    }

    companion object {
        const val EXTRA_DIR = "dir"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ELAPSED_MS = "elapsed_ms"
        const val EXTRA_KEYFRAMES = "keyframes"
        /** Last overlay snapshot from the scan: surfaces string ("x0,z0,x1,z1,pct;...") + flags. Absent when no overlay arrived. */
        const val EXTRA_SURFACES = "room_surfaces"
        const val EXTRA_FLOOR = "room_floor"
        const val EXTRA_CEILING = "room_ceiling"
        const val EXTRA_WALLS_FOUND = "room_walls_found"

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
            Parsed(verdict, kf, issues)
        } catch (t: Throwable) { Parsed(null, null, emptyList()) }   // missing/corrupt manifest: show "not available"
    }
}
