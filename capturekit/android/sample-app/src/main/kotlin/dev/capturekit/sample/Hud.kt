package dev.capturekit.sample

import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared capture HUD drawn over the camera preview, in non-overlapping zones:
 *  - top bar: tracking/quality chips (left), REC pill and voice switch (right)
 *  - stack under the top bar: banner, detail line, result card (never over the bar)
 *  - row under the top bar: understanding card (left) beside the banner/detail/result stack (completeness ring + Walls/Floor/Ceiling chips)
 *  - bottom centre: coaching line, core "why" line, subtitle, Pause + Start/Finish buttons
 * Everything is clamped by system bar / cutout insets.
 */
class Hud(private val ctx: Activity) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val tracking = UiKit.chip(ctx).also { UiKit.setChip(it, "Tracking: starting...", UiKit.AMBER) }
    private val quality = UiKit.chip(ctx).apply { visibility = View.GONE }
    private val understanding = UnderstandingView(ctx)
    private val detail = UiKit.statusText(ctx, 15f).apply { visibility = View.GONE }
    /** Ingest counters line (trk/frm/kf/pts/pl); hidden until the tracking chip is long-pressed. */
    private val debug = UiKit.statusText(ctx, 12f).apply { visibility = View.GONE; typeface = Typeface.MONOSPACE }
    private var debugOn = false
    private val result = UiKit.statusText(ctx, 16f).apply { visibility = View.GONE; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; maxLines = 4 }
    private val recDot = View(ctx).apply { background = UiKit.rounded(Color.RED, dp(8).toFloat()) }
    private val recText = UiKit.statusText(ctx, 17f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(dp(10), dp(6), dp(12), dp(6)) }
    private val recRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(recDot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(8) })
        addView(recText)
    }
    private val voice = UiKit.chip(ctx).apply { visibility = View.GONE }
    private val coaching = UiKit.statusText(ctx, 22f).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
    private val subtitle = TextView(ctx).apply {
        textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; maxLines = 2
        background = UiKit.rounded(UiKit.PANEL, dp(8).toFloat()); setPadding(dp(12), dp(4), dp(12), dp(4)); visibility = View.GONE
    }
    private val bannerText = TextView(ctx)
    private val bannerButton = UiKit.bigButton(ctx, "Retry", 0x33FFFFFF).apply { textSize = 16f; minimumHeight = 0; minHeight = 0 }
    private val bannerRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(bannerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(bannerButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
    }
    val button: Button = UiKit.bigButton(ctx, "Start")
    val pauseButton: Button = UiKit.secondaryButton(ctx, "Pause").apply { visibility = View.GONE }

    private val pulse = ObjectAnimator.ofFloat(recDot, View.ALPHA, 1f, 0.2f).apply {
        duration = 600; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
    }
    private var coachText = ""
    private var subText = ""
    private var bannerKey: String? = null
    private var paused = false

    private val longSide = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
    init {
        tracking.maxWidth = (longSide * 0.34).toInt(); quality.maxWidth = (longSide * 0.34).toInt()
        val mid = (longSide * 0.55).toInt()
        coaching.maxWidth = mid; subtitle.maxWidth = mid; bannerText.maxWidth = mid; detail.maxWidth = mid; result.maxWidth = mid
        voice.setOnClickListener { onVoiceToggle() }
        debug.maxWidth = mid
        understanding.setMaxWidth(mid)
        tracking.setOnLongClickListener { debugOn = !debugOn; if (!debugOn) debug.visibility = View.GONE; true }
    }

    /** No-op while the debug line is toggled off (long-press the tracking chip to show it). */
    fun setDebug(text: String) {
        if (!debugOn) return
        debug.visibility = View.VISIBLE
        debug.text = text
    }

    /** Set to handle taps on the voice chip. */
    var onVoiceToggle: () -> Unit = {}

    val root = FrameLayout(ctx).apply {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(tracking, lp(bottom = 6)); addView(quality)
        }
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(recRow, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dp(8) })
            addView(voice)
        }
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(right, LinearLayout.LayoutParams(wrap, wrap))
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap).apply { bottomMargin = dp(8) })
            // Second row: understanding card on the left, status messages beside it (so neither pushes the other).
            val msgs = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(bannerRow, lp(bottom = 8)); addView(detail, lp(bottom = 8)); addView(debug, lp(bottom = 8)); addView(result)
            }
            val row2 = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(understanding.card, LinearLayout.LayoutParams(wrap, wrap))
                addView(msgs, LinearLayout.LayoutParams(0, wrap, 1f))
            }
            addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
        }
        addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap, Gravity.TOP))
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(pauseButton, LinearLayout.LayoutParams(dp(150), wrap).apply { rightMargin = dp(12) })
            addView(button, LinearLayout.LayoutParams(dp(230), wrap))
        }
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(coaching, lp(bottom = 6)); addView(understanding.why, lp(bottom = 6)); addView(subtitle, lp(bottom = 10)); addView(buttons)
        }
        addView(bottom, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        UiKit.applyInsets(this)
    }

    private fun lp(bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(bottom) }

    /** [live] false = no camera frame recently (paused/stalled): show a neutral chip, never a tracking verdict. */
    fun setTracking(state: String, failure: String, live: Boolean = true) {
        if (!live) { UiKit.setChip(tracking, "Camera paused", 0xFF455A64.toInt()); return }
        val (t, c) = UiKit.trackingChip(state, failure)
        UiKit.setChip(tracking, t, c)
    }

    fun setQuality(q: Double, reasons: String) {
        val (label, color) = when { q >= 0.7 -> "Good" to UiKit.GREEN; q >= 0.4 -> "Fair" to UiKit.AMBER; else -> "Poor" to UiKit.RED }
        quality.visibility = View.VISIBLE
        UiKit.setChip(quality, "Image quality: $label" + if (reasons.isEmpty()) "" else " ($reasons)", color)
    }

    fun hideQuality() { quality.visibility = View.GONE }

    /** Latest core overlay snapshot (null hides it). Rendered at most 4 Hz; UI thread only. */
    fun setUnderstanding(o: dev.capturekit.capture.CoreEvent.Overlay?) = understanding.update(o)

    fun setDetail(text: String?) {
        detail.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) detail.text = text
    }

    /** Fades from the old coaching line to the new one; [sub] is the longer explanation under it. No-op when unchanged. */
    fun setCoaching(text: String, sub: String = "") {
        if (sub != subText) {
            subText = sub
            subtitle.text = sub
            subtitle.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        }
        if (text == coachText) return
        coachText = text
        coaching.animate().cancel()
        coaching.animate().alpha(0f).setDuration(150).withEndAction {
            coaching.text = text
            coaching.animate().alpha(1f).setDuration(150).start()
        }.start()
    }

    fun setRecording(on: Boolean) {
        recRow.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { if (!pulse.isStarted) pulse.start() } else { pulse.cancel(); recDot.alpha = 1f; paused = false }
        button.text = if (on) "Finish" else "Start"
        UiKit.recolor(button, if (on) UiKit.RED else 0xFF1565C0.toInt())
        pauseButton.visibility = if (on && pauseEnabled) View.VISIBLE else View.GONE
        if (!on) pauseButton.text = "Pause"
    }

    /** Pause is offered only where a screen supports it (guided scan). */
    var pauseEnabled = false

    fun setPaused(on: Boolean) {
        paused = on
        pauseButton.text = if (on) "Resume" else "Pause"
        if (on) { pulse.cancel(); recDot.alpha = 0.3f } else if (!pulse.isStarted) pulse.start()
    }

    fun setRecInfo(elapsedMs: Long, counter: String) {
        recText.text = (if (paused) "PAUSED  " else "REC  ") + UiKit.mmss(elapsedMs) + "   " + counter
    }

    fun setVoice(on: Boolean?) {   // null hides the chip
        if (on == null) { voice.visibility = View.GONE; return }
        voice.visibility = View.VISIBLE
        UiKit.setChip(voice, if (on) "Voice: on" else "Voice: off", if (on) UiKit.GREEN else 0xFF546E7A.toInt())
    }

    fun setBanner(msg: String?, kind: UiKit.Kind = UiKit.Kind.WARN, retryLabel: String? = null, retry: (() -> Unit)? = null) {
        if (msg == null) { bannerRow.visibility = View.GONE; bannerKey = null; return }
        if (retry != null) bannerButton.setOnClickListener { retry() }
        val key = msg + "|" + kind + "|" + retryLabel + "|" + (retry != null)
        if (key == bannerKey && bannerRow.visibility == View.VISIBLE) return   // unchanged: no new drawables
        bannerKey = key
        UiKit.applyBanner(bannerText, msg, kind)
        bannerText.textSize = 16f
        bannerRow.background = UiKit.rounded(kind.color, dp(10).toFloat())
        bannerText.background = null
        bannerButton.visibility = if (retry != null) View.VISIBLE else View.GONE
        if (retry != null) bannerButton.text = retryLabel ?: "Retry"
        bannerRow.visibility = View.VISIBLE
    }

    fun setResult(msg: String?, kind: UiKit.Kind = UiKit.Kind.OK) {
        if (msg == null) { result.visibility = View.GONE; return }
        result.text = msg
        result.background = UiKit.rounded(kind.color, dp(8).toFloat())
        result.visibility = View.VISIBLE
    }

    fun haptic(confirm: Boolean) = UiKit.haptic(button, confirm)
    fun hapticFor(haptic: String, priority: Int) = UiKit.hapticFor(button, haptic, priority)

    fun release() { pulse.cancel(); coaching.animate().cancel() }
}
