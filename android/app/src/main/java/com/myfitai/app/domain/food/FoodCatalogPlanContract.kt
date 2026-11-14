package com.myfitai.app.domain.food

import com.myfitai.app.domain.food.catalog.FoodCatalog
import org.json.JSONObject

/**
 * Protocollo MFP2 (lotto MACRO L3): il modello sceglie SOLO alimenti e grammi; kcal, macro, totali e dosi sono
 * calcolati dall'app dal catalogo alimenti (vedi [FoodCatalogPlanBuilder]). Vive accanto a
 * [NutritionPlanCompactContract] (MFP1), che resta invariato.
 *
 * Il trasporto e' lo stesso di MFP1: una busta JSON `{"data": "<record pipe>"}`. Ogni rifiuto e' un codice stabile
 * (mai testo del modello), pensato per il messaggio di retry; un `foodId` eventualmente citato e' ripulito.
 */
object FoodCatalogPlanContract {
    const val SCHEMA_NAME = "myfitai_weekly_nutrition_pipe_v2"
    const val HEADER = "MFP2"

    /** Il modello restituisce lo stesso involucro di MFP1: cambia solo il protocollo dentro `data`. */
    val schemaJson: String = NutritionPlanCompactContract.schemaJson

    /** Foodid usato dall'integratore CREATINE: non e' nel catalogo e non apporta nutrienti. */
    const val NO_FOOD = "-"

    const val PROTOCOL = """MFP2
W|weekStartEpochDay
D|dateEpochDay (one record for each requested date, followed by exactly MEALS_PER_DAY M records; the period can contain 7, 14 or 28 dates)
M|type|title|timeMinutes|preparation
I|foodId|grams (at least one I record for each M record; foodId copied exactly from the CAT list; grams = positive number with a dot, no unit)
S|kind|foodId|grams|timeMinutes|notes (PROTEIN_POWDER with a CAT foodId, or CREATINE with foodId -)
H|hydrationNote
V|1_or_0|notes
No kcal, protein, carbs, fat, totals, displayDose, unit or weightState anywhere: the app calculates them from the catalog.
${NutritionPlanCompactContract.TITLE_RULE}"""

    data class Item(val foodId: String, val grams: Double)

    data class Meal(
        val type: String,
        val title: String,
        val timeMinutes: Int,
        val preparation: String,
        val items: List<Item>,
    )

    /** [foodId] e' null per la creatina (record `S|CREATINE|-|...`). */
    data class Supplement(
        val kind: String,
        val foodId: String?,
        val grams: Double,
        val timeMinutes: Int,
        val notes: String,
    )

    data class Day(
        val dateEpochDay: Long,
        val meals: List<Meal>,
        val supplements: List<Supplement>,
        val hydrationNote: String,
    )

    /** Proposta del modello gia' strutturata: nessun numero nutrizionale, tutti i foodId esistono nel catalogo. */
    data class Proposal(
        val weekStartEpochDay: Long,
        val days: List<Day>,
        val agentValidation: NutritionPlanContract.AgentValidation,
    )

    /**
     * Una preparazione non puo' contenere quantita' (cifra + unita' di misura o di dose): le dosi sono dell'app.
     * Tempi ("10 minuti") e temperature ("180 gradi") restano ammessi.
     */
    private val PREPARATION_QUANTITY = Regex(
        "(\\d+(?:[.,]\\d+)?|\\d+/\\d+)\\s*(?:kg|g|gr|grammi|grammo|mg|ml|cl|dl|l|litri|litro|cucchiai|cucchiaio|cucchiaini|cucchiaino|tazze|tazza|tazzine|tazzina|bicchieri|bicchiere|fette|fetta|pezzi|pezzo|spicchi|spicchio|pizzichi|pizzico|uova|uovo|bustine|bustina|vasetti|vasetto|manciata|manciate|kcal|calorie)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )

    /** True se il testo contiene una quantita' numerica con unita' (es. "200 g", "2 cucchiai", "1/2 bicchiere"). */
    fun hasQuantity(text: String): Boolean = PREPARATION_QUANTITY.containsMatchIn(text)

    private val STRICT_NUMBER = Regex("[0-9]+(?:\\.[0-9]+)?")
    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,60}")
    private const val MAX_LISTED_IDS = 5

    fun parseEnvelope(jsonText: String, catalog: FoodCatalog, mealsPerDay: Int = NutritionPlanContract.REQUIRED_MEALS_PER_DAY): Proposal =
        parsePayload(unwrapPayload(JSONObject(jsonText).getString("data")), catalog, mealsPerDay)

    private fun unwrapPayload(payload: String, depth: Int = 0): String {
        val candidate = payload.trim()
        if (!candidate.startsWith("{") || depth >= 2) return payload
        return runCatching { unwrapPayload(JSONObject(candidate).getString("data"), depth + 1) }.getOrElse { payload }
    }

    /** Id citabile in un motivo di rifiuto: solo caratteri di un id di catalogo, altrimenti "?". */
    fun safeId(raw: String): String = raw.trim().takeIf { SAFE_ID.matches(it) } ?: "?"

    fun parsePayload(payload: String, catalog: FoodCatalog, mealsPerDay: Int = NutritionPlanContract.REQUIRED_MEALS_PER_DAY): Proposal {
        var weekStart: Long? = null
        var validation: NutritionPlanContract.AgentValidation? = null
        val days = mutableListOf<Day>()
        val unknownIds = linkedSetOf<String>()

        var dayDate: Long? = null
        var dayMeals = mutableListOf<Meal>()
        var daySupplements = mutableListOf<Supplement>()
        var dayHydration: String? = null
        var meal: MealBuilder? = null

        fun flushMeal() {
            val current = meal ?: return
            require(current.items.isNotEmpty()) { "PIPE_MEAL_WITHOUT_INGREDIENTS" }
            check(dayDate != null) { "PIPE_MEAL_WITHOUT_DAY" }
            dayMeals.add(Meal(current.type, current.title, current.timeMinutes, current.preparation, current.items.toList()))
            meal = null
        }

        fun flushDay() {
            flushMeal()
            val date = dayDate ?: return
            require(dayMeals.size == mealsPerDay) { "PIPE_DAY_MUST_HAVE_${mealsPerDay}_MEALS" }
            require(days.none { it.dateEpochDay == date }) { "PIPE_D_DUPLICATE" }
            days += Day(date, dayMeals.toList(), daySupplements.toList(), dayHydration.orEmpty())
            dayDate = null
            dayMeals = mutableListOf()
            daySupplements = mutableListOf()
            dayHydration = null
        }

        fun foodId(raw: String, code: String): String {
            val id = raw.trim()
            require(id.isNotEmpty()) { code }
            if (catalog.byId(id) == null) unknownIds += safeId(id)
            return id
        }

        val lines = payload.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.isNotEmpty()) { "PIPE_EMPTY" }
        require(lines.first() == HEADER) { "PIPE_VERSION_INVALID" }

        lines.drop(1).forEach { line ->
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "W" -> {
                    require(parts.size == 2 && weekStart == null && days.isEmpty() && dayDate == null) { "PIPE_W_INVALID" }
                    weekStart = parts[1].toLongStrict("PIPE_W_INVALID")
                }
                "D" -> {
                    require(parts.size == 2 && weekStart != null) { "PIPE_D_INVALID" }
                    flushDay()
                    dayDate = parts[1].toLongStrict("PIPE_D_DATE_INVALID")
                }
                "M" -> {
                    require(parts.size >= 5 && dayDate != null) { "PIPE_M_INVALID" }
                    flushMeal()
                    val preparation = parts.drop(4).joinToString("|").trim()
                    require(!hasQuantity(preparation)) { "PIPE_M_PREPARATION_HAS_QUANTITY" }
                    meal = MealBuilder(
                        type = parts[1].requiredText("PIPE_M_TYPE_INVALID"),
                        title = parts[2].requiredText("PIPE_M_TITLE_INVALID"),
                        timeMinutes = parts[3].toIntStrict("PIPE_M_TIME_INVALID"),
                        preparation = preparation,
                    )
                }
                "I" -> {
                    require(parts.size == 3 && meal != null) { "PIPE_I_INVALID" }
                    val id = foodId(parts[1], "PIPE_I_FOOD_MISSING")
                    meal!!.items += Item(id, parts[2].gramsStrict("PIPE_I_GRAMS_INVALID"))
                }
                "S" -> {
                    require(parts.size >= 5 && dayDate != null) { "PIPE_S_INVALID" }
                    flushMeal()
                    val kind = parts[1].requiredText("PIPE_S_KIND_INVALID")
                    val rawId = parts[2].trim()
                    val id: String? = if (rawId == NO_FOOD) {
                        require(kind == "CREATINE") { "PIPE_S_FOOD_REQUIRED" }
                        null
                    } else foodId(rawId, "PIPE_S_FOOD_MISSING")
                    daySupplements.add(Supplement(
                        kind = kind,
                        foodId = id,
                        grams = parts[3].gramsStrict("PIPE_S_GRAMS_INVALID"),
                        timeMinutes = parts[4].toIntStrict("PIPE_S_TIME_INVALID"),
                        notes = parts.drop(5).joinToString("|").trim(),
                    ))
                }
                "H" -> {
                    require(parts.size >= 2 && dayDate != null) { "PIPE_H_INVALID" }
                    flushMeal()
                    require(dayHydration == null) { "PIPE_H_DUPLICATE" }
                    dayHydration = parts.drop(1).joinToString("|").trim()
                }
                "V" -> {
                    require(parts.size >= 3 && weekStart != null) { "PIPE_V_INVALID" }
                    flushDay()
                    val valid = when (parts[1]) {
                        "1" -> true
                        "0" -> false
                        else -> error("PIPE_V_FLAG_INVALID")
                    }
                    validation = NutritionPlanContract.AgentValidation(valid, parts.drop(2).joinToString("|").trim())
                }
                else -> error("PIPE_RECORD_UNKNOWN")
            }
        }
        flushDay()

        require(unknownIds.isEmpty()) { "PIPE_I_FOOD_UNKNOWN:${unknownIds.take(MAX_LISTED_IDS).joinToString(",")}" }
        require(weekStart != null) { "PIPE_WEEK_MISSING" }
        require(days.isNotEmpty()) { "PIPE_DAYS_MISSING" }
        require(validation != null) { "PIPE_VALIDATION_MISSING" }
        return Proposal(weekStart!!, days.toList(), validation!!)
    }

    private class MealBuilder(
        val type: String,
        val title: String,
        val timeMinutes: Int,
        val preparation: String,
        val items: MutableList<Item> = mutableListOf(),
    )

    private fun String.requiredText(code: String): String = trim().also { require(it.isNotEmpty()) { code } }
    private fun String.toIntStrict(code: String): Int = toIntOrNull() ?: kotlin.error(code)
    private fun String.toLongStrict(code: String): Long = toLongOrNull() ?: kotlin.error(code)

    /** Grammi: numero decimale con il punto, finito e > 0. Niente virgola, niente unita', niente esponente. */
    private fun String.gramsStrict(code: String): Double {
        val text = trim()
        require(STRICT_NUMBER.matches(text)) { code }
        val value = text.toDouble()
        require(value.isFinite() && value > 0.0) { code }
        return value
    }
}
