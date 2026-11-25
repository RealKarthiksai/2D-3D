package dev.capturekit.sample

import org.junit.Assert.assertTrue
import org.junit.Test

/** Every text token is readable (WCAG 4.5:1) on bg and surface in both modes, and no token is teal/cyan or violet. */
class TokensTest {
    private val palettes = mapOf("dark" to Tokens.DARK, "light" to Tokens.LIGHT)

    @Test fun textTokensReachAaOnBgAndSurface() {
        for ((mode, p) in palettes) for ((name, fg) in listOf("label" to p.label, "label2" to p.label2, "label3" to p.label3, "danger" to p.danger))
            for ((bgName, bg) in listOf("bg" to p.bg, "surface" to p.surface)) {
                val cr = Tokens.contrast(fg, bg)
                assertTrue("$mode $name on $bgName = $cr", cr >= 4.5)
            }
    }

    @Test fun primaryButtonTextReachesAa() {
        for ((mode, p) in palettes) assertTrue(mode, Tokens.contrast(p.onPrimary, p.primaryFill) >= 4.5)
    }

    @Test fun whiteTextOnRegularGlassOverAWhiteWall() {
        val glass = Tokens.over(Tokens.glass(Tokens.GLASS_REGULAR), Tokens.WHITE)
        val cr = Tokens.contrast(Tokens.WHITE, glass)
        assertTrue("white on regular glass over white = $cr", cr >= 4.5)
        val thick = Tokens.over(Tokens.glass(Tokens.GLASS_THICK), Tokens.WHITE)
        assertTrue(Tokens.contrast(Tokens.DARK.label, thick) >= 4.5)
    }

    @Test fun noBannedHues() {
        val all = palettes.values.flatMap { p ->
            listOf(p.bg, p.surface, p.surface2, p.separator, p.label, p.label2, p.label3, p.primaryFill, p.onPrimary, p.danger, p.green, p.orange, p.red)
        } + Tokens.OBJECT_INKS.toList() + listOf(Tokens.glass(Tokens.GLASS_REGULAR), Tokens.HAIRLINE)
        for (c in all) {
            val (h, s) = Tokens.hueSat(c)
            val banned = s > 0.2f && (h in 150f..200f || h in 250f..310f)
            assertTrue("#%08X hue %.0f sat %.2f".format(c, h, s), !banned)
        }
    }

    @Test fun everyObjectClassUsesAnObjectInk() {
        for (name in listOf("chair", "couch", "bench", "dining_table", "desk", "bed", "tv", "laptop", "potted_plant",
                "toilet", "sink", "refrigerator", "oven", "shelf", "cabinet", "lamp", "unknown_new_class"))
            assertTrue(name, Tokens.objectInk(name) in Tokens.OBJECT_INKS)
    }
}
