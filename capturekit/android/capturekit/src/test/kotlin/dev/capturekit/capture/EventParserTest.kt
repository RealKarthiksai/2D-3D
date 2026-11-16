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
    fun instructionEvent() {
        val e = EventParser.parseLine(
            """{"haptic":"tap","id":7,"instruction":"MOVE_TO","long_text":"Walk ahead about 2 meters.","msg_id":"move.to","overlay_hint":"arrow:10:2","params":{"distance_m":2,"rel_bearing_deg":10},"priority":2,"speak_text":"Walk ahead, 2 meters.","t_ns":5,"type":"instruction"}""",
        ) as CoreEvent.Instruction
        assertEquals(7, e.id); assertEquals("MOVE_TO", e.type); assertEquals("Walk ahead, 2 meters.", e.speakText)
        assertEquals(2, e.priority); assertEquals("tap", e.haptic); assertEquals("arrow:10:2", e.overlayHint)
    }

    @Test
    fun overlayEvent() {
        val e = EventParser.parseLine(
            """{"ceiling_found":false,"completeness":0.35,"floor_found":true,"has_target":true,"mode":"target","seen":0.8627,"surfaces":"1.25,4.00,3.65,4.00,86;-0.5,0.00,-0.5,3.20,12","t_ns":4999999950,"target_x":2.55,"target_z":3.05,"tracking":1,"type":"overlay","user_x":2.47,"user_yaw_deg":-2,"user_z":3.05,"wall_found":true,"walls":2,"why_id":"why.left","why_text":"Left wall not scanned yet"}""",
        ) as CoreEvent.Overlay
        assertEquals(0.35, e.completeness, 1e-9); assertEquals(0.8627, e.seen, 1e-9)
        assertEquals(1, e.tracking); assertEquals("target", e.mode)
        assertTrue(e.wallFound && e.floorFound && !e.ceilingFound); assertEquals(2, e.walls)
        assertEquals(2.47, e.userX, 1e-9); assertEquals(3.05, e.userZ, 1e-9); assertEquals(-2.0, e.userYawDeg, 1e-9)
        assertTrue(e.hasTarget); assertEquals(2.55, e.targetX, 1e-9); assertEquals(3.05, e.targetZ, 1e-9)
        assertEquals("why.left", e.whyId); assertEquals("Left wall not scanned yet", e.whyText)
        assertEquals(listOf(WallSeg(1.25, 4.0, 3.65, 4.0, 86), WallSeg(-0.5, 0.0, -0.5, 3.2, 12)), e.surfaces)
    }

    @Test
    fun overlayEmptySurfacesAndMalformed() {
        val e = EventParser.parseLine(
            """{"ceiling_found":false,"completeness":0,"floor_found":false,"has_target":false,"mode":"sweep","seen":0,"surfaces":"","t_ns":0,"target_x":0,"target_z":0,"tracking":1,"type":"overlay","user_x":2.5,"user_yaw_deg":0,"user_z":2,"wall_found":false,"walls":0,"why_id":"why.sweep","why_text":"Looking for walls: sweep slowly"}""",
        ) as CoreEvent.Overlay
        assertTrue(e.surfaces.isEmpty()); assertTrue(!e.hasTarget); assertEquals("sweep", e.mode)
        assertEquals(listOf(WallSeg(1.0, 2.0, 3.0, 4.0, 5)), EventParser.parseSurfaces("1,2,3,4,5;bad;1,2,3;x,y,z,w,v;"))
    }

    @Test
    fun overlayV2SixFieldSurfacesAndYawMask() {
        val e = EventParser.parseLine(
            """{"ceiling_found":false,"completeness":0,"floor_found":false,"has_target":false,"mode":"sweep","seen":0,"surfaces":"1.25,4.00,3.65,4.00,86,2;-0.5,0.00,-0.5,3.20,12,1","t_ns":0,"target_x":0,"target_z":0,"tracking":1,"type":"overlay","user_x":2.5,"user_yaw_deg":0,"user_z":2,"wall_found":true,"walls":2,"why_id":"why.sweep","why_text":"x","yaw_mask":2049}""",
        ) as CoreEvent.Overlay
        assertEquals(listOf(WallSeg(1.25, 4.0, 3.65, 4.0, 86, 2), WallSeg(-0.5, 0.0, -0.5, 3.2, 12, 1)), e.surfaces)
        assertEquals(2049, e.yawMask)
        // Mixed 5- and 6-field entries and a 7-field entry: 5 -> id 0, 6 -> id kept, 7 skipped.
        assertEquals(
            listOf(WallSeg(1.0, 2.0, 3.0, 4.0, 5, 0), WallSeg(1.0, 2.0, 3.0, 4.0, 5, 7)),
            EventParser.parseSurfaces("1,2,3,4,5;1,2,3,4,5,7;1,2,3,4,5,7,9"),
        )
        // Old core: no yaw_mask key -> 0, and 5-field surfaces still parse (v1 back-compat).
        val old = EventParser.parseLine(
            """{"completeness":0,"mode":"sweep","surfaces":"1,2,3,4,5","tracking":1,"type":"overlay","why_id":"a","why_text":"b"}""",
        ) as CoreEvent.Overlay
        assertEquals(0, old.yawMask)
        assertEquals(listOf(WallSeg(1.0, 2.0, 3.0, 4.0, 5)), old.surfaces)
        // Out-of-range mask bits are dropped.
        val big = EventParser.parseLine("""{"type":"overlay","surfaces":"","yaw_mask":65535}""") as CoreEvent.Overlay
        assertEquals(0xFFF, big.yawMask)
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
