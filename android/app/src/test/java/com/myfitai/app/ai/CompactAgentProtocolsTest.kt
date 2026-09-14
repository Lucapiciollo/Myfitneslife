package com.myfitai.app.ai

import com.myfitai.app.domain.body.BiaImportCompactContract
import com.myfitai.app.domain.food.CheatAdjustmentCompactContract
import com.myfitai.app.domain.food.CheatUnderstandingCompactContract
import com.myfitai.app.domain.food.MealAlternativeCompactContract
import com.myfitai.app.domain.food.NutritionAdviceCompactContract
import com.myfitai.app.domain.review.WeeklyReviewCompactContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactAgentProtocolsTest {
    private fun envelope(data: String) = JSONObject().put("data", data).toString()

    @Test fun bia_parsesCompactEnvelope() {
        val value = BiaImportCompactContract.parseEnvelope(envelope("BIA1\nB|1|?|89.0|18.0|7|68|35|58|1900|HIGH||leggibile"))
        assertTrue(value.isBiaDocument)
        assertEquals(89f, value.weightKg)
    }

    @Test fun cheatUnderstanding_parsesCompactEnvelope() {
        val value = CheatUnderstandingCompactContract.parse(envelope("CU1\nU|pizza margherita 1\nE|800|30|90|30|medium|stima"))
        assertEquals(800, value.estimate.kcal)
    }

    @Test fun cheatAdjustment_supportsNoAdaptation() {
        val value = CheatAdjustmentCompactContract.parse(envelope("CA1\nE|800|30|90|30|medium|stima\nA|0|nessun pasto futuro\nV|1|"))
        assertFalse(value.adaptationPossible)
        assertTrue(value.replacementMeals.isEmpty())
    }

    @Test fun mealAlternatives_groupIngredientsByAlternative() {
        val payload = buildString {
            appendLine("MA1")
            repeat(5) { i ->
                appendLine("A|Opzione $i|500|30|60|15|prepara|breve")
                appendLine("I|Riso|80|g|80 g|DRY|HIGH|CEREALS")
            }
            append("V|1|")
        }
        val value = MealAlternativeCompactContract.parse(envelope(payload))
        assertEquals(5, value.alternatives.size)
        assertEquals("Riso", value.alternatives.first().ingredients.first().name)
    }

    @Test fun nutritionAdvice_parsesFiveOptions() {
        val payload = buildString {
            appendLine("NA1")
            appendLine("S|1")
            appendLine("A|Cinque scelte compatte")
            repeat(5) { i -> appendLine("O|Scelta $i|coerente|300|25|30|8") }
            appendLine("Q|")
            append("V|1|")
        }
        val value = NutritionAdviceCompactContract.parse(envelope(payload))
        assertTrue(value.inScope)
        assertEquals(5, value.suggestions.size)
    }

    @Test fun weeklyReview_parsesCompactRows() {
        val value = WeeklyReviewCompactContract.parse(envelope("WR1\nW|20710\nS|Settimana coerente\nO|Target pianificati vicini\nG|Mantieni struttura\nV|1|"))
        assertEquals(20710L, value.weekStartEpochDay)
        assertEquals(1, value.observations.size)
    }

    @Test fun malformedPipe_isRejected() {
        assertTrue(runCatching { NutritionAdviceCompactContract.parse(envelope("NA1\nS|1|extra")) }.isFailure)
    }
}
