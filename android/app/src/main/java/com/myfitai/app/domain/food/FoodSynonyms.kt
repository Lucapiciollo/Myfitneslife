package com.myfitai.app.domain.food

/**
 * Curated Italian food families used by [FoodConstraintValidator].
 *
 * IMPORTANT LIMITS (see 06-ai/providers/shared/FOOD_CONSTRAINTS_LIMITS.md): this is a list based check, not a
 * medical guarantee. It only sees the words of ingredient names, meal titles and supplement notes. It cannot know
 * hidden ingredients (sauces, stock cubes, "may contain traces", cross contamination), brand recipes or
 * ingredients written in another language. Items not in the lists below are NOT detected.
 *
 * How a text is compared:
 *  1. NFD normalisation without accents, lower case, only letters/digits ([DietaryProfile.normalize]).
 *  2. Every word is reduced with [stem], a deliberately tiny Italian singular/plural folding
 *     (pomodoro/pomodori, uovo/uova, noce/noci, pistacchio/pistacchi, mollusco/molluschi).
 *  3. A term matches when its words appear contiguously in the text (whole words, never substrings, so
 *     "grano" does not match "grana padano" and "noce" does not match "pranzo").
 *  4. Before matching, "senza X" is dropped and the concept's [Concept] safe lookalikes are neutralised
 *     (e.g. "burro di arachidi" is not butter, "noce di cocco" is not a walnut, "latte di mandorla" is not milk).
 *  5. A concept can be waived for the whole ingredient by a free-from label (e.g. "senza lattosio" for the
 *     lactose concept). Allergy to milk proteins is never waived by "senza lattosio".
 *
 * Design decisions worth knowing:
 *  - "risotto" violates a rice constraint (it is rice) but not a gluten one; no wheat is assumed.
 *  - "avena" counts as gluten (conservative: cross contamination), "grano saraceno" does not.
 *  - Allergy/intolerance named "latte" means all dairy; an *excluded food* named "latte" means only milk.
 *  - Cheese rennet, wine fining agents, gelatine in sweets and similar hidden animal derivatives are not modelled
 *    for vegetarian/vegan styles beyond the explicit terms listed (gelatina is listed).
 */
object FoodSynonyms {
    enum class DietStyle { VEGETARIAN, VEGAN, PESCETARIAN }

    /** One allergen/diet family, with terms already normalised and stemmed. */
    class Concept internal constructor(
        val id: String,
        private val terms: List<List<String>>,
        private val safe: List<List<String>>,
        private val freeFrom: List<List<String>>,
    ) {
        fun matches(ingredient: List<String>): Boolean {
            if (ingredient.isEmpty() || terms.isEmpty()) return false
            if (freeFrom.any { containsSequence(ingredient, it) }) return false
            var cleaned = stripNegations(ingredient)
            safe.forEach { cleaned = replaceSequence(cleaned, it) }
            return terms.any { containsSequence(cleaned, it) }
        }
    }

    /** A group of concepts: matches when any concept matches. */
    class Matcher internal constructor(private val concepts: List<Concept>) {
        fun matches(ingredientTokens: List<String>): Boolean = concepts.any { it.matches(ingredientTokens) }
        fun matchesText(text: String): Boolean = matches(tokenize(text))
    }

    fun tokenize(text: String): List<String> =
        DietaryProfile.normalize(text).split(' ').filter { it.isNotBlank() }.map(::stem)

    /** Folds Italian singular/plural; short words and a few look-alikes are kept as they are. */
    fun stem(token: String): String {
        IRREGULAR[token]?.let { return it }
        if (token in PROTECTED || token.length < 4 || token.any { it.isDigit() }) return token
        var s = token
        if (s.last() in "aeio") s = s.dropLast(1)
        if (s.length >= 4 && s.last() == 'i') s = s.dropLast(1)
        if (s.length >= 4 && (s.endsWith("ch") || s.endsWith("gh"))) s = s.dropLast(1)
        return s
    }

    /**
     * Builds the matcher for one declared constraint. [strict] is true for allergies and intolerances
     * (the name "latte" then means every dairy product) and false for free "excluded foods".
     */
    fun constraintMatcher(rawConstraint: String, strict: Boolean): Matcher {
        val tokens = constraintTokens(rawConstraint)
        if (tokens.isEmpty()) return Matcher(emptyList())
        lookup(tokens, strict)?.let { return Matcher(listOf(it)) }
        val parts = splitOnConjunction(tokens)
        if (parts.size > 1) {
            val found = parts.map { lookup(it, strict) }
            if (found.all { it != null }) return Matcher(found.filterNotNull())
        }
        return Matcher(listOf(Concept("literal", listOf(tokens), emptyList(), emptyList())))
    }

    fun dietStyle(rawStyle: String?): DietStyle? {
        val words = DietaryProfile.normalize(rawStyle.orEmpty()).split(' ').filter { it.isNotBlank() }
        return when {
            words.any { it.startsWith("vegan") } -> DietStyle.VEGAN
            words.any { it.startsWith("pescetarian") || it.startsWith("pescatarian") || it.startsWith("pesco") } -> DietStyle.PESCETARIAN
            words.any { it.startsWith("vegetarian") } -> DietStyle.VEGETARIAN
            else -> null
        }
    }

    /** Null when the free-text style is not one of the known restrictive styles (e.g. onnivoro, mediterranea). */
    fun dietMatcher(rawStyle: String?): Matcher? = dietStyle(rawStyle)?.let { style ->
        val ids = when (style) {
            DietStyle.VEGETARIAN -> VEGETARIAN_IDS
            DietStyle.VEGAN -> VEGAN_IDS
            DietStyle.PESCETARIAN -> PESCETARIAN_IDS
        }
        Matcher(ids.mapNotNull { id -> definitions.firstOrNull { it.id == id }?.concept })
    }

    // ---------------------------------------------------------------------------------------------------------

    private val IRREGULAR = mapOf("pesci" to "pesce", "pesche" to "pesca", "pasti" to "pasto", "polpe" to "polpa")
    private val PROTECTED = setOf("grana", "pesca", "pesce", "pasto", "polpa")
    private val NEGATION_FILLER = setOf("di", "d", "del", "dell", "dei", "l", "il", "lo", "la", "i", "gli", "le", "un", "uno", "una")
    private val LEADING_NOISE = setOf(
        "allergia", "allergie", "allergico", "allergica", "intolleranza", "intolleranze", "intollerante", "senza", "no",
        "evito", "evitare", "escludere", "esclusione", "non", "sensibilita", "sensibile", "mangio", "tollero",
        "a", "al", "allo", "alla", "alle", "ai", "agli", "all", "ad", "di", "del", "dello", "della", "dei", "degli",
        "delle", "da", "il", "lo", "la", "le", "i", "gli", "un", "una", "uno",
    )

    private fun constraintTokens(raw: String): List<String> {
        val words = DietaryProfile.normalize(raw).split(' ').filter { it.isNotBlank() }.toMutableList()
        while (words.size > 1 && words.first() in LEADING_NOISE) words.removeAt(0)
        return words.map(::stem)
    }

    private fun splitOnConjunction(tokens: List<String>): List<List<String>> {
        val parts = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        tokens.forEach { token ->
            if (token == "e" || token == "ed") {
                if (current.isNotEmpty()) parts += current
                current = mutableListOf()
            } else current += token
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }

    private fun lookup(tokens: List<String>, strict: Boolean): Concept? {
        val key = tokens.joinToString(" ")
        return if (strict) strictIndex[key] else plainIndex[key]
    }

    private fun containsSequence(hay: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > hay.size) return false
        for (start in 0..hay.size - needle.size) {
            var ok = true
            for (i in needle.indices) if (hay[start + i] != needle[i]) { ok = false; break }
            if (ok) return true
        }
        return false
    }

    private fun replaceSequence(hay: List<String>, needle: List<String>): List<String> {
        if (needle.isEmpty() || needle.size > hay.size) return hay
        val out = ArrayList<String>(hay.size)
        var i = 0
        while (i < hay.size) {
            if (i + needle.size <= hay.size && (needle.indices).all { hay[i + it] == needle[it] }) {
                out += "#"
                i += needle.size
            } else out += hay[i++]
        }
        return out
    }

    /** "senza burro", "privo di glutine": the negated word is not an ingredient of the dish. */
    private fun stripNegations(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val negation = t == "senz" || (t == "priv" && tokens.getOrNull(i + 1) == "di")
            if (!negation) { out += t; i++; continue }
            i += if (t == "priv") 2 else 1
            while (i < tokens.size && tokens[i] in NEGATION_FILLER) i++
            if (i < tokens.size) i++
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------------------
    // Curated lists

    private class Definition(
        val id: String,
        val names: Set<String>,
        val allergyNames: Set<String>,
        val concept: Concept,
    )

    private fun phrases(items: Collection<String>): List<List<String>> =
        items.map(::tokenize).filter { it.isNotEmpty() }.distinct().sortedByDescending { it.size }

    private fun def(
        id: String,
        names: Set<String>,
        terms: Set<String>,
        allergyNames: Set<String> = emptySet(),
        safe: Collection<String> = emptySet(),
        freeFrom: Collection<String> = emptySet(),
    ) = Definition(id, names, allergyNames, Concept(id, phrases(terms), phrases(safe), phrases(freeFrom)))

    private val PLANT_BASES = listOf(
        "mandorla", "cocco", "soia", "avena", "riso", "nocciola", "canapa", "anacardi", "pisello", "castagna",
        "quinoa", "farro", "pistacchio", "arachide", "sesamo", "lupino", "cacao", "karite", "girasole", "mais",
    )
    private val DAIRY_PLANT_SAFE = listOf("latte", "burro", "panna", "yogurt", "formaggio", "gelato", "kefir")
        .flatMap { dairy -> PLANT_BASES.map { "$dairy di $it" } }
    private val VEGAN_LABELS = listOf("vegetale", "vegetali", "vegano", "vegana", "vegani", "vegan", "plant based")

    private val CHEESES = setOf(
        "formaggio", "mozzarella", "ricotta", "parmigiano", "grana", "pecorino", "gorgonzola", "mascarpone",
        "stracchino", "scamorza", "provola", "provolone", "fontina", "taleggio", "asiago", "emmental", "emmenthal",
        "gruviera", "gruyere", "brie", "camembert", "feta", "caciotta", "caciocavallo", "burrata", "crescenza",
        "robiola", "quartirolo", "primo sale", "cheddar", "philadelphia", "caprino", "halloumi", "paneer", "fiocchi di latte",
    )
    private val MILK_PRODUCTS = setOf(
        "latte", "latticini", "latticino", "yogurt", "yoghurt", "panna", "burro", "gelato", "kefir", "skyr", "quark",
        "besciamella", "ghee", "latticello", "siero del latte", "siero di latte", "whey", "caseina", "caseinato",
        "lattoalbumina", "lattoglobulina", "stracciatella", "crema pasticcera", "budino",
    )
    private val WALNUT_SAFE = listOf(
        "cocco", "vitello", "burro", "prosciutto", "maiale", "manzo", "coscia", "spalla", "pollo",
    ).map { "noce di $it" } + "noce moscata"
    private val FISH = setOf(
        "pesce", "salmone", "tonno", "merluzzo", "orata", "branzino", "spigola", "sgombro", "sardina", "acciuga", "alice",
        "baccala", "stoccafisso", "trota", "sogliola", "nasello", "cernia", "rombo", "dentice", "aringa", "carpa",
        "luccio", "pangasio", "platessa", "halibut", "tilapia", "bottarga", "surimi", "spada", "cefalo", "palombo",
    )
    private val CRUSTACEANS = setOf(
        "crostacei", "crostaceo", "gambero", "gamberetto", "gamberone", "scampo", "aragosta", "astice", "granchio",
        "mazzancolla", "canocchia", "krill", "frutti di mare",
    )
    private val MOLLUSCS = setOf(
        "molluschi", "mollusco", "cozza", "vongola", "polpo", "calamaro", "calamaretto", "seppia", "totano", "ostrica",
        "capasanta", "cappasanta", "lumaca", "cannolicchio", "fasolaro", "frutti di mare",
    )
    private val MEAT = setOf(
        "carne", "pollo", "tacchino", "manzo", "vitello", "vitellone", "maiale", "suino", "bovino", "prosciutto", "bresaola",
        "salsiccia", "lardo", "pancetta", "guanciale", "speck", "salame", "salamino", "mortadella", "wurstel", "agnello",
        "coniglio", "anatra", "cinghiale", "capriolo", "fegato", "hamburger", "bacon", "nduja", "coppa", "cotechino",
        "zampone", "porchetta", "strutto", "pollame", "frattaglie", "trippa",
    )
    private val MEAT_SAFE = listOf("soia", "seitan", "legumi", "ceci", "lenticchie", "verdure", "tofu", "fagioli", "piselli", "quinoa")
        .flatMap { base ->
            listOf("hamburger", "burger", "salsiccia", "wurstel", "prosciutto", "salame", "bresaola", "mortadella", "affettato", "carne", "ragu", "bistecca")
                .map { "$it di $base" }
        }
    private val GLUTEN = setOf(
        "pane", "pancarre", "pan carre", "panino", "pasta", "spaghetti", "penne", "fusilli", "rigatoni", "tagliatelle",
        "lasagne", "gnocchi", "tortellini", "ravioli", "farina", "couscous", "cous cous", "seitan", "biscotto", "fetta biscottata",
        "cracker", "grissino", "piadina", "pizza", "focaccia", "frumento", "grano", "segale", "orzo", "farro", "spelta",
        "kamut", "bulgur", "semola", "avena", "cereale", "muesli", "granola", "pangrattato", "pan grattato", "birra",
        "malto", "wafer", "cornflakes", "corn flakes", "crostino", "crouton", "brioche", "cornetto", "croissant",
        "panettone", "pandoro", "taralli", "tarallo", "frisella", "friselle", "tigella", "cantucci", "crepes", "crespella",
    )
    private val GLUTEN_SAFE = buildList {
        listOf("pasta", "pane", "farina", "gnocchi", "biscotto").forEach { item ->
            listOf("riso", "mais", "legumi", "ceci", "lenticchie", "piselli", "quinoa", "grano saraceno", "patate", "tapioca", "manioca")
                .forEach { add("$item di $it") }
        }
        listOf("mandorla", "nocciola", "pistacchio", "arachide", "sesamo", "acciughe", "datteri").forEach { add("pasta di $it") }
        listOf("cocco", "castagne", "teff", "amaranto", "miglio", "lupini", "soia", "nocciole", "mandorle").forEach { add("farina di $it") }
        add("grano saraceno")
    }

    private val definitions: List<Definition> = listOf(
        def("LACTOSE", setOf("lattosio"), CHEESES + MILK_PRODUCTS, safe = DAIRY_PLANT_SAFE,
            freeFrom = listOf("senza lattosio", "delattosato", "privo di lattosio", "lactose free", "lattosio free") + VEGAN_LABELS),
        def("MILK", setOf("latticini", "latticino", "derivati del latte", "proteine del latte", "proteine latte", "formaggi e latticini"),
            CHEESES + MILK_PRODUCTS, allergyNames = setOf("latte"), safe = DAIRY_PLANT_SAFE, freeFrom = VEGAN_LABELS),
        def("MILK_DRINK", setOf("latte"), setOf("latte"), safe = DAIRY_PLANT_SAFE, freeFrom = VEGAN_LABELS),
        def("CHEESE", setOf("formaggio", "formaggi"), CHEESES, safe = DAIRY_PLANT_SAFE, freeFrom = VEGAN_LABELS),
        def("BUTTER", setOf("burro"), setOf("burro", "ghee"), safe = DAIRY_PLANT_SAFE, freeFrom = VEGAN_LABELS),
        def("GLUTEN", setOf("glutine", "celiachia", "celiaco"), GLUTEN, safe = GLUTEN_SAFE,
            freeFrom = listOf("senza glutine", "gluten free", "privo di glutine", "per celiaci", "naturalmente senza glutine")),
        def("TREE_NUTS", setOf("frutta a guscio", "frutta secca", "noci e mandorle"),
            setOf("noce", "mandorla", "nocciola", "pistacchio", "anacardio", "pinolo", "pecan", "macadamia", "gianduia", "nutella", "marzapane", "gheriglio", "frutta a guscio", "frutta secca"),
            safe = WALNUT_SAFE),
        def("WALNUT", setOf("noce", "noce comune", "gheriglio"), setOf("noce", "gheriglio"), safe = WALNUT_SAFE),
        def("PEANUT", setOf("arachide", "nocciolina", "noccioline americane"), setOf("arachide", "nocciolina")),
        def("SOY", setOf("soia"), setOf("soia", "tofu", "tempeh", "edamame", "miso", "tamari", "shoyu", "natto", "okara")),
        def("SESAME", setOf("sesamo"), setOf("sesamo", "tahina", "tahini", "hummus", "halva", "gomasio")),
        def("CELERY", setOf("sedano"), setOf("sedano")),
        def("MUSTARD", setOf("senape"), setOf("senape", "mostarda")),
        def("LUPIN", setOf("lupini", "lupino"), setOf("lupino")),
        def("FISH", setOf("pesce"), FISH, freeFrom = VEGAN_LABELS),
        def("CRUSTACEANS", setOf("crostacei", "crostaceo"), CRUSTACEANS, freeFrom = VEGAN_LABELS),
        def("MOLLUSCS", setOf("molluschi", "mollusco"), MOLLUSCS, freeFrom = VEGAN_LABELS),
        def("EGG", setOf("uovo", "uova"), setOf("uovo", "albume", "tuorlo", "frittata", "omelette", "maionese", "meringa", "ovoprodotti"),
            freeFrom = VEGAN_LABELS + listOf("senza uova", "senza uovo", "eggless")),
        def("RICE", setOf("riso"), setOf("riso", "risotto", "arancini"), freeFrom = emptyList()),
        def("MEAT", setOf("carne"), MEAT, safe = MEAT_SAFE, freeFrom = VEGAN_LABELS),
        def("GELATIN", setOf("gelatina"), setOf("gelatina"), freeFrom = VEGAN_LABELS + listOf("agar")),
        def("HONEY", setOf("miele"), setOf("miele", "pappa reale", "propoli"), freeFrom = VEGAN_LABELS),
    )

    private val strictIndex: Map<String, Concept> = buildMap {
        definitions.forEach { d -> d.allergyNames.forEach { putIfAbsent(tokenize(it).joinToString(" "), d.concept) } }
        definitions.forEach { d -> d.names.forEach { putIfAbsent(tokenize(it).joinToString(" "), d.concept) } }
    }
    private val plainIndex: Map<String, Concept> = buildMap {
        definitions.forEach { d -> d.names.forEach { putIfAbsent(tokenize(it).joinToString(" "), d.concept) } }
    }

    private val PESCETARIAN_IDS = listOf("MEAT")
    private val VEGETARIAN_IDS = listOf("MEAT", "FISH", "CRUSTACEANS", "MOLLUSCS", "GELATIN")
    private val VEGAN_IDS = VEGETARIAN_IDS + listOf("MILK", "EGG", "HONEY")
}
