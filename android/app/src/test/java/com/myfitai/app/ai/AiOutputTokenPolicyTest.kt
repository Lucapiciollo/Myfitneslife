package com.myfitai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiOutputTokenPolicyTest {
    @Test
    fun foodPlansDoNotAcquireFixedCompactCap() {
        assertNull(AiOutputTokenPolicy.effectiveLimit("myfitai_weekly_nutrition_pipe_v1", null))
        assertEquals(
            40_000,
            AiOutputTokenPolicy.effectiveLimit("myfitai_weekly_nutrition_pipe_v1", 40_000),
        )
    }

    @Test
    fun shortCompactTasksKeepTheirGuardrails() {
        assertEquals(900, AiOutputTokenPolicy.effectiveLimit("myfitai_nutrition_advice_pipe_v1", 1_100))
        assertEquals(400, AiOutputTokenPolicy.effectiveLimit("myfitai_nutrition_advice_pipe_v1", 400))
    }

    @Test
    fun nonCompactTasksKeepRequestedLimit() {
        assertEquals(24_576, AiOutputTokenPolicy.effectiveLimit("custom_json", 24_576))
        assertNull(AiOutputTokenPolicy.effectiveLimit("custom_json", null))
    }
}
