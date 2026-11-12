package com.myfitai.app.domain.food

/**
 * Curated Italian food families used by [FoodConstraintValidator].
 *
 * IMPORTANT LIMITS (see 06-ai/providers/shared/FOOD_CONSTRAINTS_LIMITS.md): "controllo su elenco, non garanzia
 * medica". It only sees the words of ingredient names, meal titles and supplement texts. It cannot know hidden
 * ingredients (sauces, stock cubes, "may contain traces", cross contamination), brand recipes or ingredients
 * written in another language. Items not in the lists below are NOT detected.
 *
 * How a text is compared:
 *  1. NFD normalisation without accents, lower case, only letters/digits ([DietaryProfile.normalize]).
 *  2. Every word is reduced with [stem], a deliberately tiny Italian singular/plural folding
 *     (pomodoro/pomodori, uovo/uova, noce/noci, pistacchio/pistacchi, mollusco/molluschi).
 *  3. A term matches when its words appear contiguously in the text (whole words, never substrings, so
 *     "grano" does not match "grana padano" and "noce" does not match "pranzo").
 *  4. Negations ("senza X", "privo di X", "non contiene X") and the concept's safe lookalikes
 *     ("burro di arachidi" is not butter, "noce di cocco" is not a walnut, "pasta di riso" is not gluten)
 *     are masked before matching.
 *  5. A free-from/plant label ("senza lattosio", "delattosato", "senza glutine", "vegetale", "vegano") waives a
 *     term only when it sits right next to it: at most two words away and with no "con", "e", "in"... between
 *     (so "Burger vegetale con formaggio" still violates a dairy constraint). Allergy to milk proteins is never
 *     waived by "senza lattosio".
 *  6. In a MEAL TITLE only strong, unambiguous terms count (seitan, orzo, mozzarella, pollo...). Generic staples
 *     that have free-from variants (pasta, pane, latte, yogurt, hamburger...) are decided by the ingredient list,
 *     which is authoritative: "Pasta al pomodoro" with ingredient "Pasta di riso" is accepted for gluten.
 *
 * Constraints that are not in the lists are never silently ignored: a multi-word constraint is expanded through
 * the known groups ("latte e derivati" -> all dairy) and every remaining significant word (>= 4 letters, not an
 * Italian stopword) is matched on its own. Such a constraint is reported through
 * [DietaryProfile.constraintNotices] as NAME_ONLY (checked by name only). "nichel" and "solfiti" are declared
 * LIMITED: only the word itself can be found.
 *
 * Design decisions worth knowing:
 *  - "risotto" violates a rice constraint (it is rice) but not a gluten one; no wheat is assumed.
 *  - "avena" counts as gluten (conservative: cross contamination), "grano saraceno" does not.
 *  - Allergy/intolerance named "latte" means all dairy; an *excluded food* named "latte" means only milk,
 *    unless written "latte e derivati".
 *  - Hidden animal derivatives (rennet, wine fining agents...) are not modelled for vegetarian/vegan styles
 *    beyond the explicit terms listed (gelatina is listed).
 */
object FoodSynonyms {
    enum class DietStyle { VEGETARIAN, VEGAN, PESCETARIAN }

    /** One allergen/diet family, with terms already normalised and stemmed. */
    class Concept internal constructor(
        val id: String,
        private val terms: List<List<String>>,
        private val weakInTitle: Set<List<String>>,
        private val safe: List<List<String>>,
        private val freeFrom: List<List<String>>,
        /** True when the app cannot really verify this family (nichel, solfiti): only the word itself is found. */
        val limited: Boolean = false,
    ) {
        fun matches(ingredient: List<String>, titleMode: Boolean = false): Boolean {
            if (ingredient.isEmpty() || terms.isEmpty()) return false
            var masked = maskNegations(ingredient)
            safe.forEach { masked = maskSequence(masked, it) }
            val labels = freeFrom.flatMap { findAll(ingredient, it) }
            val active = if (titleMode) terms.filterNot { it in weakInTitle } else terms
            return active.any { term ->
                findAll(masked, term).any { range -> !isWaived(range, labels, ingredient) }
            }
        }
    }

    /** A group of concepts: matches when any concept matches. */
    class Matcher internal constructor(
        private val concepts: List<Concept>,
        /** True when part of the constraint is only checked by the literal word (not an entry of the lists). */
        val nameOnly: Boolean = false,
    ) {
        val limited: Boolean get() = concepts.any { it.limited }
        fun matches(ingredientTokens: List<String>, titleMode: Boolean = false): Boolean =
            concepts.any { it.matches(ingredientTokens, titleMode) }
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
     *
     * Resolution: whole text is a known name -> that family; otherwise the longest known names inside the text
     * are used ("latte e derivati", "frutta a guscio noci"), "derivati" widens a family to its group, and every
     * other significant word is matched literally (prudent fallback, [Matcher.nameOnly]).
     */
    fun constraintMatcher(rawConstraint: String, strict: Boolean): Matcher {
        val words = DietaryProfile.normalize(rawConstraint).split(' ').filter { it.isNotBlank() }.toMutableList()
        while (words.size > 1 && words.first() in LEADING_NOISE) words.removeAt(0)
        if (words.isEmpty()) return Matcher(emptyList())
        val tokens = words.map(::stem)
        lookup(tokens, strict)?.let { return Matcher(listOf(it)) }

        val widen = words.any { it.startsWith("derivat") }
        val concepts = linkedMapOf<String, Concept>()
        val literals = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            var matched = 0
            for (len in minOf(MAX_PHRASE, tokens.size - i) downTo 1) {
                val concept = lookup(tokens.subList(i, i + len), strict)
                if (concept != null) {
                    concepts.putIfAbsent(concept.id, concept)
                    matched = len
                    break
                }
            }
            if (matched > 0) {
                i += matched
                continue
            }
            if (isSignificant(words[i], single = words.size == 1)) literals += tokens[i]
            i++
        }
        if (concepts.isEmpty() && literals.isEmpty()) literals += tokens.filter { it.length >= 2 }
        val resolved = concepts.values.map { c -> if (widen) DERIVATIVE_GROUP[c.id]?.let(byId::get) ?: c else c }
            .distinctBy { it.id }
            .toMutableList()
        if (literals.isNotEmpty()) {
            resolved += Concept("literal", literals.distinct().map { listOf(it) }, emptySet(), emptyList(), emptyList())
        }
        return Matcher(resolved, nameOnly = literals.isNotEmpty())
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

    /** True for "nessuno", "onnivoro" and blank: no restriction at all. */
    fun isUnrestrictedStyle(rawStyle: String?): Boolean =
        DietaryProfile.normalize(rawStyle.orEmpty()) in setOf("", "nessuno", "nessuna", "onnivoro", "onnivora", "none", "omnivore")

    /** Null when the free-text style is not one of the known restrictive styles (e.g. onnivoro, mediterranea). */
    fun dietMatcher(rawStyle: String?): Matcher? = dietStyle(rawStyle)?.let { style ->
        val ids = when (style) {
            DietStyle.VEGETARIAN -> VEGETARIAN_IDS
            DietStyle.VEGAN -> VEGAN_IDS
            DietStyle.PESCETARIAN -> PESCETARIAN_IDS
        }
        Matcher(ids.mapNotNull(byId::get))
    }

    // ---------------------------------------------------------------------------------------------------------
    // Token helpers

    private val IRREGULAR = mapOf("pesci" to "pesce", "pesche" to "pesca", "pasti" to "pasto", "polpe" to "polpa")
    private val PROTECTED = setOf("grana", "pesca", "pesce", "pasto", "polpa")
    private const val MAX_PHRASE = 4
    private val NEGATION_FILLER = setOf("di", "d", "del", "dell", "dei", "l", "il", "lo", "la", "i", "gli", "le", "un", "uno", "una")

    /** Words that may not sit between a free-from/plant label and the word it is supposed to qualify. */
    private val LABEL_BARRIER = setOf("con", "e", "ed", "piu", "in", "su", "poi", "anche", "ma", "o", "oppure")
    private const val LABEL_MAX_GAP = 2

    private val LEADING_NOISE = setOf(
        "allergia", "allergie", "allergico", "allergica", "intolleranza", "intolleranze", "intollerante", "senza", "no",
        "evito", "evitare", "escludere", "esclusione", "non", "sensibilita", "sensibile", "mangio", "tollero",
        "a", "al", "allo", "alla", "alle", "ai", "agli", "all", "ad", "di", "del", "dello", "della", "dei", "degli",
        "delle", "da", "il", "lo", "la", "le", "i", "gli", "un", "una", "uno",
    )
    private val STOPWORDS = LEADING_NOISE + setOf(
        "con", "per", "tra", "fra", "nel", "nei", "nella", "nelle", "sul", "sulla", "sui", "che", "anche", "oppure",
        "tutti", "tutte", "tutto", "ogni", "altri", "altre", "tipo", "tipi", "come", "molto", "poco", "poca", "poche",
        "pochi", "niente", "nessun", "nessuno", "nulla", "contiene", "contengono", "prodotti", "prodotto", "alimenti",
        "alimento", "cibi", "cibo", "tracce", "traccia", "vari", "varie", "simili", "altro", "altra", "miei", "mio",
    )
    private val GENERIC_WORDS = setOf(
        "bianco", "bianca", "rosso", "rossa", "verde", "giallo", "gialla", "fresco", "fresca", "secco", "secca",
        "intero", "intera", "cotto", "cotta", "crudo", "cruda", "naturale", "scatola", "grande", "piccolo", "piccola",
    )

    private fun isSignificant(word: String, single: Boolean): Boolean =
        if (single) word.length >= 2 && word !in LEADING_NOISE
        else word.length >= 4 && word !in STOPWORDS && word !in GENERIC_WORDS && !word.startsWith("derivat")

    private fun findAll(hay: List<String>, needle: List<String>): List<IntRange> {
        if (needle.isEmpty() || needle.size > hay.size) return emptyList()
        val out = ArrayList<IntRange>(2)
        for (start in 0..hay.size - needle.size) {
            var ok = true
            for (i in needle.indices) if (hay[start + i] != needle[i]) { ok = false; break }
            if (ok) out += start until start + needle.size
        }
        return out
    }

    /** Same-length replacement so that positions stay comparable with the unmasked tokens. */
    private fun maskSequence(hay: List<String>, needle: List<String>): List<String> {
        val ranges = findAll(hay, needle)
        if (ranges.isEmpty()) return hay
        val out = hay.toMutableList()
        ranges.forEach { r -> r.forEach { out[it] = "#" } }
        return out
    }

    /** "senza burro", "privo di glutine", "non contiene lattosio": the negated word is not in the dish. */
    private fun maskNegations(tokens: List<String>): List<String> {
        val out = tokens.toMutableList()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val span = when {
                t == "senz" -> 1
                t == "priv" && tokens.getOrNull(i + 1) == "di" -> 2
                t == "non" && tokens.getOrNull(i + 1) == "contien" -> 2
                else -> 0
            }
            if (span == 0) { i++; continue }
            var j = i + span
            while (j < tokens.size && tokens[j] in NEGATION_FILLER) j++
            if (j < tokens.size) j++
            for (k in i until j) out[k] = "#"
            i = j
        }
        return out
    }

    private fun isWaived(term: IntRange, labels: List<IntRange>, tokens: List<String>): Boolean = labels.any { label ->
        val after = label.first - term.last - 1
        val before = term.first - label.last - 1
        (after in 0..LABEL_MAX_GAP && gapIsClean(tokens, term.last + 1, label.first - 1)) ||
            (before in 0..LABEL_MAX_GAP && gapIsClean(tokens, label.last + 1, term.first - 1))
    }

    private fun gapIsClean(tokens: List<String>, from: Int, to: Int): Boolean =
        (from..to).none { tokens[it] in LABEL_BARRIER }

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
        weakInTitle: Collection<String> = emptySet(),
        limited: Boolean = false,
    ) = Definition(
        id, names, allergyNames,
        Concept(id, phrases(terms), phrases(weakInTitle).toSet(), phrases(safe), phrases(freeFrom), limited),
    )

    private val PLANT_BASES = listOf(
        "mandorla", "cocco", "soia", "avena", "riso", "nocciola", "canapa", "anacardi", "pisello", "castagna",
        "quinoa", "farro", "pistacchio", "arachide", "sesamo", "lupino", "cacao", "karite", "girasole", "mais",
    )
    private val DAIRY_ITEMS = listOf("latte", "burro", "panna", "yogurt", "formaggio", "gelato", "kefir", "cappuccino", "budino", "crema")
    private val DAIRY_PLANT_SAFE = DAIRY_ITEMS.flatMap { dairy -> PLANT_BASES.map { "$dairy di $it" } }
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
        "lattoalbumina", "lattoglobulina", "stracciatella", "crema pasticcera", "budino", "cappuccino", "tiramisu",
        "cheesecake",
    )
    private val DAIRY_WEAK_IN_TITLE = setOf(
        "formaggio", "latte", "latticini", "latticino", "yogurt", "yoghurt", "panna", "burro", "gelato", "kefir",
        "skyr", "quark", "cappuccino", "budino",
    )
    private val WALNUT_SAFE = listOf(
        "cocco", "vitello", "burro", "prosciutto", "maiale", "manzo", "coscia", "spalla", "pollo",
    ).map { "noce di $it" } + "noce moscata"
    private val FISH = setOf(
        "pesce", "salmone", "tonno", "merluzzo", "orata", "branzino", "spigola", "sgombro", "sardina", "acciuga", "alice",
        "baccala", "stoccafisso", "trota", "sogliola", "nasello", "cernia", "rombo", "dentice", "aringa", "carpa",
        "luccio", "pangasio", "platessa", "halibut", "tilapia", "bottarga", "surimi", "spada", "cefalo", "palombo",
        "pesce azzurro",
    )
    private val CRUSTACEANS = setOf(
        "crostacei", "crostaceo", "gambero", "gamberetto", "gamberone", "scampo", "aragosta", "astice", "granchio",
        "mazzancolla", "canocchia", "krill", "frutti di mare",
    )
    private val MOLLUSCS = setOf(
        "molluschi", "mollusco", "cozza", "vongola", "polpo", "polipo", "calamaro", "calamaretto", "seppia", "totano",
        "ostrica", "capasanta", "cappasanta", "lumaca", "cannolicchio", "fasolaro", "frutti di mare",
    )
    private val MEAT = setOf(
        "carne", "pollo", "tacchino", "manzo", "vitello", "vitellone", "maiale", "suino", "bovino", "prosciutto", "bresaola",
        "salsiccia", "lardo", "pancetta", "guanciale", "speck", "salame", "salamino", "mortadella", "wurstel", "agnello",
        "coniglio", "anatra", "cinghiale", "capriolo", "fegato", "hamburger", "bacon", "nduja", "coppa", "cotechino",
        "zampone", "porchetta", "strutto", "pollame", "frattaglie", "trippa",
        "cotoletta", "ragu", "polpetta", "carpaccio", "spezzatino",
    )
    private val MEAT_WEAK_IN_TITLE = setOf(
        "hamburger", "salsiccia", "wurstel", "prosciutto", "salame", "bresaola", "mortadella", "carne", "bacon",
        "cotoletta", "ragu", "polpetta", "carpaccio", "spezzatino",
    )
    private val MEAT_SAFE: List<String> = run {
        val plantBases = listOf(
            "soia", "seitan", "legumi", "ceci", "lenticchie", "verdure", "ortaggi", "tofu", "fagioli", "piselli", "quinoa",
            "melanzane", "zucchine", "patate", "riso", "funghi", "ananas", "barbabietola", "finocchi",
        )
        val items = listOf(
            "hamburger", "burger", "salsiccia", "wurstel", "prosciutto", "salame", "bresaola", "mortadella", "affettato",
            "carne", "ragu", "bistecca", "polpetta", "carpaccio", "cotoletta", "spezzatino",
        )
        val fishBases = listOf("pesce", "salmone", "tonno", "merluzzo", "baccala", "spada", "orata", "branzino", "gamberi", "polpo")
        items.flatMap { item -> plantBases.map { "$item di $it" } } +
            listOf("polpetta", "carpaccio", "cotoletta", "ragu", "hamburger", "burger").flatMap { item -> fishBases.map { "$item di $it" } }
    }
    private val GLUTEN = setOf(
        "pane", "pancarre", "pan carre", "panino", "pasta", "spaghetti", "penne", "fusilli", "rigatoni", "tagliatelle",
        "lasagne", "gnocchi", "tortellini", "ravioli", "farina", "couscous", "cous cous", "seitan", "biscotto", "fetta biscottata",
        "cracker", "crackers", "grissino", "piadina", "pizza", "focaccia", "frumento", "grano", "segale", "orzo", "farro", "spelta",
        "kamut", "bulgur", "semola", "avena", "cereale", "muesli", "granola", "pangrattato", "pan grattato", "birra",
        "malto", "wafer", "cornflakes", "corn flakes", "crostino", "crouton", "brioche", "cornetto", "croissant",
        "panettone", "pandoro", "taralli", "tarallo", "frisella", "friselle", "tigella", "cantucci", "crepes", "crespella",
        "cotoletta", "panko", "bagel", "impanato", "panato", "milanese", "tiramisu",
        "salsa di soia", "shoyu", "teriyaki",
    )
    private val GLUTEN_STRONG_IN_TITLE = setOf(
        "frumento", "grano", "segale", "orzo", "farro", "spelta", "kamut", "bulgur", "semola", "couscous", "cous cous",
        "seitan", "birra", "malto", "salsa di soia", "shoyu", "teriyaki",
    )
    private val GLUTEN_SAFE = buildList {
        val items = listOf(
            "pasta", "pane", "farina", "gnocchi", "biscotto", "cracker", "crackers", "galletta", "fetta biscottata", "grissino",
            "fiocchi", "wafer", "spaghetti", "penne", "fusilli", "pancarre", "cereale", "crepes", "piadina", "focaccia", "pizza",
            "panko", "pangrattato",
        )
        val bases = listOf(
            "riso", "mais", "legumi", "ceci", "lenticchie", "piselli", "quinoa", "grano saraceno", "patate", "tapioca",
            "manioca", "castagne", "miglio", "amaranto", "teff", "sorgo", "soia",
        )
        items.forEach { item -> bases.forEach { add("$item di $it") } }
        listOf("mandorla", "nocciola", "pistacchio", "arachide", "sesamo", "acciughe", "datteri").forEach { add("pasta di $it") }
        listOf("cocco", "castagne", "teff", "amaranto", "miglio", "lupini", "nocciole", "mandorle").forEach { add("farina di $it") }
        add("grano saraceno")
    }
    private val GLUTEN_LABELS = listOf(
        "senza glutine", "gluten free", "glutine free", "privo di glutine", "non contiene glutine", "per celiaci",
        "adatto ai celiaci", "naturalmente senza glutine",
    )
    private val LACTOSE_LABELS = listOf(
        "senza lattosio", "delattosato", "privo di lattosio", "lactose free", "lattosio free", "non contiene lattosio",
    )

    private val definitions: List<Definition> = listOf(
        def("LACTOSE", setOf("lattosio"), CHEESES + MILK_PRODUCTS, safe = DAIRY_PLANT_SAFE,
            freeFrom = LACTOSE_LABELS + VEGAN_LABELS, weakInTitle = DAIRY_WEAK_IN_TITLE),
        def("MILK", setOf("latticini", "latticino", "derivati del latte", "proteine del latte", "proteine latte", "formaggi e latticini"),
            CHEESES + MILK_PRODUCTS, allergyNames = setOf("latte"), safe = DAIRY_PLANT_SAFE, freeFrom = VEGAN_LABELS,
            weakInTitle = DAIRY_WEAK_IN_TITLE),
        def("MILK_DRINK", setOf("latte"), setOf("latte", "cappuccino"), safe = DAIRY_PLANT_SAFE, freeFrom = VEGAN_LABELS,
            weakInTitle = DAIRY_WEAK_IN_TITLE),
        def("CHEESE", setOf("formaggio", "formaggi"), CHEESES, safe = DAIRY_PLANT_SAFE, freeFrom = VEGAN_LABELS,
            weakInTitle = DAIRY_WEAK_IN_TITLE),
        def("BUTTER", setOf("burro"), setOf("burro", "ghee"), safe = DAIRY_PLANT_SAFE, freeFrom = VEGAN_LABELS,
            weakInTitle = DAIRY_WEAK_IN_TITLE),
        def("GLUTEN", setOf("glutine", "celiachia", "celiaco"), GLUTEN, safe = GLUTEN_SAFE, freeFrom = GLUTEN_LABELS,
            weakInTitle = GLUTEN - GLUTEN_STRONG_IN_TITLE),
        def("TREE_NUTS", setOf("frutta a guscio", "frutta con guscio", "frutta secca", "frutta secca a guscio", "noci e mandorle"),
            setOf("noce", "mandorla", "nocciola", "pistacchio", "anacardio", "pinolo", "pecan", "macadamia", "gianduia", "nutella", "marzapane", "gheriglio", "frutta a guscio", "frutta secca"),
            safe = WALNUT_SAFE),
        def("WALNUT", setOf("noce", "noce comune", "gheriglio"), setOf("noce", "gheriglio"), safe = WALNUT_SAFE),
        def("ALMOND", setOf("mandorla"), setOf("mandorla", "marzapane", "orzata")),
        def("HAZELNUT", setOf("nocciola"), setOf("nocciola", "gianduia", "nutella")),
        def("PISTACHIO", setOf("pistacchio"), setOf("pistacchio")),
        def("CASHEW", setOf("anacardio", "anacardi"), setOf("anacardio")),
        def("PINE_NUT", setOf("pinolo"), setOf("pinolo")),
        def("PEANUT", setOf("arachide", "nocciolina", "noccioline americane"), setOf("arachide", "nocciolina")),
        def("SOY", setOf("soia"), setOf("soia", "tofu", "tempeh", "edamame", "miso", "tamari", "shoyu", "natto", "okara", "teriyaki")),
        def("SESAME", setOf("sesamo"), setOf("sesamo", "tahina", "tahini", "hummus", "halva", "gomasio")),
        def("CELERY", setOf("sedano"), setOf("sedano")),
        def("MUSTARD", setOf("senape"), setOf("senape", "mostarda")),
        def("LUPIN", setOf("lupini", "lupino"), setOf("lupino")),
        def("FISH", setOf("pesce", "pesce azzurro"), FISH, freeFrom = VEGAN_LABELS),
        def("CRUSTACEANS", setOf("crostacei", "crostaceo"), CRUSTACEANS, freeFrom = VEGAN_LABELS),
        def("MOLLUSCS", setOf("molluschi", "mollusco"), MOLLUSCS, freeFrom = VEGAN_LABELS),
        def("EGG", setOf("uovo", "uova"), setOf("uovo", "albume", "tuorlo", "frittata", "omelette", "maionese", "meringa", "ovoprodotti", "tiramisu"),
            freeFrom = VEGAN_LABELS + listOf("senza uova", "senza uovo", "eggless"), weakInTitle = setOf("maionese")),
        def("RICE", setOf("riso"), setOf("riso", "risotto", "arancini"), freeFrom = emptyList()),
        def("MEAT", setOf("carne"), MEAT, safe = MEAT_SAFE, freeFrom = VEGAN_LABELS, weakInTitle = MEAT_WEAK_IN_TITLE),
        def("GELATIN", setOf("gelatina"), setOf("gelatina"), freeFrom = VEGAN_LABELS + listOf("agar")),
        def("HONEY", setOf("miele"), setOf("miele", "pappa reale", "propoli"), freeFrom = VEGAN_LABELS),
        // Declared limits: the app cannot verify these families, only the literal word is searched.
        def("NICKEL", setOf("nichel"), setOf("nichel"), limited = true),
        def("SULPHITES", setOf("solfiti", "solfito", "anidride solforosa"), setOf("solfito", "anidride solforosa"), limited = true),
    )

    private val byId: Map<String, Concept> = definitions.associate { it.id to it.concept }

    private val strictIndex: Map<String, Concept> = buildMap {
        definitions.forEach { d -> d.allergyNames.forEach { putIfAbsent(tokenize(it).joinToString(" "), d.concept) } }
        definitions.forEach { d -> d.names.forEach { putIfAbsent(tokenize(it).joinToString(" "), d.concept) } }
    }
    private val plainIndex: Map<String, Concept> = buildMap {
        definitions.forEach { d -> d.names.forEach { putIfAbsent(tokenize(it).joinToString(" "), d.concept) } }
    }

    private fun lookup(tokens: List<String>, strict: Boolean): Concept? {
        val key = tokens.joinToString(" ")
        return if (strict) strictIndex[key] else plainIndex[key]
    }

    /** "<family> e derivati": a single food widens to the whole group it belongs to. */
    private val DERIVATIVE_GROUP = mapOf(
        "MILK_DRINK" to "MILK", "CHEESE" to "MILK", "BUTTER" to "MILK", "LACTOSE" to "MILK",
        "WALNUT" to "TREE_NUTS", "ALMOND" to "TREE_NUTS", "HAZELNUT" to "TREE_NUTS", "PISTACHIO" to "TREE_NUTS",
        "CASHEW" to "TREE_NUTS", "PINE_NUT" to "TREE_NUTS",
    )

    private val PESCETARIAN_IDS = listOf("MEAT")
    private val VEGETARIAN_IDS = listOf("MEAT", "FISH", "CRUSTACEANS", "MOLLUSCS", "GELATIN")
    private val VEGAN_IDS = VEGETARIAN_IDS + listOf("MILK", "EGG", "HONEY")
}
