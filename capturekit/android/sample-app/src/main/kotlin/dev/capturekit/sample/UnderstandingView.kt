package dev.capturekit.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.capturekit.capture.CoreEvent

/**
 * "What the app understands": a completeness ring with %, three chips (walls found, floor, ceiling) and, under the
 * coaching line, the core's one-line reason. All sentences come from the core (why_text); only fixed labels live
 * here. Two views so the HUD can place them in separate zones: [card] (top-left column, under the chips) and [why]
 * (bottom stack under the coaching line). Tap the card to collapse it to the ring only. Updates are throttled to
 * 4 Hz and must be called on the UI thread; the mini map is a later step.
 */
class UnderstandingView(private val ctx: Context) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private class Ring(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiKit.PANEL }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; color = 0x44FFFFFF }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 15 * d; typeface = Typeface.DEFAULT_BOLD
        }
        private val box = RectF()
        var fraction = 0f
        var color = UiKit.GREEN
        var text = "0%"

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val inset = arc.strokeWidth
            c.drawCircle(w / 2, h / 2, minOf(w, h) / 2, bg)
            box.set(inset, inset, w - inset, h - inset)
            c.drawOval(box, track)
            arc.color = color
            c.drawArc(box, -90f, 360f * fraction.coerceIn(0f, 1f), false, arc)
            c.drawText(text, w / 2, h / 2 - (label.descent() + label.ascent()) / 2, label)
        }
    }

    private val ring = Ring(ctx)
    private val walls = UiKit.chip(ctx).apply { textSize = 13f; setPadding(dp(10), dp(3), dp(10), dp(3)) }
    private val floor = UiKit.chip(ctx).apply { textSize = 13f; setPadding(dp(10), dp(3), dp(10), dp(3)) }
    private val ceiling = UiKit.chip(ctx).apply { textSize = 13f; setPadding(dp(10), dp(3), dp(10), dp(3)) }
    private val chips = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(walls, chipLp()); addView(floor, chipLp()); addView(ceiling, chipLp())
    }

    /** Top-left zone (inside the HUD's left column, below the tracking/quality chips). Hidden until the first snapshot. */
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(0, dp(6), 0, 0)
        addView(ring, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(4) })
        addView(chips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setOnClickListener { collapsed = !collapsed; render() }
    }

    /** The core's reason line, placed under the coaching line. Hidden when empty or collapsed. */
    val why: TextView = UiKit.statusText(ctx, 16f).apply {
        gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE
        setTextColor(0xFFE3F2FD.toInt())
    }

    private var collapsed = false
    private var last: CoreEvent.Overlay? = null
    private var shown: CoreEvent.Overlay? = null
    private var lastRenderMs = 0L

    init {
        // Children are summarised by the card's contentDescription; keep TalkBack from reading them again.
        card.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        chips.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ring.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setMaxWidth(px: Int) { why.maxWidth = px }

    /** Newest snapshot (null = not scanning: hide everything). Renders at most every 250 ms (4 Hz). */
    fun update(o: CoreEvent.Overlay?, nowMs: Long = SystemClock.elapsedRealtime()) {
        if (o == null) {
            last = null; shown = null
            card.visibility = View.GONE; why.visibility = View.GONE
            return
        }
        last = o
        if (o === shown || nowMs - lastRenderMs < 250) return
        lastRenderMs = nowMs
        render()
    }

    private fun render() {
        val o = last ?: return
        shown = o
        val pct = Math.round((o.completeness * 100).coerceIn(0.0, 100.0)).toInt()
        ring.fraction = (o.completeness).toFloat()
        ring.color = if (o.tracking == 1) UiKit.GREEN else UiKit.AMBER
        ring.text = "$pct%"
        ring.invalidate()
        UiKit.setChip(walls, "Walls ${o.walls} found", if (o.walls > 0) UiKit.GREEN else OFF)
        UiKit.setChip(floor, (if (o.floorFound) "✓ " else "○ ") + "Floor", if (o.floorFound) UiKit.GREEN else OFF)
        UiKit.setChip(ceiling, (if (o.ceilingFound) "✓ " else "○ ") + "Ceiling", if (o.ceilingFound) UiKit.GREEN else OFF)
        chips.visibility = if (collapsed) View.GONE else View.VISIBLE
        card.visibility = View.VISIBLE
        card.contentDescription = "Room understanding $pct percent complete. " +
            "${o.walls} walls found. Floor ${if (o.floorFound) "found" else "not found yet"}. " +
            "Ceiling ${if (o.ceilingFound) "found" else "not found yet"}. " +
            if (collapsed) "Details hidden. Tap to show." else "Tap to hide details."
        val t = o.whyText
        if (t.isEmpty() || collapsed) why.visibility = View.GONE
        else { if (why.text.toString() != t) why.text = t; why.visibility = View.VISIBLE }
    }

    private fun chipLp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { bottomMargin = dp(4) }

    private companion object { const val OFF = 0xFF546E7A.toInt() }
}
