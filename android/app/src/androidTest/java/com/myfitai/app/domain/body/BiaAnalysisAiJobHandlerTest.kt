package com.myfitai.app.domain.body

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.AiAutomationPreferences
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaAnalysisResultRepository
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.ai.AiJobOutcome
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.calculation.ProfileCalculationService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real job handler on an isolated in-memory database with a deterministic provider,
 * so it never touches the user's data and never spends AI quota.
 */
@RunWith(AndroidJUnit4::class)
class BiaAnalysisAiJobHandlerTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var gateway: RecordingGateway
    private lateinit var handler: BiaAnalysisAiJobHandler
    private lateinit var results: BiaAnalysisResultRepository
    private lateinit var biaRepository: BiaRepository
    private var profileId = 0L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MyFitAiDatabase::class.java).allowMainThreadQueries().build()
        val profiles = UserProfileRepository(db)
        biaRepository = BiaRepository(db)
        val body = BodyMeasurementRepository(db)
        results = BiaAnalysisResultRepository(db)
        val activeProfile = ActiveProfileStore(context)
        gateway = RecordingGateway()
        val now = System.currentTimeMillis()
        profileId = profiles.create(
            UserProfileEntity(
                name = "Test BIA", birthDateEpochDay = null, heightCm = 186f, currentWeightKg = 89f, goal = "Ricomposizione",
                activityLevel = null, wakeTimeMinutes = null, sleepTimeMinutes = null, dietaryPreferencesJson = null,
                createdAtEpochMillis = now, updatedAtEpochMillis = now,
            ),
        )
        handler = BiaAnalysisAiJobHandler(
            service = BiaAnalysisService(gateway),
            profiles = profiles,
            bia = biaRepository,
            body = body,
            workouts = WorkoutRepository(db),
            plans = MealPlanRepository(db),
            calculations = ProfileCalculationService(profiles, biaRepository, body, activeProfile),
            scheduler = BiaProgressCoachScheduler(AiAutomationPreferences(context), activeProfile, AiJobScheduler(context)),
            results = results,
            clock = { 1_790_000_000_000L },
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun longHistory_savesResultInsideTheJobAndReturnsOnlyIdentifiers() = runBlocking {
        // 40 fully populated readings used to overflow the 10 KB WorkManager limit and crash the screen.
        val ids = (1..40).map { biaRepository.insert(fullReading(it)) }

        val outcome = handler.execute(profileId, "job", BiaAnalysisAiJobHandler.params(ids.last()))

        val success = outcome as AiJobOutcome.Success
        val resultId = success.output.getLong(BiaAnalysisAiJobHandler.KEY_RESULT_ID, -1L)
        assertTrue(resultId > 0L)
        assertEquals(ids.last(), success.output.getLong(BiaAnalysisAiJobHandler.KEY_MEASUREMENT_ID, -1L))
        assertEquals(3, success.output.keyValueMap.size)

        val saved = results.latestForMeasurement(profileId, ids.last())
        assertNotNull(saved)
        assertEquals(1_790_000_000_000L, saved!!.createdAtEpochMillis)
        val payload = JSONObject(saved.payloadJson)
        assertEquals("Andamento favorevole", payload.getString("verdict"))
        assertEquals(3, payload.getJSONArray("actions").length())

        // The model receives a bounded, chronological history ending at the analysed reading.
        val historyLine = gateway.prompts.single().lines().first { it.startsWith("STORICO_BIA:") }
        assertEquals(BiaAnalysisReport.MAX_HISTORY_ROWS, historyLine.split("date=").size - 1)
    }

    @Test
    fun analysingAnOlderReading_ignoresLaterOnes() = runBlocking {
        val ids = (1..5).map { biaRepository.insert(fullReading(it)) }

        val outcome = handler.execute(profileId, "job", BiaAnalysisAiJobHandler.params(ids[2]))

        assertTrue(outcome is AiJobOutcome.Success)
        val historyLine = gateway.prompts.single().lines().first { it.startsWith("STORICO_BIA:") }
        assertEquals(3, historyLine.split("date=").size - 1)
        assertNotNull(results.latestForMeasurement(profileId, ids[2]))
        assertNull(results.latestForMeasurement(profileId, ids[4]))
    }

    @Test
    fun providerFailure_isBoundedAndStoresNothing() = runBlocking {
        val id = biaRepository.insert(fullReading(1))
        gateway.failure = IllegalStateException("x".repeat(5_000))

        val outcome = handler.execute(profileId, "job", BiaAnalysisAiJobHandler.params(id))

        val failure = outcome as AiJobOutcome.Failure
        assertTrue(failure.message.startsWith("BIA_PROGRESS_COACH_FAILED:"))
        // WorkManager Data is limited to 10 KB; a provider error must never blow it up.
        assertTrue(failure.message.length < 400)
        assertNull(results.latestForMeasurement(profileId, id))
    }

    @Test
    fun emptyHistory_failsCleanlyInsteadOfCrashing() = runBlocking {
        val outcome = handler.execute(profileId, "job", BiaAnalysisAiJobHandler.params(999L))

        assertTrue(outcome is AiJobOutcome.Failure)
        assertTrue(gateway.prompts.isEmpty())
    }

    private fun fullReading(index: Int) = BiaMeasurementEntity(
        profileId = profileId,
        measuredAtEpochMillis = 1_700_000_000_000L + index * 86_400_000L,
        weightKg = 90f - index * 0.1f, bodyFatPercent = 20f, visceralFatLevel = 6f, muscleMassKg = 66f,
        skeletalMuscleKg = 41f, bodyWaterPercent = 58f, bmrKcal = 1900f, fasting = true, justWokeUp = true,
        afterBathroom = true, noRecentWorkout = true, notes = null, fatMassKg = 18f, leanMassKg = 72f,
        bodyWaterKg = 52f, subcutaneousFatPercent = 14f, boneMassKg = 4.8f, proteinPercent = 16f,
        proteinKg = 14f, bodyAgeYears = 41, bmi = 26f,
    )

    private class RecordingGateway : AiRuntimeGateway {
        val prompts = mutableListOf<String>()
        var failure: Exception? = null

        override suspend fun execute(
            request: AiStructuredRequest,
            maxSchemaRetries: Int,
            businessValidator: (String) -> Result<Unit>,
        ): AiExecutionService.ValidatedResponse {
            failure?.let { throw it }
            prompts += request.userPrompt
            val json = JSONObject().put("data", RESPONSE).toString()
            businessValidator(json).getOrThrow()
            return AiExecutionService.ValidatedResponse(AiProviderType.GEMINI, "fake-model", json)
        }
    }

    private companion object {
        const val RESPONSE = "B|PROBABLY_POSITIVE S|Andamento favorevole C|Grasso in calo F|? O|? K|? P|Ripeti la misura " +
            "L|MEDIUM|Storico coerente X|Una X|Due X|Tre D|Analisi informativa, non diagnosi medica N|? V|1|ok"
    }
}
