package dev.capturekit.sample

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.capturekit.consent.ConsentStore
import dev.capturekit.sample.upload.UploadGate
import dev.capturekit.upload.UploadSettings
import dev.capturekit.upload.UploadState
import dev.capturekit.upload.UploadWorker

/**
 * Settings: voice guidance, Wi-Fi only uploads (field flavour), privacy (consent summary, withdraw in a sheet),
 * developer tools (debuggable builds) and the version.
 */
class SettingsActivity : Activity() {
    private val consent by lazy { ConsentStore.get(this) }
    private var consentSummary: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(UiKit.iconButton(this, R.drawable.ic_arrow_back, "Back").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(dp(Tokens.TOUCH_MIN), dp(Tokens.TOUCH_MIN)).apply { bottomMargin = dp(Tokens.S12) })
        root.addView(UiKit.largeTitle(this, "Settings"), lp(Tokens.S24))

        root.addView(UiKit.sectionHeader(this, "Scanning"))
        val voice = UiKit.toggle(this, SpeechRenderer.savedEnabled(this)).apply {
            isClickable = false; isFocusable = false
            setOnCheckedChangeListener { _, on -> SpeechRenderer.saveEnabled(this@SettingsActivity, on) }
        }
        root.addView(UiKit.group(this).apply {
            addView(UiKit.listRow(this@SettingsActivity, R.drawable.ic_voice, "Voice guidance", "Spoken coaching while you scan", trailing = voice, chevron = false) { voice.toggle() })
        }, lp(Tokens.SECTION_GAP))

        if (UploadGate.available) {
            root.addView(UiKit.sectionHeader(this, "Uploads"))
            val wifi = UiKit.toggle(this, UploadSettings.unmeteredOnly(this)).apply {
                isClickable = false; isFocusable = false
                setOnCheckedChangeListener { _, on -> setWifiOnly(on) }
            }
            root.addView(UiKit.group(this).apply {
                addView(UiKit.listRow(this@SettingsActivity, R.drawable.ic_cloud_upload, "Upload on Wi-Fi only",
                    "Scans are large. Off means mobile data may be used.", trailing = wifi, chevron = false) { wifi.toggle() })
            }, lp(Tokens.SECTION_GAP))
        }

        root.addView(UiKit.sectionHeader(this, "Privacy"))
        root.addView(UiKit.group(this).apply {
            addView(UiKit.listRow(this@SettingsActivity, R.drawable.ic_lock, "Stored on this phone", "").also { row ->
                consentSummary = ((row.getChildAt(1) as LinearLayout).getChildAt(1) as TextView)
            })
            addView(UiKit.listRow(this@SettingsActivity, null, "Withdraw consent", titleColor = UiKit.c(this@SettingsActivity).danger, chevron = false) { confirmRevoke() })
        }, lp(Tokens.SECTION_GAP))

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            root.addView(UiKit.sectionHeader(this, "Developer"))
            root.addView(UiKit.group(this).apply {
                addView(UiKit.listRow(this@SettingsActivity, null, "Sensor recorder", "Raw camera and IMU capture") {
                    startActivity(Intent(this@SettingsActivity, RecorderActivity::class.java))
                })
                addView(UiKit.listRow(this@SettingsActivity, null, "Clock check", "Camera and sensor timestamp offsets") {
                    startActivity(Intent(this@SettingsActivity, ClockCheckActivity::class.java))
                })
                addView(UiKit.listRow(this@SettingsActivity, null, "Design gallery", "Every component in light and dark") {
                    startActivity(Intent(this@SettingsActivity, DesignGalleryActivity::class.java))
                })
            }, lp(Tokens.SECTION_GAP))
        }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (t: Throwable) { Log.w(TAG, "version unavailable", t); null }
        root.addView(UiKit.footnote(this, "Version ${version ?: "unknown"}").apply { gravity = Gravity.CENTER }, lp(Tokens.S8))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
            UiKit.applyInsets(this, Tokens.SCREEN_MARGIN)
        }
        setContentView(UiKit.page(this, scroll))
    }

    override fun onResume() {
        super.onResume()
        paintConsent()
    }

    private fun paintConsent() {
        Telemetry.sync(this)
        val c = consent.current()
        consentSummary?.text = if (c == null) "Consent not given yet. It is needed before scanning."
            else "Upload ${if (c.upload) "on" else "off"} · Anonymous telemetry ${if (c.telemetry) "on" else "off"}"
    }

    private fun confirmRevoke() {
        UiKit.sheet(this) { content, dlg ->
            val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
            val match = ViewGroup.LayoutParams.MATCH_PARENT
            content.addView(UiKit.title(this, "Withdraw consent?"))
            content.addView(UiKit.body(this, "Scanning will be blocked until you accept again. Uploading and anonymous telemetry stop, and recorded choices are removed from this phone. Your saved scans stay on the phone."),
                LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(Tokens.S8) })
            val withdraw = UiKit.primaryButton(this, "Withdraw").apply {
                setOnClickListener {
                    consent.revoke()
                    UploadWorker.cancelAll(this@SettingsActivity)
                    paintConsent()
                    dlg.dismiss()
                }
            }
            val cancel = UiKit.secondaryButton(this, "Cancel").apply { setOnClickListener { dlg.dismiss() } }
            content.addView(UiKit.sheetActions(this, withdraw, cancel), LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(Tokens.S24) })
        }
    }

    /** Queued uploads keep the old network rule, so the unfinished ones are re-queued with the new one (worker thread). */
    private fun setWifiOnly(on: Boolean) {
        Thread {
            UploadSettings.setUnmeteredOnly(applicationContext, on)
            for (d in ScanLibrary.bundleDirs(this)) {
                val st = UploadWorker.status(applicationContext, d).state
                if (st != UploadState.WAITING && st != UploadState.RETRYING && st != UploadState.UPLOADING) continue
                try { UploadGate.enqueueIfAllowed(applicationContext, d, ScanLibrary.manifestOf(d).verdict, replace = true) }
                catch (t: Throwable) { Log.w(TAG, "re-queue failed: ${d.name}", t) }
            }
        }.start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(bottom) }

    private companion object { const val TAG = "CKSettings" }
}
