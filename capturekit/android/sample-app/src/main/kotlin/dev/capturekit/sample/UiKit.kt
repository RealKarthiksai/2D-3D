package dev.capturekit.sample

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import dev.capturekit.guidance.HapticMap
import dev.capturekit.nativebridge.Status
import java.io.File
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Marks the camera screens: always the dark palette, whatever the phone's light/dark setting. */
interface AlwaysDark

/**
 * CaptureKit design system v3 (ADR 0036): calm monochrome surfaces from [Tokens], one type scale, status hue only as a
 * drawn dot or icon next to words. Content screens follow the phone's light/dark setting; glass is used only over the
 * camera. Every builder reads its colours from [c] at build time, so there is no global mutable palette.
 */
object UiKit {
    enum class Kind { OK, WARN, ERROR, INFO }

    /** Palette for [ctx]: dark for [AlwaysDark] screens, else the night bit of the current configuration. */
    fun c(ctx: Context): Tokens.Palette {
        var x: Context? = ctx
        while (x != null) {
            if (x is AlwaysDark) return Tokens.DARK
            x = (x as? ContextWrapper)?.baseContext
        }
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return if (night) Tokens.DARK else Tokens.LIGHT
    }

    /** Status hue of [kind] (dots and icons only, never text). */
    fun hue(p: Tokens.Palette, kind: Kind): Int = when (kind) {
        Kind.OK -> p.green
        Kind.WARN -> p.orange
        Kind.ERROR -> p.red
        Kind.INFO -> p.label3
    }

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    fun Context.dpF(v: Float): Float = v * resources.displayMetrics.density
    private fun Context.hairline(): Int = maxOf(1, (0.5f * resources.displayMetrics.density).roundToInt())

    // ---- Motion ----
    val EASE_STANDARD = Tokens.EASE_STANDARD.let { PathInterpolator(it[0], it[1], it[2], it[3]) }
    val EASE_ENTER = Tokens.EASE_EMPHASIZED_DECELERATE.let { PathInterpolator(it[0], it[1], it[2], it[3]) }

    // ---- Type ----
    private val REGULAR: Typeface = Typeface.DEFAULT
    private val MEDIUM: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val SEMIBOLD: Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 600, false) else Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun face(weight: Int): Typeface = when {
        weight >= 600 -> SEMIBOLD
        weight >= 500 -> MEDIUM
        else -> REGULAR
    }

    /** Applies size, weight and line height of [t] to [tv]. */
    fun type(tv: TextView, t: Tokens.TypeStyle): TextView {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.sp)
        tv.typeface = face(t.weight)
        val lh = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, t.lineSp, tv.resources.displayMetrics).roundToInt()
        if (Build.VERSION.SDK_INT >= 28) tv.lineHeight = lh
        else tv.setLineSpacing((lh - tv.paint.getFontMetricsInt(null)).coerceAtLeast(0).toFloat(), 1f)
        return tv
    }

    fun text(ctx: Context, s: CharSequence, t: Tokens.TypeStyle = Tokens.BODY, color: Int = c(ctx).label): TextView =
        type(TextView(ctx).apply { text = s; setTextColor(color) }, t)

    fun largeTitle(ctx: Context, s: String): TextView = heading(text(ctx, s, Tokens.LARGE_TITLE))
    fun title(ctx: Context, s: String): TextView = heading(text(ctx, s, Tokens.TITLE))
    fun headline(ctx: Context, s: CharSequence, color: Int = c(ctx).label): TextView = text(ctx, s, Tokens.HEADLINE, color)
    fun body(ctx: Context, s: CharSequence, color: Int = c(ctx).label2): TextView = text(ctx, s, Tokens.BODY, color)
    fun callout(ctx: Context, s: CharSequence, color: Int = c(ctx).label2): TextView = text(ctx, s, Tokens.CALLOUT, color)
    fun footnote(ctx: Context, s: CharSequence, color: Int = c(ctx).label3): TextView = text(ctx, s, Tokens.FOOTNOTE, color)

    private fun heading(tv: TextView): TextView = tv.apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }

    // ---- Surfaces ----
    fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokeWidthPx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
        }

    /** Opaque content surface (cards, groups). */
    fun surface(ctx: Context, color: Int = c(ctx).surface, radiusDp: Int = Tokens.R_CARD): GradientDrawable =
        rounded(color, ctx.dpF(radiusDp.toFloat()))

    /** Camera glass: flat graphite at [alpha] plus one hairline. No blur, gradient, shadow or glow. */
    fun glass(ctx: Context, alpha: Int = Tokens.GLASS_REGULAR, radiusDp: Int = Tokens.R_SHEET): GradientDrawable =
        rounded(Tokens.glass(alpha), ctx.dpF(radiusDp.toFloat()), Tokens.HAIRLINE, ctx.hairline())

    private fun ripple(ctx: Context, content: Drawable?, radiusDp: Int): RippleDrawable {
        val mask = rounded(Tokens.WHITE, ctx.dpF(radiusDp.toFloat()))
        return RippleDrawable(ColorStateList.valueOf(Tokens.withAlpha(c(ctx).label, 0x1F)), content, mask)
    }

    /** Ripple only (rows inside a grouped list). */
    fun rowRipple(ctx: Context): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Tokens.withAlpha(c(ctx).label, 0x14)), null, ColorDrawable(Tokens.WHITE))

    fun iconImage(ctx: Context, drawableId: Int, tint: Int): ImageView = ImageView(ctx).apply {
        setImageResource(drawableId)
        imageTintList = ColorStateList.valueOf(tint)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    // ---- Buttons ----
    enum class ButtonStyle { PRIMARY, SECONDARY, TEXT }

    fun button(ctx: Context, label: String, style: ButtonStyle = ButtonStyle.PRIMARY): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        stateListAnimator = null
        type(this, Tokens.BUTTON)
        gravity = Gravity.CENTER
        minimumHeight = ctx.dp(Tokens.TOUCH_MIN); minHeight = ctx.dp(Tokens.TOUCH_MIN)
        minimumWidth = ctx.dp(Tokens.TOUCH_MIN); minWidth = ctx.dp(Tokens.TOUCH_MIN)
        setPadding(ctx.dp(Tokens.S20), ctx.dp(Tokens.S12), ctx.dp(Tokens.S20), ctx.dp(Tokens.S12))
        paintButton(this, style)
        pressScale(this)
    }

    fun primaryButton(ctx: Context, label: String): Button = button(ctx, label, ButtonStyle.PRIMARY)
    fun secondaryButton(ctx: Context, label: String): Button = button(ctx, label, ButtonStyle.SECONDARY)
    fun textButton(ctx: Context, label: String): Button = button(ctx, label, ButtonStyle.TEXT)

    /** (Re)paints [b] in [style]; a disabled button always reads as a quiet secondary with tertiary text. */
    fun paintButton(b: Button, style: ButtonStyle) {
        val ctx = b.context
        val p = c(ctx)
        val shown = if (!b.isEnabled && style == ButtonStyle.PRIMARY) ButtonStyle.SECONDARY else style
        b.background = when (shown) {
            ButtonStyle.PRIMARY -> ripple(ctx, rounded(p.primaryFill, ctx.dpF(Tokens.R_PILL.toFloat())), Tokens.R_PILL)
            ButtonStyle.SECONDARY -> ripple(ctx, rounded(p.surface2, ctx.dpF(Tokens.R_PILL.toFloat())), Tokens.R_PILL)
            ButtonStyle.TEXT -> ripple(ctx, null, Tokens.R_PILL)
        }
        b.setTextColor(when {
            !b.isEnabled -> p.label3
            shown == ButtonStyle.PRIMARY -> p.onPrimary
            else -> p.label
        })
    }

    /** 48 dp round icon button; [onGlass] = over the camera (glass body), else a quiet secondary fill. */
    fun iconButton(ctx: Context, drawableId: Int, description: String, onGlass: Boolean = false): FrameLayout = FrameLayout(ctx).apply {
        isClickable = true
        isFocusable = true
        minimumWidth = ctx.dp(Tokens.TOUCH_MIN)
        minimumHeight = ctx.dp(Tokens.TOUCH_MIN)
        contentDescription = description
        val body = if (onGlass) glass(ctx, Tokens.GLASS_REGULAR, Tokens.R_PILL) else rounded(c(ctx).surface2, ctx.dpF(Tokens.R_PILL.toFloat()))
        background = ripple(ctx, body, Tokens.R_PILL)
        addView(iconImage(ctx, drawableId, c(ctx).label), FrameLayout.LayoutParams(ctx.dp(24), ctx.dp(24), Gravity.CENTER))
        pressScale(this)
    }

    /** Swaps the glyph of an [iconButton]. */
    fun setIcon(button: FrameLayout, drawableId: Int, description: String) {
        (button.getChildAt(0) as? ImageView)?.setImageResource(drawableId)
        if (button.contentDescription != description) button.contentDescription = description
    }

    // ---- Grouped list (inset-grouped) ----
    fun sectionHeader(ctx: Context, s: String): TextView = heading(footnote(ctx, s.uppercase(Locale.getDefault()), c(ctx).label2)).apply {
        letterSpacing = 0.04f
        setPadding(ctx.dp(Tokens.S16), 0, ctx.dp(Tokens.S16), ctx.dp(Tokens.S8))
    }

    /** Opaque group whose rows are separated by inset hairlines. */
    fun group(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = surface(ctx)
        clipToOutline = true
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setColor(c(ctx).separator); setSize(1, ctx.hairline()) }
        dividerPadding = 0
    }

    /** One row (>= 56 dp): optional icon, title + subtitle, trailing view (chevron when tappable and nothing else given). */
    fun listRow(
        ctx: Context, icon: Int?, title: CharSequence, subtitle: CharSequence? = null,
        trailing: View? = null, titleColor: Int = c(ctx).label, chevron: Boolean = true, onClick: (() -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        val p = c(ctx)
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(Tokens.ROW_MIN)
        setPadding(ctx.dp(Tokens.S16), ctx.dp(Tokens.S12), ctx.dp(Tokens.S16), ctx.dp(Tokens.S12))
        if (icon != null) addView(iconImage(ctx, icon, p.label2),
            LinearLayout.LayoutParams(ctx.dp(24), ctx.dp(24)).apply { rightMargin = ctx.dp(Tokens.S16) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(ctx, title, Tokens.BODY, titleColor))
            if (subtitle != null) addView(footnote(ctx, subtitle, p.label2).apply { setPadding(0, ctx.dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val end = trailing ?: if (onClick != null && chevron) iconImage(ctx, R.drawable.ic_chevron_right, p.label3) else null
        if (end != null) addView(end, if (trailing == null) LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)).apply { leftMargin = ctx.dp(Tokens.S8) }
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ctx.dp(Tokens.S8) })
        if (onClick != null) {
            isClickable = true; isFocusable = true
            foreground = rowRipple(ctx)
            setOnClickListener { onClick() }
        }
    }

    /** Switch: white thumb, green track when on, separator grey when off. */
    fun toggle(ctx: Context, checked: Boolean): Switch = Switch(ctx).apply {
        val p = c(ctx)
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(Tokens.WHITE, Tokens.WHITE))
        trackTintList = ColorStateList(states, intArrayOf(p.green, p.separator))
        if (Build.VERSION.SDK_INT >= 29) trackTintMode = android.graphics.PorterDuff.Mode.SRC
        minimumHeight = ctx.dp(Tokens.TOUCH_MIN)
    }

    // ---- Page scaffolding ----
    /** Full-screen page in the palette's background colour. */
    fun page(ctx: Context, content: View): FrameLayout = FrameLayout(ctx).apply {
        setBackgroundColor(c(ctx).bg)
        addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * Opaque bottom bar with a top hairline: [primary] on the right / full width, [secondary] beside it. Pads itself by
     * the navigation bar inset.
     */
    fun bottomActionBar(ctx: Context, primary: View, secondary: View? = null): LinearLayout = LinearLayout(ctx).apply {
        val p = c(ctx)
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(p.surface)
        addView(View(ctx).apply { setBackgroundColor(p.separator) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.hairline()))
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = ctx.dp(Tokens.S16)
            setPadding(ctx.dp(Tokens.SCREEN_MARGIN), pad, ctx.dp(Tokens.SCREEN_MARGIN), pad)
            if (secondary != null) addView(secondary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ctx.dp(Tokens.S12) })
            addView(primary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(row)
        insetBottom(row, Tokens.S16)
    }

    /** Pads [v] by the bottom system bar inset plus [baseDp] (horizontal padding is kept). */
    private fun insetBottom(v: View, baseDp: Int) {
        val base = v.context.dp(baseDp)
        v.setOnApplyWindowInsetsListener { view, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, base + b.bottom)
            }
            ins
        }
    }

    /** Static loading placeholder shaped like a library row (no shimmer). */
    fun skeletonRow(ctx: Context): View = LinearLayout(ctx).apply {
        val fill = c(ctx).surface2
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ctx.dp(72)
        setPadding(ctx.dp(Tokens.S16), ctx.dp(Tokens.S8), ctx.dp(Tokens.S16), ctx.dp(Tokens.S8))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(View(ctx).apply { background = rounded(fill, ctx.dpF(Tokens.R_THUMB.toFloat())) },
            LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56)).apply { rightMargin = ctx.dp(Tokens.S12) })
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(View(ctx).apply { background = rounded(fill, ctx.dpF(4f)) }, LinearLayout.LayoutParams(ctx.dp(140), ctx.dp(14)).apply { bottomMargin = ctx.dp(Tokens.S8) })
            addView(View(ctx).apply { background = rounded(fill, ctx.dpF(4f)) }, LinearLayout.LayoutParams(ctx.dp(90), ctx.dp(12)))
        })
    }

    /** Centred icon, title, one line of body and an optional action. */
    fun emptyState(ctx: Context, icon: Int, title: String, body: String, action: View? = null): LinearLayout = LinearLayout(ctx).apply {
        val p = c(ctx)
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(ctx.dp(Tokens.S24), ctx.dp(Tokens.S40), ctx.dp(Tokens.S24), ctx.dp(Tokens.S24))
        addView(iconImage(ctx, icon, p.label3), LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply { bottomMargin = ctx.dp(Tokens.S16) })
        addView(headline(ctx, title).apply { gravity = Gravity.CENTER }, wrapLp(Tokens.S8))
        addView(callout(ctx, body).apply { gravity = Gravity.CENTER }, wrapLp(if (action != null) Tokens.S24 else 0))
        if (action != null) addView(action, wrapLp(0))
    }

    private fun View.wrapLp(bottomDp: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = context.dp(bottomDp) }

    // ---- Sheet (replaces AlertDialog and hand-made dialog frames) ----
    /**
     * Bottom sheet over a scrim: surface colour, 20 dp top corners, a handle, then whatever [build] adds to the
     * vertical content column. Max 560 dp wide (centred in landscape). [cancelable] false = only the sheet's own buttons
     * close it.
     */
    fun sheet(act: Activity, cancelable: Boolean = true, build: (content: LinearLayout, dialog: Dialog) -> Unit): Dialog {
        val p = c(act)
        val dlg = Dialog(act)
        val r = act.dpF(Tokens.R_SHEET.toFloat())
        val content = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(p.surface); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
            setPadding(act.dp(Tokens.S24), act.dp(Tokens.S8), act.dp(Tokens.S24), act.dp(Tokens.S24))
            addView(View(act).apply { background = rounded(p.separator, act.dpF(3f)) },
                LinearLayout.LayoutParams(act.dp(36), act.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = act.dp(Tokens.S16) })
        }
        build(content, dlg)
        insetBottom(content, Tokens.S24)
        dlg.setContentView(content)
        dlg.setCancelable(cancelable)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(0))
            w.setGravity(Gravity.BOTTOM)
            val dm = act.resources.displayMetrics
            w.setLayout(min(dm.widthPixels, act.dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(((Tokens.SCRIM ushr 24) and 0xFF) / 255f)
        }
        dlg.show()
        return dlg
    }

    /** Title + body + actions in the standard sheet layout. [secondary] closes as "cancel" (the dialog's dismiss handler runs). */
    fun sheetActions(ctx: Context, primary: Button, secondary: Button?): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(primary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (secondary != null) addView(secondary, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(Tokens.S8) })
    }

    // ---- Helpers ----
    /** Gentle press feedback (scale 0.97) without consuming the touch. */
    fun pressScale(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(Tokens.MOTION_SHORT).setInterpolator(EASE_STANDARD).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(Tokens.MOTION_MEDIUM).setInterpolator(EASE_STANDARD).start()
            }
            false
        }
    }

    /** Status code name for logs only (never shown to the user). */
    fun statusName(st: Int): String = when (st) {
        Status.OK -> "OK"; Status.STATE -> "STATE"; Status.ARG -> "ARG"
        Status.IO -> "IO"; Status.POLICY -> "POLICY"; Status.FULL -> "FULL"
        else -> "status $st"
    }

    /** "m:ss" (timers use tabular figures). */
    fun mmss(ms: Long): String { val s = ms / 1000; return String.format(Locale.US, "%d:%02d", s / 60, s % 60) }

    fun dirSizeBytes(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun humanSize(b: Long): String = when {
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    /**
     * Pads [v] by the system bars/cutout (targetSdk 37 is edge-to-edge) plus [basePadDp]. [bottom] false leaves the
     * bottom edge to a [bottomActionBar] below it.
     */
    fun applyInsets(v: View, basePadDp: Int = Tokens.S12, bottom: Boolean = true) {
        val base = v.context.dp(basePadDp)
        val baseBottom = if (bottom) base else 0
        v.setPadding(base, base, base, baseBottom)
        v.setOnApplyWindowInsetsListener { view, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(base + b.left, base + b.top, base + b.right, if (bottom) base + b.bottom else 0)
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

    /** Camera screens only: hides the system bars (swipe brings them back briefly) so the preview is edge to edge. */
    fun immersive(a: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            val lp = a.window.attributes
            if (lp.layoutInDisplayCutoutMode != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                a.window.attributes = lp
            }
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

    /** Selectable text that keeps the whole value (long file paths). */
    fun pathText(ctx: Context, text: String): TextView = footnote(ctx, text).apply { setTextIsSelectable(true) }
}

/** Drawn status dot ([sizeDp] dp). [hollow] = a ring (not yet / unknown). */
class DotView(ctx: Context, private val sizeDp: Int = 8) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hollow = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(color: Int, hollow: Boolean = false) {
        if (paint.color == color && this.hollow == hollow) return
        paint.color = color
        this.hollow = hollow
        paint.style = if (hollow) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = 1.5f * resources.displayMetrics.density
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (sizeDp * resources.displayMetrics.density).roundToInt()
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f - (if (hollow) paint.strokeWidth / 2f else 0f)
        c.drawCircle(width / 2f, height / 2f, r, paint)
    }
}

/** Drawn 8 dp dot + words. [onGlass] = camera pill (glass body); else plain inline text. */
class StatusBadge(ctx: Context, private val onGlass: Boolean = false) : LinearLayout(ctx) {
    private val dot = DotView(ctx)
    private val label = UiKit.text(ctx, "", if (onGlass) Tokens.LABEL else Tokens.FOOTNOTE, UiKit.c(ctx).let { if (onGlass) it.label else it.label2 })
    private var key = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(dot, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = (Tokens.S8 * resources.displayMetrics.density).roundToInt() })
        addView(label)
        if (onGlass) {
            background = UiKit.glass(ctx, Tokens.GLASS_REGULAR, Tokens.R_PILL)
            val d = resources.displayMetrics.density
            setPadding((14 * d).roundToInt(), (10 * d).roundToInt(), (16 * d).roundToInt(), (10 * d).roundToInt())
            minimumHeight = (Tokens.TOUCH_MIN * d).roundToInt()
        }
    }

    /** No-op when unchanged. [hollow] draws a ring instead of a filled dot (e.g. "not checked"). */
    fun set(text: String, kind: UiKit.Kind, hollow: Boolean = false) {
        val k = "$text|$kind|$hollow"
        if (k == key) return
        key = k
        label.text = text
        dot.set(UiKit.hue(UiKit.c(context), kind), hollow)
    }

    var maxTextWidth: Int
        get() = label.maxWidth
        set(v) { label.maxWidth = v; label.maxLines = 1; label.ellipsize = android.text.TextUtils.TruncateAt.END }
}

/**
 * One-line alert with a status icon, the message and an optional text action. Replaces the old solid banners and
 * alert cards. [onGlass] = camera HUD (thick glass, dark); else an opaque surface card.
 */
class InlineAlert(ctx: Context, private val onGlass: Boolean = false) : LinearLayout(ctx) {
    private val p = UiKit.c(ctx)
    private val icon = ImageView(ctx).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val titleView = UiKit.headline(ctx, "").apply { visibility = GONE }
    private val message = UiKit.callout(ctx, "", p.label)
    val action: Button = UiKit.textButton(ctx, "")
    private var key: String? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).roundToInt()
        background = if (onGlass) UiKit.glass(ctx, Tokens.GLASS_THICK, Tokens.R_CARD) else UiKit.surface(ctx)
        setPadding(px(Tokens.CARD_PAD), px(Tokens.S12), px(Tokens.S8), px(Tokens.S12))
        addView(icon, LayoutParams(px(24), px(24)).apply { rightMargin = px(Tokens.S12) })
        addView(LinearLayout(ctx).apply {
            orientation = VERTICAL
            addView(titleView)
            addView(message)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = px(Tokens.S8) })
        visibility = GONE
    }

    /** Shows [msg] (null hides). Unchanged content allocates nothing. */
    fun set(msg: String?, kind: UiKit.Kind, actionLabel: String? = null, onAction: (() -> Unit)? = null, title: String? = null) {
        if (msg == null) { visibility = GONE; key = null; return }
        if (onAction != null) action.setOnClickListener { onAction() }
        val k = "$title|$msg|$kind|$actionLabel|${onAction != null}"
        if (k == key && visibility == VISIBLE) return
        key = k
        icon.setImageResource(when (kind) {
            UiKit.Kind.OK -> R.drawable.ic_check_circle
            UiKit.Kind.WARN -> R.drawable.ic_warning
            UiKit.Kind.ERROR -> R.drawable.ic_error
            UiKit.Kind.INFO -> R.drawable.ic_check_circle
        })
        icon.imageTintList = ColorStateList.valueOf(UiKit.hue(p, kind))
        titleView.text = title ?: ""
        titleView.visibility = if (title == null) GONE else VISIBLE
        message.text = msg
        message.setTextColor(if (title == null) p.label else p.label2)
        action.visibility = if (onAction != null) VISIBLE else GONE
        if (onAction != null) action.text = actionLabel ?: "Retry"
        contentDescription = (if (kind == UiKit.Kind.ERROR) "Problem: " else if (kind == UiKit.Kind.WARN) "Warning: " else "") + (title?.let { "$it. " } ?: "") + msg
        visibility = VISIBLE
    }
}

/** Drawn determinate bar: track in surface2 (or separator on glass), fill in label. [fraction] in 0..1. */
class LinearProgress(ctx: Context) : View(ctx) {
    private val p = UiKit.c(ctx)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.separator }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.label }
    private val rect = RectF()

    var fraction = 0f
        set(v) { val f = v.coerceIn(0f, 1f); if (f != field) { field = f; invalidate() } }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (4 * resources.displayMetrics.density).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, r, r, track)
        if (fraction > 0f) { rect.right = width * fraction; c.drawRoundRect(rect, r, r, fill) }
    }
}

/** Camera shutter with a distinct recording state, touch animation, and haptic feedback. */
class ShutterButton(ctx: Context) : Button(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    var isRecording = false
        set(value) {
            field = value
            contentDescription = if (value) "Finish Scan" else "Start Scan"
            invalidate()
        }

    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = Tokens.withAlpha(Tokens.WHITE, 0xF2)
    }

    /** Record red: the only colour on the capture deck. */
    private val recordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Tokens.DARK.red
    }

    private val rectF = RectF()

    init {
        background = null
        text = ""
        isClickable = true
        isFocusable = true
        contentDescription = "Start Scan"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(Tokens.MOTION_SHORT).start()
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1.0f).scaleY(1.0f).setDuration(Tokens.MOTION_SHORT).start()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerRadius = (min(width, height) / 2f) - dp(2f)
        canvas.drawCircle(cx, cy, outerRadius, outerPaint)
        if (!isRecording) {
            canvas.drawCircle(cx, cy, outerRadius - dp(7f), recordPaint)
        } else {
            val halfSize = dp(13f)
            val cornerRadius = dp(6f)
            rectF.set(cx - halfSize, cy - halfSize, cx + halfSize, cy + halfSize)
            canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, recordPaint)
        }
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
