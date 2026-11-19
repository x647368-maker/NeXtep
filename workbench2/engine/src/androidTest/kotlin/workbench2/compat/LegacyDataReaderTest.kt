package workbench2.compat

import org.junit.Assert.*
import org.junit.Test

class LegacyDataReaderTest {
    private val fixture = """{"active":"group-a","switching":false,"groups":[{"version":1,"id":"group-a","name":"示例","main":{"taskId":12,"userId":0,"component":"example.app/.Main","future_member":true},"slots":[null,{"taskId":19,"userId":999,"component":"example.app/.Main"},null,null],"depth_order":[3,1,2,0],"future_group":{"v":2}}],"future_root":[1,2]}"""

    @Test fun legacySettingsPreserveTypesOrderAndUnknownFields() {
        val hidden = linkedSetOf("user0:example.app","user999:example.app")
        val values = linkedMapOf<String, Any>("top_apps" to "one.app/.Main\ntwo.app/.Main\none.app/.Main",
            "frost_strength" to 123, "four_finger_gesture_blacklist" to hidden,
            "future_long" to 9007199254740993L, "future_float" to 1.25f, "top_title" to " A ")
        val snapshot=LegacyDataReader.settings(values)
        hidden.clear(); values.clear()
        assertEquals(listOf("one.app/.Main","two.app/.Main","one.app/.Main"),snapshot.topApps)
        assertEquals(123,snapshot.original["frost_strength"])
        assertEquals(9007199254740993L,snapshot.original["future_long"])
        assertEquals(1.25f,snapshot.original["future_float"])
        assertEquals(" A ",snapshot.original["top_title"])
        assertEquals(setOf("user0:example.app","user999:example.app"),snapshot.original["four_finger_gesture_blacklist"])
        assertFalse(snapshot.original.containsKey("manual_app_order"))
        assertNull(LegacyDataReader.settings(emptyMap<String,Any>()).topApps)
    }

    @Test fun legacySettingsRejectWrongTypesAndOversize() {
        rejected { LegacyDataReader.settings(mapOf("frost_strength" to "50")) }
        rejected { LegacyDataReader.settings(mapOf("top_apps" to 1)) }
        rejected { LegacyDataReader.settings(mapOf("four_finger_gesture_blacklist" to setOf(1))) }
        rejected { LegacyDataReader.settings(mapOf("unknown" to listOf("x"))) }
        rejected { LegacyDataReader.settings(mapOf("unknown" to "x".repeat(1_048_577))) }
    }

    @Test fun legacyGroupsPreserveSlotsProfilesAndRawExtensions() {
        val s=LegacyDataReader.groups(fixture)
        assertEquals(fixture,s.originalJson)
        assertEquals("group-a",s.activeId)
        assertTrue(s.readyForPlanning)
        val g=s.entries.single()
        assertEquals("示例",g.name)
        assertEquals(0,g.main!!.userId)
        assertEquals(999,g.slots[1]!!.userId)
        assertEquals(19,g.slots[1]!!.historicalTaskId)
        assertEquals(g.main!!.component,g.slots[1]!!.component)
        assertNull(g.slots[0]); assertNull(g.slots[2]); assertNull(g.slots[3])
        assertEquals(listOf(3,1,2,0),g.depthOrder)
    }

    @Test fun legacyGroupsMissingIdAndSwitchingStayUnready() {
        val old=fixture.replace("\"active\":\"group-a\",","").replace("\"id\":\"group-a\",","")
            .replace(",\"depth_order\":[3,1,2,0]","")
        val s=LegacyDataReader.groups(old)
        assertNull(s.activeId); assertNull(s.entries.single().id)
        assertEquals(listOf(0,1,2,3),s.entries.single().depthOrder)
        assertEquals(old,s.originalJson)
        assertFalse(s.readyForPlanning)
        assertFalse(LegacyDataReader.groups(fixture.replace("\"switching\":false","\"switching\":true")).readyForPlanning)
        assertTrue(LegacyDataReader.groups("""{"switching":false,"groups":[]}""").entries.isEmpty())
    }

    @Test fun legacyGroupsRejectInvalidWithoutPartialResult() {
        for (bad in listOf(fixture.dropLast(1),fixture+" {}",fixture.replace("\"version\":1","\"version\":2"),
            fixture.replace("\"userId\":999","\"userId\":\"999\""),
            fixture.replace("\"userId\":999","\"userId\":-1"),
            fixture.replace("\"taskId\":12","\"taskId\":12.0"),
            fixture.replace("[3,1,2,0]","[3,1,1,0]"),
            fixture.replace("null,null]","null]"),
            fixture.replace("\"active\":\"group-a\"","\"active\":\"missing\""),
            fixture.replace("\"switching\":false","\"switching\":false,\"switching\":true"),
            fixture.replace("\"main\":{","\"main\":{\"userId\":7,"),
            """{"switching":false,"groups":[],"future":"""+"[".repeat(30)+"0"+"]".repeat(30)+"}")) {
            rejected { LegacyDataReader.groups(bad) }
        }
    }

    private fun rejected(block: ()->Unit) {
        try { block(); fail("Invalid source was accepted") } catch (_: IllegalArgumentException) { }
    }
}
