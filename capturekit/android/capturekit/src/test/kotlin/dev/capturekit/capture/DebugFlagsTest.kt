package dev.capturekit.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugFlagsTest {
    @Test fun understandingLevelParsing() {
        assertNull(DebugFlags.understandingLevel(null))
        assertNull(DebugFlags.understandingLevel(""))
        assertEquals(0, DebugFlags.understandingLevel("0"))
        assertEquals(1, DebugFlags.understandingLevel("1"))
        assertEquals(2, DebugFlags.understandingLevel(" 2\n"))
        assertNull(DebugFlags.understandingLevel("3"))
        assertNull(DebugFlags.understandingLevel("on"))
        assertNull(DebugFlags.understandingLevel("-1"))
    }

    @Test fun detectorIsOnByDefaultAndOnlyZeroTurnsItOff() {
        assertTrue(DebugFlags.detectorOn(null)); assertTrue(DebugFlags.detectorOn("")); assertTrue(DebugFlags.detectorOn("1"))
        assertTrue(DebugFlags.detectorOn(" 1\n")); assertTrue(DebugFlags.detectorOn("true")); assertTrue(DebugFlags.detectorOn("junk"))
        assertFalse(DebugFlags.detectorOn("0")); assertFalse(DebugFlags.detectorOn(" 0\n"))
    }

    @Test fun understandingDefaultsToObjectsOnlyWhenTheDetectorRuns() {
        assertEquals(1, DebugFlags.understandingFor(null, detectorWillRun = true))
        assertNull(DebugFlags.understandingFor(null, detectorWillRun = false))   // no detector: session unchanged (no key)
        // An explicit property always wins, including 0 (off) and 2 (gap steering), with or without the detector.
        assertEquals(0, DebugFlags.understandingFor(0, detectorWillRun = true))
        assertEquals(2, DebugFlags.understandingFor(2, detectorWillRun = true))
        assertEquals(2, DebugFlags.understandingFor(2, detectorWillRun = false))
        assertEquals(mapOf<String, Any>("guide.understanding" to 1), DebugFlags.extraConfig(DebugFlags.understandingFor(null, true)))
        assertEquals(emptyMap<String, Any>(), DebugFlags.extraConfig(DebugFlags.understandingFor(null, false)))
    }

    @Test fun extraConfigIsPassedExplicitlyWhenSetAndEmptyWhenUnset() {
        assertEquals(emptyMap<String, Any>(), DebugFlags.extraConfig(null))
        assertEquals(mapOf<String, Any>("guide.understanding" to 0), DebugFlags.extraConfig(0))
        assertEquals(mapOf<String, Any>("guide.understanding" to 1), DebugFlags.extraConfig(1))
        assertEquals(mapOf<String, Any>("guide.understanding" to 2), DebugFlags.extraConfig(2))
    }
}
