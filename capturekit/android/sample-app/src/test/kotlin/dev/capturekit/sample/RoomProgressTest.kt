package dev.capturekit.sample

import dev.capturekit.sample.RoomProgress.Seg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomProgressTest {
    @Test fun openOutlineNeverClaimsATotal() {
        val p = RoomProgress.from(floor = true, ceiling = false, wallPcts = listOf(95, 80, 30, 5), shellClosed = false)
        assertEquals(listOf(Seg.DONE, Seg.DONE, Seg.SCANNING, Seg.FOUND), p.walls)
        assertTrue(p.ghost)
        assertEquals("2 walls done", p.wallsText)
        assertFalse(p.description.contains("%"))
    }

    @Test fun closedOutlineCountsAgainstTheWalls() {
        val p = RoomProgress.from(floor = true, ceiling = true, wallPcts = listOf(90, 85, 81, 79), shellClosed = true)
        assertFalse(p.ghost)
        assertEquals(3, p.done)
        assertEquals("3 of 4 walls done", p.wallsText)
        assertEquals("Floor found. 3 of 4 walls done. Ceiling found.", p.description)
    }

    @Test fun thresholdsMatchTheLiveViews() {
        val p = RoomProgress.from(false, false, listOf(RoomView.DONE_PCT, RoomView.DONE_PCT - 1, ChecklistView.SCANNING_PCT, ChecklistView.SCANNING_PCT - 1), false)
        assertEquals(listOf(Seg.DONE, Seg.SCANNING, Seg.SCANNING, Seg.FOUND), p.walls)
    }

    @Test fun noWallsAndSingular() {
        assertEquals("No walls yet", RoomProgress.from(false, false, emptyList(), false).wallsText)
        assertEquals("1 wall done", RoomProgress.from(false, false, listOf(100), false).wallsText)
    }

    @Test fun foundWallsWithoutCoverageArePadded() {
        assertEquals(listOf(90, 0, 0), RoomProgress.pcts(listOf(90), 3))
        assertEquals(listOf(90, 40), RoomProgress.pcts(listOf(90, 40), 1))
    }
}
