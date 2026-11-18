package dev.capturekit.capture

/**
 * Debug-build-only feature switches (U11-A): `adb shell setprop debug.capturekit.understanding 0|1|2` and
 * `debug.capturekit.detector 1`. The host reads the properties ONLY in a debuggable build and passes the raw strings
 * here; an unset or unparsable value means "no override", so release builds and unset properties behave exactly as before.
 */
object DebugFlags {
    const val PROP_UNDERSTANDING = "debug.capturekit.understanding"
    const val PROP_DETECTOR = "debug.capturekit.detector"

    /** 0 = off, 1 = objects, 2 = objects + gap steering (core `guide.understanding`); null = property unset / blank / junk / out of range. */
    fun understandingLevel(raw: String?): Int? = raw?.trim()?.toIntOrNull()?.takeIf { it in 0..2 }

    /** Only "1" turns the 2D detector on. */
    fun detectorOn(raw: String?): Boolean = raw?.trim() == "1"

    /** Extra session config for [SessionManager.start]: the key is passed explicitly whenever the property is set (including 0); unset = no key. */
    fun extraConfig(understanding: Int?): Map<String, Any> =
        if (understanding != null && understanding in 0..2) mapOf("guide.understanding" to understanding) else emptyMap()
}
