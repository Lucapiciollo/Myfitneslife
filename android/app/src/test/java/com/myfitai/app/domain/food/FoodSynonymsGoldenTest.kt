package com.myfitai.app.domain.food

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden table for the curated allergen/diet lists. The check is list based, not a medical guarantee
 * (see 06-ai/providers/shared/FOOD_CONSTRAINTS_LIMITS.md): every row documents one intended decision.
 */
class FoodSynonymsGoldenTest {
    private enum class Kind { ALLERGY, INTOLERANCE, EXCLUDED }

    private fun profile(kind: Kind, constraint: String) = when (kind) {
        Kind.ALLERGY -> DietaryProfile(allergies = listOf(constraint))
        Kind.INTOLERANCE -> DietaryProfile(intolerances = listOf(constraint))
        Kind.EXCLUDED -> DietaryProfile(excludedFoods = listOf(constraint))
    }

    private fun violates(kind: Kind, constraint: String, ingredient: String): Boolean =
        FoodConstraintValidator.validateIngredientNames(listOf(ingredient), profile(kind, constraint)).isNotEmpty()

    private fun dietViolates(style: String, ingredient: String): Boolean =
        FoodConstraintValidator.validateIngredientNames(listOf(ingredient), DietaryProfile(dietStyle = style))
            .any { it.kind == "DIET_STYLE" }

    private fun assertAllViolate(kind: Kind, constraint: String, ingredients: List<String>) {
        val missed = ingredients.filterNot { violates(kind, constraint, it) }
        assertTrue("$kind '$constraint' non intercetta: $missed", missed.isEmpty())
    }

    private fun assertNoneViolate(kind: Kind, constraint: String, ingredients: List<String>) {
        val wrong = ingredients.filter { violates(kind, constraint, it) }
        assertTrue("$kind '$constraint' segnala a torto: $wrong", wrong.isEmpty())
    }

    @Test
    fun lactose_catchesDairyDerivativesNotNamedMilk() = assertAllViolate(
        Kind.INTOLERANCE, "lattosio",
        listOf(
            "Mozzarella di bufala", "Ricotta vaccina", "Parmigiano Reggiano", "Grana padano", "Pecorino romano",
            "Gorgonzola", "Mascarpone", "Stracchino", "Scamorza affumicata", "Gelato alla crema", "Kefir",
            "Panna da cucina", "Yogurt greco", "Latte intero", "Burro", "Mozzarelle", "Ricotte", "Yogurt bianco 0%",
        ),
    )

    @Test
    fun lactose_acceptsLactoseFreeAndPlantBasedProducts() = assertNoneViolate(
        Kind.INTOLERANCE, "lattosio",
        listOf(
            "Yogurt senza lattosio", "Latte delattosato", "Mozzarella delattosata", "Latte di mandorla", "Latte di cocco",
            "Latte di soia", "Latte di avena", "Burro di arachidi", "Burro di cacao", "Petto di pollo", "Riso basmati",
            "Olio extravergine di oliva", "Yogurt di soia", "Panna vegetale",
        ),
    )

    @Test
    fun milkAllergy_isNotWaivedByLactoseFreeLabel() {
        assertAllViolate(Kind.ALLERGY, "latte", listOf("Latte senza lattosio", "Mozzarella", "Ricotta", "Yogurt delattosato"))
        assertNoneViolate(Kind.ALLERGY, "latte", listOf("Latte di mandorla", "Burro di arachidi"))
    }

    @Test
    fun explicitMilkAndButterExclusions_ignorePlantBasedLookalikes() {
        assertNoneViolate(Kind.EXCLUDED, "latte", listOf("Latte di mandorla", "Latte di cocco", "Latte di soia", "Latte di avena"))
        assertNoneViolate(Kind.EXCLUDED, "burro", listOf("Burro di arachidi", "Burro di cacao", "Burro di mandorle"))
        assertAllViolate(Kind.EXCLUDED, "burro", listOf("Burro", "Burro chiarificato"))
    }

    @Test
    fun gluten_catchesCerealsAndDerivatives() = assertAllViolate(
        Kind.INTOLERANCE, "glutine",
        listOf(
            "Pasta integrale", "Pane casereccio", "Farina 00", "Cous cous", "Seitan", "Biscotti secchi", "Fiocchi di avena",
            "Orzo perlato", "Farro", "Cereali da colazione", "Spaghetti", "Pani", "Paste", "Semola di grano duro",
        ),
    )

    @Test
    fun gluten_acceptsNaturallyFreeAndLabelledFree() = assertNoneViolate(
        Kind.INTOLERANCE, "glutine",
        listOf(
            "Pasta di riso", "Pasta senza glutine", "Pane senza glutine", "Farina di mais", "Grano saraceno", "Riso basmati",
            "Risotto ai funghi", "Petto di pollo", "Patate", "Quinoa", "Grana padano", "Pasta di legumi",
        ),
    )

    @Test
    fun treeNutsGroup_catchesEveryNutAndNutDerivatives() {
        assertAllViolate(
            Kind.ALLERGY, "frutta a guscio",
            listOf("Noci", "Mandorle", "Nocciole", "Pistacchi", "Anacardi", "Pinoli", "Crema di nocciole", "Latte di mandorla", "Noce", "Mandorla"),
        )
        assertNoneViolate(
            Kind.ALLERGY, "frutta a guscio",
            listOf("Noci di cocco", "Latte di cocco", "Noce moscata", "Noce di vitello", "Noce di burro", "Burro di arachidi"),
        )
    }

    @Test
    fun literalWalnutConstraint_ignoresCoconutNutmegAndMeatCut() {
        assertAllViolate(Kind.EXCLUDED, "noci", listOf("Noci", "Noce", "Gherigli di noci"))
        assertNoneViolate(Kind.EXCLUDED, "noci", listOf("Noci di cocco", "Noce di cocco", "Noce moscata"))
    }

    @Test
    fun peanutAllergy_catchesPeanutButter() = assertAllViolate(
        Kind.ALLERGY, "arachidi", listOf("Burro di arachidi", "Arachidi tostate", "Arachide", "Noccioline"),
    )

    @Test
    fun shellfishMolluscsAndFish_areSeparateGroups() {
        assertAllViolate(Kind.ALLERGY, "crostacei", listOf("Gamberi", "Gamberetti", "Scampi", "Aragosta", "Granchio"))
        assertNoneViolate(Kind.ALLERGY, "crostacei", listOf("Cozze", "Salmone"))
        assertAllViolate(Kind.ALLERGY, "molluschi", listOf("Cozze", "Vongole", "Polpo", "Calamari", "Seppia"))
        assertNoneViolate(Kind.ALLERGY, "molluschi", listOf("Gamberi", "Salmone"))
        assertAllViolate(Kind.ALLERGY, "pesce", listOf("Tonno al naturale", "Salmone", "Merluzzo", "Filetto di orata", "Pesce spada"))
        assertNoneViolate(Kind.ALLERGY, "pesce", listOf("Gamberi", "Petto di pollo", "Pesca"))
    }

    @Test
    fun otherMajorAllergens_haveCuratedAliases() {
        assertAllViolate(Kind.ALLERGY, "sesamo", listOf("Semi di sesamo", "Tahina", "Hummus"))
        assertAllViolate(Kind.ALLERGY, "sedano", listOf("Sedano rapa", "Gambo di sedano"))
        assertAllViolate(Kind.ALLERGY, "senape", listOf("Senape di Digione", "Mostarda"))
        assertAllViolate(Kind.ALLERGY, "lupini", listOf("Lupini", "Farina di lupino"))
        assertAllViolate(Kind.ALLERGY, "soia", listOf("Tofu", "Edamame", "Salsa di soia", "Latte di soia", "Tempeh"))
        assertNoneViolate(Kind.ALLERGY, "soia", listOf("Riso", "Petto di pollo"))
    }

    @Test
    fun singularAndPluralAreInterchangeable() {
        assertAllViolate(Kind.EXCLUDED, "pomodoro", listOf("Pomodori maturi", "Pomodoro"))
        assertAllViolate(Kind.EXCLUDED, "pomodori", listOf("Pomodoro ciliegino", "Pomodori"))
        assertAllViolate(Kind.EXCLUDED, "uovo", listOf("Uova strapazzate", "Uovo sodo"))
        assertAllViolate(Kind.EXCLUDED, "gamberetti", listOf("Gamberetto", "Gamberetti"))
        assertNoneViolate(Kind.EXCLUDED, "grano", listOf("Grana padano"))
    }

    @Test
    fun constraintsWrittenAsSentencesAreUnderstood() {
        assertAllViolate(Kind.ALLERGY, "allergia al glutine", listOf("Pasta", "Pane"))
        assertAllViolate(Kind.ALLERGY, "senza glutine", listOf("Pasta"))
        assertAllViolate(Kind.INTOLERANCE, "intolleranza al lattosio", listOf("Mozzarella", "Yogurt"))
        assertNoneViolate(Kind.INTOLERANCE, "intolleranza al lattosio", listOf("Yogurt senza lattosio"))
        assertAllViolate(Kind.ALLERGY, "Allergia alle Noci", listOf("Noci"))
        assertAllViolate(Kind.ALLERGY, "allergia ai crostacei", listOf("Gamberi"))
    }

    @Test
    fun accentsAndCaseAreIgnored() {
        assertAllViolate(Kind.EXCLUDED, "PESCE", listOf("pesce spada"))
        assertAllViolate(Kind.INTOLERANCE, "lattosio", listOf("RICOTTA", "Panna"))
        assertAllViolate(Kind.INTOLERANCE, "glutine", listOf("Pancarr\u00E8", "Pan carr\u00E9"))
    }

    @Test
    fun riceDishViolatesRiceButNotGlutenConstraint() {
        assertAllViolate(Kind.EXCLUDED, "riso", listOf("Riso basmati", "Risotto ai funghi"))
        assertNoneViolate(Kind.INTOLERANCE, "glutine", listOf("Risotto ai funghi"))
    }

    @Test
    fun vegetarian_excludesCuredMeatGameAndGelatin() {
        val banned = listOf(
            "Lardo", "Pancetta", "Speck", "Salame", "Mortadella", "Wurstel", "Prosciutto crudo", "Bresaola", "Agnello",
            "Coniglio", "Anatra", "Gelatina", "Petto di pollo", "Salmone", "Gamberi", "Tonno",
        )
        val missed = banned.filterNot { dietViolates("Vegetariano", it) }
        assertTrue("vegetariano non intercetta: $missed", missed.isEmpty())
        val allowed = listOf("Tofu", "Mozzarella", "Uova", "Miele", "Ricotta", "Riso", "Lenticchie", "Wurstel vegetale")
        val wrong = allowed.filter { dietViolates("Vegetariano", it) }
        assertTrue("vegetariano segnala a torto: $wrong", wrong.isEmpty())
    }

    @Test
    fun vegan_excludesEveryAnimalProduct() {
        val banned = listOf("Burro", "Panna", "Mozzarella", "Ricotta", "Parmigiano", "Uova", "Miele", "Yogurt", "Prosciutto", "Salmone", "Gelatina")
        val missed = banned.filterNot { dietViolates("Vegano", it) }
        assertTrue("vegano non intercetta: $missed", missed.isEmpty())
        val allowed = listOf("Tofu", "Latte di avena", "Burro di arachidi", "Pasta", "Lenticchie", "Latte di mandorla")
        val wrong = allowed.filter { dietViolates("Vegano", it) }
        assertTrue("vegano segnala a torto: $wrong", wrong.isEmpty())
    }

    @Test
    fun pescetarian_excludesOnlyMeat() {
        val banned = listOf("Pollo", "Prosciutto", "Bresaola", "Manzo", "Pancetta", "Agnello")
        val missed = banned.filterNot { dietViolates("Pescetariano", it) }
        assertTrue("pescetariano non intercetta: $missed", missed.isEmpty())
        val allowed = listOf("Salmone", "Gamberi", "Tonno", "Uova", "Mozzarella", "Tofu", "Cozze")
        val wrong = allowed.filter { dietViolates("Pescetariano", it) }
        assertTrue("pescetariano segnala a torto: $wrong", wrong.isEmpty())
    }

    @Test
    fun noConstraintsMeansNoViolations() {
        assertEquals(emptyList<FoodConstraintValidator.Violation>(), FoodConstraintValidator.validateIngredientNames(listOf("Mozzarella", "Pasta"), DietaryProfile()))
    }

    // ---- Round 2 (revisore): vincoli non in elenco, etichette vegane, falsi positivi, falsi negativi residui ----

    @Test
    fun unlistedMultiWordConstraints_useGroupExpansionOrPrudentTermMatching() {
        assertAllViolate(Kind.INTOLERANCE, "latticini e derivati", listOf("Mozzarella", "Yogurt greco", "Burro", "Latte intero", "Panna"))
        assertAllViolate(Kind.ALLERGY, "latte e derivati", listOf("Ricotta", "Parmigiano", "Latte scremato", "Burro"))
        assertAllViolate(Kind.EXCLUDED, "latte e derivati", listOf("Ricotta", "Parmigiano", "Latte scremato", "Burro"))
        assertAllViolate(Kind.ALLERGY, "frutta con guscio", listOf("Noci", "Mandorle", "Pistacchi", "Nocciole"))
        assertAllViolate(Kind.ALLERGY, "pesce azzurro", listOf("Sardine", "Sgombro", "Acciughe", "Alici"))
        assertNoneViolate(Kind.ALLERGY, "pesce azzurro", listOf("Petto di pollo", "Riso"))
    }

    @Test
    fun parentheticalConstraintSplitByCommaStillWorks() {
        val profile = DietaryProfile(allergies = DietaryProfile.csv("frutta a guscio (noci, mandorle)"))
        val violations = FoodConstraintValidator.validateIngredientNames(listOf("Noci", "Mandorle", "Pistacchi", "Riso"), profile)
        assertTrue(violations.any { it.ingredient == "Noci" })
        assertTrue(violations.any { it.ingredient == "Mandorle" })
        assertTrue(violations.any { it.ingredient == "Pistacchi" })
        assertTrue(violations.none { it.ingredient == "Riso" })
    }

    @Test
    fun unknownConstraintFallsBackToEverySignificantWord() {
        assertAllViolate(Kind.ALLERGY, "kiwi giallo", listOf("Kiwi", "Kiwi giallo", "Macedonia con kiwi"))
        assertNoneViolate(Kind.ALLERGY, "kiwi giallo", listOf("Mela", "Riso"))
        assertAllViolate(Kind.INTOLERANCE, "cavolfiore e broccoli", listOf("Cavolfiore", "Broccoli al vapore"))
    }

    @Test
    fun veganLabelOnlyWaivesTheAdjacentWord() {
        assertAllViolate(Kind.ALLERGY, "pesce", listOf("Insalata di vegetali con tonno"))
        assertAllViolate(Kind.INTOLERANCE, "latticini", listOf("Burger vegetale con formaggio", "Insalata vegetale e mozzarella"))
        assertNoneViolate(Kind.INTOLERANCE, "latticini", listOf("Formaggio vegetale", "Formaggio a base vegetale", "Yogurt vegetale", "Bevanda vegana"))
        assertTrue(dietViolates("Vegetariano", "Burger vegetale con prosciutto"))
        assertTrue(!dietViolates("Vegetariano", "Hamburger vegetale"))
        assertTrue(!dietViolates("Vegano", "Formaggio vegano"))
    }

    @Test
    fun freeFromLabelAlsoOnlyWaivesTheAdjacentWord() {
        assertNoneViolate(Kind.INTOLERANCE, "lattosio", listOf("Yogurt greco senza lattosio", "Yogurt (non contiene lattosio)", "Yogurt privo di lattosio"))
        assertAllViolate(Kind.INTOLERANCE, "lattosio", listOf("Yogurt senza lattosio con burro e mozzarella"))
        assertNoneViolate(Kind.INTOLERANCE, "glutine", listOf("Spaghetti senza glutine", "Pane (non contiene glutine)", "Cracker privo di glutine"))
    }

    @Test
    fun riceBasedProductsAreNotGluten() = assertNoneViolate(
        Kind.INTOLERANCE, "glutine",
        listOf("Pasta di riso", "Cracker di riso", "Gallette di riso", "Biscotti di riso", "Pane di riso", "Fette biscottate di riso", "Farina di riso"),
    )

    @Test
    fun certainResidualFalseNegativesAreCovered() {
        assertAllViolate(Kind.ALLERGY, "molluschi", listOf("Polipo", "Insalata di polipo"))
        assertAllViolate(Kind.INTOLERANCE, "glutine", listOf("Cotoletta", "Panko", "Bagel", "Salsa di soia", "Tiramisu"))
        assertAllViolate(Kind.ALLERGY, "soia", listOf("Salsa di soia"))
        assertAllViolate(Kind.INTOLERANCE, "lattosio", listOf("Cappuccino", "Tiramisu"))
        assertAllViolate(Kind.ALLERGY, "uova", listOf("Tiramisu"))
        listOf("Cotoletta", "Ragu", "Polpette", "Carpaccio di manzo", "Polpette al sugo").forEach {
            assertTrue("vegetariano non intercetta $it", dietViolates("Vegetariano", it))
            assertTrue("pescetariano non intercetta $it", dietViolates("Pescetariano", it))
        }
        assertTrue(!dietViolates("Pescetariano", "Polpette di pesce"))
        assertTrue(!dietViolates("Vegetariano", "Polpette di melanzane"))
        assertTrue(!dietViolates("Vegetariano", "Carpaccio di zucchine"))
        assertTrue(!dietViolates("Vegetariano", "Ragu di lenticchie"))
    }

    @Test
    fun legacyStyleKeyIsMappedToDietStyle() {
        val profile = DietaryProfile.parse("{\"style\":\"vegano\"}")
        assertEquals(DietaryProfile.Status.OK, profile.status)
        assertEquals("vegano", profile.dietStyle)
        assertTrue(dietViolates("vegano", "Burro"))
        assertEquals("Vegetariano", DietaryProfile.parse("{\"dietStyle\":\"Vegetariano\",\"style\":\"vegano\"}").dietStyle)
    }
}