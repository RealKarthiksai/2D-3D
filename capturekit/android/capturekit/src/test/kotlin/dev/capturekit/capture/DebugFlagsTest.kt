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

    @Test fun detectorOnlyForOne() {
        assertTrue(DebugFlags.detectorOn("1")); assertTrue(DebugFlags.detectorOn(" 1\n"))
        assertFalse(DebugFlags.detectorOn(null)); assertFalse(DebugFlags.detectorOn("")); assertFalse(DebugFlags.detectorOn("0")); assertFalse(DebugFlags.detectorOn("true"))
    }

    @Test fun extraConfigIsPassedExplicitlyWhenSetAndEmptyWhenUnset() {
        assertEquals(emptyMap<String, Any>(), DebugFlags.extraConfig(null))
        assertEquals(mapOf<String, Any>("guide.understanding" to 0), DebugFlags.extraConfig(0))
        assertEquals(mapOf<String, Any>("guide.understanding" to 1), DebugFlags.extraConfig(1))
        assertEquals(mapOf<String, Any>("guide.understanding" to 2), DebugFlags.extraConfig(2))
    }
}
