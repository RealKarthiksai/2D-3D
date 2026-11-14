package dev.capturekit.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventParserTest {
    @Test
    fun keyframeRequest() {
        val e = EventParser.parseLine("""{"file":"keyframes/000012.jpg","frame_id":42,"t_ns":123456,"type":"keyframe_request"}""")
        assertEquals(CoreEvent.KeyframeRequest(42, "keyframes/000012.jpg"), e)
    }

    @Test
    fun multiLineAndUnknown() {
        val text = """{"type":"state","t_ns":1,"from":"IDLE","to":"CALIBRATING","reason":"start"}
{"type":"governor","t_ns":2,"from":"NOMINAL","to":"LOWER_RESOLUTION","reason":"thermal=2"}
{"type":"mystery","x":1}
not json
"""
        val ev = EventParser.parse(text)
        assertEquals(3, ev.size)
        assertEquals(CoreEvent.StateChange("IDLE", "CALIBRATING", "start"), ev[0])
        assertEquals(CoreEvent.Governor("NOMINAL", "LOWER_RESOLUTION", "thermal=2"), ev[1])
        assertTrue(ev[2] is CoreEvent.Other)
    }

    @Test
    fun frameQualityAndDrops() {
        val q = EventParser.parseLine("""{"type":"frame_quality","t_ns":5,"frame_id":7,"quality":0.0,"hard_reject":true,"reasons":["MOTION_BLUR","LOW_TEXTURE"]}""")
            as CoreEvent.FrameQuality
        assertEquals(7, q.frameId); assertTrue(q.hardReject); assertEquals(listOf("MOTION_BLUR", "LOW_TEXTURE"), q.reasons)
        val d = EventParser.parseLine("""{"type":"ingest_drop","t_ns":9,"frame":3,"gyro":10}""") as CoreEvent.IngestDrop
        assertEquals(mapOf("gyro" to 10L, "frame" to 3L), d.counts)
        assertNull(EventParser.parseLine("""{"no_type":1}"""))
    }

    @Test
    fun policyDecode() {
        val p = Policy.from(doubleArrayOf(3.0, 0.5, 0.25, 1.0, 0.0, 0.0, 0.0))
        assertEquals("LOWER_RESOLUTION", p.levelName)
        assertTrue(!p.overlay && !p.upload && !p.mustPause)
        assertTrue(Policy.from(doubleArrayOf(5.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0)).mustPause)
    }

    @Test
    fun configJsonEscapes() {
        assertEquals("""{"a":1,"b":"x\"y","c":true}""", SessionManager.toJson(linkedMapOf("a" to 1, "b" to "x\"y", "c" to true)))
    }
}
