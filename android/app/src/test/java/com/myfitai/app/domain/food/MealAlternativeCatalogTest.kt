package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiContractRegistry
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProvider
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRawResponse
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.toDraft
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.catalog
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.envelope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.math.abs

/** Lotto MACRO L5a: alternative pasto MA2 (MFP2 ridotto) coerenti con il catalogo. Provider finto, nessuna rete. */
class MealAlternativeCatalogTest {
    private val engine = MealAlternativeCatalogEngine(catalog)
    private val builder = FoodCatalogPlanBuilder(catalog)
    private val week = FoodCatalogPlanFixtures.monday.toEpochDay()
    private val day = FoodCatalogPlanFixtures.day.toEpochDay()

    // ---- fixture -----------------------------------------------------------------------------------------------

    private class Alt(val title: String, val prep: String, val items: List<Pair<String, Double>>)

    private fun kcalOf(id: String, grams: Double) = catalog.byId(id)!!.per100.kcal * grams / 100.0

    /** Alternativa la cui PROPOSTA vale [total] kcal: i grammi di [flexId] compensano gli altri alimenti. */
    private fun tuned(title: String, prep: String, flexId: String, fixed: List<Pair<String, Double>>, total: Double = 600.0): Alt {
        val rest = total - fixed.sumOf { kcalOf(it.first, it.second) }
        val flexGrams = Math.round(rest / catalog.byId(flexId)!!.per100.kcal * 100.0 * 10.0) / 10.0
        return Alt(title, prep, listOf(flexId to flexGrams) + fixed)
    }

    /** Cinque alternative da ~600 kcal; nessun alimento supera il 1,4x entro il massimo di porzione. */
    private fun fiveAlternatives(total: Double = 600.0): List<Alt> = listOf(
        tuned("Riso e salmone", "Cuoci il riso per 10 minuti e il salmone in padella.", "it.riso_basmati",
            listOf("it.salmone" to 100.0, "it.insalata_mista" to 150.0, "it.olio_evo" to 5.0), total),
        tuned("Pasta al tonno", "Cuoci la pasta e condiscila con il tonno.", "it.pasta_semola_secca",
            listOf("it.tonno_naturale" to 180.0, "it.insalata_mista" to 200.0, "it.olio_evo" to 10.0), total),
        tuned("Couscous con tacchino", "Idrata il couscous e rosola il tacchino.", "it.couscous",
            listOf("it.petto_tacchino" to 150.0, "it.pomodorini" to 150.0, "it.olio_evo" to 10.0), total),
        tuned("Quinoa e merluzzo", "Lessa la quinoa e inforna il merluzzo.", "it.quinoa",
            listOf("it.merluzzo" to 200.0, "it.fagiolini" to 150.0, "it.olio_evo" to 10.0), total),
        tuned("Riso e pollo", "Cuoci il riso e griglia il pollo.", "it.riso_basmati",
            listOf("it.petto_pollo" to 150.0, "it.fagiolini" to 150.0, "it.olio_evo" to 10.0), total),
    )

    private fun payload(alts: List<Alt>, week: Long = this.week, day: Long = this.day, extra: List<String> = emptyList()): String =
        buildList {
            add("MFP2")
            add("W|$week")
            add("D|$day")
            alts.forEach { alt ->
                add("M|LUNCH|${alt.title}|780|${alt.prep}")
                alt.items.forEach { (id, grams) -> add("I|$id|${String.format(Locale.US, "%.1f", grams)}") }
            }
            addAll(extra)
            add("V|1|ok")
        }.joinToString("\n")

    private fun foodMeal(
        generated: NutritionPlanContract.GeneratedMeal,
        id: Long = 7L,
        source: String = FoodMeal.NUTRITION_SOURCE_COMPUTED,
        kcal: Int? = generated.kcal,
        protein: Float? = generated.proteinG,
    ) = FoodMeal(
        id = id, dayId = 1L, sortOrder = 0, type = generated.type, title = generated.title, timeMinutes = generated.timeMinutes,
        kcal = kcal, proteinG = protein, carbsG = generated.carbsG, fatG = generated.fatG, preparation = generated.preparation,
        ingredients = generated.ingredients.mapIndexed { i, g ->
            FoodIngredient(
                100L + i, id, g.name, g.quantity, g.unit, g.displayDose, g.weightState, g.nutritionConfidence, g.category, i,
                g.foodId, g.grams, g.kcal, g.proteinG, g.carbsG, g.fatG, g.fiberG, g.sodiumMg,
            )
        },
        nutritionSource = source,
    )

    /** Pasto COMPUTED corrente: pasta, pollo, olio, insalata (~670 kcal). */
    private val currentGenerated = builder.computedMeal(
        "LUNCH", "Pasta con pollo", 780, "Cuoci la pasta e rosola il pollo.",
        listOf(
            catalog.byId("it.pasta_semola_secca")!! to 90.0,
            catalog.byId("it.petto_pollo")!! to 150.0,
            catalog.byId("it.olio_evo")!! to 15.0,
            catalog.byId("it.insalata_mista")!! to 150.0,
        ),
    )
    private val current = foodMeal(currentGenerated)

    private fun input(meal: FoodMeal = current, profile: DietaryProfile = DietaryProfile()) =
        MealAlternativeCatalogEngine.Input(week, day, meal, profile)

    private fun rejection(json: String, input: MealAlternativeCatalogEngine.Input = input(), attempt: Int = 0): String? =
        runCatching { engine.evaluate(json, input, attempt) }.exceptionOrNull()?.message

    private fun gateway(prompts: MutableList<String>, vararg responses: String): AiRuntimeGateway {
        val queue = ArrayDeque(responses.toList())
        val provider = object : AiProvider {
            override val type = AiProviderType.OPENAI
            override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
                prompts += request.userPrompt
                return AiRawResponse(type, "fake", queue.removeFirst())
            }
        }
        val service = AiExecutionService(diagnostics = {})
        return object : AiRuntimeGateway {
            override suspend fun execute(
                request: AiStructuredRequest,
                maxSchemaRetries: Int,
                businessValidator: (String) -> Result<Unit>,
            ) = service.execute(provider, request, maxSchemaRetries, businessValidator)
        }
    }

    // ---- 1. alternativa valida entro il 3% ---------------------------------------------------------------------

    @Test
    fun validProposal_isComputedFromTheCatalogWithinThreePercentOfTheCurrentMeal() {
        val alternatives = engine.evaluate(envelope(payload(fiveAlternatives())), input(), attempt = 0)

        assertEquals(5, alternatives.size)
        val target = current.kcal!!.toDouble()
        alternatives.forEach { alt ->
            assertTrue("kcal=${alt.kcal} target=$target", abs(alt.kcal - target) / target <= 0.03)
            assertEquals(catalog.version, alt.catalogVersion)
            assertTrue(alt.reason.isEmpty() || alt.reason.startsWith("Proteine "))
            // Snapshot ricostruibile: foodId + grammi per ogni ingrediente, dose pratica e nome del catalogo.
            alt.ingredients.forEach { ingredient ->
                val food = catalog.byId(ingredient.foodId!!)!!
                assertTrue(ingredient.grams!! > 0f)
                assertTrue(ingredient.name.startsWith(food.name))
                assertTrue(ingredient.displayDose.isNotBlank())
            }
            // Pasto = somma degli ingredienti calcolati dal catalogo (stessa aritmetica del piano MFP2).
            val rebuilt = engine.rebuild(alt, input())
            assertEquals(FoodMeal.NUTRITION_SOURCE_COMPUTED, rebuilt.nutritionSource)
            assertEquals(alt.kcal, rebuilt.kcal)
            assertEquals(alt.proteinG.toDouble(), rebuilt.proteinG.toDouble(), 0.001)
            assertEquals(rebuilt.kcal.toDouble(), rebuilt.ingredients.sumOf { it.kcal!!.toDouble() }, 0.51)
            assertEquals(rebuilt.proteinG.toDouble(), rebuilt.ingredients.sumOf { it.proteinG!!.toDouble() }, 0.06)
            assertEquals(rebuilt.carbsG.toDouble(), rebuilt.ingredients.sumOf { it.carbsG!!.toDouble() }, 0.06)
            assertEquals(rebuilt.fatG.toDouble(), rebuilt.ingredients.sumOf { it.fatG!!.toDouble() }, 0.06)
            rebuilt.ingredients.forEach { ingredient ->
                val food = catalog.byId(ingredient.foodId!!)!!
                assertEquals(food.per100.kcal * ingredient.grams!! / 100.0, ingredient.kcal!!.toDouble(), 0.01)
            }
        }
        // Deterministico: stessa busta, stesse alternative.
        assertEquals(alternatives, engine.evaluate(envelope(payload(fiveAlternatives())), input(), attempt = 0))
    }

    @Test
    fun titleIsCoherentWithTheIngredients() {
        val alts = fiveAlternatives().toMutableList()
        // Titolo che cita un alimento assente (pasta, salmone) sopra ingredienti di riso e pollo.
        alts[4] = Alt("Pasta al salmone", alts[4].prep, alts[4].items)
        val result = engine.evaluate(envelope(payload(alts)), input(), attempt = 0)
        val title = result.first { alt -> alt.ingredients.any { it.foodId == "it.petto_pollo" } }.title
        assertFalse(title, title.contains("salmone", ignoreCase = true) || title.contains("pasta", ignoreCase = true))
        assertTrue(title, title.contains("pollo", ignoreCase = true))
    }

    // ---- 2. fuori tolleranza: rifiutata con motivo, retry al 4%, poi si riparte dal 3% --------------------------

    @Test
    fun outsideThreeButInsideFourPercent_isRejectedFirst_thenAcceptedOnTheRetry_andThreePercentIsRestored() = runBlocking {
        // Ogni proposta vale 600 kcal e puo' arrivare al massimo a 1,4x = 840 (limite per pasto). Pasto attuale da 870 kcal:
        // 840 e' a -3,45% (fuori dal 3%, dentro il 4%).
        val big = foodMeal(currentGenerated, kcal = 870, protein = 0f).copy(carbsG = 0f, fatG = 0f)
        val body = envelope(payload(fiveAlternatives()))

        val first = rejection(body, input(big), attempt = 0)
        assertNotNull(first)
        assertTrue(first!!, first.startsWith("TARGET_TOLERANCE_EXCEEDED:1:target=870;actual="))
        assertTrue(first, first.contains(";reach="))

        val prompts = mutableListOf<String>()
        val generated = engine.generate(gateway(prompts, body, body), input(big))
        assertEquals(2, prompts.size)
        assertTrue(prompts[1], prompts[1].contains("FIX:TARGET_TOLERANCE_EXCEEDED:1:target=870;actual="))
        assertTrue(generated.items.size >= MealAlternativeCatalogEngine.MIN_ALTERNATIVES)
        generated.items.forEach { assertTrue("kcal=${it.kcal}", abs(it.kcal - 870.0) / 870.0 <= 0.04 + 1e-9) }
        // Con i retry accettati al 4% almeno una alternativa sta tra 3% e 4% (altrimenti il primo tentativo sarebbe passato).
        assertTrue(generated.items.any { abs(it.kcal - 870.0) / 870.0 > 0.03 })

        // Ripristino: una nuova generazione riparte dal 3% (tentativo 0) e rifiuta di nuovo la stessa proposta.
        assertTrue(rejection(body, input(big), attempt = 0)!!.startsWith("TARGET_TOLERANCE_EXCEEDED"))
    }

    @Test
    fun unreachableKcal_isRejectedAtBothTolerances_withTheReachableRange() {
        val huge = foodMeal(currentGenerated, kcal = 1500)
        val body = envelope(payload(fiveAlternatives()))
        listOf(0, 1).forEach { attempt ->
            val message = rejection(body, input(huge), attempt)
            assertTrue("attempt=$attempt message=$message", message!!.startsWith("TARGET_TOLERANCE_EXCEEDED:1:target=1500;actual="))
            assertTrue(message.length <= AiExecutionService.FIX_TOLERANCE_REASON_MAX_CHARS)
        }
    }

    // ---- 3. vocabolario chiuso e vincoli per TAG ---------------------------------------------------------------

    @Test
    fun unknownFoodId_isRejected() {
        val alts = fiveAlternatives()
        val bad = payload(alts).replace("it.salmone", "it.inventato")
        assertEquals("PIPE_I_FOOD_UNKNOWN:it.inventato", rejection(envelope(bad)))
    }

    @Test
    fun allergenFoundByCatalogTag_isRejectedForTheWholeAttempt() {
        val profile = DietaryProfile(allergies = listOf("pesce"))
        // Salmone, tonno e merluzzo hanno il tag FISH: il rifiuto non dipende dal nome scritto dal modello.
        val message = rejection(envelope(payload(fiveAlternatives())), input(profile = profile))
        assertNotNull(message)
        assertTrue(message!!, message.startsWith("FOOD_CONSTRAINT_VIOLATION:it."))
        // Nessuna alternativa passa anche se le altre sarebbero valide: il rifiuto e' sull'intero tentativo.
        val request = engine.request(input(profile = profile))
        assertFalse(request.userPrompt.contains("it.salmone|"))
        assertTrue(request.userPrompt.contains("it.riso_basmati|"))
    }

    @Test
    fun structureErrors_haveStableCodes() {
        val ok = payload(fiveAlternatives())
        assertEquals("MA2_WEEK_MISMATCH", rejection(envelope(payload(fiveAlternatives(), week = week + 7))))
        assertEquals("MA2_DAY_MISMATCH", rejection(envelope(payload(fiveAlternatives(), day = day + 1))))
        assertEquals("MA2_SUPPLEMENT_NOT_ALLOWED", rejection(envelope(payload(fiveAlternatives(), extra = listOf("S|PROTEIN_POWDER|it.riso_basmati|20|900|x")))))
        assertEquals("PIPE_DAY_MUST_HAVE_5_MEALS", rejection(envelope(ok.lineSequence().filterNot { it.startsWith("I|it.merluzzo") || it.startsWith("M|LUNCH|Quinoa") }.joinToString("\n"))))
    }

    // ---- 4. applicazione: ricostruzione e riverifica -----------------------------------------------------------

    @Test
    fun rebuild_rejectsTamperedOrInconsistentAlternatives() {
        val alt = engine.evaluate(envelope(payload(fiveAlternatives())), input(), attempt = 0).first()
        assertNotNull(engine.rebuild(alt, input()))

        fun assertInvalid(label: String, candidate: MealAlternativeContract.Alternative, profile: DietaryProfile = DietaryProfile()) {
            try {
                engine.rebuild(candidate, input(profile = profile))
                fail("$label: expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // atteso
            }
        }
        assertInvalid("kcal cambiate", alt.copy(kcal = alt.kcal + 10))
        assertInvalid("versione catalogo", alt.copy(catalogVersion = 999))
        assertInvalid("dichiarata dal modello", alt.copy(catalogVersion = null))
        assertInvalid("foodId assente", alt.copy(ingredients = alt.ingredients.map { it.copy(foodId = null) }))
        assertInvalid("alimento ignoto", alt.copy(ingredients = alt.ingredients.map { it.copy(foodId = "it.inventato") }))
        assertInvalid("grammi triplicati", alt.copy(ingredients = alt.ingredients.map { it.copy(grams = it.grams!! * 3f) }))
        val fish = engine.evaluate(envelope(payload(fiveAlternatives())), input(), attempt = 0).first { a -> a.ingredients.any { it.foodId == "it.salmone" } }
        assertInvalid("allergene", fish, DietaryProfile(allergies = listOf("pesce")))
    }

    // ---- 5. richiesta e contratto ------------------------------------------------------------------------------

    @Test
    fun request_usesTheRegisteredMfp2EnvelopeAndTheClosedVocabulary() {
        val request = engine.request(input())
        assertNotNull(AiContractRegistry.find(request.schemaName))
        assertEquals(FoodCatalogPlanContract.SCHEMA_NAME, request.schemaName)
        assertTrue(request.systemPrompt.contains("MFP2"))
        assertTrue(request.systemPrompt.contains("NEVER write kcal"))
        assertTrue(request.userPrompt.contains("TARGET_KCAL:${current.kcal}"))
        assertTrue(request.userPrompt.contains("WEEK:$week"))
        assertTrue(request.userPrompt.contains("DAY:$day"))
        assertTrue(request.userPrompt.contains("CURRENT_FOODS:it.pasta_semola_secca:"))
        assertTrue(request.userPrompt.contains("CAT:v${catalog.version}|"))
    }

    @Test
    fun declaredAlternativesKeepTheirShapeAndAreNotComputed() {
        val legacy = MealAlternativeContract.Alternative("Riso", 300, 10f, 50f, 5f, "", "", listOf(MealAlternativeContract.Ingredient("Riso", 80f, "g", "80 g", "crudo", "A", "cereali")))
        assertNull(legacy.catalogVersion)
        assertNull(legacy.ingredients.single().foodId)
        assertNull(legacy.ingredients.single().grams)
    }

    // ---- 6. cronologia -----------------------------------------------------------------------------------------

    @Test
    fun historicalComputedMeal_staysComputed_withTotalsEqualToTheSumOfTheSnapshot() {
        // Totali del pasto storico non allineati (es. arrotondati diversamente): il nuovo pasto li ricalcola dagli ingredienti.
        val history = foodMeal(currentGenerated, id = 55L, kcal = currentGenerated.kcal + 25, protein = currentGenerated.proteinG + 9f)
        val source = foodMeal(currentGenerated, id = 7L)

        val replacement = MealAlternativeCatalogEngine.historicalReplacement(history, source)

        assertEquals(FoodMeal.NUTRITION_SOURCE_COMPUTED, replacement.nutritionSource)
        assertEquals(history.ingredients.sumOf { it.kcal!!.toDouble() }.let { Math.round(it).toInt() }, replacement.kcal)
        assertEquals(history.ingredients.sumOf { it.proteinG!!.toDouble() }, replacement.proteinG!!.toDouble(), 0.06)
        assertEquals(history.ingredients.sumOf { it.carbsG!!.toDouble() }, replacement.carbsG!!.toDouble(), 0.06)
        assertEquals(history.ingredients.sumOf { it.fatG!!.toDouble() }, replacement.fatG!!.toDouble(), 0.06)
        assertEquals(replacement.kcal!!.toDouble(), replacement.ingredients.sumOf { it.kcal!!.toDouble() }, 0.51)
        // Snapshot copiato tale e quale (foodId, grammi, kcal, macro) dal mapper unico.
        assertEquals(history.ingredients.map { it.toDraft() }, replacement.ingredients)
        assertTrue(replacement.ingredients.all { it.foodId != null && it.grams != null && it.kcal != null })
        // Tipo e orario restano quelli del pasto sostituito.
        assertEquals(source.type, replacement.type)
        assertEquals(source.timeMinutes, replacement.timeMinutes)
        assertEquals(history.title, replacement.title)
    }

    @Test
    fun historicalDeclaredMeal_isUnchanged_andIncompleteSnapshotStaysDeclared() {
        val declaredIngredients = listOf(
            FoodIngredient(1L, 55L, "Riso", 80f, "g", "80 g", "crudo", "A", "cereali", 0),
            FoodIngredient(2L, 55L, "Pollo", 150f, "g", "150 g", "crudo", "A", "carne", 1),
        )
        val declared = FoodMeal(55L, 1L, 0, "LUNCH", "Riso e pollo", 780, 640, 41f, 70f, 12f, "Cuoci.", declaredIngredients)
        val source = foodMeal(currentGenerated)

        val replacement = MealAlternativeCatalogEngine.historicalReplacement(declared, source)

        assertEquals(FoodMeal.NUTRITION_SOURCE_DECLARED, replacement.nutritionSource)
        assertEquals(640, replacement.kcal)
        assertEquals(41f, replacement.proteinG)
        assertEquals(70f, replacement.carbsG)
        assertEquals(12f, replacement.fatG)
        assertEquals(declaredIngredients.map { it.toDraft() }, replacement.ingredients)
        assertTrue(replacement.ingredients.all { it.foodId == null && it.kcal == null })

        // COMPUTED con uno snapshot incompleto: non si inventano numeri, resta dichiarato con i totali dichiarati.
        val partial = foodMeal(currentGenerated, id = 56L).let { meal ->
            meal.copy(ingredients = meal.ingredients.mapIndexed { i, ing -> if (i == 0) ing.copy(kcal = null) else ing })
        }
        val fallback = MealAlternativeCatalogEngine.historicalReplacement(partial, source)
        assertEquals(FoodMeal.NUTRITION_SOURCE_DECLARED, fallback.nutritionSource)
        assertEquals(partial.kcal, fallback.kcal)
    }

    // ---- 7. mapper unico ---------------------------------------------------------------------------------------

    @Test
    fun generatedMealMapper_propagatesEveryProperty() {
        val meal = currentGenerated
        val draft = meal.toDraft()
        assertEquals(meal.nutritionSource, draft.nutritionSource)
        assertEquals(meal.kcal, draft.kcal)
        assertEquals(meal.ingredients.size, draft.ingredients.size)
        meal.ingredients.zip(draft.ingredients).forEach { (source, copy) ->
            // Ogni proprieta' dell'ingrediente generato deve ritrovarsi, con lo stesso valore, nella bozza.
            NutritionPlanContract.GeneratedIngredient::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach { field ->
                    field.isAccessible = true
                    val target = IngredientDraft::class.java.getDeclaredField(field.name).also { it.isAccessible = true }
                    assertEquals(field.name, field.get(source), target.get(copy))
                }
        }
        assertTrue(draft.ingredients.all { it.foodId != null && it.grams != null && it.fiberG != null && it.sodiumMg != null })
    }
}
