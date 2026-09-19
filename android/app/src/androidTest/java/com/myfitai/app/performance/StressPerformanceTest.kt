package com.myfitai.app.performance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.PlanVersionDraft
import com.myfitai.app.domain.export.ProfileExportService
import com.myfitai.app.domain.shopping.ShoppingListEngine
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class StressPerformanceTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var store: ActiveProfileStore
    private lateinit var databaseFile: File
    private lateinit var profileIds: List<Long>
    private lateinit var plans: MealPlanRepository
    private val time = FixedStressTime(LocalDate.of(2026, 9, 14).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli())

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        databaseFile = File(context.cacheDir, "stress-${System.nanoTime()}.db")
        db = Room.databaseBuilder(context, MyFitAiDatabase::class.java, databaseFile.absolutePath)
            .allowMainThreadQueries().build()
        store = ActiveProfileStore(context)
        store.clear()
        plans = MealPlanRepository(db)
        profileIds = (0 until 5).map { index -> db.userProfileDao().insert(profile("Stress $index")) }
        seedHistory()
    }

    @After
    fun tearDown() {
        db.close()
        databaseFile.delete()
        store.clear()
    }

    @Test
    fun stressDataset_measuresRealDeviceTimingsAndPreservesCounts() = runBlocking {
        val profileId = profileIds.first()
        store.selectProfile(profileId)
        val timings = linkedMapOf<String, Long>()

        timings["bia_history_ms"] = measure { db.biaMeasurementDao().observeAll(profileId).first() }
        timings["body_history_ms"] = measure { db.bodyMeasurementDao().observeAll(profileId).first() }
        timings["workout_history_ms"] = measure { db.workoutDao().observeAll(profileId).first() }
        timings["plan_roots_ms"] = measure { db.mealPlanDao().observePlans(profileId).first() }

        val latestPlan = db.mealPlanDao().observePlans(profileId).first().maxBy { it.weekStartEpochDay }
        val snapshotStart = System.nanoTime()
        val snapshot = plans.loadLatestSnapshot(profileId, latestPlan.weekStartEpochDay)!!
        timings["latest_snapshot_ms"] = elapsed(snapshotStart)
        val shoppingStart = System.nanoTime()
        val shopping = ShoppingListEngine.aggregate(snapshot)
        timings["shopping_list_ms"] = elapsed(shoppingStart)

        val export = ProfileExportService(context = ApplicationProvider.getApplicationContext(), db = db, activeProfileStore = store, time = time)
        timings["json_export_ms"] = measure { export.export(ProfileExportService.Format.JSON) }
        timings["csv_zip_export_ms"] = measure { export.export(ProfileExportService.Format.CSV_ZIP) }
        timings["profile_pdf_export_ms"] = measure { export.export(ProfileExportService.Format.PDF) }
        timings["weekly_pdf_export_ms"] = measure { export.export(ProfileExportService.Format.WEEKLY_PLAN_PDF) }

        assertEquals(48, db.biaMeasurementDao().observeAll(profileId).first().size)
        assertEquals(48, db.bodyMeasurementDao().observeAll(profileId).first().size)
        assertEquals(200, db.workoutDao().observeAll(profileId).first().size)
        assertEquals(104, db.mealPlanDao().observePlans(profileId).first().size)
        assertEquals(40, db.cheatEntryDao().observeAll(profileId).first().size)
        assertTrue(snapshot.version.days.isNotEmpty())
        assertTrue(shopping.isNotEmpty())
        assertTrue(timings.values.all { it >= 0L })
        println("STRESS_TIMINGS profile=$profileId data=$timings").also { }
    }

    private suspend fun seedHistory() {
        val start = LocalDate.of(2024, 9, 16)
        profileIds.forEachIndexed { profileIndex, profileId ->
            repeat(48) { index ->
                val date = start.plusDays(index * 15L)
                db.biaMeasurementDao().insert(BiaMeasurementEntity(profileId = profileId, measuredAtEpochMillis = epoch(date), weightKg = 94f - index * .05f, bodyFatPercent = 22f - index * .03f, visceralFatLevel = 10f, muscleMassKg = 68f + index * .01f, skeletalMuscleKg = 34f, bodyWaterPercent = 55f, bmrKcal = null, fasting = true, justWokeUp = true, afterBathroom = true, noRecentWorkout = true))
                db.bodyMeasurementDao().insert(BodyMeasurementEntity(profileId = profileId, measuredAtEpochMillis = epoch(date), chestCm = 104f, waistCm = 96f - index * .04f, abdomenCm = 99f, shouldersCm = 49f, glutesCm = 103f, armLeftCm = 35f, armRightCm = 35f, thighLeftCm = 60f, thighRightCm = 60f, calfLeftCm = 39f, calfRightCm = 39f))
            }
            repeat(200) { index -> db.workoutDao().insert(WorkoutEntity(profileId = profileId, startedAtEpochMillis = epoch(start.plusDays(index.toLong())), type = "PESI", title = "Stress workout $profileIndex-$index", durationMinutes = 45, isRestDay = false)) }
            repeat(40) { index -> db.cheatEntryDao().insert(CheatEntryEntity(profileId = profileId, occurredAtEpochMillis = epoch(start.plusDays(index * 18L), 20), description = "Stress cheat $profileIndex-$index", quantityText = "1", estimatedKcal = 400, estimatedProteinG = 15f, estimatedCarbsG = 45f, estimatedFatG = 12f, planVersionId = null)) }
            repeat(104) { weekIndex ->
                val week = start.plusWeeks(weekIndex.toLong())
                val planId = plans.createPlan(profileId, week.toEpochDay(), epoch(week))
                val versionCount = if (weekIndex < 92) 2 else 1
                repeat(versionCount) { version -> plans.appendVersion(profileId, planId, epoch(week, 8 + version), planVersion(week, version + 1)) }
            }
        }
    }

    private fun planVersion(week: LocalDate, version: Int) = PlanVersionDraft(
        source = "STRESS",
        reason = if (version == 1) "AI_GENERATION" else "STRESS_VERSION",
        targetKcal = 2400,
        targetProteinG = 180f,
        targetCarbsG = 280f,
        targetFatG = 70f,
        days = (0..6).map { day -> DayDraft(
            dateEpochDay = week.plusDays(day.toLong()).toEpochDay(),
            totalKcal = 2400,
            proteinG = 180f,
            carbsG = 280f,
            fatG = 70f,
            meals = listOf(
                meal("Colazione", 600, 420),
                meal("Pranzo", 800, 780),
                meal("Cena", 1000, 1200),
            ),
        ) },
    )

    private fun meal(type: String, kcal: Int, time: Int) = MealDraft(type, "Stress $type", time, kcal, 50f, 70f, 18f, "Fixture", listOf(IngredientDraft("Riso", 100f, "g", "100 g", "RAW", "HIGH", "cereali")))
    private fun profile(name: String) = UserProfileEntity(
        name = name,
        birthDateEpochDay = null,
        heightCm = 186f,
        currentWeightKg = 89f,
        goal = "Ricomposizione",
        activityLevel = "Moderatamente attivo",
        wakeTimeMinutes = 420,
        sleepTimeMinutes = 1410,
        dietaryPreferencesJson = null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        biologicalSex = "Maschio",
        initialWeightKg = 94f,
    )
    private fun epoch(date: LocalDate, hour: Int = 7) = date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000L
    private suspend fun <T> measure(block: suspend () -> T): Long { val start = System.nanoTime(); block(); return elapsed(start) }
}

private data class FixedStressTime(private val now: Long) : TimeProvider {
    override val zoneId = ZoneOffset.UTC
    override fun nowEpochMillis(): Long = now
}
