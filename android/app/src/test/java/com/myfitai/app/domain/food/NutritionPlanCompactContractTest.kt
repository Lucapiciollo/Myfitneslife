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
}
