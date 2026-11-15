package dev.capturekit.sample

import android.Manifest
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.capturekit.guidance.HapticMap
import dev.capturekit.nativebridge.Status
import java.io.File
import java.util.Locale

/** Programmatic UI helpers shared by the sample activities (no layouts, no extra dependencies). */
object UiKit {
    const val GREEN = 0xFF2E7D32.toInt()
    const val AMBER = 0xFFEF8F00.toInt()
    const val RED = 0xFFC62828.toInt()
    const val PANEL = 0xAA000000.toInt()

    enum class Kind(val color: Int) { OK(GREEN), WARN(AMBER), ERROR(RED), INFO(PANEL) }

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

    /** Large white text on a translucent black panel, readable over the camera image. */
    fun statusText(ctx: Context, sizeSp: Float = 18f): TextView = TextView(ctx).apply {
        textSize = sizeSp
        setTextColor(Color.WHITE)
        background = rounded(PANEL, ctx.dp(8).toFloat())
        setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
    }

    fun bigButton(ctx: Context, label: String, color: Int = 0xFF1565C0.toInt()): Button = Button(ctx).apply {
        text = label
        textSize = 20f
        isAllCaps = false
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        background = rounded(color, ctx.dp(12).toFloat())
        minimumHeight = ctx.dp(64)
        minHeight = ctx.dp(64)
        setPadding(ctx.dp(24), ctx.dp(12), ctx.dp(24), ctx.dp(12))
    }

    fun recolor(button: Button, color: Int) { button.background = rounded(color, button.context.dp(12).toFloat()) }

    fun chip(ctx: Context): TextView = TextView(ctx).apply {
        textSize = 16f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
    }

    fun setChip(v: TextView, text: String, color: Int) {
        if (v.tag == color && v.text.toString() == text) return   // unchanged: no new drawable
        v.tag = color
        v.text = text
        v.background = rounded(color, v.context.dp(16).toFloat())
    }

    /** Coloured message strip: green = ok, amber = gentle warning, red = error. */
    fun banner(ctx: Context, msg: String, kind: Kind): TextView = TextView(ctx).apply { applyBanner(this, msg, kind) }

    fun applyBanner(v: TextView, msg: String, kind: Kind) {
        v.text = msg
        v.textSize = 18f
        v.setTextColor(Color.WHITE)
        v.background = rounded(kind.color, v.context.dp(10).toFloat())
        v.setPadding(v.context.dp(16), v.context.dp(10), v.context.dp(16), v.context.dp(10))
    }

    fun statusName(st: Int): String = when (st) {
        Status.OK -> "OK"; Status.STATE -> "STATE"; Status.ARG -> "ARG"
        Status.IO -> "IO"; Status.POLICY -> "POLICY"; Status.FULL -> "FULL"
        else -> "status $st"
    }

    fun mmss(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%02d:%02d", s / 60, s % 60) }

    fun dirSizeBytes(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun humanSize(b: Long): String = when {
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    /** Pads [v] by the system bars/cutout (targetSdk 37 is edge-to-edge) plus [basePadDp]. */
    fun applyInsets(v: View, basePadDp: Int = 12) {
        val base = v.context.dp(basePadDp)
        v.setPadding(base, base, base, base)
        v.setOnApplyWindowInsetsListener { view, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(base + b.left, base + b.top, base + b.right, base + b.bottom)
            }
            ins
        }
        v.requestApplyInsets()
    }

    fun haptic(v: View, confirm: Boolean) {
        val c = if (confirm && Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
        v.performHapticFeedback(c)
    }

    /** Plays the core's Instruction.haptic ("none"/"tap"/"double"/"success") via [HapticMap]. Safe from any thread. */
    fun hapticFor(v: View, haptic: String, priority: Int) {
        for (p in HapticMap.pulses(haptic, priority)) {
            val run = Runnable { haptic(v, p.kind == HapticMap.Kind.CONFIRM) }
            if (p.delayMs == 0L) v.post(run) else v.postDelayed(run, p.delayMs)
        }
    }

    /** Hides the system bars (swipe brings them back briefly) so the preview is edge to edge. */
    fun immersive(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val lp = a.window.attributes
            if (lp.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                a.window.attributes = lp
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            a.window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            a.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /** Quiet secondary button (translucent), same shape as [bigButton]. */
    fun secondaryButton(ctx: Context, label: String): Button = bigButton(ctx, label, 0xDD455A64.toInt()).apply { textSize = 18f }

    /** Text that keeps one or two lines and cuts the middle (long file paths). */
    fun pathText(ctx: Context, text: String, lines: Int = 2): TextView = TextView(ctx).apply {
        this.text = text; textSize = 14f; setTextColor(0xFFB0BEC5.toInt())
        maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }

    /** Plain-language hint for an ARCore tracking state. */
    fun trackingChip(state: String, failure: String): Pair<String, Int> = when (state) {
        "TRACKING" -> "Tracking: good" to GREEN
        "PAUSED" -> "Tracking: limited - " + when (failure) {
            "INSUFFICIENT_LIGHT" -> "needs more light"
            "EXCESSIVE_MOTION" -> "move more slowly"
            "INSUFFICIENT_FEATURES" -> "point at textured surfaces"
            "CAMERA_UNAVAILABLE" -> "camera busy"
            else -> "hold steady"
        } to AMBER
        "STOPPED" -> "Tracking lost - go back to where you started" to RED
        else -> "Tracking: starting..." to AMBER
    }
}

/** Camera permission helpers with "blocked" (don't ask again) detection. */
object CameraPermission {
    private var blocked = false

    fun granted(a: Activity) = a.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    fun isBlocked() = blocked

    fun request(a: Activity) = a.requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)

    /** Call from onRequestPermissionsResult; returns true when granted. */
    fun onResult(a: Activity, grantResults: IntArray): Boolean {
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        blocked = !ok && !a.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        return ok
    }

    /** Re-asks, or opens app settings once the system will no longer show the dialog. */
    fun retry(a: Activity) {
        if (blocked) {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", a.packageName, null)))
        } else request(a)
    }

    fun retryLabel() = if (blocked) "Open settings" else "Allow camera"
}
