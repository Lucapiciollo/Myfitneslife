package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionPathContractTest {
    @Test
    fun parsesRecommendationAndCapsAlternativesToTwo() {
        val response = NutritionPathContract.parse(
            org.json.JSONObject().put("data", """
                NP1
                R|RECOMPOSITION|0,85|Dati coerenti con un percorso graduale.
                A|MAINTENANCE|0.60|Alternativa prudente se il peso resta stabile.
                A|PERFORMANCE|0.40|Alternativa se la priorita diventa la prestazione.
                A|WEIGHT_LOSS|0.20|Non deve comparire oltre il limite.
                C|OK|Contesto sufficiente.
                V|1|Verificato
            """.trimIndent()).toString(),
        )

        assertEquals("RECOMPOSITION", response.recommendation.path)
        assertEquals(2, response.alternatives.size)
        assertTrue(NutritionPathContract.validateBusiness(response).isSuccess)
    }

    @Test
    fun rejectsUnknownPath() {
        val response = NutritionPathContract.Response(
            recommendation = NutritionPathContract.Suggestion("DIAGNOSIS", 0.9f, "No"),
            alternatives = emptyList(),
            code = "INVALID",
            explanation = "No",
            agentValid = false,
        )

        assertFalse(NutritionPathContract.validateBusiness(response).isSuccess)
    }

    @Test
    fun rejectsConfidenceOutsideRange() {
        val response = NutritionPathContract.Response(
            recommendation = NutritionPathContract.Suggestion("MAINTENANCE", 1.1f, "No"),
            alternatives = emptyList(),
            code = "INVALID",
            explanation = "No",
            agentValid = false,
        )

        assertFalse(NutritionPathContract.validateBusiness(response).isSuccess)
    }
}
