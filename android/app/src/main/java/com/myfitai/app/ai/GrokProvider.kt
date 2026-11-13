package com.myfitai.app.ai

import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * EXPERIMENTAL xAI Grok transport (Responses API, OpenAI-compatible). Off by default: nothing selects
 * [AiProviderType.GROK] until the provider selector/UI is wired (batch 2). The API key is decrypted only for the
 * duration of generateStructured() and is never stored on this provider, logged or copied into an exception.
 *
 * The canonical JSON contracts are untouched: only the copy of the schema sent to xAI goes through
 * [GrokSchemaAdapter]; local schema/business validation keeps using the canonical schema.
 */
class GrokProvider(
    private val credentialStore: SecureAiCredentialStore,
    private val model: String = AiModelConfig.GROK,
) : AiProvider {
    override val type: AiProviderType = AiProviderType.GROK

    override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
        val apiKey = credentialStore.read(AiCredentialProvider.GROK)?.takeIf { it.isNotBlank() }
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
                maxOutputTokens = 64,
            ))
        } catch (_: AiExecutionService.Failure) {
            // The HTTP call succeeded but the probe answer was empty/blocked/cut: verification only needs a readable reply.
            throw AiTransportException.InvalidResponse()
        }
    }

    private suspend fun generateWithKey(apiKey: String, request: AiStructuredRequest): AiRawResponse {
        require(apiKey.isNotBlank()) { "Grok API key must not be blank" }
        val raw = HttpJsonClient.post(
            provider = type,
            url = ENDPOINT,
            headers = mapOf("Authorization" to "Bearer $apiKey"),
            body = buildRequestBody(model, request).toString(),
        )
        return parseResponse(raw, model)
    }

    companion object {
        private const val ENDPOINT = "https://api.x.ai/v1/responses"

        /**
         * Responses API body (docs.x.ai). `store=false` is mandatory with images ("do not store request history").
         * `max_output_tokens` only caps visible output: reasoning tokens are not counted by xAI.
         */
        internal fun buildRequestBody(model: String, request: AiStructuredRequest): JSONObject {
            val body = JSONObject()
                .put("model", model)
                .put("instructions", request.systemPrompt)
                .put("input", buildInputFor(request))
                .put("store", false)
                .put(
                    "text",
                    JSONObject().put(
                        "format",
                        JSONObject()
                            .put("type", "json_schema")
                            .put("name", request.schemaName)
                            .put("strict", true)
                            .put("schema", GrokSchemaAdapter.adapt(request.schemaJson).schema)
                    )
                )
            request.maxOutputTokens?.let { body.put("max_output_tokens", it) }
            return body
        }

        /**
         * Pure parser of a Responses API body (no network). `incomplete` with max_output_tokens -> Truncated,
         * content_filter -> Refused, a `refusal` content part -> Refused (its text is never read), no output text ->
         * EmptyOutput, `failed`/unknown status or a non-JSON body -> protocol error. Provider text is never copied into an exception.
         */
        internal fun parseResponse(raw: String, model: String): AiRawResponse {
            val response = try {
                JSONObject(raw)
            } catch (_: org.json.JSONException) {
                throw AiTransportException.InvalidResponse()
            }
            when (response.optString("status")) {
                "", "completed" -> Unit
                "incomplete" -> {
                    val reason = response.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
                    throw (AiFailure.fromFinishReason(reason.ifBlank { "incomplete" }) ?: AiFailure.Truncated).toException()
                }
                else -> throw AiTransportException.InvalidResponse()
            }
            if (hasRefusal(response)) throw AiExecutionService.Failure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL)
            val jsonText = extractOutputText(response) ?: throw AiExecutionService.Failure.EmptyOutput()
            return AiRawResponse(AiProviderType.GROK, model, jsonText, parseUsage(response.optJSONObject("usage")))
        }

        /**
         * xAI reports `input_tokens`/`output_tokens` (+ `*_tokens_details`); the docs also show the chat-style names.
         * Both are accepted. NOTE: on xAI `reasoning_tokens` are reported in addition to `output_tokens`
         * (total = input + output + reasoning); values are passed through unchanged, pricing is batch 2.
         */
        private fun parseUsage(usage: JSONObject?): AiUsageMetadata? {
            usage ?: return null
            fun long(vararg keys: String): Long? = keys.firstOrNull { usage.has(it) && !usage.isNull(it) }?.let { usage.optLong(it) }
            fun detail(vararg objects: String, key: String): Long? = objects.firstNotNullOfOrNull { name ->
                usage.optJSONObject(name)?.takeIf { it.has(key) && !it.isNull(key) }?.optLong(key)
            }
            return AiUsageMetadata(
                inputTokens = long("input_tokens", "prompt_tokens"),
                outputTokens = long("output_tokens", "completion_tokens"),
                totalTokens = long("total_tokens"),
                thoughtsTokens = detail("output_tokens_details", "completion_tokens_details", key = "reasoning_tokens"),
                cachedTokens = detail("input_tokens_details", "prompt_tokens_details", key = "cached_tokens"),
            )
        }

        private fun buildInputFor(request: AiStructuredRequest): Any {
            val image = request.image ?: return request.userPrompt
            val content = JSONArray()
                .put(JSONObject().put("type", "input_text").put("text", request.userPrompt))
                .put(JSONObject().put("type", "input_image").put("image_url", "data:${image.mimeType};base64,${image.base64Data}"))
            return JSONArray().put(JSONObject().put("role", "user").put("content", content))
        }

        private fun hasRefusal(root: JSONObject): Boolean {
            val output = root.optJSONArray("output") ?: return false
            for (i in 0 until output.length()) {
                val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    if (content.optJSONObject(j)?.optString("type") == "refusal") return true
                }
            }
            return false
        }

        private fun extractOutputText(root: JSONObject): String? {
            val output = root.optJSONArray("output") ?: return null
            // Reasoning items carry no `content`; only output_text parts are concatenated (a long plan may be split).
            val text = buildString {
                for (i in 0 until output.length()) {
                    val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "output_text") append(part.optString("text"))
                    }
                }
            }
            return text.takeIf { it.isNotBlank() }
        }
    }
}

/**
 * Pure adapter that removes from the COPY of a canonical schema sent to xAI what xAI rejects with HTTP 400 or does not document
 * (docs.x.ai structured outputs, "Rejected schemas"). It never touches the canonical schema nor local validation:
 * every constraint dropped here is still enforced by [CanonicalJsonSchemaValidator] on the answer.
 *
 * - `maxContains`/`minContains` -> removed (400).
 * - `items` as an array -> renamed `prefixItems` (tuple validation); an existing `prefixItems` is kept.
 * - empty `enum`/`anyOf`/`oneOf` -> removed (400).
 * - property schema `true` -> `{}`; property schema `false` -> property dropped (and removed from `required`) (400).
 * - `$schema`, `deprecated`, `uniqueItems` -> removed (annotations/keywords xAI does not list as supported; enforced locally).
 * - `enum`, `const`, `default`, `examples` values are data and are never walked.
 * Circular `$ref` is not supported by xAI and is not rewritten (the app schemas have none).
 */
internal object GrokSchemaAdapter {
    data class Adapted(val schema: JSONObject, val changes: List<String>)

    private val REMOVED_KEYWORDS = setOf("maxContains", "minContains", "\$schema", "deprecated", "uniqueItems")
    private val DATA_KEYWORDS = setOf("enum", "const", "default", "examples")
    private val EMPTY_REMOVED = setOf("enum", "anyOf", "oneOf")
    private val SCHEMA_MAP_KEYWORDS = setOf("properties", "\$defs", "definitions", "patternProperties")

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
                key in EMPTY_REMOVED && value is JSONArray && value.length() == 0 -> {
                    node.remove(key)
                    changes += "$path: removed empty $key"
                }
                key in DATA_KEYWORDS -> Unit
                key == "items" && value is JSONArray -> {
                    node.remove("items")
                    if (!node.has("prefixItems")) node.put("prefixItems", value)
                    changes += "$path: items array -> prefixItems"
                    adaptSchemaArray(node.getJSONArray("prefixItems"), "$path/prefixItems", changes)
                }
                key in SCHEMA_MAP_KEYWORDS && value is JSONObject -> adaptSchemaMap(node, key, value, path, changes)
                value is JSONObject -> adaptSchema(value, "$path/$key", changes)
                value is JSONArray -> adaptSchemaArray(value, "$path/$key", changes)
            }
        }
    }

    private fun adaptSchemaMap(owner: JSONObject, keyword: String, map: JSONObject, path: String, changes: MutableList<String>) {
        for (name in map.keys().asSequence().toList()) {
            val child = map.get(name)
            val childPath = "$path/$keyword/$name"
            when {
                keyword == "properties" && child == java.lang.Boolean.TRUE -> {
                    map.put(name, JSONObject())
                    changes += "$childPath: true -> {}"
                }
                keyword == "properties" && child == java.lang.Boolean.FALSE -> {
                    map.remove(name)
                    owner.optJSONArray("required")?.let { owner.put("required", withoutValue(it, name)) }
                    changes += "$childPath: false property dropped"
                }
                child is JSONObject -> adaptSchema(child, childPath, changes)
            }
        }
    }

    private fun adaptSchemaArray(array: JSONArray, path: String, changes: MutableList<String>) {
        for (index in 0 until array.length()) {
            (array.opt(index) as? JSONObject)?.let { adaptSchema(it, "$path/$index", changes) }
        }
    }

    private fun withoutValue(array: JSONArray, value: String): JSONArray = JSONArray().also { result ->
        for (index in 0 until array.length()) if (array.opt(index) != value) result.put(array.opt(index))
    }
}
