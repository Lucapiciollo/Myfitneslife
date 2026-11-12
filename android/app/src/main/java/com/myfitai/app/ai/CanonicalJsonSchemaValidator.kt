package com.myfitai.app.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Local validator (v2) for the JSON-Schema subset used by MyFitAI contracts.
 *
 * Supported keywords: `type` (single or array, incl. `"null"`), `nullable`, `enum`, `const`,
 * `minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum`, `minLength`/`maxLength`, `pattern` (unanchored
 * Java regex), `minItems`/`maxItems`/`uniqueItems`, `items`, `properties`, `required` and
 * `additionalProperties=false`. Annotation keywords (`description`, `title`, `format`, `$schema`) are ignored.
 * Composition/reference keywords (`$ref`, `oneOf`, `anyOf`, `allOf`, `not`) are NOT supported and fail closed.
 *
 * Privacy contract: an error carries only the field path and a closed [Category]; it never contains the value
 * produced by the model. The only model-originated text that may appear is an unknown property NAME, and only
 * when it is a short identifier ([SAFE_KEY]); anything else is replaced by `<key>`.
 *
 * Backward compatibility: a null on an optional, non-nullable property is still tolerated (legacy behaviour),
 * and the legacy phrases "<path> is required" / "<path> is not allowed" are preserved.
 */
object CanonicalJsonSchemaValidator {
    enum class Category {
        NOT_JSON_OBJECT, INVALID_SCHEMA, UNSUPPORTED_KEYWORD, DEPTH_EXCEEDED,
        TYPE_MISMATCH, ENUM_MISMATCH, OUT_OF_RANGE, TOO_SHORT, TOO_LONG, PATTERN_MISMATCH,
        TOO_FEW_ITEMS, TOO_MANY_ITEMS, DUPLICATE_ITEMS, MISSING_REQUIRED, NOT_ALLOWED,
    }

    /** Structured error: [path] is a JSON path like `$.days[0].kcal`; never includes model values. */
    data class Issue(val path: String, val category: Category, val message: String)

    data class Result(
        val valid: Boolean,
        val errors: List<String>,
        val issues: List<Issue> = emptyList(),
    )

    private const val MAX_DEPTH = 32
    private val SAFE_KEY = Regex("^[A-Za-z0-9_]{1,40}$")
    private val UNSUPPORTED = listOf("\$ref", "oneOf", "anyOf", "allOf", "not")

    fun validate(jsonText: String, schemaJson: String): Result {
        val value = try {
            JSONObject(jsonText)
        } catch (error: JSONException) {
            return failure(Issue("$", Category.NOT_JSON_OBJECT, "$ is not a JSON object"))
        }
        val schema = runCatching { JSONObject(schemaJson) }.getOrElse {
            return failure(Issue("$", Category.INVALID_SCHEMA, "Invalid local schema"))
        }
        val issues = mutableListOf<Issue>()
        validateNode(value, schema, "$", 0, issues)
        return Result(issues.isEmpty(), issues.map { it.message }, issues)
    }

    private fun failure(issue: Issue) = Result(false, listOf(issue.message), listOf(issue))

    private fun MutableList<Issue>.add(path: String, category: Category, phrase: String) {
        add(Issue(path, category, "$path $phrase"))
    }

    private fun validateNode(value: Any?, schema: JSONObject, path: String, depth: Int, issues: MutableList<Issue>) {
        if (depth > MAX_DEPTH) {
            issues.add(path, Category.DEPTH_EXCEEDED, "exceeds maximum nesting depth")
            return
        }
        if (UNSUPPORTED.any { schema.has(it) }) {
            issues.add(path, Category.UNSUPPORTED_KEYWORD, "uses an unsupported schema keyword")
            return
        }
        val isNull = value == null || value == JSONObject.NULL
        val types = declaredTypes(schema)
        val nullable = schema.optBoolean("nullable", false) || "null" in types
        if (isNull) {
            if (!nullable && (types.isNotEmpty() || schema.has("enum") || schema.has("const"))) {
                issues.add(path, Category.TYPE_MISMATCH, "must not be null")
            }
            return
        }
        val concrete = types.filter { it != "null" }
        if (concrete.isNotEmpty() && concrete.none { matchesType(value, it) }) {
            issues.add(path, Category.TYPE_MISMATCH, "must be ${concrete.joinToString(" or ")}")
            return
        }
        if (schema.has("enum")) {
            val allowed = schema.optJSONArray("enum")
            if (allowed == null || (0 until allowed.length()).none { jsonEquals(allowed.opt(it), value) }) {
                issues.add(path, Category.ENUM_MISMATCH, "must be one of the allowed values")
                return
            }
        }
        if (schema.has("const") && !jsonEquals(schema.opt("const"), value)) {
            issues.add(path, Category.ENUM_MISMATCH, "must equal the required constant")
            return
        }
        when (value) {
            is JSONObject -> validateObject(value, schema, path, depth, issues)
            is JSONArray -> validateArray(value, schema, path, depth, issues)
            is String -> validateString(value, schema, path, issues)
            is Number -> validateNumber(value, schema, path, issues)
        }
    }

    private fun declaredTypes(schema: JSONObject): List<String> = when (val type = schema.opt("type")) {
        is String -> listOf(type)
        is JSONArray -> (0 until type.length()).mapNotNull { type.optString(it).takeIf(String::isNotEmpty) }
        else -> emptyList()
    }

    private fun matchesType(value: Any, type: String): Boolean = when (type) {
        "object" -> value is JSONObject
        "array" -> value is JSONArray
        "string" -> value is String
        "number" -> value is Number
        "integer" -> value is Number && value.toDouble() % 1.0 == 0.0
        "boolean" -> value is Boolean
        else -> true // unknown type names are annotations: behave like the legacy validator
    }

    private fun jsonEquals(expected: Any?, actual: Any?): Boolean = when {
        expected is Number && actual is Number -> expected.toDouble() == actual.toDouble()
        else -> expected == actual
    }

    private fun validateNumber(value: Number, schema: JSONObject, path: String, issues: MutableList<Issue>) {
        val number = value.toDouble()
        fun bound(key: String): Double? = (schema.opt(key) as? Number)?.toDouble()
        val outOfRange = bound("minimum")?.let { number < it } == true ||
            bound("maximum")?.let { number > it } == true ||
            bound("exclusiveMinimum")?.let { number <= it } == true ||
            bound("exclusiveMaximum")?.let { number >= it } == true
        if (outOfRange) issues.add(path, Category.OUT_OF_RANGE, "is outside the allowed range")
    }

    private fun validateString(value: String, schema: JSONObject, path: String, issues: MutableList<Issue>) {
        val length = value.codePointCount(0, value.length)
        schema.optInt("minLength", -1).takeIf { it >= 0 }?.let {
            if (length < it) issues.add(path, Category.TOO_SHORT, "is shorter than the allowed minimum")
        }
        schema.optInt("maxLength", -1).takeIf { it >= 0 }?.let {
            if (length > it) issues.add(path, Category.TOO_LONG, "is longer than the allowed maximum")
        }
        val pattern = schema.optString("pattern")
        if (pattern.isNotEmpty()) {
            val regex = runCatching { Regex(pattern) }.getOrNull()
            if (regex == null) issues.add(path, Category.INVALID_SCHEMA, "uses an invalid schema pattern")
            else if (!regex.containsMatchIn(value)) issues.add(path, Category.PATTERN_MISMATCH, "does not match the required pattern")
        }
    }

    private fun validateObject(value: JSONObject, schema: JSONObject, path: String, depth: Int, issues: MutableList<Issue>) {
        val properties = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) {
            val key = required.optString(i)
            val nullableRequired = properties.optJSONObject(key)?.let { prop ->
                prop.optBoolean("nullable", false) || "null" in declaredTypes(prop)
            } == true
            val missing = !value.has(key) || (value.isNull(key) && !nullableRequired)
            if (missing) issues.add("$path.$key", Category.MISSING_REQUIRED, "is required")
        }
        if (schema.opt("additionalProperties") == false) {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!properties.has(key)) {
                    val shown = if (SAFE_KEY.matches(key)) key else "<key>"
                    issues.add("$path.$shown", Category.NOT_ALLOWED, "is not allowed")
                }
            }
        }
        val propertyKeys = properties.keys()
        while (propertyKeys.hasNext()) {
            val key = propertyKeys.next()
            if (!value.has(key)) continue
            val propertySchema = properties.optJSONObject(key) ?: continue
            val propertyValue = value.opt(key)
            val isNull = propertyValue == null || propertyValue == JSONObject.NULL
            // Legacy leniency: an explicit null on an optional property is treated as absent
            // unless the schema declares it nullable (then it is validated normally and passes).
            if (isNull) continue
            validateNode(propertyValue, propertySchema, "$path.$key", depth + 1, issues)
        }
    }

    private fun validateArray(value: JSONArray, schema: JSONObject, path: String, depth: Int, issues: MutableList<Issue>) {
        schema.optInt("minItems", -1).takeIf { it >= 0 }?.let {
            if (value.length() < it) issues.add(path, Category.TOO_FEW_ITEMS, "must contain at least $it items")
        }
        schema.optInt("maxItems", -1).takeIf { it >= 0 }?.let {
            if (value.length() > it) issues.add(path, Category.TOO_MANY_ITEMS, "must contain at most $it items")
        }
        if (schema.optBoolean("uniqueItems", false)) {
            val seen = HashSet<String>()
            val duplicate = (0 until value.length()).any { !seen.add(value.opt(it).toString()) }
            if (duplicate) issues.add(path, Category.DUPLICATE_ITEMS, "must not contain duplicate items")
        }
        val itemSchema = schema.optJSONObject("items") ?: return
        for (i in 0 until value.length()) validateNode(value.opt(i), itemSchema, "$path[$i]", depth + 1, issues)
    }
}
