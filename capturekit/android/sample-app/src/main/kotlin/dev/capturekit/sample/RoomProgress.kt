package dev.capturekit.sample

/**
 * Room progress as parts, never a percentage: floor, one segment per found wall, ceiling. While the room outline is
 * not closed a trailing ghost segment says "more walls may come", and the walls line never claims a total
 * ("3 walls done"); once closed it reads "3 of 4 walls done". Pure Kotlin (JVM-tested).
 */
data class RoomProgress(
    val floor: Boolean,
    val ceiling: Boolean,
    val walls: List<Seg>,
    val ghost: Boolean,
    val shellClosed: Boolean,
) {
    enum class Seg { DONE, SCANNING, FOUND }

    val done: Int get() = walls.count { it == Seg.DONE }

    val wallsText: String
        get() = when {
            walls.isEmpty() -> "No walls yet"
            shellClosed -> "$done of ${walls.size} walls done"
            else -> if (done == 1) "1 wall done" else "$done walls done"
        }

    /** Plain-language summary for TalkBack. */
    val description: String
        get() = "Floor ${if (floor) "found" else "not found yet"}. $wallsText. Ceiling ${if (ceiling) "found" else "not found yet"}."

    companion object {
        /**
         * [wallPcts] = seen percentage of each found wall (a found wall without coverage data counts as 0).
         * DONE from [RoomView.DONE_PCT], SCANNING from [ChecklistView.SCANNING_PCT], else FOUND.
         */
        fun from(floor: Boolean, ceiling: Boolean, wallPcts: List<Int>, shellClosed: Boolean): RoomProgress =
            RoomProgress(floor, ceiling, wallPcts.map {
                when {
                    it >= RoomView.DONE_PCT -> Seg.DONE
                    it >= ChecklistView.SCANNING_PCT -> Seg.SCANNING
                    else -> Seg.FOUND
                }
            }, ghost = !shellClosed, shellClosed = shellClosed)

        /** Coverage of the evidenced walls, padded with 0 up to [found] walls (a found wall not yet measured). */
        fun pcts(evidenced: List<Int>, found: Int): List<Int> =
            if (found <= evidenced.size) evidenced else evidenced + List(found - evidenced.size) { 0 }
    }
}
