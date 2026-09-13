package com.myfitai.app.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Small local validator for the JSON-Schema subset used by MyFitAI contracts.
 * It intentionally rejects unknown fields when additionalProperties=false.
 */
object CanonicalJsonSchemaValidator {
    data class Result(val valid: Boolean, val errors: List<String>)

    fun validate(jsonText: String, schemaJson: String): Result {
        val value = runCatching { JSONObject(jsonText) }.getOrElse {
            return Result(false, listOf("$ is not a JSON object"))
        }
        val schema = runCatching { JSONObject(schemaJson) }.getOrElse {
            return Result(false, listOf("Invalid local schema"))
        }
        val errors = mutableListOf<String>()
        validateNode(value, schema, "$", errors)
        return Result(errors.isEmpty(), errors)
    }

    private fun validateNode(value: Any?, schema: JSONObject, path: String, errors: MutableList<String>) {
        val type = schema.optString("type")
        when (type) {
            "object" -> validateObject(value, schema, path, errors)
            "array" -> validateArray(value, schema, path, errors)
            "string" -> if (value !is String) errors += "$path must be string"
            "number" -> if (value !is Number) errors += "$path must be number"
            "integer" -> if (value !is Number || value.toDouble() % 1.0 != 0.0) errors += "$path must be integer"
            "boolean" -> if (value !is Boolean) errors += "$path must be boolean"
        }
    }

    private fun validateObject(value: Any?, schema: JSONObject, path: String, errors: MutableList<String>) {
        if (value !is JSONObject) {
            errors += "$path must be object"
            return
        }
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) {
            val key = required.optString(i)
            if (!value.has(key) || value.isNull(key)) errors += "$path.$key is required"
        }
        if (schema.opt("additionalProperties") == false) {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!properties.has(key)) errors += "$path.$key is not allowed"
            }
        }
        val propertyKeys = properties.keys()
        while (propertyKeys.hasNext()) {
            val key = propertyKeys.next()
            if (value.has(key) && !value.isNull(key)) {
                validateNode(value.opt(key), properties.optJSONObject(key) ?: continue, "$path.$key", errors)
            }
        }
    }

    private fun validateArray(value: Any?, schema: JSONObject, path: String, errors: MutableList<String>) {
        if (value !is JSONArray) {
            errors += "$path must be array"
            return
        }
        val minItems = schema.optInt("minItems", -1)
        if (minItems >= 0 && value.length() < minItems) errors += "$path must contain at least $minItems items"
        val maxItems = schema.optInt("maxItems", -1)
        if (maxItems >= 0 && value.length() > maxItems) errors += "$path must contain at most $maxItems items"
        val itemSchema = schema.optJSONObject("items") ?: return
        for (i in 0 until value.length()) validateNode(value.opt(i), itemSchema, "$path[$i]", errors)
    }
}
