package com.myfitai.app.domain.food.catalog

/** Stato di preparazione al quale si riferiscono i valori per 100 g di una voce di catalogo. */
enum class FoodState { RAW, COOKED, AS_SOLD, NA }

/**
 * Affidabilita' del dato: A = voce USDA equivalente, B = proxy USDA dichiarato (nota obbligatoria),
 * C = stima NON USDA da verificare con l'etichetta. Una voce C non diventa mai A.
 */
enum class FoodConfidence { A, B, C }

enum class FoodKind { FOOD, DISH }

/** Valori per 100 g. `kcal` e' quello della fonte, mai ricalcolato. I campi opzionali mancano se la fonte non li ha. */
data class Per100(
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fiber: Double? = null,
    val sodiumMg: Double? = null,
    val water: Double? = null,
)

/**
 * Equivalenza pratica nota (cucchiaio, fetta, uovo...). [grams] e' il peso di UNA unita'. [source] dice da dove
 * viene: "USDA food_portion" oppure una convenzione dichiarata (es. "convenzione 200 ml"); mai inventata.
 */
data class FoodUnit(val name: String, val grams: Double, val ml: Double?, val source: String)

data class FoodSource(val entity: String, val fdcId: Long?, val dataset: String?, val date: String?)

/** Una voce del catalogo alimenti in sola lettura (assets/food/catalog_v1.json). */
data class FoodEntry(
    /** Identificatore stabile con prefisso, es. `it.pasta_semola_secca`. Sempre ASCII. */
    val id: String,
    val name: String,
    /** Alias gia' normalizzati (vedi [FoodCatalog.normalizeAlias]). */
    val aliases: List<String>,
    val category: String,
    val shopCategory: String,
    val state: FoodState,
    /** Voce opposta crudo/cotto, se esiste. La relazione e' simmetrica. */
    val pairId: String?,
    /** Resa cotto/crudo (grammi di cotto per 1 g di crudo). Uguale sulle due voci della coppia; null se non nota. */
    val yieldCookedPerRaw: Double?,
    val per100: Per100,
    /** Parte edibile (default 1.0). */
    val edible: Double,
    val units: List<FoodUnit>,
    /** g/ml per i liquidi. */
    val density: Double?,
    /** Identificatori dei concetti di FoodSynonyms (allergeni/dieta) che la voce contiene. Vedi [FoodCatalog.TAG_VOCABULARY]. */
    val tags: Set<String>,
    val roles: Set<String>,
    /** Mesi di stagione (1-12). Vuoto = tutto l'anno. */
    val seasonMonths: Set<Int>,
    val minGrams: Double,
    val maxGrams: Double,
    /** Passo pratico di porzione in grammi. */
    val stepGrams: Double,
    val scalable: Boolean,
    val confidence: FoodConfidence,
    val source: FoodSource,
    val kind: FoodKind,
    val note: String?,
) {
    fun isInSeason(month: Int): Boolean = seasonMonths.isEmpty() || month in seasonMonths
}
