package dev.capturekit.sample

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.capturekit.capture.CoreEvent
import dev.capturekit.sample.UiKit.dp
import kotlin.math.max

/**
 * Review-before-finish sheet. Shown after the core is paused and before finalize: the same parts checklist as the
 * live HUD ([ChecklistView]) plus the weak walls, with "Scan the gaps" (resume the same bundle) and "Finish anyway".
 * Wall names are "Wall N", N = 1-based position in the last overlay snapshot's surfaces. RoomView shows no wall
 * numbers (its internal draw order is sorted by depth, not a label), so there is no on-screen base to match yet.
 */
object ReviewSheet {
    /** Walls below this percentage are called weak (guess until Gate 0 data; the scanning band starts at 20). */
    const val WEAK_PCT = 50

    /** 1-based wall numbers whose coverage is below [threshold]. */
    fun weakWalls(o: CoreEvent.Overlay?, threshold: Int = WEAK_PCT): List<Int> =
        o?.surfaces.orEmpty().withIndex().filter { it.value.pct < threshold }.map { it.index + 1 }

    /** The sheet is skipped (finish straight away) when there is no snapshot or no weak wall. */
    fun needed(o: CoreEvent.Overlay?): Boolean = weakWalls(o).isNotEmpty()

    fun show(act: Activity, o: CoreEvent.Overlay, onGaps: () -> Unit, onFinish: () -> Unit): Dialog {
        val weak = weakWalls(o)
        val segs = o.surfaces
        var done = 0; var scanning = 0
        for (s in segs) if (s.pct >= RoomView.DONE_PCT) done++ else if (s.pct >= ChecklistView.SCANNING_PCT) scanning++
        val pct = Math.round((o.completeness * 100).coerceIn(0.0, 100.0)).toInt()
        val checklist = ChecklistView(act).apply { set(o.floorFound, o.ceilingFound, max(o.walls, segs.size), done, scanning, pct) }

        val dlg = Dialog(act)
        fun text(t: String, sp: Float, bold: Boolean = false) = TextView(act).apply {
            this.text = t; textSize = sp; setTextColor(Color.WHITE)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        val gapsText = if (weak.isEmpty()) "Nothing weak found." else
            "Needs more scanning: " + weak.joinToString(", ") { "Wall $it" } + "."
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = UiKit.rounded(0xFF101418.toInt(), act.dp(UiKit.RADIUS_DP).toFloat())
            setPadding(act.dp(20), act.dp(16), act.dp(20), act.dp(16))
            addView(text("Before you finish", UiKit.TEXT_L, true))
            addView(checklist, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(8) })
            addView(text(gapsText, UiKit.TEXT_M).apply { contentDescription = "$gapsText ${checklist.description}" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(8) })
            val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            btns.addView(UiKit.bigButton(act, "Finish anyway", 0xFF455A64.toInt()).apply {
                setOnClickListener { dlg.dismiss(); onFinish() }
            })
            btns.addView(UiKit.bigButton(act, "Scan the gaps").apply {
                setOnClickListener { dlg.dismiss(); onGaps() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = act.dp(12) })
            addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = act.dp(16) })
        }
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dlg.setContentView(root)
        dlg.setCancelable(false)   // the scan is paused: the only ways out are the two buttons
        dlg.show()
        return dlg
    }
}
