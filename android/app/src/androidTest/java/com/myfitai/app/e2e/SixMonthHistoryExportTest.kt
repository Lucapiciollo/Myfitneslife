package com.myfitai.app.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.WeeklyReviewEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.domain.export.ProfileExportService
import com.myfitai.app.domain.time.TimeProvider
import com.myfitai.app.fixtures.SixMonthHistoryFixture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class SixMonthHistoryExportTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var store: ActiveProfileStore
    private lateinit var fixture: SixMonthHistoryFixture.Dataset
    private lateinit var databaseFile: File
    private val time = FixedTimeProvider(SixMonthHistoryFixture.epoch(SixMonthHistoryFixture.TODAY, 12))

    @Before
    fun setUp() = runBlocking {
        fixture = SixMonthHistoryFixture.build()
        databaseFile = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "six-month-${System.nanoTime()}.db")
        db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyFitAiDatabase::class.java,
            databaseFile.absolutePath,
        ).allowMainThreadQueries().build()
        store = ActiveProfileStore(ApplicationProvider.getApplicationContext())
        store.clear()
        insertFixture()
    }

    @After
    fun tearDown() {
        db.close()
        databaseFile.delete()
        store.clear()
    }

    @Test
    fun sixMonthFixture_persistsAllHistoryAndVersions() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        assertEquals(13, db.biaMeasurementDao().observeAll(profileId).first().size)
        assertEquals(13, db.bodyMeasurementDao().observeAll(profileId).first().size)
        assertTrue(db.workoutDao().observeAll(profileId).first().size > 70)
        assertEquals(18, db.cheatEntryDao().observeAll(profileId).first().size)
        assertEquals(26, db.mealPlanDao().observePlans(profileId).first().size)
        assertEquals(26, db.weeklyReviewDao().observeAll(profileId).first().size)
        assertTrue(db.foodConsumptionDao().observeAll(profileId).first().isEmpty())
        assertTrue(db.mealPlanDao().observeVersions(profileId, db.mealPlanDao().observePlans(profileId).first().first().id).first().size >= 1)
    }

    @Test
    fun restartLikeReopen_preservesHistoricalRoomData() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val before = db.biaMeasurementDao().observeAll(profileId).first().map { it.weightKg }
        val planCount = db.mealPlanDao().observePlans(profileId).first().size
        db.close()
        db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyFitAiDatabase::class.java,
            databaseFile.absolutePath,
        ).allowMainThreadQueries().addMigrations(*com.myfitai.app.data.local.DatabaseMigrations.ALL).build()
        assertEquals(before, db.biaMeasurementDao().observeAll(profileId).first().map { it.weightKg })
        assertEquals(planCount, db.mealPlanDao().observePlans(profileId).first().size)
    }

    @Test
    fun exportJson_containsProfileHistoryVersionsSupplementsAndHydration() = runBlocking {
        val profileId = store.currentIdOrNull()!!
        val plan = db.mealPlanDao().observePlans(profileId).first().first()
        val version = db.mealPlanDao().observeVersions(profileId, plan.id).first().first()
        val day = db.mealPlanDao().getDays(profileId, version.id).first()
        val meal = db.mealPlanDao().getMeals(profileId, day.id).first()
        db.foodConsumptionDao().upsert(
            FoodConsumptionEntity(
                profileId = profileId,
                planId = plan.id,
                planVersionId = version.id,
                dayId = day.id,
                plannedDateEpochDay = day.dateEpochDay,
                itemType = "MEAL",
                itemKey = "MEAL:${meal.id}",
                mealId = meal.id,
                supplementKey = null,
                status = "CONSUMED",
                recordedAtEpochMillis = 1234L,
                updatedAtEpochMillis = 1234L,
                quantityFactor = 1f,
                kcal = 700,
                proteinG = 50f,
                carbsG = 80f,
                fatG = 18f,
            ),
        )
        val service = exportService()
        val exported = service.export(ProfileExportService.Format.JSON)
        val root = JSONObject(exported.file.readText())

        assertEquals("myfitai_profile_export_v1", root.getString("schema"))
        assertEquals("Test Sport", root.getJSONObject("profile").getString("name"))
        assertEquals(13, root.getJSONArray("biaMeasurements").length())
        assertEquals(13, root.getJSONArray("bodyMeasurements").length())
        assertTrue(root.getJSONArray("workouts").length() > 70)
        assertEquals(18, root.getJSONArray("cheatEntries").length())
        assertEquals(26, root.getJSONArray("weeklyReviews").length())
        assertEquals(1, root.getJSONArray("foodConsumptions").length())
        assertEquals("CONSUMED", root.getJSONArray("foodConsumptions").getJSONObject(0).getString("status"))
        assertEquals(700, root.getJSONArray("foodConsumptions").getJSONObject(0).getInt("kcal"))
        assertEquals(26, root.getJSONArray("mealPlans").length())
        assertTrue(root.getJSONArray("mealPlans").toString().contains("supplements"))
        assertTrue(root.getJSONArray("mealPlans").toString().contains("hydrationNote"))
        assertFalse(root.toString().contains("apiKey"))
        assertFalse(root.toString().contains("openai"))
    }

    @Test
    fun exportZipAndPdfs_areCreatedWithExpectedStructure() = runBlocking {
        val service = exportService()
        val zip = service.export(ProfileExportService.Format.CSV_ZIP).file
        val entries = buildList {
            ZipInputStream(zip.inputStream()).use { stream ->
                while (true) add(stream.nextEntry?.name ?: break)
            }
        }
        assertTrue(entries.contains("profile.csv"))
        assertTrue(entries.contains("meal_plans.json"))
        assertTrue(entries.contains("README.txt"))
        assertTrue(entries.contains("biaMeasurements.csv"))
        assertTrue(entries.contains("foodConsumptions.csv"))

        val profilePdf = service.export(ProfileExportService.Format.PDF).file
        val weeklyPdf = service.export(ProfileExportService.Format.WEEKLY_PLAN_PDF).file
        assertTrue(profilePdf.length() > 1_000L)
        assertTrue(weeklyPdf.length() > 1_000L)
    }

    @Test
    fun switchingProfiles_keepsHistoryAndExportScoped() = runBlocking {
        val secondId = db.userProfileDao().insert(fixture.profile.copy(id = 0, name = "Test Normal", currentWeightKg = 76f, activityLevel = "Sedentario"))
        val thirdId = db.userProfileDao().insert(fixture.profile.copy(id = 0, name = "Test Empty", currentWeightKg = null, activityLevel = null))
        val primaryId = store.currentIdOrNull()!!

        store.selectProfile(secondId)
        assertTrue(db.biaMeasurementDao().observeAll(secondId).first().isEmpty())
        assertTrue(db.mealPlanDao().observePlans(secondId).first().isEmpty())
        assertEquals("Test Normal", db.userProfileDao().get(secondId)?.name)

        store.selectProfile(thirdId)
        val emptyExport = exportService().export(ProfileExportService.Format.JSON).file
        val emptyRoot = JSONObject(emptyExport.readText())
        assertEquals("Test Empty", emptyRoot.getJSONObject("profile").getString("name"))
        assertEquals(0, emptyRoot.getJSONArray("biaMeasurements").length())
        assertEquals(0, emptyRoot.getJSONArray("mealPlans").length())

        store.selectProfile(primaryId)
        val primaryExport = exportService().export(ProfileExportService.Format.JSON).file
        assertEquals("Test Sport", JSONObject(primaryExport.readText()).getJSONObject("profile").getString("name"))
    }

    private fun exportService() = ProfileExportService(
        context = ApplicationProvider.getApplicationContext(),
        db = db,
        activeProfileStore = store,
        time = time,
    )

    private suspend fun insertFixture() {
        val profileId = db.userProfileDao().insert(fixture.profile)
        store.selectProfile(profileId)
        fixture.bia.forEach { db.biaMeasurementDao().insert(it.copy(profileId = profileId)) }
        fixture.body.forEach { db.bodyMeasurementDao().insert(it.copy(profileId = profileId)) }
        fixture.workouts.forEach { db.workoutDao().insert(it.copy(profileId = profileId)) }
        fixture.cheats.forEach { db.cheatEntryDao().insert(it.copy(profileId = profileId)) }
        fixture.reviews.forEach { db.weeklyReviewDao().upsert(it.copy(profileId = profileId)) }
        val plans = MealPlanRepository(db)
        fixture.plans.forEach { plan ->
            val planId = plans.createPlan(profileId, plan.weekStart.toEpochDay(), plan.createdAtEpochMillis)
            plan.versions.forEach { version -> plans.appendVersion(profileId, planId, plan.createdAtEpochMillis, version) }
        }
    }

    private data class FixedTimeProvider(private val now: Long) : TimeProvider {
        override val zoneId = SixMonthHistoryFixture.ZONE
        override fun nowEpochMillis(): Long = now
    }
}
