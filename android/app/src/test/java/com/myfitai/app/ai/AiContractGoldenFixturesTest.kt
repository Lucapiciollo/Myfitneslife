package com.myfitai.app.ai

import com.myfitai.app.domain.body.BiaAnalysisContract
import com.myfitai.app.domain.body.BiaImportCompactContract
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.MealAlternativeContract
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.domain.food.NutritionPlanCompactContract
import com.myfitai.app.domain.review.WeeklyReviewCompactContract
import com.myfitai.app.domain.review.WeeklyReviewContract
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden responses shared by every provider (`src/test/resources/ai_golden_fixtures.json`): VALID and invalid
 * answers (enum out of list, wrong type, missing field/record, truncated, empty, refusal) for the registered
 * contracts. Each one goes through the real path `AiExecutionService.executeValidated` (finishReason check,
 * schema v2, parse + business rules) with no retries and must end as the expected [AiFailure] category.
 */
class AiContractGoldenFixturesTest {
    private class Contract(val schemaName: String, val schemaJson: String, val business: (String) -> Result<Any>)

    private class ScriptedProvider(private val raw: String, private val finishReason: String?) : AiProvider {
        override val type = AiProviderType.GEMINI
        override suspend fun generateStructured(request: AiStructuredRequest) =
            AiRawResponse(type, "golden", raw, finishReason = finishReason)
    }

    private fun <R : Any> parsing(parse: (String) -> R, check: (R) -> Result<Unit> = { Result.success(Unit) }): (String) -> Result<Any> =
        { json -> runCatching { parse(json) }.mapCatching { value -> check(value).getOrThrow(); value } }

    private val sourceMeal = FoodMeal(
        id = 1, dayId = 1, sortOrder = 0, type = "LUNCH", title = "Pranzo", timeMinutes = 780,
        kcal = 500, proteinG = 30f, carbsG = 60f, fatG = 10f, preparation = null, ingredients = emptyList(),
    )

    private val contracts = listOf(
        Contract(NutritionAdviceContract.SCHEMA_NAME, NutritionAdviceContract.schemaJson,
            parsing(NutritionAdviceContract::parse) { NutritionAdviceContract.validateBusiness(it) }),
        Contract(BiaAnalysisContract.SCHEMA_NAME, BiaAnalysisContract.schemaJson,
            parsing(BiaAnalysisContract::parse, BiaAnalysisContract::validateBusiness)),
        Contract(BiaImportCompactContract.SCHEMA_NAME, BiaImportCompactContract.schemaJson,
            parsing(BiaImportCompactContract::parseEnvelope)),
        Contract(WeeklyReviewContract.SCHEMA_NAME, WeeklyReviewContract.schemaJson,
            parsing(WeeklyReviewCompactContract::parse) { WeeklyReviewContract.validateBusiness(it, 20000L) }),
        Contract("myfitai_weekly_nutrition_pipe_v1", NutritionPlanCompactContract.schemaJson,
            parsing({ NutritionPlanCompactContract.parseEnvelope(it, mealsPerDay = 1) })),
        Contract(MealAlternativeContract.SCHEMA_NAME, MealAlternativeContract.schemaJson,
            parsing(MealAlternativeContract::parse) { MealAlternativeContract.validateBusiness(it, sourceMeal) }),
    ).associateBy { it.schemaName }

    private class Case(val id: String, val contract: Contract, val raw: String, val finishReason: String?, val expect: String, val rule: String?)

    private fun loadFixtures(): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("ai_golden_fixtures.json")
        assertNotNull("ai_golden_fixtures.json must be on the test classpath", stream)
        return JSONObject(stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun cases(): List<Case> {
        val fixtures = loadFixtures()
        val common = fixtures.getJSONArray("common").objects()
        val result = mutableListOf<Case>()
        fixtures.getJSONArray("contracts").objects().forEach { entry ->
            val contract = contracts.getValue(entry.getString("schemaName"))
            result += common.map { c ->
                Case("${contract.schemaName}/${c.getString("id")}", contract, c.getString("raw"),
                    c.optString("finishReason").ifEmpty { null }, c.getString("expect"), null)
            }
            result += entry.getJSONArray("cases").objects().map { c ->
                Case("${contract.schemaName}/${c.getString("id")}", contract,
                    JSONObject().put("data", c.getString("data")).toString(), null,
                    c.getString("expect"), c.optString("rule").ifEmpty { null })
            }
        }
        return result
    }

    private fun play(case: Case): Result<AiExecutionService.ValidatedExecution<Any>> = runCatching {
        runBlocking {
            AiExecutionService(diagnostics = {}).executeValidated(
                ScriptedProvider(case.raw, case.finishReason),
                AiStructuredRequest("system", "user", case.contract.schemaName, case.contract.schemaJson),
                maxSchemaRetries = 0,
                business = case.contract.business,
            )
        }
    }

    @Test
    fun everyGoldenFixtureEndsWithTheExpectedCategory() {
        val all = cases()
        assertTrue("fixtures must not be empty", all.size > 60)
        all.forEach { case ->
            val outcome = play(case)
            if (case.expect == "VALID") {
                assertTrue("${case.id}: expected VALID but got ${outcome.exceptionOrNull()?.let { AiFailure.classify(it).code }}", outcome.isSuccess)
                assertEquals(case.id, case.contract.schemaName, outcome.getOrThrow().output.schemaName)
            } else {
                val error = outcome.exceptionOrNull()
                assertNotNull("${case.id}: expected ${case.expect} but the response was accepted", error)
                val failure = AiFailure.classify(error!!)
                assertEquals(case.id, case.expect, failure.code)
                if (case.rule != null) {
                    assertEquals(case.id, case.rule, (failure as AiFailure.BusinessRuleViolated).ruleCode)
                }
            }
        }
    }

    @Test
    fun everyCoveredContractIsRegisteredAndHasAllFailureKinds() {
        val fixtures = loadFixtures()
        val commonExpects = fixtures.getJSONArray("common").objects().map { it.getString("expect") }.toSet()
        listOf("INVALID_SCHEMA", "MALFORMED_JSON", "OUTPUT_TRUNCATED", "EMPTY_OUTPUT", "REFUSED").forEach {
            assertTrue("common fixtures must cover $it", it in commonExpects)
        }
        val covered = fixtures.getJSONArray("contracts").objects()
        val names = covered.map { it.getString("schemaName") }
        listOf(
            "myfitai_nutrition_advice_pipe_v1", "myfitai_bia_progress_coach_pipe_v2", "myfitai_bia_pipe_v1",
            "myfitai_weekly_review_pipe_v1", "myfitai_weekly_nutrition_pipe_v1", "myfitai_meal_alternatives_pipe_v1",
        ).forEach { assertTrue("$it must have golden fixtures", it in names) }
        covered.forEach { entry ->
            val schemaName = entry.getString("schemaName")
            assertNotNull("$schemaName must be in AiContractRegistry", AiContractRegistry.find(schemaName))
            val expects = entry.getJSONArray("cases").objects().map { it.getString("expect") }
            assertTrue("$schemaName needs a VALID fixture", "VALID" in expects)
            assertTrue("$schemaName needs a business-rule fixture", "BUSINESS_VALIDATION_FAILED" in expects)
        }
    }

    @Test
    fun schemaFailuresInFixturesCarryOnlyPathsAndCategories() {
        cases().filter { it.expect == "INVALID_SCHEMA" }.forEach { case ->
            val error = play(case).exceptionOrNull() as AiExecutionService.Failure.InvalidSchema
            assertTrue("${case.id}: issues expected", error.issues.isNotEmpty())
            error.issues.forEach { issue -> assertTrue(issue.path.startsWith("$")) }
            assertTrue(case.id, error.errors.none { it.contains("123") })
        }
    }
}
