package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.food.catalog.CatalogConstraints
import com.myfitai.app.domain.food.catalog.FoodCatalog
import com.myfitai.app.domain.food.catalog.FoodEntry
import com.myfitai.app.domain.food.catalog.FoodKind
import com.myfitai.app.domain.food.catalog.FoodState
import com.myfitai.app.domain.food.nutrition.DayRepair
import com.myfitai.app.domain.food.nutrition.DayTargets
import com.myfitai.app.domain.food.nutrition.DoseFormatter
import com.myfitai.app.domain.food.nutrition.IngredientInput
import com.myfitai.app.domain.food.nutrition.NutrientTotals
import com.myfitai.app.domain.food.nutrition.NutritionCalculator
import com.myfitai.app.domain.food.nutrition.RepairLimits
import com.myfitai.app.domain.food.nutrition.RepairMeal
import com.myfitai.app.domain.food.nutrition.RepairStatus
import com.myfitai.app.domain.food.nutrition.UnitEquivalent
import com.myfitai.app.domain.food.nutrition.ValidationPolicy
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import com.myfitai.app.domain.food.nutrition.Per100 as CalcPer100

/**
 * Da una proposta MFP2 (alimenti + grammi) al piano calcolato (lotto MACRO L3).
 *
 * Passi, per ogni giorno: risolve i foodId nel catalogo, controlla allergeni/esclusioni per TAG del catalogo, porta i
 * grammi verso il target del giorno con [DayRepair] (i pasti restano nei limiti per voce e per pasto; gli integratori
 * non sono scalabili) e costruisce un [NutritionPlanContract.Response] in cui ogni numero e' CALCOLATO:
 * ingrediente = per100 x grammi / 100, pasto = somma degli ingredienti, giorno = somma dei pasti.
 * Il piano costruito passa poi dal gate unico [NutritionPlanContract.validateBusinessAssessed] (struttura, date,
 * tolleranza kcal, vincoli per nome), identico a MFP1.
 *
 * Politica (decisione di prodotto): bloccano SOLO le kcal calcolate (+-3% primo tentativo, +-4% nei retry); le
 * proteine sotto target sono un avviso ([Computation.warnings]); carboidrati e grassi sono informativi.
 *
 * Limite dichiarato: il flusso di generazione non conosce pasti gia' consumati del periodo richiesto, quindi nessun
 * pasto e' bloccato nella riparazione (il supporto esiste in [RepairMeal.consumed] per i lotti successivi).
 */
class FoodCatalogPlanBuilder(val catalog: FoodCatalog) {

    /** Rifiuto con un codice stabile (nessun testo del modello). */
    class Rejection(message: String) : IllegalArgumentException(message)

    data class Params(
        val targets: NutritionBusinessValidator.Targets,
        val dailyTargets: Map<Long, NutritionBusinessValidator.Targets>,
        val dietaryProfile: DietaryProfile,
        /** Tolleranza kcal del tentativo: 0,03 al primo, 0,04 ai retry (vedi [NutritionPlanAssessment.toleranceForAttempt]). */
        val tolerance: Double,
        val attempt: Int,
        val limits: RepairLimits = RepairLimits.DEFAULT,
    )

    /** Una voce che la riparazione ha cambiato: [mealIndex] e' l'indice del pasto nel giorno (0-based). */
    data class Change(val mealIndex: Int, val foodId: String, val fromG: Double, val toG: Double)

    data class DayComputation(
        val dateEpochDay: Long,
        val status: RepairStatus,
        val targetKcal: Double,
        val proposedKcal: Double,
        val finalKcal: Double,
        val targetProteinG: Double,
        val proposedProteinG: Double,
        val finalProteinG: Double,
        val kcalBand: com.myfitai.app.domain.food.nutrition.DeviationBand,
        val proteinBand: com.myfitai.app.domain.food.nutrition.DeviationBand,
        val sweeps: Int,
        val reachableMinKcal: Double,
        val reachableMaxKcal: Double,
        val changes: List<Change>,
    )

    /**
     * Un tentativo rifiutato (parser, riparazione, vincoli, gate): [attempt] e' 1-based, [code] un codice stabile
     * (maiuscole e underscore) e [detail] al massimo [MAX_DETAIL_CHARS] caratteri di numeri/identificatori scritti
     * dall'app. Mai testo del modello ne' dati personali (vedi [rejectionOf]).
     */
    data class AttemptRejection(val attempt: Int, val code: String, val detail: String)

    /** Riepilogo della riparazione e avvisi non bloccanti: finisce in appValidationJson (chiave `computation`). */
    data class Computation(
        val catalogVersion: Int,
        val attempt: Int,
        val tolerance: Double,
        val days: List<DayComputation>,
        val warnings: List<NutritionValidationIssue>,
        /** Titoli dei pasti sostituiti perche' citavano un alimento assente dagli ingredienti. */
        val titlesReplaced: Int = 0,
        /** Tentativi rifiutati PRIMA di quello accettato (raccolti dal chiamante, vedi [rejectionOf]). */
        val attemptsLog: List<AttemptRejection> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("source", FoodMeal.NUTRITION_SOURCE_COMPUTED)
            put("catalogVersion", catalogVersion)
            put("attempt", attempt + 1)
            put("tolerancePercent", round1(tolerance * 100.0))
            put("titlesReplaced", titlesReplaced)
            put("attemptsLog", JSONArray().apply {
                attemptsLog.take(MAX_LOGGED_ATTEMPTS).forEach {
                    put(JSONObject().put("attempt", it.attempt).put("code", it.code).put("detail", it.detail))
                }
            })
            var budget = MAX_LISTED_CHANGES
            put("days", JSONArray().apply {
                days.forEach { day ->
                    val listed = day.changes.take(maxOf(0, budget))
                    budget -= listed.size
                    put(JSONObject().apply {
                        put("day", day.dateEpochDay)
                        put("status", day.status.name)
                        put("kcal", JSONObject().put("target", round1(day.targetKcal)).put("proposed", round1(day.proposedKcal)).put("final", round1(day.finalKcal)))
                        put("protein", JSONObject().put("target", round1(day.targetProteinG)).put("proposed", round1(day.proposedProteinG)).put("final", round1(day.finalProteinG)))
                        put("kcalBand", day.kcalBand.name)
                        put("proteinBand", day.proteinBand.name)
                        put("sweeps", day.sweeps)
                        put("reachKcal", JSONArray().put(Math.round(day.reachableMinKcal)).put(Math.round(day.reachableMaxKcal)))
                        put("changed", day.changes.size)
                        put("changes", JSONArray().apply {
                            listed.forEach { put("${it.mealIndex}|${it.foodId}|${round1(it.fromG)}|${round1(it.toG)}") }
                        })
                    })
                }
            })
            if (days.sumOf { it.changes.size } > MAX_LISTED_CHANGES) put("changesTruncated", true)
        }
    }

    data class Result(val response: NutritionPlanContract.Response, val computation: Computation)

    /** Tutto cio' che il gate di [NutritionPlanContract.validateBusinessAssessed] richiede oltre ai target. */
    data class Context(
        val monday: java.time.LocalDate,
        val generationStart: java.time.LocalDate,
        val periodWeeks: Int,
        val targets: NutritionBusinessValidator.Targets,
        val dailyTargets: Map<Long, NutritionBusinessValidator.Targets>,
        val dietaryProfile: DietaryProfile,
        val sportsMode: SportsNutritionClassifier.Mode,
        val mealsPerDay: Int,
        val maintenanceCeilingKcal: Double?,
    )

    class Accepted(
        val response: NutritionPlanContract.Response,
        val assessment: NutritionPlanAssessment.Assessment,
        val computation: Computation,
    )

    /**
     * Un tentativo di generazione (corpo del businessValidator): busta MFP2 -> proposta -> piano calcolato -> gate
     * unico con la tolleranza del tentativo (+-3% al primo, +-4% ai retry). Qualunque rifiuto e' un'eccezione con un
     * codice stabile, che il runtime trasforma nel messaggio di retry.
     */
    fun evaluate(json: String, context: Context, attempt: Int): Accepted {
        val tolerance = NutritionPlanAssessment.toleranceForAttempt(attempt)
        val parsed = try {
            FoodCatalogPlanContract.parseEnvelope(json, catalog, context.mealsPerDay)
        } catch (e: IllegalArgumentException) {
            throw withSimilarFoodIds(e, context)
        }
        // W e' un dato che l'app conosce gia' (lunedi' della richiesta): il valore scritto dal modello e' solo
        // informativo e viene sostituito, cosi' un numero riscritto male non fa fallire un piano valido. Le date dei
        // giorni (D) restano validate dal gate.
        val proposal = parsed.copy(weekStartEpochDay = context.monday.toEpochDay())
        val built = build(
            proposal,
            Params(
                targets = context.targets,
                dailyTargets = context.dailyTargets,
                dietaryProfile = context.dietaryProfile,
                tolerance = tolerance,
                attempt = attempt,
            ),
        )
        val assessment = NutritionPlanContract.validateBusinessAssessed(
            response = built.response,
            expectedWeekStart = context.monday,
            targets = context.targets,
            sportsMode = context.sportsMode,
            enforceWeeklyVariety = false,
            mealsPerDay = context.mealsPerDay,
            dailyTargets = context.dailyTargets,
            dietaryProfile = context.dietaryProfile,
            tolerance = tolerance,
            targetBelowOnly = false,
            expectedFirstDate = context.generationStart,
            expectedPeriodWeeks = context.periodWeeks,
            maintenanceCeilingKcal = context.maintenanceCeilingKcal,
            attempt = attempt,
        ).getOrThrow()
        return Accepted(built.response, assessment, built.computation)
    }

    /**
     * `PIPE_I_FOOD_UNKNOWN:id1,id2` -> `PIPE_I_FOOD_UNKNOWN:id1>sim1,sim2,sim3;id2>...` con i foodId del CAT (stessi
     * vincoli e mese del prompt) piu' simili, in modo che il retry possa correggere l'id. Altri errori invariati.
     */
    private fun withSimilarFoodIds(error: IllegalArgumentException, context: Context): IllegalArgumentException {
        val message = error.message.orEmpty()
        if (!message.startsWith(UNKNOWN_FOOD_PREFIX)) return error
        val ids = message.removePrefix(UNKNOWN_FOOD_PREFIX).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val candidates = catalog.subsetFor(
            CatalogConstraints(profile = context.dietaryProfile, month = context.generationStart.monthValue),
        ).map { it.id }
        return IllegalArgumentException(unknownFoodReason(ids, candidates))
    }

    /**
     * Costruisce il piano calcolato. Lancia [Rejection] (codici stabili) se un foodId viola i vincoli del profilo o se
     * le kcal di un giorno non sono raggiungibili con gli alimenti scelti (motivo con l'intervallo raggiungibile).
     */
    fun build(proposal: FoodCatalogPlanContract.Proposal, params: Params): Result {
        val allowedIds = catalog.subsetFor(
            CatalogConstraints(profile = params.dietaryProfile, kinds = FoodKind.entries.toSet()),
        ).map { it.id }.toSet()
        val violating = proposal.days.flatMap { day ->
            day.meals.flatMap { meal -> meal.items.map { it.foodId } } + day.supplements.mapNotNull { it.foodId }
        }.filter { it !in allowedIds }.map { FoodCatalogPlanContract.safeId(it) }.distinct()
        if (violating.isNotEmpty()) throw Rejection("FOOD_CONSTRAINT_VIOLATION:${violating.take(MAX_LISTED_IDS).joinToString(",")}")

        val computed = proposal.days.sortedBy { it.dateEpochDay }.map { day -> computeDay(day, params) }
        val failing = computed.filter { it.day.status != RepairStatus.REPAIRED || it.verdictBlocking }
        if (failing.isNotEmpty()) throw Rejection(toleranceReason(failing.map { it.day }))

        val warnings = computed.flatMap { it.warnings }
        val response = NutritionPlanContract.Response(
            weekStartEpochDay = proposal.weekStartEpochDay,
            days = computed.map { it.generated },
            agentValidation = proposal.agentValidation,
        )
        return Result(
            response,
            Computation(catalog.version, params.attempt, params.tolerance, computed.map { it.day }, warnings, computed.sumOf { it.titlesReplaced }),
        )
    }

    private class ComputedDay(
        val day: DayComputation,
        val generated: NutritionPlanContract.GeneratedDay,
        val verdictBlocking: Boolean,
        val warnings: List<NutritionValidationIssue>,
        val titlesReplaced: Int = 0,
    )

    private fun computeDay(day: FoodCatalogPlanContract.Day, params: Params): ComputedDay {
        val target = params.dailyTargets[day.dateEpochDay] ?: params.targets
        if (target.kcal <= 0.0) throw Rejection("CALORIE_TARGET_INVALID")
        val dayTargets = DayTargets(target.kcal, target.proteinG, target.carbsG, target.fatG)

        val mealInputs = day.meals.mapIndexed { index, meal ->
            RepairMeal("M$index", meal.items.map { ingredientInput(entry(it.foodId), it.grams) })
        }
        val supplementItems = day.supplements.filter { it.foodId != null }
        val repairMeals = if (supplementItems.isEmpty()) mealInputs else
            mealInputs + RepairMeal("S", supplementItems.map { ingredientInput(entry(it.foodId!!), it.grams, fixed = true) })

        val proposed = repairMeals.fold(NutrientTotals.ZERO) { acc, m -> acc + NutritionCalculator.meal(m.ingredients) }
        val repair = DayRepair.repair(repairMeals, dayTargets, params.limits, params.tolerance)

        // Grammi persistiti a 1 decimale: i macro si calcolano da quelli, cosi' sono verificabili a mano.
        val finalGrams = repair.mealGrams.map { meal -> meal.map { Math.round(it * 10.0) / 10.0 } }
        var totals = NutrientTotals.ZERO
        val changes = mutableListOf<Change>()
        var titlesReplaced = 0
        val generatedMeals = day.meals.mapIndexed { mi, meal ->
            val ingredients = meal.items.mapIndexed { ii, item ->
                val food = entry(item.foodId)
                val grams = finalGrams[mi][ii]
                if (abs(grams - item.grams) > CHANGE_EPSILON_G) changes += Change(mi, item.foodId, item.grams, grams)
                generatedIngredient(food, grams)
            }
            val mealTotals = ingredients.fold(NutrientTotals.ZERO) { acc, i ->
                acc + NutrientTotals(i.kcal!!.toDouble(), i.proteinG!!.toDouble(), i.carbsG!!.toDouble(), i.fatG!!.toDouble(), i.fiberG!!.toDouble(), i.sodiumMg!!.toDouble())
            }
            totals += mealTotals
            val title = coherentTitle(meal.title, meal.items.map { entry(it.foodId) }, finalGrams[mi])
            if (title != meal.title) titlesReplaced++
            NutritionPlanContract.GeneratedMeal(
                type = meal.type,
                title = title,
                timeMinutes = meal.timeMinutes,
                kcal = Math.round(mealTotals.kcal).toInt(),
                proteinG = round1(mealTotals.proteinG).toFloat(),
                carbsG = round1(mealTotals.carbsG).toFloat(),
                fatG = round1(mealTotals.fatG).toFloat(),
                preparation = meal.preparation,
                ingredients = ingredients,
                nutritionSource = FoodMeal.NUTRITION_SOURCE_COMPUTED,
            )
        }
        var supplementRow = 0
        val supplements = day.supplements.map { supplement ->
            val food = supplement.foodId?.let(::entry)
            if (food == null) {
                NutritionPlanContract.GeneratedSupplement(supplement.kind, CREATINE_NAME, supplement.grams.toFloat(), "g", supplement.timeMinutes, 0, 0f, 0f, 0f, supplement.notes)
            } else {
                val grams = finalGrams.last()[supplementRow]
                supplementRow++
                val t = NutritionCalculator.ingredient(calcPer100(food), grams)
                totals += t
                NutritionPlanContract.GeneratedSupplement(
                    kind = supplement.kind,
                    name = food.name,
                    dose = grams.toFloat(),
                    unit = "g",
                    timeMinutes = supplement.timeMinutes,
                    kcal = Math.round(t.kcal).toInt(),
                    proteinG = round1(t.proteinG).toFloat(),
                    carbsG = round1(t.carbsG).toFloat(),
                    fatG = round1(t.fatG).toFloat(),
                    notes = supplement.notes,
                )
            }
        }
        val generated = NutritionPlanContract.GeneratedDay(
            dateEpochDay = day.dateEpochDay,
            totalKcal = generatedMeals.sumOf { it.kcal } + supplements.sumOf { it.kcal },
            proteinG = (generatedMeals.sumOf { it.proteinG.toDouble() } + supplements.sumOf { it.proteinG.toDouble() }).toFloat(),
            carbsG = (generatedMeals.sumOf { it.carbsG.toDouble() } + supplements.sumOf { it.carbsG.toDouble() }).toFloat(),
            fatG = (generatedMeals.sumOf { it.fatG.toDouble() } + supplements.sumOf { it.fatG.toDouble() }).toFloat(),
            meals = generatedMeals,
            supplements = supplements,
            hydrationNote = day.hydrationNote,
        )

        val verdict = ValidationPolicy.evaluate(totals, dayTargets, params.tolerance)
        val warnings = verdict.warnings
            .filter { it.macro == ValidationPolicy.Macro.PROTEIN && it.deviation < 0.0 }
            .map {
                NutritionValidationIssue(
                    code = PROTEIN_BELOW_TARGET,
                    severity = Severity.WARNING,
                    message = "Proteine calcolate sotto il target oltre la tolleranza: obiettivo di riparazione, non bloccante",
                    dayEpochDay = day.dateEpochDay,
                    expected = it.targetG,
                    actual = it.calculatedG,
                    deviationPercent = it.deviation * 100.0,
                )
            }
        return ComputedDay(
            day = DayComputation(
                dateEpochDay = day.dateEpochDay,
                status = repair.status,
                targetKcal = target.kcal,
                proposedKcal = proposed.kcal,
                finalKcal = totals.kcal,
                targetProteinG = target.proteinG,
                proposedProteinG = proposed.proteinG,
                finalProteinG = totals.proteinG,
                kcalBand = repair.kcalBand,
                proteinBand = repair.proteinBand,
                sweeps = repair.iterations,
                reachableMinKcal = repair.reachableKcal.minKcal,
                reachableMaxKcal = repair.reachableKcal.maxKcal,
                changes = changes,
            ),
            generated = generated,
            verdictBlocking = verdict.isBlocking,
            warnings = warnings,
            titlesReplaced = titlesReplaced,
        )
    }

    /**
     * Titolo coerente con gli ingredienti. Se il titolo del modello cita un alimento del catalogo (nome o alias) che
     * NON e' tra gli ingredienti del pasto, resta il titolo scritto dall'app con i 2-3 ingredienti principali per kcal
     * ([generatedTitle]); altrimenti resta quello del modello. Un alimento citato e' presente se l'ingrediente e' la
     * stessa voce, la sua coppia crudo/cotto, oppure una voce piu' specifica (nome/alias che contiene quello citato:
     * "riso" con riso integrale). Nessun rifiuto e nessun retry.
     */
    internal fun coherentTitle(title: String, foods: List<FoodEntry>, grams: List<Double>): String {
        val present = foods.flatMap { listOfNotNull(it.id, it.pairId) }.toSet()
        val presentKeys = foods.flatMap { catalog.keysOf(it) }.map { " $it " }
        val mismatch = catalog.mentionsIn(title).any { mention ->
            mention.entry.id !in present && presentKeys.none { it.contains(" ${mention.key} ") }
        }
        if (!mismatch) return title
        return generatedTitle(foods, grams) ?: title
    }

    /** "Salmone, patate e zucca": fino a 3 nomi di catalogo (senza stato, condimenti/spezie/integratori esclusi) per kcal. */
    private fun generatedTitle(foods: List<FoodEntry>, grams: List<Double>): String? {
        val rows = foods.mapIndexed { i, food ->
            Triple(food, titleName(food), NutritionCalculator.ingredient(calcPer100(food), grams[i]).kcal)
        }
        val main = rows.filterNot { isCondimentLike(it.first) }.ifEmpty { rows }
        val names = main.groupBy { it.second.lowercase() }.values
            .map { group -> group.first().second to group.sumOf { it.third } }
            .sortedByDescending { it.second }
            .take(MAX_TITLE_FOODS)
            .map { it.first }
        if (names.isEmpty()) return null
        val lowered = names.mapIndexed { i, n -> if (i == 0) n.replaceFirstChar { it.uppercase() } else n.replaceFirstChar { it.lowercase() } }
        return if (lowered.size == 1) lowered[0] else lowered.dropLast(1).joinToString(", ") + " e " + lowered.last()
    }

    private fun entry(id: String): FoodEntry = catalog.byId(id) ?: throw Rejection("PIPE_I_FOOD_UNKNOWN:${FoodCatalogPlanContract.safeId(id)}")

    private fun calcPer100(food: FoodEntry) = CalcPer100(
        kcal = food.per100.kcal,
        proteinG = food.per100.protein,
        carbsG = food.per100.carbs,
        fatG = food.per100.fat,
        fiberG = food.per100.fiber ?: 0.0,
        sodiumMg = food.per100.sodiumMg ?: 0.0,
        waterG = food.per100.water ?: 0.0,
    )

    internal fun ingredientInput(food: FoodEntry, grams: Double, fixed: Boolean = false) = IngredientInput(
        foodId = food.id,
        per100 = calcPer100(food),
        grams = grams,
        minG = food.minGrams,
        maxG = food.maxGrams,
        stepG = food.stepGrams,
        scalable = food.scalable && !fixed,
        densityGPerMl = food.density,
    )

    /**
     * Ingrediente persistibile: nome del catalogo (con lo stato crudo/cotto se il nome non lo dice), quantita' in grammi
     * (in ml per i liquidi, cosi' i lettori esistenti restano compatibili), dose pratica dalle sole equivalenze note.
     */
    internal fun generatedIngredient(food: FoodEntry, grams: Double): NutritionPlanContract.GeneratedIngredient {
        val t = NutritionCalculator.ingredient(calcPer100(food), grams)
        val liquid = food.density?.takeIf { it > 0.0 }
        val quantity = if (liquid != null) Math.round(grams / liquid).toFloat() else grams.toFloat()
        return NutritionPlanContract.GeneratedIngredient(
            name = displayName(food),
            quantity = quantity,
            unit = if (liquid != null) "ml" else "g",
            displayDose = displayDose(food, grams),
            weightState = when (food.state) {
                FoodState.RAW -> "crudo"
                FoodState.COOKED -> "cotto"
                else -> ""
            },
            nutritionConfidence = food.confidence.name,
            category = food.shopCategory,
            foodId = food.id,
            grams = grams.toFloat(),
            kcal = t.kcal.toFloat(),
            proteinG = t.proteinG.toFloat(),
            carbsG = t.carbsG.toFloat(),
            fatG = t.fatG.toFloat(),
            fiberG = t.fiberG.toFloat(),
            sodiumMg = t.sodiumMg.toFloat(),
        )
    }

    /**
     * Pasto calcolato dai SOLI grammi (nessuna riparazione): stessa aritmetica di [build] (ingrediente = per100 x grammi,
     * pasto = somma degli ingredienti gia' calcolati). Serve a ricostruire in modo deterministico un pasto gia'
     * accettato (alternativa MA2 al momento dell'applicazione), partendo da (alimento, grammi).
     */
    internal fun computedMeal(
        type: String,
        title: String,
        timeMinutes: Int,
        preparation: String,
        items: List<Pair<FoodEntry, Double>>,
    ): NutritionPlanContract.GeneratedMeal {
        val ingredients = items.map { (food, grams) -> generatedIngredient(food, Math.round(grams * 10.0) / 10.0) }
        val totals = ingredients.fold(NutrientTotals.ZERO) { acc, i ->
            acc + NutrientTotals(i.kcal!!.toDouble(), i.proteinG!!.toDouble(), i.carbsG!!.toDouble(), i.fatG!!.toDouble(), i.fiberG!!.toDouble(), i.sodiumMg!!.toDouble())
        }
        return NutritionPlanContract.GeneratedMeal(
            type = type,
            title = title,
            timeMinutes = timeMinutes,
            kcal = Math.round(totals.kcal).toInt(),
            proteinG = round1(totals.proteinG).toFloat(),
            carbsG = round1(totals.carbsG).toFloat(),
            fatG = round1(totals.fatG).toFloat(),
            preparation = preparation,
            ingredients = ingredients,
            nutritionSource = FoodMeal.NUTRITION_SOURCE_COMPUTED,
        )
    }

    private fun displayDose(food: FoodEntry, grams: Double): String {
        val equivalents = food.units.map { UnitEquivalent(it.name, it.grams, PLURALS[it.name] ?: it.name) }
        val dose = DoseFormatter.format(grams, equivalents)
        val liquid = food.density?.takeIf { it > 0.0 }
        return if (liquid != null && dose.endsWith(" g")) "${Math.round(grams / liquid)} ml" else dose
    }

    companion object {
        const val PROTEIN_BELOW_TARGET = "PROTEIN_BELOW_TARGET"
        const val MAX_LISTED_IDS = 5
        const val MAX_DETAIL_CHARS = 120
        internal const val MAX_LOGGED_ATTEMPTS = 5
        private const val MAX_TITLE_FOODS = 3
        private const val MAX_CODE_CHARS = 60
        private val CODE_PATTERN = Regex("^([A-Z][A-Z0-9_]*)(?::(.*))?$")
        private val PARENTHESES = Regex("\\s*\\([^)]*\\)")
        private val TRAILING_STATE = Regex("\\s+(cott[oaie]|crud[oaie])$", RegexOption.IGNORE_CASE)
        internal const val MAX_LISTED_CHANGES = 300
        private const val CHANGE_EPSILON_G = 0.05
        private const val CREATINE_NAME = "Creatina monoidrato"
        private val PLURALS = mapOf(
            "cucchiaio" to "cucchiai", "cucchiaino" to "cucchiaini", "bicchiere" to "bicchieri",
            "fetta" to "fette", "pezzo" to "pezzi", "uovo" to "uova",
        )
        private val STATE_RAW = Regex("crud", RegexOption.IGNORE_CASE)
        private val STATE_COOKED = Regex("cott|bollit|lessat|arrost|grigliat|al forno|sodo", RegexOption.IGNORE_CASE)

        /** Nome del catalogo per un titolo: senza parentesi, senza alternativa dopo " / " e senza lo stato crudo/cotto. */
        internal fun titleName(food: FoodEntry): String =
            food.name.replace(PARENTHESES, "").substringBefore(" / ").trim().replace(TRAILING_STATE, "").trim().ifEmpty { food.name }

        /** Condimenti, spezie, sale, integratori e oli/burro: fuori dal titolo generato. */
        internal fun isCondimentLike(food: FoodEntry): Boolean =
            "SEASONING" in food.roles || "SUPPLEMENT" in food.roles ||
                ("FAT" in food.roles && (food.density != null || food.id == "it.burro"))

        /**
         * Rifiuto di un tentativo in forma registrabile: il codice e' la parte iniziale `MAIUSCOLE_E_UNDERSCORE` del
         * messaggio (i rifiuti dell'app sono `CODICE` o `CODICE:dettaglio`), il dettaglio e' troncato a
         * [MAX_DETAIL_CHARS]. Se il messaggio non ha quella forma (per esempio un'eccezione di parsing JSON, che
         * potrebbe citare il testo del modello) si registra solo `UNCLASSIFIED`, senza dettaglio.
         */
        fun rejectionOf(attempt: Int, error: Throwable): AttemptRejection {
            val match = CODE_PATTERN.matchEntire(error.message.orEmpty().lineSequence().firstOrNull().orEmpty().trim())
                ?: return AttemptRejection(attempt + 1, "UNCLASSIFIED", "")
            val detail = match.groupValues[2].replace(Regex("\\s+"), " ").trim().take(MAX_DETAIL_CHARS)
            return AttemptRejection(attempt + 1, match.groupValues[1].take(MAX_CODE_CHARS), detail)
        }

        private const val UNKNOWN_FOOD_PREFIX = "PIPE_I_FOOD_UNKNOWN:"
        private const val MAX_SIMILAR_IDS = 3
        private const val MIN_TOKEN_CHARS = 3
        private const val MIN_COMMON_PREFIX = 5

        private val STATE_TOKEN = Regex("^(cott|crud|bollit|lessat|arrost|grigliat|secch|fresc)[oaie]$|^(scatola|forno|vapore)$")

        private fun idTokens(id: String): List<String> =
            id.removePrefix("it.").lowercase().split('_', '.', '-')
                .filter { it.length >= MIN_TOKEN_CHARS && !STATE_TOKEN.matches(it) }

        private fun tokenScore(a: String, b: String): Int = when {
            a == b -> 2
            a.commonPrefixWith(b).length >= MIN_COMMON_PREFIX -> 1
            else -> 0
        }

        /**
         * Fino a [MAX_SIMILAR_IDS] id di [candidates] simili a [unknown], deterministici: punteggio per token condivisi
         * (uguale = 2, prefisso comune di almeno 5 caratteri = 1; i token di stato crudo/cotto/secco non contano), poi
         * meno token in piu', poi ordine alfabetico.
         */
        internal fun similarFoodIds(unknown: String, candidates: Collection<String>): List<String> {
            val wanted = idTokens(unknown)
            if (wanted.isEmpty()) return emptyList()
            return candidates.asSequence().map { id ->
                val tokens = idTokens(id)
                val score = wanted.sumOf { w -> tokens.maxOfOrNull { tokenScore(w, it) } ?: 0 }
                Triple(id, score, tokens.size)
            }.filter { it.second > 0 }
                .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third }.thenBy { it.first })
                .take(MAX_SIMILAR_IDS).map { it.first }.toList()
        }

        /** Motivo di retry per foodId sconosciuti, con suggerimenti dal CAT; al massimo [NutritionPlanAssessment.MAX_REASON_CHARS]. */
        internal fun unknownFoodReason(ids: List<String>, candidates: Collection<String>): String {
            val entries = ids.map { id ->
                val similar = similarFoodIds(id, candidates)
                if (similar.isEmpty()) id else "$id>${similar.joinToString(",")}"
            }
            val out = StringBuilder(UNKNOWN_FOOD_PREFIX)
            entries.forEachIndexed { index, entry ->
                val piece = if (index == 0) entry else ";$entry"
                if (index > 0 && out.length + piece.length > NutritionPlanAssessment.MAX_REASON_CHARS) return@forEachIndexed
                out.append(piece)
            }
            return out.toString().take(NutritionPlanAssessment.MAX_REASON_CHARS)
        }

        /** Tag logcat della diagnostica MFP2 (L6d). */
        const val LOG_TAG = "MyFitAiMfp2"

        /**
         * Riga di diagnostica per un tentativo rifiutato: numero tentativo (1-based), codice stabile e dettaglio breve
         * (<= [MAX_DETAIL_CHARS], solo numeri/identificatori scritti dall'app). Mai testo del modello ne' dati personali.
         */
        fun logLine(rejection: AttemptRejection): String =
            "rejected attempt=${rejection.attempt} code=${rejection.code} detail=${rejection.detail.take(MAX_DETAIL_CHARS)}"

        /** Sintesi persistibile dell'ultimo fallimento di una generazione (tentativo, codice, dettaglio), <= 160 caratteri. */
        fun failureSummary(rejection: AttemptRejection): String =
            "attempt=${rejection.attempt};code=${rejection.code};detail=${rejection.detail}".take(160)

        fun displayName(food: FoodEntry): String = when {
            food.state == FoodState.RAW && !STATE_RAW.containsMatchIn(food.name) -> "${food.name} (crudo)"
            food.state == FoodState.COOKED && !STATE_COOKED.containsMatchIn(food.name) -> "${food.name} (cotto)"
            else -> food.name
        }

        /**
         * Motivo di retry nel formato di [NutritionPlanAssessment.toleranceExceededReason] (stesso codice, cosi' il
         * messaggio ai retry mantiene il limite di 320 caratteri): `target` e' il target del giorno, `actual` le kcal
         * della proposta del modello, `reach` l'intervallo kcal raggiungibile riscalando i grammi con gli alimenti scelti.
         */
        fun toleranceReason(days: List<DayComputation>): String {
            val bad = days.sortedBy { it.dateEpochDay }
            val shown = bad.take(NutritionPlanAssessment.MAX_FIX_DAYS).joinToString("|") {
                "${it.dateEpochDay}:target=${it.targetKcal.toInt()};actual=${it.proposedKcal.toInt()};reach=${Math.round(it.reachableMinKcal)}-${Math.round(it.reachableMaxKcal)}"
            }
            val hidden = bad.size - NutritionPlanAssessment.MAX_FIX_DAYS
            return "${NutritionPlanAssessment.TOLERANCE_EXCEEDED_CODE}:$shown" + if (hidden > 0) "|+$hidden" else ""
        }

        internal fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    }
}
