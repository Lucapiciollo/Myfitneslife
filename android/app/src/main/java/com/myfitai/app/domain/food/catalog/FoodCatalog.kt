package com.myfitai.app.domain.food.catalog

import com.myfitai.app.domain.food.DietaryProfile
import com.myfitai.app.domain.food.FoodConstraintValidator
import com.myfitai.app.domain.food.FoodSynonyms
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.InputStream

/**
 * Filtri deterministici per estrarre dal catalogo il sottoinsieme da proporre a un piano.
 * Tutto cio' che e' vuoto/nullo non filtra. Solo dati: nessun prompt, nessuna UI, nessun Room.
 */
data class CatalogConstraints(
    /** Allergie, intolleranze, esclusioni e stile alimentare: stessa semantica di [FoodConstraintValidator]. Profilo illeggibile = nessuna voce. */
    val profile: DietaryProfile? = null,
    /** Tag da escludere in piu' rispetto a quelli derivati dal profilo (vedi [FoodCatalog.TAG_VOCABULARY]). */
    val excludedTags: Set<String> = emptySet(),
    val excludedIds: Set<String> = emptySet(),
    /** Mese 1-12: tiene le voci di stagione e quelle senza stagionalita'. */
    val month: Int? = null,
    /** Categorie ammesse (es. "CEREALI"); null = tutte. */
    val categories: Set<String>? = null,
    /** Per default solo alimenti semplici: i piatti pronti vanno chiesti esplicitamente. */
    val kinds: Set<FoodKind> = setOf(FoodKind.FOOD),
    /** Voci da tenere comunque (se superano i filtri) quando si applica [maxEntries]. */
    val includeIds: Set<String> = emptySet(),
    /** Tetto al numero di voci (incluse le [includeIds]). I restanti posti vanno alle prime voci nell'ordine del catalogo. */
    val maxEntries: Int? = null,
)

/**
 * Catalogo alimenti in sola lettura. Puro: [load] legge un [InputStream] (nessuna dipendenza Android);
 * l'accesso agli assets sta in [AssetFoodCatalogLoader].
 */
class FoodCatalog private constructor(val version: Int, val entries: List<FoodEntry>) {

    private val byIdIndex: Map<String, FoodEntry> = entries.associateBy { it.id }
    private val aliasIndex: Map<String, FoodEntry>

    /** Alias normalizzati usati da piu' voci -> id in conflitto. Nell'indice vince la prima voce. Vuoto nell'asset curato. */
    val aliasCollisions: Map<String, List<String>>

    init {
        val index = LinkedHashMap<String, FoodEntry>()
        val owners = LinkedHashMap<String, MutableList<String>>()
        entries.forEach { entry ->
            (entry.aliases + entry.name).map { normalizeAlias(it) }.filter { it.isNotEmpty() }.distinct().forEach { key ->
                owners.getOrPut(key) { mutableListOf() }.add(entry.id)
                index.putIfAbsent(key, entry)
            }
        }
        aliasIndex = index
        aliasCollisions = owners.filterValues { it.size > 1 }
    }

    fun byId(id: String): FoodEntry? = byIdIndex[id]

    /** Un alimento citato in un testo libero: [key] e' la chiave normalizzata (nome o alias) che ha combaciato. */
    data class Mention(val entry: FoodEntry, val key: String)

    private val maxAliasTokens: Int = aliasIndex.keys.maxOfOrNull { it.count { c -> c == ' ' } + 1 } ?: 1

    /** Chiavi normalizzate (nome e alias) di una voce. */
    fun keysOf(entry: FoodEntry): Set<String> =
        (entry.aliases + entry.name).map { normalizeAlias(it) }.filter { it.isNotEmpty() }.toSet()

    /**
     * Alimenti del catalogo citati in [text] (per nome o alias, dopo la normalizzazione), nell'ordine del testo.
     * Combacia sempre la chiave PIU' LUNGA a ogni posizione ("patate dolci" batte "patate") e consuma le parole usate.
     */
    fun mentionsIn(text: String): List<Mention> {
        val tokens = normalizeAlias(text).split(' ').filter { it.isNotEmpty() }
        val found = mutableListOf<Mention>()
        var i = 0
        while (i < tokens.size) {
            var matched = 0
            for (len in minOf(maxAliasTokens, tokens.size - i) downTo 1) {
                val key = tokens.subList(i, i + len).joinToString(" ")
                val entry = aliasIndex[key] ?: continue
                found += Mention(entry, key)
                matched = len
                break
            }
            i += if (matched > 0) matched else 1
        }
        return found
    }

    /** Cerca per nome o alias, dopo la normalizzazione: "L'Olio d'oliva" e "olio oliva" sono la stessa chiave. */
    fun findByAlias(text: String): FoodEntry? = aliasIndex[normalizeAlias(text)]

    /** Voce opposta crudo/cotto, se presente. */
    fun pairOf(entry: FoodEntry): FoodEntry? = entry.pairId?.let { byIdIndex[it] }

    /** Voci senza nessuno dei [tags] (allergeni/dieta). Ordine del catalogo. */
    fun withoutTags(tags: Set<String>): List<FoodEntry> =
        if (tags.isEmpty()) entries else entries.filter { entry -> entry.tags.none { it in tags } }

    /** Voci di stagione nel [month] (1-12) piu' quelle disponibili tutto l'anno. */
    fun inSeason(month: Int): List<FoodEntry> {
        require(month in 1..12) { "mese non valido: $month" }
        return entries.filter { it.isInSeason(month) }
    }

    /**
     * Sottoinsieme deterministico (ordine del catalogo) per il prompt. Funzione pura.
     *
     * Con un profilo: una voce e' esclusa se un suo tag e' bloccato dal profilo (vedi [blockedTags]) oppure se il
     * suo NOME viola il profilo secondo [FoodConstraintValidator] (controllo su elenco, non garanzia medica).
     * Un profilo illeggibile non e' "senza vincoli": restituisce una lista vuota.
     */
    fun subsetFor(constraints: CatalogConstraints): List<FoodEntry> {
        constraints.month?.let { require(it in 1..12) { "mese non valido: $it" } }
        constraints.maxEntries?.let { require(it >= 0) { "maxEntries negativo: $it" } }
        val profile = constraints.profile
        if (profile != null && profile.isUnreadable) return emptyList()
        val blocked = constraints.excludedTags + (profile?.let { blockedTags(it) } ?: emptySet())
        val nameCheck = profile?.takeIf { FoodConstraintValidator.hasHardConstraints(it) }
        val candidates = entries.filter { entry ->
            entry.kind in constraints.kinds &&
                (constraints.categories == null || entry.category in constraints.categories) &&
                entry.id !in constraints.excludedIds &&
                entry.tags.none { it in blocked } &&
                (constraints.month == null || entry.isInSeason(constraints.month)) &&
                (nameCheck == null || FoodConstraintValidator.validateIngredientNames(listOf(entry.name), nameCheck).isEmpty())
        }
        val max = constraints.maxEntries ?: return candidates
        val must = candidates.filter { it.id in constraints.includeIds }
        val room = max - must.size
        val chosen = if (room <= 0) must.take(max).map { it.id }.toSet() else
            (must.map { it.id } + candidates.filter { it.id !in constraints.includeIds }.take(room).map { it.id }).toSet()
        return candidates.filter { it.id in chosen }
    }

    companion object {
        /**
         * Identificatori dei concetti di [FoodSynonyms] usati come tag di catalogo. NICKEL e SULPHITES non ci sono:
         * l'app non puo' verificarli (vedi FoodSynonyms, "limited").
         */
        val TAG_VOCABULARY: Set<String> = setOf(
            "LACTOSE", "MILK", "MILK_DRINK", "CHEESE", "BUTTER", "GLUTEN", "TREE_NUTS", "WALNUT", "ALMOND", "HAZELNUT",
            "PISTACHIO", "CASHEW", "PINE_NUT", "PEANUT", "SOY", "SESAME", "CELERY", "MUSTARD", "LUPIN", "FISH",
            "CRUSTACEANS", "MOLLUSCS", "EGG", "RICE", "MEAT", "GELATIN", "HONEY",
        )

        /** Testo canonico che [FoodSynonyms] riconosce per ciascun tag: serve a sapere quali tag blocca un profilo. */
        private val TAG_PROBES: Map<String, String> = mapOf(
            "LACTOSE" to "latticini", "MILK" to "latticini", "MILK_DRINK" to "latte", "CHEESE" to "formaggio", "BUTTER" to "burro",
            "GLUTEN" to "farina", "TREE_NUTS" to "frutta a guscio", "WALNUT" to "noce", "ALMOND" to "mandorla",
            "HAZELNUT" to "nocciola", "PISTACHIO" to "pistacchio", "CASHEW" to "anacardio", "PINE_NUT" to "pinolo",
            "PEANUT" to "arachide", "SOY" to "soia", "SESAME" to "sesamo", "CELERY" to "sedano", "MUSTARD" to "senape",
            "LUPIN" to "lupino", "FISH" to "pesce", "CRUSTACEANS" to "gambero", "MOLLUSCS" to "cozza", "EGG" to "uovo",
            "RICE" to "riso", "MEAT" to "carne", "GELATIN" to "gelatina", "HONEY" to "miele",
        )

        /** Articoli e preposizioni scartati dalla chiave di ricerca (stessa lista dello script tools/food-catalog). */
        private val STOPWORDS = setOf(
            "il", "lo", "la", "le", "i", "gli", "l", "un", "uno", "una", "di", "del", "dello", "della", "dei", "degli",
            "delle", "d", "a", "al", "allo", "alla", "ai", "agli", "alle", "da", "dal", "dalla", "con", "e", "ed", "in", "su", "per",
        )

        /** Minuscolo, senza accenti e punteggiatura (come [DietaryProfile.normalize]), senza articoli/preposizioni. */
        fun normalizeAlias(text: String): String =
            DietaryProfile.normalize(text).split(' ').filter { it.isNotEmpty() && it !in STOPWORDS }.joinToString(" ")

        /** Tag che un profilo blocca: quelli il cui testo canonico e' rifiutato da allergie, intolleranze, esclusioni o dieta. */
        private fun blockedTags(profile: DietaryProfile): Set<String> {
            if (!FoodConstraintValidator.hasHardConstraints(profile)) return emptySet()
            val matchers = buildList<FoodSynonyms.Matcher> {
                profile.allergies.forEach { add(FoodSynonyms.constraintMatcher(it, strict = true)) }
                profile.intolerances.forEach { add(FoodSynonyms.constraintMatcher(it, strict = true)) }
                profile.excludedFoods.forEach { add(FoodSynonyms.constraintMatcher(it, strict = false)) }
                FoodSynonyms.dietMatcher(profile.dietStyle)?.let { add(it) }
            }
            return TAG_PROBES.filter { (_, probe) ->
                val tokens = FoodSynonyms.tokenize(probe)
                matchers.any { it.matches(tokens) }
            }.keys
        }

        /**
         * Legge il catalogo JSON. Lancia [IllegalArgumentException] se manca `catalogVersion`, se un id e' duplicato,
         * se un tag non e' in [TAG_VOCABULARY] o se il JSON non e' leggibile.
         */
        fun load(input: InputStream): FoodCatalog {
            val text = input.bufferedReader(Charsets.UTF_8).readText()
            try {
                val root = JSONObject(text)
                require(root.has("catalogVersion")) { "catalogVersion mancante" }
                val array = root.getJSONArray("entries")
                val entries = (0 until array.length()).map { parseEntry(array.getJSONObject(it)) }
                val duplicates = entries.groupBy { it.id }.filterValues { it.size > 1 }.keys
                require(duplicates.isEmpty()) { "id duplicati: $duplicates" }
                return FoodCatalog(root.getInt("catalogVersion"), entries)
            } catch (e: JSONException) {
                throw IllegalArgumentException("catalogo alimenti non leggibile: ${e.message}", e)
            }
        }

        private fun parseEntry(o: JSONObject): FoodEntry {
            val id = o.getString("id")
            val per = o.getJSONObject("per100")
            val src = o.getJSONObject("source")
            val tags = o.getJSONArray("tags").strings().toSet()
            require(tags.all { it in TAG_VOCABULARY }) { "$id: tag sconosciuti ${tags - TAG_VOCABULARY}" }
            return FoodEntry(
                id = id,
                name = o.getString("name"),
                aliases = o.getJSONArray("aliases").strings(),
                category = o.getString("cat"),
                shopCategory = o.getString("shopCat"),
                state = FoodState.valueOf(o.getString("state")),
                pairId = o.stringOrNull("pairId"),
                yieldCookedPerRaw = o.doubleOrNull("yield"),
                per100 = Per100(
                    kcal = per.getDouble("kcal"),
                    protein = per.getDouble("p"),
                    carbs = per.getDouble("c"),
                    fat = per.getDouble("f"),
                    fiber = per.doubleOrNull("fiber"),
                    sodiumMg = per.doubleOrNull("sodiumMg"),
                    water = per.doubleOrNull("water"),
                ),
                edible = o.doubleOrNull("edible") ?: 1.0,
                units = o.getJSONArray("units").objects().map { u ->
                    FoodUnit(u.getString("n"), u.getDouble("g"), u.doubleOrNull("ml"), u.getString("src"))
                },
                density = o.doubleOrNull("density"),
                tags = tags,
                roles = o.getJSONArray("role").strings().toSet(),
                seasonMonths = o.getJSONArray("season").ints().toSet(),
                minGrams = o.getDouble("minG"),
                maxGrams = o.getDouble("maxG"),
                stepGrams = o.getDouble("stepG"),
                scalable = o.getBoolean("scalable"),
                confidence = FoodConfidence.valueOf(o.getString("confidence")),
                source = FoodSource(
                    entity = src.getString("ente"),
                    fdcId = if (src.has("fdcId") && !src.isNull("fdcId")) src.getLong("fdcId") else null,
                    dataset = src.stringOrNull("dataset"),
                    date = src.stringOrNull("data"),
                ),
                kind = FoodKind.valueOf(o.getString("kind")),
                note = o.stringOrNull("note"),
            )
        }

        private fun JSONObject.stringOrNull(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.doubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) getDouble(key) else null

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

        private fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    }
}
