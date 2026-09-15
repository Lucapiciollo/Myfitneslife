package com.myfitai.app.ai

import org.json.JSONException

/** Read-only diagnostics for invalid JSON. It never changes or repairs the input. */
object JsonParseDiagnostics {
    enum class FailureCategory {
        TRUNCATED_JSON,
        MARKDOWN_FENCE,
        TEXT_BEFORE_JSON,
        TEXT_AFTER_JSON,
        UNESCAPED_CHARACTER,
        UNTERMINATED_STRING,
        INVALID_ESCAPE,
        INVALID_NUMBER,
        CONTROL_CHARACTER,
        OTHER_JSON_SYNTAX_ERROR,
    }

    data class Report(
        val outputLength: Int,
        val outputShape: String,
        val exceptionType: String,
        val exceptionMessage: String,
        val parserPosition: Int?,
        val hasLeadingFence: Boolean,
        val hasTrailingFence: Boolean,
        val startsWithObjectBrace: Boolean,
        val endsWithObjectBrace: Boolean,
        val containsNullByte: Boolean,
        val containsBom: Boolean,
        val hasTrailingCharactersAfterObject: Boolean,
        val firstCharCode: Int?,
        val lastCharCode: Int?,
        val firstNonWhitespaceCharCode: Int?,
        val lastNonWhitespaceCharCode: Int?,
        val category: FailureCategory,
    )

    fun diagnose(input: String, error: JSONException): Report {
        val trimmed = input.trim()
        val leadingFence = trimmed.startsWith("```")
        val trailingFence = trimmed.endsWith("```")
        val firstNonWhitespace = input.firstOrNull { !it.isWhitespace() }
        val lastNonWhitespace = input.lastOrNull { !it.isWhitespace() }
        val position = Regex("(?:position|character)\\s+(\\d+)", RegexOption.IGNORE_CASE)
            .find(error.message.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        val objectEnd = trimmed.lastIndexOf('}')
        val trailingAfterObject = objectEnd >= 0 && trimmed.substring(objectEnd + 1).isNotEmpty()
        val shape = when {
            input.isEmpty() -> "EMPTY"
            leadingFence || trailingFence -> "MARKDOWN_FENCED"
            firstNonWhitespace == '{' && lastNonWhitespace == '}' -> "OBJECT_LIKE"
            firstNonWhitespace == '[' -> "ARRAY_LIKE"
            firstNonWhitespace != '{' && firstNonWhitespace != '[' -> "TEXT_PREFIX"
            lastNonWhitespace != '}' -> "TEXT_SUFFIX"
            else -> "UNKNOWN"
        }
        return Report(
            outputLength = input.length,
            outputShape = shape,
            exceptionType = error.javaClass.simpleName,
            exceptionMessage = sanitizeExceptionMessage(error.message.orEmpty()),
            parserPosition = position,
            hasLeadingFence = leadingFence,
            hasTrailingFence = trailingFence,
            startsWithObjectBrace = firstNonWhitespace == '{',
            endsWithObjectBrace = lastNonWhitespace == '}',
            containsNullByte = input.indexOf('\u0000') >= 0,
            containsBom = input.indexOf('\uFEFF') >= 0,
            hasTrailingCharactersAfterObject = trailingAfterObject,
            firstCharCode = input.firstOrNull()?.code,
            lastCharCode = input.lastOrNull()?.code,
            firstNonWhitespaceCharCode = firstNonWhitespace?.code,
            lastNonWhitespaceCharCode = lastNonWhitespace?.code,
            category = classify(error.message.orEmpty(), shape, trailingAfterObject),
        )
    }

    private fun classify(message: String, shape: String, trailingAfterObject: Boolean): FailureCategory {
        val lower = message.lowercase()
        return when {
            lower.contains("unterminated") || lower.contains("end of string") -> FailureCategory.UNTERMINATED_STRING
            lower.contains("end of input") || lower.contains("unexpected end") -> FailureCategory.TRUNCATED_JSON
            shape == "MARKDOWN_FENCED" -> FailureCategory.MARKDOWN_FENCE
            shape == "TEXT_PREFIX" -> FailureCategory.TEXT_BEFORE_JSON
            shape == "TEXT_SUFFIX" || trailingAfterObject -> FailureCategory.TEXT_AFTER_JSON
            lower.contains("escape") -> FailureCategory.INVALID_ESCAPE
            lower.contains("number") || lower.contains("digit") -> FailureCategory.INVALID_NUMBER
            lower.contains("control") -> FailureCategory.CONTROL_CHARACTER
            lower.contains("character") || lower.contains("syntax") || lower.contains("expected") -> FailureCategory.OTHER_JSON_SYNTAX_ERROR
            else -> FailureCategory.OTHER_JSON_SYNTAX_ERROR
        }
    }

    private fun sanitizeExceptionMessage(message: String): String = message
        .substringBefore(" of {")
        .substringBefore(" of [")
        .take(180)
}
