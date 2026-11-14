package com.myfitai.app.ai

import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * EXPERIMENTAL Groq transport (groq.com inference on open models, Chat Completions API compatible with OpenAI). Off by default:
 * [AiProviderType.GROQ] is only selected when `AiSettingsStore.groqEnabled` is true and the user chose it. The API key is
 * decrypted only for the duration of generateStructured() and is never stored on this provider, logged or copied into an exception.
 *
 * The canonical JSON contracts are untouched: only the copy of the schema sent to Groq goes through [GroqSchemaAdapter];
 * local schema/business validation keeps using the canonical schema. Docs consulted on 14/11/2026 (see 06-ai/providers/groq/README.md).
 */
class GroqProvider(
    private val credentialStore: SecureAiCredentialStore,
    private val model: String = AiModelConfig.GROQ,
) : AiProvider {
    override val type: AiProviderType = AiProviderType.GROQ

    override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
        val apiKey = credentialStore.read(AiCredentialProvider.GROQ)?.takeIf { it.isNotBlank() }
            ?: throw AiTransportException.NotConfigured(type)
        return generateWithKey(apiKey, request)
    }

    suspend fun verifyApiKey(apiKey: String): AiRawResponse {
        return try {
            generateWithKey(apiKey.trim(), AiStructuredRequest(
                systemPrompt = "Return only the string ok.",
                userPrompt = "Reply with ok.",
                schemaName = "myfitai_provider_verification",
                schemaJson = "{\"type\":\"object\",\"properties\":{\"status\":{\"type\":\"string\",\"enum\":[\"ok\"]}},\"required\":[\"status\"],\"additionalProperties\":false}",
                // gpt-oss reasons before answering and its reasoning counts inside max_completion_tokens: keep a margin.
                maxOutputTokens = 1_024,
            ))
        } catch (_: AiExecutionService.Failure) {
            // The HTTP call succeeded but the probe answer was empty/blocked/cut: verification only needs a readable reply.
            throw AiTransportException.InvalidResponse()
        }
    }

    private suspend fun generateWithKey(apiKey: String, request: AiStructuredRequest): AiRawResponse {
        require(apiKey.isNotBlank()) { "Groq API key must not be blank" }
        val raw = HttpJsonClient.post(
            provider = type,
            url = ENDPOINT,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = buildRequestBody(model, request).toString(),
        )
        return parseResponse(raw, model)
    }

    companion object {
        private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        /** Documented efforts of the gpt-oss models (docs/reasoning, 14/11/2026). qwen3.8-27b defaults to `none`: nothing is sent. */
        private val GPT_OSS_EFFORTS = setOf("low", "medium", "high")

        /** gpt-oss effort sent by default: cheap and fast, enough for schema-bound nutrition JSON. Null = field omitted. */
        internal fun defaultReasoningEffort(model: String): String? =
            if (model == AiModelConfig.GROQ_GPT_OSS_20B || model == AiModelConfig.GROQ_GPT_OSS_120B) "low" else null

        /**
         * Chat Completions body. Structured output is `response_format = {type: json_schema, json_schema: {name, strict: true, schema}}`
         * with the ADAPTED copy of the schema (strict needs every property required and `additionalProperties:false`).
         * Streaming and tool use are not combinable with structured outputs, so neither is sent. `max_completion_tokens`
         * includes the reasoning tokens of the model: a cap that is too small ends as Truncated, never as a silent success.
         *
         * A photo is only sent to a model documented with image input; otherwise the request fails locally (no network, no cost).
         */
        internal fun buildRequestBody(
            model: String,
            request: AiStructuredRequest,
            reasoningEffort: String? = defaultReasoningEffort(model),
        ): JSONObject {
            if (request.image != null && !AiModelConfig.groqSupportsImages(model)) {
                // Reuses the shared "model cannot serve this request" category; the message carries no provider text.
                throw AiTransportException.Http(AiProviderType.GROQ, 400, AiTransportFailureKind.MODEL_UNAVAILABLE)
            }
            val body = JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", request.systemPrompt))
                        .put(JSONObject().put("role", "user").put("content", userContentFor(request)))
                )
                .put(
                    "response_format",
                    JSONObject()
                        .put("type", "json_schema")
                        .put(
                            "json_schema",
                            JSONObject()
                                .put("name", request.schemaName)
                                .put("strict", true)
                                .put("schema", GroqSchemaAdapter.adapt(request.schemaJson).schema)
                        )
                )
            reasoningEffort?.let { body.put("reasoning_effort", validatedEffort(it)) }
            request.maxOutputTokens?.let { body.put("max_completion_tokens", it) }
            return body
        }

        internal fun validatedEffort(effort: String): String {
            require(effort in GPT_OSS_EFFORTS) { "Unsupported Groq reasoning effort" }
            return effort
        }

        /**
         * Pure parser of a Chat Completions body (no network). finish_reason `length` -> Truncated, `content_filter` -> Refused,
         * a non-blank `message.refusal` -> Refused (its text is never read), no content -> EmptyOutput, unknown finish reason
         * (e.g. `tool_calls`), an `error` object or a non-JSON body -> protocol error. Provider text is never copied into an exception.
         */
        internal fun parseResponse(raw: String, model: String): AiRawResponse {
            val response = try {
                JSONObject(raw)
            } catch (_: org.json.JSONException) {
                throw AiTransportException.InvalidResponse()
            }
            if (response.optJSONObject("error") != null) throw AiTransportException.InvalidResponse()
            val choice = response.optJSONArray("choices")?.optJSONObject(0) ?: throw AiExecutionService.Failure.EmptyOutput()
            AiFailure.fromFinishReason(text(choice, "finish_reason"))?.let { throw it.toException() }
            val message = choice.optJSONObject("message") ?: throw AiExecutionService.Failure.EmptyOutput()
            if (!text(message, "refusal").isNullOrBlank()) throw AiExecutionService.Failure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL)
            val jsonText = text(message, "content")?.takeIf { it.isNotBlank() } ?: throw AiExecutionService.Failure.EmptyOutput()
            return AiRawResponse(AiProviderType.GROQ, model, jsonText, parseUsage(response.optJSONObject("usage")))
        }

        /** Null-safe string read: org.json turns a JSON null into the text "null" with optString. */
        private fun text(node: JSONObject, key: String): String? = if (node.isNull(key)) null else node.optString(key)

        /**
         * Groq reports `prompt_tokens`/`completion_tokens`/`total_tokens` and, for models with prompt caching,
         * `prompt_tokens_details.cached_tokens`. Reasoning tokens are part of `completion_tokens` (total = prompt + completion);
         * a `completion_tokens_details.reasoning_tokens`, if present, is informational only and is not added to billing.
         */
        private fun parseUsage(usage: JSONObject?): AiUsageMetadata? {
            usage ?: return null
            fun long(key: String): Long? = if (usage.has(key) && !usage.isNull(key)) usage.optLong(key) else null
            fun detail(container: String, key: String): Long? = usage.optJSONObject(container)
                ?.takeIf { it.has(key) && !it.isNull(key) }?.optLong(key)
            return AiUsageMetadata(
                inputTokens = long("prompt_tokens"),
                outputTokens = long("completion_tokens"),
                totalTokens = long("total_tokens"),
                thoughtsTokens = detail("completion_tokens_details", "reasoning_tokens"),
                cachedTokens = detail("prompt_tokens_details", "cached_tokens"),
            )
        }

        private fun userContentFor(request: AiStructuredRequest): Any {
            val image = request.image ?: return request.userPrompt
            return JSONArray()
                .put(JSONObject().put("type", "text").put("text", request.userPrompt))
                .put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${image.mimeType};base64,${image.base64Data}"))
                )
        }
    }
}

/**
 * Pure adapter that turns the COPY of a canonical schema sent to Groq into what `strict: true` documents
 * (console.groq.com/docs/structured-outputs, "Schema Requirements", 14/11/2026). It never touches the canonical schema nor
 * local validation: every constraint dropped here is still enforced by [CanonicalJsonSchemaValidator] on the answer.
 *
 * Doc facts: strict needs ALL properties in `required` and `additionalProperties:false` on every object; optional fields are
 * expressed as a union with `null`; supported are primitives, object, array, enum and `anyOf` (+ `$defs`/`$ref`). The doc does not
 * list any validation keyword for strict, so the conservative choice is applied:
 * - every object with `properties` gets all its properties required and `additionalProperties:false`; a property that was optional
 *   becomes `anyOf[<original>, {type:null}]` (unless it already accepts null) so the model may still say "absent";
 * - `const` -> single-value `enum`; `oneOf` -> `anyOf`;
 * - annotations/validation keywords not documented for strict are removed: `$schema`, `deprecated`, `uniqueItems`, `pattern`,
 *   `format`, `minLength`/`maxLength`, `minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum`/`multipleOf`,
 *   `minItems`/`maxItems`, `minProperties`/`maxProperties`, `contains`/`minContains`/`maxContains`.
 * `enum`, `const`, `default`, `examples` values are data and never walked. `description` is kept (the doc's examples use it).
 *
 * Caveat for native (non-compact) contracts: an optional property turned nullable may come back as `null`; the canonical validator
 * would reject it. Every contract registered today is the compact `{data: string}` envelope (already strict-compatible, unchanged).
 */
internal object GroqSchemaAdapter {
    data class Adapted(val schema: JSONObject, val changes: List<String>)

    private val REMOVED_KEYWORDS = setOf(
        "\$schema", "deprecated", "uniqueItems", "pattern", "format", "minLength", "maxLength",
        "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
        "minItems", "maxItems", "minProperties", "maxProperties", "contains", "minContains", "maxContains",
    )
    private val DATA_KEYWORDS = setOf("enum", "default", "examples")
    private val SCHEMA_MAP_KEYWORDS = setOf("properties", "\$defs", "definitions")

    fun adapt(canonicalJson: String): Adapted {
        val changes = mutableListOf<String>()
        val schema = JSONObject(canonicalJson)
        adaptSchema(schema, "#", changes)
        return Adapted(schema, changes)
    }

    private fun adaptSchema(node: JSONObject, path: String, changes: MutableList<String>) {
        for (key in node.keys().asSequence().toList()) {
            val value = node.get(key)
            when {
                key in REMOVED_KEYWORDS -> {
                    node.remove(key)
                    changes += "$path: removed $key"
                }
                key == "const" -> {
                    node.remove("const")
                    if (!node.has("enum")) node.put("enum", JSONArray().put(value))
                    changes += "$path: const -> enum"
                }
                key in DATA_KEYWORDS -> Unit
                key == "oneOf" && value is JSONArray -> {
                    node.remove("oneOf")
                    if (!node.has("anyOf")) node.put("anyOf", value)
                    changes += "$path: oneOf -> anyOf"
                    adaptSchemaArray(value, "$path/anyOf", changes)
                }
                key in SCHEMA_MAP_KEYWORDS && value is JSONObject -> {
                    for (name in value.keys().asSequence().toList()) {
                        (value.get(name) as? JSONObject)?.let { adaptSchema(it, "$path/$key/$name", changes) }
                    }
                }
                value is JSONObject -> adaptSchema(value, "$path/$key", changes)
                value is JSONArray -> adaptSchemaArray(value, "$path/$key", changes)
            }
        }
        closeObject(node, path, changes)
    }

    /** Strict mode: every declared property is required, optional ones become nullable, and the object is closed. */
    private fun closeObject(node: JSONObject, path: String, changes: MutableList<String>) {
        val properties = node.optJSONObject("properties") ?: return
        val required = node.optJSONArray("required") ?: JSONArray()
        val requiredNames = (0 until required.length()).map { required.optString(it) }.toMutableSet()
        for (name in properties.keys().asSequence().toList()) {
            if (name in requiredNames) continue
            (properties.get(name) as? JSONObject)?.takeIf { !acceptsNull(it) }?.let { original ->
                properties.put(name, JSONObject().put("anyOf", JSONArray().put(original).put(JSONObject().put("type", "null"))))
            }
            required.put(name)
            requiredNames += name
            changes += "$path/properties/$name: optional -> required nullable"
        }
        node.put("required", required)
        if (node.opt("additionalProperties") != java.lang.Boolean.FALSE) {
            node.put("additionalProperties", false)
            changes += "$path: additionalProperties=false"
        }
    }

    private fun acceptsNull(schema: JSONObject): Boolean {
        val type = schema.opt("type")
        if (type == "null") return true
        if (type is JSONArray && (0 until type.length()).any { type.optString(it) == "null" }) return true
        val enum = schema.optJSONArray("enum")
        if (enum != null && (0 until enum.length()).any { enum.isNull(it) }) return true
        val anyOf = schema.optJSONArray("anyOf")
        return anyOf != null && (0 until anyOf.length()).any { index -> anyOf.optJSONObject(index)?.let(::acceptsNull) == true }
    }

    private fun adaptSchemaArray(array: JSONArray, path: String, changes: MutableList<String>) {
        for (index in 0 until array.length()) {
            (array.opt(index) as? JSONObject)?.let { adaptSchema(it, "$path/$index", changes) }
        }
    }
}
