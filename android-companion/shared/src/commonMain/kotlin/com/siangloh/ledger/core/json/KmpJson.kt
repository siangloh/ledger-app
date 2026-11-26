package com.siangloh.ledger.core.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

private object JsonNullSentinel {
    override fun equals(other: Any?): Boolean = other === this || other == null
    override fun hashCode(): Int = 0
    override fun toString(): String = "null"
}

private val compactJson = Json

@OptIn(ExperimentalSerializationApi::class)
private fun prettyJson(indentSpaces: Int): Json = Json {
    prettyPrint = true
    prettyPrintIndent = " ".repeat(indentSpaces.coerceAtLeast(0))
}

private fun parseJsonElementOrThrow(jsonString: String): JsonElement {
    return try {
        compactJson.parseToJsonElement(jsonString)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid JSON: ${e.message}", e)
    }
}

private fun fromJsonElement(element: JsonElement): Any {
    return when (element) {
        is JsonNull -> JSONObject.NULL_VALUE
        is JsonObject -> JSONObject.fromJsonObject(element)
        is JsonArray -> JSONArray.fromJsonArray(element)
        is JsonPrimitive -> {
            if (element.isString) {
                element.content
            } else {
                element.booleanOrNull
                    ?: element.intOrNull
                    ?: element.longOrNull
                    ?: element.doubleOrNull
                    ?: element.content
            }
        }
    }
}

private fun toJsonElement(value: Any?): JsonElement {
    return when (value) {
        null, JSONObject.NULL_VALUE -> JsonNull
        is JSONObject -> value.toJsonObject()
        is JSONArray -> value.toJsonArray()
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value.toDouble())
        is Number -> JsonPrimitive(value.toDouble())
        else -> JsonPrimitive(value.toString())
    }
}

private fun normalizePutValue(value: Any?): Any? {
    return when (value) {
        null -> null
        JSONObject.NULL_VALUE -> JSONObject.NULL_VALUE
        is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long -> value
        is Double -> {
            if (!value.isFinite()) throw IllegalArgumentException("JSON does not allow non-finite numbers: $value")
            value
        }
        is Float -> {
            if (!value.isFinite()) throw IllegalArgumentException("JSON does not allow non-finite numbers: $value")
            value.toDouble()
        }
        is Short, is Byte -> (value as Number).toInt()
        is Number -> value.toDouble()
        is Collection<*> -> JSONArray(value)
        is Map<*, *> -> JSONObject().also { obj ->
            for ((k, v) in value) {
                if (k != null) obj.put(k.toString(), v)
            }
        }
        else -> value.toString()
    }
}

private fun coerceString(value: Any?, fallback: String): String {
    if (value == null || value === JSONObject.NULL_VALUE) return fallback
    return value.toString()
}

private fun coerceInt(value: Any?): Int? {
    return when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt()
        else -> null
    }
}

private fun coerceLong(value: Any?): Long? {
    return when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
        else -> null
    }
}

private fun coerceDouble(value: Any?): Double? {
    return when (value) {
        is Double -> value
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}

private fun coerceBoolean(value: Any?): Boolean? {
    return when (value) {
        is Boolean -> value
        is String -> when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> null
        }
        else -> null
    }
}

/**
 * Multiplatform drop-in replacement for `org.json.JSONObject` backed by `kotlinx.serialization.json`.
 */
class JSONObject {
    private val entries: LinkedHashMap<String, Any?> = linkedMapOf()

    constructor()

    constructor(jsonString: String) {
        val element = parseJsonElementOrThrow(jsonString)
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("Value is not a JSONObject")
        for ((k, v) in obj) {
            entries[k] = fromJsonElement(v)
        }
    }

    companion object {
        val NULL_VALUE: Any = JsonNullSentinel

        internal fun fromJsonObject(obj: JsonObject): JSONObject {
            return JSONObject().apply {
                for ((k, v) in obj) {
                    entries[k] = fromJsonElement(v)
                }
            }
        }
    }

    fun has(key: String): Boolean = entries.containsKey(key)

    fun isNull(key: String): Boolean {
        if (!entries.containsKey(key)) return true
        val v = entries[key]
        return v == null || v === NULL_VALUE
    }

    fun keys(): Iterator<String> = entries.keys.toList().iterator()

    fun length(): Int = entries.size

    fun remove(key: String): Any? = entries.remove(key)

    fun opt(key: String): Any? = entries[key]

    fun get(key: String): Any {
        if (!entries.containsKey(key)) {
            throw IllegalArgumentException("No value for $key")
        }
        return entries[key] ?: NULL_VALUE
    }

    fun put(key: String, value: Any?): JSONObject {
        val normalized = normalizePutValue(value)
        if (normalized == null) {
            entries.remove(key)
        } else {
            entries[key] = normalized
        }
        return this
    }

    fun optString(key: String, fallback: String = ""): String {
        return coerceString(entries[key], fallback)
    }

    fun getString(key: String): String {
        val v = entries[key]
        if (v == null || v === NULL_VALUE) {
            throw IllegalArgumentException("No String value for $key")
        }
        return v.toString()
    }

    fun optInt(key: String, fallback: Int = 0): Int {
        return coerceInt(entries[key]) ?: fallback
    }

    fun getInt(key: String): Int {
        return coerceInt(entries[key])
            ?: throw IllegalArgumentException("No Int value for $key")
    }

    fun optLong(key: String, fallback: Long = 0L): Long {
        return coerceLong(entries[key]) ?: fallback
    }

    fun getLong(key: String): Long {
        return coerceLong(entries[key])
            ?: throw IllegalArgumentException("No Long value for $key")
    }

    fun optDouble(key: String): Double = optDouble(key, Double.NaN)

    fun optDouble(key: String, fallback: Double): Double {
        return coerceDouble(entries[key]) ?: fallback
    }

    fun getDouble(key: String): Double {
        return coerceDouble(entries[key])
            ?: throw IllegalArgumentException("No Double value for $key")
    }

    fun optBoolean(key: String, fallback: Boolean = false): Boolean {
        return coerceBoolean(entries[key]) ?: fallback
    }

    fun getBoolean(key: String): Boolean {
        return coerceBoolean(entries[key])
            ?: throw IllegalArgumentException("No Boolean value for $key")
    }

    fun optJSONObject(key: String): JSONObject? = entries[key] as? JSONObject

    fun getJSONObject(key: String): JSONObject {
        return entries[key] as? JSONObject
            ?: throw IllegalArgumentException("No JSONObject value for $key")
    }

    fun optJSONArray(key: String): JSONArray? = entries[key] as? JSONArray

    fun getJSONArray(key: String): JSONArray {
        return entries[key] as? JSONArray
            ?: throw IllegalArgumentException("No JSONArray value for $key")
    }

    internal fun toJsonObject(): JsonObject {
        val map = LinkedHashMap<String, JsonElement>(entries.size)
        for ((k, v) in entries) {
            map[k] = toJsonElement(v)
        }
        return JsonObject(map)
    }

    override fun toString(): String {
        return compactJson.encodeToString(JsonElement.serializer(), toJsonObject())
    }

    fun toString(indentSpaces: Int): String {
        return prettyJson(indentSpaces).encodeToString(JsonElement.serializer(), toJsonObject())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JSONObject) return false
        return entries == other.entries
    }

    override fun hashCode(): Int = entries.hashCode()
}

/**
 * Multiplatform drop-in replacement for `org.json.JSONArray` backed by `kotlinx.serialization.json`.
 */
class JSONArray {
    private val items: ArrayList<Any> = arrayListOf()

    constructor()

    constructor(jsonString: String) {
        val element = parseJsonElementOrThrow(jsonString)
        val arr = element as? JsonArray
            ?: throw IllegalArgumentException("Value is not a JSONArray")
        for (item in arr) {
            items.add(fromJsonElement(item))
        }
    }

    constructor(collection: Collection<Any?>) {
        for (item in collection) {
            items.add(normalizePutValue(item) ?: JSONObject.NULL_VALUE)
        }
    }

    companion object {
        internal fun fromJsonArray(arr: JsonArray): JSONArray {
            return JSONArray().apply {
                for (item in arr) {
                    items.add(fromJsonElement(item))
                }
            }
        }
    }

    fun length(): Int = items.size

    fun put(value: Any?): JSONArray {
        items.add(normalizePutValue(value) ?: JSONObject.NULL_VALUE)
        return this
    }

    fun put(index: Int, value: Any?): JSONArray {
        if (index < 0) throw IllegalArgumentException("JSONArray[$index] not found.")
        val normalized = normalizePutValue(value) ?: JSONObject.NULL_VALUE
        if (index < items.size) {
            items[index] = normalized
        } else {
            while (items.size < index) {
                items.add(JSONObject.NULL_VALUE)
            }
            items.add(normalized)
        }
        return this
    }

    fun get(index: Int): Any {
        if (index !in items.indices) {
            throw IndexOutOfBoundsException("JSONArray[$index] not found.")
        }
        return items[index]
    }

    fun opt(index: Int): Any? = if (index in items.indices) items[index] else null

    fun isNull(index: Int): Boolean {
        if (index !in items.indices) return true
        return items[index] === JSONObject.NULL_VALUE
    }

    fun getJSONObject(index: Int): JSONObject {
        return opt(index) as? JSONObject
            ?: throw IllegalArgumentException("JSONArray[$index] is not a JSONObject.")
    }

    fun optJSONObject(index: Int): JSONObject? = opt(index) as? JSONObject

    fun getJSONArray(index: Int): JSONArray {
        return opt(index) as? JSONArray
            ?: throw IllegalArgumentException("JSONArray[$index] is not a JSONArray.")
    }

    fun optJSONArray(index: Int): JSONArray? = opt(index) as? JSONArray

    fun getString(index: Int): String {
        val v = get(index)
        if (v === JSONObject.NULL_VALUE) {
            throw IllegalArgumentException("JSONArray[$index] is null.")
        }
        return v.toString()
    }

    fun optString(index: Int, fallback: String = ""): String {
        return coerceString(opt(index), fallback)
    }

    fun getInt(index: Int): Int {
        return coerceInt(get(index))
            ?: throw IllegalArgumentException("JSONArray[$index] is not an Int.")
    }

    fun optInt(index: Int, fallback: Int = 0): Int {
        return coerceInt(opt(index)) ?: fallback
    }

    fun getLong(index: Int): Long {
        return coerceLong(get(index))
            ?: throw IllegalArgumentException("JSONArray[$index] is not a Long.")
    }

    fun optLong(index: Int, fallback: Long = 0L): Long {
        return coerceLong(opt(index)) ?: fallback
    }

    fun getDouble(index: Int): Double {
        return coerceDouble(get(index))
            ?: throw IllegalArgumentException("JSONArray[$index] is not a Double.")
    }

    fun optDouble(index: Int): Double = optDouble(index, Double.NaN)

    fun optDouble(index: Int, fallback: Double): Double {
        return coerceDouble(opt(index)) ?: fallback
    }

    fun getBoolean(index: Int): Boolean {
        return coerceBoolean(get(index))
            ?: throw IllegalArgumentException("JSONArray[$index] is not a Boolean.")
    }

    fun optBoolean(index: Int, fallback: Boolean = false): Boolean {
        return coerceBoolean(opt(index)) ?: fallback
    }

    internal fun toJsonArray(): JsonArray {
        return JsonArray(items.map { toJsonElement(it) })
    }

    override fun toString(): String {
        return compactJson.encodeToString(JsonElement.serializer(), toJsonArray())
    }

    fun toString(indentSpaces: Int): String {
        return prettyJson(indentSpaces).encodeToString(JsonElement.serializer(), toJsonArray())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JSONArray) return false
        return items == other.items
    }

    override fun hashCode(): Int = items.hashCode()
}
