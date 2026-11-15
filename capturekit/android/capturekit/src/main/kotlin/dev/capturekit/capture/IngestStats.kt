package dev.capturekit.capture

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cheap diagnostic counters for the shell -> core feed (AtomicLong only; safe on the AR thread).
 * Pure JVM: formatting and the luma buffer sizing helper are unit-tested.
 */
class IngestStats {
    val arFrames = AtomicLong()          // ARCore frames seen by ARAdapter
    val trackingPoses = AtomicLong()     // poses with TrackingState.TRACKING
    val otherPoses = AtomicLong()        // PAUSED / STOPPED
    val frameCalls = AtomicLong()        // candidate frames reaching acquireCameraImage
    val framesIngested = AtomicLong()    // ingestFrame returned OK
    val lumaShort = AtomicLong()         // Y plane smaller than stride*height (old sizing bug)
    val onFrameThrows = AtomicLong()   // session.onFrame threw (caught in ARAdapter)
    val pointsIngested = AtomicLong()
    val planesIngested = AtomicLong()
    val keyframes = AtomicLong()         // keyframe requests from the core
    @Volatile var lastReason = "NONE"
    @Volatile var lastError = ""
    private val acquireFail = ConcurrentHashMap<String, AtomicLong>()
    private val status = ConcurrentHashMap<Int, AtomicLong>()

    fun acquireFailed(e: Throwable) { acquireFail.getOrPut(e.javaClass.simpleName) { AtomicLong() }.incrementAndGet(); lastError = "${e.javaClass.simpleName}: ${e.message}" }
    private var errorsAtLastInterval = 0L

    /** Call once per log interval, after logLine(): clears lastError if no new error happened since the last call. */
    fun endInterval() {
        val total = onFrameThrows.get() + acquireFail.values.sumOf { it.get() }
        if (total == errorsAtLastInterval) lastError = ""
        errorsAtLastInterval = total
    }

    fun ingestStatus(code: Int) { status.getOrPut(code) { AtomicLong() }.incrementAndGet() }
    fun pose(tracking: Boolean, reason: String) { if (tracking) trackingPoses.incrementAndGet() else otherPoses.incrementAndGet(); lastReason = reason }

    /** "trk 612/640 frm 190 kf 14 pts 33 pl 4" */
    fun hudLine(): String =
        "trk ${trackingPoses.get()}/${arFrames.get()} frm ${framesIngested.get()} kf ${keyframes.get()} pts ${pointsIngested.get()} pl ${planesIngested.get()}"

    fun logLine(): String {
        val af = acquireFail.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifEmpty { "-" }
        val st = status.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifEmpty { "-" }
        return "${hudLine()} | other ${otherPoses.get()} reason $lastReason | frameCalls ${frameCalls.get()} acqFail $af ingestSt $st throws ${onFrameThrows.get()} lumaShort ${lumaShort.get()}" +
            if (lastError.isEmpty()) "" else " lastErr $lastError"
    }

    companion object {
        /** The Y plane's buffer can be stride*(h-1)+w; the core wants stride*h bytes. */
        fun lumaCapacity(remaining: Int, stride: Int, height: Int) = maxOf(remaining, stride * height)
    }
}
