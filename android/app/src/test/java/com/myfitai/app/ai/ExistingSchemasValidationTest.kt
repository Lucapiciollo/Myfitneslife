package com.myfitai.app.ai

import com.myfitai.app.domain.body.BiaAnalysisContract
import com.myfitai.app.domain.body.BiaImportCompactContract
import com.myfitai.app.domain.body.BiaImportContract
import com.myfitai.app.domain.body.BiaRawImportContract
import com.myfitai.app.domain.food.CheatAdjustmentContract
import com.myfitai.app.domain.food.CheatUnderstandingContract
import com.myfitai.app.domain.food.MealAlternativeContract
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.domain.food.NutritionPathContract
import com.myfitai.app.domain.food.NutritionPlanCompactContract
import com.myfitai.app.domain.food.NutritionPlanContract
import com.myfitai.app.domain.food.PlanReviewCompactContract
import com.myfitai.app.domain.progress.ProgressAnalysisCompactContract
import com.myfitai.app.domain.review.WeeklyReviewContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Backward compatibility: every schema in use (runtime contracts + 06-ai/schemas) still accepts a valid fixture. */
class ExistingSchemasValidationTest {

    private fun assertValid(name: String, json: String, schema: String) {
        val result = CanonicalJsonSchemaValidator.validate(json, schema)
        assertTrue("$name should be valid: ${result.errors}", result.valid)
    }

    @Test fun compactEnvelopeContractsAcceptDataAndRejectNonString() {
        val schemas = mapOf(
            "AiCompactEnvelope" to AiCompactEnvelope.schemaJson,
            "BiaAnalysis" to BiaAnalysisContract.schemaJson,
            "BiaImportCompact" to BiaImportCompactContract.schemaJson,
            "BiaRawImport" to BiaRawImportContract.schemaJson,
            "CheatAdjustment" to CheatAdjustmentContract.schemaJson,
            "CheatUnderstanding" to CheatUnderstandingContract.schemaJson,
            "MealAlternative" to MealAlternativeContract.schemaJson,
            "NutritionAdvice" to NutritionAdviceContract.schemaJson,
            "NutritionPath" to NutritionPathContract.schemaJson,
            "NutritionPlanCompact" to NutritionPlanCompactContract.schemaJson,
            "PlanReviewCompact" to PlanReviewCompactContract.schemaJson,
            "ProgressAnalysisCompact" to ProgressAnalysisCompactContract.schemaJson,
            "WeeklyReview" to WeeklyReviewContract.schemaJson,
        )
        schemas.forEach { (name, schema) ->
            assertValid(name, """{"data":"X1\nS|a"}""", schema)
            assertFalse("$name must reject numeric data", CanonicalJsonSchemaValidator.validate("""{"data":1}""", schema).valid)
            assertFalse("$name must reject missing data", CanonicalJsonSchemaValidator.validate("{}", schema).valid)
            assertFalse("$name must reject extra keys", CanonicalJsonSchemaValidator.validate("""{"data":"x","z":1}""", schema).valid)
        }
    }

    @Test fun biaImportContractAcceptsFixture() {
        val json = """{"isBiaDocument":true,"rejectionReason":"","measuredAtEpochMillis":1700000000000,"weightKg":80.5,
            "bodyFatPercent":18.2,"confidence":"HIGH","notes":"ok"}"""
        assertValid("BiaImport", json, BiaImportContract.schemaJson)
        assertFalse(CanonicalJsonSchemaValidator.validate(json.replace("HIGH", "VERY"), BiaImportContract.schemaJson).valid)
    }

    private fun ingredient() = JSONObject()
        .put("name", "Riso").put("quantity", 80).put("unit", "g").put("displayDose", "80 g")
        .put("weightState", "RAW").put("nutritionConfidence", "STANDARD").put("category", "GRAIN")

    private fun meal(i: Int) = JSONObject()
        .put("type", "BREAKFAST").put("title", "Pasto $i").put("timeMinutes", 480).put("kcal", 500)
        .put("proteinG", 30).put("carbsG", 50.5).put("fatG", 10).put("preparation", "x")
        .put("ingredients", JSONArray().put(ingredient()))

    @Test fun nutritionPlanContractAcceptsFixture() {
        val day = JSONObject()
            .put("dateEpochDay", 20000).put("totalKcal", 2000).put("proteinG", 120).put("carbsG", 200).put("fatG", 60)
            .put("hydrationNote", "acqua")
            .put("supplements", JSONArray().put(JSONObject()
                .put("kind", "CREATINE").put("name", "Creatina").put("dose", 3).put("unit", "g").put("timeMinutes", 600)
                .put("kcal", 0).put("proteinG", 0).put("carbsG", 0).put("fatG", 0).put("notes", "")))
            .put("meals", JSONArray().apply { (1..4).forEach { put(meal(it)) } })
        val root = JSONObject().put("weekStartEpochDay", 20000).put("days", JSONArray().put(day))
            .put("agentValidation", JSONObject().put("valid", true).put("notes", ""))
        assertValid("NutritionPlan", root.toString(), NutritionPlanContract.schemaJson)

        day.put("meals", JSONArray().put(meal(1)))
        assertFalse("minItems 4 meals", CanonicalJsonSchemaValidator.validate(root.toString(), NutritionPlanContract.schemaJson).valid)
        day.put("meals", JSONArray().apply { (1..4).forEach { put(meal(it)) } })
        day.getJSONArray("supplements").getJSONObject(0).put("kind", "OTHER")
        assertFalse("supplement enum", CanonicalJsonSchemaValidator.validate(root.toString(), NutritionPlanContract.schemaJson).valid)
    }

    private fun schemaFile(name: String): String {
        val candidates = listOf(File("../../06-ai/schemas/$name"), File("../06-ai/schemas/$name"), File("06-ai/schemas/$name"))
        val file = candidates.firstOrNull { it.isFile } ?: error("schema $name not found from ${File(".").absolutePath}")
        return file.readText(Charsets.UTF_8)
    }

    private fun weeklyMeal(mealType: String) = JSONObject()
        .put("mealType", mealType).put("time", "08:30").put("title", "Colazione")
        .put("ingredients", JSONArray().put(JSONObject()
            .put("name", "Avena").put("quantity", 50).put("unit", "g").put("displayDose", "50 g")
            .put("weightState", "RAW").put("nutritionConfidence", "STANDARD")))
        .put("kcal", 400).put("proteinG", 20).put("carbsG", 50).put("fatG", 10)
        .put("digestiveProfile", JSONObject().put("bloatingRisk", "LOW").put("waterRetentionRisk", "LOW")
            .put("digestiveFlags", JSONArray().put("HIGH_FIBER")).put("digestiveNote", "ok"))

    @Test fun weeklyMealPlanSchemaFileAcceptsFixture() {
        val schema = schemaFile("weekly-meal-plan.schema.json")
        val day = JSONObject().put("date", "2026-11-09").put("trainingDay", true)
            .put("meals", JSONArray().apply { listOf("BREAKFAST", "SNACK", "LUNCH", "DINNER").forEach { put(weeklyMeal(it)) } })
            .put("totals", JSONObject().put("kcal", 2000).put("proteinG", 120).put("carbsG", 200).put("fatG", 60))
        val root = JSONObject().put("status", "OK").put("operation", "GENERATE_WEEKLY_PLAN").put("weekStart", "2026-11-09")
            .put("days", JSONArray().put(day)).put("needsInput", JSONArray()).put("evidenceIds", JSONArray().put("e1"))
            .put("agentValidation", JSONObject().put("withinTolerance", true).put("allConstraintsSatisfied", true).put("notes", JSONArray()))
        assertValid("weekly-meal-plan", root.toString(), schema)

        val badTime = JSONObject(root.toString())
        badTime.getJSONArray("days").getJSONObject(0).getJSONArray("meals").getJSONObject(0).put("time", "25:99")
        assertFalse(CanonicalJsonSchemaValidator.validate(badTime.toString(), schema).valid)
        val badOp = JSONObject(root.toString()).put("operation", "OTHER")
        assertFalse(CanonicalJsonSchemaValidator.validate(badOp.toString(), schema).valid)
    }

    @Test fun planAdjustmentSchemaFileAcceptsFixture() {
        val schema = schemaFile("plan-adjustment.schema.json")
        val changed = weeklyMeal("LUNCH").put("date", "2026-11-10")
        val root = JSONObject().put("status", "OK").put("operation", "ADJUST_AFTER_DEVIATION")
            .put("sourcePlanVersionId", "v1").put("newVersionReason", "sgarro")
            .put("deviationEstimate", JSONObject().put("kcal", JSONObject.NULL).put("confidence", "LOW"))
            .put("changedMeals", JSONArray().put(changed))
            .put("agentValidation", JSONObject().put("withinTolerance", true))
        assertValid("plan-adjustment", root.toString(), schema)
        root.getJSONObject("deviationEstimate").put("kcal", -5)
        assertFalse(CanonicalJsonSchemaValidator.validate(root.toString(), schema).valid)
    }

    @Test fun compactEnvelopeSchemaFilesAcceptData() {
        listOf("plan-review.schema.json", "progress-review.schema.json").forEach {
            assertValid(it, """{"data":"X"}""", schemaFile(it))
            assertFalse(CanonicalJsonSchemaValidator.validate("""{"data":"X","k":1}""", schemaFile(it)).valid)
        }
    }
}
