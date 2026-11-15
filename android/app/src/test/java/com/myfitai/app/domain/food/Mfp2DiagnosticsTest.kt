package com.myfitai.app.domain.food

import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiFailure
import com.myfitai.app.domain.ai.AiJobFailures
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.catalog
import com.myfitai.app.domain.food.FoodCatalogPlanFixtures.envelope
import com.myfitai.app.domain.food.nutrition.RepairStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Lotto L6d. Diagnostica permanente dei rifiuti MFP2 (riga di log, sintesi persistibile, codice visibile all'utente)
 * e non regressione della giornata realistica (6 pasti, grammi del modello sopra target) con il catalogo REALE dopo
 * il passo per cucchiaino/cucchiaio di L6c (olio 4,5-27 g passo 4,5; miele minimo 10,5 g; ...).
 */
class Mfp2DiagnosticsTest {

    // ---- diagnostica ----

    @Test
    fun logLine_hasAttemptCodeAndShortDetailOnly() {
        val rejection = FoodCatalogPlanBuilder.rejectionOf(1, IllegalArgumentException("TARGET_TOLERANCE_EXCEEDED:" + "9".repeat(400)))
        val line = FoodCatalogPlanBuilder.logLine(rejection)
        assertTrue(line, line.startsWith("rejected attempt=2 code=TARGET_TOLERANCE_EXCEEDED detail=9"))
        assertEquals(FoodCatalogPlanBuilder.MAX_DETAIL_CHARS, line.substringAfter("detail=").length)
        assertEquals("MyFitAiMfp2", FoodCatalogPlanBuilder.LOG_TAG)

        val model = FoodCatalogPlanBuilder.rejectionOf(0, org.json.JSONException("Value Ciao mario at 0 of type java.lang.String"))
        val modelLine = FoodCatalogPlanBuilder.logLine(model)
        assertEquals("rejected attempt=1 code=UNCLASSIFIED detail=", modelLine)
        assertFalse(modelLine.contains("mario"))
    }

    @Test
    fun failureSummary_isBoundedAndKeepsTheCode() {
        val summary = FoodCatalogPlanBuilder.failureSummary(
            FoodCatalogPlanBuilder.AttemptRejection(3, "TARGET_TOLERANCE_EXCEEDED", "20779:target=2806;actual=3213;reach=2400-4100"),
        )
        assertEquals("attempt=3;code=TARGET_TOLERANCE_EXCEEDED;detail=20779:target=2806;actual=3213;reach=2400-4100", summary)
        val long = FoodCatalogPlanBuilder.failureSummary(FoodCatalogPlanBuilder.AttemptRejection(1, "X_Y", "d".repeat(500)))
        assertEquals(160, long.length)
    }

    @Test
    fun planRejectionCodes_surviveAsStableCodes_andModelTextDoesNot() {
        listOf(
            "TARGET_TOLERANCE_EXCEEDED:20779:target=2806;actual=3213;reach=2400-4100" to "TARGET_TOLERANCE_EXCEEDED",
            "PIPE_I_FOOD_UNKNOWN:it.xx" to "PIPE_I_FOOD_UNKNOWN",
            "PIPE_M_PREPARATION_HAS_QUANTITY" to "PIPE_M_PREPARATION_HAS_QUANTITY",
            "FOOD_CONSTRAINT_VIOLATION:it.mandorle" to "FOOD_CONSTRAINT_VIOLATION",
            "PREPARATION_INGREDIENT_OMITTED:olio" to "PREPARATION_INGREDIENT_OMITTED",
        ).forEach { (message, code) ->
            assertEquals(code, AiFailure.stableBusinessCode(message))
            // Dopo i tentativi il pipeline lancia BusinessRejected con l'ultimo motivo: il job inoltra solo il codice.
            val error = AiExecutionService.Failure.BusinessRejected(message)
            assertEquals(code, AiJobFailures.safeMessage(error, "Operazione IA non riuscita"))
        }
        assertNull(AiFailure.stableBusinessCode("PIPE_ ciao mario"))
        assertNull(AiFailure.stableBusinessCode("Value Ciao mario at 0 of type java.lang.String"))
        assertEquals(
            "Operazione IA non riuscita",
            AiJobFailures.safeMessage(AiExecutionService.Failure.BusinessRejected("testo libero del modello"), "Operazione IA non riuscita"),
        )
    }

    // ---- giornata realistica con il catalogo reale ----

    private val monday = FoodCatalogPlanFixtures.monday
    private val lastDay = FoodCatalogPlanFixtures.day

    private class M(val type: String, val title: String, val time: Int, val prep: String, val items: List<Pair<String, Double>>)

    /** Giornata tipica del modello (circa 3.050 kcal con i grammi di base): avena, albume, pollo, riso basmati, salmone... */
    private val base = listOf(
        M("BREAKFAST", "Avena con albume e mela", 420, "Cuoci l'avena con gli albumi, aggiungi la mela, lo yogurt, le mandorle e il miele.",
            listOf("it.fiocchi_avena" to 70.0, "it.albume" to 200.0, "it.mela" to 150.0, "it.yogurt_greco_0" to 150.0, "it.mandorle" to 15.0, "it.miele" to 10.0)),
        M("SNACK", "Kefir con kiwi e noci", 600, "Servi il kefir con i kiwi e le noci.",
            listOf("it.kefir" to 250.0, "it.kiwi" to 150.0, "it.noci" to 15.0)),
        M("LUNCH", "Pollo con riso basmati e zucchine", 780, "Cuoci il riso, griglia il pollo con le zucchine e condisci con l'olio.",
            listOf("it.petto_pollo" to 200.0, "it.riso_basmati" to 100.0, "it.zucchine" to 200.0, "it.olio_evo" to 15.0)),
        M("SNACK", "Pane di segale con bresaola", 990, "Servi il pane di segale con la bresaola e il kiwi.",
            listOf("it.pane_segale" to 80.0, "it.bresaola" to 50.0, "it.kiwi" to 100.0)),
        M("DINNER", "Salmone con patate e spinaci", 1170, "Inforna il salmone con le patate, salta gli spinaci e condisci con l'olio.",
            listOf("it.salmone" to 200.0, "it.patate" to 300.0, "it.spinaci" to 200.0, "it.olio_evo" to 10.0)),
        M("SNACK", "Yogurt con noci", 1290, "Servi lo yogurt con le noci e il miele.",
            listOf("it.yogurt_greco_0" to 200.0, "it.noci" to 15.0, "it.miele" to 5.0)),
    )

    private fun scaled(meals: List<M>, factor: Double) = meals.map { meal ->
        M(meal.type, meal.title, meal.time, meal.prep, meal.items.map { (id, g) ->
            id to if (g * factor < 12.0) Math.max(1.0, Math.round(g * factor).toDouble()) else Math.max(5.0, Math.round(g * factor / 5.0) * 5.0)
        })
    }

    private fun payload(meals: List<M>, days: Int): String = buildList {
        add("MFP2")
        add("W|${monday.toEpochDay()}")
        for (offset in days - 1 downTo 0) {
            add("D|${lastDay.minusDays(offset.toLong()).toEpochDay()}")
            meals.forEach { meal ->
                add("M|${meal.type}|${meal.title}|${meal.time}|${meal.prep}")
                meal.items.forEach { (id, g) -> add("I|$id|$g") }
            }
            add("H|Bevi con regolarita'")
        }
        add("V|1|ok")
    }.joinToString("\n")

    private fun context(days: Int, kcal: Double = 2806.6, protein: Double = 182.6) = FoodCatalogPlanFixtures.targets(kcal, protein, 378.7, 62.4).let { t ->
        FoodCatalogPlanBuilder.Context(
            monday = monday,
            generationStart = lastDay.minusDays((days - 1).toLong()),
            periodWeeks = 1,
            targets = t,
            dailyTargets = (0 until days).associate { lastDay.minusDays(it.toLong()).toEpochDay() to t },
            dietaryProfile = DietaryProfile(),
            sportsMode = SportsNutritionClassifier.Mode.NORMAL,
            mealsPerDay = 6,
            maintenanceCeilingKcal = null,
        )
    }

    private fun assertAccepted(meals: List<M>, days: Int, ctx: FoodCatalogPlanBuilder.Context, label: String) {
        val builder = FoodCatalogPlanBuilder(catalog)
        for (attempt in 0..1) {
            val accepted = try {
                builder.evaluate(envelope(payload(meals, days)), ctx, attempt)
            } catch (error: Throwable) {
                throw AssertionError("$label attempt=$attempt rifiutato: ${error.message}", error)
            }
            assertTrue(label, accepted.assessment.accepted)
            val tolerance = if (attempt == 0) 0.03 else 0.04
            assertEquals(days, accepted.response.days.size)
            accepted.computation.days.forEach { assertEquals("$label ${it.status}", RepairStatus.REPAIRED, it.status) }
            accepted.response.days.forEach { day ->
                assertTrue("$label kcal=${day.totalKcal}", abs(day.totalKcal - ctx.targets.kcal) / ctx.targets.kcal <= tolerance)
                day.meals.flatMap { it.ingredients }.forEach { ingredient ->
                    // Grammi sempre dentro i limiti di porzione del catalogo (minG/maxG di L6c compresi).
                    val food = catalog.byId(ingredient.foodId!!)!!
                    val grams = ingredient.grams!!.toDouble()
                    assertTrue("$label ${food.id} $grams < ${food.minGrams}", grams >= food.minGrams - 0.05)
                    assertTrue("$label ${food.id} $grams > ${food.maxGrams}", grams <= food.maxGrams + 0.05)
                    assertTrue(ingredient.displayDose.isNotBlank() && ingredient.quantity > 0f)
                }
            }
        }
    }

    @Test
    fun realisticDay_proposedAboveTarget_isAcceptedWithinThreePercent_oneAndTwoDays() {
        for (days in listOf(1, 2)) {
            for (factor in listOf(0.9, 1.0, 1.05, 1.1, 1.2, 1.3)) {
                assertAccepted(scaled(base, factor), days, context(days), "days=$days factor=$factor")
            }
        }
    }

    @Test
    fun smallSeasoningsBelowTheNewMinimum_orOnTheNewSteps_areStillRepaired() {
        fun swap(id: String, grams: Double) = base.map { m -> M(m.type, m.title, m.time, m.prep, m.items.map { if (it.first == id) id to grams else it }) }
        for (days in listOf(1, 2)) {
            val ctx = context(days)
            assertAccepted(scaled(swap("it.miele", 5.0), 1.08), days, ctx, "miele 5 g")
            assertAccepted(scaled(swap("it.miele", 20.0), 1.08), days, ctx, "miele 20 g")
            assertAccepted(scaled(swap("it.olio_evo", 5.0), 1.08), days, ctx, "olio 5 g")
            assertAccepted(scaled(swap("it.olio_evo", 13.5), 1.08), days, ctx, "olio 13,5 g (un cucchiaio)")
            assertAccepted(scaled(swap("it.olio_evo", 30.0), 1.08), days, ctx, "olio 30 g (sopra il nuovo massimo 27 g)")
            assertAccepted(scaled(base, 1.08), days, context(days, protein = 183.0), "proteine 183 g")
        }
    }
}
