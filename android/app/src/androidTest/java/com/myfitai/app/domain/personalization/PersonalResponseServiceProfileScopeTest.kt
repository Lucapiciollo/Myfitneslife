package com.myfitai.app.domain.personalization

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.BiaRepository
import com.myfitai.app.data.repository.BodyExpectationGoalRepository
import com.myfitai.app.data.repository.BodyMeasurementRepository
import com.myfitai.app.data.repository.CheatEntryRepository
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.UserProfileRepository
import com.myfitai.app.data.repository.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Privacy regression: the history context of a job is always the one of the job's profile, whatever profile is
 * active. Isolated in-memory database; it never touches the user's data and does not change the active profile.
 */
@RunWith(AndroidJUnit4::class)
class PersonalResponseServiceProfileScopeTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var service: PersonalResponseService
    private var profileA = 0L
    private var profileB = 0L
    private val now = 1_790_000_000_000L
    private val day = 86_400_000L

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, MyFitAiDatabase::class.java).allowMainThreadQueries().build()
        val profiles = UserProfileRepository(db)
        val bia = BiaRepository(db)
        profileA = profiles.create(profile("Profilo A"))
        profileB = profiles.create(profile("Profilo B"))
        bia.insert(reading(profileA, now - 10 * day, 90f))
        bia.insert(reading(profileA, now - 2 * day, 88f))
        bia.insert(reading(profileB, now - 10 * day, 70f))
        bia.insert(reading(profileB, now - 2 * day, 71f))
        service = PersonalResponseService(
            ActiveProfileStore(context), MealPlanRepository(db), CheatEntryRepository(db), WorkoutRepository(db),
            bia, BodyMeasurementRepository(db), BodyExpectationGoalRepository(db),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun promptContextIsAlwaysOfTheRequestedProfile() = runBlocking {
        val a = service.promptContext(nowEpochMillis = now, profileId = profileA)
        val b = service.promptContext(nowEpochMillis = now, profileId = profileB)

        assertTrue(a, a.contains("PD:-2.00"))
        assertTrue(b, b.contains("PD:1.00"))
        assertEquals(a, service.promptContext(nowEpochMillis = now, profileId = profileA))
    }

    private fun profile(name: String) = UserProfileEntity(
        name = name, birthDateEpochDay = null, heightCm = 180f, currentWeightKg = 80f, goal = "Ricomposizione",
        activityLevel = null, wakeTimeMinutes = null, sleepTimeMinutes = null, dietaryPreferencesJson = null,
        createdAtEpochMillis = now, updatedAtEpochMillis = now,
    )

    private fun reading(profileId: Long, at: Long, weight: Float) = BiaMeasurementEntity(
        profileId = profileId, measuredAtEpochMillis = at,
        weightKg = weight, bodyFatPercent = 20f, visceralFatLevel = 6f, muscleMassKg = 66f,
        skeletalMuscleKg = 41f, bodyWaterPercent = 58f, bmrKcal = 1900f, fasting = true, justWokeUp = true,
        afterBathroom = true, noRecentWorkout = true, notes = null, fatMassKg = 18f, leanMassKg = 72f,
        bodyWaterKg = 52f, subcutaneousFatPercent = 14f, boneMassKg = 4.8f, proteinPercent = 16f,
        proteinKg = 14f, bodyAgeYears = 41, bmi = 26f,
    )
}
