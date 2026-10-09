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

    @Test fun mealAlternatives_recoversHeaderlessSingleLineRecordStream() {
        val payload = buildString {
            repeat(5) { i ->
                append("A|Opzione $i|500|30|60|15|prepara|breve|")
                append("I|Riso|80|g|80 g|DRY|HIGH|CEREALS|")
            }
            append("V|1|corretto")
        }

        val value = MealAlternativeCompactContract.parse(envelope(payload))

        assertEquals(5, value.alternatives.size)
        assertEquals("Opzione 0", value.alternatives.first().title)
        assertEquals("CEREALS", value.alternatives.first().ingredients.single().category)
        assertTrue(value.agentValidation.valid)
    }

    @Test fun mealAlternatives_splitsAttachedHeaderFromFirstRecord() {
        val payload = buildString {
            append("MA1 A|Opzione 1|500|30|60|15|prepara|breve|")
            append("I|Riso|80|g|80 g|DRY|HIGH|CEREALS|")
            repeat(4) { i ->
                append("A|Opzione ${i + 2}|500|30|60|15|prepara|breve|")
                append("I|Riso $i|80|g|80 g|DRY|HIGH|CEREALS|")
            }
            append("V|1|corretto")
        }

        assertEquals(5, MealAlternativeCompactContract.parse(envelope(payload)).alternatives.size)
    }

    @Test fun mealAlternatives_mergesMultipleValidationRecordsWithoutLosingBusinessChecks() {
        val payload = buildString {
            appendLine("MA1")
            repeat(5) { i ->
                appendLine("A|Opzione $i|500|30|60|15|prepara|breve")
                appendLine("I|Riso|80|g|80 g|DRY|HIGH|CEREALS")
            }
            appendLine("V|1|valid")
            append("V|1|notes")
        }
        val value = MealAlternativeCompactContract.parse(envelope(payload))
        assertEquals(5, value.alternatives.size)
        assertTrue(value.agentValidation.valid)
        assertTrue(value.agentValidation.notes.contains("valid"))
    }

    @Test fun nutritionAdvice_parsesFiveOptions() {
        val payload = buildString {
            appendLine("NA1")
            appendLine("S|1")
            appendLine("A|Cinque scelte compatte")
            repeat(5) { i -> appendLine("O|Scelta $i|coerente|300|25|30|8|riso,pollo") }
            appendLine("Q|")
            append("V|1|")
        }
        val value = NutritionAdviceCompactContract.parse(envelope(payload))
        assertTrue(value.inScope)
        assertEquals(5, value.suggestions.size)
        assertEquals(listOf("riso", "pollo"), value.suggestions.first().foods)
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
