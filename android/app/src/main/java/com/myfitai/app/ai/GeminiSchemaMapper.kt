package com.myfitai.app.ai

import org.json.JSONArray
import org.json.JSONObject

/** Maps the canonical JSON Schema subset to Gemini's supported Schema subset. */
object GeminiSchemaMapper {
    data class Result(
        val schema: JSONObject,
        val canonicalLength: Int,
        val mappedLength: Int,
        val maxDepth: Int,
        val propertyCount: Int,
        val arrayCount: Int,
    )

    fun map(canonicalJson: String, omitCollectionBounds: Boolean = false): Result {
        val canonical = JSONObject(canonicalJson)
        val stats = Stats()
        val mapped = mapObject(canonical, 1, stats, omitCollectionBounds)
        return Result(mapped, canonicalJson.length, mapped.toString().length, stats.maxDepth, stats.propertyCount, stats.arrayCount)
    }

    private fun mapObject(source: JSONObject, depth: Int, stats: Stats, omitCollectionBounds: Boolean): JSONObject {
        stats.maxDepth = maxOf(stats.maxDepth, depth)
        return JSONObject().apply {
            source.optString("type").takeIf { it.isNotBlank() }?.let { put("type", it.uppercase()) }
            source.optString("description").takeIf { it.isNotBlank() }?.let { put("description", it) }
            source.optJSONArray("enum")?.let { put("enum", it) }
            source.optBoolean("nullable", false).takeIf { it }?.let { put("nullable", true) }
            source.optDouble("minimum", Double.NaN).takeIf { !it.isNaN() }?.let { put("minimum", it) }
            source.optDouble("maximum", Double.NaN).takeIf { !it.isNaN() }?.let { put("maximum", it) }
            // Supported by Gemini Schema for arrays. Do not pass unsupported JSON Schema keywords.
            if (!omitCollectionBounds) {
                source.optInt("minItems", -1).takeIf { it >= 0 }?.let { put("minItems", it) }
                source.optInt("maxItems", -1).takeIf { it >= 0 }?.let { put("maxItems", it) }
            }
            source.optJSONObject("items")?.let {
                stats.arrayCount++
                put("items", mapObject(it, depth + 1, stats, omitCollectionBounds))
            }
            source.optJSONArray("required")?.let { put("required", it) }
            source.optJSONObject("properties")?.let { properties ->
                val mappedProperties = JSONObject()
                properties.keys().forEach { key ->
                    stats.propertyCount++
                    mappedProperties.put(key, mapObject(properties.getJSONObject(key), depth + 1, stats, omitCollectionBounds))
                }
                put("properties", mappedProperties)
            }
            // additionalProperties and numeric minimum/maximum are not part of Gemini's Schema subset.
        }
    }

    private class Stats(var maxDepth: Int = 0, var propertyCount: Int = 0, var arrayCount: Int = 0)
}
