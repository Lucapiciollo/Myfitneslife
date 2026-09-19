package com.myfitai.app.domain.food

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
    fun nestedCompactEnvelope_isUnwrappedBeforePipeValidation() {
        val payload = """
            MFP1
            W|1
            D|1|2400|160|280|70
            M|BREAKFAST|Colazione|480|500|35|62|12|Prep
            I|Yogurt|200|g|1 vasetto|NET|HIGH|DAIRY
            V|1|ok
        """.trimIndent()
        val nested = JSONObject().put("data", payload).toString()
        val envelope = JSONObject().put("data", nested).toString()

        val response = NutritionPlanCompactContract.parseEnvelope(envelope, mealsPerDay = 1)

        assertEquals(1L, response.weekStartEpochDay)
        assertEquals("Colazione", response.days.single().meals.single().title)
    }

    @Test
    fun supplementWithoutOptionalFatAndNotes_defaultsToZeroFat() {
        val payload = """
            MFP1
            W|1
            D|1|2400|160|280|70
            M|BREAKFAST|Colazione|480|500|35|62|12|Prep
            I|Yogurt|200|g|1 vasetto|NET|HIGH|DAIRY
            S|PROTEIN_POWDER|Whey|30|g|600|120|24|2
            V|1|ok
        """.trimIndent()

        val response = NutritionPlanCompactContract.parsePayload(payload, mealsPerDay = 1)

        assertEquals(0f, response.days.single().supplements.single().fatG)
        assertEquals("", response.days.single().supplements.single().notes)
    }

    @Test
    fun supplementWithNotesButWithoutOptionalFat_preservesNotes() {
        val payload = """
            MFP1
            W|1
            D|1|2400|160|280|70
            M|BREAKFAST|Colazione|480|500|35|62|12|Prep
            I|Yogurt|200|g|1 vasetto|NET|HIGH|DAIRY
            S|PROTEIN_POWDER|Whey|30|g|600|120|24|2|Dopo allenamento
            V|1|ok
        """.trimIndent()

        val response = NutritionPlanCompactContract.parsePayload(payload, mealsPerDay = 1)

        assertEquals(0f, response.days.single().supplements.single().fatG)
        assertEquals("Dopo allenamento", response.days.single().supplements.single().notes)
    }

    @Test
    fun perDayValidationRecords_andPipeInHydration_areTolerated() {
        val payload = """
            MFP1
            W|1
            D|1|2400|160|280|70
            M|BREAKFAST|Colazione|480|500|35|62|12|Prep
            I|Yogurt|200|g|1 vasetto|NET|HIGH|DAIRY
            H|Bevi 2L|circa 8 bicchieri
            V|1|ok giorno 1
            D|2|2400|160|280|70
            M|BREAKFAST|Colazione|480|500|35|62|12|Prep
            I|Yogurt|200|g|1 vasetto|NET|HIGH|DAIRY
            H|Idratati bene
            V|1|ok giorno 2
        """.trimIndent()

        val response = NutritionPlanCompactContract.parsePayload(payload, mealsPerDay = 1)

        assertEquals(2, response.days.size)
        assertEquals("Bevi 2L|circa 8 bicchieri", response.days.first().hydrationNote)
        assertTrue(response.agentValidation.valid)
        assertEquals("ok giorno 2", response.agentValidation.notes)
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
}
