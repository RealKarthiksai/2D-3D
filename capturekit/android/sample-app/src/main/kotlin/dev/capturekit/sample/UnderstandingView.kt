package dev.capturekit.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.capturekit.capture.CoreEvent
import dev.capturekit.guidance.ScanStateSnapshot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Room parts as segments: a Floor line, one segment per found wall (done / scanning / just found, plus a ghost segment
 * while the outline is open) with "3 walls done" under it, and a Ceiling line. Never a percentage. Thresholds are
 * guesses until Gate 0 data. The rows are summarised by [description] for TalkBack.
 */
class ChecklistView(ctx: Context) : LinearLayout(ctx) {
    private val p = UiKit.c(ctx)
    private val floorDot = DotView(ctx)
    private val ceilingDot = DotView(ctx)
    private val floorText = UiKit.text(ctx, "Floor", Tokens.CALLOUT, p.label)
    private val ceilingText = UiKit.text(ctx, "Ceiling (optional)", Tokens.CALLOUT, p.label)
    private val bar = SegmentBar(ctx, p)
    private val wallsText = UiKit.text(ctx, "", Tokens.FOOTNOTE, p.label2)

    /** Plain-language summary for TalkBack; parts only, never a percentage. */
    var description = ""
        private set

    init {
        orientation = VERTICAL
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).roundToInt()
        addView(dotRow(floorDot, floorText))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, px(8)).apply { topMargin = px(Tokens.S12) })
        addView(wallsText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(Tokens.S4) })
        addView(dotRow(ceilingDot, ceilingText), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = px(Tokens.S8) })
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        set(RoomProgress.from(floor = false, ceiling = false, wallPcts = emptyList(), shellClosed = false))
    }

    private fun dotRow(dot: DotView, label: TextView) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        addView(dot, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = (Tokens.S8 * resources.displayMetrics.density).roundToInt() })
        addView(label)
    }

    fun set(progress: RoomProgress) {
        floorDot.set(if (progress.floor) p.green else p.label3, hollow = !progress.floor)
        ceilingDot.set(if (progress.ceiling) p.green else p.label3, hollow = !progress.ceiling)
        bar.set(progress)
        if (wallsText.text.toString() != progress.wallsText) wallsText.text = progress.wallsText
        description = progress.description
    }

    companion object {
        const val SCANNING_PCT = 20           // scanning band 20-79, done >= RoomView.DONE_PCT (guesses)

        /** Layout wall count: `walls` from a v3 core (evidenced layout walls), else the older max(plane count, surfaces). */
        fun foundWalls(o: CoreEvent.Overlay): Int = if (o.layoutV3) o.walls else max(o.walls, o.surfaces.size)

        /** Parts progress of a live snapshot (the same counting as the HUD, the finish sheet and Review). */
        fun progressOf(o: CoreEvent.Overlay): RoomProgress = RoomProgress.from(
            o.floorFound, o.ceilingFound || o.ceilingSeen,
            RoomProgress.pcts(ScanStateSnapshot.evidencedWalls(o).map { it.pct }, foundWalls(o)), o.shellClosed)
    }

    /** One rounded segment per wall; DONE green, SCANNING label ink, FOUND faint, ghost = dashed outline. */
    private class SegmentBar(ctx: Context, private val p: Tokens.Palette) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = p.label3
            pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f)
        }
        private val rect = RectF()
        private var segs: List<RoomProgress.Seg> = emptyList()
        private var ghost = true

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun set(progress: RoomProgress) {
            if (progress.walls == segs && progress.ghost == ghost) return
            segs = progress.walls; ghost = progress.ghost
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            val n = segs.size + if (ghost) 1 else 0
            if (n == 0) return
            val gap = 3f * d
            val w = (width - gap * (n - 1)) / n
            val r = height / 2f
            for (i in 0 until n) {
                val x = i * (w + gap)
                rect.set(x, 0f, x + w, height.toFloat())
                if (i < segs.size) {
                    fill.color = when (segs[i]) {
                        RoomProgress.Seg.DONE -> p.green
                        RoomProgress.Seg.SCANNING -> p.label
                        RoomProgress.Seg.FOUND -> Tokens.withAlpha(p.label, 0x40)
                    }
                    c.drawRoundRect(rect, r, r, fill)
                } else {
                    val h = ghostPaint.strokeWidth / 2f
                    rect.inset(h, h)
                    c.drawRoundRect(rect, r, r, ghostPaint)
                }
            }
        }
    }
}

/**
 * "What the app understands" card for the capture HUD's left zone: the room-parts segments and a small caption with
 * the core's reason line (why_text, else the core's long_text). All sentences come from the core; only fixed labels
 * live here. Tap the card to hide the caption. Updates are throttled to 4 Hz and must be called on the UI thread.
 * Feedback: a haptic tick when another wall reaches the done threshold; a short green edge when the core reports mode
 * "finish" (the core speaks "Scan complete" itself).
 */
class UnderstandingView(private val ctx: Context) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()
    private val p = UiKit.c(ctx)

    private val checklist = ChecklistView(ctx)
    private val caption = UiKit.text(ctx, "", Tokens.FOOTNOTE, p.label2).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE
    }
    private val glassBg = UiKit.glass(ctx, Tokens.GLASS_REGULAR, Tokens.R_SHEET)
    private val doneBg = UiKit.rounded(Tokens.glass(Tokens.GLASS_REGULAR), dp(Tokens.R_SHEET).toFloat(), p.green, dp(2))
    private val unglow = Runnable { card.background = glassBg }

    /** Left zone card. Hidden until the first snapshot. */
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        background = glassBg
        setPadding(dp(Tokens.CARD_PAD), dp(Tokens.S12), dp(Tokens.CARD_PAD), dp(Tokens.S12))
        addView(checklist, LinearLayout.LayoutParams(dp(CARD_W_DP), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(caption, LinearLayout.LayoutParams(dp(CARD_W_DP), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(Tokens.S8) })
        setOnClickListener { collapsed = !collapsed; render() }
    }

    private var collapsed = false
    private var last: CoreEvent.Overlay? = null
    private var shown: CoreEvent.Overlay? = null
    private var lastRenderMs = 0L
    private var longText = ""
    private var finishSeen = false
    private var shownProgress: RoomProgress? = null
    private var maxDone = -1
    private var shownCollapsed = false
    private var shownFoundText = ""

    init {
        // Children are summarised by the card's contentDescription; keep TalkBack from reading them again.
        caption.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The core's long_text for the current instruction; shown as the caption when the core gives no why_text. */
    fun setLongText(t: String) {
        if (t == longText) return
        longText = t
        if (last != null) render()
    }

    /** Newest snapshot (null = not scanning: hide everything). Renders at most every 250 ms (4 Hz). */
    fun update(o: CoreEvent.Overlay?, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (o == null) {
            last = null; shown = null; shownProgress = null; maxDone = -1; finishSeen = false
            card.removeCallbacks(unglow); card.background = glassBg
            card.visibility = View.GONE
            return
        }
        last = o
        if (o.mode != "finish") finishSeen = false
        else if (!finishSeen) {
            // Edge only: the core's Finish instruction already plays the "success" haptic.
            finishSeen = true
            card.background = doneBg
            card.removeCallbacks(unglow); card.postDelayed(unglow, DONE_EDGE_MS)
        }
        if (o === shown || nowMs - lastRenderMs < 250) return
        lastRenderMs = nowMs
        render()
    }

    private fun render() {
        val o = last ?: return
        shown = o
        val progress = ChecklistView.progressOf(o)
        // Text is rebuilt only when what it says changes (not on every 4 Hz render).
        val foundText = dev.capturekit.guidance.ObjectSummary.text(o.objects, o.openings)
        val changed = progress != shownProgress || foundText != shownFoundText
        if (progress != shownProgress) {
            checklist.set(progress)
            // Haptic tick only when the done count reaches a new maximum (not on the first snapshot, not on flicker).
            val done = progress.done
            if (maxDone >= 0 && done > maxDone) UiKit.haptic(card, false)
            if (done > maxDone) maxDone = done
        }
        if (changed || collapsed != shownCollapsed) {
            card.contentDescription = "Room parts. ${checklist.description} " +
                (if (foundText.isEmpty()) "" else "Found: $foundText. ") +
                if (collapsed) "Details hidden. Tap to show." else "Tap to hide details."
        }
        card.visibility = View.VISIBLE
        shownProgress = progress; shownCollapsed = collapsed; shownFoundText = foundText
        val t = o.whyText.ifEmpty { longText }
        if (t.isEmpty() || collapsed) caption.visibility = View.GONE
        else { if (caption.text.toString() != t) caption.text = t; caption.visibility = View.VISIBLE }
    }

    private companion object {
        const val CARD_W_DP = 168
        const val DONE_EDGE_MS = 1200L
    }
}
