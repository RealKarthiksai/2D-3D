package dev.capturekit.sample

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Design tokens (ADR 0036): the only place colour literals live. Pure Kotlin (no android.*) so the JVM tests can check
 * contrast and hue on the raw ARGB ints. Colour carries meaning only (status); everything else is grey on grey.
 */
object Tokens {
    data class Palette(
        val bg: Int, val surface: Int, val surface2: Int, val separator: Int,
        val label: Int, val label2: Int, val label3: Int,
        val primaryFill: Int, val onPrimary: Int,
        val danger: Int, val green: Int, val orange: Int, val red: Int,
    )

    val DARK = Palette(
        bg = 0xFF000000.toInt(), surface = 0xFF1C1C1E.toInt(), surface2 = 0xFF2C2C2E.toInt(), separator = 0xFF38383A.toInt(),
        label = 0xFFF5F5F7.toInt(), label2 = 0xFFAEAEB2.toInt(), label3 = 0xFF8E8E93.toInt(),
        primaryFill = 0xFFF5F5F7.toInt(), onPrimary = 0xFF000000.toInt(),
        danger = 0xFFFF6165.toInt(), green = 0xFF30D158.toInt(), orange = 0xFFFF9230.toInt(), red = 0xFFFF4245.toInt(),
    )

    /** label3 and danger were stepped toward label by 0x08 per channel (from #737378 / #E9152D) to reach 4.5:1 on bg. */
    val LIGHT = Palette(
        bg = 0xFFF2F2F7.toInt(), surface = 0xFFFFFFFF.toInt(), surface2 = 0xFFE9E9EE.toInt(), separator = 0xFFD1D1D6.toInt(),
        label = 0xFF1D1D1F.toInt(), label2 = 0xFF6E6E73.toInt(), label3 = 0xFF6B6B70.toInt(),
        primaryFill = 0xFF1D1D1F.toInt(), onPrimary = 0xFFFFFFFF.toInt(),
        danger = 0xFFD91D1F.toInt(), green = 0xFF34C759.toInt(), orange = 0xFFFF9500.toInt(), red = 0xFFFF383C.toInt(),
    )

    // ---- Glass (camera screen only; always over the dark camera palette) ----
    private const val GLASS_RGB = 0x1C1C1E
    /** Non-text chrome only (thin glass fails text contrast over a white wall). */
    const val GLASS_THIN = 0x73
    const val GLASS_REGULAR = 0xAD
    const val GLASS_THICK = 0xD1
    const val HAIRLINE = 0x24FFFFFF
    /** Scrim behind a sheet. */
    const val SCRIM = 0x66000000
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    fun glass(alpha: Int): Int = (alpha shl 24) or GLASS_RGB

    // ---- Type ----
    data class TypeStyle(val sp: Float, val lineSp: Float, val weight: Int)
    val LARGE_TITLE = TypeStyle(32f, 40f, 600)
    val TITLE = TypeStyle(24f, 30f, 600)
    val HEADLINE = TypeStyle(17f, 22f, 600)
    val BODY = TypeStyle(16f, 24f, 400)
    val CALLOUT = TypeStyle(14f, 20f, 400)
    val FOOTNOTE = TypeStyle(12f, 16f, 400)
    val LABEL = TypeStyle(14f, 20f, 500)
    val BUTTON = TypeStyle(16f, 20f, 500)

    // ---- Spacing (dp, 4 dp grid) ----
    const val S4 = 4; const val S8 = 8; const val S12 = 12; const val S16 = 16
    const val S20 = 20; const val S24 = 24; const val S32 = 32; const val S40 = 40
    const val SCREEN_MARGIN = S20
    const val CARD_PAD = S16
    const val SECTION_GAP = S32
    const val ROW_MIN = 56
    const val TOUCH_MIN = 48

    // ---- Radius (dp) ----
    const val R_CHIP = 8
    const val R_THUMB = 12
    const val R_CARD = 16
    const val R_SHEET = 20
    const val R_PILL = 999

    // ---- Motion ----
    const val MOTION_SHORT = 100L
    const val MOTION_MEDIUM = 200L
    const val MOTION_LONG = 300L
    /** Cubic-bezier control points (x1, y1, x2, y2). */
    val EASE_STANDARD = floatArrayOf(0.2f, 0f, 0f, 1f)
    val EASE_EMPHASIZED_DECELERATE = floatArrayOf(0.05f, 0.7f, 0.1f, 1f)

    // ---- Object inks (maps and camera boxes): muted greys, never a status hue ----
    private const val INK_SEATING = 0xFFA1A1AA.toInt()
    private const val INK_TABLE = 0xFFB5A48C.toInt()
    private const val INK_BED = 0xFFA89F9A.toInt()
    private const val INK_SCREEN = 0xFF8E8E93.toInt()
    private const val INK_PLANT = 0xFF9AA58C.toInt()
    private const val INK_FIXTURE = 0xFFC7C7CC.toInt()
    private const val INK_STORAGE = 0xFFA1887F.toInt()
    private const val INK_LAMP = 0xFFD6CFA8.toInt()
    private const val INK_OTHER = 0xFFB0B0B5.toInt()
    val OBJECT_INKS = intArrayOf(INK_SEATING, INK_TABLE, INK_BED, INK_SCREEN, INK_PLANT, INK_FIXTURE, INK_STORAGE, INK_LAMP, INK_OTHER)

    fun objectInk(coreName: String): Int = when (coreName) {
        "chair", "couch", "bench" -> INK_SEATING
        "dining_table", "desk" -> INK_TABLE
        "bed" -> INK_BED
        "tv", "laptop" -> INK_SCREEN
        "potted_plant" -> INK_PLANT
        "toilet", "sink", "refrigerator", "oven" -> INK_FIXTURE
        "shelf", "cabinet" -> INK_STORAGE
        "lamp" -> INK_LAMP
        else -> INK_OTHER
    }

    // ---- Colour math on raw ARGB ints ----
    fun withAlpha(c: Int, alpha: Int): Int = (c and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** [fg] composited over the opaque [bg] (alpha taken from [fg]). */
    fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) and 0xFF
        fun ch(sh: Int): Int {
            val f = (fg shr sh) and 0xFF; val b = (bg shr sh) and 0xFF
            return ((f * a + b * (255 - a)) / 255.0).roundToInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** WCAG relative luminance of the opaque colour [c]. */
    fun luminance(c: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** HSV hue in degrees [0, 360) and saturation [0, 1]. */
    fun hueSat(c: Int): Pair<Float, Float> {
        val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val dlt = mx - mn
        val sat = if (mx == 0f) 0f else dlt / mx
        if (dlt == 0f) return 0f to sat
        val h = when (mx) {
            r -> 60f * (((g - b) / dlt) % 6f)
            g -> 60f * ((b - r) / dlt + 2f)
            else -> 60f * ((r - g) / dlt + 4f)
        }
        return (if (h < 0f) h + 360f else h) to sat
    }
}
