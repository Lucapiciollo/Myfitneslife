package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiContractRegistry
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.domain.ai.AiUserContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Which wire format [NutritionAdviceService] asks the provider for. D1 proof of feasibility (docs/D1_POC_NUTRITIONADVICE.md):
 * the typed JSON path exists in parallel with the pipe path and is OFF by default.
 */
enum class NutritionAdviceContractMode {
    /** `{"data":"NA1\n..."}` envelope parsed by [NutritionAdviceCompactContract] (the contract in production). */
    PIPE,

    /** Native typed JSON validated by schema v2 + [NutritionAdviceJsonContract.business]. Not enabled anywhere yet. */
    JSON;

    companion object {
        /** Internal switch: keep [PIPE] until the proof of feasibility is approved. */
        val DEFAULT: NutritionAdviceContractMode = PIPE
    }
}

/**
 * Typed (native) JSON contract of the nutrition-advice agent, an alternative to the pipe protocol NA1.
 *
 * It produces the SAME domain model as the pipe parser ([NutritionAdviceContract.Response]) so the service and UI
 * can use either one, and it reuses the SAME business validation ([NutritionAdviceContract.validateBusiness]:
 * structure, lengths, exactly five options, [FoodConstraintValidator] hard constraints).
 *
 * Schema rules (compatible with BOTH providers, see the doc):
 * - every property is `required` and `additionalProperties=false` (OpenAI `json_schema` strict needs both);
 * - no `nullable`, `minLength`/`maxLength`, `pattern`, `$ref`, `oneOf`/`anyOf` (not portable / not mapped by
 *   [com.myfitai.app.ai.GeminiSchemaMapper]); lengths are therefore enforced by the business validator;
 * - only `type`, `enum`, `minimum`/`maximum`, `minItems`/`maxItems`, `properties`, `required`, `items`, `description`.
 *
 * `agentValidation` is deliberately absent: it was never read for this agent, the authority is the local validation.
 */
object NutritionAdviceJsonContract {
    const val SCHEMA_NAME = "myfitai_nutrition_advice_v2"

    /** Output budget (tokens) measured on the D1 fixtures with margin; see the doc. */
    const val MAX_OUTPUT_TOKENS = 1_500

    /** Closed unit list: the model cannot invent "q.b." or "un filo" as a unit (CONDIMENTS_BEVERAGES_RULES). */
    val UNITS: List<String> = listOf("g", "ml", "pz", "cucchiaino", "cucchiaio", "bicchiere", "fetta")

    /** Registry row to add (one line in [AiContractRegistry.all]) to enable the path in production. NOT registered yet. */
    val SPEC = AiContractRegistry.Spec(
        schemaName = SCHEMA_NAME,
        format = AiContractRegistry.Format.NATIVE_JSON,
        protocolHeader = null,
        maxOutputTokens = MAX_OUTPUT_TOKENS,
        persisted = false,
    )

    private val unitsJson = UNITS.joinToString(",") { "\"$it\"" }

    val schemaJson: String = """{"type":"object","additionalProperties":false,"properties":{""" +
        """"inScope":{"type":"boolean"},""" +
        """"answer":{"type":"string"},""" +
        """"options":{"type":"array","minItems":0,"maxItems":5,"items":{"type":"object","additionalProperties":false,"properties":{""" +
        """"title":{"type":"string"},"reason":{"type":"string"},""" +
        """"kcal":{"type":"integer","minimum":1,"maximum":2500},""" +
        """"proteinG":{"type":"number","minimum":0,"maximum":250},""" +
        """"carbsG":{"type":"number","minimum":0,"maximum":500},""" +
        """"fatG":{"type":"number","minimum":0,"maximum":250},""" +
        """"ingredients":{"type":"array","minItems":1,"maxItems":10,"items":{"type":"object","additionalProperties":false,"properties":{""" +
        """"name":{"type":"string"},"quantity":{"type":"number","minimum":0.1,"maximum":2000},"unit":{"type":"string","enum":[$unitsJson]}},""" +
        """"required":["name","quantity","unit"]}}},""" +
        """"required":["title","reason","kcal","proteinG","carbsG","fatG","ingredients"]}},""" +
        """"assumptions":{"type":"string"}},""" +
        """"required":["inScope","answer","options","assumptions"]}"""

    /** Same rules as the pipe prompt, rewritten for named JSON fields (no record letters, no CSV). */
    val SYSTEM_PROMPT: String = """Nutrition advice only. ${AiUserContext.INPUT_DESCRIPTION} Out of scope includes goal selection, deficit/surplus decisions, body-progress interpretation, BIA interpretation, and deviation/cheat compensation; for any of those use inScope=false, answer exactly "Posso rispondere solo a richieste di consiglio alimentare e nutrizionale." and options=[]. In scope: use app context only; M rows are planned meals, never consumed; C is the user's explicitly recorded plan consumption; D rows are recorded deviations; A is C plus D; R is the remaining daily target after actual intake. DP uses A=allergies, I=intolerances, E=excluded foods, D=disliked, P=preferred, S=diet style, N=notes. T and R are the effective daily calorie and macro targets, including any current plan adaptation. A/I/E/S are HARD constraints and must never be violated; D/P are soft preferences. Every option MUST list in ingredients every food or ingredient implied by the option, each with a numeric quantity and unit, so the app can independently reject hard-constraint violations. Do not treat planned food as eaten. For each option, propose a realistic next-meal or snack solution that fits the positive R kcal allowance and preserves the remaining protein/carbs/fat balance; do not spend the same calories twice. If R is low or negative, explain the constraint and return the lightest compliant alternatives instead of recommending an excess. If Q names a desired food, keep all options centered on it only when it does not violate a hard constraint; otherwise explain the conflict and suggest compliant alternatives. For generic requests use N meal window strongly, then R target fit, balance, calorie impact, practicality. No diagnosis, invented conditions, fasting, punitive restriction, or moral food labels. Return exactly 5 options, each with whole-meal kcal/proteinG/carbsG/fatG. answer<=120 chars; title<=45; reason<=70; assumptions only if essential <=80, otherwise an empty string. Plan changes only after app confirmation."""

    /**
     * Parses a native JSON answer into the SAME domain model as the pipe parser. Ingredient names become
     * [NutritionAdviceContract.Suggestion.foods]; quantity/unit are range-checked but not kept (the contract is not persisted).
     * Throws [IllegalArgumentException] with a stable code (never model text).
     */
    fun parse(jsonText: String): NutritionAdviceContract.Response = try {
        val root = JSONObject(jsonText)
        val options = root.getJSONArray("options")
        val suggestions = buildList {
            for (i in 0 until options.length()) {
                val option = options.getJSONObject(i)
                val ingredients = option.getJSONArray("ingredients")
                val foods = buildList {
                    for (j in 0 until ingredients.length()) {
                        val ingredient = ingredients.getJSONObject(j)
                        val name = ingredient.getString("name").trim()
                        require(name.isNotEmpty()) { "NA_JSON_INGREDIENT_NAME" }
                        val quantity = ingredient.getDouble("quantity")
                        require(quantity.isFinite() && quantity > 0.0) { "NA_JSON_INGREDIENT_QUANTITY" }
                        require(ingredient.getString("unit") in UNITS) { "NA_JSON_INGREDIENT_UNIT" }
                        add(name)
                    }
                }
                add(
                    NutritionAdviceContract.Suggestion(
                        title = option.getString("title").trim(),
                        reason = option.getString("reason").trim(),
                        estimatedKcal = option.getInt("kcal"),
                        proteinG = option.getDouble("proteinG").toFloat(),
                        carbsG = option.getDouble("carbsG").toFloat(),
                        fatG = option.getDouble("fatG").toFloat(),
                        foods = foods,
                    ),
                )
            }
        }
        NutritionAdviceContract.Response(
            inScope = root.getBoolean("inScope"),
            answer = root.getString("answer").trim(),
            suggestions = suggestions,
            assumptions = root.getString("assumptions").trim(),
            agentValidation = NutritionPlanContract.AgentValidation(true, AGENT_VALIDATION_NOT_REQUESTED),
        )
    } catch (_: JSONException) {
        throw IllegalArgumentException("NA_JSON_SHAPE")
    }

    /** Parse + the unchanged business rules of the pipe contract. A failure carries a stable rule code. */
    fun business(dietaryProfile: DietaryProfile): (String) -> Result<NutritionAdviceContract.Response> = { json ->
        runCatching {
            parse(json).also { NutritionAdviceContract.validateBusiness(it, dietaryProfile).getOrThrow() }
        }
    }

    fun buildRequest(userPrompt: String): AiStructuredRequest = AiStructuredRequest(
        systemPrompt = SYSTEM_PROMPT,
        userPrompt = userPrompt,
        schemaName = SCHEMA_NAME,
        schemaJson = schemaJson,
        maxOutputTokens = MAX_OUTPUT_TOKENS,
        // Not a compact contract: [AiRuntimeService] forwards thinkingBudget as is, and Gemini 3.5 Flash-Lite rejects an explicit 0.
        thinkingBudget = null,
        useNativeSchema = true,
    )

    /** Typed path of the service: one provider call, schema v2 + business in a single step ([com.myfitai.app.ai.ValidatedOutput]). */
    suspend fun execute(
        aiRuntime: AiRuntimeGateway,
        userPrompt: String,
        dietaryProfile: DietaryProfile,
    ): AiExecutionService.ValidatedExecution<NutritionAdviceContract.Response> =
        aiRuntime.executeValidated(buildRequest(userPrompt), maxSchemaRetries = 1, business = business(dietaryProfile))

    const val AGENT_VALIDATION_NOT_REQUESTED = "NOT_REQUESTED_JSON_V2"
}
