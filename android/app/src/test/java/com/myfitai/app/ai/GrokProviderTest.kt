package com.myfitai.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Offline tests of the experimental Grok provider: no network, no keys. */
class GrokProviderTest {
    private val marker = "ZZ-MODEL-TEXT-ZZ"
    private val fakeKey = "xai-FAKE-TEST-KEY-0000"

    private fun request(
        schema: String = """{"type":"object","properties":{"data":{"type":"string"}},"required":["data"],"additionalProperties":false}""",
        maxOutputTokens: Int? = 1234,
        image: AiImageInput? = null,
    ) = AiStructuredRequest(
        systemPrompt = "SYS",
        userPrompt = "USER",
        schemaName = "test_schema",
        schemaJson = schema,
        maxOutputTokens = maxOutputTokens,
        image = image,
    )

    private fun parse(raw: String) = runCatching { GrokProvider.parseResponse(raw, "grok-test") }

    private fun failureOf(result: Result<AiRawResponse>): AiFailure =
        AiFailure.classify(result.exceptionOrNull() ?: throw AssertionError("expected a failure"))

    private fun body(
        text: String? = "{\"data\":\"ok\"}",
        status: String = "completed",
        incompleteReason: String? = null,
        refusal: Boolean = false,
        reasoningItem: Boolean = false,
    ): String {
        val output = JSONArray()
        if (reasoningItem) output.put(JSONObject().put("type", "reasoning").put("summary", JSONArray()))
        val content = JSONArray()
        if (refusal) content.put(JSONObject().put("type", "refusal").put("refusal", marker))
        else if (text != null) content.put(JSONObject().put("type", "output_text").put("text", text))
        if (content.length() > 0) output.put(JSONObject().put("type", "message").put("role", "assistant").put("content", content))
        val root = JSONObject().put("status", status).put("output", output)
        incompleteReason?.let { root.put("incomplete_details", JSONObject().put("reason", it)) }
        return root.toString()
    }

    private fun schemaFile(name: String): String {
        val candidates = listOf(File("../../06-ai/schemas/$name"), File("../06-ai/schemas/$name"), File("06-ai/schemas/$name"))
        val file = candidates.firstOrNull { it.isFile } ?: error("schema $name not found from ${File(".").absolutePath}")
        return file.readText(Charsets.UTF_8)
    }

    // ---- buildRequestBody ----

    @Test
    fun requestBody_hasResponsesFieldsStrictSchemaAndNoStorage() {
        val body = GrokProvider.buildRequestBody("grok-4.3", request())
        assertEquals("grok-4.3", body.getString("model"))
        assertEquals("SYS", body.getString("instructions"))
        assertEquals("USER", body.getString("input"))
        assertEquals(false, body.getBoolean("store"))
        assertEquals(1234, body.getInt("max_output_tokens"))
        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals("test_schema", format.getString("name"))
        assertEquals(true, format.getBoolean("strict"))
        assertEquals("object", format.getJSONObject("schema").getString("type"))
        assertFalse(body.has("temperature"))
    }

    @Test
    fun requestBody_omitsOutputCapWhenNullAndNeverContainsAKey() {
        val body = GrokProvider.buildRequestBody("grok-4.3", request(maxOutputTokens = null))
        assertFalse(body.has("max_output_tokens"))
        assertFalse(body.toString().contains(fakeKey))
    }

    @Test
    fun requestBody_imageUsesInputTextAndInputImageDataUrl() {
        val body = GrokProvider.buildRequestBody("grok-4.3", request(image = AiImageInput("image/jpeg", "QUJD")))
        val message = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val parts = message.getJSONArray("content")
        assertEquals("input_text", parts.getJSONObject(0).getString("type"))
        assertEquals("USER", parts.getJSONObject(0).getString("text"))
        assertEquals("input_image", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getString("image_url"))
        assertEquals(false, body.getBoolean("store"))
    }

    @Test
    fun requestBody_sendsAdaptedSchemaButCanonicalRequestIsUntouched() {
        val canonical = """{"${'$'}schema":"x","type":"object","properties":{"a":{"type":"array","uniqueItems":true,"items":{"type":"string"}}},"additionalProperties":false}"""
        val req = request(schema = canonical)
        val sent = GrokProvider.buildRequestBody("grok-4.3", req)
            .getJSONObject("text").getJSONObject("format").getJSONObject("schema")
        assertFalse(sent.has("\$schema"))
        assertFalse(sent.getJSONObject("properties").getJSONObject("a").has("uniqueItems"))
        assertEquals(canonical, req.schemaJson)
    }

    // ---- parseResponse ----

    @Test
    fun parse_completedReturnsTextModelProviderAndUsage() {
        val raw = JSONObject(body(reasoningItem = true)).put(
            "usage",
            JSONObject().put("input_tokens", 32).put("output_tokens", 9).put("total_tokens", 151)
                .put("input_tokens_details", JSONObject().put("cached_tokens", 8))
                .put("output_tokens_details", JSONObject().put("reasoning_tokens", 110)),
        ).toString()
        val response = parse(raw).getOrThrow()
        assertEquals("{\"data\":\"ok\"}", response.jsonText)
        assertEquals(AiProviderType.GROK, response.provider)
        assertEquals("grok-test", response.model)
        assertEquals(32L, response.usage?.inputTokens)
        assertEquals(9L, response.usage?.outputTokens)
        assertEquals(151L, response.usage?.totalTokens)
        assertEquals(110L, response.usage?.thoughtsTokens)
        assertEquals(8L, response.usage?.cachedTokens)
    }

    @Test
    fun parse_acceptsChatStyleUsageNamesAndMissingUsage() {
        val chatStyle = JSONObject(body()).put(
            "usage",
            JSONObject().put("prompt_tokens", 5).put("completion_tokens", 2).put("total_tokens", 7)
                .put("prompt_tokens_details", JSONObject().put("cached_tokens", 1))
                .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 3)),
        ).toString()
        val usage = parse(chatStyle).getOrThrow().usage
        assertEquals(5L, usage?.inputTokens)
        assertEquals(2L, usage?.outputTokens)
        assertEquals(3L, usage?.thoughtsTokens)
        assertEquals(1L, usage?.cachedTokens)
        assertNull(parse(body()).getOrThrow().usage)
    }

    @Test
    fun parse_incompleteMaxOutputTokensIsTruncatedEvenWithPartialText() {
        val raw = body(text = "{\"data\":\"par", status = "incomplete", incompleteReason = "max_output_tokens")
        assertEquals(AiFailure.Truncated, failureOf(parse(raw)))
    }

    @Test
    fun parse_contentFilterAndRefusalAreRefusalsWithoutProviderText() {
        val filtered = parse(body(text = null, status = "incomplete", incompleteReason = "content_filter"))
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.CONTENT_FILTER), failureOf(filtered))
        val refusal = parse(body(refusal = true))
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL), failureOf(refusal))
        assertFalse(refusal.exceptionOrNull()!!.message.orEmpty().contains(marker))
    }

    @Test
    fun parse_emptyOrReasoningOnlyOutputIsEmptyOutput() {
        assertEquals(AiFailure.EmptyOutput, failureOf(parse(body(text = ""))))
        assertEquals(AiFailure.EmptyOutput, failureOf(parse(body(text = null, reasoningItem = true))))
        assertEquals(AiFailure.EmptyOutput, failureOf(parse("""{"status":"completed"}""")))
    }

    @Test
    fun parse_failedUnknownStatusAndNonJsonAreProtocolErrorsWithoutLeakingText() {
        assertEquals(AiFailure.ProviderProtocol, failureOf(parse(body(status = "failed", text = marker))))
        val html = parse("<html>bad gateway $marker $fakeKey")
        assertEquals(AiFailure.ProviderProtocol, failureOf(html))
        val message = html.exceptionOrNull()!!.message.orEmpty()
        assertFalse(message.contains(marker))
        assertFalse(message.contains(fakeKey))
    }

    @Test
    fun httpErrorsShareTheCommonClassificationAndNeverCarryTheKey() {
        fun http(status: Int, text: String) = AiTransportException.Http(AiProviderType.GROK, status, AiTransportFailureClassifier.classify(status, text))
        assertEquals(AiFailure.Auth, AiFailure.classify(http(401, """{"error":"Incorrect API key $fakeKey"}""")))
        assertEquals(AiFailure.RateLimited, AiFailure.classify(http(429, """{"error":"Rate limit reached"}""")))
        assertEquals(AiFailure.Quota, AiFailure.classify(http(429, """{"error":"quota exceeded, check billing"}""")))
        assertEquals(AiFailure.ProviderUnavailable, AiFailure.classify(http(503, """{"error":"overloaded"}""")))
        assertEquals(AiFailure.ModelUnavailable, AiFailure.classify(http(404, """{"error":"The model does not exist"}""")))
        assertFalse(http(401, fakeKey).message.orEmpty().contains(fakeKey))
        assertTrue(AiTransportException.NotConfigured(AiProviderType.GROK).message.orEmpty().contains("GROK"))
    }

    // ---- GrokSchemaAdapter ----

    @Test
    fun adapter_removesRejectedKeywordsAndConvertsTupleItems() {
        val canonical = """{
          "type":"object",
          "properties":{
            "tuple":{"type":"array","items":[{"type":"string"},{"type":"integer","uniqueItems":true}]},
            "list":{"type":"array","items":{"type":"string"},"contains":{"type":"string"},"maxContains":2,"minContains":1},
            "kind":{"enum":[],"type":"string"},
            "either":{"anyOf":[]},
            "free":true,
            "never":false,
            "enum":{"type":"string","enum":["a","b"],"const":"a"}
          },
          "required":["tuple","never","free"],
          "additionalProperties":false
        }"""
        val adapted = GrokSchemaAdapter.adapt(canonical)
        val props = adapted.schema.getJSONObject("properties")
        val tuple = props.getJSONObject("tuple")
        assertFalse(tuple.has("items"))
        assertEquals("string", tuple.getJSONArray("prefixItems").getJSONObject(0).getString("type"))
        assertFalse(tuple.getJSONArray("prefixItems").getJSONObject(1).has("uniqueItems"))
        val list = props.getJSONObject("list")
        assertFalse(list.has("maxContains")); assertFalse(list.has("minContains"))
        assertEquals("string", list.getJSONObject("items").getString("type"))
        assertFalse(props.getJSONObject("kind").has("enum"))
        assertFalse(props.getJSONObject("either").has("anyOf"))
        assertEquals(0, props.getJSONObject("free").length())
        assertFalse(props.has("never"))
        assertEquals(listOf("tuple", "free"), (0 until adapted.schema.getJSONArray("required").length()).map { adapted.schema.getJSONArray("required").getString(it) })
        // a PROPERTY called "enum" is not the keyword: its own enum/const values are data and survive
        assertEquals(2, props.getJSONObject("enum").getJSONArray("enum").length())
        assertEquals("a", props.getJSONObject("enum").getString("const"))
        assertTrue(adapted.changes.isNotEmpty())
    }

    @Test
    fun adapter_leavesAGrokCompatibleSchemaUnchanged() {
        val compatible = """{"type":"object","properties":{"data":{"type":"string","minLength":1}},"required":["data"],"additionalProperties":false}"""
        val adapted = GrokSchemaAdapter.adapt(compatible)
        assertEquals(JSONObject(compatible).toString(), adapted.schema.toString())
        assertTrue(adapted.changes.isEmpty())
    }

    @Test
    fun adapter_realSchemasKeepStructureAndOnlyLoseDocumentedKeywords() {
        listOf("weekly-meal-plan.schema.json", "plan-adjustment.schema.json", "progress-review.schema.json").forEach { name ->
            val canonical = schemaFile(name)
            val adapted = GrokSchemaAdapter.adapt(canonical)
            // only the allowed removals/renames happened
            adapted.changes.forEach { change ->
                assertTrue("$name unexpected change: $change", REMOVAL.containsMatchIn(change))
            }
            assertFalse(name, adapted.schema.toString().contains("\"uniqueItems\""))
            assertFalse(name, adapted.schema.toString().contains("\"\$schema\""))
            assertFalse(name, adapted.schema.toString().contains("\"deprecated\""))
            // structure, enums and numeric/array bounds are preserved
            assertEquals(JSONObject(canonical).getJSONObject("properties").names().toString(), adapted.schema.getJSONObject("properties").names().toString())
            assertEquals(countKeyword(JSONObject(canonical), "enum"), countKeyword(adapted.schema, "enum"))
            assertEquals(countKeyword(JSONObject(canonical), "minItems"), countKeyword(adapted.schema, "minItems"))
            assertEquals(countKeyword(JSONObject(canonical), "minimum"), countKeyword(adapted.schema, "minimum"))
            // the request built from it carries the adapted schema and strict mode
            val format = GrokProvider.buildRequestBody("grok-4.3", request(schema = canonical))
                .getJSONObject("text").getJSONObject("format")
            assertTrue(format.getBoolean("strict"))
            assertEquals(adapted.schema.toString(), format.getJSONObject("schema").toString())
        }
    }

    // ---- config ----

    @Test
    fun modelConfig_grokDefaultIsSelectableAndOtherProvidersAreUnchanged() {
        assertTrue(AiModelConfig.GROK in AiModelConfig.GROK_SELECTABLE)
        assertEquals("grok-4.3", AiModelConfig.GROK)
        assertEquals("Grok 4.7", AiModelConfig.displayName(AiModelConfig.GROK_4_7))
        assertEquals(6, AiModelConfig.GEMINI_SELECTABLE.size)
        assertEquals(4, AiModelConfig.OPENAI_SELECTABLE.size)
        assertEquals(AiModelConfig.OPENAI_GPT_5_NANO, AiModelConfig.OPENAI)
        assertEquals(AiModelConfig.GEMINI_35_FLASH_LITE, AiModelConfig.GEMINI_PRIMARY)
    }

    private fun countKeyword(node: Any?, keyword: String): Int = when (node) {
        is JSONObject -> node.keys().asSequence().sumOf { key ->
            val child = node.get(key)
            if (key == "properties" && child is JSONObject) child.keys().asSequence().sumOf { countKeyword(child.get(it), keyword) }
            else (if (key == keyword) 1 else 0) + countKeyword(child, keyword)
        }
        is JSONArray -> (0 until node.length()).sumOf { countKeyword(node.get(it), keyword) }
        else -> 0
    }

    private companion object {
        val REMOVAL = Regex("removed (uniqueItems|\\\$schema|deprecated|maxContains|minContains)|items array -> prefixItems")
    }
}
