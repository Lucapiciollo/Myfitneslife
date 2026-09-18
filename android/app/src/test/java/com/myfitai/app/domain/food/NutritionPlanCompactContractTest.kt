package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NutritionPlanCompactContractTest {
    @Test
    fun compactEnvelope_parsesWithoutRepeatedJsonFields() {
        val date = LocalDate.of(2026, 9, 14).toEpochDay()
        val payload = """
            MFP1
            W|$date
            D|$date|2400|160|280|70
            M|BREAKFAST|Yogurt avena e banana|480|500|35|62|12|Mescola e servi
            I|Yogurt greco|200|g|1 vasetto da 200 g|NET|HIGH|DAIRY
            I|Avena|60|g|60 g|DRY|HIGH|CEREALS
            M|SNACK|Mela e mandorle|600|300|15|40|12|Servi insieme
            I|Mela|150|g|1 mela da 150 g|RAW|HIGH|FRUIT
            M|LUNCH|Riso e pollo|780|600|45|70|15|Cuoci e servi
            I|Riso|100|g|100 g|DRY|HIGH|CEREALS
            M|SNACK|Yogurt e frutta|960|400|25|55|8|Servi fresco
            I|Yogurt|170|g|1 vasetto da 170 g|NET|HIGH|DAIRY
            M|DINNER|Pesce e patate|1200|600|40|53|23|Cuoci e servi
            I|Pesce|180|g|180 g|RAW|HIGH|PROTEIN
            H|Bere regolarmente durante la giornata
            V|1|target rispettati
        """.trimIndent()
        val envelope = JSONObject().put("data", payload).toString()

        val response = NutritionPlanCompactContract.parseEnvelope(envelope)

        assertEquals(date, response.weekStartEpochDay)
        assertEquals(1, response.days.size)
        assertEquals(2, response.days.first().meals.first().ingredients.size)
        assertEquals("Yogurt greco", response.days.first().meals.first().ingredients.first().name)
        assertTrue(response.agentValidation.valid)
    }

    @Test
    fun malformedRecord_isRejected() {
        val payload = """
            MFP1
            W|1
            D|1|2400|160|280|70
            M|BREAKFAST|Titolo|480|500|35|62|12|Prep|campo-extra
            V|1|ok
        """.trimIndent()

        assertTrue(runCatching { NutritionPlanCompactContract.parsePayload(payload) }.isFailure)
    }

    @Test
    fun providerDayTotals_areNormalizedFromMealsAndSupplements() {
        val date = LocalDate.of(2026, 9, 14).toEpochDay()
        val response = NutritionPlanCompactContract.parseEnvelope(JSONObject().put("data", """
            MFP1
            W|$date
            D|$date|1|1|1|1
            M|BREAKFAST|Pasto|480|500|35|62|12|Prep
            I|Yogurt|200|g|200 g|NET|HIGH|DAIRY
            V|1|ok
        """.trimIndent()).toString(), mealsPerDay = 1)

        val normalized = NutritionPlanContract.normalizeDerivedDayTotals(response)

        assertEquals(500, normalized.days.single().totalKcal)
        assertEquals(35f, normalized.days.single().proteinG)
        assertEquals(62f, normalized.days.single().carbsG)
        assertEquals(12f, normalized.days.single().fatG)
    }

    @Test
    fun rangeRecord_isAcceptedForCurrentWeekSubset() {
        val monday = LocalDate.of(2026, 9, 14).toEpochDay()
        val payload = """
            MFP1
            W|$monday
            RANGE|${monday + 2}|${monday + 2}|1
            D|${monday + 2}|500|35|62|12
            M|BREAKFAST|Pasto 1|480|100|7|12.4|2.4|Prep
            I|Yogurt|40|g|40 g|NET|HIGH|DAIRY
            M|SNACK|Pasto 2|600|100|7|12.4|2.4|Prep
            I|Yogurt|40|g|40 g|NET|HIGH|DAIRY
            M|LUNCH|Pasto 3|780|100|7|12.4|2.4|Prep
            I|Yogurt|40|g|40 g|NET|HIGH|DAIRY
            M|SNACK|Pasto 4|960|100|7|12.4|2.4|Prep
            I|Yogurt|40|g|40 g|NET|HIGH|DAIRY
            M|DINNER|Pasto 5|1200|100|7|12.4|2.4|Prep
            I|Yogurt|40|g|40 g|NET|HIGH|DAIRY
            V|1|ok
        """.trimIndent()
        val parsed = NutritionPlanCompactContract.parseEnvelope(JSONObject().put("data", payload).toString(), mealsPerDay = 5)

        val validation = NutritionPlanContract.validateBusiness(
            NutritionPlanContract.normalizeDerivedDayTotals(parsed),
            LocalDate.ofEpochDay(monday),
            NutritionBusinessValidator.Targets(500.0, 35.0, 62.0, 12.0),
            mealsPerDay = 5,
            expectedStartEpochDay = monday + 2,
            expectedEndEpochDay = monday + 2,
        )
        assertTrue("range validation failed: ${validation.exceptionOrNull()?.message}", validation.isSuccess)
    }
}
