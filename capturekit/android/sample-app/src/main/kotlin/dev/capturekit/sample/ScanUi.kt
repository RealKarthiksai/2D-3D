package dev.capturekit.sample

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import dev.capturekit.upload.UploadState
import dev.capturekit.upload.UploadStatus
import dev.capturekit.verdict.VerdictText
import java.util.Locale
import kotlin.math.roundToInt

/** Wording and status kinds shared by Review and the Home library. Words always accompany the dot. */
object ScanUi {
    /** Where a scan is on its way from this phone to a finished 3D model (upload / processing slots, bridge brief). */
    enum class ScanStage { ON_PHONE, WAITING_NETWORK, UPLOADING, UPLOADED, QUEUED, PROCESSING, DONE, FAILED }

    @Volatile private var cached: VerdictText? = null

    /** Reads assets/verdict_messages.json once (blocking disk read: call off the main thread). Never throws. */
    fun verdictText(ctx: Context): VerdictText = cached ?: synchronized(this) {
        cached ?: (try { ctx.applicationContext.assets.open("verdict_messages.json").use { VerdictText.parse(it.readBytes().decodeToString()) } }
        catch (t: Throwable) { VerdictText.FALLBACK }).also { cached = it }
    }

    fun verdictKind(verdict: String?): UiKit.Kind = when (verdict) {
        "PASS" -> UiKit.Kind.OK
        "WARN" -> UiKit.Kind.WARN
        "FAIL" -> UiKit.Kind.ERROR
        else -> UiKit.Kind.INFO
    }

    fun stageOf(s: UploadStatus): ScanStage = when (s.state) {
        UploadState.NONE -> ScanStage.ON_PHONE
        UploadState.WAITING -> ScanStage.WAITING_NETWORK
        UploadState.UPLOADING, UploadState.RETRYING -> ScanStage.UPLOADING
        UploadState.VERIFIED -> ScanStage.UPLOADED
        UploadState.FAILED -> ScanStage.FAILED
    }

    fun stageText(stage: ScanStage, unmeteredOnly: Boolean = true): String = when (stage) {
        ScanStage.ON_PHONE -> "On this phone"
        ScanStage.WAITING_NETWORK -> if (unmeteredOnly) "Waiting for Wi-Fi" else "Waiting for a network"
        ScanStage.UPLOADING -> "Uploading"
        ScanStage.UPLOADED -> "Uploaded"
        ScanStage.QUEUED -> "Waiting to be processed"
        ScanStage.PROCESSING -> "Building the 3D model"
        ScanStage.DONE -> "3D model ready"
        ScanStage.FAILED -> "Upload failed"
    }

    fun stageKind(stage: ScanStage): UiKit.Kind = when (stage) {
        ScanStage.UPLOADED, ScanStage.DONE -> UiKit.Kind.OK
        ScanStage.FAILED -> UiKit.Kind.ERROR
        else -> UiKit.Kind.INFO
    }
}

/**
 * Upload / processing stage: dot + words, and a drawn bar while uploading with a known fraction. The bridge brief
 * drives QUEUED / PROCESSING / DONE; today only the upload stages are set.
 */
class StageRow(ctx: Context) : LinearLayout(ctx) {
    private val badge = StatusBadge(ctx)
    private val bar = LinearProgress(ctx).apply { visibility = GONE }
    var stage: ScanUi.ScanStage? = null
        private set

    init {
        orientation = VERTICAL
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(badge)
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = (Tokens.S8 * resources.displayMetrics.density).roundToInt()
        })
    }

    /** [fraction] 0..1 shows the bar (uploading only); < 0 hides it. */
    fun set(stage: ScanUi.ScanStage, unmeteredOnly: Boolean = true, fraction: Float = -1f) {
        this.stage = stage
        val t = ScanUi.stageText(stage, unmeteredOnly)
        badge.set(t, ScanUi.stageKind(stage), hollow = stage == ScanUi.ScanStage.ON_PHONE || stage == ScanUi.ScanStage.WAITING_NETWORK)
        val showBar = stage == ScanUi.ScanStage.UPLOADING && fraction >= 0f
        bar.visibility = if (showBar) VISIBLE else GONE
        if (showBar) bar.fraction = fraction
        contentDescription = t
    }

    fun set(s: UploadStatus, unmeteredOnly: Boolean = true) =
        set(ScanUi.stageOf(s), unmeteredOnly, if (s.state == UploadState.UPLOADING && s.percent in 0..100) s.percent / 100f else -1f)
}

/** Room measurements from the reconstruction (bridge brief): floor area, walls, ceiling height. GONE until set. */
class RoomSummaryCard(ctx: Context) : LinearLayout(ctx) {
    private val p = UiKit.c(ctx)
    private val areaStat = stat("Floor area")
    private val wallsStat = stat("Walls")
    private val ceilingStat = stat("Ceiling height")

    init {
        orientation = HORIZONTAL
        val pad = (Tokens.CARD_PAD * resources.displayMetrics.density).roundToInt()
        background = UiKit.surface(ctx)
        setPadding(pad, pad, pad, pad)
        for (s in listOf(areaStat, wallsStat, ceilingStat)) addView(s, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        visibility = GONE
    }

    private fun stat(label: String): LinearLayout = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(UiKit.footnote(context, label, p.label2))
        addView(UiKit.text(context, "", Tokens.TITLE, p.label).apply { fontFeatureSettings = "tnum" })
    }

    private fun show(s: LinearLayout, value: String?) {
        s.visibility = if (value == null) View.GONE else View.VISIBLE
        if (value != null) (s.getChildAt(1) as android.widget.TextView).text = value
    }

    /** "14.2 m²", "4", "2.60 m"; hides each missing value and the whole card when all are null. */
    fun set(floorAreaM2: Double?, walls: Int?, ceilingM: Double?) {
        show(areaStat, floorAreaM2?.let { String.format(Locale.US, "%.1f m²", it) })
        show(wallsStat, walls?.toString())
        show(ceilingStat, ceilingM?.let { String.format(Locale.US, "%.2f m", it) })
        visibility = if (floorAreaM2 == null && walls == null && ceilingM == null) GONE else VISIBLE
        contentDescription = listOfNotNull(
            floorAreaM2?.let { String.format(Locale.US, "Floor area %.1f square metres", it) },
            walls?.let { "$it walls" },
            ceilingM?.let { String.format(Locale.US, "Ceiling height %.2f metres", it) },
        ).joinToString(". ")
    }
}
