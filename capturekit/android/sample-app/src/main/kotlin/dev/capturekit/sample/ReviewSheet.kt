package dev.capturekit.sample

import android.app.Activity
import android.app.Dialog
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.capturekit.capture.CoreEvent
import dev.capturekit.guidance.ObjectSummary
import dev.capturekit.guidance.ScanStateSnapshot
import dev.capturekit.sample.UiKit.dp

/**
 * Review-before-finish sheet. Shown after the core is paused and before finalize: the same parts checklist as the
 * live HUD ([ChecklistView]) plus the weak walls, with "Scan the gaps" (resume the same bundle) and "Finish anyway".
 * Wall names are "Wall N": N = the core's stable wall id (overlay v3, same names as the voice answers) or, for an older
 * core, the 1-based position in the last snapshot's surfaces. Only evidenced walls count (see
 * [ScanStateSnapshot.evidencedWalls]): hypothesised outline sides, plane-less inferred walls and walls entirely hidden
 * behind furniture are never called weak, so the sheet can always clear.
 */
object ReviewSheet {
    /** Walls below this percentage are called weak (guess until Gate 0 data; the scanning band starts at 20). */
    const val WEAK_PCT = 50

    /** Wall numbers (core ids on a v3 core) whose coverage is below [threshold]. */
    fun weakWalls(o: CoreEvent.Overlay?, threshold: Int = WEAK_PCT): List<Int> {
        if (o == null) return emptyList()
        if (!o.layoutV3) return o.surfaces.withIndex().filter { it.value.pct < threshold }.map { it.index + 1 }
        return ScanStateSnapshot.evidencedWalls(o).filter { it.pct < threshold }.map { it.id }
    }

    /** Nothing of the room captured: no snapshot at all, or no floor and no wall seen. */
    fun nothingFound(o: CoreEvent.Overlay?): Boolean = o == null || (!o.floorFound && o.walls == 0 && o.surfaces.isEmpty())

    /**
     * The sheet is skipped (finish straight away) only when something was captured and no wall is weak. With
     * [guidance] off (ADR 0032) the weak-wall part is gated: only the empty-scan notice remains.
     */
    fun needed(o: CoreEvent.Overlay?, guidance: Boolean): Boolean = nothingFound(o) || (guidance && weakWalls(o).isNotEmpty())

    /**
     * [guidance] off: minimal empty-scan notice (title, one line, the two buttons); no checklist or walls.
     * The "Found" line needs [detector] (ADR 0032), as on the Review screen.
     */
    fun show(act: Activity, o: CoreEvent.Overlay?, guidance: Boolean, detector: Boolean, onGaps: () -> Unit, onFinish: () -> Unit): Dialog {
        val weak = if (guidance) weakWalls(o) else emptyList()
        val empty = nothingFound(o)
        val p = UiKit.c(act)
        val checklist = ChecklistView(act).apply {
            set(if (o == null) RoomProgress.from(floor = false, ceiling = false, wallPcts = emptyList(), shellClosed = false) else ChecklistView.progressOf(o))
        }
        val gapsText = if (empty) "Nothing of the room was captured yet." else if (weak.isEmpty()) "All evidenced walls meet coverage targets." else
            "Needs more scanning: " + weak.joinToString(", ") { "Wall $it" } + "."
        val found = if (!detector) "" else o?.let { ObjectSummary.text(it.objects, it.openings) }.orEmpty()   // issue #2 D: what was recognised so far
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        // The scan is paused: the only ways out are the two buttons.
        return UiKit.sheet(act, cancelable = false) { content, dlg ->
            content.addView(UiKit.title(act, "Before you finish"))
            if (guidance) content.addView(checklist, LinearLayout.LayoutParams(match, wrap).apply { topMargin = act.dp(Tokens.S16) })
            content.addView(InlineAlert(act).apply {
                background = UiKit.surface(act, p.surface2)
                set(gapsText, if (empty || weak.isNotEmpty()) UiKit.Kind.WARN else UiKit.Kind.OK)
                contentDescription = if (guidance) "$gapsText ${checklist.description}" else gapsText
            }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = act.dp(Tokens.S16) })
            if (found.isNotEmpty()) content.addView(UiKit.callout(act, "Found: $found."),
                LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = act.dp(Tokens.S12) })
            val primary = UiKit.primaryButton(act, if (empty) "Keep scanning" else "Scan the gaps").apply {
                setOnClickListener { dlg.dismiss(); onGaps() }
            }
            val secondary = UiKit.secondaryButton(act, "Finish anyway").apply {
                setOnClickListener { dlg.dismiss(); onFinish() }
            }
            content.addView(UiKit.sheetActions(act, primary, secondary), LinearLayout.LayoutParams(match, wrap).apply { topMargin = act.dp(Tokens.S24) })
        }
    }
}
