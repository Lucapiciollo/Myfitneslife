package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiContractRegistry
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiFailure
import com.myfitai.app.ai.AiProvider
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRawResponse
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.CanonicalJsonSchemaValidator
import com.myfitai.app.ai.GeminiByokProvider
import com.myfitai.app.ai.GeminiSchemaMapper
import com.myfitai.app.ai.OpenAiProvider
import com.myfitai.app.ai.ValidatedOutput
import com.myfitai.app.domain.food.NutritionAdviceFixtures.expectedResponse
import com.myfitai.app.domain.food.NutritionAdviceFixtures.jsonFull
import com.myfitai.app.domain.food.NutritionAdviceFixtures.pipeEnvelope
import com.myfitai.app.domain.food.NutritionAdviceFixtures.pipeText
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D1 proof of feasibility: the typed JSON contract of NutritionAdvice, locally, with no network. */
class NutritionAdviceJsonContractTest {
    private val samples = NutritionAdviceFixtures.samples.associateBy { it.id }
    private val golden = samples.getValue("golden_short")
    private val validation = NutritionPlanContract.AgentValidation(true, NutritionAdviceJsonContract.AGENT_VALIDATION_NOT_REQUESTED)

    private fun validate(json: String, profile: DietaryProfile = DietaryProfile()) =
        ValidatedOutput.validate(json, NutritionAdviceJsonContract.schemaJson, NutritionAdviceJsonContract.SCHEMA_NAME, NutritionAdviceJsonContract.business(profile))

    /** `VALID`, `SCHEMA:<category>@<path>` (first issue) or `BUSINESS:<rule code>`. Never any model text. */
    private fun outcome(json: String, profile: DietaryProfile = DietaryProfile()): String {
        val result = validate(json, profile)
        if (result.isSuccess) return "VALID"
        return when (val rejection = result.exceptionOrNull()) {
            is ValidatedOutput.Rejection.Schema -> rejection.issues.first().let { "SCHEMA:${it.category}@${it.path}" }
            is ValidatedOutput.Rejection.Business -> "BUSINESS:${rejection.reason}"
            else -> "OTHER"
        }
    }

    private fun mutated(block: (JSONObject) -> Unit): String = JSONObject(golden.jsonFull()).also(block).toString()
    private fun firstOption(root: JSONObject): JSONObject = root.getJSONArray("options").getJSONObject(0)

    // ---------- 1. the schema and the parser ----------

    @Test
    fun goldenPipeFixtureIsPinnedSoTheComparisonUsesRealContent() {
        val fixtures = JSONObject(javaClass.classLoader!!.getResourceAsStream("ai_golden_fixtures.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val contracts = fixtures.getJSONArray("contracts")
        val advice = (0 until contracts.length()).map { contracts.getJSONObject(it) }.first { it.getString("schemaName") == NutritionAdviceContract.SCHEMA_NAME }
        val valid = (0 until advice.getJSONArray("cases").length()).map { advice.getJSONArray("cases").getJSONObject(it) }.first { it.getString("id") == "na1.valid" }
        assertEquals(valid.getString("data"), golden.pipeText())
    }

    @Test
    fun everyFixtureParsesToTheSameDomainModelFromPipeAndFromJson() {
        NutritionAdviceFixtures.samples.forEach { sample ->
            val fromPipe = NutritionAdviceCompactContract.parse(sample.pipeEnvelope())
            val fromJson = NutritionAdviceJsonContract.parse(sample.jsonFull())
            assertEquals(sample.id, sample.expectedResponse(fromPipe.agentValidation), fromPipe)
            assertEquals(sample.id, sample.expectedResponse(validation), fromJson)
            // Same model, so the service/UI can use either parser: only agentValidation (unused for this agent) differs.
            assertEquals(sample.id, fromPipe.copy(agentValidation = validation), fromJson)
        }
    }

    @Test
    fun everyFixtureIsAcceptedBySchemaV2AndTheReusedBusinessRules() {
        NutritionAdviceFixtures.samples.forEach { sample ->
            assertEquals(sample.id, "VALID", outcome(sample.jsonFull()))
            assertEquals(sample.id, sample.inScope, validate(sample.jsonFull()).getOrThrow().value.inScope)
        }
    }

    @Test
    fun theDefaultModeStaysPipeAndTheJsonContractIsNotRegisteredYet() {
        assertEquals(NutritionAdviceContractMode.PIPE, NutritionAdviceContractMode.DEFAULT)
        assertNull("the typed contract must not be in the registry until the PoC is approved", AiContractRegistry.find(NutritionAdviceJsonContract.SCHEMA_NAME))
        assertEquals(AiContractRegistry.Format.NATIVE_JSON, NutritionAdviceJsonContract.SPEC.format)
        assertTrue(NutritionAdviceJsonContract.SPEC.geminiNativeSchema)
        assertFalse(NutritionAdviceJsonContract.SPEC.persisted)
    }

    // ---------- 2. robustness ----------

    @Test
    fun enumNotAllowed_isASchemaErrorWithPathAndCategoryOnly() {
        val json = mutated { firstOption(it).getJSONArray("ingredients").getJSONObject(1).put("unit", "q.b.") }
        assertEquals("SCHEMA:ENUM_MISMATCH@\$.options[0].ingredients[1].unit", outcome(json))
        val issue = (validate(json).exceptionOrNull() as ValidatedOutput.Rejection.Schema).issues.single()
        assertFalse("model value must never appear in the error", issue.message.contains("q.b."))
    }

    @Test
    fun wrongType_isASchemaError() {
        assertEquals("SCHEMA:TYPE_MISMATCH@\$.options[0].kcal", outcome(mutated { firstOption(it).put("kcal", "450") }))
        assertEquals("SCHEMA:TYPE_MISMATCH@\$.inScope", outcome(mutated { it.put("inScope", "true") }))
        assertEquals("SCHEMA:TYPE_MISMATCH@\$.options[0].proteinG", outcome(mutated { firstOption(it).put("proteinG", "35") }))
    }

    @Test
    fun missingField_isASchemaError() {
        assertEquals("SCHEMA:MISSING_REQUIRED@\$.options[0].fatG", outcome(mutated { firstOption(it).remove("fatG") }))
        assertEquals("SCHEMA:MISSING_REQUIRED@\$.assumptions", outcome(mutated { it.remove("assumptions") }))
        assertEquals("SCHEMA:MISSING_REQUIRED@\$.options[0].ingredients", outcome(mutated { firstOption(it).remove("ingredients") }))
    }

    @Test
    fun unknownField_nullRequiredAndOutOfRange_areSchemaErrors() {
        assertEquals("SCHEMA:NOT_ALLOWED@\$.status", outcome(mutated { it.put("status", "ok") }))
        // An explicit null on a required property counts as missing (validator v2).
        assertEquals("SCHEMA:MISSING_REQUIRED@\$.answer", outcome(mutated { it.put("answer", JSONObject.NULL) }))
        assertEquals("SCHEMA:OUT_OF_RANGE@\$.options[0].kcal", outcome(mutated { firstOption(it).put("kcal", 0) }))
        assertEquals("SCHEMA:OUT_OF_RANGE@\$.options[0].carbsG", outcome(mutated { firstOption(it).put("carbsG", -1) }))
        assertEquals("SCHEMA:TOO_FEW_ITEMS@\$.options[0].ingredients", outcome(mutated { firstOption(it).put("ingredients", JSONArray()) }))
        assertEquals("SCHEMA:OUT_OF_RANGE@\$.options[0].ingredients[0].quantity", outcome(mutated { firstOption(it).getJSONArray("ingredients").getJSONObject(0).put("quantity", 0) }))
    }

    @Test
    fun countRules_splitBetweenSchemaAndBusiness() {
        // 6 options: the schema already forbids it; 4 options: structurally fine, the business rule "exactly five" rejects it.
        val six = mutated { root -> root.getJSONArray("options").put(JSONObject(firstOption(root).toString())) }
        assertEquals("SCHEMA:TOO_MANY_ITEMS@\$.options", outcome(six))
        val four = mutated { root -> root.getJSONArray("options").remove(4) }
        assertEquals("BUSINESS:EXPECTED_FIVE_SUGGESTIONS", outcome(four))
    }

    @Test
    fun businessRulesOfThePipeContractApplyUnchanged() {
        assertEquals("BUSINESS:INVALID_SUGGESTION_TITLE", outcome(mutated { firstOption(it).put("title", "x".repeat(71)) }))
        assertEquals("BUSINESS:INVALID_COMPACT_ANSWER", outcome(mutated { it.put("answer", "") }))
        assertEquals("BUSINESS:ASSUMPTIONS_TOO_LONG", outcome(mutated { it.put("assumptions", "y".repeat(121)) }))
        val outOfScopeWithOptions = mutated { it.put("inScope", false) }
        assertEquals("BUSINESS:OUT_OF_SCOPE_WITH_SUGGESTIONS", outcome(outOfScopeWithOptions))
        assertEquals("BUSINESS:NA_JSON_INGREDIENT_NAME", outcome(mutated { firstOption(it).getJSONArray("ingredients").getJSONObject(0).put("name", "  ") }))
    }

    @Test
    fun hardConstraintsAreStillEnforcedLocally_usingTheIngredientNames() {
        val lactoseIntolerant = DietaryProfile(intolerances = listOf("lattosio"))
        val json = mutated { firstOption(it).getJSONArray("ingredients").getJSONObject(0).put("name", "yogurt greco") }
        assertEquals("VALID", outcome(json))
        assertEquals("BUSINESS:FOOD_CONSTRAINT_VIOLATION", outcome(json, lactoseIntolerant))
        // The typed contract always carries ingredients (minItems 1), so "foods required" can no longer be violated by omission.
        assertEquals("VALID", outcome(golden.jsonFull(), DietaryProfile(allergies = listOf("arachidi"))))
    }

    @Test
    fun truncatedFencedAndPrefixedAnswers_areMalformedJsonNotSilentlyAccepted() {
        val valid = golden.jsonFull()
        assertEquals("SCHEMA:NOT_JSON_OBJECT@\$", outcome(valid.dropLast(40)))
        assertEquals("SCHEMA:NOT_JSON_OBJECT@\$", outcome("```json\n$valid\n```"))
        assertEquals("SCHEMA:NOT_JSON_OBJECT@\$", outcome("Ecco la risposta: $valid"))
        assertEquals("SCHEMA:NOT_JSON_OBJECT@\$", outcome(""))
        assertEquals("SCHEMA:NOT_JSON_OBJECT@\$", outcome("[]"))
        // Text AFTER the object is ignored by org.json (JVM and Android): harmless, the object itself is validated in full.
        assertEquals("VALID", outcome("$valid\nSpero sia utile."))
    }

    @Test
    fun separatorsInTextAreNormalContentForJson() {
        val json = mutated {
            firstOption(it).put("title", "Pollo | riso").put("reason", "riga uno\nriga due")
            firstOption(it).getJSONArray("ingredients").getJSONObject(0).put("name", "olio, extravergine")
        }
        val value = validate(json).getOrThrow().value
        assertEquals("Pollo | riso", value.suggestions[0].title)
        assertEquals("riga uno\nriga due", value.suggestions[0].reason)
        assertEquals("olio, extravergine", value.suggestions[0].foods[0])
    }

    // ---------- 3. the real executeValidated path (scripted provider, no network) ----------

    private class Scripted(private val answers: List<Pair<String, String?>>) : AiProvider {
        override val type = AiProviderType.OPENAI
        var calls = 0
        val prompts = mutableListOf<String>()
        override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
            prompts += request.userPrompt
            val (text, finish) = answers[minOf(calls++, answers.lastIndex)]
            return AiRawResponse(type, "scripted", text, finishReason = finish)
        }
    }

    private fun service() = AiExecutionService(
        contracts = { name -> NutritionAdviceJsonContract.SPEC.takeIf { it.schemaName == name } },
        diagnostics = {},
    )

    private fun run(provider: Scripted, profile: DietaryProfile = DietaryProfile()) = runCatching {
        runBlocking {
            service().executeValidated(provider, NutritionAdviceJsonContract.buildRequest("U:test\nQ:cosa mangio?"), 1, NutritionAdviceJsonContract.business(profile))
        }
    }

    @Test
    fun executeValidated_returnsTheValidatedDomainValue() {
        val provider = Scripted(listOf(golden.jsonFull() to "STOP"))
        val execution = run(provider).getOrThrow()
        assertEquals(1, provider.calls)
        assertEquals(NutritionAdviceJsonContract.SCHEMA_NAME, execution.output.schemaName)
        assertEquals(5, execution.output.value.suggestions.size)
        assertEquals(golden.answer, execution.output.value.answer)
    }

    @Test
    fun executeValidated_schemaErrorGetsOneRetryWithAJsonSpecificHint_thenSucceeds() {
        val broken = mutated { firstOption(it).getJSONArray("ingredients").getJSONObject(0).put("unit", "q.b.") }
        val provider = Scripted(listOf(broken to "STOP", golden.jsonFull() to "STOP"))
        val execution = run(provider).getOrThrow()
        assertEquals(2, provider.calls)
        assertTrue(provider.prompts[1].contains("INVALID_SCHEMA"))
        assertFalse("native contracts never ask for the pipe envelope", provider.prompts[1].contains("property named data"))
        assertEquals(5, execution.output.value.suggestions.size)
    }

    @Test
    fun executeValidated_businessErrorIsNotRetriedForNativeContracts() {
        // Documented limitation: AiExecutionService retries a business failure only for COMPACT_PIPE contracts.
        val lactose = DietaryProfile(intolerances = listOf("lattosio"))
        val violating = mutated { firstOption(it).getJSONArray("ingredients").getJSONObject(0).put("name", "yogurt greco") }
        val provider = Scripted(listOf(violating to "STOP", golden.jsonFull() to "STOP"))
        val error = run(provider, lactose).exceptionOrNull()
        assertEquals(1, provider.calls)
        val failure = AiFailure.classify(error!!) as AiFailure.BusinessRuleViolated
        assertEquals("FOOD_CONSTRAINT_VIOLATION", failure.ruleCode)
    }

    @Test
    fun executeValidated_truncationAndMalformedJsonKeepTheirCategories() {
        val truncated = run(Scripted(listOf(golden.jsonFull().dropLast(60) to "MAX_TOKENS"))).exceptionOrNull()
        assertEquals("OUTPUT_TRUNCATED", AiFailure.classify(truncated!!).code)
        val malformed = run(Scripted(listOf("```json\n${golden.jsonFull()}\n```" to "STOP"))).exceptionOrNull()
        assertEquals("MALFORMED_JSON", AiFailure.classify(malformed!!).code)
    }

    // ---------- 4. how the gateway would hand the schema to each provider ----------

    private fun walk(node: JSONObject, visit: (JSONObject) -> Unit) {
        visit(node)
        node.optJSONObject("items")?.let { walk(it, visit) }
        node.optJSONObject("properties")?.let { props -> props.keys().forEach { walk(props.getJSONObject(it), visit) } }
    }

    @Test
    fun schemaFollowsTheOpenAiStrictRules() {
        val forbidden = listOf("nullable", "minLength", "maxLength", "pattern", "format", "\$ref", "oneOf", "anyOf", "allOf", "not")
        walk(JSONObject(NutritionAdviceJsonContract.schemaJson)) { node ->
            forbidden.forEach { assertFalse("keyword $it is not portable", node.has(it)) }
            node.optJSONObject("properties")?.let { props ->
                val keys = props.keys().asSequence().toSet()
                val required = node.getJSONArray("required").let { r -> (0 until r.length()).map(r::getString).toSet() }
                assertEquals("strict mode: every property must be required", keys, required)
                assertEquals(false, node.get("additionalProperties"))
            }
        }
    }

    @Test
    fun openAiRequestWouldCarryTheSchemaAsStrictJsonSchemaUnderThisSchemaName() {
        val body = OpenAiProvider.buildRequestBody("test-model", NutritionAdviceJsonContract.buildRequest("U:x"))
        val format = body.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals(NutritionAdviceJsonContract.SCHEMA_NAME, format.getString("name"))
        assertTrue(format.getBoolean("strict"))
        assertEquals(NutritionAdviceJsonContract.MAX_OUTPUT_TOKENS, body.getInt("max_output_tokens"))
        assertTrue(format.getJSONObject("schema").getJSONObject("properties").has("options"))
    }

    @Test
    fun geminiMapperKeepsEnumTypesBoundsAndRequired_andRequestUsesResponseSchema() {
        val mapped = GeminiSchemaMapper.map(NutritionAdviceJsonContract.schemaJson).schema
        val option = mapped.getJSONObject("properties").getJSONObject("options")
        assertEquals("ARRAY", option.getString("type"))
        assertEquals(5, option.getInt("maxItems"))
        val optionProps = option.getJSONObject("items").getJSONObject("properties")
        assertEquals("INTEGER", optionProps.getJSONObject("kcal").getString("type"))
        assertEquals(2500.0, optionProps.getJSONObject("kcal").getDouble("maximum"), 0.0)
        val unit = optionProps.getJSONObject("ingredients").getJSONObject("items").getJSONObject("properties").getJSONObject("unit")
        assertEquals(NutritionAdviceJsonContract.UNITS.size, unit.getJSONArray("enum").length())
        assertEquals(4, mapped.getJSONArray("required").length())
        assertEquals("BOOLEAN", mapped.getJSONObject("properties").getJSONObject("inScope").getString("type"))

        val config = GeminiByokProvider.buildGenerationConfig(NutritionAdviceJsonContract.buildRequest("U:x"), mapped)
        assertEquals("application/json", config.getString("responseMimeType"))
        assertNotNull(config.optJSONObject("responseSchema"))
        assertFalse("thinkingConfig must be omitted for this contract (Gemini 3.5 Flash-Lite rejects budget 0)", config.has("thinkingConfig"))
        assertEquals(NutritionAdviceJsonContract.MAX_OUTPUT_TOKENS, config.getInt("maxOutputTokens"))
    }

    @Test
    fun localValidatorAcceptsEverySchemaKeywordUsed() {
        // A schema keyword the local validator does not know would fail closed with UNSUPPORTED_KEYWORD on every answer.
        val result = CanonicalJsonSchemaValidator.validate(golden.jsonFull(), NutritionAdviceJsonContract.schemaJson)
        assertTrue(result.errors.joinToString(), result.valid)
    }
}
