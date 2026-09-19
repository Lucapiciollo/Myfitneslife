package com.myfitai.app.domain.food

import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionPathContractTest {

    @Test
    fun profileOnlyRecommendationCannotClaimHighConfidence() {
        val response = NutritionPathContract.Response(
            recommendation = NutritionPathContract.Suggestion(
                path = "RECOMPOSITION",
                confidence = 0.90f,
                reason = "Profilo base senza dati corporei aggiuntivi",
            ),
            alternatives = emptyList(),
            code = "PROFILE_ONLY",
            explanation = "BIA e misure non disponibili",
            agentValid = true,
        )

        val result = NutritionPathContract.validateBusiness(
            response = response,
            hasBia = false,
            hasBodyMeasurements = false,
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun profileOnlyRecommendationAcceptsPrudentConfidence() {
        val response = NutritionPathContract.Response(
            recommendation = NutritionPathContract.Suggestion(
                path = "RECOMPOSITION",
                confidence = 0.65f,
                reason = "Raccomandazione prudente sui soli dati disponibili",
            ),
            alternatives = emptyList(),
            code = "PROFILE_ONLY",
            explanation = "La BIA potrà affinare il consiglio",
            agentValid = true,
        )

        val result = NutritionPathContract.validateBusiness(
            response = response,
            hasBia = false,
            hasBodyMeasurements = false,
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun invalidAgentSelfValidationIsRejected() {
        val response = NutritionPathContract.Response(
            recommendation = NutritionPathContract.Suggestion(
                path = "MAINTENANCE",
                confidence = 0.50f,
                reason = "Dati insufficienti per una raccomandazione più specifica",
            ),
            alternatives = emptyList(),
            code = "LIMITED_DATA",
            explanation = "Serve conferma utente",
            agentValid = false,
        )

        assertTrue(NutritionPathContract.validateBusiness(response).isFailure)
    }
}
