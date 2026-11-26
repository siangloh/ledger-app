package com.siangloh.ledger.core

import com.siangloh.ledger.core.json.JSONArray
import com.siangloh.ledger.core.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KmpJsonTest {

    @Test
    fun jsonObject_emptyAndPutAndRemove() {
        val obj = JSONObject()
        assertEquals(0, obj.length())
        assertFalse(obj.keys().hasNext())
        assertFalse(obj.has("a"))
        assertTrue(obj.isNull("a"))

        val returned = obj
            .put("str", "hello")
            .put("int", 42)
            .put("long", 9_000_000_000L)
            .put("double", 12.75)
            .put("float", 3.5f)
            .put("bool", true)
            .put("explicitNull", JSONObject.NULL_VALUE)

        assertTrue(returned === obj)
        assertEquals(7, obj.length())
        assertTrue(obj.has("str"))
        assertTrue(obj.has("explicitNull"))
        assertTrue(obj.isNull("explicitNull"))
        assertFalse(obj.isNull("str"))

        // Putting Kotlin null removes key, matching org.json behavior
        obj.put("str", null as Any?)
        assertFalse(obj.has("str"))
        assertEquals(6, obj.length())

        val removed = obj.remove("int")
        assertEquals(42, removed)
        assertNull(obj.remove("missing"))
        assertEquals(5, obj.length())
    }

    @Test
    fun jsonObject_optStringNeverReturnsNullLiteral() {
        val obj = JSONObject("""{"present":"ok","nullField":null,"num":7,"literalNull":"null"}""")
        obj.put("putNull", JSONObject.NULL_VALUE)

        assertEquals("ok", obj.optString("present"))
        assertEquals("", obj.optString("missing"))
        assertEquals("fallback", obj.optString("missing", "fallback"))
        assertEquals("", obj.optString("nullField"))
        assertEquals("fallback", obj.optString("nullField", "fallback"))
        assertEquals("", obj.optString("putNull"))
        assertEquals("fallback", obj.optString("putNull", "fallback"))
        assertEquals("7", obj.optString("num"))
        assertEquals("null", obj.optString("literalNull"))

        assertFailsWith<IllegalArgumentException> { obj.getString("missing") }
        assertFailsWith<IllegalArgumentException> { obj.getString("nullField") }
    }

    @Test
    fun jsonObject_typedGettersAndOptionals() {
        val obj = JSONObject(
            """
            {
              "i": 10,
              "l": 5000000000,
              "d": 99.25,
              "b": true,
              "sInt": "123",
              "sBool": "TRUE",
              "bad": "abc",
              "n": null,
              "nested": {"k": "v"},
              "arr": [1, 2, 3]
            }
            """.trimIndent()
        )

        assertEquals(10, obj.getInt("i"))
        assertEquals(123, obj.optInt("sInt"))
        assertEquals(-1, obj.optInt("bad", -1))
        assertEquals(0, obj.optInt("missing"))
        assertFailsWith<IllegalArgumentException> { obj.getInt("bad") }

        assertEquals(5_000_000_000L, obj.getLong("l"))
        assertEquals(10L, obj.optLong("i"))
        assertEquals(99L, obj.optLong("missing", 99L))
        assertFailsWith<IllegalArgumentException> { obj.getLong("n") }

        assertEquals(99.25, obj.getDouble("d"))
        assertEquals(10.0, obj.optDouble("i", 0.0))
        assertTrue(obj.optDouble("missing").isNaN())
        assertTrue(obj.optDouble("n").isNaN())
        assertEquals(-1.5, obj.optDouble("missing", -1.5))
        assertFailsWith<IllegalArgumentException> { obj.getDouble("bad") }

        assertTrue(obj.getBoolean("b"))
        assertTrue(obj.optBoolean("sBool"))
        assertFalse(obj.optBoolean("missing"))
        assertTrue(obj.optBoolean("missing", true))
        assertFailsWith<IllegalArgumentException> { obj.getBoolean("bad") }

        val nested = obj.getJSONObject("nested")
        assertEquals("v", nested.getString("k"))
        nested.put("k", "updated")
        assertEquals("updated", obj.getJSONObject("nested").getString("k"))
        assertNull(obj.optJSONObject("missing"))
        assertFailsWith<IllegalArgumentException> { obj.getJSONObject("missing") }

        val arr = obj.getJSONArray("arr")
        assertEquals(3, arr.length())
        assertNull(obj.optJSONArray("missing"))
        assertFailsWith<IllegalArgumentException> { obj.getJSONArray("missing") }
    }

    @Test
    fun jsonObject_invalidJsonThrowsIllegalArgumentException() {
        assertFailsWith<IllegalArgumentException> { JSONObject("") }
        assertFailsWith<IllegalArgumentException> { JSONObject("{broken") }
        assertFailsWith<IllegalArgumentException> { JSONObject("[1, 2, 3]") }
        assertFailsWith<IllegalArgumentException> { JSONObject("null") }
        assertFailsWith<IllegalArgumentException> { JSONObject("42") }
        assertFailsWith<IllegalArgumentException> { JSONObject().put("bad", Double.NaN) }
        assertFailsWith<IllegalArgumentException> { JSONObject().put("bad", Float.POSITIVE_INFINITY) }
    }

    @Test
    fun jsonObject_serializationAndPrettyPrintRoundTrip() {
        val original = JSONObject()
            .put("name", "Ledger")
            .put("count", 3)
            .put("nullable", JSONObject.NULL_VALUE)
            .put("tags", JSONArray(listOf("food", "transport")))

        val compact = original.toString()
        val reparsed = JSONObject(compact)
        assertEquals("Ledger", reparsed.getString("name"))
        assertEquals(3, reparsed.getInt("count"))
        assertTrue(reparsed.has("nullable"))
        assertTrue(reparsed.isNull("nullable"))
        assertEquals("transport", reparsed.getJSONArray("tags").getString(1))

        val pretty = original.toString(2)
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("  \"name\""))
        assertEquals(reparsed, JSONObject(pretty))
    }

    @Test
    fun jsonArray_constructorsPutAndGetters() {
        val arr = JSONArray()
            .put("alpha")
            .put(42)
            .put(7_000_000_000L)
            .put(3.14)
            .put(true)
            .put(null)
            .put(JSONObject().put("id", 1))
            .put(JSONArray().put("nested"))

        assertEquals(8, arr.length())
        assertEquals("alpha", arr.getString(0))
        assertEquals(42, arr.getInt(1))
        assertEquals(7_000_000_000L, arr.getLong(2))
        assertEquals(3.14, arr.getDouble(3))
        assertTrue(arr.getBoolean(4))
        assertTrue(arr.isNull(5))
        assertEquals("", arr.optString(5))
        assertEquals("fallback", arr.optString(5, "fallback"))
        assertEquals("", arr.optString(99))
        assertTrue(arr.isNull(99))
        assertTrue(arr.optDouble(5).isNaN())
        assertTrue(arr.optDouble(99).isNaN())
        assertEquals(9.9, arr.optDouble(99, 9.9))
        assertEquals(1, arr.getJSONObject(6).getInt("id"))
        assertEquals("nested", arr.getJSONArray(7).getString(0))

        // Indexed put with padding
        arr.put(10, "end")
        assertEquals(11, arr.length())
        assertTrue(arr.isNull(8))
        assertTrue(arr.isNull(9))
        assertEquals("end", arr.getString(10))

        arr.put(0, "replaced")
        assertEquals("replaced", arr.getString(0))

        assertFailsWith<IllegalArgumentException> { arr.put(-1, "x") }
        assertFailsWith<IllegalArgumentException> { JSONArray("{}") }
        assertFailsWith<IllegalArgumentException> { JSONArray("invalid") }

        val fromCollection = JSONArray(listOf("x", null, 5))
        assertEquals(3, fromCollection.length())
        assertEquals("x", fromCollection.getString(0))
        assertTrue(fromCollection.isNull(1))
        assertEquals(5, fromCollection.getInt(2))

        val reparsed = JSONArray(fromCollection.toString(2))
        assertEquals(fromCollection, reparsed)
        assertNotNull(reparsed.opt(0))
    }

    @Test
    fun jsonObject_keysIterator() {
        val obj = JSONObject().put("a", 1).put("b", 2).put("c", 3)
        val collected = mutableListOf<String>()
        val it = obj.keys()
        while (it.hasNext()) {
            collected.add(it.next())
        }
        assertEquals(listOf("a", "b", "c"), collected)
    }
}
