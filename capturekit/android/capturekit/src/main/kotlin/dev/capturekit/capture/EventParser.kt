package dev.capturekit.capture

/** Events the core emits through ck_poll_events (flat JSON lines). Parsed without org.json so it is JVM-testable. */
sealed class CoreEvent {
    data class KeyframeRequest(val frameId: Int, val file: String) : CoreEvent()
    data class KeyframeExpired(val frameId: Int, val file: String) : CoreEvent()
    data class StateChange(val from: String, val to: String, val reason: String) : CoreEvent()
    data class FrameQuality(val frameId: Int, val quality: Double, val hardReject: Boolean, val reasons: List<String>) : CoreEvent()
    data class Governor(val from: String, val to: String, val reason: String) : CoreEvent()
    /** A guidance instruction (Phase 8). Voice/overlay shells render it; params are in the raw line. */
    data class Instruction(
        val id: Int, val type: String, val speakText: String, val longText: String,
        val priority: Int, val haptic: String, val overlayHint: String,
    ) : CoreEvent()
    /** Glanceable "what the app understands" snapshot (~2 Hz, non-critical). Wording (whyText) comes from the core. */
    data class Overlay(
        val completeness: Double, val seen: Double, val tracking: Int, val mode: String,
        val wallFound: Boolean, val floorFound: Boolean, val ceilingFound: Boolean, val walls: Int,
        val userX: Double, val userZ: Double, val userYawDeg: Double,
        val hasTarget: Boolean, val targetX: Double, val targetZ: Double,
        val whyId: String, val whyText: String, val surfaces: List<WallSeg>,
        /** Overlay v2 (ADR 0022): bit i set = a keyframe looked along heading [30i, 30i+30) deg. 0 when absent (old core). */
        val yawMask: Int = 0,
    ) : CoreEvent()
    data class IngestDrop(val counts: Map<String, Long>) : CoreEvent()
    data class Other(val type: String, val raw: String) : CoreEvent()
}

/** One wall segment in world metres (x,z plan view), the percentage of its cells seen, and the stable 1-based wall id (0 = unknown, v1 core). */
data class WallSeg(val x0: Double, val z0: Double, val x1: Double, val z1: Double, val pct: Int, val id: Int = 0)

object EventParser {
    /** "x0,z0,x1,z1,pct[,id];..." (5 fields v1, 6 fields v2) -> segments; malformed entries are skipped. */
    fun parseSurfaces(s: String): List<WallSeg> = s.split(';').mapNotNull { part ->
        val f = part.split(',')
        if (f.size != 5 && f.size != 6) return@mapNotNull null
        val v = f.map { it.trim().toDoubleOrNull() ?: return@mapNotNull null }
        WallSeg(v[0], v[1], v[2], v[3], v[4].toInt(), if (f.size == 6) v[5].toInt().coerceAtLeast(0) else 0)
    }

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
            "instruction" -> CoreEvent.Instruction(
                (num(line, "id") ?: return null).toInt(), str(line, "instruction") ?: "", str(line, "speak_text") ?: "",
                str(line, "long_text") ?: "", (num(line, "priority") ?: 2.0).toInt(), str(line, "haptic") ?: "none",
                str(line, "overlay_hint") ?: "",
            )
            "overlay" -> CoreEvent.Overlay(
                num(line, "completeness") ?: 0.0, num(line, "seen") ?: 0.0, (num(line, "tracking") ?: 0.0).toInt(),
                str(line, "mode") ?: "",
                line.contains("\"wall_found\":true"), line.contains("\"floor_found\":true"), line.contains("\"ceiling_found\":true"),
                (num(line, "walls") ?: 0.0).toInt(),
                num(line, "user_x") ?: 0.0, num(line, "user_z") ?: 0.0, num(line, "user_yaw_deg") ?: 0.0,
                line.contains("\"has_target\":true"), num(line, "target_x") ?: 0.0, num(line, "target_z") ?: 0.0,
                str(line, "why_id") ?: "", str(line, "why_text") ?: "", parseSurfaces(str(line, "surfaces") ?: ""),
                ((num(line, "yaw_mask") ?: 0.0).toInt() and 0xFFF),
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
