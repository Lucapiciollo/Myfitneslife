package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiContractRegistry
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProvider
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRawResponse
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.catalog
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.context
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.envelope
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.payload
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.targets
import com.myfitai.app.domain.food.nutrition.RepairStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FoodCatalogPlanBuilderTest {
    private val builder = FoodCatalogPlanBuilder(catalog)
    private val dayEpoch = FoodCatalogPlanFixtures.day.toEpochDay()

    private fun evaluate(
        text: String = payload(),
        ctx: FoodCatalogPlanBuilder.Context = context(),
        attempt: Int = 0,
    ) = builder.evaluate(envelope(text), ctx, attempt)

    private fun rejection(text: String, ctx: FoodCatalogPlanBuilder.Context = context(), attempt: Int = 0): String? =
        runCatching { evaluate(text, ctx, attempt) }.exceptionOrNull()?.message

    @Test
    fun repairedPlan_isComputedFromTheCatalogWithinThreePercentOfTheTarget() {
        val accepted = evaluate()
        val day = accepted.response.days.single()

        // kcal del giorno entro +-3% del target, calcolate dal catalogo.
        assertTrue("kcal=${day.totalKcal}", abs(day.totalKcal - 2000.0) / 2000.0 <= 0.03)
        assertTrue(accepted.assessment.accepted)
        assertEquals(RepairStatus.REPAIRED, accepted.computation.days.single().status)
        // Il modello propone ~1.890 kcal: la riparazione ha cambiato i grammi.
        assertTrue(accepted.computation.days.single().proposedKcal < 1950.0)
        assertTrue(accepted.computation.days.single().changes.isNotEmpty())

        day.meals.forEach { meal ->
            assertEquals(FoodMeal.NUTRITION_SOURCE_COMPUTED, meal.nutritionSource)
            // Macro del pasto = somma degli ingredienti calcolati.
            assertEquals(meal.kcal.toDouble(), meal.ingredients.sumOf { it.kcal!!.toDouble() }, 0.51)
            assertEquals(meal.proteinG.toDouble(), meal.ingredients.sumOf { it.proteinG!!.toDouble() }, 0.06)
            assertEquals(meal.carbsG.toDouble(), meal.ingredients.sumOf { it.carbsG!!.toDouble() }, 0.06)
            assertEquals(meal.fatG.toDouble(), meal.ingredients.sumOf { it.fatG!!.toDouble() }, 0.06)
            meal.ingredients.forEach { ingredient ->
                val food = catalog.byId(ingredient.foodId!!)!!
                val grams = ingredient.grams!!.toDouble()
                // Ingrediente = per100 x grammi / 100.
                assertEquals(food.per100.kcal * grams / 100.0, ingredient.kcal!!.toDouble(), 0.01)
                assertEquals(food.per100.protein * grams / 100.0, ingredient.proteinG!!.toDouble(), 0.01)
                assertEquals(food.per100.carbs * grams / 100.0, ingredient.carbsG!!.toDouble(), 0.01)
                assertEquals(food.per100.fat * grams / 100.0, ingredient.fatG!!.toDouble(), 0.01)
                assertEquals((food.per100.fiber ?: 0.0) * grams / 100.0, ingredient.fiberG!!.toDouble(), 0.01)
                assertEquals((food.per100.sodiumMg ?: 0.0) * grams / 100.0, ingredient.sodiumMg!!.toDouble(), 0.01)
                // Compatibilita' con i lettori esistenti: nome del catalogo, quantita' in g (ml per i liquidi), dose non vuota.
                assertTrue(ingredient.name.startsWith(food.name))
                assertTrue(ingredient.displayDose.isNotBlank())
                assertEquals(food.shopCategory, ingredient.category)
                if (food.density == null) {
                    assertEquals("g", ingredient.unit)
                    assertEquals(grams, ingredient.quantity.toDouble(), 0.001)
                } else {
                    assertEquals("ml", ingredient.unit)
                    assertEquals(grams / food.density, ingredient.quantity.toDouble(), 0.51)
                }
            }
        }
        // Giorno = somma dei pasti.
        assertEquals(day.meals.sumOf { it.kcal }, day.totalKcal)
        assertEquals(day.meals.sumOf { it.proteinG.toDouble() }, day.proteinG.toDouble(), 0.01)
        assertEquals(day.meals.sumOf { it.carbsG.toDouble() }, day.carbsG.toDouble(), 0.01)
        assertEquals(day.meals.sumOf { it.fatG.toDouble() }, day.fatG.toDouble(), 0.01)
    }

    @Test
    fun ingredientNamesCarryTheRawOrCookedState_andLiquidsAreInMl() {
        val day = evaluate().response.days.single()
        val ingredients = day.meals.flatMap { it.ingredients }.associateBy { it.foodId }
        val pasta = ingredients.getValue("it.pasta_semola_secca")
        assertEquals("Pasta di semola secca (crudo)", pasta.name)
        assertEquals("crudo", pasta.weightState)
        val milk = ingredients.getValue("it.latte_intero")
        assertEquals("ml", milk.unit)
        // Dose pratica da equivalenze note (cucchiai, bicchiere) o in ml: mai in grammi per un liquido.
        assertTrue(milk.displayDose, milk.displayDose.isNotBlank() && !milk.displayDose.endsWith(" g"))
        assertEquals("Uovo intero (crudo)", FoodCatalogPlanBuilder.displayName(catalog.byId("it.uovo")!!))
        assertEquals("Uovo sodo", FoodCatalogPlanBuilder.displayName(catalog.byId("it.uovo_sodo")!!))
        assertEquals("Pasta di semola cotta", FoodCatalogPlanBuilder.displayName(catalog.byId("it.pasta_semola_cotta")!!))
    }

    @Test
    fun evaluationIsDeterministic() {
        val a = evaluate()
        val b = evaluate()
        assertEquals(a.response, b.response)
        assertEquals(a.computation.toJson().toString(), b.computation.toJson().toString())
    }

    @Test
    fun infeasibleDay_isRejectedWithTheReachableKcalRange() {
        val message = rejection(payload(), context(targets = targets(kcal = 3500.0)))
        assertNotNull(message)
        val match = Regex("^TARGET_TOLERANCE_EXCEEDED:$dayEpoch:target=3500;actual=(\\d+);reach=(\\d+)-(\\d+)$").matchEntire(message!!)
        assertNotNull("message=$message", match)
        val (actual, reachMin, reachMax) = match!!.destructured.toList().map { it.toInt() }
        assertTrue(reachMin < actual && actual < reachMax && reachMax < 3500 * 0.97)
        assertTrue(message.length <= AiExecutionServiceReasonCap)
    }

    @Test
    fun tooLightProposal_failsAtTheFirstAttemptAndTheRetryReceivesTheRange_thenSucceedsAtFourPercent() = runBlocking {
        val responses = ArrayDeque(listOf(envelope(payload(FoodCatalogPlanFixtures.tinyMeals)), envelope(payload())))
        val prompts = mutableListOf<String>()
        val provider = object : AiProvider {
            override val type = AiProviderType.OPENAI
            override suspend fun generateStructured(request: AiStructuredRequest): AiRawResponse {
                prompts += request.userPrompt
                return AiRawResponse(type, "fake", responses.removeFirst())
            }
        }
        val request = AiStructuredRequest(
            FoodCatalogPlanPrompt.SYSTEM_PROMPT, "user", FoodCatalogPlanContract.SCHEMA_NAME, FoodCatalogPlanContract.schemaJson,
            maxOutputTokens = null,
        )
        var attempt = 0
        var accepted: FoodCatalogPlanBuilder.Accepted? = null
        AiExecutionService(diagnostics = {}).execute(provider, request, maxSchemaRetries = 2) { json ->
            runCatching {
                accepted = builder.evaluate(json, context(), attempt++)
                Unit
            }
        }

        assertEquals(2, prompts.size)
        val retry = prompts[1]
        assertTrue(retry, retry.contains("FIX:TARGET_TOLERANCE_EXCEEDED:$dayEpoch:target=2000;actual="))
        assertTrue(retry, retry.contains(";reach="))
        assertTrue(retry, retry.contains("choose foods and grams", ignoreCase = true) || retry.contains("Choose foods and grams"))
        assertTrue(retry.contains("must begin with MFP2"))
        val ok = accepted!!
        assertEquals(1, ok.computation.attempt)
        assertEquals(0.04, ok.assessment.appliedTolerance, 0.0)
        assertTrue(ok.assessment.accepted)
        // Il ripristino della tolleranza di riferimento resta +-3% (policy registrata nel piano accettato).
        assertEquals(3.0, ok.assessment.tolerancePolicy.referencePercent, 1e-9)
    }

    @Test
    fun lowProtein_isANonBlockingWarning_notAnError() {
        // Target proteico irraggiungibile con questi alimenti: le kcal restano l'unico criterio bloccante.
        val ctx = context(targets = targets(protein = 250.0))
        val accepted = evaluate(ctx = ctx)

        assertEquals(RepairStatus.REPAIRED, accepted.computation.days.single().status)
        assertTrue(accepted.computation.days.single().finalProteinG < 250.0 * 0.85)
        assertTrue(accepted.computation.warnings.any { it.code == "PROTEIN_BELOW_TARGET" && it.severity == Severity.WARNING })

        val integrity = NutritionIntegrityValidator.validate(
            response = accepted.response,
            targets = ctx.targets,
            mealsPerDay = FoodCatalogPlanFixtures.MEALS_PER_DAY,
            dailyTargets = ctx.dailyTargets,
            assessment = accepted.assessment,
            computation = accepted.computation,
        )
        // La proteina bassa e' un avviso, mai un errore: il piano resta valido.
        assertTrue(integrity.issues.toString(), integrity.issues.isEmpty())
        assertTrue(integrity.valid)
        assertEquals(1, integrity.warnings.count { it.code == "PROTEIN_BELOW_TARGET" && it.dayEpochDay == dayEpoch })
        assertTrue(integrity.warnings.none { it.severity == Severity.ERROR })
        // Il riepilogo della riparazione e' nel documento persistito, e il lettore tollerante lo legge ancora.
        val json = integrity.toJson()
        val computation = json.getJSONObject("computation")
        assertEquals("COMPUTED", computation.getString("source"))
        assertEquals(1, computation.getInt("catalogVersion"))
        val day = computation.getJSONArray("days").getJSONObject(0)
        assertEquals("REPAIRED", day.getString("status"))
        assertTrue(day.getJSONObject("kcal").getDouble("proposed") < day.getJSONObject("kcal").getDouble("final"))
        assertTrue(day.getInt("changed") > 0)
        assertTrue(day.getJSONArray("changes").getString(0).split('|').size == 4)
        val parsed = NutritionIntegrityValidator.parse(json.toString())!!
        assertTrue(parsed.valid)
        assertEquals(integrity.warnings.size, parsed.warnings.size)
    }

    @Test
    fun withoutAComputation_theIntegrityValidatorKeepsRejectingInconsistentDeclaredMacros() {
        // MFP1 invariato: 0 kcal dichiarate con macro consistenti fanno scattare l'errore storico.
        val meal = NutritionPlanContract.GeneratedMeal("LUNCH", "Pasta", 780, 100, 40f, 60f, 20f, "Cuoci", emptyList())
        val response = NutritionPlanContract.Response(
            FoodCatalogPlanFixtures.monday.toEpochDay(),
            listOf(NutritionPlanContract.GeneratedDay(dayEpoch, 100, 40f, 60f, 20f, listOf(meal))),
            NutritionPlanContract.AgentValidation(true, "ok"),
        )
        val integrity = NutritionIntegrityValidator.validate(response, targets(), mealsPerDay = 1)
        assertTrue(integrity.issues.any { it.code == "MACRO_CALORIE_INCONSISTENCY" })
        assertFalse(integrity.valid)
        assertNull(integrity.toJson().optJSONObject("computation"))
        assertEquals(FoodMeal.NUTRITION_SOURCE_DECLARED, meal.nutritionSource)
        assertNull(NutritionPlanContract.GeneratedIngredient("x", 1f, "g", "1 g", "", "", "").foodId)
    }

    @Test
    fun allergenByCatalogTag_isRejected_andByNameStillApplies() {
        val nuts = context(profile = DietaryProfile(allergies = listOf("frutta a guscio")))
        val withAlmonds = payload().replace("I|it.mela|150", "I|it.mela|150\nI|it.mandorle|20")
        assertEquals("FOOD_CONSTRAINT_VIOLATION:it.mandorle", rejection(withAlmonds, nuts))

        val lactose = context(profile = DietaryProfile(intolerances = listOf("lattosio")))
        // Il latte e lo yogurt del piano standard violano l'intolleranza: elencati entrambi (max 5 id).
        val message = rejection(payload(), lactose)!!
        assertTrue(message, message.startsWith("FOOD_CONSTRAINT_VIOLATION:"))
        assertTrue(message, message.contains("it.latte_intero") && message.contains("it.yogurt_greco_0"))
        assertTrue(message.removePrefix("FOOD_CONSTRAINT_VIOLATION:").split(',').size <= FoodCatalogPlanBuilder.MAX_LISTED_IDS)

        // Senza vincoli lo stesso piano passa.
        assertNotNull(evaluate())
    }

    @Test
    fun preparationWithAQuantity_andUnknownFood_areRejectedAtEvaluation() {
        assertEquals(
            "PIPE_M_PREPARATION_HAS_QUANTITY",
            rejection(payload().replace("Servi la mela a spicchi con il pane.", "Servi la mela con 2 fette di pane.")),
        )
        val unknown = rejection(payload().replace("it.mela|150", "it.mela_d_oro|150"))!!
        assertTrue(unknown, unknown.startsWith("PIPE_I_FOOD_UNKNOWN:it.mela_d_oro>it.mela"))
        assertEquals("PIPE_I_GRAMS_INVALID", rejection(payload().replace("it.mela|150", "it.mela|centocinquanta")))
    }

    @Test
    fun unknownFood_reasonSuggestsTheClosestCatalogIds_withinTheRetryLimit() {
        // Caso reale L6d: id inventato 'it.fagiolini_bolliti'. A novembre i fagiolini sono fuori stagione (non sono nel
        // CAT del prompt): i suggerimenti devono venire solo dal CAT, mai da voci che il modello non vede.
        val message = rejection(payload().replace("it.mela|150", "it.fagiolini_bolliti|150"))!!
        assertTrue(message, message.startsWith("PIPE_I_FOOD_UNKNOWN:it.fagiolini_bolliti>"))
        assertTrue(message, message.length <= AiExecutionServiceReasonCap)
        val suggestions = message.substringAfter('>').split(',')
        assertTrue(message, suggestions.size in 1..3)
        val cat = catalog.subsetFor(
            com.myfitai.app.domain.food.catalog.CatalogConstraints(profile = DietaryProfile(), month = FoodCatalogPlanFixtures.day.monthValue),
        ).map { it.id }.toSet()
        assertTrue(message, suggestions.all { it in cat })
        assertTrue(message, "it.fagiolini" !in suggestions)
        assertTrue(message, suggestions.any { it.startsWith("it.fagioli_") })
        // Deterministico.
        assertEquals(message, rejection(payload().replace("it.mela|150", "it.fagiolini_bolliti|150")))
        // Il messaggio resta nel limite anche con 5 id sconosciuti lunghi.
        val many = (1..5).map { "it.fagiolini_bolliti_molto_lunghi_$it" }
        val all = catalog.entries.map { it.id }
        assertTrue(FoodCatalogPlanBuilder.unknownFoodReason(many, all).length <= AiExecutionServiceReasonCap)
        // Il registry fornisce ancora il suggerimento di retry per il codice.
        assertNotNull(AiContractRegistry.require(FoodCatalogPlanContract.SCHEMA_NAME).fixHint(message))
        assertTrue(AiExecutionService.fixReason(message).length == message.length)
    }

    @Test
    fun similarFoodIds_areDeterministic_sharedTokensFirst_thenFewerExtraTokens_thenAlphabetical() {
        val ids = catalog.entries.map { it.id }
        val similar = FoodCatalogPlanBuilder.similarFoodIds("it.fagiolini_bolliti", ids)
        assertEquals(3, similar.size)
        assertEquals(listOf("it.fagiolini", "it.fagiolini_cotti"), similar.take(2))
        assertEquals(similar, FoodCatalogPlanBuilder.similarFoodIds("it.fagiolini_bolliti", ids.reversed()))
        // Nessun token utile, nessun suggerimento (id non sicuro "?" incluso).
        assertTrue(FoodCatalogPlanBuilder.similarFoodIds("?", ids).isEmpty())
        assertTrue(FoodCatalogPlanBuilder.similarFoodIds("it.zzzzqqqq", ids).isEmpty())
    }

    @Test
    fun structuralRulesOfTheBusinessGateStillApply() {
        // Giorno mancante rispetto a quello chiesto: le date D restano validate, anche con un W sbagliato.
        assertEquals("WEEK_DATES_INVALID", rejection(payload(date = dayEpoch - 1)))
        assertEquals("WEEK_DATES_INVALID", rejection(payload(date = dayEpoch - 1, weekStart = 0)))
        // Pasti mancanti.
        assertEquals("PIPE_DAY_MUST_HAVE_4_MEALS", rejection(payload(FoodCatalogPlanFixtures.standardMeals.take(3))))
    }

    @Test
    fun wrongWeekStartWritten_byTheModel_isOverriddenByTheApp() {
        val monday = FoodCatalogPlanFixtures.monday.toEpochDay()
        listOf(monday + 7, monday - 1, 0L, 20000L).forEach { wrong ->
            val accepted = evaluate(payload(weekStart = wrong))
            assertEquals("W=$wrong", monday, accepted.response.weekStartEpochDay)
            assertTrue(accepted.assessment.accepted)
            // Stesso piano di quello con W corretto.
            assertEquals(evaluate().response, accepted.response)
        }
        assertEquals(monday, evaluate().response.weekStartEpochDay)
        // Un W non numerico resta un errore di formato.
        assertEquals("PIPE_W_INVALID", rejection(payload().replace("W|$monday", "W|lunedi")))
    }

    @Test
    fun supplements_areNotScaled_andCreatineHasNoNutrition() {
        val supplements = listOf("S|PROTEIN_POWDER|it.proteine_polvere|30|1000|dopo l'allenamento", "S|CREATINE|-|3|600|")
        val ctx = context(sports = SportsNutritionClassifier.Mode.SPORT)
        val accepted = evaluate(payload(supplements = supplements), ctx)
        val day = accepted.response.days.single()
        val powder = day.supplements.first { it.kind == "PROTEIN_POWDER" }
        val whey = catalog.byId("it.proteine_polvere")!!
        assertEquals(30f, powder.dose, 0.0f)
        assertEquals(Math.round(whey.per100.kcal * 30 / 100.0).toInt(), powder.kcal)
        val creatine = day.supplements.first { it.kind == "CREATINE" }
        assertEquals(0, creatine.kcal)
        assertEquals(0f, creatine.proteinG, 0f)
        // Le kcal dell'integratore contano nel totale del giorno.
        assertEquals(day.meals.sumOf { it.kcal } + powder.kcal, day.totalKcal)
        assertEquals(RepairStatus.REPAIRED, accepted.computation.days.single().status)
        // Creatina fuori dalla modalita' sport: regola storica invariata.
        assertEquals("CREATINE_REQUIRES_SPORT_MODE", rejection(payload(supplements = supplements)))
    }

    @Test
    fun theTolerancePolicyIsRestoredAtThreePercentAfterASuccessfulFirstAttempt() {
        val first = evaluate(attempt = 0)
        assertEquals(0.03, first.assessment.appliedTolerance, 0.0)
        assertEquals(1, first.computation.attempt + 1)
        val retry = evaluate(attempt = 1)
        assertEquals(0.04, retry.assessment.appliedTolerance, 0.0)
        // Un piano nuovo (nuovo tentativo 0) torna a +-3%.
        assertEquals(0.03, evaluate(attempt = 0).assessment.appliedTolerance, 0.0)
    }

    @Test
    fun retryHintsAreRegisteredForTheMfp2Rejections() {
        val spec = AiContractRegistry.require(FoodCatalogPlanContract.SCHEMA_NAME)
        listOf(
            "PIPE_I_FOOD_UNKNOWN:it.x", "PIPE_I_GRAMS_INVALID", "PIPE_M_PREPARATION_HAS_QUANTITY",
            "FOOD_CONSTRAINT_VIOLATION:it.x", "TARGET_TOLERANCE_EXCEEDED:20000:target=2000;actual=1000;reach=900-1400",
        ).forEach { reason -> assertNotNull(reason, spec.fixHint(reason)) }
    }

    private fun food(id: String) = catalog.byId(id)!!

    @Test
    fun mismatchedTitle_isReplacedByTheAppTitleFromTheMainIngredients_andCounted() {
        // Caso reale della prova MFP2: titolo con zucchine ma ingrediente zucca.
        val dinner = FoodCatalogPlanFixtures.MealSpec(
            "DINNER", "Trancio di salmone con patate e zucchine", 1230, "Inforna il salmone con le patate e la zucca e condisci con l'olio.",
            listOf("it.salmone" to "200", "it.patate" to "250", "it.zucca" to "150", "it.olio_evo" to "10"),
        )
        val accepted = evaluate(payload(FoodCatalogPlanFixtures.standardMeals.dropLast(1) + dinner))
        val titles = accepted.response.days.single().meals.map { it.title }

        assertEquals(1, accepted.computation.titlesReplaced)
        val generated = titles.last()
        assertTrue(generated, generated.startsWith("Salmone") || generated.startsWith("Patate"))
        assertTrue(generated, generated.endsWith(" e zucca") && !generated.contains("zucchine", ignoreCase = true) && !generated.contains("olio", ignoreCase = true))
        // I titoli coerenti dei modelli restano quelli del modello.
        assertEquals(FoodCatalogPlanFixtures.standardMeals.dropLast(1).map { it.title }, titles.dropLast(1))
        assertEquals(1, accepted.computation.toJson().getInt("titlesReplaced"))
    }

    @Test
    fun coherentPlan_keepsEveryTitleOfTheModel() {
        val accepted = evaluate()
        assertEquals(FoodCatalogPlanFixtures.standardMeals.map { it.title }, accepted.response.days.single().meals.map { it.title })
        assertEquals(0, accepted.computation.titlesReplaced)
        assertEquals(0, accepted.computation.toJson().getInt("titlesReplaced"))
    }

    @Test
    fun coherentTitle_acceptsAliasesPairsAndMoreSpecificFoods_butNotAMissingFood() {
        val pollo = food("it.petto_pollo")
        // "riso basmati" e' un alias del nome di catalogo "Riso bianco": coerente.
        assertEquals("Riso basmati con pollo", builder.coherentTitle("Riso basmati con pollo", listOf(food("it.riso_basmati"), pollo), listOf(80.0, 150.0)))
        // Termine generico con una voce piu' specifica ("riso" con riso a chicco medio): coerente.
        assertEquals("Riso con pollo", builder.coherentTitle("Riso con pollo", listOf(food("it.riso_tondo"), pollo), listOf(80.0, 150.0)))
        // Crudo/cotto della stessa voce: "merluzzo al forno" cita la voce cotta, l'ingrediente e' la voce cruda.
        assertEquals("Merluzzo al forno con patate", builder.coherentTitle("Merluzzo al forno con patate", listOf(food("it.merluzzo"), food("it.patate")), listOf(200.0, 250.0)))
        // Alimento assente: sostituito, senza olio, spezie e stato crudo/cotto.
        val foods = listOf(pollo, food("it.olio_evo"), food("it.cannella"), food("it.zucca"))
        assertEquals("Petto di pollo e zucca", builder.coherentTitle("Pollo e zucchine", foods, listOf(150.0, 15.0, 2.0, 200.0)))
        // Un solo alimento principale: titolo con quel solo nome.
        assertEquals("Petto di pollo", builder.coherentTitle("Pollo e zucchine", listOf(pollo, food("it.olio_evo")), listOf(150.0, 10.0)))
    }

    @Test
    fun generatedTitle_usesAtMostThreeFoodsByKcalWithCommasAndFinalE() {
        val foods = listOf(food("it.salmone"), food("it.patate"), food("it.zucca"), food("it.pane_integrale"))
        // kcal: salmone 150 g > patate 300 g > pane 80 g > zucca 100 g (per100 del catalogo).
        val title = builder.coherentTitle("Merluzzo con zucchine", foods, listOf(150.0, 300.0, 80.0, 100.0))
        assertTrue(title, Regex("^[A-Z][^,]+, [^,]+ e [^,]+$").matches(title))
        assertFalse(title, title.contains("(") || title.contains("crud", ignoreCase = true))
        assertEquals("Branzino", FoodCatalogPlanBuilder.titleName(food("it.branzino")).substringBefore(" /"))
        assertEquals("Salmone", FoodCatalogPlanBuilder.titleName(food("it.salmone_cotto")))
    }

    @Test
    fun rejectionOf_keepsOnlyAStableCodeAndAShortDetail() {
        val unknown = FoodCatalogPlanBuilder.rejectionOf(0, IllegalArgumentException("PIPE_I_FOOD_UNKNOWN:it.xx,it.yy"))
        assertEquals(FoodCatalogPlanBuilder.AttemptRejection(1, "PIPE_I_FOOD_UNKNOWN", "it.xx,it.yy"), unknown)
        assertEquals(FoodCatalogPlanBuilder.AttemptRejection(2, "PIPE_I_GRAMS_INVALID", ""), FoodCatalogPlanBuilder.rejectionOf(1, IllegalArgumentException("PIPE_I_GRAMS_INVALID")))
        val long = FoodCatalogPlanBuilder.rejectionOf(2, IllegalArgumentException("TARGET_TOLERANCE_EXCEEDED:" + "x".repeat(400)))
        assertEquals(3, long.attempt)
        assertEquals(FoodCatalogPlanBuilder.MAX_DETAIL_CHARS, long.detail.length)
        // Messaggi che non sono un codice dell'app (es. eccezioni di parsing con testo del modello) non vengono copiati.
        val model = FoodCatalogPlanBuilder.rejectionOf(0, org.json.JSONException("Value Ciao mario at 0 of type java.lang.String"))
        assertEquals(FoodCatalogPlanBuilder.AttemptRejection(1, "UNCLASSIFIED", ""), model)
        assertEquals("UNCLASSIFIED", FoodCatalogPlanBuilder.rejectionOf(0, IllegalStateException()).code)
    }

    @Test
    fun realRejections_areLoggedWithTheirCodes_andPersistedInTheComputation() {
        val unknownFood = runCatching { evaluate(payload(extraLines = emptyList()).replace("it.mela", "it.non_esiste")) }.exceptionOrNull()!!
        val first = FoodCatalogPlanBuilder.rejectionOf(0, unknownFood)
        assertEquals("PIPE_I_FOOD_UNKNOWN", first.code)
        val infeasible = runCatching { evaluate(ctx = context(targets = targets(kcal = 3500.0))) }.exceptionOrNull()!!
        val second = FoodCatalogPlanBuilder.rejectionOf(1, infeasible)
        assertEquals("TARGET_TOLERANCE_EXCEEDED", second.code)
        assertTrue(second.detail, second.detail.startsWith("$dayEpoch:target=3500;actual="))
        assertTrue(second.detail.length <= FoodCatalogPlanBuilder.MAX_DETAIL_CHARS)

        val ok = evaluate(attempt = 2)
        val log = ok.computation.copy(attemptsLog = listOf(first, second)).toJson().getJSONArray("attemptsLog")
        assertEquals(2, log.length())
        assertEquals(1, log.getJSONObject(0).getInt("attempt"))
        assertEquals("PIPE_I_FOOD_UNKNOWN", log.getJSONObject(0).getString("code"))
        assertEquals(2, log.getJSONObject(1).getInt("attempt"))
        assertEquals(3, ok.computation.toJson().getInt("attempt"))
        // Senza rifiuti precedenti il registro e' un array vuoto.
        assertEquals(0, ok.computation.toJson().getJSONArray("attemptsLog").length())
        // Il lettore tollerante del documento persistito legge ancora il piano.
        val integrity = NutritionIntegrityValidator.validate(
            response = ok.response,
            targets = context().targets,
            mealsPerDay = FoodCatalogPlanFixtures.MEALS_PER_DAY,
            dailyTargets = context().dailyTargets,
            assessment = ok.assessment,
            computation = ok.computation.copy(attemptsLog = listOf(first, second)),
        )
        assertEquals(2, integrity.toJson().getJSONObject("computation").getJSONArray("attemptsLog").length())
        assertTrue(NutritionIntegrityValidator.parse(integrity.toJson().toString())!!.valid)
    }

    private companion object {
        const val AiExecutionServiceReasonCap = 320
    }
}
