package com.myfitai.app.domain.food

import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionAdviceContractTest {
    @Test
    fun outOfScopeResponse_isAcceptedOnlyWithoutSuggestions() {
        val response = NutritionAdviceContract.Response(
            inScope = false,
            answer = "Posso rispondere solo a richieste di consiglio alimentare e nutrizionale.",
            suggestions = emptyList(),
            assumptions = "",
            agentValidation = NutritionPlanContract.AgentValidation(false, "out of scope"),
        )

        assertTrue(NutritionAdviceContract.validateBusiness(response).isSuccess)
    }
}
