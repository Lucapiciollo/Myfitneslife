package com.myfitai.app.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.repository.DayDraft
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

        val all = dao.observeAll(profile1Id).first()
        assertEquals(listOf(3000L, 2000L, 1000L), all.map { it.measuredAtEpochMillis })

        val range = dao.observeBetween(profile1Id, 1000, 2500).first()
        assertEquals(listOf(1000L, 2000L), range.map { it.measuredAtEpochMillis })

        val otherProfile = dao.observeAll(profile2Id).first()
        assertEquals(listOf(4000L), otherProfile.map { it.measuredAtEpochMillis })
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

        val range = dao.observeBetween(profile1Id, 1000, 2500).first()
        assertEquals(listOf(1000L, 2000L), range.map { it.measuredAtEpochMillis })

        val otherProfile = dao.observeAll(profile2Id).first()
        assertEquals(listOf(70f), otherProfile.map { it.weightKg })
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

    private fun profile(name: String): UserProfileEntity {
        val now = 1000L
        return UserProfileEntity(
            name = name,
            birthDateEpochDay = null,
            heightCm = null,
            currentWeightKg = null,
            goal = null,
            activityLevel = null,
            wakeTimeMinutes = null,
            sleepTimeMinutes = null,
            dietaryPreferencesJson = null,
            photoPath = null,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
    }

    private fun measure(profileId: Long, at: Long, waist: Float) = BodyMeasurementEntity(
        profileId = profileId,
        measuredAtEpochMillis = at,
        chestCm = null,
        waistCm = waist,
        abdomenCm = null,
        shouldersCm = null,
        glutesCm = null,
        armLeftCm = null,
        armRightCm = null,
        thighLeftCm = null,
        thighRightCm = null,
        calfLeftCm = null,
        calfRightCm = null,
    )

    private fun bia(profileId: Long, at: Long, weight: Float?) = BiaMeasurementEntity(
        profileId = profileId,
        measuredAtEpochMillis = at,
        weightKg = weight,
        bodyFatPercent = null,
        visceralFatLevel = null,
        muscleMassKg = null,
        skeletalMuscleKg = null,
        bodyWaterPercent = null,
        bmrKcal = null,
        fasting = false,
        justWokeUp = false,
        afterBathroom = false,
        noRecentWorkout = false,
    )
}
