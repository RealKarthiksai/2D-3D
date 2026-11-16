package dev.capturekit.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.capturekit.capture.CoreEvent
import dev.capturekit.capture.WallSeg
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Live mini room (v1, overlay data only): an oblique floor plan, fixed north-up in the world frame (world -Z up,
 * +X right). Floor = convex hull of wall endpoints (or the user's path box before two walls exist); walls are
 * extruded quads of a fixed visual height inked bottom-up by their seen percentage; ceiling icon fills when the
 * ceiling is found; user arrow and target dot. Auto-fits with smoothing.
 * [setData] copies the snapshot into preallocated arrays (call at <= 4 Hz, UI thread); onDraw never allocates.
 */
class RoomView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density

    // ---- snapshot (preallocated) ----
    private val wx0 = FloatArray(MAX_WALLS); private val wz0 = FloatArray(MAX_WALLS)
    private val wx1 = FloatArray(MAX_WALLS); private val wz1 = FloatArray(MAX_WALLS)
    private val wpct = IntArray(MAX_WALLS); private val order = IntArray(MAX_WALLS)
    private var nWalls = 0
    private val px = FloatArray(MAX_PTS); private val pz = FloatArray(MAX_PTS); private val pIdx = IntArray(MAX_PTS)
    private val hx = FloatArray(MAX_PTS + 1); private val hz = FloatArray(MAX_PTS + 1)
    private var nHull = 0
    private var pathMinX = 0f; private var pathMaxX = 0f; private var pathMinZ = 0f; private var pathMaxZ = 0f
    private var hasPath = false
    private var userX = 0f; private var userZ = 0f; private var yawRad = 0f
    private var hasTarget = false; private var targetX = 0f; private var targetZ = 0f
    private var floorFound = false; private var ceilingFound = false; private var tracking = true
    private var hasData = false
    private var showUser = true
    private var yawMask = 0                  // overlay v2: 12 x 30 deg heading sectors with a keyframe (bit i = [30i, 30i+30) deg)

    // ---- view transform (screen = o + s * (x, K*z - H*h)), smoothed ----
    private var s = 0f; private var ox = 0f; private var oy = 0f
    private var snap = true

    // ---- drawing resources ----
    private val floorFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val floorEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0x99FFFFFF.toInt() }
    private val wallGhost = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val wallEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = 0xAAFFFFFF.toInt(); strokeJoin = Paint.Join.ROUND }
    private val wallInk = Paint(Paint.ANTI_ALIAS_FLAG)
    private val userPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val userEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xFF000000.toInt() }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }
    private val targetRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xFFFFD54F.toInt() }
    private val ceilFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK_DONE }
    private val ceilEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xCCFFFFFF.toInt() }
    private val path = Path()
    private val ringGhost = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = 0x33FFFFFF; strokeCap = Paint.Cap.ROUND }
    private val ringDone = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = INK_DONE; strokeCap = Paint.Cap.ROUND }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Forget the session (path box, fit). */
    fun reset() { yawMask = 0; hasData = false; hasPath = false; nWalls = 0; nHull = 0; snap = true; invalidate() }

    /**
     * Static snapshot for the Review screen: walls plus floor/ceiling flags, no user arrow or target. Call on the UI
     * thread; the view must be (or become) laid out, [onSizeChanged] refits.
     */
    fun setSnapshot(segs: List<WallSeg>, floor: Boolean, ceiling: Boolean) {
        nWalls = min(segs.size, MAX_WALLS)
        var sx0 = 0f; var sz0 = 0f
        for (i in 0 until nWalls) {
            val w = segs[i]
            wx0[i] = w.x0.toFloat(); wz0[i] = w.z0.toFloat(); wx1[i] = w.x1.toFloat(); wz1[i] = w.z1.toFloat()
            wpct[i] = w.pct.coerceIn(0, 100)
            sx0 += wx0[i] + wx1[i]; sz0 += wz0[i] + wz1[i]
        }
        // The fit always includes the "user" point: park it at the wall centroid so it never widens the view.
        userX = if (nWalls > 0) sx0 / (2 * nWalls) else 0f; userZ = if (nWalls > 0) sz0 / (2 * nWalls) else 0f
        pathMinX = userX; pathMaxX = userX; pathMinZ = userZ; pathMaxZ = userZ; hasPath = true
        showUser = false; hasTarget = false; tracking = true; yawMask = 0
        floorFound = floor; ceilingFound = ceiling
        hasData = true; snap = true
        buildFloor(); sortWalls(); fit(); invalidate()
    }

    fun setData(o: CoreEvent.Overlay) {
        showUser = true; yawMask = o.yawMask
        val segs = o.surfaces
        nWalls = min(segs.size, MAX_WALLS)
        for (i in 0 until nWalls) {
            val w = segs[i]
            wx0[i] = w.x0.toFloat(); wz0[i] = w.z0.toFloat(); wx1[i] = w.x1.toFloat(); wz1[i] = w.z1.toFloat()
            wpct[i] = w.pct.coerceIn(0, 100)
        }
        userX = o.userX.toFloat(); userZ = o.userZ.toFloat(); yawRad = Math.toRadians(o.userYawDeg).toFloat()
        hasTarget = o.hasTarget; targetX = o.targetX.toFloat(); targetZ = o.targetZ.toFloat()
        floorFound = o.floorFound; ceilingFound = o.ceilingFound; tracking = o.tracking == 1
        if (!hasPath) { pathMinX = userX; pathMaxX = userX; pathMinZ = userZ; pathMaxZ = userZ; hasPath = true }
        else { pathMinX = min(pathMinX, userX); pathMaxX = max(pathMaxX, userX); pathMinZ = min(pathMinZ, userZ); pathMaxZ = max(pathMaxZ, userZ) }
        hasData = true
        buildFloor()
        sortWalls()
        fit()
        invalidate()
    }

    /** Floor polygon: hull of wall endpoints when it has area, else the path box (plus wall endpoints) padded. */
    private fun buildFloor() {
        var n = 0
        for (i in 0 until nWalls) { px[n] = wx0[i]; pz[n] = wz0[i]; n++; px[n] = wx1[i]; pz[n] = wz1[i]; n++ }
        nHull = if (nWalls >= 2) hull(n) else 0
        if (nHull >= 3 && hullArea() > 0.25f) return
        var x0 = pathMinX; var x1 = pathMaxX; var z0 = pathMinZ; var z1 = pathMaxZ
        for (i in 0 until n) { x0 = min(x0, px[i]); x1 = max(x1, px[i]); z0 = min(z0, pz[i]); z1 = max(z1, pz[i]) }
        x0 -= PAD_M; x1 += PAD_M; z0 -= PAD_M; z1 += PAD_M
        hx[0] = x0; hz[0] = z0; hx[1] = x1; hz[1] = z0; hx[2] = x1; hz[2] = z1; hx[3] = x0; hz[3] = z1
        nHull = 4
    }

    /** Andrew's monotone chain over px/pz[0, n) into hx/hz (counter-clockwise, no repeat). Allocation-free. */
    private fun hull(n: Int): Int {
        if (n < 3) return 0
        for (i in 0 until n) pIdx[i] = i
        for (i in 1 until n) {   // insertion sort by (x, z); n <= 24
            val k = pIdx[i]; var j = i - 1
            while (j >= 0 && (px[pIdx[j]] > px[k] || (px[pIdx[j]] == px[k] && pz[pIdx[j]] > pz[k]))) { pIdx[j + 1] = pIdx[j]; j-- }
            pIdx[j + 1] = k
        }
        var m = 0
        for (t in 0 until n) {
            val p = pIdx[t]
            while (m >= 2 && cross(hx[m - 2], hz[m - 2], hx[m - 1], hz[m - 1], px[p], pz[p]) <= 0f) m--
            hx[m] = px[p]; hz[m] = pz[p]; m++
        }
        val lower = m + 1
        for (t in n - 2 downTo 0) {
            val p = pIdx[t]
            while (m >= lower && cross(hx[m - 2], hz[m - 2], hx[m - 1], hz[m - 1], px[p], pz[p]) <= 0f) m--
            hx[m] = px[p]; hz[m] = pz[p]; m++
        }
        return m - 1
    }

    private fun cross(ax: Float, az: Float, bx: Float, bz: Float, cx: Float, cz: Float) = (bx - ax) * (cz - az) - (bz - az) * (cx - ax)

    private fun hullArea(): Float {
        var a = 0f
        for (i in 0 until nHull) { val j = (i + 1) % nHull; a += hx[i] * hz[j] - hx[j] * hz[i] }
        return kotlin.math.abs(a) * 0.5f
    }

    /** Far (small z, top of screen) walls first so near walls are drawn over them. */
    private fun sortWalls() {
        for (i in 0 until nWalls) order[i] = i
        for (i in 1 until nWalls) {
            val k = order[i]; val zk = wz0[k] + wz1[k]; var j = i - 1
            while (j >= 0 && wz0[order[j]] + wz1[order[j]] > zk) { order[j + 1] = order[j]; j-- }
            order[j + 1] = k
        }
    }

    private var bu0 = 0f; private var bu1 = 0f; private var bv0 = 0f; private var bv1 = 0f
    private fun grow(u: Float, v: Float) { bu0 = min(bu0, u); bu1 = max(bu1, u); bv0 = min(bv0, v); bv1 = max(bv1, v) }

    /** Fits floor, wall tops, user and target into the view; eases toward the new fit (snaps the first time). */
    private fun fit() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f || !hasData) return
        bu0 = userX; bu1 = userX; bv0 = K * userZ; bv1 = K * userZ
        for (i in 0 until nHull) grow(hx[i], K * hz[i])
        for (i in 0 until nWalls) {
            grow(wx0[i], K * wz0[i] - H * WALL_H); grow(wx1[i], K * wz1[i] - H * WALL_H)
            grow(wx0[i], K * wz0[i]); grow(wx1[i], K * wz1[i])
        }
        if (hasTarget) grow(targetX, K * targetZ)
        val spanU = max(bu1 - bu0, MIN_SPAN_M); val spanV = max(bv1 - bv0, MIN_SPAN_M * K)
        val pad = 12 * d
        val ts = min(min((w - 2 * pad) / spanU, (h - 2 * pad) / spanV), MAX_PX_PER_M * d)
        val tox = w / 2 - ts * (bu0 + bu1) / 2
        val toy = h / 2 - ts * (bv0 + bv1) / 2
        if (snap) { s = ts; ox = tox; oy = toy; snap = false }
        else { s += SMOOTH * (ts - s); ox += SMOOTH * (tox - ox); oy += SMOOTH * (toy - oy) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { snap = true; fit() }

    private fun sx(x: Float) = ox + s * x
    private fun sy(z: Float, hM: Float) = oy + s * (K * z - H * hM)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (!hasData || s <= 0f) return
        c.save()
        c.clipRect(0f, 0f, w, h)

        // Floor
        path.reset()
        for (i in 0 until nHull) { val x = sx(hx[i]); val y = sy(hz[i], 0f); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        path.close()
        floorFill.color = if (floorFound) 0x5566BB6A else 0x22FFFFFF
        c.drawPath(path, floorFill)
        c.drawPath(path, floorEdge)

        // Target dot (on the floor, under the walls' near faces)
        if (hasTarget) {
            val tx = sx(targetX); val ty = sy(targetZ, 0f)
            c.drawCircle(tx, ty, 3.5f * d, targetPaint)
            c.drawCircle(tx, ty, 7f * d, targetRing)
        }

        // Walls: ghost quad full height, ink bottom-up by pct
        for (k in 0 until nWalls) {
            val i = order[k]
            quad(wx0[i], wz0[i], wx1[i], wz1[i], WALL_H)
            c.drawPath(path, wallGhost)
            val p = wpct[i]
            if (p > 0) {
                wallInk.color = if (p >= DONE_PCT) INK_DONE else INK_SCANNING
                quad(wx0[i], wz0[i], wx1[i], wz1[i], WALL_H * p / 100f)
                c.drawPath(path, wallInk)
            }
            quad(wx0[i], wz0[i], wx1[i], wz1[i], WALL_H)
            c.drawPath(path, wallEdge)
        }

        // User arrow: heading (sin yaw, cos yaw) in x/z, projected onto the oblique floor
        if (showUser) {
            val ux = sx(userX); val uy = sy(userZ, 0f)
            // Heading ring (ADR 0022): one 30 deg arc per sector that has a keyframe, ghost arcs for the rest. Heading
            // (sin yaw, cos yaw) in x/z, squashed like the floor; moves with the user even when no wall plane exists.
            val rr = 15f * d
            for (sec in 0 until 12) {
                path.reset()
                for (j in 0..3) {
                    val a = Math.toRadians((sec * 30 + 1 + j * 9.33).toDouble())
                    val px0 = ux + rr * sin(a).toFloat(); val py0 = uy + rr * K * cos(a).toFloat()
                    if (j == 0) path.moveTo(px0, py0) else path.lineTo(px0, py0)
                }
                c.drawPath(path, if ((yawMask shr sec) and 1 != 0) ringDone else ringGhost)
            }
            var fx = sin(yawRad); var fy = K * cos(yawRad)
            val len = sqrt(fx * fx + fy * fy).coerceAtLeast(1e-3f); fx /= len; fy /= len
            val a = 9f * d; val b = 5.5f * d
            path.reset()
            path.moveTo(ux + fx * a, uy + fy * a)
            path.lineTo(ux - fx * b * 0.6f - fy * b, uy - fy * b * 0.6f + fx * b)
            path.lineTo(ux - fx * b * 0.1f, uy - fy * b * 0.1f)
            path.lineTo(ux - fx * b * 0.6f + fy * b, uy - fy * b * 0.6f - fx * b)
            path.close()
            userPaint.color = if (tracking) 0xFF4FC3F7.toInt() else UiKit.AMBER
            c.drawPath(path, userPaint)
            c.drawPath(path, userEdge)
        }
        c.restore()

        // Ceiling icon (top-right): a flat slab, filled once the ceiling is found
        val iw = 22f * d; val ih = 8f * d; val m = 8f * d
        val right = w - m; val top = m
        path.reset()
        path.moveTo(right - iw, top + ih); path.lineTo(right - iw * 0.75f, top)
        path.lineTo(right, top); path.lineTo(right - iw * 0.25f, top + ih); path.close()
        if (ceilingFound) c.drawPath(path, ceilFill)
        c.drawPath(path, ceilEdge)
    }

    /** Sets [path] to the wall quad from floor to [topM] metres. */
    private fun quad(x0: Float, z0: Float, x1: Float, z1: Float, topM: Float) {
        path.reset()
        path.moveTo(sx(x0), sy(z0, 0f)); path.lineTo(sx(x1), sy(z1, 0f))
        path.lineTo(sx(x1), sy(z1, topM)); path.lineTo(sx(x0), sy(z0, topM)); path.close()
    }

    companion object {
        /** "x0,z0,x1,z1,pct;..." (the overlay surfaces format, 5 fields) for the Review hand-over; parse with EventParser.parseSurfaces. */
        fun encodeSurfaces(segs: List<WallSeg>): String = segs.joinToString(";") {
            String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f,%.3f,%d", it.x0, it.z0, it.x1, it.z1, it.pct)
        }

        private const val MAX_WALLS = 12             // core overlay caps surfaces at 12
        const val MAX_PTS = 2 * MAX_WALLS
        const val WALL_H = 2.4f               // fixed visual wall height (m); guess until wall height is in the overlay
        const val K = 0.55f                   // oblique depth squash
        const val H = 0.45f                   // screen units per metre of height
        const val PAD_M = 0.5f                // floor padding around the path box (m)
        const val MIN_SPAN_M = 3f             // never zoom in tighter than 3 m
        const val MAX_PX_PER_M = 40f          // dp per metre cap
        const val SMOOTH = 0.35f              // fit easing per update (4 Hz)
        const val DONE_PCT = 80               // guess until Gate 0 data
        const val INK_DONE = 0xCC66BB6A.toInt()
        const val INK_SCANNING = 0xCC4FC3F7.toInt()
    }
}
