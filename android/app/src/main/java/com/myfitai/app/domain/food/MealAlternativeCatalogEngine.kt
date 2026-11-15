package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.toDraft
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.food.catalog.CatalogConstraints
import com.myfitai.app.domain.food.catalog.FoodCatalog
import com.myfitai.app.domain.food.catalog.FoodKind
import com.myfitai.app.domain.food.nutrition.ValidationPolicy
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Alternative pasto coerenti con il catalogo (lotto MACRO L5a, protocollo "MA2").
 *
 * Quando il pasto da sostituire e' COMPUTED (macro calcolati dall'app) il modello NON scrive piu' kcal e macro: sceglie
 * solo alimenti e grammi e l'app calcola tutto. Il trasporto e' MFP2 ridotto ([FoodCatalogPlanContract], stessa busta,
 * stesso parser, stesso schemaName): `W|<settimana>`, `D|<giorno>`, esattamente [ALTERNATIVES] record `M` (una
 * alternativa ciascuno, ognuno seguito dai suoi `I|foodId|grammi`) e `V`. Nessun record `S`/`H`.
 *
 * Per ogni alternativa si riusa [FoodCatalogPlanBuilder.build] come "giorno con un solo pasto" il cui target e' il pasto
 * originale: allergeni/esclusioni per TAG del catalogo, riparazione dei grammi ([com.myfitai.app.domain.food.nutrition.DayRepair]),
 * calcolo ingrediente = per100 x grammi, titolo coerente con gli ingredienti, avviso proteine non bloccante.
 * Accettazione: |kcal calcolate - kcal del pasto originale| <= 3% al primo tentativo, 4% ai retry (poi si riparte da
 * 3%: ogni generazione ha il proprio contatore).
 */
class MealAlternativeCatalogEngine(val catalog: FoodCatalog) {
    private val builder = FoodCatalogPlanBuilder(catalog)

    /** Pasto da sostituire e contesto: [meal] e' il pasto COMPUTED corrente (kcal e macro sono il target dell'alternativa). */
    data class Input(
        val weekStartEpochDay: Long,
        val dayEpochDay: Long,
        val meal: FoodMeal,
        val dietaryProfile: DietaryProfile,
    )

    data class Generated(val items: List<MealAlternativeContract.Alternative>, val provider: String, val model: String)

    fun request(input: Input): AiStructuredRequest = AiStructuredRequest(
        systemPrompt = SYSTEM_PROMPT,
        userPrompt = userPrompt(input),
        schemaName = FoodCatalogPlanContract.SCHEMA_NAME,
        schemaJson = FoodCatalogPlanContract.schemaJson,
        maxOutputTokens = null,
        thinkingBudget = 0,
        allowSchemaFallback = true,
    )

    suspend fun generate(aiRuntime: AiRuntimeGateway, input: Input): Generated {
        var attempt = 0
        var accepted: List<MealAlternativeContract.Alternative>? = null
        val validated = aiRuntime.execute(
            request = request(input),
            maxSchemaRetries = 2,
            businessValidator = { json ->
                val current = attempt++
                runCatching { accepted = evaluate(json, input, current); Unit }
            },
        )
        val items = accepted ?: evaluate(validated.jsonText, input, 0)
        return Generated(items, validated.provider.name, validated.model)
    }

    /**
     * Un tentativo (corpo del businessValidator): busta MFP2 ridotta -> alternative calcolate. Qualunque rifiuto e'
     * un'eccezione con un codice stabile (mai testo del modello). Un'alternativa fuori tolleranza (grammi non
     * riparabili) viene scartata; il tentativo e' rifiutato se ne restano meno di [MIN_ALTERNATIVES]. Un alimento
     * ignoto o che viola un vincolo del profilo rifiuta SEMPRE l'intero tentativo.
     */
    fun evaluate(json: String, input: Input, attempt: Int): List<MealAlternativeContract.Alternative> {
        val source = input.meal
        val targetKcal = requireNotNull(source.kcal?.takeIf { it > 0 }) { "SOURCE_MEAL_KCAL_MISSING" }
        val tolerance = NutritionPlanAssessment.toleranceForAttempt(attempt)
        val proposal = FoodCatalogPlanContract.parseEnvelope(json, catalog, ALTERNATIVES)
        require(proposal.weekStartEpochDay == input.weekStartEpochDay) { "MA2_WEEK_MISMATCH" }
        val day = proposal.days.singleOrNull() ?: throw IllegalArgumentException("MA2_SINGLE_DAY_REQUIRED")
        require(day.dateEpochDay == input.dayEpochDay) { "MA2_DAY_MISMATCH" }
        require(day.supplements.isEmpty()) { "MA2_SUPPLEMENT_NOT_ALLOWED" }

        val targets = NutritionBusinessValidator.Targets(
            kcal = targetKcal.toDouble(),
            proteinG = (source.proteinG ?: 0f).toDouble(),
            carbsG = (source.carbsG ?: 0f).toDouble(),
            fatG = (source.fatG ?: 0f).toDouble(),
        )
        val sourceFoods = source.ingredients.mapNotNull { it.foodId }.toSet()
        val failures = mutableListOf<String>()
        val seenTitles = mutableSetOf<String>()
        val accepted = mutableListOf<MealAlternativeContract.Alternative>()
        day.meals.forEachIndexed { index, meal ->
            // Giorno sintetico 1..N: il numero davanti a "target=" nei motivi di retry e' il numero dell'alternativa.
            val key = (index + 1).toLong()
            val single = FoodCatalogPlanContract.Day(
                key,
                listOf(meal.copy(type = source.type, timeMinutes = source.timeMinutes ?: meal.timeMinutes)),
                emptyList(),
                "",
            )
            val built = try {
                builder.build(
                    FoodCatalogPlanContract.Proposal(proposal.weekStartEpochDay, listOf(single), proposal.agentValidation),
                    FoodCatalogPlanBuilder.Params(targets, mapOf(key to targets), input.dietaryProfile, tolerance, attempt),
                )
            } catch (error: FoodCatalogPlanBuilder.Rejection) {
                if (error.message.orEmpty().startsWith(NutritionPlanAssessment.TOLERANCE_EXCEEDED_CODE)) {
                    failures += error.message.orEmpty().removePrefix("${NutritionPlanAssessment.TOLERANCE_EXCEEDED_CODE}:")
                    return@forEachIndexed
                }
                throw error
            }
            val computed = built.response.days.single().meals.single()
            val alternative = toAlternative(computed, built.computation.warnings.firstOrNull(), targets.proteinG)
            FoodConstraintValidator.validateAlternative(alternative, input.dietaryProfile).getOrThrow()
            val sameAsCurrent = sourceFoods.isNotEmpty() && alternative.ingredients.mapNotNull { it.foodId }.toSet() == sourceFoods
            if (sameAsCurrent || !seenTitles.add(DietaryProfile.normalize(alternative.title))) {
                failures += "$key:DUPLICATE"
                return@forEachIndexed
            }
            accepted += alternative
        }
        if (accepted.size < MIN_ALTERNATIVES) {
            val toleranceFailures = failures.filterNot { it.endsWith(":DUPLICATE") }
            throw IllegalArgumentException(
                if (toleranceFailures.isNotEmpty()) "${NutritionPlanAssessment.TOLERANCE_EXCEEDED_CODE}:${toleranceFailures.joinToString("|")}"
                else "MA2_TOO_FEW_DISTINCT_ALTERNATIVES",
            )
        }
        return accepted
    }

    /**
     * Ricostruisce in modo deterministico il pasto di un'alternativa MA2 gia' accettata partendo solo da (foodId, grammi)
     * e RIVERIFICA tutto al momento dell'applicazione: versione del catalogo, alimenti noti, TAG del profilo, kcal entro
     * la tolleranza dei retry rispetto al pasto che si sostituisce e numeri identici a quelli mostrati.
     * Lancia [IllegalArgumentException] se qualcosa non torna.
     */
    fun rebuild(alternative: MealAlternativeContract.Alternative, input: Input): NutritionPlanContract.GeneratedMeal {
        val source = input.meal
        val targetKcal = requireNotNull(source.kcal?.takeIf { it > 0 }) { "SOURCE_MEAL_KCAL_MISSING" }
        require(alternative.catalogVersion == catalog.version) { "MA2_CATALOG_VERSION" }
        require(alternative.ingredients.isNotEmpty()) { "MA2_INGREDIENTS_REQUIRED" }
        val allowed = catalog.subsetFor(CatalogConstraints(profile = input.dietaryProfile, kinds = FoodKind.entries.toSet())).map { it.id }.toSet()
        val items = alternative.ingredients.map { ingredient ->
            val id = requireNotNull(ingredient.foodId) { "MA2_FOOD_MISSING" }
            val grams = requireNotNull(ingredient.grams) { "MA2_GRAMS_MISSING" }.toDouble()
            require(grams > 0.0 && grams.isFinite()) { "MA2_GRAMS_INVALID" }
            require(id in allowed) { "FOOD_CONSTRAINT_VIOLATION" }
            (catalog.byId(id) ?: throw IllegalArgumentException("PIPE_I_FOOD_UNKNOWN")) to grams
        }
        val meal = builder.computedMeal(source.type, alternative.title, source.timeMinutes ?: 0, alternative.preparation, items)
        require(kotlin.math.abs(meal.kcal - targetKcal) <= targetKcal * ValidationPolicy.RETRY_TOLERANCE + EPS) { NutritionPlanAssessment.TOLERANCE_EXCEEDED_CODE }
        require(meal.kcal == alternative.kcal) { "MA2_KCAL_CHANGED" }
        FoodConstraintValidator.validateAlternative(toAlternative(meal, null, 0.0), input.dietaryProfile).getOrThrow()
        return meal
    }

    private fun toAlternative(
        meal: NutritionPlanContract.GeneratedMeal,
        proteinWarning: NutritionValidationIssue?,
        sourceProteinG: Double,
    ): MealAlternativeContract.Alternative = MealAlternativeContract.Alternative(
        title = meal.title,
        kcal = meal.kcal,
        proteinG = meal.proteinG,
        carbsG = meal.carbsG,
        fatG = meal.fatG,
        preparation = meal.preparation,
        // Avviso non bloccante: la riparazione ha dato priorita' alle kcal; vuoto = testo standard dell'interfaccia.
        reason = proteinWarning?.let {
            "Proteine ${meal.proteinG.roundToInt()} g contro ${sourceProteinG.roundToInt()} g del pasto attuale."
        }.orEmpty(),
        ingredients = meal.ingredients.map {
            MealAlternativeContract.Ingredient(
                name = it.name,
                quantity = it.quantity,
                unit = it.unit,
                displayDose = it.displayDose,
                weightState = it.weightState,
                nutritionConfidence = it.nutritionConfidence,
                category = it.category,
                foodId = it.foodId,
                grams = it.grams,
            )
        },
        catalogVersion = catalog.version,
    )

    private fun userPrompt(input: Input): String = buildString {
        val meal = input.meal
        val month = LocalDate.ofEpochDay(input.dayEpochDay).monthValue
        appendLine("WEEK:${input.weekStartEpochDay}")
        appendLine("DAY:${input.dayEpochDay}")
        appendLine("MEAL_TYPE:${meal.type}")
        appendLine("MEAL_TIME_MINUTES:${meal.timeMinutes ?: 0}")
        appendLine("CURRENT:${meal.title}|${meal.kcal}kcal|P${meal.proteinG}|C${meal.carbsG}|F${meal.fatG}")
        meal.ingredients.filter { it.foodId != null && it.grams != null }.takeIf { it.isNotEmpty() }?.let { current ->
            appendLine("CURRENT_FOODS:" + current.joinToString(";") { "${it.foodId}:${it.grams}" })
        }
        appendLine("TARGET_KCAL:${meal.kcal}")
        appendLine("DP:${input.dietaryProfile.toPromptCompact()}")
        appendLine("A/I/E/S in DP are hard constraints. D/P are soft preferences. Write exactly $ALTERNATIVES distinct alternatives; choose foods and grams whose total is already close to TARGET_KCAL. In a FIX message the number before target= is the alternative number.")
        append(FoodCatalogPlanPrompt.catalogBlock(catalog, CatalogConstraints(profile = input.dietaryProfile, month = month)))
    }

    companion object {
        const val ALTERNATIVES = 5

        /** Alternative minime dopo lo scarto di quelle non riparabili entro la tolleranza. */
        const val MIN_ALTERNATIVES = 3
        private const val EPS = 1e-9

        val SYSTEM_PROMPT: String = """
MyFitAI Meal Alternative Agent. Nutrition only: replace ONE planned meal with $ALTERNATIVES alternatives built from the app's food catalog. Output exactly one JSON object with exactly one property named `data`; `data` must be a string beginning with the exact line `${FoodCatalogPlanContract.HEADER}`, followed by the pipe records below. No markdown, no text outside records.
W|WEEK (copy the number from the user message)
D|DAY (copy the number from the user message)
M|type|title|timeMinutes|preparation (exactly $ALTERNATIVES M records, one per alternative; type and timeMinutes copied from MEAL_TYPE and MEAL_TIME_MINUTES; each M is followed by its I records)
I|foodId|grams (at least one per M; foodId copied exactly from the CAT list; grams = positive number with a dot, no unit)
V|1_or_0|notes (exactly one, last line; notes <= 8 words)
No S or H records. NUMBERS: you NEVER write kcal, protein, carbs, fat, totals, doses, units or any nutritional number. The app calculates them from the catalog and rescales your grams by at most about -40% / +50% per food to land on the TARGET_KCAL of the current meal, so propose realistic portions whose sum is already close to it.
CLOSED VOCABULARY: every foodId MUST be copied exactly from the CAT list (column id). Never invent, translate or alter an id. CAT already excludes foods that violate the hard constraints; stay inside it. CAT columns: id|name|state|kcal per 100 g|P|C|F|min-max grams per portion|step grams|units. state R = weigh raw/dry, C = weigh cooked, S = weigh as sold/eaten, - = not applicable.
Every edible ingredient, condiment and caloric beverage MUST be its own I record: oils, butter, dressings, sauces, sugar, honey, milk, juices, wine, protein powder cannot be omitted or hidden in a recipe. Never write `q.b.` or `un filo`: give grams. Keep the alternatives distinct from each other and from CURRENT_FOODS (different main ingredients), appropriate for the meal type and time, with a similar protein level to CURRENT.
Preparation: brief, truthful steps using only the foods of that M's own I records, WITHOUT any quantity (never digits followed by g, ml, spoons, cups, slices, pieces); times and temperatures are allowed. Never use `|` or line breaks inside a text field. No punitive compensation.
Skeleton: ${FoodCatalogPlanContract.HEADER} -> W -> D -> $ALTERNATIVES x (M -> I...) -> V.
""".trimIndent()

        /**
         * Replace per un'alternativa dalla CRONOLOGIA (nessuna IA). Se l'origine e' COMPUTED con snapshot completo il
         * pasto resta COMPUTED e i totali sono la SOMMA degli ingredienti (lo snapshot di ogni ingrediente e' gia'
         * riferito ai suoi grammi: nessun ricalcolo, nessuna copia campo per campo). Se l'origine e' DECLARED, o lo
         * snapshot e' incompleto, resta DECLARED con i totali dichiarati: comportamento storico invariato.
         */
        fun historicalReplacement(history: FoodMeal, source: FoodMeal): MealDraft {
            val ingredients = history.ingredients
            val complete = history.nutritionSource == FoodMeal.NUTRITION_SOURCE_COMPUTED && ingredients.isNotEmpty() &&
                ingredients.all { it.foodId != null && it.grams != null && it.kcal != null && it.proteinG != null && it.carbsG != null && it.fatG != null }
            if (!complete) {
                return MealDraft(
                    type = source.type,
                    title = history.title,
                    timeMinutes = source.timeMinutes,
                    kcal = history.kcal,
                    proteinG = history.proteinG,
                    carbsG = history.carbsG,
                    fatG = history.fatG,
                    preparation = history.preparation,
                    ingredients = ingredients.map { it.toDraft() },
                )
            }
            fun sum(selector: (FoodIngredient) -> Float?): Double = ingredients.sumOf { (selector(it) ?: 0f).toDouble() }
            return MealDraft(
                type = source.type,
                title = history.title,
                timeMinutes = source.timeMinutes,
                kcal = Math.round(sum { it.kcal }).toInt(),
                proteinG = (Math.round(sum { it.proteinG } * 10.0) / 10.0).toFloat(),
                carbsG = (Math.round(sum { it.carbsG } * 10.0) / 10.0).toFloat(),
                fatG = (Math.round(sum { it.fatG } * 10.0) / 10.0).toFloat(),
                preparation = history.preparation,
                ingredients = ingredients.map { it.toDraft() },
                nutritionSource = FoodMeal.NUTRITION_SOURCE_COMPUTED,
            )
        }
    }
}
