package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiCompactEnvelope

private fun String.f(code: String): Float = normalizedNumber().toFloatOrNull()?.takeIf { it.isFinite() }
    ?: error("$code value='$this'")
private fun String.i(code: String): Int = normalizedNumber().toIntOrNull() ?: error("$code value='$this'")
/** Providers occasionally emit locale decimal commas in compact numeric fields. */
private fun String.normalizedNumber(): String = trim().replace(',', '.')
private fun String.req(code: String): String = trim().also { require(it.isNotEmpty()) { code } }
private fun compactLines(json: String, version: String): List<String> = AiCompactEnvelope.data(json)
    .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    .also { require(it.firstOrNull() == version) { "${version}_INVALID" } }

object CheatUnderstandingCompactContract {
    const val SCHEMA_NAME = "myfitai_cheat_understanding_pipe_v1"
    const val PROTOCOL = "CU1\nU|understoodFood\nE|kcal|proteinG|carbsG|fatG|low_medium_high|notes"
    val schemaJson: String get() = AiCompactEnvelope.schemaJson(PROTOCOL)

    fun parse(json: String): CheatUnderstandingContract.Preview {
        val lines = compactLines(json, "CU1")
        require(lines.size == 3) { "CU_COUNT_INVALID" }
        val u = lines[1].split('|'); require(u.size == 2 && u[0] == "U")
        val e = lines[2].split('|'); require(e.size == 7 && e[0] == "E")
        return CheatUnderstandingContract.Preview(
            understoodFood = u[1].req("CU_FOOD_INVALID"),
            estimate = CheatAdjustmentContract.Estimate(
                kcal = e[1].i("CU_KCAL_INVALID"), proteinG = e[2].f("CU_P_INVALID"),
                carbsG = e[3].f("CU_C_INVALID"), fatG = e[4].f("CU_F_INVALID"),
                confidence = e[5].req("CU_CONF_INVALID"), notes = e[6].trim(),
            ),
        )
    }
}

object CheatAdjustmentCompactContract {
    const val SCHEMA_NAME = "myfitai_cheat_adjustment_pipe_v1"
    const val PROTOCOL = """CA1
E|kcal|proteinG|carbsG|fatG|low_medium_high|notes
A|0_or_1|reason
R|sortOrder|type|title|timeMinutes|kcal|proteinG|carbsG|fatG|preparation
I|name|quantity|unit|displayDose|weightState|nutritionConfidence|category
V|1_or_0|notes"""
    val schemaJson: String get() = AiCompactEnvelope.schemaJson(PROTOCOL)

    fun parse(json: String): CheatAdjustmentContract.Response {
        val lines = compactLines(json, "CA1")
        var estimate: CheatAdjustmentContract.Estimate? = null
        var possible: Boolean? = null
        var reason = ""
        var validation: NutritionPlanContract.AgentValidation? = null
        val replacements = mutableListOf<CheatAdjustmentContract.ReplacementMeal>()
        var current: ReplacementBuilder? = null
        fun flush() { current?.let { require(it.ingredients.isNotEmpty()) { "CA_INGREDIENTS_REQUIRED" }; replacements += it.build() }; current = null }
        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "E" -> { require(p.size == 7 && estimate == null); estimate = CheatAdjustmentContract.Estimate(p[1].i("CA_KCAL"), p[2].f("CA_P"), p[3].f("CA_C"), p[4].f("CA_F"), p[5].req("CA_CONF"), p[6].trim()) }
                "A" -> { require(p.size == 3 && possible == null); possible = when (p[1]) { "1" -> true; "0" -> false; else -> error("CA_FLAG") }; reason = p[2].trim() }
                "R" -> { require(p.size == 10 && possible == true && validation == null); flush(); current = ReplacementBuilder(p[1].i("CA_SORT"), p[2].req("CA_TYPE"), p[3].req("CA_TITLE"), p[4].i("CA_TIME"), p[5].i("CA_RKCAL"), p[6].f("CA_RP"), p[7].f("CA_RC"), p[8].f("CA_RF"), p[9].trim()) }
                "I" -> { require(p.size == 8 && current != null && validation == null); current!!.ingredients += NutritionPlanContract.GeneratedIngredient(p[1].req("CA_INAME"), p[2].f("CA_IQTY"), p[3].req("CA_IUNIT"), p[4].req("CA_IDOSE"), p[5].trim(), p[6].trim(), p[7].trim()) }
                "V" -> { require(p.size == 3 && validation == null && p[1] in setOf("0", "1")); flush(); validation = NutritionPlanContract.AgentValidation(p[1] == "1", p[2].trim()) }
                else -> error("CA_RECORD_INVALID")
            }
        }
        flush()
        return CheatAdjustmentContract.Response(
            requireNotNull(estimate) { "CA_ESTIMATE_MISSING" },
            requireNotNull(possible) { "CA_ADAPTATION_MISSING" },
            reason,
            replacements,
            requireNotNull(validation) { "CA_VALIDATION_MISSING" },
        )
    }

    private data class ReplacementBuilder(
        val sortOrder: Int, val type: String, val title: String, val timeMinutes: Int, val kcal: Int,
        val proteinG: Float, val carbsG: Float, val fatG: Float, val preparation: String,
        val ingredients: MutableList<NutritionPlanContract.GeneratedIngredient> = mutableListOf(),
    ) {
        fun build() = CheatAdjustmentContract.ReplacementMeal(sortOrder, type, title, timeMinutes, kcal, proteinG, carbsG, fatG, preparation, ingredients.toList())
    }
}

object MealAlternativeCompactContract {
    const val SCHEMA_NAME = "myfitai_meal_alternatives_pipe_v1"
    const val PROTOCOL = """MA1
A|title|kcal|proteinG|carbsG|fatG|preparation|reason
I|name|quantity|unit|displayDose|weightState|nutritionConfidence|category
V|1_or_0|notes"""
    val schemaJson: String get() = AiCompactEnvelope.schemaJson(PROTOCOL)

    fun parse(json: String): MealAlternativeContract.Response {
        val lines = compactLines(json, "MA1")
        val alternatives = mutableListOf<MealAlternativeContract.Alternative>()
        var validation: NutritionPlanContract.AgentValidation? = null
        var current: AlternativeBuilder? = null
        fun flush() { current?.let { require(it.ingredients.isNotEmpty()) { "MA_INGREDIENTS_REQUIRED" }; alternatives += it.build() }; current = null }
        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "A" -> {
                    require(p.size == 8) { "MA_A_FIELDS_${p.size}" }
                    flush()
                    current = AlternativeBuilder(p[1].req("MA_TITLE"), p[2].i("MA_KCAL"), p[3].f("MA_P"), p[4].f("MA_C"), p[5].f("MA_F"), p[6].trim(), p[7].trim())
                }
                "I" -> {
                    require(p.size == 8) { "MA_I_FIELDS_${p.size}" }
                    require(current != null) { "MA_I_WITHOUT_ALTERNATIVE" }
                    current!!.ingredients += MealAlternativeContract.Ingredient(p[1].req("MA_INAME"), p[2].f("MA_QTY"), p[3].req("MA_UNIT"), p[4].req("MA_DOSE"), p[5].trim(), p[6].trim(), p[7].trim())
                }
                "V" -> {
                    require(p.size == 3) { "MA_V_FIELDS_${p.size}" }
                    require(p[1] in setOf("0", "1")) { "MA_V_FLAG_${p[1]}" }
                    flush()
                    // Gemini sometimes emits the agent-validation marker after each alternative
                    // instead of once at the end. It is advisory; keep the first marker and parse
                    // all alternatives so app validation remains authoritative.
                    if (validation == null) validation = NutritionPlanContract.AgentValidation(p[1] == "1", p[2].trim())
                }
                else -> error("MA_RECORD_INVALID_${p.firstOrNull().orEmpty()}")
            }
        }
        flush()
        return MealAlternativeContract.Response(alternatives, requireNotNull(validation) { "MA_VALIDATION_MISSING" })
    }

    private data class AlternativeBuilder(
        val title: String, val kcal: Int, val proteinG: Float, val carbsG: Float, val fatG: Float,
        val preparation: String, val reason: String, val ingredients: MutableList<MealAlternativeContract.Ingredient> = mutableListOf(),
    ) {
        fun build() = MealAlternativeContract.Alternative(title, kcal, proteinG, carbsG, fatG, preparation, reason, ingredients.toList())
    }
}

object NutritionAdviceCompactContract {
    const val SCHEMA_NAME = "myfitai_nutrition_advice_pipe_v1"
    const val PROTOCOL = """NA1
S|0_or_1
A|answer
O|title|reason|kcal|proteinG|carbsG|fatG
Q|assumptions
V|1_or_0|notes"""
    val schemaJson: String get() = AiCompactEnvelope.schemaJson(PROTOCOL)

    fun parse(json: String): NutritionAdviceContract.Response {
        val lines = compactLines(json, "NA1")
        var inScope: Boolean? = null
        var answer: String? = null
        var assumptions: String? = null
        var validation: NutritionPlanContract.AgentValidation? = null
        val suggestions = mutableListOf<NutritionAdviceContract.Suggestion>()
        lines.drop(1).forEach { line ->
            val p = line.split('|')
            when (p.firstOrNull()) {
                "S" -> { require(p.size == 2 && inScope == null); inScope = when (p[1]) { "1" -> true; "0" -> false; else -> error("NA_SCOPE") } }
                "A" -> { require(p.size == 2 && answer == null); answer = p[1].trim() }
                "O" -> { require(p.size == 7 && validation == null); suggestions += NutritionAdviceContract.Suggestion(p[1].req("NA_TITLE"), p[2].req("NA_REASON"), p[3].i("NA_KCAL"), p[4].f("NA_P"), p[5].f("NA_C"), p[6].f("NA_F")) }
                "Q" -> { require(p.size == 2 && assumptions == null); assumptions = p[1].trim() }
                "V" -> { require(p.size == 3 && validation == null && p[1] in setOf("0", "1")); validation = NutritionPlanContract.AgentValidation(p[1] == "1", p[2].trim()) }
                else -> error("NA_RECORD_INVALID")
            }
        }
        return NutritionAdviceContract.Response(
            requireNotNull(inScope) { "NA_SCOPE_MISSING" },
            requireNotNull(answer) { "NA_ANSWER_MISSING" },
            suggestions,
            requireNotNull(assumptions) { "NA_ASSUMPTIONS_MISSING" },
            requireNotNull(validation) { "NA_VALIDATION_MISSING" },
        )
    }
}
