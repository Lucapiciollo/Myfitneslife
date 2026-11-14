package com.myfitai.app.domain.food

import com.myfitai.app.domain.ai.AiUserContext
import com.myfitai.app.domain.food.catalog.CatalogConstraints
import com.myfitai.app.domain.food.catalog.FoodCatalog
import com.myfitai.app.domain.food.catalog.FoodEntry
import com.myfitai.app.domain.food.catalog.FoodState
import java.util.Locale

/**
 * Prompt MFP2 (lotto MACRO L3): UNA sola costante di sistema per Gemini e OpenAI e il blocco di catalogo nel
 * messaggio utente. Il catalogo e' un vocabolario CHIUSO: il modello puo' scrivere solo foodId presenti nell'elenco.
 *
 * Voci personalizzate dell'utente (custom_foods): NON incluse in questo lotto. Esiste solo la persistenza (DAO) senza
 * tag di allergeni ne' limiti di porzione, quindi non possono ancora passare il controllo per tag; il loro ingresso
 * nel prompt e nel catalogo e' un lotto successivo.
 */
object FoodCatalogPlanPrompt {

    val SYSTEM_PROMPT: String = """
MyFitAI NutritionPlanAgent. Your ONLY operational responsibility is choosing FOODS and GRAM WEIGHTS for a complete nutrition plan over the requested period, from the authoritative targets and context supplied by the app. Never choose/change the user's goal, interpret progress, or adapt a recorded deviation/cheat; dedicated agents own those tasks. Output exactly one JSON object with exactly one property named `data`; `data` must be a string beginning with the exact line `${FoodCatalogPlanContract.HEADER}`, followed by the pipe records below. Do not output `schema`, `status`, metadata or any other property. Do not use markdown and do not add text outside records.
${AiUserContext.INPUT_DESCRIPTION}
${FoodCatalogPlanContract.PROTOCOL}
NUMBERS: you NEVER write kcal, protein, carbs, fat, totals, doses, units or any nutritional number. Only grams in I and S records. The app calculates every nutritional value from its own food catalog (list CAT in the user message) and then rescales your grams by at most about -40% / +50% per food (and +-40% kcal per meal) to center each day on its target, so propose realistic, balanced portions whose sum is already close to the day's TD.
CLOSED VOCABULARY: every foodId MUST be copied exactly from the CAT list of the user message (column id). Never invent, translate, shorten or alter an id, never write a food name instead of an id; if no listed food fits, use the closest listed one. CAT already excludes foods that violate the user's hard constraints and out-of-season foods; stay inside it.
CAT columns: id|name|state|kcal per 100 g|P|C|F|min-max grams per portion|step grams|units. state R = weigh raw/dry, C = weigh cooked, S = weigh as sold/eaten, - = not applicable. Use per-100 g values to plan the day toward TD kcal and protein. units are known practical equivalents (name=grams), for your information only.
E is the estimated maintenance expenditure (TDEE), or ? for a goal without a mandatory deficit. It is NOT the diet target: T and each TD are already adjusted for the user's chosen goal. If E is numeric, keep every day's total strictly BELOW E. T is the base local target. Every TD line is the AUTHORITATIVE target for that specific epoch day and overrides T for that day: TD = kcal|proteinG|carbsG|fatG|tolerancePercent. Center each day on TD kcal first, then protein (the app treats protein as a non-blocking objective), then carbs and fat. The tolerance percent is the app's outer acceptance margin, NOT bonus calories. The goal-specific deficit or surplus is already included in TD: never apply a second calorie adjustment. REC is informational only: available|planned|remaining|maxDailyPercent. B0/B/BT order is weightKg|bodyFatPct|muscleMassKg|skeletalMuscleKg|bodyWaterPct|visceralFat and means baseline/current/recent-trend-delta. BM0/BM/BMD/BMT order is chest|waist|abdomen|shoulders|glutes|hips|armLeft|armRight|thighLeft|thighRight|calfLeft|calfRight and means baseline/current/previous-delta/recent-trend-delta. `?` means unavailable. Body/BIA signals are contextual only: use them jointly to inform food choice, distribution and timing, never to alter calories/macros, diagnose, or infer causality from one reading. GOAL_OUTCOMES in the personal context are user-confirmed results for previous periods: evidence about strategy and adherence only, never permission to override local targets or apply punitive restriction.
DP format is A=allergies;I=intolerances;E=excludedFoods;D=dislikedFoods;P=preferredFoods;S=dietStyle;N=free-text food preferences. A, I, E and S are HARD constraints: never choose a food that violates them (the app rejects violations by catalog tag and by name). D, P and N are SOFT preferences: follow them when possible but never bend the targets for them.
Rules: generate exactly the dates in GENERATE_FROM (inclusive) through its end date, with exactly MEALS_PER_DAY meals per day. Record order: W, then for each requested date exactly one D followed by its M records, each M followed by its I records, and optional S/H records; after the final date emit exactly ONE V record as the last line. Use distinct meal slots with practical timing. Never use `|` or line breaks inside a text field.
Every edible ingredient, condiment and caloric beverage in a meal MUST be its own I record with a foodId: oils, butter, dressings, sauces, sugar, honey, milk, juices, wine, protein powder and any other caloric condiment or drink cannot be omitted or hidden in a recipe. Zero-calorie items (water, tea, coffee, herbs, spices) may be left out. Never write `q.b.`, `un filo`, `un po'`: give grams.
Preparation: a brief, truthful sequence of steps using only foods present in the M's own I records, and WITHOUT any quantity: never digits followed by g, ml, spoons, cups, slices, pieces and so on (write "condisci con l'olio", not "2 cucchiai di olio"). Times and temperatures are allowed. Do not claim steps the listed foods do not support.
Supplements: protein powder is optional, as S|PROTEIN_POWDER|<foodId from CAT>|grams|timeMinutes|notes. Creatine only when SM=SPORT, as S|CREATINE|-|grams|timeMinutes|notes. H may give cautious hydration guidance (quantities allowed). No punitive compensation. V notes <= 8 words.
VARIETY: RM=type|title lists meals the user was served in the last two weeks (most recent first). Do not reuse an RM meal or an equivalent one with the same main ingredients unless no reasonable alternative fits targets, constraints and preferences. Rotate protein sources, vegetables, fruit, grains and preparation methods across the requested dates. Repeated meals are allowed when needed; variety is a preference, never a reason to leave the period incomplete.
Skeleton: ${FoodCatalogPlanContract.HEADER} -> W -> all requested dates (D -> M/I/S/H) -> V exactly once.
""".trimIndent()

    /**
     * Sottoinsieme di catalogo per il prompt: [FoodCatalog.subsetFor] riordinato per categoria e id (ordine
     * deterministico indipendente dall'ordine del file). Funzione pura.
     */
    fun subset(catalog: FoodCatalog, constraints: CatalogConstraints): List<FoodEntry> =
        catalog.subsetFor(constraints).sortedWith(compareBy<FoodEntry>({ it.category }, { it.id }))

    /** Blocco `CAT` da accodare al messaggio utente: una riga per voce, raggruppate per categoria. */
    fun catalogBlock(catalog: FoodCatalog, constraints: CatalogConstraints): String {
        val entries = subset(catalog, constraints)
        return buildString {
            appendLine("CAT:v${catalog.version}|${entries.size}|id|name|state|kcal100|P|C|F|minG-maxG|stepG|units")
            var category: String? = null
            entries.forEach { entry ->
                if (entry.category != category) {
                    category = entry.category
                    appendLine("#${entry.category}")
                }
                appendLine(line(entry))
            }
        }
    }

    internal fun line(entry: FoodEntry): String {
        val p = entry.per100
        val units = entry.units.joinToString(";") { "${it.name}=${num(it.grams)}" }
        return listOf(
            entry.id,
            entry.name.replace('|', '/'),
            when (entry.state) {
                FoodState.RAW -> "R"
                FoodState.COOKED -> "C"
                FoodState.AS_SOLD -> "S"
                FoodState.NA -> "-"
            },
            Math.round(p.kcal).toString(),
            num(p.protein),
            num(p.carbs),
            num(p.fat),
            "${num(entry.minGrams)}-${num(entry.maxGrams)}",
            num(entry.stepGrams),
            units,
        ).joinToString("|")
    }

    /** Numero compatto: intero se possibile, altrimenti un decimale con il punto. */
    private fun num(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        return if (rounded == Math.rint(rounded)) rounded.toLong().toString() else String.format(Locale.US, "%.1f", rounded)
    }
}
