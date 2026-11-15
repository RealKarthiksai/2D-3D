package dev.capturekit.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Frame
import dev.capturekit.nativebridge.CaptureKitNative as N
import dev.capturekit.nativebridge.Status
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates one capture session over the C API. No capture logic lives here (it is all in the core):
 * this class owns the handle, feeds ingest calls, answers keyframe requests by encoding JPEGs on a worker,
 * and polls events. Every ingest returns immediately; a FULL status is a counted, logged drop.
 *
 * Threading: onPose/onFrame/... run on the AR (GL) thread, onGyro/onAccel on the sensor thread, JPEG
 * encoding on a single worker, event polling on its own handler thread.
 */
class SessionManager(
    private val context: Context,
    val dir: File,
    private val clockOffsetNs: Long = 0L,
    private val frameEveryN: Int = 3, // ARCore ~30 fps -> core candidates ~10 fps
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onEvent(e: CoreEvent) {}
        fun onPolicy(p: Policy) {}
        fun onState(state: Int) {}
    }

    /** Raw planes copied on the AR thread (fast bulk copy); converted to NV21/JPEG on the worker. */
    private class Snapshot(val w: Int, val h: Int, val y: ByteArray, val yRow: Int, val u: ByteArray, val v: ByteArray, val uvRow: Int, val uvPix: Int)

    private var handle = 0L
    private val ring = ConcurrentHashMap<Int, Snapshot>()
    private val ringOrder = ArrayDeque<Int>()
    private val frameId = AtomicInteger(0)
    private var frameCounter = 0
    private var lumaBuf: ByteBuffer? = null
    private val encoder = Executors.newSingleThreadExecutor { Thread(it, "ck-jpeg") }
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    @Volatile private var polling = false
    val droppedAtEdge = AtomicInteger(0)
    val stats = IngestStats()
    private val db = CaptureDb.get(context).sessions()

    private fun mono() = SystemClock.elapsedRealtimeNanos() + clockOffsetNs

    fun start(extraConfig: Map<String, Any> = emptyMap()): Boolean {
        val cfg = buildMap<String, Any> {
            put("platform", "android"); put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("clock_base", "elapsedRealtimeNanos"); put("clock_offset_ns", clockOffsetNs)
            putAll(extraConfig)
        }
        handle = N.create(dir.absolutePath, toJson(cfg))
        if (handle <= 0) { Log.e(TAG, "create failed: ${-handle}"); handle = 0; return false }
        encoder.execute { db.upsert(SessionRow(dir.absolutePath, System.currentTimeMillis(), "recording")) }
        val st = N.start(handle, mono())
        if (st != Status.OK) return false
        polling = true
        pollThread = HandlerThread("ck-events").also { it.start() }
        pollHandler = Handler(pollThread!!.looper).also { it.post(pollTick) }
        return true
    }

    private val pollTick = object : Runnable {
        override fun run() {
            if (!polling || handle == 0L) return
            drainEvents()
            pollHandler?.postDelayed(this, 50)
        }
    }

    private fun drainEvents() {
        val text = N.pollEvents(handle)
        if (text.isEmpty()) return
        for (e in EventParser.parse(text)) {
            when (e) {
                is CoreEvent.KeyframeRequest -> { stats.keyframes.incrementAndGet(); encoder.execute { encodeKeyframe(e.frameId, e.file) } }
                is CoreEvent.StateChange -> listener?.onState(N.state(handle))
                else -> {}
            }
            listener?.onEvent(e)
        }
        listener?.onPolicy(Policy.from(N.policy(handle)))
    }

    // ---- ingest (never block) ----
    private fun note(st: Int, what: String) {
        if (st == Status.FULL) { val n = droppedAtEdge.incrementAndGet(); if (n % 100 == 1) Log.w(TAG, "core queue full: $what dropped ($n)") }
    }
    fun onPose(t: Long, x: Double, y: Double, z: Double, qw: Double, qx: Double, qy: Double, qz: Double, state: Int, reason: Int) {
        if (handle != 0L) note(N.ingestPose(handle, t, x, y, z, qw, qx, qy, qz, state, reason), "pose")
    }
    fun onIntrinsics(t: Long, w: Int, h: Int, fx: Double, fy: Double, cx: Double, cy: Double) {
        if (handle != 0L) N.ingestIntrinsics(handle, t, w, h, fx, fy, cx, cy)
    }
    fun onGyro(t: Long, x: Double, y: Double, z: Double) { if (handle != 0L) note(N.ingestGyro(handle, t, x, y, z), "gyro") }
    fun onAccel(t: Long, x: Double, y: Double, z: Double) { if (handle != 0L) note(N.ingestAccel(handle, t, x, y, z), "accel") }
    fun onPoints(t: Long, xyz: FloatArray, n: Int) { if (handle != 0L) note(N.ingestPoints(handle, t, xyz, n), "points") }
    fun onPlane(t: Long, id: String, kind: String, d: DoubleArray) { if (handle != 0L) N.ingestPlane(handle, t, id, kind, d) }
    fun onPlatform(t: Long, thermal: Int, lowPower: Boolean, battery: Double, charging: Boolean, frameTimeMs: Double) {
        if (handle != 0L) N.ingestPlatform(handle, t, thermal, lowPower, battery, charging, frameTimeMs)
    }

    /** Called by ARAdapter for every ARCore frame; forwards every Nth as a quality candidate. */
    fun onFrame(frame: Frame, t: Long) {
        if (handle == 0L || ++frameCounter % frameEveryN != 0) return
        stats.frameCalls.incrementAndGet()
        val img = try { frame.acquireCameraImage() } catch (e: Exception) { stats.acquireFailed(e); return }
        img.use {
            val w = it.width; val h = it.height
            val yp = it.planes[0]; val up = it.planes[1]
            val ys = yp.buffer; val ysz = ys.remaining()
            val need = IngestStats.lumaCapacity(ysz, yp.rowStride, h)
            if (need > ysz) stats.lumaShort.incrementAndGet()   // old sizing would have returned CK_ERR_ARG
            var buf = lumaBuf
            if (buf == null || buf.capacity() < need) { buf = ByteBuffer.allocateDirect(need); lumaBuf = buf }
            buf!!.clear(); buf.put(ys.duplicate()); buf.flip()
            val id = frameId.getAndIncrement()
            val st = N.ingestFrame(handle, t, id, 0L, buf, w, h, yp.rowStride)
            stats.ingestStatus(st)
            if (st == Status.OK) stats.framesIngested.incrementAndGet()
            note(st, "frame")
            if (st != Status.OK) return
            // Keep the planes for a possible keyframe request (bounded ring; oldest evicted).
            val ub = up.buffer.duplicate(); val vb = it.planes[2].buffer.duplicate()
            val ua = ByteArray(ub.remaining()).also { a -> ub.get(a) }
            val va = ByteArray(vb.remaining()).also { a -> vb.get(a) }
            val yb = ByteArray(ysz); ys.duplicate().get(yb)
            synchronized(ringOrder) {
                ring[id] = Snapshot(w, h, yb, yp.rowStride, ua, va, up.rowStride, up.pixelStride)
                ringOrder.addLast(id)
                while (ringOrder.size > RING) ring.remove(ringOrder.removeFirst())
            }
        }
    }

    private fun encodeKeyframe(id: Int, rel: String) {
        val snap = synchronized(ringOrder) { ring[id] }
        if (snap == null) { N.keyframeWritten(handle, id, false); Log.w(TAG, "keyframe $id evicted before encoding"); return }
        try {
            val target = File(dir, rel); target.parentFile?.mkdirs()
            val tmp = File(dir, "$rel.tmp")
            FileOutputStream(tmp).use { out ->
                YuvImage(toNv21(snap), ImageFormat.NV21, snap.w, snap.h, null).compressToJpeg(Rect(0, 0, snap.w, snap.h), JPEG_QUALITY, out)
                out.fd.sync()
            }
            val ok = tmp.renameTo(target)
            N.keyframeWritten(handle, id, ok)
        } catch (t: Throwable) {
            Log.e(TAG, "keyframe encode failed", t); N.keyframeWritten(handle, id, false)
        }
    }

    fun pause() = if (handle != 0L) N.pause(handle, mono()) else Status.STATE
    fun resume() = if (handle != 0L) N.resume(handle, mono()) else Status.STATE
    fun state(): Int = if (handle != 0L) N.state(handle) else 0
    fun policy(): Policy? = if (handle != 0L) Policy.from(N.policy(handle)) else null

    /** Stops capture, lets pending keyframes encode, finalizes the bundle. Returns the core status. */
    fun finish(): Int {
        if (handle == 0L) return Status.STATE
        N.stop(handle, mono())
        Thread.sleep(300); drainEvents()   // final keyframe request
        encoder.shutdown(); encoder.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
        polling = false; pollThread?.quitSafely()
        val st = N.finalizeSession(handle)
        db.setStatus(dir.absolutePath, if (st == Status.OK) "complete" else "unrecoverable")
        N.destroy(handle); handle = 0
        return st
    }

    /** Abandons the session without finalizing (simulates a kill; recovery picks it up on next launch). */
    fun abandon() { polling = false; if (handle != 0L) { N.destroy(handle); handle = 0 } }

    companion object {
        private const val TAG = "CKSession"
        private const val RING = 8
        private const val JPEG_QUALITY = 92

        fun toJson(m: Map<String, Any>) = m.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":" + if (v is Number || v is Boolean) v.toString() else "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

        /** App start: every session still marked "recording" was interrupted; recover it in the core. */
        fun recoverInterrupted(context: Context): List<Pair<String, String>> {
            val dao = CaptureDb.get(context).sessions()
            return dao.interrupted().map { row ->
                val rep = N.recover(row.dir)
                dao.setStatus(row.dir, if (rep.contains("\"ok\":true")) "recovered" else "unrecoverable")
                row.dir to rep
            }
        }

        private fun toNv21(s: Snapshot): ByteArray {
            val out = ByteArray(s.w * s.h * 3 / 2)
            for (r in 0 until s.h) System.arraycopy(s.y, r * s.yRow, out, r * s.w, s.w)
            var o = s.w * s.h
            val cw = s.w / 2; val ch = s.h / 2
            for (r in 0 until ch) for (c in 0 until cw) {
                val i = r * s.uvRow + c * s.uvPix
                out[o++] = s.v[i]  // NV21 = Y plane, then interleaved V,U
                out[o++] = s.u[i]
            }
            return out
        }
    }
}
