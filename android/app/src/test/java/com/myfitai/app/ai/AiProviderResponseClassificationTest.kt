package com.myfitai.app.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Offline fixtures: provider payloads are classified without any network call. */
class AiProviderResponseClassificationTest {
    private val marker = "ZZ-MODEL-TEXT-ZZ"

    private fun gemini(raw: String) = runCatching { GeminiByokProvider.parseGenerateContent(raw, "gemini-test") }
    private fun openAi(raw: String) = runCatching { OpenAiProvider.parseResponse(raw, "gpt-test") }

    private fun failureOf(result: Result<AiRawResponse>): AiFailure {
        val error = result.exceptionOrNull() ?: throw AssertionError("expected a failure, got ${result.getOrNull()}")
        return AiFailure.classify(error)
    }

    // ---- Gemini ----

    @Test
    fun gemini_normalStopIsASuccess() {
        val raw = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{\"data\":\"ok\"}"}]}}],
            "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}"""
        val response = gemini(raw).getOrThrow()
        assertEquals("{\"data\":\"ok\"}", response.jsonText)
        assertEquals("STOP", response.finishReason)
        assertEquals(5L, response.usage?.outputTokens)
    }

    @Test
    fun gemini_maxTokensIsTruncatedEvenWhenTextIsPresent() {
        val raw = """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"{\"data\":\"MFP1\nM|"}]}}]}"""
        assertEquals(AiFailure.Truncated, failureOf(gemini(raw)))
    }

    @Test
    fun gemini_maxTokensWithoutAnyTextIsStillTruncatedNotEmpty() {
        val raw = """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"","thought":true}]}}]}"""
        assertEquals(AiFailure.Truncated, failureOf(gemini(raw)))
    }

    @Test
    fun gemini_safetyFinishIsARefusalAndNeverCarriesModelText() {
        val raw = """{"candidates":[{"finishReason":"SAFETY","safetyRatings":[{"category":"HARM_CATEGORY_X","probability":"HIGH"}]}]}"""
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.SAFETY), failureOf(gemini(raw)))
        val error = gemini(raw).exceptionOrNull()!!
        assertEquals("REFUSED", error.message)
    }

    @Test
    fun gemini_otherBlockingFinishReasonsAreRefusals() {
        listOf("BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "RECITATION").forEach { reason ->
            val raw = """{"candidates":[{"finishReason":"$reason"}]}"""
            assertEquals(reason, AiFailure.Refused(AiFailure.RefusalReason.SAFETY), failureOf(gemini(raw)))
        }
    }

    @Test
    fun gemini_promptBlockReasonWithoutCandidatesIsARefusal() {
        val raw = """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT","blockReasonMessage":"$marker"}}"""
        val failure = failureOf(gemini(raw))
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.PROMPT_BLOCKED), failure)
        assertFalse(gemini(raw).exceptionOrNull()!!.message.orEmpty().contains(marker))
    }

    @Test
    fun gemini_unspecifiedBlockReasonIsNotARefusal() {
        val raw = """{"promptFeedback":{"blockReason":"BLOCK_REASON_UNSPECIFIED"},
            "candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"{}"}]}}]}"""
        assertEquals("{}", gemini(raw).getOrThrow().jsonText)
    }

    @Test
    fun gemini_stopWithoutTextOrWithOnlyThoughtsIsEmptyOutput() {
        assertEquals(AiFailure.EmptyOutput, failureOf(gemini("""{"candidates":[{"finishReason":"STOP","content":{"parts":[]}}]}""")))
        assertEquals(AiFailure.EmptyOutput, failureOf(gemini("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"x","thought":true}]}}]}""")))
        assertEquals(AiFailure.EmptyOutput, failureOf(gemini("""{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"   "}]}}]}""")))
        assertEquals(AiFailure.EmptyOutput, failureOf(gemini("""{"candidates":[{"content":{"parts":[]}}]}""")))
    }

    @Test
    fun gemini_unknownFinishReasonIsAProtocolErrorNotASuccess() {
        val raw = """{"candidates":[{"finishReason":"SOMETHING_NEW","content":{"parts":[{"text":"{}"}]}}]}"""
        assertEquals(AiFailure.ProviderProtocol, failureOf(gemini(raw)))
    }

    @Test
    fun gemini_notJsonEnvelopeIsAProtocolError() {
        assertEquals(AiFailure.ProviderProtocol, failureOf(gemini("<html>$marker</html>")))
    }

    @Test
    fun gemini_requestCarriesMaxOutputTokensWhenSet() {
        val request = AiStructuredRequest("s", "u", "myfitai_nutrition_advice_pipe_v1", "{}", maxOutputTokens = 900, thinkingBudget = 0)
        val config = GeminiByokProvider.buildGenerationConfig(request, null)
        assertEquals(900, config.getInt("maxOutputTokens"))
        assertEquals("application/json", config.getString("responseMimeType"))
        assertEquals(0, config.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test
    fun gemini_requestOmitsMaxOutputTokensForTheUncappedWeeklyPlan() {
        val request = AiStructuredRequest("s", "u", "myfitai_weekly_nutrition_pipe_v1", "{}", maxOutputTokens = null)
        val config = GeminiByokProvider.buildGenerationConfig(request, JSONObject().put("type", "OBJECT"))
        assertFalse(config.has("maxOutputTokens"))
        assertEquals("OBJECT", config.getJSONObject("responseSchema").getString("type"))
    }

    // ---- OpenAI ----

    @Test
    fun openAi_completedMessageIsASuccess() {
        val raw = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"data\":\"ok\"}"}]}],
            "usage":{"input_tokens":7,"output_tokens":3,"total_tokens":10}}"""
        val response = openAi(raw).getOrThrow()
        assertEquals("{\"data\":\"ok\"}", response.jsonText)
        assertEquals(3L, response.usage?.outputTokens)
        assertNull(response.finishReason)
    }

    @Test
    fun openAi_incompleteMaxOutputTokensIsTruncated() {
        val raw = """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
            "output":[{"type":"message","content":[{"type":"output_text","text":"{\"data\":\"MFP1"}]}]}"""
        assertEquals(AiFailure.Truncated, failureOf(openAi(raw)))
    }

    @Test
    fun openAi_incompleteContentFilterIsARefusal() {
        val raw = """{"status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[]}"""
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.CONTENT_FILTER), failureOf(openAi(raw)))
    }

    @Test
    fun openAi_unknownIncompleteReasonIsAProtocolError() {
        val raw = """{"status":"incomplete","incomplete_details":{"reason":"new_reason"},"output":[]}"""
        assertEquals(AiFailure.ProviderProtocol, failureOf(openAi(raw)))
    }

    @Test
    fun openAi_refusalPartIsARefusalAndItsTextIsNeverForwarded() {
        val raw = """{"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"$marker"}]}]}"""
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL), failureOf(openAi(raw)))
        assertFalse(openAi(raw).exceptionOrNull()!!.message.orEmpty().contains(marker))
    }

    @Test
    fun openAi_refusalWinsOverPartialText() {
        val raw = """{"status":"completed","output":[{"type":"message","content":[
            {"type":"output_text","text":"{}"},{"type":"refusal","refusal":"no"}]}]}"""
        assertEquals(AiFailure.Refused(AiFailure.RefusalReason.MODEL_REFUSAL), failureOf(openAi(raw)))
    }

    @Test
    fun openAi_noTextIsEmptyOutput() {
        assertEquals(AiFailure.EmptyOutput, failureOf(openAi("""{"status":"completed","output":[]}""")))
        assertEquals(AiFailure.EmptyOutput, failureOf(openAi("""{"status":"completed","output":[{"type":"reasoning","summary":[]}]}""")))
        assertEquals(AiFailure.EmptyOutput, failureOf(openAi("""{"status":"completed"}""")))
        assertEquals(AiFailure.EmptyOutput, failureOf(openAi("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":" "}]}]}""")))
    }

    @Test
    fun openAi_failedStatusIsAProtocolErrorWithoutProviderText() {
        val raw = """{"status":"failed","error":{"code":"server_error","message":"$marker"},"output":[]}"""
        assertEquals(AiFailure.ProviderProtocol, failureOf(openAi(raw)))
        assertFalse(openAi(raw).exceptionOrNull()!!.message.orEmpty().contains(marker))
    }

    @Test
    fun openAi_notJsonEnvelopeIsAProtocolError() {
        assertEquals(AiFailure.ProviderProtocol, failureOf(openAi("<html>$marker</html>")))
    }

    @Test
    fun openAi_requestCarriesTheSameOutputBudgetAsGemini() {
        val capped = OpenAiProvider.buildRequestBody("gpt-test", AiStructuredRequest("s", "u", "n", "{\"type\":\"object\"}", maxOutputTokens = 900))
        assertEquals(900, capped.getInt("max_output_tokens"))
        assertEquals("n", capped.getJSONObject("text").getJSONObject("format").getString("name"))
        val uncapped = OpenAiProvider.buildRequestBody("gpt-test", AiStructuredRequest("s", "u", "n", "{\"type\":\"object\"}", maxOutputTokens = null))
        assertFalse(uncapped.has("max_output_tokens"))
        assertFalse(capped.getBoolean("store"))
    }
}
