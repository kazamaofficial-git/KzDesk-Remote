package com.kazamatech.kzdesk.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerTemplateTest {
    private var n = 0
    private fun parse(raw: String?) = ControllerTemplates.parse(raw, newId = { "id${n++}" }, now = { 42L })
    private fun ok(raw: String?) = parse(raw) as TemplateParse.Ok

    @Test fun roundTripKeepsEverythingIncludingJoystickSettings() {
        val t = ControllerTemplates.fpsTemplate()
        val back = ok(ControllerTemplates.toJson(t)).templates.single()
        assertEquals(t.name, back.name)
        assertEquals(t.keys.size, back.keys.size)
        val look = back.keys.first { it.id == "fps_look" }
        assertEquals(HudStickMode.POINTER, look.stickMode)
        assertEquals(1.6f, look.stickSens, 1e-4f)
        assertEquals(HudStickMode.WASD, back.keys.first { it.id == "fps_move" }.stickMode)
    }

    @Test fun jsonDeclaresFormatAndSchema() {
        val o = JSONObject(ControllerTemplates.toJson(ControllerTemplates.fpsTemplate()))
        assertEquals("kzdesk.controller-template", o.getString("format"))
        assertEquals(1, o.getInt("schema"))
    }

    @Test fun acceptsLegacyLayoutExportAndBareArray() {
        val keys = HudKey.defaults()
        val legacy = HudKey.exportLayoutJson(keys)
        assertEquals(keys.size, ok(legacy).templates.single().keys.size)
        assertEquals(keys.size, ok(HudKey.encode(keys)).templates.single().keys.size)
    }

    @Test fun rejectsGarbageEmptyAndOversize() {
        assertTrue(parse(null) is TemplateParse.Error)
        assertTrue(parse("   ") is TemplateParse.Error)
        assertTrue(parse("not json") is TemplateParse.Error)
        assertTrue(parse("{\"keys\":[]}") is TemplateParse.Error)
        assertTrue(parse("{\"keys\":[{\"id\":\"x\",\"kind\":\"NOPE\"}]}") is TemplateParse.Error)
        assertTrue(parse("[" + "1,".repeat(200_000) + "1]") is TemplateParse.Error)
    }

    @Test fun newerSchemaIsRefused() {
        val e = parse("{\"schema\":99,\"keys\":[{\"id\":\"a\",\"kind\":\"MOUSE_LEFT\"}]}")
        assertTrue(e is TemplateParse.Error)
    }

    @Test fun unknownKeysAreSkippedAndCounted() {
        val r = ok("{\"keys\":[{\"id\":\"a\",\"kind\":\"MOUSE_LEFT\"},{\"id\":\"b\",\"kind\":\"BOGUS\"},{\"kind\":\"KEY\"}]}")
        assertEquals(1, r.templates.single().keys.size)
        assertEquals(2, r.skippedKeys)
    }

    @Test fun keyCountIsCappedAtLimit() {
        val items = (0 until 50).joinToString(",") { "{\"id\":\"k$it\",\"kind\":\"MOUSE_LEFT\"}" }
        val r = ok("{\"keys\":[$items]}")
        assertEquals(ControllerTemplates.MAX_KEYS, r.templates.single().keys.size)
        assertEquals(50 - ControllerTemplates.MAX_KEYS, r.skippedKeys)
    }

    @Test fun importedMacrosNeverAutoPressEnterAndAreCounted() {
        val raw = "{\"keys\":[{\"id\":\"m\",\"kind\":\"MACRO\",\"label\":\"x\",\"macroText\":\"calc\",\"macroEnter\":true}]}"
        val r = ok(raw)
        assertEquals(1, r.macroCount)
        val m = r.templates.single().keys.single()
        assertFalse(m.macroSendEnter)
        assertEquals("calc", m.macroText)
    }

    @Test fun valuesAreClampedAndNamesSanitized() {
        val raw = "{\"name\":\"  Bad\\u0007Name  \",\"keys\":[{\"id\":\"a\",\"kind\":\"PAD_STICK\",\"x\":9,\"y\":-4,\"size\":999,\"stickSens\":50,\"stickDead\":0,\"stickMode\":\"WASD\"}]}"
        val t = ok(raw).templates.single()
        assertEquals("Bad Name", t.name)
        val k = t.keys.single()
        assertEquals(1f, k.x, 0f); assertEquals(0f, k.y, 0f)
        assertEquals(HudKey.MAX_SIZE, k.size, 0f)
        assertEquals(HudStickMath.MAX_SENS, k.stickSens, 0f)
        assertEquals(HudStickMath.MIN_DEAD, k.stickDead, 0f)
    }

    @Test fun duplicateKeyIdsBecomeUnique() {
        val r = ok("{\"keys\":[{\"id\":\"a\",\"kind\":\"MOUSE_LEFT\"},{\"id\":\"a\",\"kind\":\"MOUSE_RIGHT\"}]}")
        val ids = r.templates.single().keys.map { it.id }
        assertEquals(2, ids.toSet().size)
    }

    @Test fun bundleImportsEveryTemplate() {
        val list = listOf(ControllerTemplates.fpsTemplate(), ControllerTemplates.builtIns().first())
        val r = ok(ControllerTemplates.bundleToJson(list))
        assertEquals(2, r.templates.size)
        assertEquals(2, r.templates.map { it.id }.toSet().size)
    }

    @Test fun storageRoundTripAndCorruptionTolerance() {
        val a = ControllerTemplates.fromCurrent("Punyaku", HudKey.defaults(), id = "a", now = 1L)
        val b = ControllerTemplates.fpsTemplate().copy(id = "b", builtIn = false)
        val back = ControllerTemplates.decodeList(ControllerTemplates.encodeList(listOf(a, b)))
        assertEquals(listOf("a", "b"), back.map { it.id })
        assertEquals("Punyaku", back[0].name)
        assertTrue(ControllerTemplates.decodeList("garbage").isEmpty())
        assertTrue(ControllerTemplates.decodeList(null).isEmpty())
    }

    @Test fun builtInsAreValidAndUnique() {
        val all = ControllerTemplates.builtIns()
        assertTrue(all.size >= 6)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        all.forEach { t ->
            assertTrue("${t.id} has keys", t.keys.isNotEmpty())
            assertTrue("${t.id} within limit", t.keys.size <= ControllerTemplates.MAX_KEYS)
            // every built-in survives its own export/import
            assertEquals(t.keys.size, ok(ControllerTemplates.toJson(t)).templates.single().keys.size)
        }
    }

    @Test fun fileNameIsSafe() {
        assertEquals("kzdesk-fps-joystick.json", ControllerTemplates.fileName(ControllerTemplates.fpsTemplate()))
        assertEquals("kzdesk-template.json", ControllerTemplates.fileName(ControllerTemplates.fromCurrent("///", HudKey.defaults())))
    }
}
