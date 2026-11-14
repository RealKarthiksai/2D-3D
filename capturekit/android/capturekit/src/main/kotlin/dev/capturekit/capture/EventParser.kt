package dev.capturekit.capture

/** Events the core emits through ck_poll_events (flat JSON lines). Parsed without org.json so it is JVM-testable. */
sealed class CoreEvent {
    data class KeyframeRequest(val frameId: Int, val file: String) : CoreEvent()
    data class KeyframeExpired(val frameId: Int, val file: String) : CoreEvent()
    data class StateChange(val from: String, val to: String, val reason: String) : CoreEvent()
    data class FrameQuality(val frameId: Int, val quality: Double, val hardReject: Boolean, val reasons: List<String>) : CoreEvent()
    data class Governor(val from: String, val to: String, val reason: String) : CoreEvent()
    data class IngestDrop(val counts: Map<String, Long>) : CoreEvent()
    data class Other(val type: String, val raw: String) : CoreEvent()
}

object EventParser {
    private fun str(line: String, key: String): String? =
        Regex("\"$key\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)

    private fun num(line: String, key: String): Double? =
        Regex("\"$key\":(-?[0-9.eE+-]+)").find(line)?.groupValues?.get(1)?.toDoubleOrNull()

    fun parseLine(line: String): CoreEvent? {
        val type = str(line, "type") ?: return null
        return when (type) {
            "keyframe_request" -> CoreEvent.KeyframeRequest((num(line, "frame_id") ?: return null).toInt(), str(line, "file") ?: return null)
            "keyframe_expired" -> CoreEvent.KeyframeExpired((num(line, "frame_id") ?: return null).toInt(), str(line, "file") ?: "")
            "state" -> CoreEvent.StateChange(str(line, "from") ?: "", str(line, "to") ?: "", str(line, "reason") ?: "")
            "governor" -> CoreEvent.Governor(str(line, "from") ?: "", str(line, "to") ?: "", str(line, "reason") ?: "")
            "frame_quality" -> CoreEvent.FrameQuality(
                (num(line, "frame_id") ?: return null).toInt(),
                num(line, "quality") ?: 0.0,
                line.contains("\"hard_reject\":true"),
                Regex("\"reasons\":\\[([^\\]]*)]").find(line)?.groupValues?.get(1)
                    ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() } ?: emptyList(),
            )
            "ingest_drop" -> CoreEvent.IngestDrop(
                listOf("pose", "gyro", "accel", "intrinsics", "frame", "points", "plane", "platform")
                    .mapNotNull { k -> num(line, k)?.let { k to it.toLong() } }.toMap(),
            )
            else -> CoreEvent.Other(type, line)
        }
    }

    fun parse(text: String): List<CoreEvent> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull(::parseLine).toList()
}

/** Decoded ck_policy (CaptureKitNative.policy). */
data class Policy(
    val level: Int, val keyframeRateScale: Double, val mlRateScale: Double,
    val resolutionTier: Int, val overlay: Boolean, val upload: Boolean, val mustPause: Boolean,
) {
    companion object {
        val LEVELS = listOf("NOMINAL", "REDUCE_KEYFRAMES", "REDUCE_ML", "LOWER_RESOLUTION", "WARN", "SAFE_PAUSE")
        fun from(a: DoubleArray) = Policy(a[0].toInt(), a[1], a[2], a[3].toInt(), a[4] != 0.0, a[5] != 0.0, a[6] != 0.0)
    }
    val levelName: String get() = LEVELS.getOrElse(level) { "?" }
}
