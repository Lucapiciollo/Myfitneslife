package com.myfitai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiTransportFailureKindTest {
    @Test
    fun failureKinds_areDistinctForFallbackPolicy() {
        assertEquals(AiTransportFailureKind.MODEL_UNAVAILABLE, AiTransportFailureClassifier.classify(404, "Model not found"))
        assertEquals(AiTransportFailureKind.INVALID_API_KEY, AiTransportFailureClassifier.classify(401, "API key not valid"))
        assertEquals(AiTransportFailureKind.QUOTA_EXHAUSTED, AiTransportFailureClassifier.classify(429, "RESOURCE_EXHAUSTED: quota"))
        assertEquals(AiTransportFailureKind.RATE_LIMITED, AiTransportFailureClassifier.classify(429, "Too many requests"))
        assertEquals(AiTransportFailureKind.SCHEMA, AiTransportFailureClassifier.classify(400, "Invalid argument: schema"))
        assertEquals(AiTransportFailureKind.PROVIDER_UNAVAILABLE, AiTransportFailureClassifier.classify(503, "Service unavailable"))
    }

    @Test
    fun geminiAuthKey_accessTokenTypeUnsupported_isNotTreatedAsInvalidKey() {
        val geminiAuthKeyBody = """
            {"error":{"code":401,"message":"Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential.","status":"UNAUTHENTICATED","details":[{"reason":"ACCESS_TOKEN_TYPE_UNSUPPORTED"}]}}
        """.trimIndent()
        assertEquals(
            AiTransportFailureKind.UNSUPPORTED_AUTH_METHOD,
            AiTransportFailureClassifier.classify(401, geminiAuthKeyBody),
        )
        assertEquals(
            AiTransportFailureKind.UNSUPPORTED_AUTH_METHOD,
            AiTransportFailureClassifier.classify(401, "reason: ACCESS_TOKEN_TYPE_UNSUPPORTED"),
        )
    }

    @Test
    fun genuineInvalidKey_staysInvalidKey() {
        assertEquals(
            AiTransportFailureKind.INVALID_API_KEY,
            AiTransportFailureClassifier.classify(400, "API key not valid. Please pass a valid API key."),
        )
        assertEquals(
            AiTransportFailureKind.INVALID_API_KEY,
            AiTransportFailureClassifier.classify(401, "UNAUTHENTICATED"),
        )
    }
}
