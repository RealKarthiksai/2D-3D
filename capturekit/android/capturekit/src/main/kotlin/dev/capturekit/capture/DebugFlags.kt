package dev.capturekit.capture

/**
 * Debug-build-only feature overrides (U11-A): `adb shell setprop debug.capturekit.understanding 0|1|2` and
 * `debug.capturekit.detector 0|1`. The host reads the properties ONLY in a debuggable build and passes the raw strings
 * here; an unset or unparsable value means "no override".
 *
 * Defaults (ADR 0030): the 2D detector is ON in every build (it still needs the model asset, scripts/fetch_models.sh),
 * and when it will run and nothing overrides understanding, the session gets `guide.understanding = 1` so detections
 * are lifted to 3D objects (mini-map, review counts, /guide/labels). Gap steering (level 2) stays opt-in (ADR 0018 item 7).
 */
object DebugFlags {
    const val PROP_UNDERSTANDING = "debug.capturekit.understanding"
    const val PROP_DETECTOR = "debug.capturekit.detector"

    /** Understanding level when the detector runs and no property overrides it: objects only, no gap steering. */
    const val DEFAULT_UNDERSTANDING_WITH_DETECTOR = 1

    /** 0 = off, 1 = objects, 2 = objects + gap steering (core `guide.understanding`); null = property unset / blank / junk / out of range. */
    fun understandingLevel(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it in 0..2 }

    /** The detector is on unless the property is exactly "0" (unset, blank or junk keep the default: on). */
    fun detectorOn(raw: String?): Boolean = raw?.trim() != "0"

    /**
     * Understanding level to pass: the [override] when set (including 0), else [DEFAULT_UNDERSTANDING_WITH_DETECTOR]
     * when [detectorWillRun], else null (no key: the core default, off). Without a detector, level 1 would only add
     * geometry-only outputs, so the session is left exactly as before.
     */
    fun understandingFor(override: Int?, detectorWillRun: Boolean): Int? =
        override ?: if (detectorWillRun) DEFAULT_UNDERSTANDING_WITH_DETECTOR else null

    /** Extra session config for [SessionManager.start]: the key is passed explicitly whenever a level is given (including 0); null = no key. */
    fun extraConfig(understanding: Int?): Map<String, Any> =
        if (understanding != null && understanding in 0..2) mapOf("guide.understanding" to understanding) else emptyMap()
}
