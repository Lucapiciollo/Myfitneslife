package com.myfitai.app.domain.food

import com.myfitai.app.R
import com.myfitai.app.ai.AiContractRegistry
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiFailure
import com.myfitai.app.ai.AiOutputTokenPolicy
import com.myfitai.app.ui.food.NutritionAdviceViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6: the nutrition advice must never be refused without a reason the app can name.
 * Several valid ways a model can write the NA1 protocol, plausible food names against the hard-constraint check,
 * stable failure codes for malformed records, diagnostics without model text, and the user-facing presentation.
 */
class NutritionAdviceRobustnessTest {

    private fun envelope(lines: List<String>, separator: String = "\n") =
        JSONObject().put("data", lines.joinToString(separator)).toString()

    private fun option(title: String = "Yogurt magro", reason: String = "Leggero e proteico", kcal: String = "180", foods: String = "yogurt magro") =
        "O|$title|$reason|$kcal|18|12|3|$foods"

    private fun five(foods: String = "yogurt magro", kcal: String = "180") = (1..5).map { option(title = "Opzione $it", kcal = kcal, foods = foods) }

    private fun valid(options: List<String> = five(), answer: String = "Scegli uno spuntino leggero e proteico.", q: String = "Q|Porzioni standard.", v: String = "V|1|Controlli interni superati.") =
        listOf("NA1", "S|1", "A|$answer") + options + listOf(q, v)

    private val noConstraints = DietaryProfile()
    private val onlyNotes = DietaryProfile(notes = "Preferisco cene leggere e veloci")
    private val unrestrictedStyle = DietaryProfile(dietStyle = "Onnivoro")
    private val noneStyle = DietaryProfile(dietStyle = "nessuno")
    private val lactose = DietaryProfile(intolerances = listOf("lattosio"))

    private fun assertAccepted(label: String, data: String, profile: DietaryProfile) {
        val outcome = runCatching {
            val response = NutritionAdviceCompactContract.parse(data)
            NutritionAdviceContract.validateBusiness(response, profile).getOrThrow()
        }
        assertTrue("$label: rejected with ${outcome.exceptionOrNull()?.message}", outcome.isSuccess)
    }

    // ---------- valid model variants ----------

    @Test
    fun everyValidWayOfWritingTheProtocolIsAccepted() {
        val longAnswer = "A".repeat(180)
        val variants = mapOf(
            "baseline" to envelope(valid()),
            "crlf line endings" to envelope(valid(), "\r\n"),
            "blank lines and padding" to envelope(valid().flatMap { listOf("  $it  ", "") }),
            "kcal written as 450.0" to envelope(valid(five(kcal = "450.0"))),
            "answer at the 180 character limit" to envelope(valid(answer = longAnswer)),
            "title at the 70 character limit" to envelope(valid(five().toMutableList().also { it[0] = option(title = "T".repeat(70)) })),
            "reason at the 120 character limit" to envelope(valid(five().toMutableList().also { it[0] = option(reason = "R".repeat(120)) })),
            "empty assumptions" to envelope(valid(q = "Q|")),
            "assumptions at the 120 character limit" to envelope(valid(q = "Q|" + "a".repeat(120))),
            "agent validation false" to envelope(valid(v = "V|0|Dubbi sulle porzioni.")),
            "agent validation without notes" to envelope(valid(v = "V|1|")),
            "assumptions before the options" to envelope(listOf("NA1", "S|1", "A|Scegli leggero.", "Q|Nessuna.") + five() + "V|1|ok"),
            "foods with spaces and accents" to envelope(valid(five(foods = " ricotta , cacao amaro,  più frutta "))),
            "answer with colon and question mark" to envelope(valid(answer = "Dopo cena: meglio qualcosa di leggero, ti va un budino proteico?")),
        )
        listOf(noConstraints to "no constraints", onlyNotes to "notes only", unrestrictedStyle to "unrestricted style", noneStyle to "style none")
            .forEach { (profile, profileLabel) ->
                variants.forEach { (label, data) -> assertAccepted("$profileLabel / $label", data, profile) }
            }
    }

    @Test
    fun outOfScopeAnswerIsAcceptedWithoutOptions() {
        val data = envelope(listOf("NA1", "S|0", "A|${NutritionAdviceService.OUT_OF_SCOPE_MESSAGE}", "Q|", "V|1|ok"))
        assertAccepted("out of scope", data, noConstraints)
        assertFalse(NutritionAdviceCompactContract.parse(data).inScope)
    }

    @Test
    fun emptyFoodsAreNotDemandedWhenTheDietStyleIsUnrestricted() {
        // "Onnivoro" / "nessuno" restrict nothing: no foods list is needed and none may be required.
        val noFoods = envelope(valid(five(foods = "")))
        assertAccepted("onnivoro", noFoods, unrestrictedStyle)
        assertAccepted("nessuno", noFoods, noneStyle)
        assertAccepted("no constraints", noFoods, noConstraints)
        // A declared constraint still needs the foods, otherwise it cannot be verified.
        val rejected = NutritionAdviceContract.validateBusiness(NutritionAdviceCompactContract.parse(noFoods), lactose)
        assertEquals("FOODS_REQUIRED_FOR_CONSTRAINT_VALIDATION", rejected.exceptionOrNull()?.message)
        val restrictiveStyle = NutritionAdviceContract.validateBusiness(NutritionAdviceCompactContract.parse(noFoods), DietaryProfile(dietStyle = "vegetariano"))
        assertEquals("FOODS_REQUIRED_FOR_CONSTRAINT_VALIDATION", restrictiveStyle.exceptionOrNull()?.message)
    }

    // ---------- plausible food names against the hard-constraint check ----------

    private val ordinaryNames = listOf(
        "Yogurt magro", "Ricotta e cannella", "Budino proteico fondente", "Porridge freddo al cacao",
        "Crema di tofu dolce", "Yogurt greco e cacao", "Fiocchi di latte magri con cacao",
    )

    @Test
    fun ordinaryFoodNamesNeverViolateAProfileWithoutConstraints() {
        listOf(noConstraints, onlyNotes, unrestrictedStyle, noneStyle).forEach { profile ->
            ordinaryNames.forEach { name ->
                assertTrue("$name / $profile", FoodConstraintValidator.validateIngredientNames(listOf(name), profile).isEmpty())
            }
        }
        val response = NutritionAdviceCompactContract.parse(envelope(valid(ordinaryNames.map { option(title = it, foods = it) }.take(5))))
        assertTrue(NutritionAdviceContract.validateBusiness(response, noConstraints).isSuccess)
    }

    @Test
    fun lactoseProductsViolateAndLactoseFreeOrDairyFreeNamesDoNot() {
        val violating = listOf("Yogurt magro", "Ricotta e cannella", "Budino proteico fondente", "Yogurt greco e cacao", "Fiocchi di latte magri con cacao")
        violating.forEach { name ->
            assertFalse("$name must violate lactose", FoodConstraintValidator.validateIngredientNames(listOf(name), lactose).isEmpty())
        }
        val allowed = listOf(
            "Porridge freddo al cacao", "Crema di tofu dolce", "Yogurt magro senza lattosio", "Yogurt greco senza lattosio",
            "Ricotta delattosata", "Yogurt di soia",
        )
        allowed.forEach { name ->
            assertTrue("$name must not violate lactose", FoodConstraintValidator.validateIngredientNames(listOf(name), lactose).isEmpty())
        }
        val response = NutritionAdviceCompactContract.parse(envelope(valid(five(foods = "yogurt magro"))))
        assertEquals("FOOD_CONSTRAINT_VIOLATION", NutritionAdviceContract.validateBusiness(response, lactose).exceptionOrNull()?.message)
    }

    // ---------- malformed records: always a stable code, never "Failed requirement." ----------

    @Test
    fun malformedRecordsFailWithAStableBusinessCode() {
        val base = valid()
        val cases = mapOf(
            "scope with extra field" to base.map { if (it == "S|1") "S|1|x" else it },
            "duplicate answer" to base + "A|Seconda risposta.",
            "option with nine fields" to listOf("NA1", "S|1", "A|Ok.", option() + "|extra", "Q|", "V|1|ok"),
            "option after validation" to base + option(),
            "duplicate assumptions" to base.dropLast(1) + "Q|Altra." + "V|1|ok",
            "bad validation flag" to base.dropLast(1) + "V|2|ok",
            "validation without notes field" to base.dropLast(1) + "V|1",
            "unknown record" to base + "X|boh",
        )
        cases.forEach { (label, lines) ->
            val error = runCatching { NutritionAdviceCompactContract.parse(envelope(lines)) }.exceptionOrNull()
            assertNotNull("$label: must be rejected", error)
            val failure = AiFailure.classify(error!!)
            assertTrue("$label: classified as $failure (${error.message})", failure is AiFailure.BusinessRuleViolated && failure.ruleCode != null)
        }
    }

    @Test
    fun theRetryNamesTheRecordToFixForEveryRuleOfTheAdviceContract() {
        val spec = AiContractRegistry.require(NutritionAdviceContract.SCHEMA_NAME)
        listOf(
            "NA_ASSUMPTIONS_MISSING", "NA_VALIDATION_MISSING", "NA_RECORD_INVALID", "NA_KCAL", "NA_P", "NA_C", "NA_F",
            "EXPECTED_FIVE_SUGGESTIONS", "FOODS_REQUIRED_FOR_CONSTRAINT_VALIDATION", "INVALID_COMPACT_ANSWER", "FOOD_CONSTRAINT_VIOLATION",
        ).forEach { code -> assertNotNull(code, spec.fixHint("$code:detail")) }
        assertTrue(spec.fixHint("NA_ASSUMPTIONS_MISSING")!!.contains("Q|"))
        assertNull(spec.fixHint("SOMETHING_ELSE"))
    }

    // ---------- diagnostics and token budget ----------

    @Test
    fun diagnosticOutcomeCarriesOnlyCategoryAndRuleCode() {
        assertEquals(
            "BUSINESS_VALIDATION_FAILED:EXPECTED_FIVE_SUGGESTIONS",
            NutritionAdviceService.diagnosticOutcome(AiExecutionService.Failure.BusinessRejected("EXPECTED_FIVE_SUGGESTIONS")),
        )
        assertEquals(
            "BUSINESS_VALIDATION_FAILED:NA_ASSUMPTIONS_MISSING",
            NutritionAdviceService.diagnosticOutcome(IllegalArgumentException("NA_ASSUMPTIONS_MISSING")),
        )
        assertEquals("OUTPUT_TRUNCATED", NutritionAdviceService.diagnosticOutcome(AiExecutionService.Failure.OutputTruncated()))
        assertEquals("EMPTY_OUTPUT", NutritionAdviceService.diagnosticOutcome(AiExecutionService.Failure.EmptyOutput()))
        val unexpected = NutritionAdviceService.diagnosticOutcome(IllegalStateException("text copied from the model: secret-123"))
        assertEquals("UNEXPECTED:IllegalStateException", unexpected)
        assertFalse(unexpected.contains("secret"))
        // A business rule outside the closed set never leaks its text.
        assertEquals("UNEXPECTED:IllegalArgumentException", NutritionAdviceService.diagnosticOutcome(IllegalArgumentException("free model text")))
    }

    @Test
    fun theRequestedTokenBudgetIsTheOneTheRegistryAllows() {
        assertEquals(
            NutritionAdviceService.MAX_OUTPUT_TOKENS,
            AiOutputTokenPolicy.effectiveLimit(NutritionAdviceContract.SCHEMA_NAME, NutritionAdviceService.MAX_OUTPUT_TOKENS),
        )
    }

    // ---------- what the user sees ----------

    @Test
    fun businessRejectionOffersAManualRetryWhileOtherPermanentFailuresDoNot() {
        val rejected = NutritionAdviceViewModel.presentationFor("EXPECTED_FIVE_SUGGESTIONS")
        assertEquals(R.string.ai_failure_rejected, rejected.messageRes)
        assertTrue(rejected.canRetry)
        assertEquals("EXPECTED_FIVE_SUGGESTIONS", rejected.technicalCode)

        val truncated = NutritionAdviceViewModel.presentationFor("OUTPUT_TRUNCATED")
        assertEquals(R.string.ai_failure_truncated, truncated.messageRes)
        assertFalse(truncated.canRetry)

        val quota = NutritionAdviceViewModel.presentationFor(AiFailure.Quota.userMessage)
        assertEquals(R.string.ai_failure_quota, quota.messageRes)

        val unknown = NutritionAdviceViewModel.presentationFor("Consiglio non disponibile")
        assertEquals(R.string.ai_failure_generic, unknown.messageRes)
        assertFalse(unknown.canRetry)
        assertNull(unknown.technicalCode)
    }
}
