package dev.capturekit.sample

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.capturekit.capture.FeatureFlags
import dev.capturekit.guidance.StatusPill
import kotlin.math.roundToInt

/**
 * Shared capture HUD over the camera preview (always dark, glass only here):
 *  - Top left: close and the status pill. Top right: REC capsule, mic, and a "more" button whose sheet holds the
 *    voice / boxes / masks / trial switches.
 *  - Under the top bar: one inline alert (errors and blocking conditions, with an optional text action).
 *  - Left: room parts ([UnderstandingView]). Right: message stack (detail, voice feedback, debug, result).
 *  - Bottom: one coaching capsule, then the deck (pause | shutter | spacer).
 * Long-press the status pill to toggle the ingest debug line. Clamped by system bar / cutout insets.
 * [features] (ADR 0032): a switched-off feature's control is never shown and gets no click listener
 * (room card = guidance, boxes = detector, masks = segmentation, mic = voiceCommands, recalibration card =
 * recalibrateCard). One camera-overlay switch (issue #8): while the masks switch is offered it replaces the boxes switch.
 */
class Hud(private val ctx: Activity, private val features: FeatureFlags.Flags = FeatureFlags.Flags.DEFAULTS) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).roundToInt()
    private val p = UiKit.c(ctx)

    private val pill = StatusBadge(ctx, onGlass = true)
    private val understanding = UnderstandingView(ctx)
    private val detail = glassText(Tokens.CALLOUT).apply { visibility = View.GONE }
    /** Voice-command feedback ("Paused.", answers, notes): its own line so it never overwrites [detail]. */
    private val voiceLine = glassText(Tokens.CALLOUT).apply { visibility = View.GONE; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
    /** Ingest counters line (trk/frm/kf/pts/pl); hidden until the status pill is long-pressed. */
    private val debug = glassText(Tokens.FOOTNOTE).apply { visibility = View.GONE; typeface = Typeface.MONOSPACE }
    var debugOn = false
        private set
    private val result = InlineAlert(ctx, onGlass = true)
    private val recDot = DotView(ctx).apply { set(p.red) }
    private val recText = UiKit.text(ctx, "", Tokens.LABEL, p.label).apply { fontFeatureSettings = "tnum" }
    private val recRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        background = UiKit.glass(ctx, Tokens.GLASS_REGULAR, Tokens.R_PILL)
        minimumHeight = dp(Tokens.TOUCH_MIN)
        setPadding(dp(14), 0, dp(Tokens.S16), 0)
        addView(recDot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(Tokens.S8) })
        addView(recText)
    }

    // Switch states offered in the "more" sheet (null = not offered).
    private var voiceOn: Boolean? = null
    private var boxesWanted: Boolean? = null   // last setBoxes value, re-applied when the masks switch goes away
    private var boxesShown: Boolean? = null
    private var masksShown: Boolean? = null
    private var trialLabel: String? = null
    private var moreSheet: Dialog? = null
    private var moreKey = ""

    /** Push-to-talk capsule: mic glyph plus words while listening / working / blocked. Hidden unless voice commands are available. */
    private val micIcon = UiKit.iconImage(ctx, R.drawable.ic_mic, p.label)
    private val micText = UiKit.text(ctx, "", Tokens.LABEL, p.label).apply { visibility = View.GONE }
    private val mic = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        isClickable = true; isFocusable = true
        background = UiKit.glass(ctx, Tokens.GLASS_REGULAR, Tokens.R_PILL)
        minimumHeight = dp(Tokens.TOUCH_MIN); minimumWidth = dp(Tokens.TOUCH_MIN)
        setPadding(dp(Tokens.S12), 0, dp(Tokens.S12), 0)
        addView(micIcon, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(micText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        UiKit.pressScale(this)
    }
    private val more = UiKit.iconButton(ctx, R.drawable.ic_more_horiz, "More controls", onGlass = true).apply { visibility = View.GONE }

    private val coaching = UiKit.text(ctx, "", Tokens.HEADLINE, p.label).apply {
        gravity = Gravity.CENTER
        background = UiKit.glass(ctx, Tokens.GLASS_REGULAR, Tokens.R_PILL)
        setPadding(dp(Tokens.S24), dp(Tokens.S12), dp(Tokens.S24), dp(Tokens.S12))
        maxLines = 2
    }
    /** Issue #1: centred "Recalibrating" card with the circling-phone animation (declared before [root], which adds it). */
    private val recalibrate = RecalibrateOverlay(ctx)
    private val banner = InlineAlert(ctx, onGlass = true)

    /** Camera shutter in the centre of the deck. */
    val button: Button = ShutterButton(ctx).apply {
        minimumWidth = dp(76); minWidth = dp(76)
        minimumHeight = dp(76); minHeight = dp(76)
    }
    /** Round glass pause / resume button left of the shutter (drawn glyphs). */
    val pauseButton: Button = GlyphButton(ctx, R.drawable.ic_pause, "Pause", p.label).apply {
        visibility = View.GONE
    }
    /** Leaves the capture screen before recording starts (hidden while recording: Finish is the way out). */
    val closeButton: View = UiKit.iconButton(ctx, R.drawable.ic_close, "Close", onGlass = true).apply {
        setOnClickListener { ctx.finish() }
    }

    private val pulse = ObjectAnimator.ofFloat(recDot, View.ALPHA, 1f, 0.3f).apply {
        duration = 600; repeatMode = ObjectAnimator.REVERSE; repeatCount = ObjectAnimator.INFINITE
    }
    private var coachText = ""
    private var paused = false
    private var recSec = -1L
    private var recPaused = false
    private var recCounter = ""
    private var everTracked = false   // "Starting..." only until the camera first tracks

    private val longSide = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
    init {
        pill.maxTextWidth = (longSide * 0.3).toInt()
        val mid = (longSide * 0.45).toInt()
        coaching.maxWidth = (longSide * 0.6).toInt(); detail.maxWidth = mid; voiceLine.maxWidth = mid; debug.maxWidth = mid
        if (features.voiceCommands) mic.setOnClickListener { onMicClick() }
        more.setOnClickListener { showMore() }
        pill.isLongClickable = true
        pill.setOnLongClickListener { debugOn = !debugOn; if (!debugOn) debug.visibility = View.GONE; true }
        setPill(StatusPill.STARTING)
    }

    private fun glassText(t: Tokens.TypeStyle) = UiKit.text(ctx, "", t, p.label).apply {
        background = UiKit.glass(ctx, Tokens.GLASS_REGULAR, Tokens.R_CARD)
        setPadding(dp(Tokens.S12), dp(Tokens.S8), dp(Tokens.S12), dp(Tokens.S8))
    }

    /** No-op while the debug line is toggled off (long-press the status pill to show it). */
    fun setDebug(text: String) {
        if (!debugOn) return
        debug.visibility = View.VISIBLE
        if (debug.text.toString() != text) debug.text = text
    }

    /** Set to handle taps on the voice switch. */
    var onVoiceToggle: () -> Unit = {}

    val root = FrameLayout(ctx).apply {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val gap = dp(Tokens.S8)
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(recRow, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = gap })
            addView(mic, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = gap })
            addView(more, LinearLayout.LayoutParams(dp(Tokens.TOUCH_MIN), dp(Tokens.TOUCH_MIN)))
        }
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(closeButton, LinearLayout.LayoutParams(dp(Tokens.TOUCH_MIN), dp(Tokens.TOUCH_MIN)).apply { rightMargin = gap })
            addView(pill, LinearLayout.LayoutParams(wrap, wrap))
            addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            addView(right, LinearLayout.LayoutParams(wrap, wrap))
        }
        val msgs = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(detail, lp(gap)); addView(voiceLine, lp(gap)); addView(debug, lp(gap))
            addView(result, LinearLayout.LayoutParams((longSide * 0.45).toInt(), wrap))
        }
        val middle = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            if (features.guidance) addView(understanding.card, LinearLayout.LayoutParams(wrap, wrap).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(msgs, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dp(Tokens.S12); gravity = Gravity.CENTER_VERTICAL })
        }
        val deck = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(pauseButton, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(Tokens.S32) })
            addView(button, LinearLayout.LayoutParams(dp(76), dp(76)))
            addView(View(ctx), LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(Tokens.S32) })   // keeps the shutter centred
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(topBar, LinearLayout.LayoutParams(match, wrap))
            addView(banner, LinearLayout.LayoutParams(match, wrap).apply { topMargin = gap })
            addView(middle, LinearLayout.LayoutParams(match, 0, 1f).apply { topMargin = gap; bottomMargin = gap })
            addView(coaching, LinearLayout.LayoutParams(wrap, wrap).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(Tokens.S12) })
            addView(deck, LinearLayout.LayoutParams(match, wrap))
        }
        addView(column, FrameLayout.LayoutParams(match, match))
        if (features.recalibrateCard) addView(recalibrate.card, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))   // above the column, never clickable
        UiKit.applyInsets(this)
    }

    private fun lp(bottom: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = bottom }

    private fun kindOf(t: StatusPill.Tone): UiKit.Kind = when (t) {
        StatusPill.Tone.GOOD -> UiKit.Kind.OK
        StatusPill.Tone.WARN -> UiKit.Kind.WARN
        StatusPill.Tone.BAD -> UiKit.Kind.ERROR
        StatusPill.Tone.NEUTRAL -> UiKit.Kind.INFO
    }

    private fun setPill(pl: StatusPill.Pill) {
        pill.set(pl.text, kindOf(pl.tone))
        pill.contentDescription = "Status: ${pl.text}. Long press for debug details."
    }

    /**
     * The single status pill. [reasons] are the latest core frame-quality reason codes, [thermalLevel] the governor
     * policy level. [live] false = no camera frame recently (paused/stalled): neutral pill, never a tracking verdict.
     */
    fun setStatus(state: String, failure: String, reasons: List<String>, thermalLevel: Int, live: Boolean = true) {
        if (!live) { pill.set("Camera paused", UiKit.Kind.INFO, hollow = true); return }
        if (state == "TRACKING") everTracked = true
        setPill(StatusPill.statusPill(state, failure, reasons, thermalLevel, everTracked))
    }

    /** Tracking-only pill (screens without core quality/thermal, e.g. the raw recorder). */
    fun setTracking(state: String, failure: String, live: Boolean = true) = setStatus(state, failure, emptyList(), 0, live)

    /** Latest core overlay snapshot (null hides it). Rendered at most 4 Hz; UI thread only. */
    fun setUnderstanding(o: dev.capturekit.capture.CoreEvent.Overlay?) { if (features.guidance) understanding.update(o) }

    /** Voice-command feedback line; null hides it. Independent of [setDetail]. */
    fun setVoiceLine(text: String?) {
        voiceLine.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null && voiceLine.text.toString() != text) voiceLine.text = text
    }

    fun setDetail(text: String?) {
        detail.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) detail.text = text
    }

    /** Fades to the new coaching sentence; [sub] (core long_text) goes to the room card caption. No-op when unchanged. */
    fun setCoaching(text: String, sub: String = "") {
        if (features.guidance) understanding.setLongText(sub)
        if (text == coachText) return
        coachText = text
        coaching.animate().cancel()
        coaching.animate().alpha(0f).setDuration(Tokens.MOTION_SHORT).setInterpolator(UiKit.EASE_STANDARD).withEndAction {
            coaching.text = text
            coaching.animate().alpha(1f).setDuration(Tokens.MOTION_MEDIUM).setInterpolator(UiKit.EASE_ENTER).start()
        }.start()
    }

    fun setRecording(on: Boolean) {
        recRow.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { if (!pulse.isStarted) pulse.start() } else { pulse.cancel(); recDot.alpha = 1f; paused = false }
        (button as? ShutterButton)?.isRecording = on
        button.text = if (on) "Finish Scan" else "Start Scan"
        pauseButton.visibility = if (on && pauseEnabled) View.VISIBLE else View.GONE
        closeButton.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) (pauseButton as GlyphButton).setGlyph(R.drawable.ic_pause, "Pause")
        recSec = -1
    }

    /** Pause is offered only where a screen supports it (guided scan). */
    var pauseEnabled = false

    fun setPaused(on: Boolean) {
        if (on == paused) return
        paused = on
        (pauseButton as GlyphButton).setGlyph(if (on) R.drawable.ic_play else R.drawable.ic_pause, if (on) "Resume" else "Pause")
        if (on) { pulse.cancel(); recDot.alpha = 0.3f } else if (!pulse.isStarted) pulse.start()
    }

    /** REC clock; text is rebuilt only when the shown second, pause state or [counter] changes. */
    fun setRecInfo(elapsedMs: Long, counter: String = "") {
        val sec = elapsedMs / 1000
        if (sec == recSec && paused == recPaused && counter == recCounter) return
        recSec = sec; recPaused = paused; recCounter = counter
        recText.text = (if (paused) "Paused  " else "REC  ") + UiKit.mmss(elapsedMs) + if (counter.isEmpty()) "" else "  ·  $counter"
    }

    fun setVoice(on: Boolean?) {   // null = not offered
        voiceOn = on
        refreshMore()
    }

    /** Set to handle taps on the boxes switch. */
    var onBoxesToggle: () -> Unit = {}

    /** Camera-feed boxes switch; null hides it (detector not running, not recording, or the unguided trial arm). */
    fun setBoxes(on: Boolean?) {
        boxesWanted = on
        boxesShown = if (on == null || !features.detector || masksShown != null) null else on
        refreshMore()
    }

    /** Set to handle taps on the masks switch. */
    var onMasksToggle: () -> Unit = {}

    /**
     * Camera-feed segmentation masks switch (issue #8); null hides it (segmentation not running, not recording, or the
     * unguided trial arm). While offered it replaces the boxes switch (one camera-overlay control); hiding it brings
     * the boxes switch back as last set.
     */
    fun setMasks(on: Boolean?) {
        if (on == null || !features.segmentation) {
            val wasShown = masksShown != null
            masksShown = null
            if (wasShown) setBoxes(boxesWanted) else refreshMore()
            return
        }
        masksShown = on
        boxesShown = null
        refreshMore()
    }

    /** Set to handle taps on the trial row (field flavour: opens the trial setup). */
    var onTrialClick: () -> Unit = {}

    /** Trial label; null hides the row (standard flavour, or while recording). */
    fun setTrial(label: String?) {
        trialLabel = label
        refreshMore()
    }

    /**
     * "More" is shown only when its sheet has something to offer. An open sheet closes when its set of rows changes
     * (the setters run on every HUD tick, so unchanged rows must not close it).
     */
    private fun refreshMore() {
        val key = "${voiceOn != null}|${boxesShown != null}|${masksShown != null}|$trialLabel"
        if (key == moreKey) return
        moreKey = key
        val any = voiceOn != null || boxesShown != null || masksShown != null || trialLabel != null
        more.visibility = if (any) View.VISIBLE else View.GONE
        moreSheet?.dismiss()
        moreSheet = null
    }

    private fun showMore() {
        moreSheet?.dismiss()
        moreSheet = UiKit.sheet(ctx) { content, dlg ->
            content.addView(UiKit.headline(ctx, "Scan controls"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(Tokens.S12) })
            val group = UiKit.group(ctx).apply { background = UiKit.surface(ctx, p.surface2) }
            voiceOn?.let { on -> group.addView(switchRow("Voice guidance", "Spoken coaching while you scan", on) { onVoiceToggle() }) }
            boxesShown?.let { on -> group.addView(switchRow("Object boxes", "Outline recognised objects on the camera view", on) { onBoxesToggle() }) }
            masksShown?.let { on -> group.addView(switchRow("Object masks", "Shade recognised objects and surfaces", on) { onMasksToggle() }) }
            trialLabel?.let { label ->
                group.addView(UiKit.listRow(ctx, null, "Research trial", label) { dlg.dismiss(); onTrialClick() }.apply {
                    contentDescription = "Research trial: $label. Tap to change participant or guided arm."
                })
            }
            content.addView(group)
        }
    }

    /** Switch row whose switch mirrors [on]; a tap runs [toggle] (the activity then calls the matching setter). */
    private fun switchRow(title: String, subtitle: String, on: Boolean, toggle: () -> Unit): View {
        val sw = UiKit.toggle(ctx, on).apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        return UiKit.listRow(ctx, null, title, subtitle, trailing = sw, chevron = false) { sw.toggle(); toggle() }.apply {
            contentDescription = "$title, ${if (on) "on" else "off"}. Double tap to switch."
        }
    }

    /** Set to handle taps on the mic capsule (push-to-talk). */
    var onMicClick: () -> Unit = {}

    /**
     * Push-to-talk capsule. null hides it (no voice commands here). Listening is unmistakable: red glyph, "Listening"
     * text and a spoken-by-TalkBack description; idle is a quiet glyph.
     */
    fun setMic(state: MicState?) {
        if (state == null || !features.voiceCommands) { mic.visibility = View.GONE; return }
        mic.visibility = View.VISIBLE
        val (text, tint, desc) = when (state) {
            MicState.IDLE -> Triple(null, p.label, "Voice commands. Tap, then say pause, resume, repeat, what is missing, finish, louder or quieter.")
            MicState.LISTENING -> Triple("Listening...", p.red, "Listening for a command. Tap to cancel.")
            MicState.THINKING -> Triple("Working...", p.label, "Understanding the command.")
            MicState.NEEDS_PERMISSION -> Triple("Mic off", p.label3, "Voice commands need microphone permission. Tap to learn more.")
        }
        micIcon.imageTintList = ColorStateList.valueOf(tint)
        micText.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null && micText.text.toString() != text) micText.text = text
        if (mic.contentDescription != desc) mic.contentDescription = desc
        mic.isEnabled = state != MicState.THINKING
    }

    /** Inline alert under the top bar; reserved for errors and blocking conditions. */
    fun setBanner(msg: String?, kind: UiKit.Kind = UiKit.Kind.WARN, retryLabel: String? = null, retry: (() -> Unit)? = null) =
        banner.set(msg, kind, retryLabel, retry)

    fun setResult(msg: String?, kind: UiKit.Kind = UiKit.Kind.OK) = result.set(msg, kind)

    fun haptic(confirm: Boolean) = UiKit.haptic(button, confirm)
    fun hapticFor(haptic: String, priority: Int) = UiKit.hapticFor(button, haptic, priority)

    /**
     * Issue #1 / ADR 0031: the recalibration card. [text] = the core's sentence ([dev.capturekit.guidance.Recalibration.prompt]);
     * null hides it. Shown while the core recovers tracking (keyframe capture paused); dismissed when tracking is back.
     */
    fun setRecalibrate(text: String?) { if (features.recalibrateCard) recalibrate.set(text) }

    fun release() {
        pulse.cancel(); coaching.animate().cancel(); recalibrate.release()
        moreSheet?.dismiss(); moreSheet = null
    }

    /** Round glass button that draws one 24 dp glyph (no text glyphs). */
    private class GlyphButton(ctx: Context, icon: Int, description: String, private val tint: Int) : Button(ctx) {
        private val size = (24 * ctx.resources.displayMetrics.density).roundToInt()
        private var glyph: Drawable = load(icon)

        init {
            text = ""
            isAllCaps = false
            stateListAnimator = null
            background = UiKit.glass(ctx, Tokens.GLASS_REGULAR, Tokens.R_PILL)
            contentDescription = description
            setPadding(0, 0, 0, 0)
            UiKit.pressScale(this)
        }

        private fun load(icon: Int): Drawable = context.getDrawable(icon)!!.mutate().apply { setTint(tint) }

        fun setGlyph(icon: Int, description: String) {
            glyph = load(icon)
            contentDescription = description
            invalidate()
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.4f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val l = (width - size) / 2; val t = (height - size) / 2
            glyph.setBounds(l, t, l + size, t + size)
            glyph.draw(canvas)
        }
    }
}
