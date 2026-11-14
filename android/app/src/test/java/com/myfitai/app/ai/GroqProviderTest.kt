package com.myfitai.app.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Offline tests of the experimental Groq provider: no network, no keys. */
class GroqProviderTest {
    private val marker = "ZZ-MODEL-TEXT-ZZ"
    private val fakeKey = "gsk_FAKE_TEST_KEY_0000"
    private val gptOss = AiModelConfig.GROQ_GPT_OSS_20B
    private val qwen = AiModelConfig.GROQ_QWEN_38_27B

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

    private fun parse(raw: String) = runCatching { GroqProvider.parseResponse(raw, "groq-test") }

    private fun failureOf(result: Result<AiRawResponse>): AiFailure =
        AiFailure.classify(result.exceptionOrNull() ?: throw AssertionError("expected a failure"))

    private fun body(
        content: String? = "{\"data\":\"ok\"}",
        finishReason: String? = "stop",
        refusal: String? = null,
        usage: JSONObject? = null,
    ): String {
        val message = JSONObject().put("role", "assistant")
        message.put("content", content ?: JSONObject.NULL)
        refusal?.let { message.put("refusal", it) }
        val choice = JSONObject().put("index", 0).put("message", message)
        choice.put("finish_reason", finishReason ?: JSONObject.NULL)
        val root = JSONObject().put("choices", JSONArray().put(choice))
        usage?.let { root.put("usage", it) }
        return root.toString()
    }

    private fun schemaFile(name: String): String {
        val candidates = listOf(File("../../06-ai/schemas/$name"), File("../06-ai/schemas/$name"), File("06-ai/schemas/$name"))
        val file = candidates.firstOrNull { it.isFile } ?: error("schema $name not found from ${File(".").absolutePath}")
        return file.readText(Charsets.UTF_8)
    }

    // ---- buildRequestBody ----

    @Test
    fun requestBody_hasChatCompletionsFieldsAndStrictJsonSchema() {
        val body = GroqProvider.buildRequestBody(gptOss, request())
        assertEquals(gptOss, body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("SYS", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("USER", messages.getJSONObject(1).getString("content"))
        assertEquals(1234, body.getInt("max_completion_tokens"))
        val format = body.getJSONObject("response_format")
        assertEquals("json_schema", format.getString("type"))
        val jsonSchema = format.getJSONObject("json_schema")
        assertEquals("test_schema", jsonSchema.getString("name"))
        assertEquals(true, jsonSchema.getBoolean("strict"))
        assertEquals("object", jsonSchema.getJSONObject("schema").getString("type"))
        // streaming and tool use are not supported together with structured outputs
        assertFalse(body.has("stream"))
        assertFalse(body.has("tools"))
        assertFalse(body.has("temperature"))
    }

    @Test
    fun requestBody_reasoningEffortLowForGptOssOmittedForQwenAndValidated() {
        assertEquals("low", GroqProvider.buildRequestBody(gptOss, request()).getString("reasoning_effort"))
        assertEquals("low", GroqProvider.buildRequestBody(AiModelConfig.GROQ_GPT_OSS_120B, request()).getString("reasoning_effort"))
        assertFalse(GroqProvider.buildRequestBody(qwen, request()).has("reasoning_effort"))
        assertFalse(GroqProvider.buildRequestBody(gptOss, request(), null).has("reasoning_effort"))
        assertEquals("high", GroqProvider.buildRequestBody(gptOss, request(), "high").getString("reasoning_effort"))
        assertThrows(IllegalArgumentException::class.java) { GroqProvider.buildRequestBody(gptOss, request(), "turbo") }
    }

    @Test
    fun requestBody_omitsOutputCapWhenNullAndNeverContainsAKey() {
        val body = GroqProvider.buildRequestBody(gptOss, request(maxOutputTokens = null))
        assertFalse(body.has("max_completion_tokens"))
        assertFalse(body.toString().contains(fakeKey))
    }

    @Test
    fun requestBody_imageUsesImageUrlDataUrlOnlyOnTheVisionModel() {
        val body = GroqProvider.buildRequestBody(qwen, request(image = AiImageInput("image/jpeg", "QUJD")))
        val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("USER", parts.getJSONObject(0).getString("text"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun requestBody_photoOnATextOnlyModelFailsLocallyWithoutLeakingTheImage() {
        for (model in listOf(AiModelConfig.GROQ_GPT_OSS_20B, AiModelConfig.GROQ_GPT_OSS_120B)) {
            val error = assertThrows(AiTransportException.Http::class.java) {
                GroqProvider.buildRequestBody(model, request(image = AiImageInput("image/jpeg", "QUJD-SECRET-PIXELS")))
            }
            assertEquals(AiFailure.ModelUnavailable, AiFailure.classify(error))
            assertFalse(error.message.orEmpty().contains("QUJD-SECRET-PIXELS"))
        }
    }

    @Test
    fun requestBody_sendsAdaptedSchemaButCanonicalRequestIsUntouched() {
        val canonical = """{"${'$'}schema":"x","type":"object","properties":{"a":{"type":"array","uniqueItems":true,"items":{"type":"string"}},"b":{"type":"string","minLength":1}},"required":["a"],"additionalProperties":false}"""
        val req = request(schema = canonical)
        val sent = GroqProvider.buildRequestBody(gptOss, req)
            .getJSONObject("response_format").getJSONObject("json_schema").getJSONObject("schema")
        assertFalse(sent.has("\$schema"))
        assertFalse(sent.getJSONObject("properties").getJSONObject("a").has("uniqueItems"))
        assertEquals(2, sent.getJSONArray("required").length())
        assertEquals(canonical, req.schemaJson)
    }

    // ---- parseResponse ----

    @Test
    fun parse_completedReturnsTextModelProviderAndUsage() {
        val usage = JSONObject().put("prompt_tokens", 4641).put("completion_tokens", 1817).put("total_tokens", 6458)
            .put("prompt_tokens_details", JSONObject().put("cached_tokens", 4608))
            .put("queue_time", 0.02)
        val response = parse(body(usage = usage)).getOrThrow()
        assertEquals("{\"data\":\"ok\"}", response.jsonText)
        assertEquals(AiProviderType.GROQ, response.provider)
        assertEquals("groq-test", response.model)
        assertEquals(4641L, response.usage?.inputTokens)
        assertEquals(1817L, response.usage?.outputTokens)
        assertEquals(6458L, response.usage?.totalTokens)
        assertEquals(4608L, response.usage?.cachedTokens)
        assertNull(response.usage?.thoughtsTokens)
    }

    @Test
    fun parse_missingUsageAndOptionalReasoningDetail() {
        assertNull(parse(body()).getOrThrow().usage)
        val withReasoning = JSONObject().put("prompt_tokens", 5).put("completion_tokens", 20).put("total_tokens", 25)
            .put("completion_tokens_details", JSONObject().put("reasoning_tokens", 12))
        assertEquals(12L, parse(body(usage = withReasoning)).getOrThrow().usage?.thoughtsTokens)
    }

    @Test
    fun parse_lengthIsTruncatedEvenWithPartialText() {
        assertEquals(AiFailure.Truncated, failureOf(parse(body(content = "{\"data\":\"par", finishReason = "length"))))
    }

    @Test
    fun parse_contentFilterAndRefusalAreRefusalsWithoutProviderText() {
        val filtered = parse(body(content = null, finishReason = "content_filter"))
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.CONTENT_FILTER), failureOf(filtered))
        val refusal = parse(body(content = null, refusal = marker))
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL), failureOf(refusal))
        assertFalse(refusal.exceptionOrNull()!!.message.orEmpty().contains(marker))
    }

    @Test
    fun parse_emptyNullOrMissingContentIsEmptyOutput() {
        assertEquals(AiFailure.EmptyOutput, failureOf(parse(body(content = ""))))
        assertEquals(AiFailure.EmptyOutput, failureOf(parse(body(content = "  \n"))))
        assertEquals(AiFailure.EmptyOutput, failureOf(parse(body(content = null))))
        assertEquals(AiFailure.EmptyOutput, failureOf(parse("""{"choices":[]}""")))
        assertEquals(AiFailure.EmptyOutput, failureOf(parse("""{}""")))
    }

    @Test
    fun parse_unknownFinishReasonErrorObjectAndNonJsonAreProtocolErrorsWithoutLeakingText() {
        assertEquals(AiFailure.ProviderProtocol, failureOf(parse(body(content = marker, finishReason = "tool_calls"))))
        assertEquals(AiFailure.ProviderProtocol, failureOf(parse("""{"error":{"message":"$marker $fakeKey"}}""")))
        val html = parse("<html>bad gateway $marker $fakeKey")
        assertEquals(AiFailure.ProviderProtocol, failureOf(html))
        val message = html.exceptionOrNull()!!.message.orEmpty()
        assertFalse(message.contains(marker))
        assertFalse(message.contains(fakeKey))
    }

    @Test
    fun httpErrorsShareTheCommonClassificationAndNeverCarryTheKey() {
        fun http(status: Int, text: String) = AiTransportException.Http(AiProviderType.GROQ, status, AiTransportFailureClassifier.classify(status, text))
        assertEquals(AiFailure.Auth, AiFailure.classify(http(401, """{"error":{"message":"Invalid API Key $fakeKey"}}""")))
        assertEquals(AiFailure.RateLimited, AiFailure.classify(http(429, """{"error":{"message":"Rate limit reached for model"}}""")))
        assertEquals(AiFailure.Quota, AiFailure.classify(http(429, """{"error":{"message":"quota exceeded, check billing"}}""")))
        assertEquals(AiFailure.ProviderUnavailable, AiFailure.classify(http(503, """{"error":{"message":"overloaded"}}""")))
        assertEquals(AiFailure.ModelUnavailable, AiFailure.classify(http(404, """{"error":{"message":"The model does not exist"}}""")))
        // best-effort structured outputs: 400 json_validate_failed is a protocol/validation error, never a success and never copied
        val validate = http(400, """{"error":{"message":"Generated JSON does not match the expected schema. Please adjust your prompt.","type":"invalid_request_error","code":"json_validate_failed","failed_generation":"$marker"}}""")
        assertEquals(AiFailure.ProviderProtocol, AiFailure.classify(validate))
        assertFalse(validate.message.orEmpty().contains(marker))
        assertFalse(http(401, fakeKey).message.orEmpty().contains(fakeKey))
        assertTrue(AiTransportException.NotConfigured(AiProviderType.GROQ).message.orEmpty().contains("GROQ"))
    }

    // ---- GroqSchemaAdapter ----

    @Test
    fun adapter_makesEveryObjectStrictAndOptionalPropertiesNullable() {
        val canonical = """{
          "type":"object",
          "properties":{
            "need":{"type":"string","minLength":1,"pattern":"^a"},
            "opt":{"type":"array","items":{"type":"string"},"uniqueItems":true,"minItems":1},
            "choice":{"enum":["A","B"]},
            "already":{"type":["number","null"],"minimum":0},
            "kind":{"const":"X"},
            "nested":{"type":"object","properties":{"k":{"type":"boolean"}}},
            "either":{"oneOf":[{"type":"string"},{"type":"integer","maximum":3}]}
          },
          "required":["need","kind","either"]
        }"""
        val adapted = GroqSchemaAdapter.adapt(canonical)
        val schema = adapted.schema
        val props = schema.getJSONObject("properties")
        assertEquals(false, schema.getBoolean("additionalProperties"))
        assertEquals(props.keys().asSequence().toSet(), strings(schema.getJSONArray("required")).toSet())
        assertFalse(props.getJSONObject("need").has("minLength")); assertFalse(props.getJSONObject("need").has("pattern"))
        // optional -> union with null, original schema kept inside (without the removed keywords)
        val opt = props.getJSONObject("opt").getJSONArray("anyOf")
        assertEquals("array", opt.getJSONObject(0).getString("type"))
        assertFalse(opt.getJSONObject(0).has("uniqueItems")); assertFalse(opt.getJSONObject(0).has("minItems"))
        assertEquals("null", opt.getJSONObject(1).getString("type"))
        assertTrue(props.getJSONObject("choice").has("anyOf"))
        // a property that already accepts null is not wrapped again
        assertFalse(props.getJSONObject("already").has("anyOf"))
        assertFalse(props.getJSONObject("already").has("minimum"))
        // const -> enum, oneOf -> anyOf
        assertEquals("X", props.getJSONObject("kind").getJSONArray("enum").getString(0))
        assertFalse(props.getJSONObject("kind").has("const"))
        assertFalse(props.getJSONObject("either").has("oneOf"))
        assertEquals(2, props.getJSONObject("either").getJSONArray("anyOf").length())
        assertFalse(props.getJSONObject("either").getJSONArray("anyOf").getJSONObject(1).has("maximum"))
        // nested object is closed too (inside the nullable wrapper)
        val nested = props.getJSONObject("nested").getJSONArray("anyOf").getJSONObject(0)
        assertEquals(false, nested.getBoolean("additionalProperties"))
        assertEquals("k", nested.getJSONArray("required").getString(0))
        assertTrue(adapted.changes.isNotEmpty())
    }

    @Test
    fun adapter_leavesTheCompactEnvelopeUnchanged() {
        val envelope = AiCompactEnvelope.schemaJson("PR1 pipe protocol")
        val adapted = GroqSchemaAdapter.adapt(envelope)
        assertEquals(JSONObject(envelope).toString(), adapted.schema.toString())
        assertTrue(adapted.changes.isEmpty())
    }

    @Test
    fun adapter_enumValuesNamedLikeKeywordsAreDataAndPropertiesNamedLikeKeywordsSurvive() {
        val canonical = """{"type":"object","properties":{"pattern":{"type":"string","enum":["const","format"]},"format":{"type":"string"}},"required":["pattern","format"],"additionalProperties":false}"""
        val adapted = GroqSchemaAdapter.adapt(canonical)
        assertEquals(JSONObject(canonical).toString(), adapted.schema.toString())
    }

    @Test
    fun adapter_realSchemasEndStrictCompatibleAndCanonicalTextIsUntouched() {
        listOf("weekly-meal-plan.schema.json", "plan-adjustment.schema.json", "plan-review.schema.json", "progress-review.schema.json").forEach { name ->
            val canonical = schemaFile(name)
            val before = JSONObject(canonical)
            val adapted = GroqSchemaAdapter.adapt(canonical)
            var objects = 0
            forEachSchemaNode(adapted.schema) { node ->
                REMOVED.forEach { keyword -> assertFalse("$name still has $keyword", node.has(keyword)) }
                assertFalse("$name still has const/oneOf", node.has("const") || node.has("oneOf"))
                node.optJSONObject("properties")?.let { properties ->
                    objects++
                    assertEquals("$name additionalProperties", false, node.opt("additionalProperties"))
                    assertEquals("$name required", properties.keys().asSequence().toSet(), strings(node.getJSONArray("required")).toSet())
                }
            }
            assertTrue("$name should contain objects", objects > 0)
            // top-level structure is preserved: same property names, same root type
            assertEquals(before.getJSONObject("properties").keys().asSequence().toSet(), adapted.schema.getJSONObject("properties").keys().asSequence().toSet())
            assertEquals("object", adapted.schema.getString("type"))
            // the request built from it carries the adapted schema in strict mode
            val format = GroqProvider.buildRequestBody(gptOss, request(schema = canonical)).getJSONObject("response_format").getJSONObject("json_schema")
            assertTrue(format.getBoolean("strict"))
            assertEquals(adapted.schema.toString(), format.getJSONObject("schema").toString())
        }
    }

    @Test
    fun adapter_weeklyPlanOptionalFieldsBecomeNullableAndRequiredFieldsStayAsTheyWere() {
        val adapted = GroqSchemaAdapter.adapt(schemaFile("weekly-meal-plan.schema.json")).schema
        val props = adapted.getJSONObject("properties")
        assertTrue(props.getJSONObject("needsInput").has("anyOf"))
        assertTrue(props.getJSONObject("agentValidation").has("anyOf"))
        assertFalse(props.getJSONObject("weekStart").has("anyOf"))
        assertEquals(setOf("OK", "NEEDS_INPUT", "OUT_OF_SCOPE", "CANNOT_SAFELY_GENERATE"), strings(props.getJSONObject("status").getJSONArray("enum")).toSet())
        assertEquals("GENERATE_WEEKLY_PLAN", props.getJSONObject("operation").getJSONArray("enum").getString(0))
    }

    // ---- config ----

    @Test
    fun modelConfig_groqDefaultIsSelectableCheapestStrictAndOtherProvidersAreUnchanged() {
        assertEquals("openai/gpt-oss-20b", AiModelConfig.GROQ)
        assertTrue(AiModelConfig.GROQ in AiModelConfig.GROQ_SELECTABLE)
        assertEquals(3, AiModelConfig.GROQ_SELECTABLE.size)
        assertEquals("Qwen 3.8 27B (anteprima)", AiModelConfig.displayName(AiModelConfig.GROQ_QWEN_38_27B))
        assertEquals(listOf(false, false, true), AiModelConfig.GROQ_SELECTABLE.map(AiModelConfig::groqSupportsImages))
        assertEquals(6, AiModelConfig.GEMINI_SELECTABLE.size)
        assertEquals(4, AiModelConfig.OPENAI_SELECTABLE.size)
        assertEquals(2, AiModelConfig.GROK_SELECTABLE.size)
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    /** Visits schema nodes only (root, properties values, items, anyOf elements), never property names. */
    private fun forEachSchemaNode(node: JSONObject, visit: (JSONObject) -> Unit) {
        visit(node)
        node.optJSONObject("properties")?.let { p -> p.keys().asSequence().forEach { (p.get(it) as? JSONObject)?.let { child -> forEachSchemaNode(child, visit) } } }
        node.optJSONObject("items")?.let { forEachSchemaNode(it, visit) }
        node.optJSONArray("anyOf")?.let { a -> (0 until a.length()).forEach { (a.opt(it) as? JSONObject)?.let { child -> forEachSchemaNode(child, visit) } } }
    }

    private companion object {
        val REMOVED = listOf(
            "\$schema", "deprecated", "uniqueItems", "pattern", "format", "minLength", "maxLength", "minimum", "maximum",
            "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems", "maxItems", "minProperties", "maxProperties",
            "contains", "minContains", "maxContains",
        )
    }
}
