package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import kotlin.math.abs
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

data class NutritionValidationIssue(
    val code: String,
    val severity: Severity,
    val message: String,
    val dayEpochDay: Long? = null,
    val mealTitle: String? = null,
    val expected: Double? = null,
    val actual: Double? = null,
    val deviationPercent: Double? = null,
)

enum class Severity { INFO, WARNING, ERROR }

data class AppNutritionValidation(
    val available: Boolean,
    val valid: Boolean,
    val validatorVersion: String,
    val issues: List<NutritionValidationIssue>,
    val warnings: List<NutritionValidationIssue>,
    val derivedTotals: Boolean = true,
    /** Per-day kcal/macro deviations (v2). Empty when read from a v1 document. */
    val deviations: List<NutritionPlanAssessment.DayAssessment> = emptyList(),
    /** Business attempts used to obtain the plan (v2); null when unknown (v1 document or no assessment). */
    val attempts: Int? = null,
    /** Tolerance values in force (v2); null when unknown. */
    val tolerancePolicy: NutritionPlanAssessment.TolerancePolicy? = null,
) {
    /**
     * Serialises the document persisted in `meal_plan_versions.appValidationJson`. Only numbers, codes and fixed
     * messages: no model text and no personal data. Export/backup carry it as a plain string.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("available", available)
        put("valid", valid)
        put("validatorVersion", validatorVersion)
        put("derivedTotals", derivedTotals)
        put("issues", JSONArray().apply { issues.forEach { put(it.toJson()) } })
        put("warnings", JSONArray().apply { warnings.forEach { put(it.toJson()) } })
        attempts?.let { put("attempts", it) }
        tolerancePolicy?.let { put("tolerancePolicy", it.toJson()) }
        if (deviations.isNotEmpty()) put("deviations", JSONArray().apply { deviations.forEach { put(it.toJson()) } })
    }
}

/**
 * Non-blocking quality check of meal titles (K6): a title must name the dish ("Merluzzo al forno con zucchine"), not
 * only the slot or an adjective ("Colazione leggera"). The check is a CLOSED list of words (slot names, adjectives,
 * connectors) plus a comparison with the meal's own ingredients; it never blocks and never rewrites a title.
 *
 * Declared limit: a legitimate dish name whose words do not appear in the ingredient names (e.g. "Frittata" with
 * "Uova") is listed too. The warning is informative and the list is capped.
 */
object MealTitleQuality {
    const val CODE = "GENERIC_MEAL_TITLE"
    const val MAX_LISTED = 6
    private const val MIN_PREFIX_SHARE = 0.6

    private val SLOT_WORDS = setOf(
        "colazione", "spuntino", "merenda", "pranzo", "cena", "pasto", "mattina", "mattutino", "mattutina",
        "pomeriggio", "pomeridiano", "pomeridiana", "sera", "serale", "mezzogiorno", "prima", "seconda", "primo", "secondo",
        "breakfast", "lunch", "dinner", "snack", "meta", "mid", "pre", "post", "workout", "allenamento",
    )
    private val ADJECTIVES = setOf(
        "leggero", "leggera", "leggeri", "leggere", "equilibrato", "equilibrata", "equilibrati", "equilibrate",
        "bilanciato", "bilanciata", "bilanciati", "bilanciate", "proteico", "proteica", "proteici", "proteiche",
        "sano", "sana", "sani", "sane", "salutare", "completo", "completa", "nutriente", "veloce", "semplice",
        "light", "classico", "classica", "tipico", "tipica", "ricco", "ricca", "energetico", "energetica",
        "saziante", "genuino", "genuina", "fresco", "fresca", "abbondante", "ideale", "base",
    )
    private val CONNECTORS = setOf("di", "del", "della", "dello", "dei", "delle", "e", "ed", "il", "la", "lo", "un", "una", "a", "da", "in", "per", "con", "al", "alla")
    private val GENERIC_WORDS = SLOT_WORDS + ADJECTIVES + CONNECTORS
    private val IGNORED_FOR_MATCH = CONNECTORS + setOf("allo", "alle", "agli", "dal", "dalla", "sul", "sulla", "su")

    private fun tokens(text: String): List<String> =
        java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("yoghurt", "yogurt")
            .split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() }

    /** True when every word of the title is a slot name, an adjective or a connector (or the title has no word). */
    fun isGeneric(title: String): Boolean = tokens(title).all { it in GENERIC_WORDS }

    private fun sameWord(a: String, b: String): Boolean {
        if (a == b) return true
        val shortest = minOf(a.length, b.length)
        if (shortest < 4) return false
        val prefix = a.commonPrefixWith(b).length
        return prefix >= shortest - 1 && prefix >= MIN_PREFIX_SHARE * maxOf(a.length, b.length)
    }

    /** True when no word of the title appears (same word or inflection) in any ingredient name of the meal. */
    fun mentionsNoIngredient(title: String, ingredientNames: List<String>): Boolean {
        if (ingredientNames.isEmpty()) return false
        val titleWords = tokens(title).filter { it !in IGNORED_FOR_MATCH && it !in GENERIC_WORDS && it.length >= 3 }
        val ingredientWords = ingredientNames.flatMap(::tokens).filter { it !in IGNORED_FOR_MATCH && it.length >= 3 }
        return titleWords.none { word -> ingredientWords.any { sameWord(word, it) } }
    }

    fun isWeak(meal: NutritionPlanContract.GeneratedMeal): Boolean =
        isGeneric(meal.title) || mentionsNoIngredient(meal.title, meal.ingredients.map { it.name })

    /** Titles (distinct, in plan order) that are generic or name none of the meal's ingredients. */
    fun weakTitles(response: NutritionPlanContract.Response): List<String> =
        response.days.flatMap { it.meals }.filter(::isWeak).map { it.title.trim() }.distinct()

    /** One non-blocking warning listing the weak titles (at most [MAX_LISTED], then `+N`); null when all are fine. */
    fun warning(response: NutritionPlanContract.Response): NutritionValidationIssue? {
        val titles = weakTitles(response)
        if (titles.isEmpty()) return null
        val shown = titles.take(MAX_LISTED).joinToString("; ")
        val hidden = titles.size - MAX_LISTED
        return NutritionValidationIssue(
            code = CODE,
            severity = Severity.WARNING,
            message = "Titoli dei pasti generici: il titolo dovrebbe nominare il piatto con i suoi ingredienti principali",
            mealTitle = shown + if (hidden > 0) "; +$hidden" else "",
            actual = titles.size.toDouble(),
        )
    }
}

object NutritionIntegrityValidator {
    const val VERSION = "nutrition-integrity-v2"
    private const val KCAL_TOLERANCE_RATIO = 0.15
    private const val KCAL_MIN_TOLERANCE = 50.0
    private const val MACRO_TOLERANCE = 0.2

    fun validate(
        response: NutritionPlanContract.Response,
        targets: NutritionBusinessValidator.Targets,
        mealsPerDay: Int,
        dailyTargets: Map<Long, NutritionBusinessValidator.Targets> = emptyMap(),
        tolerance: Double = NutritionBusinessValidator.DEFAULT_TOLERANCE,
        belowOnly: Boolean = false,
        macroKcalToleranceRatio: Double = KCAL_TOLERANCE_RATIO,
        /** The single evaluation of the final plan; computed here with [tolerance] when the caller has none. */
        assessment: NutritionPlanAssessment.Assessment? = null,
    ): AppNutritionValidation {
        val errors = mutableListOf<NutritionValidationIssue>()
        val warnings = mutableListOf<NutritionValidationIssue>()
        val evaluated = assessment ?: NutritionPlanAssessment.assess(response, targets, dailyTargets, tolerance, belowOnly)
        errors += evaluated.blockers
        warnings += evaluated.warnings
        response.days.forEach { day ->
            if (day.meals.size != mealsPerDay) errors += issue("MEAL_COUNT_INVALID", "Il numero di pasti non coincide con il profilo", dayEpochDay = day.dateEpochDay, expected = mealsPerDay.toDouble(), actual = day.meals.size.toDouble())

            day.meals.forEach { meal -> validateItem(meal.kcal, meal.proteinG, meal.carbsG, meal.fatG, day.dateEpochDay, meal.title, errors, macroKcalToleranceRatio) }
            day.supplements.forEach { supplement -> validateItem(supplement.kcal, supplement.proteinG, supplement.carbsG, supplement.fatG, day.dateEpochDay, supplement.name, errors, macroKcalToleranceRatio) }

            val mealKcal = day.meals.sumOf { it.kcal } + day.supplements.sumOf { it.kcal }
            val mealProtein = day.meals.sumOf { it.proteinG.toDouble() } + day.supplements.sumOf { it.proteinG.toDouble() }
            val mealCarbs = day.meals.sumOf { it.carbsG.toDouble() } + day.supplements.sumOf { it.carbsG.toDouble() }
            val mealFat = day.meals.sumOf { it.fatG.toDouble() } + day.supplements.sumOf { it.fatG.toDouble() }
            if (mealKcal != day.totalKcal) errors += issue("DAY_MEALS_KCAL_MISMATCH", "La somma dei pasti non coincide con il totale giornaliero", day.dateEpochDay, expected = mealKcal.toDouble(), actual = day.totalKcal.toDouble())
            if (abs(mealProtein - day.proteinG) > MACRO_TOLERANCE) warnings += issue("DAY_MEALS_PROTEIN_ESTIMATE_MISMATCH", "La somma delle proteine stimate differisce dal totale dichiarato", day.dateEpochDay, severity = Severity.WARNING, expected = mealProtein, actual = day.proteinG.toDouble())
            if (abs(mealCarbs - day.carbsG) > MACRO_TOLERANCE) warnings += issue("DAY_MEALS_CARBS_ESTIMATE_MISMATCH", "La somma dei carboidrati stimati differisce dal totale dichiarato", day.dateEpochDay, severity = Severity.WARNING, expected = mealCarbs, actual = day.carbsG.toDouble())
            if (abs(mealFat - day.fatG) > MACRO_TOLERANCE) warnings += issue("DAY_MEALS_FAT_ESTIMATE_MISMATCH", "La somma dei grassi stimati differisce dal totale dichiarato", day.dateEpochDay, severity = Severity.WARNING, expected = mealFat, actual = day.fatG.toDouble())
            day.meals.flatMap { it.ingredients }.filter { it.nutritionConfidence.isBlank() || it.nutritionConfidence.equals("UNKNOWN", true) }.forEach {
                warnings += issue("INGREDIENT_NUTRITION_NOT_VERIFIABLE", "Fonte nutrizionale ingrediente non verificabile", day.dateEpochDay)
            }
        }
        return AppNutritionValidation(
            available = true,
            valid = errors.isEmpty(),
            validatorVersion = VERSION,
            issues = errors,
            warnings = warnings + listOfNotNull(MealTitleQuality.warning(response)),
            deviations = evaluated.days,
            attempts = evaluated.attempt + 1,
            tolerancePolicy = evaluated.tolerancePolicy,
        )
    }

    /**
     * Tolerant reader of a persisted appValidation document: accepts v1 (no deviations/attempts/policy) and v2,
     * ignores unknown fields and returns null for blank or malformed text instead of throwing.
     */
    fun parse(text: String?): AppNutritionValidation? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(text)
            AppNutritionValidation(
                available = root.optBoolean("available", true),
                valid = root.optBoolean("valid", true),
                validatorVersion = root.optString("validatorVersion", "unknown"),
                issues = root.optJSONArray("issues").issues(),
                warnings = root.optJSONArray("warnings").issues(),
                derivedTotals = root.optBoolean("derivedTotals", true),
                deviations = root.optJSONArray("deviations").let { array ->
                    if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toDayAssessment() }
                },
                attempts = if (root.has("attempts") && !root.isNull("attempts")) root.optInt("attempts") else null,
                tolerancePolicy = root.optJSONObject("tolerancePolicy")?.toTolerancePolicy(),
            )
        }.getOrNull()
    }

    private fun validateItem(kcal: Int, protein: Float, carbs: Float, fat: Float, day: Long, title: String, errors: MutableList<NutritionValidationIssue>, kcalToleranceRatio: Double = KCAL_TOLERANCE_RATIO) {
        val expected = protein * 4.0 + carbs * 4.0 + fat * 9.0
        val tolerance = max(KCAL_MIN_TOLERANCE, kcal * kcalToleranceRatio)
        if (abs(expected - kcal) > tolerance) errors += issue("MACRO_CALORIE_INCONSISTENCY", "Le calorie dichiarate non sono coerenti con i macro stimati", day, title, severity = Severity.WARNING, expected = expected, actual = kcal.toDouble(), deviationPercent = if (kcal == 0) null else abs(expected - kcal) / kcal * 100.0)
    }

    private fun issue(code: String, message: String, dayEpochDay: Long? = null, mealTitle: String? = null, severity: Severity = Severity.ERROR, expected: Double? = null, actual: Double? = null, deviationPercent: Double? = null) = NutritionValidationIssue(code, severity, message, dayEpochDay, mealTitle, expected, actual, deviationPercent)
}

private fun NutritionValidationIssue.toJson() = JSONObject().apply {
    put("code", code); put("severity", severity.name); put("message", message)
    put("dayEpochDay", dayEpochDay ?: JSONObject.NULL); put("mealTitle", mealTitle ?: JSONObject.NULL)
    put("expected", expected ?: JSONObject.NULL); put("actual", actual ?: JSONObject.NULL); put("deviationPercent", deviationPercent ?: JSONObject.NULL)
}

private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

private fun NutritionPlanAssessment.TolerancePolicy.toJson() = JSONObject().apply {
    put("referencePercent", referencePercent); put("retryPercent", retryPercent); put("appliedPercent", appliedPercent)
    put("centeringWarningPercent", centeringWarningPercent); put("proteinMinPercent", proteinMinPercent); put("macroBandPercent", macroBandPercent)
}

private fun JSONObject.toTolerancePolicy() = NutritionPlanAssessment.TolerancePolicy(
    referencePercent = optDouble("referencePercent", NutritionPlanAssessment.REFERENCE_TOLERANCE * 100.0),
    retryPercent = optDouble("retryPercent", NutritionPlanAssessment.RETRY_TOLERANCE * 100.0),
    appliedPercent = optDouble("appliedPercent", NutritionPlanAssessment.REFERENCE_TOLERANCE * 100.0),
    centeringWarningPercent = optDouble("centeringWarningPercent", NutritionPlanAssessment.CENTERING_WARNING_MEAN * 100.0),
    proteinMinPercent = optDouble("proteinMinPercent", NutritionPlanAssessment.PROTEIN_MIN_RATIO * 100.0),
    macroBandPercent = optDouble("macroBandPercent", NutritionPlanAssessment.MACRO_BAND * 100.0),
)

private fun metric(target: Double, actual: Double, deviation: Double) = JSONObject().apply {
    put("target", round1(target)); put("actual", round1(actual)); put("devPercent", round1(deviation * 100.0))
}

private fun NutritionPlanAssessment.DayAssessment.toJson() = JSONObject().apply {
    put("day", dateEpochDay)
    put("class", classification.name)
    put("blocking", blocking)
    put("kcal", metric(targetKcal, actualKcal, kcalDeviation))
    put("protein", metric(targetProteinG, actualProteinG, proteinDeviation))
    put("carbs", metric(targetCarbsG, actualCarbsG, carbsDeviation))
    put("fat", metric(targetFatG, actualFatG, fatDeviation))
}

private fun JSONObject.toDayAssessment(): NutritionPlanAssessment.DayAssessment {
    fun field(name: String): Triple<Double, Double, Double> {
        val metric = optJSONObject(name)
        return Triple(
            metric?.optDouble("target", 0.0) ?: 0.0,
            metric?.optDouble("actual", 0.0) ?: 0.0,
            (metric?.optDouble("devPercent", 0.0) ?: 0.0) / 100.0,
        )
    }
    val kcal = field("kcal"); val protein = field("protein"); val carbs = field("carbs"); val fat = field("fat")
    return NutritionPlanAssessment.DayAssessment(
        dateEpochDay = optLong("day"),
        targetKcal = kcal.first, actualKcal = kcal.second, kcalDeviation = kcal.third,
        targetProteinG = protein.first, actualProteinG = protein.second, proteinDeviation = protein.third,
        targetCarbsG = carbs.first, actualCarbsG = carbs.second, carbsDeviation = carbs.third,
        targetFatG = fat.first, actualFatG = fat.second, fatDeviation = fat.third,
        classification = runCatching { NutritionPlanAssessment.DayClass.valueOf(optString("class")) }.getOrDefault(NutritionPlanAssessment.DayClass.OK),
        blocking = optBoolean("blocking", false),
    )
}

private fun JSONArray?.issues(): List<NutritionValidationIssue> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.toIssue() }

private fun JSONObject.toIssue(): NutritionValidationIssue {
    fun double(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key)
    return NutritionValidationIssue(
        code = optString("code"),
        severity = runCatching { Severity.valueOf(optString("severity")) }.getOrDefault(Severity.WARNING),
        message = optString("message"),
        dayEpochDay = if (!has("dayEpochDay") || isNull("dayEpochDay")) null else optLong("dayEpochDay"),
        mealTitle = if (!has("mealTitle") || isNull("mealTitle")) null else optString("mealTitle"),
        expected = double("expected"),
        actual = double("actual"),
        deviationPercent = double("deviationPercent"),
    )
}