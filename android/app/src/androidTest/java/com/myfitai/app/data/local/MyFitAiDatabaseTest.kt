package com.myfitai.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.PlanVersionDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyFitAiDatabaseTest {
    private lateinit var db: MyFitAiDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyFitAiDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun bodyMeasurements_areIsolatedByProfile_andRangeAscending() = runBlocking {
        val profile1Id = db.userProfileDao().insert(profile("Uno"))
        val profile2Id = db.userProfileDao().insert(profile("Due"))
        val dao = db.bodyMeasurementDao()
        dao.insert(measure(profile1Id, 1000, 90f))
        dao.insert(measure(profile1Id, 3000, 86f))
        dao.insert(measure(profile1Id, 2000, 88f))
        dao.insert(measure(profile2Id, 4000, 70f))
        assertEquals(listOf(3000L, 2000L, 1000L), dao.observeAll(profile1Id).first().map { it.measuredAtEpochMillis })
        assertEquals(listOf(1000L, 2000L), dao.observeBetween(profile1Id, 1000, 2500).first().map { it.measuredAtEpochMillis })
        assertEquals(listOf(4000L), dao.observeAll(profile2Id).first().map { it.measuredAtEpochMillis })
    }

    @Test
    fun bodyMeasurements_sameTimestamp_keepNewestInsertFirst() = runBlocking {
        val profileId = db.userProfileDao().insert(profile("Storico misure"))
        val dao = db.bodyMeasurementDao()
        val firstId = dao.insert(measure(profileId, 5000, 90f))
        val secondId = dao.insert(measure(profileId, 5000, 89f))
        val all = dao.observeAll(profileId).first()
        assertEquals(listOf(secondId, firstId), all.map { it.id })
        assertEquals(listOf(89f, 90f), all.map { it.waistCm })
    }

    @Test
    fun biaHistory_isIsolatedByProfile_andChronological() = runBlocking {
        val profile1Id = db.userProfileDao().insert(profile("BIA uno"))
        val profile2Id = db.userProfileDao().insert(profile("BIA due"))
        val dao = db.biaMeasurementDao()
        dao.insert(bia(profile1Id, 1000, 80f))
        dao.insert(bia(profile1Id, 3000, 78f))
        dao.insert(bia(profile1Id, 2000, null))
        dao.insert(bia(profile2Id, 4000, 70f))
        val all = dao.observeAll(profile1Id).first()
        assertEquals(listOf(3000L, 2000L, 1000L), all.map { it.measuredAtEpochMillis })
        assertEquals(listOf(78f, null, 80f), all.map { it.weightKg })
        assertEquals(listOf(1000L, 2000L), dao.observeBetween(profile1Id, 1000, 2500).first().map { it.measuredAtEpochMillis })
        assertEquals(listOf(70f), dao.observeAll(profile2Id).first().map { it.weightKg })
    }

    @Test
    fun workoutHistory_isIsolatedByProfile_andRangeAscending() = runBlocking {
        val profile1Id = db.userProfileDao().insert(profile("Workout uno"))
        val profile2Id = db.userProfileDao().insert(profile("Workout due"))
        val dao = db.workoutDao()
        dao.insert(workout(profile1Id, 3000, "Upper"))
        dao.insert(workout(profile1Id, 1000, "Lower"))
        dao.insert(workout(profile1Id, 2000, "Riposo", rest = true))
        dao.insert(workout(profile2Id, 4000, "Cardio"))
        assertEquals(listOf(3000L, 2000L, 1000L), dao.observeAll(profile1Id).first().map { it.startedAtEpochMillis })
        assertEquals(listOf(1000L, 2000L), dao.observeBetween(profile1Id, 1000, 2500).first().map { it.startedAtEpochMillis })
        assertEquals(listOf("Cardio"), dao.observeAll(profile2Id).first().map { it.title })
    }

    @Test
    fun mealPlan_appendVersion_neverOverwritesPreviousVersion() = runBlocking {
        val profileId = db.userProfileDao().insert(profile("Piano"))
        val repository = MealPlanRepository(db)
        val planId = repository.createPlan(profileId, 20000, 1000)
        val emptyDraft = PlanVersionDraft(
            source = "TEST",
            reason = null,
            targetKcal = 2200,
            targetProteinG = 160f,
            targetCarbsG = 230f,
            targetFatG = 70f,
            days = listOf(DayDraft(20000, 2200, 160f, 230f, 70f, emptyList())),
        )
        repository.appendVersion(planId, 2000, emptyDraft)
        repository.appendVersion(planId, 3000, emptyDraft.copy(reason = "ADAPTATION"))
        val versions = repository.versions(planId).first()
        assertEquals(2, versions.size)
        assertEquals(listOf(2, 1), versions.map { it.versionNumber })
        assertTrue(versions.any { it.reason == "ADAPTATION" })
    }

    @Test
    fun mealPlan_latestSnapshot_containsDaysMealsAndIngredients() = runBlocking {
        val profileId = db.userProfileDao().insert(profile("Piano completo"))
        val repository = MealPlanRepository(db)
        val weekStart = 21000L
        val planId = repository.createPlan(profileId, weekStart, 1000)
        repository.appendVersion(
            planId = planId,
            createdAtEpochMillis = 2000,
            draft = PlanVersionDraft(
                source = "TEST",
                reason = null,
                targetKcal = 2200,
                targetProteinG = 160f,
                targetCarbsG = 230f,
                targetFatG = 70f,
                days = listOf(
                    DayDraft(
                        dateEpochDay = weekStart,
                        totalKcal = 2200,
                        proteinG = 160f,
                        carbsG = 230f,
                        fatG = 70f,
                        meals = listOf(
                            MealDraft(
                                type = "Pranzo",
                                title = "Riso e pollo",
                                timeMinutes = 780,
                                kcal = 700,
                                proteinG = 50f,
                                carbsG = 80f,
                                fatG = 18f,
                                preparation = "Cuoci e componi",
                                ingredients = listOf(
                                    IngredientDraft("Riso", 80f, "g", "80 g", "DRY", "HIGH", "carbs"),
                                    IngredientDraft("Pollo", 200f, "g", "200 g", "RAW", "HIGH", "protein"),
                                ),
                            )
                        ),
                    )
                ),
            ),
        )

        val snapshot = repository.loadLatestSnapshot(profileId, weekStart)!!
        assertEquals(profileId, snapshot.profileId)
        assertEquals(1, snapshot.version.versionNumber)
        assertEquals(1, snapshot.version.days.size)
        assertEquals("Riso e pollo", snapshot.version.days.single().meals.single().title)
        assertEquals(listOf("Riso", "Pollo"), snapshot.version.days.single().meals.single().ingredients.map { it.name })
    }

    private fun profile(name: String): UserProfileEntity {
        val now = 1000L
        return UserProfileEntity(name = name, birthDateEpochDay = null, heightCm = null, currentWeightKg = null, goal = null, activityLevel = null, wakeTimeMinutes = null, sleepTimeMinutes = null, dietaryPreferencesJson = null, photoPath = null, createdAtEpochMillis = now, updatedAtEpochMillis = now)
    }

    private fun measure(profileId: Long, at: Long, waist: Float) = BodyMeasurementEntity(profileId = profileId, measuredAtEpochMillis = at, chestCm = null, waistCm = waist, abdomenCm = null, shouldersCm = null, glutesCm = null, armLeftCm = null, armRightCm = null, thighLeftCm = null, thighRightCm = null, calfLeftCm = null, calfRightCm = null)

    private fun bia(profileId: Long, at: Long, weight: Float?) = BiaMeasurementEntity(profileId = profileId, measuredAtEpochMillis = at, weightKg = weight, bodyFatPercent = null, visceralFatLevel = null, muscleMassKg = null, skeletalMuscleKg = null, bodyWaterPercent = null, bmrKcal = null, fasting = false, justWokeUp = false, afterBathroom = false, noRecentWorkout = false)

    private fun workout(profileId: Long, at: Long, title: String, rest: Boolean = false) = WorkoutEntity(
        profileId = profileId,
        startedAtEpochMillis = at,
        type = if (rest) "REST" else "PESI",
        title = title,
        durationMinutes = if (rest) null else 45,
        isRestDay = rest,
    )
}
