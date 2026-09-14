package com.myfitai.app.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRawResponse
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.MealAlternativeService
import com.myfitai.app.domain.food.NutritionAdviceService
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import com.myfitai.app.domain.personalization.PersonalResponseService
import com.myfitai.app.domain.review.WeeklyReviewService
import com.myfitai.app.domain.time.TimeProvider
import com.myfitai.app.fixtures.SixMonthHistoryFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class AiWorkflowIntegrationTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var store: ActiveProfileStore
    private lateinit var fixture: SixMonthHistoryFixture.Dataset
    private lateinit var gateway: FakeAiRuntimeGateway
    private lateinit var time: FixedTimeProvider

    @Before
    fun setUp() = runBlocking {
        fixture = SixMonthHistoryFixture.build()
        time = FixedTimeProvider(SixMonthHistoryFixture.epoch(SixMonthHistoryFixture.TODAY, 12))
        gateway = FakeAiRuntimeGateway()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyFitAiDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = ActiveProfileStore(ApplicationProvider.getApplicationContext())
        store.clear()
        insertFixture()
    }

    @After
    fun tearDown() {
        db.close()
        store.clear()
    }

    @Test
    fun generation_usesLocalTargetsAndPersistsValidatedImmutableVersion() = runBlocking {
        val service = services().generation
        val week = SixMonthHistoryFixture.TODAY
        val result = service.generateWeek(week)
        val snapshot = MealPlanRepository(db).loadLatestSnapshot(store.currentIdOrNull()!!, week.toEpochDay())!!

        assertEquals(result.versionId, snapshot.version.id)
        assertEquals("AI_GENERATION", snapshot.version.reason)
        assertEquals(7, snapshot.version.days.size)
        assertTrue(snapshot.version.days.all { it.meals.size == 3 })
        assertTrue(snapshot.version.days.all { it.hydrationNote != null })
        assertTrue(gateway.requests.any { it.schemaName == "myfitai_weekly_nutrition_plan_v2" })
    }

    @Test
    fun mealSwap_generatesFiveAlternativesAndAppendsOnlyAfterApply() = runBlocking {
        val generation = services().generation
        val week = SixMonthHistoryFixture.TODAY
        generation.generateWeek(week)
        val repository = MealPlanRepository(db)
        val before = repository.loadLatestSnapshot(store.currentIdOrNull()!!, week.toEpochDay())!!
        val meal = before.version.days.first().meals.last()
        val alternatives = services().mealAlternative.generate(week.toEpochDay(), week.toEpochDay(), meal.id)

        assertEquals(5, alternatives.items.size)
        assertEquals(before.version.id, alternatives.sourceVersionId)
        assertEquals(1, repository.versions(store.currentIdOrNull()!!, before.planId).first().size)

        val applied = services().mealAlternative.apply(alternatives, alternatives.items.first())
        val after = repository.loadLatestSnapshot(store.currentIdOrNull()!!, week.toEpochDay())!!
        assertEquals(applied.versionId, after.version.id)
        assertEquals(2, repository.versions(store.currentIdOrNull()!!, before.planId).first().size)
        assertEquals(before.version.days.first().supplements, after.version.days.first().supplements)
        assertEquals(before.version.days.first().hydrationNote, after.version.days.first().hydrationNote)
        assertFalse(before.version.days.first().meals.last().title == after.version.days.first().meals.last().title)
    }

    @Test
    fun advice_isNutritionScopedFiveSuggestionsAndDoesNotPersistConversation() = runBlocking {
        val service = services().advice
        val before = db.cheatEntryDao().observeAll(store.currentIdOrNull()!!).first().size
        val result = service.ask("Mi va un gelato")
        val after = db.cheatEntryDao().observeAll(store.currentIdOrNull()!!).first().size

        assertTrue(result.accepted)
        assertEquals(5, result.suggestions.size)
        assertTrue(result.suggestions.all { it.estimatedKcal > 0 })
        assertEquals(before, after)
        assertTrue(gateway.requests.any { it.schemaName == "myfitai_nutrition_advice_v1" })
    }

    @Test
    fun cheat_isNotPersistedDuringAnalyze_andPersistsOnlyAfterConfirmation() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val before = db.cheatEntryDao().observeAll(profileId).first().size
        val input = CheatAdjustmentService.Input(
            description = "Pizza margherita",
            quantityText = "una pizza",
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(SixMonthHistoryFixture.TODAY.minusDays(10), 20),
        )
        val understanding = services().cheat.analyze(input)
        assertEquals(before, db.cheatEntryDao().observeAll(profileId).first().size)

        val result = services().cheat.registerAndAdapt(input, understanding)
        assertEquals(before + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertFalse(result.adapted)
        assertTrue(result.newVersionId == null)
    }

    @Test
    fun weeklyReview_usesCompletedWeekAndPersistsStructuredLocalMetrics() = runBlocking {
        val week = LocalDate.of(2026, 8, 24)
        val result = services().review.generate(week)

        assertEquals(week, result.metrics.weekStart)
        assertTrue(result.metrics.hasPlan)
        assertEquals(week.toEpochDay(), result.entity.weekStartEpochDay)
        assertNotNull(db.weeklyReviewDao().getForWeek(store.currentIdOrNull()!!, week.toEpochDay()))
        assertTrue(result.entity.structuredJson.orEmpty().contains("localMetrics"))
    }

    private fun services(): Services {
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val bia = com.myfitai.app.data.repository.BiaRepository(db)
        val body = com.myfitai.app.data.repository.BodyMeasurementRepository(db)
        val workouts = com.myfitai.app.data.repository.WorkoutRepository(db)
        val plans = MealPlanRepository(db)
        val cheats = com.myfitai.app.data.repository.CheatEntryRepository(db)
        val reviews = com.myfitai.app.data.repository.WeeklyReviewRepository(db)
        val calculations = ProfileCalculationService(profiles, bia, body, store)
        val personal = PersonalResponseService(store, plans, cheats, workouts, bia, body)
        return Services(
            generation = NutritionPlanGenerationService(gateway, calculations, profiles, workouts, plans, store, personal, time),
            mealAlternative = MealAlternativeService(gateway, profiles, plans, store, time),
            advice = NutritionAdviceService(gateway, profiles, plans, cheats, store, time),
            cheat = CheatAdjustmentService(gateway, plans, cheats, store, time),
            review = WeeklyReviewService(gateway, reviews, plans, workouts, cheats, bia, body, personal, store, time),
        )
    }

    private suspend fun insertFixture() {
        val profileId = db.userProfileDao().insert(fixture.profile)
        store.selectProfile(profileId)
        fixture.bia.forEach { db.biaMeasurementDao().insert(it.copy(profileId = profileId)) }
        fixture.body.forEach { db.bodyMeasurementDao().insert(it.copy(profileId = profileId)) }
        fixture.workouts.forEach { db.workoutDao().insert(it.copy(profileId = profileId)) }
        fixture.cheats.forEach { db.cheatEntryDao().insert(it.copy(profileId = profileId)) }
        fixture.reviews.forEach { db.weeklyReviewDao().upsert(it.copy(profileId = profileId)) }
        // The current week is generated by the service under test; historical weeks are enough
        // for review context and cheat confirmation.
        val plans = MealPlanRepository(db)
        fixture.plans.filter { it.weekStart.isBefore(SixMonthHistoryFixture.TODAY.minusDays(7)) }.forEach { plan ->
            val planId = plans.createPlan(profileId, plan.weekStart.toEpochDay(), plan.createdAtEpochMillis)
            plan.versions.forEach { version -> plans.appendVersion(profileId, planId, plan.createdAtEpochMillis, version) }
        }
    }

    private data class Services(
        val generation: NutritionPlanGenerationService,
        val mealAlternative: MealAlternativeService,
        val advice: NutritionAdviceService,
        val cheat: CheatAdjustmentService,
        val review: WeeklyReviewService,
    )
}

private data class FixedTimeProvider(private val now: Long) : TimeProvider {
    override val zoneId = ZoneOffset.UTC
    override fun nowEpochMillis(): Long = now
}

private class FakeAiRuntimeGateway : AiRuntimeGateway {
    val requests = mutableListOf<AiStructuredRequest>()

    override suspend fun execute(
        request: AiStructuredRequest,
        maxSchemaRetries: Int,
        businessValidator: (String) -> Result<Unit>,
    ): AiExecutionService.ValidatedResponse {
        requests += request
        val json = when (request.schemaName) {
            "myfitai_weekly_nutrition_plan_v2" -> weeklyPlan(request.userPrompt)
            "myfitai_meal_alternatives_v1" -> alternatives(request.userPrompt)
            "myfitai_nutrition_advice_v1" -> advice()
            "myfitai_cheat_understanding_v1" -> understanding()
            "myfitai_cheat_adjustment_v1" -> noAdaptation(request.userPrompt)
            "myfitai_weekly_review_v1" -> review(request.userPrompt)
            else -> error("Unsupported test schema ${request.schemaName}")
        }
        businessValidator(json).getOrThrow()
        return AiExecutionService.ValidatedResponse(AiProviderType.OPENAI, "fixture", json)
    }

    private fun weeklyPlan(prompt: String): String {
        val week = Regex("Generate the nutrition plan for the week starting (-?\\d+)").find(prompt)!!.groupValues[1].toLong()
        val kcal = Regex("DAILY_TARGETS_AUTHORITATIVE:kcal=(\\d+)").find(prompt)!!.groupValues[1].toInt()
        val protein = Regex("DAILY_TARGETS_AUTHORITATIVE:kcal=\\d+\\|P=([0-9.]+)").find(prompt)!!.groupValues[1].toDouble()
        val carbs = Regex("DAILY_TARGETS_AUTHORITATIVE:kcal=\\d+\\|P=[0-9.]+\\|C=([0-9.]+)").find(prompt)!!.groupValues[1].toDouble()
        val fat = Regex("DAILY_TARGETS_AUTHORITATIVE:kcal=\\d+\\|P=[0-9.]+\\|C=[0-9.]+\\|F=([0-9.]+)").find(prompt)!!.groupValues[1].toDouble()
        val days = JSONArray()
        repeat(7) { offset ->
            val meals = JSONArray()
            val mealKcal = listOf(kcal / 4, kcal / 4, kcal - (kcal / 4) * 2)
            mealKcal.forEachIndexed { index, value -> meals.put(meal(index, value, protein / 3, carbs / 3, fat / 3)) }
            days.put(JSONObject().apply {
                put("dateEpochDay", week + offset)
                put("totalKcal", kcal)
                put("proteinG", protein)
                put("carbsG", carbs)
                put("fatG", fat)
                put("hydrationNote", "Bevi regolarmente in modo prudente.")
                put("supplements", JSONArray())
                put("meals", meals)
            })
        }
        return JSONObject().apply {
            put("weekStartEpochDay", week)
            put("days", days)
            put("agentValidation", JSONObject().put("valid", true).put("notes", "fixture"))
        }.toString()
    }

    private fun alternatives(prompt: String): String {
        val kcal = Regex("TARGET_KCAL_EXACT:(\\d+)").find(prompt)!!.groupValues[1].toInt()
        val values = JSONArray()
        repeat(5) { index ->
            values.put(JSONObject().apply {
                put("title", "Alternativa fixture ${index + 1}")
                put("kcal", kcal)
                put("proteinG", 30.0)
                put("carbsG", 35.0)
                put("fatG", 12.0)
                put("preparation", "Preparazione fixture")
                put("reason", "Compatibile con il pasto")
                put("ingredients", JSONArray().put(ingredient("Ingrediente ${index + 1}")))
            })
        }
        return JSONObject().apply {
            put("alternatives", values)
            put("agentValidation", JSONObject().put("valid", true).put("notes", "fixture"))
        }.toString()
    }

    private fun advice(): String {
        val suggestions = JSONArray()
        repeat(5) { index -> suggestions.put(JSONObject().apply {
            put("title", "Gelato fixture ${index + 1}")
            put("reason", "Porzione coerente con il piano")
            put("estimatedKcal", 220 + index * 20)
            put("proteinG", 6.0)
            put("carbsG", 30.0)
            put("fatG", 8.0)
        }) }
        return JSONObject().apply {
            put("inScope", true)
            put("answer", "Ecco cinque opzioni di gelato gestibili nel piano.")
            put("suggestions", suggestions)
            put("assumptions", "Porzione standard")
            put("agentValidation", JSONObject().put("valid", true).put("notes", "fixture"))
        }.toString()
    }

    private fun understanding(): String = JSONObject().apply {
        put("understoodFood", "Pizza margherita, una porzione")
        put("estimate", JSONObject().apply {
            put("kcal", 750)
            put("proteinG", 28.0)
            put("carbsG", 90.0)
            put("fatG", 28.0)
            put("confidence", "medium")
            put("notes", "Stima fixture")
        })
    }.toString()

    private fun noAdaptation(prompt: String): String = JSONObject().apply {
        put("estimate", JSONObject().apply {
            put("kcal", 750); put("proteinG", 28.0); put("carbsG", 90.0); put("fatG", 28.0); put("confidence", "medium"); put("notes", "Stima fixture")
        })
        put("adaptationPossible", false)
        put("adaptationReason", "Sgarro salvato senza compensazione punitiva.")
        put("replacementMeals", JSONArray())
        put("agentValidation", JSONObject().put("valid", true).put("notes", "fixture"))
    }.toString()

    private fun review(prompt: String): String {
        val date = Regex("Review the completed week (\\d{4}-\\d{2}-\\d{2})").find(prompt)!!.groupValues[1]
        val epochDay = LocalDate.parse(date).toEpochDay()
        return JSONObject().apply {
            put("weekStartEpochDay", epochDay)
            put("summary", "Settimana descritta dai dati registrati.")
            put("observations", JSONArray().put("Il piano è presente.").put("I dati corporei sono osservazioni."))
            put("nextWeekGuidance", JSONArray().put("Mantieni target e timing locali."))
            put("agentValidation", JSONObject().put("valid", true).put("notes", "fixture"))
        }.toString()
    }

    private fun meal(index: Int, kcal: Int, protein: Double, carbs: Double, fat: Double) = JSONObject().apply {
        put("type", if (index == 0) "Colazione" else if (index == 1) "Pranzo" else "Cena")
        put("title", "Pasto fixture $index")
        put("timeMinutes", 420 + index * 360)
        put("kcal", kcal)
        put("proteinG", protein)
        put("carbsG", carbs)
        put("fatG", fat)
        put("preparation", "Preparazione fixture")
        put("ingredients", JSONArray().put(ingredient("Ingrediente principale")))
    }

    private fun ingredient(name: String) = JSONObject().apply {
        put("name", name); put("quantity", 100.0); put("unit", "g"); put("displayDose", "100 g"); put("weightState", "RAW"); put("nutritionConfidence", "HIGH"); put("category", "fixture")
    }
}
