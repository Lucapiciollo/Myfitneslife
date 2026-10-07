package com.myfitai.app.domain.food

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.ai.AiExecutionService
import com.myfitai.app.ai.AiProviderType
import com.myfitai.app.ai.AiRuntimeGateway
import com.myfitai.app.ai.AiStructuredRequest
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.DailyActivityCheckInEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.TrainingProgramPreferences
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyExpectationGoalRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CalorieRecoveryRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.DailyActivityCheckInRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import com.myfitai.app.domain.calculation.DailyActivityCheckInEngine.Intensity
import com.myfitai.app.domain.calculation.DayEnergyProvider
import com.myfitai.app.domain.calculation.ProfileCalculationService
import com.myfitai.app.domain.calculation.TrainingEnergyPlanner.ActivityBasis
import com.myfitai.app.domain.calculation.TrainingProgram
import com.myfitai.app.domain.personalization.PersonalResponseService
import com.myfitai.app.domain.time.TimeProvider
import com.myfitai.app.fixtures.SixMonthHistoryFixture
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Plan generation with a weekly training program. Runs on an in-memory database with a profile id that
 * never exists on the device and a scripted gateway: it never touches the real profiles, the real
 * active-profile pointer or any real plan.
 */
@RunWith(AndroidJUnit4::class)
class TrainingProgramGenerationDeviceTest {
    private lateinit var context: Context
    private lateinit var db: MyFitAiDatabase
    private lateinit var preferences: TrainingProgramPreferences
    private lateinit var gateway: ScriptedGateway
    private lateinit var time: FixedTime
    private lateinit var service: NutritionPlanGenerationService
    private lateinit var dayEnergy: DayEnergyProvider
    private val profileId = TEST_PROFILE_ID
    private val monday = SixMonthHistoryFixture.TODAY

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MyFitAiDatabase::class.java).allowMainThreadQueries().build()
        preferences = TrainingProgramPreferences(context)
        preferences.clearProfile(profileId)
        gateway = ScriptedGateway()
        time = FixedTime(SixMonthHistoryFixture.epoch(monday, 8))

        val fixture = SixMonthHistoryFixture.build()
        db.userProfileDao().insert(fixture.profile.copy(id = profileId))
        fixture.bia.forEach { db.biaMeasurementDao().insert(it.copy(profileId = profileId)) }
        fixture.body.forEach { db.bodyMeasurementDao().insert(it.copy(profileId = profileId)) }

        val profiles = UserProfileRepository(db)
        val bia = BiaRepository(db)
        val body = BodyMeasurementRepository(db)
        val workouts = WorkoutRepository(db)
        val plans = MealPlanRepository(db)
        val cheats = CheatEntryRepository(db)
        // Read-only: the real active-profile pointer is never changed by this test.
        val store = ActiveProfileStore(context)
        val calculations = ProfileCalculationService(profiles, bia, body, store)
        val personal = PersonalResponseService(store, plans, cheats, workouts, bia, body, BodyExpectationGoalRepository(db))
        dayEnergy = DayEnergyProvider(
            calculations = calculations,
            profiles = profiles,
            workouts = workouts,
            checkIns = DailyActivityCheckInRepository(db),
            program = preferences,
            zone = { ZoneOffset.UTC },
        )
        service = NutritionPlanGenerationService(
            aiRuntime = gateway,
            calculations = calculations,
            profiles = profiles,
            workouts = workouts,
            plans = plans,
            cheats = cheats,
            recovery = CalorieRecoveryRepository(db),
            activeProfileStore = store,
            personalResponse = personal,
            trainingProgramPreferences = preferences,
            dailyActivityCheckIns = DailyActivityCheckInRepository(db),
            time = time,
        )
    }

    @After
    fun tearDown() {
        preferences.clearProfile(profileId, synchronous = true)
        db.close()
    }

    @Test
    fun trainingDaysGetTheirOwnTargetsCeilingsAndMarkers() = runBlocking {
        preferences.set(
            profileId,
            TrainingProgram(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), 60, Intensity.MODERATE, 18 * 60),
            synchronous = true,
        )
        preferences.setActivityBasis(profileId, ActivityBasis.LEGACY_INCLUDES_TRAINING, synchronous = true)

        service.generatePeriod(profileId, monday, 1)

        val prompt = gateway.requests.single().userPrompt
        val trainingDates = prompt.lines().filter { it.startsWith("TR:") }.map { it.substringAfter(':').substringBefore('|').toLong() }
        assertEquals(listOf(monday.plusDays(2).toEpochDay(), monday.plusDays(4).toEpochDay()), trainingDates)
        assertTrue("TR carries start|minutes|intensity", prompt.lines().any { it.endsWith("|1080|60|MODERATE") })
        assertEquals("one ceiling per day for a deficit goal", 7, prompt.lines().count { it.startsWith("ED:") })
        assertTrue("E is not used in program mode", prompt.lines().any { it == "E:?" })

        val snapshot = MealPlanRepository(db).loadLatestSnapshot(profileId, monday.toEpochDay())!!
        val byDay = snapshot.version.days.associateBy { it.dateEpochDay }
        val rest = byDay.getValue(monday.toEpochDay())
        val training = byDay.getValue(monday.plusDays(2).toEpochDay())
        assertTrue(
            "training ${training.targetKcal} must exceed rest ${rest.targetKcal}",
            training.targetKcal!! > rest.targetKcal!!,
        )
        assertTrue("rest days are identical", byDay.getValue(monday.plusDays(1).toEpochDay()).targetKcal == rest.targetKcal)
        assertTrue("every day keeps its own base target", snapshot.version.days.all { it.baseTargetKcal != null })
        assertEquals(training.targetKcal, training.baseTargetKcal)
        assertTrue("reason=${snapshot.version.reason}", snapshot.version.reason.orEmpty().endsWith(":TRAINING=2"))
        val ceilings = prompt.lines().filter { it.startsWith("ED:") }
            .associate { it.substringAfter(':').substringBefore('|').toLong() to it.substringAfterLast('|').toInt() }
        snapshot.version.days.forEach { day ->
            assertTrue("day ${day.dateEpochDay} stays under its ceiling", day.totalKcal!! < ceilings.getValue(day.dateEpochDay))
        }
    }

    @Test
    fun homeAndFoodReadTheSameDayEnergyAsThePlan() = runBlocking {
        preferences.set(
            profileId,
            TrainingProgram(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), 60, Intensity.MODERATE),
            synchronous = true,
        )
        service.generatePeriod(profileId, monday, 1)
        val plan = MealPlanRepository(db).loadLatestSnapshot(profileId, monday.toEpochDay())!!.version.days.associateBy { it.dateEpochDay }

        val week = (0L..6L).map { monday.plusDays(it) }
        val shown = dayEnergy.days(profileId, week)!!.associateBy { it.date.toEpochDay() }

        val training = monday.plusDays(2).toEpochDay()
        val rest = monday.toEpochDay()
        assertTrue(shown.getValue(training).resolution.isTraining)
        assertFalse(shown.getValue(rest).resolution.isTraining)
        assertTrue(shown.getValue(training).energy.tdeeKcal > shown.getValue(rest).energy.tdeeKcal)
        // The plan may apply the adaptive correction, but it must be the same ratio on every day.
        val trainingRatio = plan.getValue(training).targetKcal!! / shown.getValue(training).baseTargets.kcal
        val restRatio = plan.getValue(rest).targetKcal!! / shown.getValue(rest).baseTargets.kcal
        assertEquals("plan=$trainingRatio/$restRatio", restRatio, trainingRatio, 0.005)
        // Protein follows body weight, not calories.
        assertEquals(shown.getValue(training).baseTargets.proteinG, shown.getValue(rest).baseTargets.proteinG, 0.001)
    }

    @Test
    fun theProviderIsSilentWithoutAProgramAndFollowsTodaysCheckIn() = runBlocking {
        assertEquals(null, dayEnergy.day(profileId, monday))

        preferences.set(profileId, TrainingProgram(setOf(DayOfWeek.MONDAY), 60, Intensity.MODERATE), synchronous = true)
        assertTrue(dayEnergy.day(profileId, monday)!!.resolution.isTraining)

        DailyActivityCheckInRepository(db).upsert(
            DailyActivityCheckInEntity(
                profileId = profileId, dateEpochDay = monday.toEpochDay(), status = "REST", durationMinutes = null,
                intensity = null, adjustmentKcal = 0, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
            ),
        )
        assertFalse("a rest check-in wins over the program", dayEnergy.day(profileId, monday)!!.resolution.isTraining)
    }

    @Test
    fun theDisplayedWeekFollowsARegeneratedVersionWithoutRestartingTheScreen() = runBlocking {
        val repository = MealPlanRepository(db)
        service.generatePeriod(profileId, monday, 1)
        val seen = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val collector = launch(kotlinx.coroutines.Dispatchers.Default) {
            repository.latestSnapshotForDisplayedWeek(profileId, monday.toEpochDay()).collect { seen += it?.version?.versionNumber ?: -1 }
        }
        withTimeout(5_000) { while (1 !in seen) delay(25) }

        service.generatePeriod(profileId, monday, 1)

        withTimeout(5_000) { while (2 !in seen) delay(25) }
        collector.cancel()
        assertTrue("seen=$seen", seen.last() == 2)
    }

    @Test
    fun withoutAProgramTheLegacySingleTargetIsKept() = runBlocking {
        service.generatePeriod(profileId, monday, 1)

        val prompt = gateway.requests.single().userPrompt
        assertFalse(prompt.lines().any { it.startsWith("TR:") })
        assertFalse(prompt.lines().any { it.startsWith("ED:") })
        val snapshot = MealPlanRepository(db).loadLatestSnapshot(profileId, monday.toEpochDay())!!
        assertEquals("one target for the week", 1, snapshot.version.days.map { it.targetKcal }.distinct().size)
        assertFalse("reason=${snapshot.version.reason}", snapshot.version.reason.orEmpty().contains("TRAINING="))
    }

    @Test
    fun aRestCheckInTurnsAProgrammedDayIntoARestDay() = runBlocking {
        preferences.set(
            profileId,
            TrainingProgram(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), 60, Intensity.MODERATE),
            synchronous = true,
        )
        val wednesday = monday.plusDays(2)
        DailyActivityCheckInRepository(db).upsert(
            DailyActivityCheckInEntity(
                profileId = profileId,
                dateEpochDay = wednesday.toEpochDay(),
                status = "REST",
                durationMinutes = null,
                intensity = null,
                adjustmentKcal = 0,
                createdAtEpochMillis = time.nowEpochMillis(),
                updatedAtEpochMillis = time.nowEpochMillis(),
            ),
        )

        service.generatePeriod(profileId, monday, 1)

        val trainingDates = gateway.requests.single().userPrompt.lines()
            .filter { it.startsWith("TR:") }.map { it.substringAfter(':').substringBefore('|').toLong() }
        assertEquals(listOf(monday.plusDays(4).toEpochDay()), trainingDates)
        assertNotNull(MealPlanRepository(db).loadLatestSnapshot(profileId, monday.toEpochDay()))
    }

    @Test
    fun recentMealsAreSentAndRepeatsAreOnlyANotice() = runBlocking {
        val first = service.generatePeriod(profileId, monday, 1)
        assertFalse("no memory yet", gateway.requests.single().userPrompt.lines().any { it.startsWith("RM:") })
        assertEquals("a varied plan has no notice", null, first.varietyNotice)

        // The next week is generated a week later: the scripted model reuses the same titles on purpose.
        val nextMonday = monday.plusWeeks(1)
        time.now = SixMonthHistoryFixture.epoch(nextMonday, 8)
        val second = service.generatePeriod(profileId, nextMonday, 1)

        val memory = gateway.requests.last().userPrompt.lines().filter { it.startsWith("RM:") }
        assertTrue("memory=$memory", memory.contains("RM:Colazione|Pasto fixture 0_0"))
        assertTrue("memory stays compact: ${memory.size}", memory.size <= MealVariety.MAX_MEMORY_MEALS)
        assertNotNull(second.varietyNotice)
        assertTrue(second.varietyNotice!!.contains("già proposti"))
        val saved = MealPlanRepository(db).versions(profileId, second.planId).first().last()
        assertTrue("warnings persisted", saved.appValidationJson.orEmpty().contains("VARIETY_REPEATS_RECENT"))
        assertNotNull("the plan is still saved", MealPlanRepository(db).loadLatestSnapshot(profileId, nextMonday.toEpochDay()))
    }

    private class FixedTime(var now: Long) : TimeProvider {
        override val zoneId = ZoneOffset.UTC
        override fun nowEpochMillis(): Long = now
    }

    /** Answers the weekly plan with every day centered on its own TD line, like a well-behaved model. */
    private class ScriptedGateway : AiRuntimeGateway {
        val requests = mutableListOf<AiStructuredRequest>()

        override suspend fun execute(
            request: AiStructuredRequest,
            maxSchemaRetries: Int,
            businessValidator: (String) -> Result<Unit>,
        ): AiExecutionService.ValidatedResponse {
            requests += request
            val json = weeklyPlan(request.userPrompt)
            businessValidator(json).getOrThrow()
            return AiExecutionService.ValidatedResponse(AiProviderType.OPENAI, "fixture", json)
        }

        private fun weeklyPlan(prompt: String): String {
            val lines = prompt.lines()
            val week = lines.first { it.startsWith("W:") }.substringAfter(':').toLong()
            val base = lines.first { it.startsWith("T:") }.substringAfter(':').split('|')
            val daily = lines.filter { it.startsWith("TD:") }.associate { line ->
                val fields = line.substringAfter(':').split('|')
                fields[0].toLong() to fields.drop(1)
            }
            val mealsPerDay = lines.first { it.startsWith("MEALS_PER_DAY:") }.substringAfter(':').toInt()
            val out = mutableListOf("MFP1", "W|$week")
            repeat(7) { offset ->
                val target = daily[week + offset] ?: base
                val kcal = target[0].toDouble().toInt()
                val protein = target[1].toDouble()
                val carbs = target[2].toDouble()
                val fat = target[3].toDouble()
                out += "D|${week + offset}|$kcal|$protein|$carbs|$fat"
                repeat(mealsPerDay) { index ->
                    val mealKcal = if (index == mealsPerDay - 1) kcal - (kcal / mealsPerDay) * (mealsPerDay - 1) else kcal / mealsPerDay
                    out += "M|${if (index == 0) "Colazione" else "Pasto"}|Pasto fixture ${offset}_$index|${420 + index * 120}|$mealKcal|${protein / mealsPerDay}|${carbs / mealsPerDay}|${fat / mealsPerDay}|Preparazione fixture"
                    out += "I|Ingrediente principale ${offset}_$index|100|g|100 g|RAW|HIGH|fixture"
                }
                out += "H|Bevi regolarmente in modo prudente."
            }
            out += "V|1|fixture"
            return JSONObject().put("data", out.joinToString("\n")).toString()
        }
    }

    private companion object {
        /** Never exists on the device: preferences are keyed by profile id. */
        const val TEST_PROFILE_ID = 9_000_002L
    }
}
