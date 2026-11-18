package com.myfitai.app.e2e

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.concurrent.futures.ResolvableFuture
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiImageInput
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRawResponse
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.PlanVersionDraft
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobHandler
import com.myfitai.app.domain.ai.AiJobRegistry
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.food.CheatAdjustmentAiJobHandler
import com.myfitai.app.domain.food.CheatAdjustmentService
import com.myfitai.app.domain.food.MealAlternativeService
import com.myfitai.app.domain.food.NutritionAdviceContract
import com.myfitai.app.domain.food.NutritionAdviceAiJobHandler
import com.myfitai.app.domain.food.NutritionAdviceService
import com.myfitai.app.domain.food.NutritionPlanGenerationService
import com.myfitai.app.domain.food.catalog.FoodCatalog
import com.myfitai.app.domain.food.catalog.FoodCatalogLoader
import com.myfitai.app.domain.personalization.PersonalResponseService
import com.myfitai.app.domain.calculation.SafetyCalorieFloor
import com.myfitai.app.domain.safety.HealthDeclaration
import com.myfitai.app.domain.safety.HealthDeclarationCodec
import com.myfitai.app.domain.safety.SafetyRuleCode
import com.myfitai.app.domain.safety.SafetyThresholds
import com.myfitai.app.domain.review.WeeklyReviewService
import com.myfitai.app.domain.time.TimeProvider
import com.myfitai.app.fixtures.SixMonthHistoryFixture
import com.myfitai.app.notifications.NotificationScheduler
import com.myfitai.app.ui.food.NutritionAdviceViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class AiWorkflowIntegrationTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var store: ActiveProfileStore
    private lateinit var testSessionPrefs: SharedPreferences
    private lateinit var productionSessionPrefs: SharedPreferences
    private lateinit var productionSessionSnapshot: Map<String, *>
    private lateinit var fixture: SixMonthHistoryFixture.Dataset
    private lateinit var gateway: FakeAiRuntimeGateway
    private lateinit var adviceWorkHandler: FakeNutritionAdviceWorkHandler
    private lateinit var time: FixedTimeProvider
    private val scheduledAdviceJobs = mutableSetOf<Pair<Long, String>>()

    @Before
    fun setUp() = runBlocking {
        fixture = SixMonthHistoryFixture.build()
        time = FixedTimeProvider(SixMonthHistoryFixture.epoch(SixMonthHistoryFixture.TODAY, 12))
        gateway = FakeAiRuntimeGateway()
        adviceWorkHandler = FakeNutritionAdviceWorkHandler()
        scheduledAdviceJobs.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppDataContainer.testAiJobRegistry = AiJobRegistry(mapOf(AiJobType.NUTRITION_ADVICE to adviceWorkHandler))
        productionSessionPrefs = context.getSharedPreferences(PRODUCTION_SESSION_PREFS, Context.MODE_PRIVATE)
        productionSessionSnapshot = productionSessionPrefs.all.toMap()
        testSessionPrefs = context.getSharedPreferences(TEST_SESSION_PREFS, Context.MODE_PRIVATE)
        assertTrue("Could not clear stale test session preferences", testSessionPrefs.edit().clear().commit())
        db = Room.inMemoryDatabaseBuilder(
            context,
            MyFitAiDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = ActiveProfileStore(context, TEST_SESSION_PREFS)
        store.clear()
        insertFixture()
    }

    @After
    fun tearDown() {
        try {
            if (::adviceWorkHandler.isInitialized) adviceWorkHandler.releaseAll()
            try {
                if (::adviceWorkHandler.isInitialized && scheduledAdviceJobs.isNotEmpty()) {
                    val context = ApplicationProvider.getApplicationContext<Context>()
                    val workManager = WorkManager.getInstance(context)
                    scheduledAdviceJobs.forEach { (profileId, jobKey) ->
                        val uniqueName = "ai-${AiJobType.NUTRITION_ADVICE.name.lowercase()}-$profileId-$jobKey"
                        runCatching { workManager.cancelUniqueWork(uniqueName).result.get(10, TimeUnit.SECONDS) }
                    }
                }
            } finally {
                AppDataContainer.testAiJobRegistry = null
            }
            if (::db.isInitialized) db.close()
        } finally {
            try {
                if (::store.isInitialized) store.clear()
                if (::testSessionPrefs.isInitialized) {
                    assertTrue("Test session preferences must be synchronously cleared", testSessionPrefs.edit().clear().commit())
                    assertFalse(testSessionPrefs.contains("active_profile_id"))
                    assertFalse(testSessionPrefs.contains("default_profile_id"))
                }
            } finally {
                if (::productionSessionPrefs.isInitialized && ::productionSessionSnapshot.isInitialized) {
                    assertTrue(
                        "Production profile session must remain unchanged",
                        productionSessionPrefs.all == productionSessionSnapshot,
                    )
                }
            }
        }
    }

    @Test
    fun generation_usesLocalTargetsAndPersistsValidatedImmutableVersion() = runBlocking {
        val service = services().generation
        val week = SixMonthHistoryFixture.TODAY
        val result = service.generateWeek(week)
        val snapshot = MealPlanRepository(db).loadLatestSnapshot(store.currentIdOrNull()!!, week.toEpochDay())!!

        assertEquals("version", result.versionId, snapshot.version.id)
        assertTrue("reason=${snapshot.version.reason}", snapshot.version.reason.orEmpty().startsWith("AI_GENERATION"))
        assertEquals("days", 7, snapshot.version.days.size)
        assertTrue("mealCounts=${snapshot.version.days.map { it.meals.size }}", snapshot.version.days.all { it.meals.size == 5 })
        assertTrue("hydration=${snapshot.version.days.map { it.hydrationNote }}", snapshot.version.days.all { it.hydrationNote != null })
        assertTrue("schemas=${gateway.requests.map { it.schemaName }}", gateway.requests.any { it.schemaName == "myfitai_weekly_nutrition_pipe_v1" })
        assertTrue(gateway.requests.first { it.schemaName == "myfitai_weekly_nutrition_pipe_v1" }.userPrompt.lineSequence().any { it.startsWith("U:") })
        assertTrue(gateway.requests.first { it.schemaName == "myfitai_weekly_nutrition_pipe_v1" }.userPrompt.lineSequence().any { it.startsWith("LC:") })
    }

    @Test
    fun weeklyGenerationSafetyBlocksBeforeGatewayAndPersistence() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val original = profiles.get(profileId)!!
        val planRepository = MealPlanRepository(db)
        val week = SixMonthHistoryFixture.TODAY
        val incomplete = HealthDeclarationCodec.encode(
            HealthDeclaration(pregnancyOrLactation = null, clinicianManagedConditionOrMedication = false, confirmedAtEpochMillis = 1L),
        )
        val positive = HealthDeclarationCodec.encode(
            HealthDeclaration(pregnancyOrLactation = true, clinicianManagedConditionOrMedication = false, confirmedAtEpochMillis = 2L),
        )
        val unsupported = """{"schemaVersion":2,"policyVersion":"future","pregnancyOrLactation":false,"clinicianManagedConditionOrMedication":false,"confirmedAtEpochMillis":3}"""
        val scenarios = listOf(
            Triple("absent", original.copy(healthDeclarationJson = null), SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE),
            Triple("incomplete", original.copy(healthDeclarationJson = incomplete), SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE),
            Triple("invalid", original.copy(healthDeclarationJson = "not-json"), SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE),
            Triple("unsupported", original.copy(healthDeclarationJson = unsupported), SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE),
            Triple(
                "minor",
                original.copy(birthDateEpochDay = SixMonthHistoryFixture.TODAY.minusYears(17).toEpochDay()),
                SafetyRuleCode.AGE_UNDER_18,
            ),
            Triple(
                "positive declaration with deficit goal",
                original.copy(goal = "Dimagrimento", healthDeclarationJson = positive),
                SafetyRuleCode.PREGNANCY_OR_LACTATION_DECLARED,
            ),
        )
        val generation = services().generation
        val requestsBefore = gateway.requests.size

        scenarios.forEach { (name, profile, expectedRule) ->
            db.userProfileDao().update(profile)
            val error = runCatching { generation.generateWeek(profileId, week) }.exceptionOrNull()
            assertTrue("$name should be a typed local block; got $error", error is NutritionPlanGenerationService.GenerationException.SafetyBlocked)
            assertEquals("$name must not reach AI", requestsBefore, gateway.requests.size)
            assertEquals("$name must not create or append a plan", null, planRepository.getPlanForWeek(profileId, week.toEpochDay()))
            val blocked = error as NutritionPlanGenerationService.GenerationException.SafetyBlocked
            assertTrue("$name missing expected rule", expectedRule in blocked.decision.rules)
            assertFalse("$name must not expose declaration fields", blocked.message.orEmpty().contains("pregnancyOrLactation"))
        }

        // The worker/service contract is profileId-scoped even while another profile remains active.
        val otherProfileId = db.userProfileDao().insert(
            original.copy(id = 0, name = "Profile without declaration", healthDeclarationJson = null),
        )
        val otherProfileError = runCatching { generation.generateWeek(otherProfileId, week) }.exceptionOrNull()
        assertTrue(otherProfileError is NutritionPlanGenerationService.GenerationException.SafetyBlocked)
        assertTrue(
            SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE in
                (otherProfileError as NutritionPlanGenerationService.GenerationException.SafetyBlocked).decision.rules,
        )
        assertEquals(requestsBefore, gateway.requests.size)
        assertEquals(null, planRepository.getPlanForWeek(otherProfileId, week.toEpochDay()))
    }

    @Test
    fun positiveHealthDeclarationUsesMaintenanceTdeeWithoutChangingSavedGoal() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val original = profiles.get(profileId)!!
        val positive = HealthDeclarationCodec.encode(
            HealthDeclaration(pregnancyOrLactation = false, clinicianManagedConditionOrMedication = true, confirmedAtEpochMillis = 4L),
        )
        db.userProfileDao().update(original.copy(goal = "Mantenimento", healthDeclarationJson = positive))
        val tdee = ProfileCalculationService(
            profiles,
            com.myfitai.app.data.repository.BiaRepository(db),
            com.myfitai.app.data.repository.BodyMeasurementRepository(db),
            store,
        ).profileSnapshot(profileId, SixMonthHistoryFixture.TODAY)!!.calculation.tdeeKcal!!

        val result = services().generation.generateWeek(profileId, SixMonthHistoryFixture.TODAY)
        val snapshot = MealPlanRepository(db).loadLatestSnapshot(profileId, SixMonthHistoryFixture.TODAY.toEpochDay())!!
        assertEquals("Mantenimento", profiles.get(profileId)!!.goal)
        assertNotNull(result.safetyNotice)
        assertTrue(snapshot.version.days.all { day ->
            day.totalKcal?.let { it >= tdee * 0.97 && it <= tdee * 1.03 } == true
        })
        assertEquals(1, gateway.requests.size)
        assertTrue(gateway.requests.none { request ->
            request.userPrompt.contains("pregnancyOrLactation") ||
                request.userPrompt.contains("clinicianManagedConditionOrMedication") || request.userPrompt.contains(positive)
        })
    }

    @Test
    fun maintenanceTdeeBandDoesNotWidenOnRetryOrPersistRejectedOutput() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val original = profiles.get(profileId)!!
        val positive = HealthDeclarationCodec.encode(
            HealthDeclaration(pregnancyOrLactation = true, clinicianManagedConditionOrMedication = false, confirmedAtEpochMillis = 5L),
        )
        db.userProfileDao().update(original.copy(goal = "Mantenimento", healthDeclarationJson = positive))
        val tdee = ProfileCalculationService(
            profiles,
            com.myfitai.app.data.repository.BiaRepository(db),
            com.myfitai.app.data.repository.BodyMeasurementRepository(db),
            store,
        ).profileSnapshot(profileId, SixMonthHistoryFixture.TODAY)!!.calculation.tdeeKcal!!
        gateway.weeklyPlanKcalOffset = (tdee * 0.035).toInt() + 1
        gateway.simulateWeeklyPlanRetry = true

        val error = runCatching { services().generation.generateWeek(profileId, SixMonthHistoryFixture.TODAY) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().startsWith("SAFETY_MAINTENANCE_TARGET_OUT_OF_RANGE"))
        assertEquals(1, gateway.requests.size)
        assertEquals(2, gateway.weeklyValidationAttempts)
        assertEquals(null, MealPlanRepository(db).getPlanForWeek(profileId, SixMonthHistoryFixture.TODAY.toEpochDay()))
        assertEquals("Mantenimento", profiles.get(profileId)!!.goal)
    }

    @Test
    fun safetyProfileAndDeclarationAreRecheckedAfterProviderBeforeAnyPlanWrite() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val original = profiles.get(profileId)!!
        val positive = HealthDeclarationCodec.encode(
            HealthDeclaration(pregnancyOrLactation = true, clinicianManagedConditionOrMedication = false, confirmedAtEpochMillis = 6L),
        )
        val nonPositive = HealthDeclarationCodec.encode(
            HealthDeclaration(pregnancyOrLactation = false, clinicianManagedConditionOrMedication = false, confirmedAtEpochMillis = 5L),
        )
        val week = SixMonthHistoryFixture.TODAY
        val plans = MealPlanRepository(db)
        val scenarios = listOf(
            original.copy(healthDeclarationJson = positive),
            original.copy(goal = "Mantenimento", healthDeclarationJson = nonPositive),
        )

        scenarios.forEach { changedProfile ->
            db.userProfileDao().update(original.copy(healthDeclarationJson = nonPositive))
            val callsBefore = gateway.requests.size
            gateway.beforeWeeklyPlanResponse = {
                val latest = profiles.get(profileId)!!
                db.userProfileDao().update(latest.copy(
                    goal = changedProfile.goal,
                    healthDeclarationJson = changedProfile.healthDeclarationJson,
                ))
            }

            val error = runCatching { services().generation.generateWeek(profileId, week) }.exceptionOrNull()

            assertEquals("provider was called before the mutation", 1, gateway.requests.size - callsBefore)
            assertEquals("SAFETY_PROFILE_CHANGED", error?.message)
            assertFalse(error?.message.orEmpty().contains("pregnancyOrLactation"))
            assertFalse(error?.message.orEmpty().contains(positive))
            assertEquals(null, plans.getPlanForWeek(profileId, week.toEpochDay()))
        }
    }

    @Test
    fun createAndAppendVersionRollsBackNewPlanWhenVersionWriteFails() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = SixMonthHistoryFixture.TODAY.plusWeeks(2)
        val profileSnapshot = db.userProfileDao().get(profileId)!!
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_fixture_plan_day BEFORE INSERT ON meal_plan_days " +
                "BEGIN SELECT RAISE(ABORT, 'fixture append failure'); END",
        )

        val error = runCatching {
            MealPlanRepository(db).createOrAppendVersion(
                profileId = profileId,
                weekStartEpochDay = week.toEpochDay(),
                createdAtEpochMillis = time.nowEpochMillis(),
                draft = draft(listOf(historyDay(week, meal("Rollback", "Pranzo", 500, 780)))),
                expectedProfileSnapshot = profileSnapshot,
                revalidateBeforeWrite = {},
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertEquals("transaction must roll back the newly created week row", null,
            MealPlanRepository(db).getPlanForWeek(profileId, week.toEpochDay()))
    }

    @Test
    fun createOrAppendVersionRejectsStaleProfileSnapshotBeforeAnyWrite() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = SixMonthHistoryFixture.TODAY.plusWeeks(3)
        val expectedProfile = db.userProfileDao().get(profileId)!!
        db.userProfileDao().update(expectedProfile.copy(
            healthDeclarationJson = HealthDeclarationCodec.encode(
                HealthDeclaration(true, false, confirmedAtEpochMillis = 7L),
            ),
            updatedAtEpochMillis = expectedProfile.updatedAtEpochMillis + 1,
        ))

        val error = runCatching {
            MealPlanRepository(db).createOrAppendVersion(
                profileId = profileId,
                weekStartEpochDay = week.toEpochDay(),
                createdAtEpochMillis = time.nowEpochMillis(),
                draft = draft(listOf(historyDay(week, meal("Stale", "Pranzo", 500, 780)))),
                expectedProfileSnapshot = expectedProfile,
                revalidateBeforeWrite = {},
            )
        }.exceptionOrNull()

        assertTrue(error is MealPlanRepository.SafetySnapshotChanged)
        assertEquals("SAFETY_PROFILE_CHANGED", error?.message)
        assertEquals(null, MealPlanRepository(db).getPlanForWeek(profileId, week.toEpochDay()))
    }

    @Test
    fun weeklyGenerationRejectsAPlanSnapshotSupersededDuringProviderCall() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = com.myfitai.app.domain.food.NutritionPlanPeriod.monday(SixMonthHistoryFixture.TODAY)
        val plans = MealPlanRepository(db)
        val generation = services().generation

        // Existing-plan race: a cheat adaptation appends after generation's snapshot but before provider return.
        generation.generateWeek(profileId, week)
        val beforeAdaptation = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val versionsBeforeAdaptation = plans.versions(profileId, beforeAdaptation.planId).first().size
        var adaptedVersionId = -1L
        gateway.beforeWeeklyPlanResponse = {
            val current = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
            assertEquals(beforeAdaptation.version.id, current.version.id)
            adaptedVersionId = plans.appendVersion(
                profileId = profileId,
                planId = current.planId,
                createdAtEpochMillis = time.nowEpochMillis() + 1,
                draft = draft(current.version.days.map { it.toDraftForTest() }).copy(
                    source = "CHEAT_ADJUSTMENT",
                    reason = "CHEAT_ADJUSTMENT_FIXTURE",
                ),
            )
        }

        val staleGenerationError = runCatching { generation.generateWeek(profileId, week) }.exceptionOrNull()

        assertTrue("Expected a retryable stale-plan result; got $staleGenerationError", staleGenerationError is NutritionPlanGenerationService.GenerationException.StalePlan)
        val adaptedLatest = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        assertEquals(adaptedVersionId, adaptedLatest.version.id)
        assertEquals("CHEAT_ADJUSTMENT_FIXTURE", adaptedLatest.version.reason)
        assertEquals(versionsBeforeAdaptation + 1, plans.versions(profileId, beforeAdaptation.planId).first().size)

        // Empty-week race: two first generations cannot both create a latest plan from an absent snapshot.
        val emptyWeek = week.plusWeeks(1)
        assertNull(plans.getPlanForWeek(profileId, emptyWeek.toEpochDay()))
        var concurrentPlanId = -1L
        var concurrentVersionId = -1L
        gateway.beforeWeeklyPlanResponse = {
            concurrentPlanId = plans.createPlan(profileId, emptyWeek.toEpochDay(), time.nowEpochMillis())
            concurrentVersionId = plans.appendVersion(
                profileId = profileId,
                planId = concurrentPlanId,
                createdAtEpochMillis = time.nowEpochMillis(),
                draft = draft(listOf(historyDay(emptyWeek, meal("Sgarro concorrente", "Pranzo", 500, 780))))
                    .copy(source = "CHEAT_ADJUSTMENT", reason = "CHEAT_ADJUSTMENT_FIRST_PLAN_FIXTURE"),
            )
        }

        val firstCreateError = runCatching { generation.generateWeek(profileId, emptyWeek) }.exceptionOrNull()

        assertTrue("Expected first-create CAS rejection; got $firstCreateError", firstCreateError is NutritionPlanGenerationService.GenerationException.StalePlan)
        val concurrentLatest = plans.loadLatestSnapshot(profileId, emptyWeek.toEpochDay())!!
        assertEquals(concurrentPlanId, concurrentLatest.planId)
        assertEquals(concurrentVersionId, concurrentLatest.version.id)
        assertEquals("CHEAT_ADJUSTMENT_FIRST_PLAN_FIXTURE", concurrentLatest.version.reason)
        assertEquals(1, plans.versions(profileId, concurrentPlanId).first().size)
    }

    @Test
    fun safetyInputsAreRecheckedAfterProviderWhenBiaChanges() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = SixMonthHistoryFixture.TODAY
        val beforeRequests = gateway.requests.size
        val latestBia = db.biaMeasurementDao().getAll(profileId).maxBy { it.measuredAtEpochMillis }
        gateway.beforeWeeklyPlanResponse = {
            db.biaMeasurementDao().insert(
                latestBia.copy(
                    id = 0,
                    measuredAtEpochMillis = time.nowEpochMillis() + 1,
                    weightKg = (latestBia.weightKg ?: 80f) + 10f,
                ),
            )
        }

        val error = runCatching { services().generation.generateWeek(profileId, week) }.exceptionOrNull()

        assertEquals(1, gateway.requests.size - beforeRequests)
        assertEquals("SAFETY_PROFILE_CHANGED", error?.message)
        assertEquals(null, MealPlanRepository(db).getPlanForWeek(profileId, week.toEpochDay()))
    }

    @Test
    fun profileCalculationAndCompetingMeasurementWriteShareCommitTransaction() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = SixMonthHistoryFixture.TODAY.plusWeeks(4)
        val profile = db.userProfileDao().get(profileId)!!
        val latestBia = db.biaMeasurementDao().getAll(profileId).maxBy { it.measuredAtEpochMillis }
        val calculations = ProfileCalculationService(
            com.myfitai.app.data.repository.UserProfileRepository(db),
            com.myfitai.app.data.repository.BiaRepository(db),
            com.myfitai.app.data.repository.BodyMeasurementRepository(db),
            store,
        )
        val enteredRecheck = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val plans = MealPlanRepository(db)
        val planWrite = async(Dispatchers.IO) {
            plans.createOrAppendVersion(
                profileId = profileId,
                weekStartEpochDay = week.toEpochDay(),
                createdAtEpochMillis = time.nowEpochMillis(),
                draft = draft(listOf(historyDay(week, meal("Serializzato", "Pranzo", 500, 780)))),
                expectedProfileSnapshot = profile,
                revalidateBeforeWrite = {
                    val snapshot = calculations.profileSnapshot(profileId, SixMonthHistoryFixture.TODAY)
                    assertNotNull(snapshot?.calculation?.tdeeKcal)
                    enteredRecheck.complete(Unit)
                    allowCommit.await()
                },
            )
        }
        enteredRecheck.await()

        val measurementWrite = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            db.biaMeasurementDao().insert(
                latestBia.copy(
                    id = 0,
                    measuredAtEpochMillis = time.nowEpochMillis() + 2,
                    weightKg = (latestBia.weightKg ?: 80f) + 5f,
                ),
            )
        }
        assertFalse("concurrent BIA write must wait for safety recheck + plan commit", measurementWrite.isCompleted)
        allowCommit.complete(Unit)
        val (_, versionId) = planWrite.await()
        assertTrue(versionId > 0)
        measurementWrite.await()
        assertNotNull(plans.getPlanForWeek(profileId, week.toEpochDay()))
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
    fun localMealAlternatives_matchCaloriesSlotTimeDeduplicateAndApplyWithoutAi() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = SixMonthHistoryFixture.TODAY
        val mealPlans = MealPlanRepository(db)
        val planId = mealPlans.createPlan(profileId, week.toEpochDay(), time.nowEpochMillis())
        val targetDays = (0L..6L).map { dayOffset ->
            val date = week.plusDays(dayOffset)
            val row = historyDay(
                date,
                meal(title = if (dayOffset == 0L) "Pasto target" else "Pranzo $dayOffset", type = "Pranzo", kcal = 500, time = 780),
                meal(title = "Colazione $dayOffset", type = "Colazione", kcal = 500, time = 480),
                meal(title = "Spuntino mattina $dayOffset", type = "Spuntino", kcal = 500, time = 630),
                meal(title = "Spuntino pomeriggio $dayOffset", type = "Spuntino", kcal = 500, time = 960),
                meal(title = "Cena $dayOffset", type = "Cena", kcal = 500, time = 1140),
            )
            row.copy(
                targetKcal = 2_500,
                targetProteinG = row.proteinG,
                targetCarbsG = row.carbsG,
                targetFatG = row.fatG,
            )
        }
        val firstTargetDay = targetDays.first()
        mealPlans.appendVersion(
            profileId,
            planId,
            time.nowEpochMillis(),
            PlanVersionDraft(
                source = "fixture",
                reason = "fixture",
                targetKcal = 2_500,
                targetProteinG = firstTargetDay.proteinG,
                targetCarbsG = firstTargetDay.carbsG,
                targetFatG = firstTargetDay.fatG,
                days = targetDays,
            ),
        )
        val sourceSnapshot = mealPlans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val sourceDayEpochDay = week.toEpochDay()
        assertTrue(
            "Expected source day $sourceDayEpochDay in the loaded weekly plan",
            sourceSnapshot.version.days.any { it.dateEpochDay == sourceDayEpochDay },
        )
        val sourceDay = sourceSnapshot.version.days.single { it.dateEpochDay == sourceDayEpochDay }
        assertTrue(
            "Expected meal 'Pasto target' on source day $sourceDayEpochDay",
            sourceDay.meals.any { it.title == "Pasto target" },
        )
        val sourceMeal = sourceDay.meals.single { it.title == "Pasto target" }

        val historicalWeek = week.minusDays(7)
        val historyPlanId = mealPlans.createPlan(profileId, historicalWeek.toEpochDay(), time.nowEpochMillis() - 20_000)
        val oldVersionDay = historyDay(
            historicalWeek,
            meal(title = "Riso e pollo", type = "Pranzo", kcal = 450, time = 690),
            meal(title = "Riso e pollo", type = "Pranzo", kcal = 455, time = 700), // duplicate recipe, newer version
            meal(title = "Pasta fuori soglia", type = "Pranzo", kcal = 444, time = 700),
            meal(title = "Cena incompatibile", type = "Cena", kcal = 500, time = 720),
            meal(title = "Pranzo tardi", type = "Pranzo", kcal = 500, time = 950),
        )
        mealPlans.appendVersion(profileId, historyPlanId, time.nowEpochMillis() - 1_000, draft(listOf(oldVersionDay)))
        val newestVersionDay = historyDay(
            historicalWeek,
            meal(title = "Riso e pollo", type = "Pranzo", kcal = 455, time = 700), // duplicate of the historical recipe
            meal(title = "Zuppa e pane", type = "Pranzo", kcal = 530, time = 780),
        )
        mealPlans.appendVersion(profileId, historyPlanId, time.nowEpochMillis(), draft(listOf(newestVersionDay)))

        val callsBefore = gateway.requests.size
        val matches = services().mealAlternative.historicalAlternatives(week.toEpochDay(), week.toEpochDay(), sourceMeal.id)

        assertEquals(listOf("Zuppa e pane", "Riso e pollo"), matches.map { it.meal.title })
        assertEquals(callsBefore, gateway.requests.size)
        assertTrue(matches.all { it.meal.type == "Pranzo" && abs(it.meal.timeMinutes!! - sourceMeal.timeMinutes!!) <= 120 })
        val applied = services().mealAlternative.applyHistorical(week.toEpochDay(), week.toEpochDay(), sourceMeal.id, matches.first())
        val after = mealPlans.loadLatestSnapshot(profileId, week.toEpochDay())!!

        assertTrue(after.version.id > sourceSnapshot.version.id)
        assertEquals("Zuppa e pane", after.version.days.first().meals.first().title)
        assertTrue(after.version.reason.orEmpty().startsWith("HISTORY_MEAL_SWAP:"))
        assertEquals("LOCAL_HISTORY_SWAP", after.version.source)
        assertEquals(matches.first().meal.ingredients.map { it.name }, after.version.days.first().meals.first().ingredients.map { it.name })
        assertEquals(callsBefore, gateway.requests.size)
        assertEquals(applied.title, "Zuppa e pane")
    }

    @Test
    fun mealAlternativeApplyAndHistoricalApplyRecheckMealTimeAtTransactionalWriteBoundary() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = SixMonthHistoryFixture.TODAY
        time.setNow(week.atTime(12, 59).toInstant(ZoneOffset.UTC).toEpochMilli())
        services().generation.generateWeek(profileId, week)

        val plans = MealPlanRepository(db)
        val generated = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val targetDates = setOf(week.toEpochDay(), week.plusDays(1).toEpochDay())
        val revisedDays = generated.version.days.map { day ->
            val dayDraft = day.toDraftForTest()
            if (day.dateEpochDay in targetDates) {
                dayDraft.copy(meals = dayDraft.meals.mapIndexed { index, meal ->
                    if (index == 1) meal.copy(type = "Pranzo", timeMinutes = 13 * 60) else meal
                })
            } else dayDraft
        }
        plans.appendVersion(
            profileId = profileId,
            planId = generated.planId,
            createdAtEpochMillis = time.nowEpochMillis() + 1,
            draft = draft(revisedDays),
        )

        val service = services().mealAlternative
        val target = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val todayMeal = target.version.days.first { it.dateEpochDay == week.toEpochDay() }
            .meals.single { it.type == "Pranzo" && it.timeMinutes == 13 * 60 }
        val tomorrowMeal = target.version.days.first { it.dateEpochDay == week.plusDays(1).toEpochDay() }
            .meals.single { it.type == "Pranzo" && it.timeMinutes == 13 * 60 }

        val historyWeek = week.minusWeeks(1)
        val historicalMeal = meal(
            title = "Historical lunch",
            type = "Pranzo",
            kcal = todayMeal.kcal!!,
            time = 13 * 60,
        ).apply {
            put("proteinG", todayMeal.proteinG!!.toDouble())
            put("carbsG", todayMeal.carbsG!!.toDouble())
            put("fatG", todayMeal.fatG!!.toDouble())
        }
        val historyPlanId = plans.createPlan(profileId, historyWeek.toEpochDay(), time.nowEpochMillis())
        plans.appendVersion(
            profileId = profileId,
            planId = historyPlanId,
            createdAtEpochMillis = time.nowEpochMillis(),
            draft = draft(listOf(historyDay(historyWeek, historicalMeal))),
        )

        val todayPreview = service.generate(profileId, week.toEpochDay(), week.toEpochDay(), todayMeal.id)
        val latestBeforeAiApply = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val versionsBeforeAiApply = plans.versions(profileId, target.planId).first().size
        time.advanceAfterNextRead(week.atTime(13, 1).toInstant(ZoneOffset.UTC).toEpochMilli())
        val aiError = runCatching { service.apply(todayPreview, todayPreview.items.first()) }.exceptionOrNull()

        assertTrue("AI apply must reject the meal after the clock crosses 13:00: $aiError", aiError is MealAlternativeService.AlternativeException.PastMeal)
        val afterAiReject = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        assertEquals(latestBeforeAiApply.version.id, afterAiReject.version.id)
        assertEquals(versionsBeforeAiApply, plans.versions(profileId, target.planId).first().size)
        assertEquals(todayMeal.title, afterAiReject.version.days.first { it.dateEpochDay == week.toEpochDay() }
            .meals.single { it.type == "Pranzo" && it.timeMinutes == 13 * 60 }.title)

        // A still-future meal remains applicable, providing the non-past control for the same target time.
        time.setNow(week.atTime(12, 59).toInstant(ZoneOffset.UTC).toEpochMilli())
        val futurePreview = service.generate(profileId, week.toEpochDay(), week.plusDays(1).toEpochDay(), tomorrowMeal.id)
        val controlResult = service.apply(futurePreview, futurePreview.items.first())
        val afterControl = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        assertEquals(controlResult.versionId, afterControl.version.id)

        val currentTodayMeal = afterControl.version.days.first { it.dateEpochDay == week.toEpochDay() }
            .meals.single { it.type == "Pranzo" && it.timeMinutes == 13 * 60 }
        val historicalAlternative = service.historicalAlternatives(
            week.toEpochDay(), week.toEpochDay(), currentTodayMeal.id,
        ).single { it.meal.title == "Historical lunch" }
        val latestBeforeHistoricalApply = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val versionsBeforeHistoricalApply = plans.versions(profileId, target.planId).first().size
        time.setNow(week.atTime(12, 59).toInstant(ZoneOffset.UTC).toEpochMilli())
        time.advanceAfterNextRead(week.atTime(13, 1).toInstant(ZoneOffset.UTC).toEpochMilli())
        val historicalError = runCatching {
            service.applyHistorical(week.toEpochDay(), week.toEpochDay(), currentTodayMeal.id, historicalAlternative)
        }.exceptionOrNull()

        assertTrue(
            "Historical apply must reject the meal after the clock crosses 13:00: $historicalError",
            historicalError is MealAlternativeService.AlternativeException.PastMeal,
        )
        val afterHistoricalReject = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        assertEquals(latestBeforeHistoricalApply.version.id, afterHistoricalReject.version.id)
        assertEquals(versionsBeforeHistoricalApply, plans.versions(profileId, target.planId).first().size)
        assertEquals(currentTodayMeal.title, afterHistoricalReject.version.days.first { it.dateEpochDay == week.toEpochDay() }
            .meals.single { it.type == "Pranzo" && it.timeMinutes == 13 * 60 }.title)
    }

    @Test
    fun mealAlternativePreviewRemainsAvailableButApplyFailsClosedForSafetyProfiles() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = SixMonthHistoryFixture.TODAY
        services().generation.generateWeek(profileId, week)
        val plans = MealPlanRepository(db)
        val initial = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val meal = initial.version.days.first().meals.last()
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val profile = profiles.get(profileId)!!
        val incomplete = HealthDeclarationCodec.encode(
            HealthDeclaration(pregnancyOrLactation = null, clinicianManagedConditionOrMedication = false, confirmedAtEpochMillis = 10L),
        )
        val positive = HealthDeclarationCodec.encode(
            HealthDeclaration(pregnancyOrLactation = true, clinicianManagedConditionOrMedication = false, confirmedAtEpochMillis = 11L),
        )
        val unsupported = """{"schemaVersion":2,"policyVersion":"future","pregnancyOrLactation":false,"clinicianManagedConditionOrMedication":false,"confirmedAtEpochMillis":12}"""
        val scenarios = listOf(
            Triple("minor", profile.copy(birthDateEpochDay = SixMonthHistoryFixture.TODAY.minusYears(17).toEpochDay()), SafetyRuleCode.AGE_UNDER_18),
            Triple("absent", profile.copy(healthDeclarationJson = null), SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE),
            Triple("incomplete", profile.copy(healthDeclarationJson = incomplete), SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE),
            Triple("invalid", profile.copy(healthDeclarationJson = "not-json"), SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE),
            Triple("unsupported", profile.copy(healthDeclarationJson = unsupported), SafetyRuleCode.HEALTH_DECLARATION_INCOMPLETE),
            Triple("positive non-maintenance", profile.copy(goal = "Dimagrimento", healthDeclarationJson = positive), SafetyRuleCode.PREGNANCY_OR_LACTATION_DECLARED),
        )
        val versionCount = plans.versions(profileId, initial.planId).first().size

        scenarios.forEach { (name, changedProfile, expectedRule) ->
            db.userProfileDao().update(changedProfile)
            val callsBeforePreview = gateway.requests.size
            val preview = services().mealAlternative.generate(profileId, week.toEpochDay(), week.toEpochDay(), meal.id)
            assertEquals("$name preview must remain available", 5, preview.items.size)
            assertEquals("$name preview must not write", initial.version.id, plans.loadLatestSnapshot(profileId, week.toEpochDay())!!.version.id)
            assertTrue("$name preview must still use AI", gateway.requests.size > callsBeforePreview)

            val error = runCatching { services().mealAlternative.apply(preview, preview.items.first()) }.exceptionOrNull()
            assertTrue("$name apply should be a typed local block; got $error", error is MealAlternativeService.AlternativeException.SafetyBlocked)
            assertTrue(expectedRule in (error as MealAlternativeService.AlternativeException.SafetyBlocked).decision.rules)
            assertEquals(versionCount, plans.versions(profileId, initial.planId).first().size)
            assertEquals(initial.version.id, plans.loadLatestSnapshot(profileId, week.toEpochDay())!!.version.id)
            db.userProfileDao().update(profile)
        }

        assertTrue(gateway.requests.none { request ->
            request.userPrompt.contains("healthDeclarationJson") ||
                request.userPrompt.contains("pregnancyOrLactation") ||
                request.userPrompt.contains(positive)
        })
    }

    @Test
    fun positiveMaintenanceMealSwapUsesTdeePolicyAndPreservesSavedGoal() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val original = profiles.get(profileId)!!
        val positive = HealthDeclarationCodec.encode(
            HealthDeclaration(pregnancyOrLactation = false, clinicianManagedConditionOrMedication = true, confirmedAtEpochMillis = time.nowEpochMillis()),
        )
        db.userProfileDao().update(original.copy(goal = "Mantenimento", healthDeclarationJson = positive))
        val week = SixMonthHistoryFixture.TODAY
        val tdee = services().calculations.profileSnapshot(profileId, week)!!.calculation.tdeeKcal!!
        services().generation.generateWeek(profileId, week)
        val plans = MealPlanRepository(db)
        val before = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val meal = before.version.days.first().meals.last()
        val preview = services().mealAlternative.generate(profileId, week.toEpochDay(), week.toEpochDay(), meal.id)

        val applied = services().mealAlternative.apply(preview, preview.items.first())
        val after = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!

        assertTrue(applied.versionId > before.version.id)
        assertEquals("Mantenimento", profiles.get(profileId)!!.goal)
        assertTrue(after.version.days.all { day ->
            day.totalKcal?.let { it >= tdee * 0.97 && it <= tdee * 1.03 } == true
        })
    }

    @Test
    fun mealAlternativeApplyRejectsStaleProfileBiaAndCompetingPlanVersion() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val plans = MealPlanRepository(db)
        val generation = services().generation
        val week = SixMonthHistoryFixture.TODAY
        generation.generateWeek(profileId, week)
        val initial = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val meal = initial.version.days.first().meals.last()
        val preview = services().mealAlternative.generate(profileId, week.toEpochDay(), week.toEpochDay(), meal.id)
        val otherProfileId = db.userProfileDao().insert(db.userProfileDao().get(profileId)!!.copy(id = 0, name = "Alternative target profile"))
        store.selectProfile(otherProfileId)
        val profileSwitchError = runCatching { services().mealAlternative.apply(preview, preview.items.first()) }.exceptionOrNull()
        assertTrue("The profile captured by the preview must not be inferred from another active profile", profileSwitchError is MealAlternativeService.AlternativeException.StalePlan)
        assertNull(plans.getPlanForWeek(otherProfileId, week.toEpochDay()))
        store.selectProfile(profileId)
        val latestBia = db.biaMeasurementDao().getAll(profileId).maxBy { it.measuredAtEpochMillis }
        db.biaMeasurementDao().insert(latestBia.copy(
            id = 0,
            measuredAtEpochMillis = time.nowEpochMillis() + 1,
            weightKg = (latestBia.weightKg ?: 80f) + 2f,
        ))

        val biaError = runCatching { services().mealAlternative.apply(preview, preview.items.first()) }.exceptionOrNull()
        assertTrue("Changed BIA must make the generated target stale: $biaError", biaError is MealAlternativeService.AlternativeException.StalePlan)
        assertEquals(initial.version.id, plans.loadLatestSnapshot(profileId, week.toEpochDay())!!.version.id)

        val nextWeek = week.plusWeeks(1)
        generation.generateWeek(profileId, nextWeek)
        val secondSnapshot = plans.loadLatestSnapshot(profileId, nextWeek.toEpochDay())!!
        val secondMeal = secondSnapshot.version.days.first().meals.last()
        val secondPreview = services().mealAlternative.generate(profileId, nextWeek.toEpochDay(), nextWeek.toEpochDay(), secondMeal.id)
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val originalProfile = profiles.get(profileId)!!
        db.userProfileDao().update(originalProfile.copy(heightCm = (originalProfile.heightCm ?: 170f) + 1f))

        val profileError = runCatching { services().mealAlternative.apply(secondPreview, secondPreview.items.first()) }.exceptionOrNull()
        assertTrue("Changed profile inputs must make the generated target stale: $profileError", profileError is MealAlternativeService.AlternativeException.StalePlan)
        assertEquals(secondSnapshot.version.id, plans.loadLatestSnapshot(profileId, nextWeek.toEpochDay())!!.version.id)
        db.userProfileDao().update(originalProfile)

        val competingWeek = nextWeek.plusWeeks(1)
        generation.generateWeek(profileId, competingWeek)
        val competingSnapshot = plans.loadLatestSnapshot(profileId, competingWeek.toEpochDay())!!
        val competingMeal = competingSnapshot.version.days.first().meals.last()
        val competingPreview = services().mealAlternative.generate(profileId, competingWeek.toEpochDay(), competingWeek.toEpochDay(), competingMeal.id)
        val competitor = plans.appendVersion(
            profileId = profileId,
            planId = competingSnapshot.planId,
            createdAtEpochMillis = time.nowEpochMillis() + 2,
            draft = draft(competingSnapshot.version.days.map { it.toDraftForTest() }).copy(reason = "COMPETING_VERSION"),
        )

        val competingError = runCatching { services().mealAlternative.apply(competingPreview, competingPreview.items.first()) }.exceptionOrNull()
        assertTrue("A competing append must win the version CAS: $competingError", competingError is MealAlternativeService.AlternativeException.StalePlan)
        val latest = plans.loadLatestSnapshot(profileId, competingWeek.toEpochDay())!!
        assertEquals(competitor, latest.version.id)
        assertEquals("COMPETING_VERSION", latest.version.reason)
    }

    @Test
    fun historicalMealApplyIsSafetyGatedAtThePlanWriteBoundary() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = SixMonthHistoryFixture.TODAY
        services().generation.generateWeek(profileId, week)
        val plans = MealPlanRepository(db)
        val target = plans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        val sourceMeal = target.version.days.first().meals.last()
        val historicalWeek = week.minusWeeks(1)
        val historyPlanId = plans.createPlan(profileId, historicalWeek.toEpochDay(), time.nowEpochMillis() - 10_000)
        plans.appendVersion(
            profileId = profileId,
            planId = historyPlanId,
            createdAtEpochMillis = time.nowEpochMillis() - 9_000,
            draft = draft(listOf(historyDay(historicalWeek, meal(
                title = "Pasto storico",
                type = sourceMeal.type,
                kcal = sourceMeal.kcal!!,
                time = sourceMeal.timeMinutes!!,
            )))),
        )
        val historical = services().mealAlternative.historicalAlternatives(week.toEpochDay(), week.toEpochDay(), sourceMeal.id).single()
        val profile = com.myfitai.app.data.repository.UserProfileRepository(db).get(profileId)!!
        db.userProfileDao().update(profile.copy(birthDateEpochDay = SixMonthHistoryFixture.TODAY.minusYears(17).toEpochDay()))

        val error = runCatching {
            services().mealAlternative.applyHistorical(week.toEpochDay(), week.toEpochDay(), sourceMeal.id, historical)
        }.exceptionOrNull()

        assertTrue("Historical apply must be blocked before append: $error", error is MealAlternativeService.AlternativeException.SafetyBlocked)
        assertTrue(SafetyRuleCode.AGE_UNDER_18 in (error as MealAlternativeService.AlternativeException.SafetyBlocked).decision.rules)
        assertEquals(target.version.id, plans.loadLatestSnapshot(profileId, week.toEpochDay())!!.version.id)
    }

    @Test
    fun localMealAlternatives_excludesCandidatesViolatingCurrentHardConstraints() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val profile = com.myfitai.app.data.repository.UserProfileRepository(db).get(profileId)!!
        com.myfitai.app.data.repository.UserProfileRepository(db).update(
            profile.copy(dietaryPreferencesJson = com.myfitai.app.domain.food.DietaryProfile(allergies = listOf("arachidi")).toJson()),
        )
        val week = SixMonthHistoryFixture.TODAY
        val mealPlans = MealPlanRepository(db)
        val planId = mealPlans.createPlan(profileId, week.toEpochDay(), time.nowEpochMillis())
        mealPlans.appendVersion(profileId, planId, time.nowEpochMillis(), draft(listOf(historyDay(week, meal("Target", "Pranzo", 500, 780)))))
        val source = mealPlans.loadLatestSnapshot(profileId, week.toEpochDay())!!.version.days.single().meals.single()
        val historyWeek = week.minusDays(7)
        val historyPlanId = mealPlans.createPlan(profileId, historyWeek.toEpochDay(), time.nowEpochMillis())
        mealPlans.appendVersion(profileId, historyPlanId, time.nowEpochMillis(), draft(listOf(historyDay(
            historyWeek,
            meal("Riso e arachidi", "Pranzo", 500, 720, ingredientNames = listOf("Riso", "Arachidi")),
            meal("Riso e pollo", "Pranzo", 500, 720, ingredientNames = listOf("Riso", "Pollo")),
        ))))

        val matches = services().mealAlternative.historicalAlternatives(week.toEpochDay(), week.toEpochDay(), source.id)

        assertEquals(listOf("Riso e pollo"), matches.map { it.meal.title })
    }

    @Test
    fun applyingHistoricalAlternative_rejectsStaleTargetVersion() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = SixMonthHistoryFixture.TODAY
        val mealPlans = MealPlanRepository(db)
        val targetPlanId = mealPlans.createPlan(profileId, week.toEpochDay(), time.nowEpochMillis())
        mealPlans.appendVersion(profileId, targetPlanId, time.nowEpochMillis(), draft(listOf(historyDay(week, meal("Target", "Pranzo", 500, 780)))))
        val sourceMeal = mealPlans.loadLatestSnapshot(profileId, week.toEpochDay())!!.version.days.single().meals.single()
        val historyWeek = week.minusDays(7)
        val historyPlanId = mealPlans.createPlan(profileId, historyWeek.toEpochDay(), time.nowEpochMillis())
        mealPlans.appendVersion(profileId, historyPlanId, time.nowEpochMillis(), draft(listOf(historyDay(historyWeek, meal("Storico", "Pranzo", 500, 780)))))
        val match = services().mealAlternative.historicalAlternatives(week.toEpochDay(), week.toEpochDay(), sourceMeal.id).single()
        val targetSnapshot = mealPlans.loadLatestSnapshot(profileId, week.toEpochDay())!!
        mealPlans.appendVersion(profileId, targetPlanId, time.nowEpochMillis() + 1, draft(targetSnapshot.version.days.map { it.toDraftForTest() }))

        val error = runCatching {
            services().mealAlternative.applyHistorical(week.toEpochDay(), week.toEpochDay(), sourceMeal.id, match)
        }.exceptionOrNull()

        assertTrue(error is MealAlternativeService.AlternativeException.StalePlan)
    }

    @Test
    fun unreadableDietaryPreferences_blockPlanAdviceAlternativesAndApplyWithoutCallingAi() = runBlocking {
        val week = SixMonthHistoryFixture.TODAY
        val generation = services().generation
        generation.generateWeek(week)
        val profileId = store.currentIdOrNull()!!
        val meal = MealPlanRepository(db).loadLatestSnapshot(profileId, week.toEpochDay())!!.version.days.first().meals.last()
        val alternatives = services().mealAlternative.generate(week.toEpochDay(), week.toEpochDay(), meal.id)

        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        profiles.update(profiles.get(profileId)!!.copy(dietaryPreferencesJson = "Allergico alle noci, niente lattosio"))
        val callsBefore = gateway.requests.size

        val planError = runCatching { services().generation.generateWeek(week.plusDays(7)) }.exceptionOrNull()
        assertTrue("plan error=$planError", planError is NutritionPlanGenerationService.GenerationException.ConstraintsUnreadable)
        val advice = services().advice.ask("Mi va un gelato")
        assertFalse(advice.accepted)
        assertEquals(com.myfitai.app.domain.food.DietaryProfile.UNREADABLE_MESSAGE, advice.answer)
        val generateError = runCatching { services().mealAlternative.generate(week.toEpochDay(), week.toEpochDay(), meal.id) }.exceptionOrNull()
        assertTrue("alt error=$generateError", generateError is MealAlternativeService.AlternativeException.ConstraintsUnreadable)
        val applyError = runCatching { services().mealAlternative.apply(alternatives, alternatives.items.first()) }.exceptionOrNull()
        assertTrue("apply error=$applyError", applyError is MealAlternativeService.AlternativeException.ConstraintsUnreadable)
        assertEquals(callsBefore, gateway.requests.size)
    }

    @Test
    fun advice_isNutritionScopedFiveSuggestionsAndDoesNotPersistConversation() = runBlocking {
        val service = services().advice
        val before = db.cheatEntryDao().observeAll(store.currentIdOrNull()!!).first().size
        val result = service.ask("Mi va un gelato")
        val after = db.cheatEntryDao().observeAll(store.currentIdOrNull()!!).first().size

        assertTrue("advice answer=${result.answer} provider=${result.providerLabel}", result.accepted)
        assertEquals("advice answer=${result.answer} provider=${result.providerLabel}", 5, result.suggestions.size)
        assertTrue("advice suggestions=${result.suggestions}", result.suggestions.all { it.estimatedKcal > 0 })
        assertEquals(before, after)
        assertTrue(gateway.requests.any { it.schemaName == "myfitai_nutrition_advice_pipe_v1" })
        assertTrue(gateway.requests.last { it.schemaName == "myfitai_nutrition_advice_pipe_v1" }.userPrompt.lineSequence().any { it.startsWith("U:") })
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
        val request = gateway.requests.last { it.schemaName == "myfitai_cheat_understanding_pipe_v1" }
        assertEquals("Nutrition-label image attached: false", request.userPrompt.lineSequence().first { it.contains("image attached:") })
        assertTrue(request.systemPrompt.contains("Your entire scope is nutritional estimation"))
        assertEquals(before, db.cheatEntryDao().observeAll(profileId).first().size)

        val result = services().cheat.registerAndAdapt(profileId, input, understanding)
        assertEquals(before + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertTrue("The confirmed deviation should adapt eligible future meals", result.adapted)
        assertTrue("The adaptation must append an immutable plan version", result.newVersionId != null)
    }

    @Test
    fun cheatFreeTextPrivacyWarningIsAvailableAndExplicit() {
        val warning = ApplicationProvider.getApplicationContext<Context>()
            .getString(com.myfitai.app.R.string.cheat_free_text_privacy_warning)

        listOf(
            "solo informazioni alimentari",
            "diagnosi",
            "farmaci",
            "sintomi",
            "altre informazioni sanitarie",
            "provider IA",
            "job",
            "export",
            "backup",
        ).forEach { expected -> assertTrue("Privacy warning should mention '$expected'", warning.contains(expected, ignoreCase = true)) }
    }

    @Test
    fun cheatInputFingerprintIsStableOpaqueAndIncludesEveryInputWithoutDelimiterCollisions() {
        val service = services().cheat
        val input = CheatAdjustmentService.Input(
            description = "DESCRIPTION-DO-NOT-EXPOSE",
            quantityText = "QUANTITY-DO-NOT-EXPOSE",
            notes = "NOTES-DO-NOT-EXPOSE",
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(SixMonthHistoryFixture.TODAY.minusDays(10), 20),
        )
        val fingerprint = service.inputFingerprint(input)

        assertEquals(fingerprint, service.inputFingerprint(input.copy()))
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        listOf(
            input.copy(description = "Different food"),
            input.copy(quantityText = "Different serving"),
            input.copy(notes = "Different food context"),
            input.copy(occurredAtEpochMillis = input.occurredAtEpochMillis + 1),
        ).forEach { changed -> assertTrue(fingerprint != service.inputFingerprint(changed)) }
        listOf(input.description, input.quantityText!!, input.notes!!).forEach { raw ->
            assertFalse("Fingerprint must not expose raw field text", fingerprint.contains(raw))
        }

        val delimiterLeft = input.copy(description = "A|B", quantityText = "C")
        val delimiterRight = input.copy(description = "A", quantityText = "B|C")
        assertTrue(service.inputFingerprint(delimiterLeft) != service.inputFingerprint(delimiterRight))

        val firstPhoto = AiImageInput(mimeType = "image/jpeg", base64Data = "Zm9v")
        val secondPhoto = AiImageInput(mimeType = "image/jpeg", base64Data = "YmFy")
        val firstPhotoInput = input.copy(
            foodImage = firstPhoto,
            photoFingerprint = CheatAdjustmentService.photoFingerprint(firstPhoto),
        )
        val secondPhotoInput = input.copy(
            foodImage = secondPhoto,
            photoFingerprint = CheatAdjustmentService.photoFingerprint(secondPhoto),
        )
        val firstPhotoFingerprint = service.inputFingerprint(firstPhotoInput)
        val secondPhotoFingerprint = service.inputFingerprint(secondPhotoInput)
        assertTrue(firstPhotoFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue(firstPhotoFingerprint != secondPhotoFingerprint)
        assertFalse(firstPhotoFingerprint.contains(firstPhoto.base64Data))
    }

    @Test
    fun cheatAdjustmentWorkerReplay_reusesTheProfileEventAndPlanVersion() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val eventDate = SixMonthHistoryFixture.TODAY.minusDays(10)
        val weekStart = eventDate.minusDays((eventDate.dayOfWeek.value - 1).toLong())
        val plans = MealPlanRepository(db)
        val planId = plans.getPlanForWeek(profileId, weekStart.toEpochDay())!!.id
        val versionsBefore = plans.versions(profileId, planId).first().size
        val entriesBefore = db.cheatEntryDao().observeAll(profileId).first().size
        val input = CheatAdjustmentService.Input(
            description = "Pizza margherita",
            quantityText = "una porzione",
            notes = "Evento confermato dal test",
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(eventDate, 20),
        )
        val service = services().cheat
        val understanding = service.analyze(input)
        assertTrue(understanding.inputFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertFalse(understanding.inputFingerprint.contains(input.notes.orEmpty()))

        fun params(value: CheatAdjustmentService.Input, estimate: CheatAdjustmentService.Understanding) = Data.Builder()
            .putString(CheatAdjustmentAiJobHandler.KEY_DESCRIPTION, value.description)
            .putString(CheatAdjustmentAiJobHandler.KEY_QUANTITY, value.quantityText)
            .putString(CheatAdjustmentAiJobHandler.KEY_NOTES, value.notes)
            .putLong(CheatAdjustmentAiJobHandler.KEY_OCCURRED_AT, value.occurredAtEpochMillis)
            .putInt(CheatAdjustmentAiJobHandler.KEY_KCAL, estimate.estimate.kcal)
            .putFloat(CheatAdjustmentAiJobHandler.KEY_PROTEIN, estimate.estimate.proteinG)
            .putFloat(CheatAdjustmentAiJobHandler.KEY_CARBS, estimate.estimate.carbsG)
            .putFloat(CheatAdjustmentAiJobHandler.KEY_FAT, estimate.estimate.fatG)
            .putString(CheatAdjustmentAiJobHandler.KEY_CONFIDENCE, estimate.estimate.confidence)
            .putString(CheatAdjustmentAiJobHandler.KEY_ESTIMATE_NOTES, estimate.estimate.notes)
            .putString(CheatAdjustmentAiJobHandler.KEY_UNDERSTOOD, estimate.understoodFood)
            .putString(CheatAdjustmentAiJobHandler.KEY_AGENT_PROVIDER, estimate.provider)
            .putString(CheatAdjustmentAiJobHandler.KEY_AGENT_MODEL, estimate.model)
            .putString(CheatAdjustmentAiJobHandler.KEY_PHOTO_FINGERPRINT, value.photoFingerprint)
            .putString(CheatAdjustmentAiJobHandler.KEY_FINGERPRINT, estimate.inputFingerprint)
            .build()

        fun success(outcome: AiJobOutcome): JSONObject {
            val committed = outcome as? AiJobOutcome.Success ?: error("Cheat job failed: $outcome")
            return JSONObject(committed.output.getString(CheatAdjustmentAiJobHandler.KEY_PAYLOAD)!!)
        }

        val handler = CheatAdjustmentAiJobHandler(service)
        val jobKey = "${CheatAdjustmentAiJobHandler.CONFIRMED_JOB_KEY_PREFIX}${input.occurredAtEpochMillis}"
        val first = success(handler.execute(profileId, jobKey, params(input, understanding)))
        val replay = success(handler.execute(profileId, jobKey, params(input, understanding)))

        assertEquals(first.getLong("cheatId"), replay.getLong("cheatId"))
        assertEquals(first.get("newVersionId"), replay.get("newVersionId"))
        assertEquals(entriesBefore + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertEquals(versionsBefore + if (first.getBoolean("adapted")) 1 else 0, plans.versions(profileId, planId).first().size)

        val mismatchedKey = handler.execute(
            profileId,
            "${CheatAdjustmentAiJobHandler.CONFIRMED_JOB_KEY_PREFIX}${input.occurredAtEpochMillis + 1}",
            params(input, understanding),
        )
        assertTrue("A retry key cannot refer to a different event time", mismatchedKey is AiJobOutcome.Failure)
        assertEquals(entriesBefore + 1, db.cheatEntryDao().observeAll(profileId).first().size)

        val distinctInput = input.copy(occurredAtEpochMillis = input.occurredAtEpochMillis + 60_000)
        val distinctUnderstanding = service.analyze(distinctInput)
        val distinct = success(
            handler.execute(
                profileId,
                "${CheatAdjustmentAiJobHandler.CONFIRMED_JOB_KEY_PREFIX}${distinctInput.occurredAtEpochMillis}",
                params(distinctInput, distinctUnderstanding),
            ),
        )
        assertTrue("A distinct event timestamp remains a separate accepted operation", first.getLong("cheatId") != distinct.getLong("cheatId"))
        assertEquals(entriesBefore + 2, db.cheatEntryDao().observeAll(profileId).first().size)
    }

    @Test
    fun acceptingAdviceSuggestionTwiceAfterRetainedViewModelRecreation_writesOnce() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val serviceSet = services()
        serviceSet.generation.generateWeek(SixMonthHistoryFixture.TODAY)
        val plans = MealPlanRepository(db)
        val planId = plans.getPlanForWeek(profileId, SixMonthHistoryFixture.TODAY.toEpochDay())!!.id
        val versionsBefore = plans.versions(profileId, planId).first().size
        val entriesBefore = db.cheatEntryDao().observeAll(profileId).first().size
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModelStore = ViewModelStore()
        val suggestion = ADVICE_TEST_SUGGESTION

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(NutritionAdviceViewModel::class.java))
                return NutritionAdviceViewModel(
                    adviceService = serviceSet.advice,
                    cheatService = serviceSet.cheat,
                    notificationScheduler = NotificationScheduler(context, plans, store, time),
                    aiJobScheduler = AiJobScheduler(context, NetworkType.NOT_REQUIRED),
                    activeProfileStore = store,
                    nowEpochMillis = time::nowEpochMillis,
                    refreshNotifications = {},
                ) as T
            }
        }

        try {
            val initialActivityViewModel = ViewModelProvider(viewModelStore, factory)[NutritionAdviceViewModel::class.java]
            val question = "Uno spuntino?"
            adviceWorkHandler.failNextExecution.set(true)
            initialActivityViewModel.ask(question)
            val firstRequestIdentity = initialActivityViewModel.currentRequestRunIdentity
            val firstJobKey = initialActivityViewModel.currentJobKey
            assertNotNull(firstRequestIdentity)
            assertEquals("New job key is the opaque run identity", firstRequestIdentity, firstJobKey)
            scheduledAdviceJobs += profileId to firstJobKey!!
            withTimeout(15_000) {
                initialActivityViewModel.state.first { !it.running && it.failure?.canRetry == true }
            }
            initialActivityViewModel.ask(question)
            assertEquals("Retry must retain the run identity", firstRequestIdentity, initialActivityViewModel.currentRequestRunIdentity)
            assertEquals("Retry must replace only the same unique job", firstJobKey, initialActivityViewModel.currentJobKey)
            val completed = withTimeout(15_000) {
                initialActivityViewModel.state.first { !it.running && it.suggestions.isNotEmpty() }
            }
            assertEquals("answer:$firstJobKey", completed.answer)

            initialActivityViewModel.acceptSuggestion(suggestion)
            // A second click while the first write is in flight must share the same operation identity.
            initialActivityViewModel.acceptSuggestion(suggestion)
            val accepted = withTimeout(15_000) {
                initialActivityViewModel.state.first { !it.accepting && (it.acceptedResult != null || it.error != null) }
            }
            assertNotNull("Acceptance failed: ${accepted.error}", accepted.acceptedResult)
            val firstResult = accepted.acceptedResult!!
            assertEquals(entriesBefore + 1, db.cheatEntryDao().observeAll(profileId).first().size)

            // The recreated Activity receives the retained ViewModel and may repeat the old click after its UI settles.
            val recreatedActivityViewModel = ViewModelProvider(viewModelStore, factory)[NutritionAdviceViewModel::class.java]
            assertSame(initialActivityViewModel, recreatedActivityViewModel)
            assertEquals("Retaining the ViewModel across Activity recreation must retain the run identity", firstRequestIdentity, recreatedActivityViewModel.currentRequestRunIdentity)
            recreatedActivityViewModel.consumeAcceptedResult()
            recreatedActivityViewModel.acceptSuggestion(suggestion)
            assertEquals(entriesBefore + 1, db.cheatEntryDao().observeAll(profileId).first().size)
            assertEquals(
                versionsBefore + if (firstResult.adapted) 1 else 0,
                plans.versions(profileId, planId).first().size,
            )

            // A new execution of the same question gets a new WorkManager identity and acceptance identity.
            recreatedActivityViewModel.ask(question)
            val secondRequestIdentity = recreatedActivityViewModel.currentRequestRunIdentity
            val secondJobKey = recreatedActivityViewModel.currentJobKey
            assertNotNull(secondRequestIdentity)
            assertNotEquals("A fresh run cannot replace the earlier WorkManager request", firstJobKey, secondJobKey)
            assertEquals(secondRequestIdentity, secondJobKey)
            scheduledAdviceJobs += profileId to secondJobKey!!
            withTimeout(15_000) {
                recreatedActivityViewModel.state.first { !it.running && it.suggestions.isNotEmpty() }
            }
            assertTrue("Each new execution needs its own request identity", firstRequestIdentity != secondRequestIdentity)
            assertEquals("Only the second WorkManager output may be rendered", "answer:$secondJobKey", recreatedActivityViewModel.state.value.answer)
            recreatedActivityViewModel.acceptSuggestion(suggestion)
            val secondAccepted = withTimeout(15_000) {
                recreatedActivityViewModel.state.first { !it.accepting && (it.acceptedResult != null || it.error != null) }
            }
            val secondResult = secondAccepted.acceptedResult
            assertNotNull("The identical suggestion from a new execution remains acceptable: ${secondAccepted.error}", secondResult)
            assertTrue("A new run must append a new event", firstResult.cheatId != secondResult!!.cheatId)
            assertEquals(entriesBefore + 2, db.cheatEntryDao().observeAll(profileId).first().size)
            assertEquals(
                versionsBefore + (if (firstResult.adapted) 1 else 0) + (if (secondResult.adapted) 1 else 0),
                plans.versions(profileId, planId).first().size,
            )
        } finally {
            viewModelStore.clear()
        }
    }

    @Test
    fun adviceStartupFailuresAreRecoverableAndKeepRetryIdentity() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val serviceSet = services()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = AiJobScheduler(context, NetworkType.NOT_REQUIRED)
        val enqueueAttempts = AtomicInteger()
        val observationAttempts = AtomicInteger()
        val failNextEnqueue = AtomicBoolean(false)
        val failNextOperationResult = AtomicBoolean(false)
        val failNextObservation = AtomicBoolean(false)
        val viewModelStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(NutritionAdviceViewModel::class.java))
                return NutritionAdviceViewModel(
                    adviceService = serviceSet.advice,
                    cheatService = serviceSet.cheat,
                    notificationScheduler = NotificationScheduler(context, MealPlanRepository(db), store, time),
                    aiJobScheduler = scheduler,
                    activeProfileStore = store,
                    enqueueAdviceJob = { requestedProfileId, jobKey, params ->
                        enqueueAttempts.incrementAndGet()
                        if (failNextEnqueue.compareAndSet(true, false)) {
                            throw IllegalStateException("private question must never be shown")
                        }
                        if (failNextOperationResult.compareAndSet(true, false)) {
                            val future = ResolvableFuture.create<Operation.State.SUCCESS>()
                            CoroutineScope(Dispatchers.IO).launch {
                                delay(25)
                                future.setException(IllegalStateException("private operation result must never be shown"))
                            }
                            FakeWorkOperation(future)
                        } else {
                            scheduledAdviceJobs += requestedProfileId to jobKey
                            scheduler.enqueue(
                                AiJobType.NUTRITION_ADVICE,
                                requestedProfileId,
                                jobKey,
                                params = params,
                                replaceExisting = true,
                            )
                        }
                    },
                    observeAdviceJob = { requestedProfileId, jobKey ->
                        observationAttempts.incrementAndGet()
                        if (failNextObservation.compareAndSet(true, false)) {
                            throw IllegalStateException("private observer details must never be shown")
                        }
                        scheduler.observe(AiJobType.NUTRITION_ADVICE, requestedProfileId, jobKey)
                    },
                ) as T
            }
        }

        try {
            val viewModel = ViewModelProvider(viewModelStore, factory)[NutritionAdviceViewModel::class.java]

            // Data.Builder rejects a string over WorkManager's serialized Data limit before enqueue is called.
            viewModel.ask("x".repeat(11_000))
            val oversizedRunKey = viewModel.currentJobKey!!
            val oversizedFailure = withTimeout(15_000) {
                viewModel.state.first { !it.running && it.failure?.canRetry == true }
            }
            assertFalse(oversizedFailure.running)
            assertNull(oversizedFailure.error)
            assertNull("Startup errors must not expose technical details", oversizedFailure.failure?.technicalCode)
            assertEquals("Oversized data must fail before scheduler invocation", 0, enqueueAttempts.get())

            // A corrected ask starts a fresh UUID run and proves the ViewModel is no longer stuck busy.
            viewModel.ask("Uno spuntino?")
            val correctedJobKey = viewModel.currentJobKey!!
            assertNotEquals(oversizedRunKey, correctedJobKey)
            withTimeout(15_000) { viewModel.state.first { !it.running && it.suggestions.isNotEmpty() } }

            // Operation.result fails after enqueue returns, not via a synchronous scheduler throw.
            val asyncFailureQuestion = "Un consiglio per lo spuntino?"
            val observationsBeforeAsyncFailure = observationAttempts.get()
            failNextOperationResult.set(true)
            viewModel.ask(asyncFailureQuestion)
            val failedOperationKey = viewModel.currentJobKey!!
            val operationFailure = withTimeout(15_000) {
                viewModel.state.first { !it.running && it.failure?.canRetry == true }
            }
            assertFalse(operationFailure.running)
            assertNull(operationFailure.error)
            assertNull(operationFailure.failure?.technicalCode)
            assertEquals("Observer must not start when Operation.result fails", observationsBeforeAsyncFailure, observationAttempts.get())
            viewModel.ask(asyncFailureQuestion)
            assertEquals("Retry after async Operation failure must reuse the run UUID", failedOperationKey, viewModel.currentJobKey)
            val asyncRetry = withTimeout(15_000) {
                viewModel.state.first { !it.running && it.suggestions.isNotEmpty() }
            }
            assertEquals("answer:$failedOperationKey", asyncRetry.answer)

            // A synchronous scheduler exception is generic in UI, while a manual retry reuses this UUID.
            val schedulerFailureQuestion = "Una merenda?"
            failNextEnqueue.set(true)
            viewModel.ask(schedulerFailureQuestion)
            val failedSchedulerKey = viewModel.currentJobKey!!
            val schedulerFailure = withTimeout(15_000) {
                viewModel.state.first { !it.running && it.failure?.canRetry == true }
            }
            assertFalse(schedulerFailure.running)
            assertNull(schedulerFailure.error)
            assertNull(schedulerFailure.failure?.technicalCode)

            viewModel.ask(schedulerFailureQuestion)
            assertEquals("Retry must reuse the failed run UUID", failedSchedulerKey, viewModel.currentJobKey)
            val schedulerRetry = withTimeout(15_000) {
                viewModel.state.first { !it.running && it.suggestions.isNotEmpty() }
            }
            assertEquals("answer:$failedSchedulerKey", schedulerRetry.answer)

            // Observer creation/collection failures also clear running and leave the same run retryable.
            val observerFailureQuestion = "Mi dai un consiglio?"
            failNextObservation.set(true)
            viewModel.ask(observerFailureQuestion)
            val failedObserverKey = viewModel.currentJobKey!!
            val observerFailure = withTimeout(15_000) {
                viewModel.state.first { !it.running && it.failure?.canRetry == true }
            }
            assertFalse(observerFailure.running)
            assertNull(observerFailure.error)
            assertNull(observerFailure.failure?.technicalCode)

            viewModel.ask(observerFailureQuestion)
            assertEquals("Retry after observer failure must reuse the run UUID", failedObserverKey, viewModel.currentJobKey)
            val observerRetry = withTimeout(15_000) {
                viewModel.state.first { !it.running && it.suggestions.isNotEmpty() }
            }
            assertEquals("answer:$failedObserverKey", observerRetry.answer)
            assertTrue("Each enqueue attempt should be accounted without logging request content", enqueueAttempts.get() >= 5)
        } finally {
            viewModelStore.clear()
        }
    }

    @Test
    fun adviceWaitsForPersistedWorkInfoAndDoesNotHangOnPersistentNullOrCancellation() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val serviceSet = services()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = AiJobScheduler(context, NetworkType.NOT_REQUIRED)
        val transientStore = ViewModelStore()
        val transientFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(NutritionAdviceViewModel::class.java))
                return NutritionAdviceViewModel(
                    adviceService = serviceSet.advice,
                    cheatService = serviceSet.cheat,
                    notificationScheduler = NotificationScheduler(context, MealPlanRepository(db), store, time),
                    aiJobScheduler = scheduler,
                    activeProfileStore = store,
                    enqueueAdviceJob = { requestedProfileId, jobKey, params ->
                        scheduledAdviceJobs += requestedProfileId to jobKey
                        scheduler.enqueue(AiJobType.NUTRITION_ADVICE, requestedProfileId, jobKey, params = params, replaceExisting = true)
                    },
                    observeAdviceJob = { requestedProfileId, jobKey ->
                        flow {
                            emit(null)
                            delay(100)
                            scheduler.observe(AiJobType.NUTRITION_ADVICE, requestedProfileId, jobKey).collect { emit(it) }
                        }
                    },
                    observationStartTimeoutMillis = 2_000L,
                ) as T
            }
        }
        adviceWorkHandler.blockExecutions.set(true)

        try {
            val transientViewModel = ViewModelProvider(transientStore, transientFactory)[NutritionAdviceViewModel::class.java]
            transientViewModel.ask("Una domanda dopo un valore nullo transitorio?")
            val transientKey = transientViewModel.currentJobKey!!
            scheduledAdviceJobs += profileId to transientKey
            withTimeout(15_000) { adviceWorkHandler.startedFor(transientKey).await() }
            val waitingState = transientViewModel.state.value
            assertTrue("Initial null WorkInfo must not end the run", waitingState.running)
            assertNull(waitingState.failure)
            adviceWorkHandler.release(transientKey)
            val completed = withTimeout(15_000) {
                transientViewModel.state.first { !it.running && it.suggestions.isNotEmpty() }
            }
            assertEquals("answer:$transientKey", completed.answer)
        } finally {
            adviceWorkHandler.releaseAll()
            transientStore.clear()
        }

        val persistentNullStore = ViewModelStore()
        val persistentNullFactory = adviceStartupFactory(
            context = context,
            serviceSet = serviceSet,
            scheduler = scheduler,
            enqueue = { _, _, _ -> completedWorkOperation() },
            observe = { _, _ ->
                flow {
                    emit(null)
                    delay(20)
                    emit(WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet(), Data.EMPTY))
                    emit(null)
                    awaitCancellation()
                }
            },
            observationTimeoutMillis = 100L,
        )
        try {
            val viewModel = ViewModelProvider(persistentNullStore, persistentNullFactory)[NutritionAdviceViewModel::class.java]
            viewModel.ask("Nessun WorkInfo persistente?")
            val failedKey = viewModel.currentJobKey!!
            val state = withTimeout(5_000) { viewModel.state.first { !it.running && it.failure?.canRetry == true } }
            assertFalse(state.running)
            assertNull(state.error)
            viewModel.ask("Nessun WorkInfo persistente?")
            assertEquals("Timeout retry must keep its run identity", failedKey, viewModel.currentJobKey)
        } finally {
            persistentNullStore.clear()
        }

        val cancelledStore = ViewModelStore()
        val cancelledWork = WorkInfo(UUID.randomUUID(), WorkInfo.State.CANCELLED, emptySet(), Data.EMPTY)
        val cancelledFactory = adviceStartupFactory(
            context = context,
            serviceSet = serviceSet,
            scheduler = scheduler,
            enqueue = { _, _, _ -> completedWorkOperation() },
            observe = { _, _ -> flow { emit(cancelledWork) } },
            observationTimeoutMillis = 100L,
        )
        try {
            val viewModel = ViewModelProvider(cancelledStore, cancelledFactory)[NutritionAdviceViewModel::class.java]
            viewModel.ask("Richiesta annullata?")
            val state = withTimeout(5_000) { viewModel.state.first { !it.running && it.failure?.canRetry == true } }
            assertFalse(state.running)
            assertNull(state.error)
        } finally {
            cancelledStore.clear()
        }
    }

    @Test
    fun adviceSucceededWithoutPayloadOrWithMalformedPayloadIsRetryable() = runBlocking {
        val serviceSet = services()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = AiJobScheduler(context, NetworkType.NOT_REQUIRED)
        val viewModelStore = ViewModelStore()
        val observationAttempts = AtomicInteger()
        val validPayload = JSONObject()
            .put("question", "Uno spuntino?")
            .put("answer", "Risposta valida")
            .put("assumptions", "")
            .put("providerLabel", "Fixture")
            .put("suggestions", JSONArray())
            .toString()
        fun workInfo(outputData: Data) = WorkInfo(
            UUID.randomUUID(),
            WorkInfo.State.SUCCEEDED,
            emptySet(),
            outputData,
        )
        val factory = adviceStartupFactory(
            context = context,
            serviceSet = serviceSet,
            scheduler = scheduler,
            enqueue = { _, _, _ -> completedWorkOperation() },
            observe = { _, _ ->
                val attempt = observationAttempts.getAndIncrement()
                val output = when (attempt) {
                    0 -> Data.EMPTY
                    1 -> Data.Builder()
                        .putString(NutritionAdviceAiJobHandler.KEY_PAYLOAD, "{\"answer\":\"private response fragment")
                        .build()
                    else -> Data.Builder()
                        .putString(NutritionAdviceAiJobHandler.KEY_PAYLOAD, validPayload)
                        .build()
                }
                flow { emit(workInfo(output)) }
            },
            observationTimeoutMillis = 100L,
        )

        try {
            val viewModel = ViewModelProvider(viewModelStore, factory)[NutritionAdviceViewModel::class.java]
            val question = "Uno spuntino?"
            viewModel.ask(question)
            val requestIdentity = viewModel.currentRequestRunIdentity
            val missingPayload = withTimeout(5_000) {
                viewModel.state.first { !it.running && it.failure?.canRetry == true }
            }
            assertFalse(missingPayload.running)
            assertNull(missingPayload.answer)
            assertNull(missingPayload.error)
            assertNull("Missing output must not expose a technical detail", missingPayload.failure?.technicalCode)

            viewModel.ask(question)
            assertEquals("A user retry must retain its run identity", requestIdentity, viewModel.currentRequestRunIdentity)
            val malformedPayload = withTimeout(5_000) {
                viewModel.state.first { !it.running && it.failure?.canRetry == true }
            }
            assertFalse(malformedPayload.running)
            assertNull(malformedPayload.answer)
            assertNull(malformedPayload.error)
            assertNull("Malformed output must not expose a technical detail", malformedPayload.failure?.technicalCode)
            assertFalse(
                "Malformed response content must never reach the user-facing failure",
                context.getString(malformedPayload.failure!!.messageRes).contains("private response fragment"),
            )

            viewModel.ask(question)
            val completed = withTimeout(5_000) {
                viewModel.state.first { !it.running && it.answer != null }
            }
            assertEquals("Risposta valida", completed.answer)
            assertNull("A terminal success followed by flow completion stays successful", completed.failure)
            assertNull(completed.error)
            assertEquals(3, observationAttempts.get())
        } finally {
            viewModelStore.clear()
        }
    }

    @Test
    fun adviceObserverCompletionAfterNonTerminalWorkInfoIsRetryable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceSet = services()
        val scheduler = AiJobScheduler(context, NetworkType.NOT_REQUIRED)
        val viewModelStore = ViewModelStore()
        val observationAttempts = AtomicInteger()
        val factory = adviceStartupFactory(
            context = context,
            serviceSet = serviceSet,
            scheduler = scheduler,
            enqueue = { _, _, _ -> completedWorkOperation() },
            observe = { _, _ ->
                observationAttempts.incrementAndGet()
                flow {
                    emit(WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet(), Data.EMPTY))
                    emit(WorkInfo(UUID.randomUUID(), WorkInfo.State.RUNNING, emptySet(), Data.EMPTY))
                }
            },
            observationTimeoutMillis = 100L,
        )

        try {
            val viewModel = ViewModelProvider(viewModelStore, factory)[NutritionAdviceViewModel::class.java]
            viewModel.ask("Un consiglio?")
            val state = withTimeout(5_000) {
                viewModel.state.first { !it.running && it.failure?.canRetry == true }
            }

            assertFalse(state.running)
            assertNull(state.answer)
            assertNull(state.error)
            assertNull("Observer completion must not expose technical details", state.failure?.technicalCode)
            val runIdentity = viewModel.currentRequestRunIdentity
            viewModel.ask("Un consiglio?")
            assertEquals("The retry must reuse the failed run identity", runIdentity, viewModel.currentRequestRunIdentity)
            withTimeout(5_000) {
                while (observationAttempts.get() < 2) delay(10)
            }
            assertTrue("A second observer proves the follow-up ask was accepted", observationAttempts.get() >= 2)
        } finally {
            viewModelStore.clear()
        }
    }

    @Test
    fun cancellationOfAnOlderAdviceObserverCannotChangeTheNewRun() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceSet = services()
        val scheduler = AiJobScheduler(context, NetworkType.NOT_REQUIRED)
        val viewModelStore = ViewModelStore()
        val releaseOldObserver = CompletableDeferred<Unit>()
        val newObserverStarted = CompletableDeferred<Unit>()
        val observationAttempts = AtomicInteger()
        val factory = adviceStartupFactory(
            context = context,
            serviceSet = serviceSet,
            scheduler = scheduler,
            enqueue = { _, _, _ -> completedWorkOperation() },
            observe = { _, _ ->
                when (observationAttempts.getAndIncrement()) {
                    0 -> flow {
                        emit(WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED, emptySet(), Data.EMPTY))
                        releaseOldObserver.await()
                    }
                    else -> flow {
                        emit(WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet(), Data.EMPTY))
                        newObserverStarted.complete(Unit)
                        awaitCancellation()
                    }
                }
            },
            observationTimeoutMillis = 100L,
        )

        try {
            val viewModel = ViewModelProvider(viewModelStore, factory)[NutritionAdviceViewModel::class.java]
            viewModel.ask("Prima domanda")
            withTimeout(5_000) { viewModel.state.first { !it.running && it.failure != null } }
            val oldRunIdentity = viewModel.currentRequestRunIdentity

            viewModel.ask("Domanda successiva")
            val newRunIdentity = viewModel.currentRequestRunIdentity
            assertNotEquals(oldRunIdentity, newRunIdentity)
            withTimeout(5_000) { newObserverStarted.await() }
            withTimeout(5_000) { viewModel.state.first { it.running && it.question == "Domanda successiva" } }

            releaseOldObserver.cancel()
            delay(100)
            val current = viewModel.state.value
            assertTrue("Cancelling the stale observer must not clear the new run", current.running)
            assertEquals("Domanda successiva", current.question)
            assertNull(current.failure)
        } finally {
            releaseOldObserver.cancel()
            viewModelStore.clear()
        }
    }

    private fun adviceStartupFactory(
        context: Context,
        serviceSet: Services,
        scheduler: AiJobScheduler,
        enqueue: (Long, String, Data) -> Operation,
        observe: (Long, String) -> kotlinx.coroutines.flow.Flow<WorkInfo?>,
        observationTimeoutMillis: Long,
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NutritionAdviceViewModel::class.java))
            return NutritionAdviceViewModel(
                adviceService = serviceSet.advice,
                cheatService = serviceSet.cheat,
                notificationScheduler = NotificationScheduler(context, MealPlanRepository(db), this@AiWorkflowIntegrationTest.store, time),
                aiJobScheduler = scheduler,
                activeProfileStore = this@AiWorkflowIntegrationTest.store,
                enqueueAdviceJob = enqueue,
                observeAdviceJob = observe,
                observationStartTimeoutMillis = observationTimeoutMillis,
            ) as T
        }
    }

    @Test
    fun concurrentAdviceViewModelsObserveOnlyTheirOwnWorkManagerOutput() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val serviceSet = services()
        val plans = MealPlanRepository(db)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storeA = ViewModelStore()
        val storeB = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(NutritionAdviceViewModel::class.java))
                return NutritionAdviceViewModel(
                    adviceService = serviceSet.advice,
                    cheatService = serviceSet.cheat,
                    notificationScheduler = NotificationScheduler(context, plans, store, time),
                    aiJobScheduler = AiJobScheduler(context, NetworkType.NOT_REQUIRED),
                    activeProfileStore = store,
                    nowEpochMillis = time::nowEpochMillis,
                    refreshNotifications = {},
                ) as T
            }
        }
        adviceWorkHandler.blockExecutions.set(true)

        try {
            val viewModelA = ViewModelProvider(storeA, factory)[NutritionAdviceViewModel::class.java]
            val viewModelB = ViewModelProvider(storeB, factory)[NutritionAdviceViewModel::class.java]
            val question = "Uno spuntino?"
            viewModelA.ask(question)
            val runA = viewModelA.currentRequestRunIdentity!!
            val jobKeyA = viewModelA.currentJobKey!!
            scheduledAdviceJobs += profileId to jobKeyA
            viewModelB.ask(question)
            val runB = viewModelB.currentRequestRunIdentity!!
            val jobKeyB = viewModelB.currentJobKey!!
            scheduledAdviceJobs += profileId to jobKeyB

            assertNotEquals("Concurrent requests need distinct run identities", runA, runB)
            assertEquals(runA, jobKeyA)
            assertEquals(runB, jobKeyB)
            withTimeout(15_000) { adviceWorkHandler.startedFor(jobKeyA).await() }
            withTimeout(15_000) { adviceWorkHandler.startedFor(jobKeyB).await() }

            adviceWorkHandler.release(jobKeyA)
            val responseA = withTimeout(15_000) {
                viewModelA.state.first { it.answer != null }
            }
            assertEquals("answer:$jobKeyA", responseA.answer)
            assertTrue("Run B must remain pending when only run A completes", viewModelB.state.value.running)
            assertNull("Run A's output must not appear in ViewModel B", viewModelB.state.value.answer)

            adviceWorkHandler.release(jobKeyB)
            val responseB = withTimeout(15_000) {
                viewModelB.state.first { it.answer != null }
            }
            assertEquals("answer:$jobKeyB", responseB.answer)
            assertNotEquals("ViewModel B must never render run A's output", responseA.answer, responseB.answer)
        } finally {
            adviceWorkHandler.releaseAll()
            storeA.clear()
            storeB.clear()
        }
    }

    @Test
    fun safetyBlockedCheatApplicationKeepsTrackingAndAdviceAndUnderstandingRemainAvailable() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val originalProfile = profiles.get(profileId)!!
        profiles.update(originalProfile.copy(
            birthDateEpochDay = SixMonthHistoryFixture.TODAY.minusYears(17).toEpochDay(),
        ))
        val serviceSet = services()
        val plans = MealPlanRepository(db)
        val eventDate = SixMonthHistoryFixture.TODAY.minusDays(10)
        val weekStart = eventDate.minusDays((eventDate.dayOfWeek.value - 1).toLong())
        val versionBefore = plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.id
        val entriesBefore = db.cheatEntryDao().observeAll(profileId).first().size
        val input = CheatAdjustmentService.Input(
            description = "Pizza margherita",
            quantityText = "una porzione",
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(eventDate, 20),
        )

        val understanding = serviceSet.cheat.analyze(input)
        val advice = serviceSet.advice.ask("Quale pasto leggero posso scegliere?")
        assertTrue("Nutritional advice remains available for a non-plan-writing request", advice.accepted)
        assertEquals("Advice and cheat understanding must not persist a tracking event", entriesBefore, db.cheatEntryDao().observeAll(profileId).first().size)

        val result = serviceSet.cheat.registerAndAdapt(profileId, input, understanding)

        assertFalse("Under-18 profiles may track but must not adapt a plan", result.adapted)
        assertEquals(null, result.newVersionId)
        assertEquals(entriesBefore + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        val planId = plans.getPlanForWeek(profileId, weekStart.toEpochDay())!!.id
        val versionsAfterCommit = plans.versions(profileId, planId).first().size
        val replay = serviceSet.cheat.registerAndAdapt(profileId, input, understanding)
        assertEquals("Safety-blocked replay must reuse the confirmed event", result.cheatId, replay.cheatId)
        assertEquals(entriesBefore + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertEquals(versionsAfterCommit, plans.versions(profileId, planId).first().size)
        assertEquals(versionBefore, plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.id)
    }

    @Test
    fun incompleteOrInvalidHealthDeclarationTracksCheatWithoutWritingAPlan() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val original = db.userProfileDao().get(profileId)!!
        val plans = MealPlanRepository(db)
        val serviceSet = services()
        val eventDate = SixMonthHistoryFixture.TODAY.minusDays(10)
        val weekStart = eventDate.minusDays((eventDate.dayOfWeek.value - 1).toLong())
        val incomplete = HealthDeclarationCodec.encode(
            HealthDeclaration(null, false, confirmedAtEpochMillis = time.nowEpochMillis()),
        )
        val positive = HealthDeclarationCodec.encode(
            HealthDeclaration(true, false, confirmedAtEpochMillis = time.nowEpochMillis()),
        )
        val unsupported = """{"schemaVersion":2,"policyVersion":"future","pregnancyOrLactation":false,"clinicianManagedConditionOrMedication":false,"confirmedAtEpochMillis":7}"""
        val cases = listOf(
            "absent" to null,
            "incomplete" to incomplete,
            "invalid" to "not-json",
            "unsupported" to unsupported,
            "positive deficit" to positive,
        )
        cases.forEachIndexed { index, (name, declaration) ->
            db.userProfileDao().update(original.copy(goal = "Dimagrimento", healthDeclarationJson = declaration))
            val beforeVersion = plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.id
            val beforeEntries = db.cheatEntryDao().observeAll(profileId).first().size
            val input = CheatAdjustmentService.Input(
                description = "Sgarro $name",
                quantityText = "una porzione",
                notes = null,
                occurredAtEpochMillis = SixMonthHistoryFixture.epoch(eventDate, 20) + index,
            )

            val preview = serviceSet.cheat.analyze(input)
            val advice = serviceSet.advice.ask("Quale pasto posso scegliere?")
            assertTrue("Advice remains available for $name", advice.accepted)
            assertEquals("Analyze/advice must not persist for $name", beforeEntries, db.cheatEntryDao().observeAll(profileId).first().size)
            assertTrue(gateway.requests.none { request ->
                request.userPrompt.contains("pregnancyOrLactation") ||
                    request.userPrompt.contains("clinicianManagedConditionOrMedication") ||
                    (declaration != null && request.userPrompt.contains(declaration))
            })

            val result = serviceSet.cheat.registerAndAdapt(profileId, input, preview)
            assertFalse("$name must be tracking-only", result.adapted)
            assertEquals(null, result.newVersionId)
            assertEquals(beforeEntries + 1, db.cheatEntryDao().observeAll(profileId).first().size)
            assertEquals("$name must not append a plan version", beforeVersion, plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.id)
        }
    }

    @Test
    fun maintenanceGoalNeverAppliesCheatCompensationAndKeepsTdeeTargets() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val original = db.userProfileDao().get(profileId)!!
        val plans = MealPlanRepository(db)
        val serviceSet = services()
        val eventDate = SixMonthHistoryFixture.TODAY.minusDays(10)
        val weekStart = eventDate.minusDays((eventDate.dayOfWeek.value - 1).toLong())
        db.userProfileDao().update(original.copy(goal = "Mantenimento", healthDeclarationJson = null))
        val calculation = serviceSet.calculations.profileSnapshot(profileId, time.today())!!.calculation
        val tdee = calculation.tdeeKcal!!.toInt()
        val baseline = plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!
        val tdeeDays = baseline.version.days.map { it.toDraftForTest().copy(totalKcal = tdee, targetKcal = tdee) }
        plans.appendVersion(
            profileId = profileId,
            planId = baseline.planId,
            createdAtEpochMillis = time.nowEpochMillis(),
            draft = PlanVersionDraft(
                source = "SAFETY_FIXTURE",
                reason = "maintenance TDEE fixture",
                targetKcal = tdee,
                targetProteinG = baseline.version.targetProteinG,
                targetCarbsG = baseline.version.targetCarbsG,
                targetFatG = baseline.version.targetFatG,
                days = tdeeDays,
                catalogVersion = baseline.version.catalogVersion,
            ),
        )
        val positive = HealthDeclarationCodec.encode(
            HealthDeclaration(false, true, confirmedAtEpochMillis = time.nowEpochMillis()),
        )
        val declarations = listOf(null, HealthDeclarationCodec.encode(
            HealthDeclaration(false, false, confirmedAtEpochMillis = time.nowEpochMillis()),
        ), positive)
        declarations.forEachIndexed { index, declaration ->
            db.userProfileDao().update(original.copy(goal = "Mantenimento", healthDeclarationJson = declaration))
            val before = plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!
            val input = CheatAdjustmentService.Input(
                description = "Sgarro mantenimento $index",
                quantityText = "una porzione",
                notes = null,
                occurredAtEpochMillis = SixMonthHistoryFixture.epoch(eventDate, 21) + index,
            )
            val preview = serviceSet.cheat.analyze(input)
            val result = serviceSet.cheat.registerAndAdapt(profileId, input, preview)
            val after = plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!

            assertFalse("Mantenimento non deve compensare", result.adapted)
            assertEquals(null, result.newVersionId)
            assertEquals(before.version.id, after.version.id)
            assertTrue(after.version.days.all { day ->
                val target = day.targetKcal ?: day.totalKcal ?: 0
                target in (calculation.tdeeKcal!! * 0.97).toInt()..(calculation.tdeeKcal * 1.03).toInt()
            })
        }
    }

    @Test
    fun cheatCommitRechecksProfileAndBiaAfterInitialSafetyCalculation() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = context.assets.open("food/catalog_v1.json").use { FoodCatalog.load(it) }
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val originalProfile = profiles.get(profileId)!!
        val plans = MealPlanRepository(db)
        val eventDate = SixMonthHistoryFixture.TODAY.minusDays(10)
        val weekStart = eventDate.minusDays((eventDate.dayOfWeek.value - 1).toLong())
        val versionBefore = plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.id
        val input = CheatAdjustmentService.Input(
            description = "Pizza margherita",
            quantityText = "una porzione",
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(eventDate, 20),
        )
        val serviceSet = services()
        val preview = serviceSet.cheat.analyze(input)
        val positiveDeclaration = HealthDeclarationCodec.encode(
            HealthDeclaration(true, false, confirmedAtEpochMillis = time.nowEpochMillis()),
        )
        var changed = false
        val profileMutatingLoader = FoodCatalogLoader {
            if (!changed) {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE user_profile SET healthDeclarationJson = ? WHERE id = ?",
                    arrayOf<Any?>(positiveDeclaration, profileId),
                )
                changed = true
            }
            catalog
        }
        val profileMutationService = CheatAdjustmentService(
            gateway, plans, com.myfitai.app.data.repository.CheatEntryRepository(db), profiles, store,
            serviceSet.calculations, time, profileMutatingLoader,
        )
        val beforeProfileMutation = db.cheatEntryDao().observeAll(profileId).first().size
        val profileMutationResult = profileMutationService.registerAndAdapt(profileId, input, preview)
        assertTrue("The loader must mutate the profile after adaptation calculations", changed)
        assertFalse(profileMutationResult.adapted)
        assertEquals(null, profileMutationResult.newVersionId)
        assertEquals(beforeProfileMutation + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertEquals(versionBefore, plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.id)

        db.userProfileDao().update(originalProfile)
        val latestBia = db.biaMeasurementDao().getAll(profileId).maxBy { it.measuredAtEpochMillis }
        changed = false
        val biaInput = input.copy(occurredAtEpochMillis = input.occurredAtEpochMillis + 1L)
        val biaMutatingLoader = FoodCatalogLoader {
            if (!changed) {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE bia_measurements SET bodyFatPercent = ? WHERE id = ? AND profileId = ?",
                    arrayOf<Any?>((latestBia.bodyFatPercent ?: 20f) + 1f, latestBia.id, profileId),
                )
                changed = true
            }
            catalog
        }
        val biaMutationService = CheatAdjustmentService(
            gateway, plans, com.myfitai.app.data.repository.CheatEntryRepository(db), profiles, store,
            serviceSet.calculations, time, biaMutatingLoader,
        )
        val freshPreview = biaMutationService.analyze(biaInput)
        val beforeBiaMutation = db.cheatEntryDao().observeAll(profileId).first().size
        val biaMutationResult = biaMutationService.registerAndAdapt(profileId, biaInput, freshPreview)
        assertTrue("The loader must mutate BIA after adaptation calculations", changed)
        assertFalse(biaMutationResult.adapted)
        assertEquals(null, biaMutationResult.newVersionId)
        assertEquals(beforeBiaMutation + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertEquals(versionBefore, plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.id)

        var stalePlanChanged = false
        var concurrentVersionId: Long? = null
        val stalePlanInput = input.copy(occurredAtEpochMillis = input.occurredAtEpochMillis + 2L)
        val planMutatingLoader = FoodCatalogLoader {
            if (!stalePlanChanged) {
                runBlocking {
                    val latest = plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!
                    concurrentVersionId = plans.appendVersion(
                        profileId = profileId,
                        planId = latest.planId,
                        createdAtEpochMillis = time.nowEpochMillis(),
                        draft = PlanVersionDraft(
                            source = "CONCURRENT_FIXTURE",
                            reason = "concurrent plan version fixture",
                            targetKcal = latest.version.targetKcal,
                            targetProteinG = latest.version.targetProteinG,
                            targetCarbsG = latest.version.targetCarbsG,
                            targetFatG = latest.version.targetFatG,
                            days = latest.version.days.map { it.toDraftForTest() },
                            catalogVersion = latest.version.catalogVersion,
                        ),
                    )
                }
                stalePlanChanged = true
            }
            catalog
        }
        val stalePlanService = CheatAdjustmentService(
            gateway, plans, com.myfitai.app.data.repository.CheatEntryRepository(db), profiles, store,
            serviceSet.calculations, time, planMutatingLoader,
        )
        val stalePlanPreview = stalePlanService.analyze(stalePlanInput)
        val beforeStalePlanEntries = db.cheatEntryDao().observeAll(profileId).first().size
        val stalePlanResult = stalePlanService.registerAndAdapt(profileId, stalePlanInput, stalePlanPreview)
        assertTrue("The fixture must append a concurrent version before commit", stalePlanChanged)
        assertFalse("A stale source plan must be tracking-only", stalePlanResult.adapted)
        assertEquals(null, stalePlanResult.newVersionId)
        assertEquals(beforeStalePlanEntries + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertEquals(concurrentVersionId, plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.id)
    }

    @Test
    fun cheatApplicationUsesExplicitProfileIdAfterActiveProfileSwitch() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val profile = db.userProfileDao().get(profileId)!!
        val otherProfileId = db.userProfileDao().insert(profile.copy(id = 0L, name = "Other active profile"))
        val service = services().cheat
        val eventDate = SixMonthHistoryFixture.TODAY.minusDays(10)
        val weekStart = eventDate.minusDays((eventDate.dayOfWeek.value - 1).toLong())
        val input = CheatAdjustmentService.Input(
            description = "Pizza margherita",
            quantityText = "una porzione",
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(eventDate, 20),
        )
        val preview = service.analyze(input)
        store.selectProfile(otherProfileId)

        val result = service.registerAndAdapt(profileId, input, preview)

        assertTrue(result.adapted)
        assertEquals(1, db.cheatEntryDao().observeAll(profileId).first().count { it.description == "Pizza margherita" })
        assertEquals(0, db.cheatEntryDao().observeAll(otherProfileId).first().size)
        assertNotNull(MealPlanRepository(db).loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.id)
        assertEquals(null, MealPlanRepository(db).getPlanForWeek(otherProfileId, weekStart.toEpochDay()))
    }

    @Test
    fun failedCheatAppendRollsBackTrackingAndRetryCreatesOnlyOneEntry() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val eventDate = SixMonthHistoryFixture.TODAY.minusDays(10)
        val weekStart = eventDate.minusDays((eventDate.dayOfWeek.value - 1).toLong())
        val input = CheatAdjustmentService.Input(
            description = "Pizza margherita",
            quantityText = "una porzione",
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(eventDate, 20),
        )
        val plans = MealPlanRepository(db)
        val planId = plans.getPlanForWeek(profileId, weekStart.toEpochDay())!!.id
        val versionsBefore = plans.versions(profileId, planId).first().size
        val entriesBefore = db.cheatEntryDao().observeAll(profileId).first().size
        val service = services().cheat
        val preview = service.analyze(input)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_cheat_plan_day BEFORE INSERT ON meal_plan_days " +
                "BEGIN SELECT RAISE(ABORT, 'fixture cheat append failure'); END",
        )
        val appendError = try {
            runCatching { service.registerAndAdapt(profileId, input, preview) }.exceptionOrNull()
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_cheat_plan_day")
        }

        assertNotNull("The injected plan write error should surface", appendError)
        assertEquals("The cheat insert must roll back with the failed plan append", entriesBefore, db.cheatEntryDao().observeAll(profileId).first().size)
        assertEquals(versionsBefore, plans.versions(profileId, planId).first().size)

        val retry = service.registerAndAdapt(profileId, input, preview)
        val replay = service.registerAndAdapt(profileId, input, preview)
        assertTrue(retry.adapted)
        assertEquals(entriesBefore + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertEquals(versionsBefore + 1, plans.versions(profileId, planId).first().size)
        assertEquals("Successful WorkManager replay must return the original event", retry.cheatId, replay.cheatId)
        assertEquals("Successful WorkManager replay must reuse the original version", retry.newVersionId, replay.newVersionId)
        assertEquals(entriesBefore + 1, db.cheatEntryDao().observeAll(profileId).first().size)
        assertEquals(versionsBefore + 1, plans.versions(profileId, planId).first().size)
    }

    @Test
    fun cheatFoodPhoto_usesSameEstimateContractAndCalorieAdaptationFlow() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val beforeEntries = db.cheatEntryDao().observeAll(profileId).first()
        val beforeIds = beforeEntries.mapTo(hashSetOf()) { it.id }
        val before = beforeEntries.size
        val photo = AiImageInput(mimeType = "image/jpeg", base64Data = "Zm9v")
        val eventDate = SixMonthHistoryFixture.TODAY.minusDays(10)
        val weekStart = eventDate.minusDays((eventDate.dayOfWeek.value - 1).toLong())
        val plans = MealPlanRepository(db)
        val initialSnapshot = plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!
        val initialDays = initialSnapshot.version.days
        val targetsBefore = initialDays.sumOf { (it.targetKcal ?: it.totalKcal ?: 0).toLong() }
        val input = CheatAdjustmentService.Input(
            description = "",
            quantityText = null,
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(eventDate, 20),
            foodImage = photo,
            photoFingerprint = CheatAdjustmentService.photoFingerprint(photo),
        )

        val serviceSet = services()
        val service = serviceSet.cheat
        val calculation = serviceSet.calculations.profileSnapshot(profileId, time.today())!!.calculation
        val eligibleInitialDays = initialDays.filter { day ->
            day.dateEpochDay > eventDate.toEpochDay() ||
                (day.dateEpochDay == eventDate.toEpochDay() && day.meals.any { (it.timeMinutes ?: Int.MIN_VALUE) > 20 * 60 })
        }
        val maximumSafeWeeklyReduction = eligibleInitialDays.sumOf { day ->
            SafetyCalorieFloor.maximumSafeDailyReductionKcal(
                targetKcal = day.targetKcal ?: day.totalKcal ?: initialSnapshot.version.targetKcal ?: 0,
                tdeeKcal = calculation.tdeeKcal,
                minimumKcal = calculation.minimumKcal,
                maximumDailyDeficitKcal = SafetyThresholds.MAX_DAILY_DEFICIT_KCAL,
            ).toLong()
        }
        val understanding = service.analyze(input)
        val request = gateway.requests.last { it.schemaName == "myfitai_cheat_understanding_pipe_v1" }
        assertEquals(photo, request.image)
        assertTrue(input.photoFingerprint?.matches(Regex("[0-9a-f]{64}")) == true)
        assertTrue(request.userPrompt.contains("User description: not provided; identify the visible food from the attached photo"))
        assertTrue(request.userPrompt.contains("Quantity hint: "))
        assertTrue(request.userPrompt.contains("Food or nutrition-label photo attached: true"))
        assertTrue(request.systemPrompt.contains("Your entire scope is nutritional estimation"))
        assertTrue(request.systemPrompt.contains("A food photo alone is sufficient input"))
        assertTrue(request.systemPrompt.contains("automatically identify visible foods"))
        assertTrue(request.systemPrompt.contains("refuse the entire request"))
        assertTrue(request.systemPrompt.contains(NutritionAdviceService.OUT_OF_SCOPE_MESSAGE))
        assertEquals("Pizza margherita, una porzione", understanding.understoodFood)
        assertEquals(750, understanding.estimate.kcal)
        assertEquals(before, db.cheatEntryDao().observeAll(profileId).first().size)

        val result = service.registerAndAdapt(profileId, input.copy(foodImage = null), understanding)
        // observeAll is ordered by occurredAtEpochMillis DESC, so locate this insertion by its new row id.
        val saved = db.cheatEntryDao().observeAll(profileId).first().single { it.id !in beforeIds }
        val adaptedDays = plans.loadLatestSnapshot(profileId, weekStart.toEpochDay())!!.version.days
        val targetsAfter = adaptedDays.sumOf { (it.targetKcal ?: it.totalKcal ?: 0).toLong() }
        assertEquals(understanding.estimate.kcal, saved.estimatedKcal)
        assertEquals(understanding.understoodFood, saved.description)
        assertEquals(understanding.estimate.kcal, result.estimatedKcal)
        assertTrue("The confirmed photo estimate should use the existing weekly calorie adaptation", result.adapted)
        assertNotNull(result.newVersionId)
        assertTrue("The photo surplus exceeds the safe weekly reduction capacity", understanding.estimate.kcal.toLong() > maximumSafeWeeklyReduction)
        val effectiveReduction = targetsBefore - targetsAfter
        assertTrue("Only positive target reductions are expected", effectiveReduction >= 0L)
        assertTrue("Applied reduction cannot exceed the confirmed surplus", effectiveReduction <= understanding.estimate.kcal.toLong())
        assertTrue("Applied reduction cannot exceed the total daily safety capacity", effectiveReduction <= maximumSafeWeeklyReduction)
        assertTrue("An adapted plan must apply an effective reduction", !result.adapted || effectiveReduction > 0L)
        eligibleInitialDays.forEach { originalDay ->
            val adaptedDay = adaptedDays.single { it.dateEpochDay == originalDay.dateEpochDay }
            val target = (adaptedDay.targetKcal ?: adaptedDay.totalKcal ?: 0).toDouble()
            val originalTarget = (originalDay.targetKcal ?: originalDay.totalKcal ?: initialSnapshot.version.targetKcal ?: 0).toDouble()
            val allowedDailyReduction = SafetyCalorieFloor.maximumSafeDailyReductionKcal(
                targetKcal = originalTarget.toInt(),
                tdeeKcal = calculation.tdeeKcal,
                minimumKcal = calculation.minimumKcal,
                maximumDailyDeficitKcal = SafetyThresholds.MAX_DAILY_DEFICIT_KCAL,
            )
            val dailyReduction = originalTarget - target
            assertTrue("Daily reduction must be nonnegative", dailyReduction >= 0.0)
            assertTrue("Daily reduction must not exceed its own safe capacity", dailyReduction <= allowedDailyReduction + 1.0)
            assertTrue("Future target must stay above the calorie floor", target >= calculation.minimumKcal!!)
            assertTrue(
                "Future target must stay within the daily deficit cap",
                calculation.tdeeKcal!! - target <= SafetyThresholds.MAX_DAILY_DEFICIT_KCAL,
            )
        }
    }

    @Test
    fun cheatPhotoOnly_changedPhotoInvalidatesPreviouslyReviewedEstimate() = runBlocking {
        val photo = AiImageInput(mimeType = "image/jpeg", base64Data = "Zm9v")
        val input = CheatAdjustmentService.Input(
            description = "",
            quantityText = null,
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(SixMonthHistoryFixture.TODAY.minusDays(10), 20),
            foodImage = photo,
            photoFingerprint = CheatAdjustmentService.photoFingerprint(photo),
        )
        val service = services().cheat
        val preview = service.analyze(input)
        val changed = input.copy(foodImage = null, photoFingerprint = "0".repeat(64))

        val error = runCatching { service.registerAndAdapt(store.currentIdOrNull()!!, changed, preview) }.exceptionOrNull()

        assertTrue(error is CheatAdjustmentService.AdjustmentException.PreviewStale)
    }

    @Test
    fun cheatPhotoOnly_allowsAutomaticEstimationWithoutDescriptionOrQuantity() = runBlocking {
        val photo = AiImageInput(mimeType = "image/jpeg", base64Data = "Zm9v")
        val input = CheatAdjustmentService.Input(
            description = "",
            quantityText = null,
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(SixMonthHistoryFixture.TODAY.minusDays(10), 20),
            foodImage = photo,
            photoFingerprint = CheatAdjustmentService.photoFingerprint(photo),
        )

        val service = services().cheat
        val beforeRequests = gateway.requests.size
        val result = service.analyze(input)

        assertEquals("Pizza margherita, una porzione", result.understoodFood)
        assertEquals(750, result.estimate.kcal)
        assertEquals(1, gateway.requests.size - beforeRequests)
        assertTrue(gateway.requests.last().userPrompt.contains("Quantity hint: "))
        assertTrue(gateway.requests.last().systemPrompt.contains("A food photo alone is sufficient input"))
    }

    @Test
    fun cheatWithoutDescriptionOrPhoto_isRejectedBeforeAiRequest() = runBlocking {
        val service = services().cheat
        val beforeRequests = gateway.requests.size
        val input = CheatAdjustmentService.Input(
            description = " ",
            quantityText = null,
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(SixMonthHistoryFixture.TODAY.minusDays(10), 20),
        )

        val error = runCatching { service.analyze(input) }.exceptionOrNull()

        assertTrue(error is CheatAdjustmentService.AdjustmentException.NeedsInput)
        assertEquals(beforeRequests, gateway.requests.size)
    }

    @Test
    fun unrelatedPhoto_returnsStandardOutOfScopeMessageWithoutPersisting() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val before = db.cheatEntryDao().observeAll(profileId).first().size
        gateway.nextCheatUnderstandingResponse = JSONObject().put(
            "data",
            "CU1\nU|${NutritionAdviceService.OUT_OF_SCOPE_MESSAGE}\nE|0|0|0|0|low|OUT_OF_SCOPE_IMAGE",
        ).toString()
        val input = CheatAdjustmentService.Input(
            description = "Pizza margherita",
            quantityText = "una pizza",
            notes = null,
            occurredAtEpochMillis = SixMonthHistoryFixture.epoch(SixMonthHistoryFixture.TODAY.minusDays(10), 20),
            foodImage = AiImageInput(mimeType = "image/jpeg", base64Data = "Zm9v"),
        )

        val error = runCatching { services().cheat.analyze(input) }.exceptionOrNull()

        assertEquals(NutritionAdviceService.OUT_OF_SCOPE_MESSAGE, error?.message)
        assertEquals(before, db.cheatEntryDao().observeAll(profileId).first().size)
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
        assertTrue(gateway.requests.last { it.schemaName == "myfitai_weekly_review_pipe_v1" }.userPrompt.lineSequence().any { it.startsWith("U:") })
    }

    @Test
    fun weeklyReview_localMetrics_calculateConsumptionCoverageAndAdherence() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val week = LocalDate.of(2026, 8, 24)
        val snapshot = MealPlanRepository(db).loadLatestSnapshot(profileId, week.toEpochDay())!!
        val version = snapshot.version
        val meals = version.days.flatMap { it.meals }.take(3)
        val firstDay = version.days.first()

        meals.take(2).forEachIndexed { index, meal ->
            db.foodConsumptionDao().upsert(
                FoodConsumptionEntity(
                    profileId = profileId,
                    planId = snapshot.planId,
                    planVersionId = version.id,
                    dayId = firstDay.id,
                    plannedDateEpochDay = firstDay.dateEpochDay,
                    itemType = "MEAL",
                    itemKey = "MEAL:${meal.id}",
                    mealId = meal.id,
                    supplementKey = null,
                    status = if (index == 0) "CONSUMED" else "SKIPPED",
                    recordedAtEpochMillis = time.nowEpochMillis(),
                    updatedAtEpochMillis = time.nowEpochMillis(),
                    quantityFactor = 1f,
                    kcal = meal.kcal,
                    proteinG = meal.proteinG,
                    carbsG = meal.carbsG,
                    fatG = meal.fatG,
                ),
            )
        }

        val metrics = services().review.buildLocalMetrics(week)

        assertEquals(35, metrics.plannedMealCount)
        assertEquals(1, metrics.consumedMealCount)
        assertEquals(1, metrics.skippedMealCount)
        assertEquals(4, metrics.trackingCoveragePercent)
        assertEquals(2, metrics.adherencePercent)
        assertEquals(meals.first().kcal, metrics.consumedKcal)
    }

    private fun services(): Services {
        val profiles = com.myfitai.app.data.repository.UserProfileRepository(db)
        val bia = com.myfitai.app.data.repository.BiaRepository(db)
        val body = com.myfitai.app.data.repository.BodyMeasurementRepository(db)
        val plans = MealPlanRepository(db)
        val cheats = com.myfitai.app.data.repository.CheatEntryRepository(db)
        val reviews = com.myfitai.app.data.repository.WeeklyReviewRepository(db)
        val consumptions = com.myfitai.app.data.repository.FoodConsumptionRepository(db)
        val calculations = ProfileCalculationService(profiles, bia, body, store)
        val personal = PersonalResponseService(store, plans, cheats, bia, body, com.myfitai.app.data.repository.BodyExpectationGoalRepository(db))
        return Services(
            calculations = calculations,
            generation = NutritionPlanGenerationService(gateway, calculations, profiles, plans, cheats, com.myfitai.app.data.repository.CalorieRecoveryRepository(db), store, personal, time = time),
            mealAlternative = MealAlternativeService(gateway, profiles, plans, store, time, calculations = calculations),
            advice = NutritionAdviceService(gateway, profiles, plans, cheats, consumptions, store, time),
            cheat = CheatAdjustmentService(gateway, plans, cheats, profiles, store, calculations, time),
            review = WeeklyReviewService(gateway, reviews, plans, cheats, bia, body, personal, store, time, consumptions, profiles),
        )
    }

    private suspend fun insertFixture() {
        val profileId = db.userProfileDao().insert(
            fixture.profile.copy(
                healthDeclarationJson = HealthDeclarationCodec.encode(
                    HealthDeclaration(
                        pregnancyOrLactation = false,
                        clinicianManagedConditionOrMedication = false,
                        confirmedAtEpochMillis = time.nowEpochMillis(),
                    ),
                ),
            ),
        )
        store.selectProfile(profileId)
        fixture.bia.forEach { db.biaMeasurementDao().insert(it.copy(profileId = profileId)) }
        fixture.body.forEach { db.bodyMeasurementDao().insert(it.copy(profileId = profileId)) }
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

    private fun historyDay(date: LocalDate, vararg meals: JSONObject) = DayDraft(
        dateEpochDay = date.toEpochDay(),
        totalKcal = meals.sumOf { it.getInt("kcal") },
        proteinG = meals.sumOf { it.getDouble("proteinG") }.toFloat(),
        carbsG = meals.sumOf { it.getDouble("carbsG") }.toFloat(),
        fatG = meals.sumOf { it.getDouble("fatG") }.toFloat(),
        meals = meals.mapIndexed { index, value ->
            MealDraft(
                type = value.getString("type"),
                title = value.getString("title"),
                timeMinutes = value.getInt("timeMinutes"),
                kcal = value.getInt("kcal"),
                proteinG = value.getDouble("proteinG").toFloat(),
                carbsG = value.getDouble("carbsG").toFloat(),
                fatG = value.getDouble("fatG").toFloat(),
                preparation = value.getString("preparation"),
                ingredients = value.getJSONArray("ingredients").let { array ->
                    buildList {
                        for (i in 0 until array.length()) {
                            val ingredient = array.getJSONObject(i)
                            add(IngredientDraft(ingredient.getString("name"), ingredient.getDouble("quantity").toFloat(), ingredient.getString("unit"), ingredient.getString("displayDose"), ingredient.getString("weightState"), ingredient.getString("nutritionConfidence"), ingredient.getString("category")))
                        }
                    }
                },
            )
        },
    )

    private fun com.myfitai.app.domain.food.FoodPlanDay.toDraftForTest() = DayDraft(
        dateEpochDay = dateEpochDay,
        totalKcal = totalKcal,
        proteinG = proteinG,
        carbsG = carbsG,
        fatG = fatG,
        meals = meals.map { meal ->
            MealDraft(meal.type, meal.title, meal.timeMinutes, meal.kcal, meal.proteinG, meal.carbsG, meal.fatG, meal.preparation, meal.ingredients.map { ingredient ->
                IngredientDraft(ingredient.name, ingredient.quantity, ingredient.unit, ingredient.displayDose, ingredient.weightState, ingredient.nutritionConfidence, ingredient.category)
            })
        },
        supplements = supplements.map { supplement ->
            com.myfitai.app.data.repository.SupplementDraft(supplement.kind, supplement.name, supplement.dose, supplement.unit, supplement.timeMinutes, supplement.kcal, supplement.proteinG, supplement.carbsG, supplement.fatG, supplement.notes)
        },
        hydrationNote = hydrationNote,
        targetKcal = targetKcal,
        targetProteinG = targetProteinG,
        targetCarbsG = targetCarbsG,
        targetFatG = targetFatG,
        baseTargetKcal = baseTargetKcal,
    )

    private fun draft(days: List<DayDraft>) = PlanVersionDraft(
        source = "fixture",
        reason = "fixture",
        targetKcal = days.sumOf { it.totalKcal ?: 0 },
        targetProteinG = days.sumOf { (it.proteinG ?: 0f).toDouble() }.toFloat(),
        targetCarbsG = days.sumOf { (it.carbsG ?: 0f).toDouble() }.toFloat(),
        targetFatG = days.sumOf { (it.fatG ?: 0f).toDouble() }.toFloat(),
        days = days,
    )

    private fun meal(
        title: String,
        type: String,
        kcal: Int,
        time: Int,
        ingredientNames: List<String> = listOf("Pollo", "Riso"),
    ) = JSONObject().apply {
        put("title", title)
        put("type", type)
        put("kcal", kcal)
        put("timeMinutes", time)
        put("proteinG", 30.0)
        put("carbsG", 50.0)
        put("fatG", 12.0)
        put("preparation", "Preparazione storica")
        put("ingredients", JSONArray().apply {
            ingredientNames.forEach { name ->
                put(JSONObject().apply {
                    put("name", name)
                    put("quantity", 100.0)
                    put("unit", "g")
                    put("displayDose", "100 g")
                    put("weightState", "RAW")
                    put("nutritionConfidence", "HIGH")
                    put("category", "fixture")
                })
            }
        })
    }

    private data class Services(
        val calculations: ProfileCalculationService,
        val generation: NutritionPlanGenerationService,
        val mealAlternative: MealAlternativeService,
        val advice: NutritionAdviceService,
        val cheat: CheatAdjustmentService,
        val review: WeeklyReviewService,
    )

    private fun completedWorkOperation(): Operation {
        val future = ResolvableFuture.create<Operation.State.SUCCESS>()
        future.set(Operation.SUCCESS)
        return FakeWorkOperation(future)
    }

    companion object {
        private const val TEST_SESSION_PREFS = "ai_workflow_integration_test_session"
        private const val PRODUCTION_SESSION_PREFS = "myfitai_profile_session"
    }
}

private class FixedTimeProvider(initialNow: Long) : TimeProvider {
    private var now = initialNow
    private var nextReadValue: Long? = null
    override val zoneId = ZoneOffset.UTC

    override fun nowEpochMillis(): Long {
        val current = now
        nextReadValue?.let {
            now = it
            nextReadValue = null
        }
        return current
    }

    fun setNow(value: Long) {
        now = value
        nextReadValue = null
    }

    /** Returns the current instant once, then advances so the next service boundary observes the new time. */
    fun advanceAfterNextRead(value: Long) {
        nextReadValue = value
    }
}

private class FakeAiRuntimeGateway : AiRuntimeGateway {
    val requests = mutableListOf<AiStructuredRequest>()
    var nextCheatUnderstandingResponse: String? = null
    var beforeWeeklyPlanResponse: (suspend () -> Unit)? = null
    var weeklyPlanKcalOffset: Int = 0
    var simulateWeeklyPlanRetry: Boolean = false
    var weeklyValidationAttempts: Int = 0

    override suspend fun execute(
        request: AiStructuredRequest,
        maxSchemaRetries: Int,
        businessValidator: (String) -> Result<Unit>,
    ): AiExecutionService.ValidatedResponse {
        requests += request
        val json = when (request.schemaName) {
            "myfitai_weekly_nutrition_pipe_v1" -> weeklyPlan(request.userPrompt)
            "myfitai_meal_alternatives_pipe_v1" -> alternatives(request.userPrompt)
            "myfitai_nutrition_advice_pipe_v1" -> advice()
            "myfitai_cheat_understanding_pipe_v1" -> nextCheatUnderstandingResponse.also { nextCheatUnderstandingResponse = null } ?: understanding()
            "myfitai_cheat_adjustment_pipe_v1" -> noAdaptation(request.userPrompt)
            "myfitai_weekly_review_pipe_v1" -> review(request.userPrompt)
            else -> error("Unsupported test schema ${request.schemaName}")
        }
        if (request.schemaName == "myfitai_weekly_nutrition_pipe_v1" && simulateWeeklyPlanRetry) {
            weeklyValidationAttempts = 0
            val first = businessValidator(json)
            weeklyValidationAttempts++
            if (first.isFailure) {
                val retry = businessValidator(weeklyPlan(request.userPrompt))
                weeklyValidationAttempts++
                retry.getOrThrow()
            } else {
                first.getOrThrow()
            }
        } else {
            businessValidator(json).getOrThrow()
        }
        if (request.schemaName == "myfitai_weekly_nutrition_pipe_v1") {
            beforeWeeklyPlanResponse?.invoke()
            beforeWeeklyPlanResponse = null
        }
        return AiExecutionService.ValidatedResponse(AiProviderType.OPENAI, "fixture", json)
    }

    private fun weeklyPlan(prompt: String): String {
        val week = prompt.lineSequence().first { it.startsWith("W:") }.substringAfter(':').toLong()
        val baseTargets = prompt.lineSequence().first { it.startsWith("T:") }.substringAfter(':').split('|')
        val dailyTargets = prompt.lineSequence().filter { it.startsWith("TD:") }.associate { line ->
            val fields = line.substringAfter(':').split('|')
            fields[0].toLong() to fields.drop(1)
        }
        val mealsPerDay = prompt.lineSequence().first { it.startsWith("MEALS_PER_DAY:") }.substringAfter(':').toInt()
        val kcal = baseTargets[0].toInt()
        val protein = baseTargets[1].toDouble()
        val carbs = baseTargets[2].toDouble()
        val fat = baseTargets[3].toDouble()
        val lines = mutableListOf("MFP1", "W|$week")
        repeat(7) { offset ->
            val target = dailyTargets[week + offset] ?: listOf(kcal.toString(), protein.toString(), carbs.toString(), fat.toString())
            val dayKcal = target[0].toInt() + weeklyPlanKcalOffset
            val dayProtein = target[1].toDouble()
            val dayCarbs = target[2].toDouble()
            val dayFat = target[3].toDouble()
            lines += "D|${week + offset}|$dayKcal|$dayProtein|$dayCarbs|$dayFat"
            val mealKcal = (0 until mealsPerDay).map { index -> if (index == mealsPerDay - 1) dayKcal - (dayKcal / mealsPerDay) * (mealsPerDay - 1) else dayKcal / mealsPerDay }
            mealKcal.forEachIndexed { index, value ->
                lines += "M|${if (index == 0) "Colazione" else "Pasto"}|Pasto fixture ${offset}_$index|${420 + index * 120}|$value|${dayProtein / mealsPerDay}|${dayCarbs / mealsPerDay}|${dayFat / mealsPerDay}|Preparazione fixture"
                lines += "I|Ingrediente principale ${offset}_$index|100|g|100 g|RAW|HIGH|fixture"
            }
            lines += "H|Bevi regolarmente in modo prudente."
        }
        lines += "V|1|fixture"
        return JSONObject().put("data", lines.joinToString("\n")).toString()
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
        val lines = buildList {
            add("NA1")
            add("S|1")
            add("A|Ecco cinque opzioni di gelato gestibili nel piano.")
            repeat(5) { index -> add("O|Gelato fixture ${index + 1}|Porzione coerente con il piano|${220 + index * 20}|6|30|8|gelato") }
            add("Q|Porzione standard")
            add("V|1|fixture")
        }
        return JSONObject().put("data", lines.joinToString("\n")).toString()
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
        val epochDay = prompt.lineSequence().first { it.startsWith("W:") }.substringAfter(':').toLong()
        return JSONObject().put("data", listOf(
            "WR1",
            "W|$epochDay",
            "S|Settimana descritta dai dati registrati.",
            "O|Il piano è presente.",
            "O|I dati corporei sono osservazioni.",
            "G|Mantieni target e timing locali.",
            "V|1|fixture",
        ).joinToString("\n")).toString()
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

private val ADVICE_TEST_SUGGESTION = NutritionAdviceContract.Suggestion(
    title = "Yogurt bianco con frutta",
    reason = "Spuntino semplice",
    estimatedKcal = 220,
    proteinG = 12f,
    carbsG = 30f,
    fatG = 5f,
    foods = listOf("yogurt bianco", "frutta"),
)

/** Fake WorkManager handler: exercises enqueue/Worker/output observation without invoking a provider. */
private class FakeNutritionAdviceWorkHandler : AiJobHandler {
    val blockExecutions = AtomicBoolean(false)
    val failNextExecution = AtomicBoolean(false)
    private val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val releases = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    fun startedFor(jobKey: String): CompletableDeferred<Unit> =
        started.computeIfAbsent(jobKey) { CompletableDeferred() }

    fun release(jobKey: String) {
        releases.computeIfAbsent(jobKey) { CompletableDeferred() }.complete(Unit)
    }

    fun releaseAll() {
        releases.values.forEach { it.complete(Unit) }
    }

    override suspend fun execute(profileId: Long, jobKey: String, params: Data): AiJobOutcome {
        startedFor(jobKey).complete(Unit)
        if (failNextExecution.compareAndSet(true, false)) {
            return AiJobOutcome.Failure("EXPECTED_FIVE_SUGGESTIONS")
        }
        if (blockExecutions.get()) {
            releases.computeIfAbsent(jobKey) { CompletableDeferred() }.await()
        }
        val question = params.getString(com.myfitai.app.domain.food.NutritionAdviceAiJobHandler.KEY_QUESTION).orEmpty()
        val suggestion = ADVICE_TEST_SUGGESTION
        val payload = JSONObject()
            .put("question", question)
            .put("answer", "answer:$jobKey")
            .put("assumptions", "")
            .put("providerLabel", "Fixture")
            .put("suggestions", JSONArray().put(
                JSONObject()
                    .put("title", suggestion.title)
                    .put("reason", suggestion.reason)
                    .put("estimatedKcal", suggestion.estimatedKcal)
                    .put("proteinG", suggestion.proteinG)
                    .put("carbsG", suggestion.carbsG)
                    .put("fatG", suggestion.fatG)
                    .put("foods", JSONArray(suggestion.foods)),
            ))
            .toString()
        return AiJobOutcome.Success(
            Data.Builder()
                .putString(com.myfitai.app.domain.food.NutritionAdviceAiJobHandler.KEY_PAYLOAD, payload)
                // A fixture result should not post a user-facing notification.
                .putBoolean(AiJobWorker.KEY_SKIPPED, true)
                .build(),
        )
    }
}

private class FakeWorkOperation(
    private val future: ResolvableFuture<Operation.State.SUCCESS>,
) : Operation {
    override fun getState(): LiveData<Operation.State> = MutableLiveData(Operation.SUCCESS)

    override fun getResult(): com.google.common.util.concurrent.ListenableFuture<Operation.State.SUCCESS> = future
}
