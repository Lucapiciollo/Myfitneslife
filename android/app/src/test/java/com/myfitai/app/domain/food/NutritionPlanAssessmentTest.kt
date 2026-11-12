package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionPlanAssessmentTest {
    private val week = 20_000L
    private val targets = NutritionBusinessValidator.Targets(2400.0, 160.0, 280.0, 70.0)

    private fun meals(offset: Int) = listOf("Colazione", "Spuntino mattutino", "Pranzo", "Spuntino pomeridiano", "Cena").mapIndexed { index, type ->
        NutritionPlanContract.GeneratedMeal(
            type, "$type $offset", 420 + index * 60, 480, 32f, 56f, 14f, "Preparazione semplice",
            listOf(NutritionPlanContract.GeneratedIngredient("Riso $offset $index", 100f, "g", "100 g", "crudo", "high", "cereali")),
        )
    }

    private fun day(offset: Int, kcal: Int, protein: Float = 160f, carbs: Float = 280f, fat: Float = 70f) =
        NutritionPlanContract.GeneratedDay(week + offset, kcal, protein, carbs, fat, meals(offset))

    private fun plan(vararg kcal: Int) = NutritionPlanContract.Response(
        weekStartEpochDay = week,
        days = kcal.mapIndexed { index, value -> day(index, value) },
        agentValidation = NutritionPlanContract.AgentValidation(false, "advisory"),
    )

    private fun assess(response: NutritionPlanContract.Response, tolerance: Double = 0.03, attempt: Int = 0) =
        NutritionPlanAssessment.assess(response, targets, emptyMap(), tolerance, attempt = attempt)

    @Test
    fun classifiesEachDayAgainstThreeAndFourPercent() {
        val result = assess(plan(2400, 2490, 2520, 2328, 2304), tolerance = 0.04)

        assertEquals(
            listOf(
                NutritionPlanAssessment.DayClass.OK,
                NutritionPlanAssessment.DayClass.RETRY_ZONE,
                NutritionPlanAssessment.DayClass.OUT,
                NutritionPlanAssessment.DayClass.OK,
                NutritionPlanAssessment.DayClass.RETRY_ZONE,
            ),
            result.days.map { it.classification },
        )
        // 2490 is +3.75% and 2304 is -4.0% (the 4% limit is inclusive); only +5% blocks at 4%.
        assertEquals(listOf(week + 2), result.blockers.map { it.dayEpochDay })
        assertEquals(0.0375, result.days[1].kcalDeviation, 1e-9)
    }

    @Test
    fun toleranceIsThreePercentOnTheFirstAttemptAndFourPercentOnRetries() {
        assertEquals(0.03, NutritionPlanAssessment.toleranceForAttempt(0), 0.0)
        assertEquals(0.04, NutritionPlanAssessment.toleranceForAttempt(1), 0.0)
        assertEquals(0.04, NutritionPlanAssessment.toleranceForAttempt(2), 0.0)
        assertEquals(0.03, NutritionPlanAssessment.REFERENCE_TOLERANCE, 0.0)
    }

    @Test
    fun aDayAtThreePointFivePercentFailsFirstAndPassesOnTheRetry() {
        val response = plan(2400, 2484, 2400, 2400, 2400, 2400, 2400)
        val outcomes = (0..2).map { attempt ->
            val tolerance = NutritionPlanAssessment.toleranceForAttempt(attempt)
            NutritionPlanContract.validateBusinessAssessed(
                response = response,
                expectedWeekStart = java.time.LocalDate.ofEpochDay(week),
                targets = targets,
                tolerance = tolerance,
                attempt = attempt,
            )
        }

        assertTrue(outcomes[0].isFailure)
        assertTrue(outcomes[0].exceptionOrNull()?.message.orEmpty().startsWith("TARGET_TOLERANCE_EXCEEDED:${week + 1}:target=2400;actual=2484"))
        val accepted = outcomes[1].getOrThrow()
        assertEquals(0.04, accepted.appliedTolerance, 0.0)
        assertEquals(1, accepted.attempt)
        assertEquals(NutritionPlanAssessment.DayClass.RETRY_ZONE, accepted.days[1].classification)
        assertTrue(accepted.warnings.any { it.code == "KCAL_IN_RETRY_ZONE" && it.dayEpochDay == week + 1 })
        assertTrue(outcomes[2].isSuccess)
    }

    @Test
    fun aDayBeyondFourPercentIsStillRejectedOnTheRetry() {
        val result = NutritionPlanContract.validateBusinessAssessed(
            response = plan(2400, 2500, 2400, 2400, 2400, 2400, 2400),
            expectedWeekStart = java.time.LocalDate.ofEpochDay(week),
            targets = targets,
            tolerance = NutritionPlanAssessment.toleranceForAttempt(1),
            attempt = 1,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun centeringIsAnInformativeWarningAndNeverBlocks() {
        // Every day at +2.5%: average 2.5% > 2% so a warning, but all within 3% so nothing blocks.
        val loose = assess(plan(2460, 2460, 2460, 2460, 2460, 2460, 2460))
        assertTrue(loose.blockers.isEmpty())
        assertTrue(loose.accepted)
        val warning = loose.warnings.single { it.code == "CALORIE_CENTERING_LOOSE" }
        assertEquals(2.5, warning.actual!!, 1e-6)

        val centered = assess(plan(2424, 2400, 2376, 2400, 2460, 2400, 2400))
        assertTrue(centered.warnings.none { it.code == "CALORIE_CENTERING_LOOSE" })
    }

    @Test
    fun macrosNeverBlockAndAreRecordedAsWarnings() {
        val response = NutritionPlanContract.Response(
            week,
            listOf(
                day(0, 2400, protein = 100f, carbs = 400f, fat = 30f),
                day(1, 2400, protein = 140f, carbs = 336f, fat = 87f),
                day(2, 2400, protein = 220f, carbs = 220f, fat = 52f),
            ),
            NutritionPlanContract.AgentValidation(true, "ok"),
        )
        val result = assess(response)

        assertTrue(result.blockers.isEmpty())
        val day0 = result.warnings.filter { it.dayEpochDay == week }.map { it.code }.toSet()
        assertEquals(setOf("PROTEIN_BELOW_TARGET", "CARBS_OUT_OF_BAND", "FAT_OUT_OF_BAND"), day0)
        // 87.5% protein, +20% carbs and +24% fat are inside the bands.
        assertTrue(result.warnings.none { it.dayEpochDay == week + 1 })
        // Protein above target is not a warning; -21% carbs and -26% fat: only fat leaves the band.
        assertEquals(listOf("FAT_OUT_OF_BAND"), result.warnings.filter { it.dayEpochDay == week + 2 }.map { it.code })
        assertEquals(-0.375, result.days[0].proteinDeviation, 1e-9)
        assertEquals(0.4286, result.days[0].carbsDeviation, 1e-3)
    }

    @Test
    fun dailyTargetsOverrideTheBaseTarget() {
        val special = mapOf(week + 1 to NutritionBusinessValidator.Targets(2600.0, 160.0, 280.0, 70.0))
        val result = NutritionPlanAssessment.assess(plan(2400, 2600), targets, special, 0.03)
        assertTrue(result.blockers.isEmpty())
        assertEquals(2600.0, result.days[1].targetKcal, 0.0)
    }

    @Test
    fun belowOnlyRejectsAnyDayAboveTarget() {
        val result = NutritionPlanAssessment.assess(plan(2400, 2410), targets, emptyMap(), 0.03, belowOnly = true)
        assertEquals(listOf(week + 1), result.blockers.map { it.dayEpochDay })
    }

    @Test
    fun retryReasonListsEveryDayOutsideToleranceInACompactCappedFormat() {
        val reasons = assess(plan(2000, 2400, 2000, 2800, 2400, 2000, 2000)).toleranceExceededReason()!!
        assertEquals(
            "TARGET_TOLERANCE_EXCEEDED:${week}:target=2400;actual=2000" +
                "|${week + 2}:target=2400;actual=2000" +
                "|${week + 3}:target=2400;actual=2800" +
                "|${week + 5}:target=2400;actual=2000" +
                "|${week + 6}:target=2400;actual=2000",
            reasons,
        )

        val many = assess(NutritionPlanContract.Response(week, (0..9).map { day(it, 1000) }, NutritionPlanContract.AgentValidation(true, "")))
        val capped = many.toleranceExceededReason()!!
        assertEquals(NutritionPlanAssessment.MAX_FIX_DAYS, capped.removePrefix("TARGET_TOLERANCE_EXCEEDED:").substringBefore("|+").split('|').size)
        assertTrue(capped.endsWith("|+4"))
        assertTrue(capped.length <= NutritionPlanAssessment.MAX_REASON_CHARS)
        assertFalse(capped.contains("meal", ignoreCase = true))
        assertNull(assess(plan(2400, 2400)).toleranceExceededReason())
    }

    @Test
    fun appValidationV2RecordsDeviationsAttemptsAndTolerancePolicyWithoutModelText() {
        val response = plan(2400, 2484, 2400)
        val assessment = assess(response, tolerance = 0.04, attempt = 1)
        val validation = NutritionIntegrityValidator.validate(
            response = response,
            targets = targets,
            mealsPerDay = 5,
            assessment = assessment,
        )
        val json = JSONObject(validation.toJson().toString())

        assertEquals("nutrition-integrity-v2", json.getString("validatorVersion"))
        assertEquals(2, json.getInt("attempts"))
        val policy = json.getJSONObject("tolerancePolicy")
        assertEquals(3.0, policy.getDouble("referencePercent"), 0.0)
        assertEquals(4.0, policy.getDouble("retryPercent"), 0.0)
        assertEquals(4.0, policy.getDouble("appliedPercent"), 0.0)
        val deviations = json.getJSONArray("deviations")
        assertEquals(3, deviations.length())
        assertEquals("RETRY_ZONE", deviations.getJSONObject(1).getString("class"))
        assertEquals(3.5, deviations.getJSONObject(1).getJSONObject("kcal").getDouble("devPercent"), 1e-9)
        assertTrue(json.getJSONArray("warnings").toString().contains("KCAL_IN_RETRY_ZONE"))

        val restored = NutritionIntegrityValidator.parse(validation.toJson().toString())!!
        assertEquals(3, restored.deviations.size)
        assertEquals(2, restored.attempts)
        assertEquals(NutritionPlanAssessment.DayClass.RETRY_ZONE, restored.deviations[1].classification)
        assertEquals(4.0, restored.tolerancePolicy!!.appliedPercent, 0.0)
    }

    @Test
    fun legacyV1AppValidationStillReads() {
        val v1 = """{"available":true,"valid":true,"validatorVersion":"nutrition-integrity-v1","derivedTotals":true,"issues":[],"warnings":[{"code":"VARIETY_REPEATS_RECENT","severity":"WARNING","message":"x","dayEpochDay":null,"mealTitle":null,"expected":null,"actual":null,"deviationPercent":null}]}"""
        val parsed = NutritionIntegrityValidator.parse(v1)
        assertNotNull(parsed)
        assertEquals("nutrition-integrity-v1", parsed!!.validatorVersion)
        assertEquals(1, parsed.warnings.size)
        assertTrue(parsed.deviations.isEmpty())
        assertNull(parsed.attempts)
        assertNull(parsed.tolerancePolicy)
        assertNull(NutritionIntegrityValidator.parse(null))
        assertNull(NutritionIntegrityValidator.parse("not json"))
    }

    @Test
    fun exportStyleStringSerializationKeepsTheDocumentIntact() {
        val response = plan(2400, 2484, 2400)
        val text = NutritionIntegrityValidator.validate(response, targets, 5, assessment = assess(response, 0.04, 1)).toJson().toString()
        // ProfileExportService/ProfileBackupService carry appValidationJson as a plain JSON string value.
        val wrapped = JSONObject().put("appValidationJson", text)
        val reread = JSONObject(wrapped.toString()).getString("appValidationJson")
        assertEquals(text, reread)
        assertNotNull(NutritionIntegrityValidator.parse(reread))
    }
}
