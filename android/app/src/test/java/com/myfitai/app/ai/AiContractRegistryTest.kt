package com.myfitai.app.ai

import com.myfitai.app.domain.body.BiaAnalysisContract
import com.myfitai.app.domain.body.BiaImportCompactContract
import com.myfitai.app.domain.body.BiaRawImportContract
import com.myfitai.app.domain.body.BodyProportionAnalysisService
import com.myfitai.app.domain.food.CheatAdjustmentCompactContract
import com.myfitai.app.domain.food.CheatAdjustmentContract
import com.myfitai.app.domain.food.CheatUnderstandingCompactContract
import com.myfitai.app.domain.food.CheatUnderstandingContract
import com.myfitai.app.domain.food.MealAlternativeCompactContract
import com.myfitai.app.domain.food.MealAlternativeContract
import com.myfitai.app.domain.food.NutritionAdviceCompactContract
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.domain.food.NutritionPathContract
import com.myfitai.app.domain.food.NutritionPlanCompactContract
import com.myfitai.app.domain.food.PlanReviewCompactContract
import com.myfitai.app.domain.progress.ProgressAnalysisCompactContract
import com.myfitai.app.domain.review.WeeklyReviewCompactContract
import com.myfitai.app.domain.review.WeeklyReviewContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiContractRegistryTest {
    /** Every schemaName actually sent to a provider by a contract, with the header its protocol declares. */
    private val contractProtocols: List<Pair<String, String>> = listOf(
        BiaRawImportContract.SCHEMA_NAME to BiaRawImportContract.PROTOCOL,
        BiaImportCompactContract.SCHEMA_NAME to BiaImportCompactContract.PROTOCOL,
        BiaAnalysisContract.SCHEMA_NAME to "BA2",
        BodyProportionAnalysisService.SCHEMA_NAME to "BP1",
        ProgressAnalysisCompactContract.SCHEMA_NAME to ProgressAnalysisCompactContract.PROTOCOL,
        WeeklyReviewCompactContract.SCHEMA_NAME to WeeklyReviewCompactContract.PROTOCOL,
        MealAlternativeCompactContract.SCHEMA_NAME to MealAlternativeCompactContract.PROTOCOL,
        NutritionAdviceCompactContract.SCHEMA_NAME to NutritionAdviceCompactContract.PROTOCOL,
        CheatUnderstandingCompactContract.SCHEMA_NAME to CheatUnderstandingCompactContract.PROTOCOL,
        CheatAdjustmentCompactContract.SCHEMA_NAME to CheatAdjustmentCompactContract.PROTOCOL,
        PlanReviewCompactContract.SCHEMA_NAME to PlanReviewCompactContract.PROTOCOL,
        NutritionPathContract.SCHEMA_NAME to NutritionPathContract.PROTOCOL,
        NutritionPlanCompactContract.SCHEMA_NAME to NutritionPlanCompactContract.PROTOCOL,
    ).map { (schema, protocol) -> schema to protocol.lineSequence().first().trim() }

    @Test
    fun everySchemaUsedByAContractIsRegisteredWithItsExactHeader() {
        contractProtocols.forEach { (schema, header) ->
            val spec = AiContractRegistry.find(schema)
            assertNotNull("$schema is not registered", spec)
            assertEquals(schema, AiContractRegistry.Format.COMPACT_PIPE, spec!!.format)
            assertEquals(schema, header, spec.protocolHeader)
        }
    }

    @Test
    fun registryHasNoStaleOrDuplicateEntries() {
        assertEquals(contractProtocols.map { it.first }.toSet(), AiContractRegistry.all.map { it.schemaName }.toSet())
        assertEquals(AiContractRegistry.all.size, AiContractRegistry.all.map { it.schemaName }.toSet().size)
    }

    @Test
    fun schemaAliasesDeclaredByContractsResolveToRegisteredNames() {
        listOf(
            CheatAdjustmentContract.SCHEMA_NAME, CheatUnderstandingContract.SCHEMA_NAME, MealAlternativeContract.SCHEMA_NAME,
            NutritionAdviceContract.SCHEMA_NAME, WeeklyReviewContract.SCHEMA_NAME,
        ).forEach { schema -> assertNotNull("$schema missing from the registry", AiContractRegistry.find(schema)) }
    }

    @Test
    fun lookupIsExactNeverBySubstring() {
        assertNull(AiContractRegistry.find("myfitai_bia_pipe_v1_extra"))
        assertNull(AiContractRegistry.find("prefix_myfitai_nutrition_advice_pipe_v1"))
        assertNull(AiContractRegistry.find("weekly_nutrition"))
        assertNull(AiContractRegistry.find("myfitai_unknown_pipe_v1"))
        assertNull(AiContractRegistry.find(""))
    }

    @Test
    fun requireFailsLoudlyForAnUnregisteredSchema() {
        val error = runCatching { AiContractRegistry.require("myfitai_unknown_pipe_v1") }.exceptionOrNull()
        assertTrue("got $error", error is AiExecutionService.Failure.ContractNotRegistered)
        assertEquals("CONTRACT_NOT_REGISTERED", error!!.message)
        assertEquals(AiFailure.ContractNotRegistered, AiFailure.classify(error))
    }

    @Test
    fun everyCompactEntryDeclaresAHeaderAndEveryRetryHintNamesIt() {
        AiContractRegistry.all.forEach { spec ->
            if (spec.format == AiContractRegistry.Format.COMPACT_PIPE) {
                assertTrue(spec.schemaName, !spec.protocolHeader.isNullOrBlank())
                assertTrue(spec.schemaName, spec.retryRule().contains("must begin with ${spec.protocolHeader} "))
            }
        }
    }

    @Test
    fun outputTokenBudgets_keepTheValuesPreviouslyAppliedBySubstring() {
        val expected = mapOf(
            "myfitai_cheat_adjustment_pipe_v1" to 2_500,
            "myfitai_meal_alternatives_pipe_v1" to 2_200,
            "myfitai_nutrition_advice_pipe_v1" to 900,
            "myfitai_weekly_review_pipe_v1" to 700,
            "myfitai_cheat_understanding_pipe_v1" to 500,
            "myfitai_body_proportion_pipe_v1" to 500,
            "myfitai_bia_raw_pipe_v2" to 350,
            "myfitai_bia_pipe_v1" to 350,
            "myfitai_bia_progress_coach_pipe_v2" to 2_200,
            "myfitai_progress_analysis_pipe_v1" to 1_500,
            "myfitai_plan_review_pipe_v1" to 1_500,
            "myfitai_nutrition_path_pipe_v1" to 1_500,
        )
        expected.forEach { (schema, cap) -> assertEquals(schema, cap, AiContractRegistry.require(schema).maxOutputTokens) }
        // The weekly plan has a variable length: no application cap.
        assertNull(AiContractRegistry.require("myfitai_weekly_nutrition_pipe_v1").maxOutputTokens)
        assertEquals(expected.size + 1, AiContractRegistry.all.size)
    }

    @Test
    fun geminiNativeSchemaIsDisabledOnlyForTheTwoWorkloadsItRejects() {
        val withoutNative = AiContractRegistry.all.filterNot { it.geminiNativeSchema }.map { it.schemaName }.toSet()
        assertEquals(setOf("myfitai_weekly_nutrition_pipe_v1", "myfitai_meal_alternatives_pipe_v1"), withoutNative)
    }

    @Test
    fun persistedFlagMarksTheContractsWhoseResultIsStored() {
        assertTrue(AiContractRegistry.require("myfitai_weekly_nutrition_pipe_v1").persisted)
        assertTrue(AiContractRegistry.require("myfitai_bia_progress_coach_pipe_v2").persisted)
        assertFalse(AiContractRegistry.require("myfitai_nutrition_advice_pipe_v1").persisted)
    }

    @Test
    fun tokenPolicyReadsTheRegistry() {
        assertEquals(350, AiOutputTokenPolicy.effectiveLimit("myfitai_bia_raw_pipe_v2", 500))
        assertEquals(100, AiOutputTokenPolicy.effectiveLimit("myfitai_bia_raw_pipe_v2", 100))
        // A caller that did not ask for a budget still gets the contract's prudent cap.
        assertEquals(900, AiOutputTokenPolicy.effectiveLimit("myfitai_nutrition_advice_pipe_v1", null))
        assertNull(AiOutputTokenPolicy.effectiveLimit("myfitai_weekly_nutrition_pipe_v1", null))
    }
}
