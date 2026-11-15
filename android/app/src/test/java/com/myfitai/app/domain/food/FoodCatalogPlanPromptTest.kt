package com.myfitai.app.domain.food

import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.catalog
import com.myfitai.app.domain.food.catalog.CatalogConstraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodCatalogPlanPromptTest {

    private fun block(constraints: CatalogConstraints) = FoodCatalogPlanPrompt.catalogBlock(catalog, constraints)

    private fun ids(block: String): List<String> =
        block.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("CAT:") }.map { it.substringBefore('|') }.toList()

    @Test
    fun catalogBlock_isDeterministicAndSortedByCategoryThenId() {
        val constraints = CatalogConstraints(profile = DietaryProfile(), month = 11)
        val first = block(constraints)
        assertEquals(first, block(constraints))
        val entries = FoodCatalogPlanPrompt.subset(catalog, constraints)
        assertEquals(entries.sortedWith(compareBy({ it.category }, { it.id })), entries)
        assertEquals(entries.map { it.id }, ids(first))
        assertTrue(first.startsWith("CAT:v${catalog.version}|${entries.size}|id|name|state|kcal100|P|C|F|minG-maxG|stepG|units\n"))
        // Un'intestazione di categoria per gruppo, mai ripetuta.
        val headers = first.lineSequence().filter { it.startsWith("#") }.toList()
        assertEquals(headers.distinct(), headers)
        assertEquals(entries.map { it.category }.distinct().map { "#$it" }, headers)
    }

    @Test
    fun everyLineHasTenFieldsAndReferencesARealFood() {
        val lines = block(CatalogConstraints()).lineSequence().filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("CAT:") }.toList()
        assertTrue(lines.isNotEmpty())
        lines.forEach { line ->
            val parts = line.split('|')
            assertEquals(line, 10, parts.size)
            val food = catalog.byId(parts[0])!!
            assertEquals(Math.round(food.per100.kcal).toString(), parts[3])
            assertTrue(line, parts[2] in setOf("R", "C", "S", "-"))
            assertTrue(line, Regex("[0-9.]+-[0-9.]+").matches(parts[7]))
        }
        assertTrue(lines.any { it.startsWith("it.olio_evo|Olio extravergine d'oliva|S|884|0|0|100|4.5-27|4.5|cucchiaio=13.5;cucchiaino=4.5") })
    }

    @Test
    fun allergensAndDietStyle_areFilteredOutOfTheCatalogBlock() {
        val lactoseAndNuts = ids(block(CatalogConstraints(profile = DietaryProfile(intolerances = listOf("lattosio"), allergies = listOf("frutta a guscio")))))
        assertFalse("it.latte_intero" in lactoseAndNuts)
        assertFalse("it.parmigiano" in lactoseAndNuts)
        assertFalse("it.mandorle" in lactoseAndNuts)
        assertFalse("it.noci" in lactoseAndNuts)
        assertTrue("it.petto_pollo" in lactoseAndNuts)

        val vegan = ids(block(CatalogConstraints(profile = DietaryProfile(dietStyle = "vegano"))))
        assertFalse("it.petto_pollo" in vegan)
        assertFalse("it.uovo" in vegan)
        assertTrue("it.lenticchie_secche" in vegan)

        val celiac = ids(block(CatalogConstraints(profile = DietaryProfile(allergies = listOf("glutine")))))
        assertFalse("it.pasta_semola_secca" in celiac)
        assertTrue("it.riso_basmati" in celiac)
    }

    @Test
    fun seasonFiltersTheBlock() {
        val july = ids(block(CatalogConstraints(month = 7)))
        val january = ids(block(CatalogConstraints(month = 1)))
        assertTrue("it.zucchine" in july)
        assertFalse("it.zucchine" in january)
        assertTrue("it.mela" in january)
    }

    @Test
    fun systemPrompt_isTheClosedVocabularyContractWithoutNumbersFromTheModel() {
        val prompt = FoodCatalogPlanPrompt.SYSTEM_PROMPT
        assertTrue(prompt.contains("beginning with the exact line `MFP2`"))
        assertTrue(prompt.contains("CLOSED VOCABULARY"))
        assertTrue(prompt.contains("NEVER write kcal, protein, carbs, fat, totals, doses, units or any nutritional number"))
        assertTrue(prompt.contains("caloric condiment or drink cannot be omitted"))
        assertTrue(prompt.contains("WITHOUT any quantity"))
        assertTrue(prompt.contains(FoodCatalogPlanContract.PROTOCOL))
        // MFP1 vecchio protocollo non presente.
        assertFalse(prompt.contains("MFP1"))
        assertFalse(prompt.contains("displayDose|weightState"))
    }

    /** Misura la dimensione del prompt con tre profili tipo (stampa sullo standard output del test). */
    @Test
    fun promptSizeIsMeasuredForThreeProfiles() {
        val profiles = listOf(
            "P1 nessun vincolo, novembre" to CatalogConstraints(profile = DietaryProfile(), month = 11),
            "P2 vegano senza glutine, luglio" to CatalogConstraints(profile = DietaryProfile(dietStyle = "vegano", allergies = listOf("glutine")), month = 7),
            "P3 lattosio e frutta a guscio, marzo" to CatalogConstraints(profile = DietaryProfile(intolerances = listOf("lattosio"), allergies = listOf("frutta a guscio")), month = 3),
        )
        val system = FoodCatalogPlanPrompt.SYSTEM_PROMPT
        println("PROMPT_SIZE system chars=${system.length} estTokens~${system.length / 4}-${system.length / 3}")
        profiles.forEach { (name, constraints) ->
            val catalogBlock = FoodCatalogPlanPrompt.catalogBlock(catalog, constraints)
            val entries = FoodCatalogPlanPrompt.subset(catalog, constraints).size
            val total = system.length + catalogBlock.length
            println("PROMPT_SIZE $name entries=$entries catalogChars=${catalogBlock.length} estTokens~${catalogBlock.length / 4}-${catalogBlock.length / 3} systemPlusCatalogChars=$total estTokens~${total / 4}-${total / 3}")
            assertTrue(name, entries > 50)
            // Budget dichiarato: il blocco di catalogo non deve superare 40.000 caratteri.
            assertTrue("$name catalog block too large: ${catalogBlock.length}", catalogBlock.length < 40_000)
        }
    }
}
