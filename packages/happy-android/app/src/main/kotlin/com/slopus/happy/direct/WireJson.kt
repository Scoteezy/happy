package com.slopus.happy.direct

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A Happy Agent answer that does not have the shape its route promises. */
class WireException(message: String) : Exception(message)

/**
 * Reading and writing the Happy Agent API's JSON by hand, as happy-ios's
 * Codable conformances do: a required field throws when it is missing, a
 * `Nullable` one reads JSON `null` and absence alike as null.
 */
internal object Wire {
    fun obj(value: JsonElement?): JsonObject = value as? JsonObject ?: throw WireException("not an object")
    fun array(value: JsonElement?): JsonArray = value as? JsonArray ?: throw WireException("not an array")

    fun string(o: JsonObject, key: String): String =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw WireException("$key: not a string")

    fun stringOrNull(o: JsonObject, key: String): String? = when (val v = o[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else throw WireException("$key: not a string")
        else -> throw WireException("$key: not a string")
    }

    fun bool(o: JsonObject, key: String): Boolean =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw WireException("$key: not a boolean")

    fun boolOrNull(o: JsonObject, key: String): Boolean? = when (val v = o[key]) {
        null, JsonNull -> null
        else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw WireException("$key: not a boolean")
    }

    fun double(o: JsonObject, key: String): Double =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw WireException("$key: not a number")

    fun doubleOrNull(o: JsonObject, key: String): Double? = when (val v = o[key]) {
        null, JsonNull -> null
        else -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw WireException("$key: not a number")
    }

    fun long(o: JsonObject, key: String): Long = longOrNull(o, key) ?: throw WireException("$key: not an integer")

    fun longOrNull(o: JsonObject, key: String): Long? = when (val v = o[key]) {
        null, JsonNull -> null
        else -> {
            val p = (v as? JsonPrimitive)?.takeIf { !it.isString } ?: throw WireException("$key: not an integer")
            p.longOrNull ?: p.doubleOrNull?.takeIf { it == Math.floor(it) && !it.isInfinite() }?.toLong()
                ?: throw WireException("$key: not an integer")
        }
    }

    fun int(o: JsonObject, key: String): Int = long(o, key).toInt()
    fun intOrNull(o: JsonObject, key: String): Int? = longOrNull(o, key)?.toInt()

    fun strings(o: JsonObject, key: String): List<String> = array(o[key]).map {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw WireException("$key: not strings")
    }

    fun <T> list(o: JsonObject, key: String, read: (JsonElement) -> T): List<T> = array(o[key]).map(read)

    /** A number as JavaScript writes it: a whole one without a fraction. */
    fun number(value: Double): JsonPrimitive =
        if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 9.007199254740992E15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    fun number(value: Long?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    fun number(value: Int?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    fun numberOrNull(value: Double?): JsonElement = value?.let(::number) ?: JsonNull
    fun string(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    fun bool(value: Boolean?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    /** An object of `pairs`, leaving out those whose value is absent (Kotlin null). */
    fun objectOf(vararg pairs: Pair<String, JsonElement?>): JsonObject =
        JsonObject(LinkedHashMap<String, JsonElement>().apply { for ((k, v) in pairs) if (v != null) put(k, v) })
}
