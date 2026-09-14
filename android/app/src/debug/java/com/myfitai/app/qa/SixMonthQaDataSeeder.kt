package com.myfitai.app.qa

import android.content.Context
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.*
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

class SixMonthQaDataSeeder(context: Context) {
    private val appContext = context.applicationContext
    private val db = MyFitAiDatabase.getInstance(appContext)
    private val plans = MealPlanRepository(db)
    private val profiles = UserProfileRepository(db)
    private val active = ActiveProfileStore(appContext)
    private val today = LocalDate.of(2026, 9, 14)
    private val start = today.minusMonths(6)

    suspend fun reset() {
        db.clearAllTables()
        active.clear()
    }

    suspend fun seedSixMonths(): String {
        reset()
        val profileId = profiles.create(profile("Test Sport", "Moderatamente attivo", 89f, 94f))
        active.selectProfile(profileId)
        repeat(13) { index ->
            val date = start.plusDays(index * 15L)
            db.biaMeasurementDao().insert(BiaMeasurementEntity(
                profileId = profileId, measuredAtEpochMillis = epoch(date),
                weightKg = (94f - index * .4f + oscillation(index)),
                bodyFatPercent = (22f - index * .31f + oscillation(index) * .1f),
                visceralFatLevel = 10f - index * .1f, muscleMassKg = 68f + index * .14f,
                skeletalMuscleKg = 34f + index * .07f, bodyWaterPercent = 55f + index * .13f,
                bmrKcal = null, fasting = true, justWokeUp = true, afterBathroom = true, noRecentWorkout = true,
                notes = "QA seed 20260914",
            ))
            db.bodyMeasurementDao().insert(BodyMeasurementEntity(
                profileId = profileId, measuredAtEpochMillis = epoch(date), chestCm = 104f,
                waistCm = 96f - index * .58f + oscillation(index), abdomenCm = 99f - index * .55f,
                shouldersCm = 49f, glutesCm = 103f - index * .15f, armLeftCm = 35f,
                armRightCm = 35f, thighLeftCm = 60f, thighRightCm = 60f, calfLeftCm = 39f, calfRightCm = 39f,
            ))
        }
        repeat(26) { weekIndex ->
            val week = start.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusWeeks(weekIndex.toLong())
            val planId = plans.createPlan(profileId, week.toEpochDay(), epoch(week, 8))
            plans.appendVersion(profileId, planId, epoch(week, 8), version(week, "AI_GENERATION", null))
            if (weekIndex % 5 == 0) plans.appendVersion(profileId, planId, epoch(week, 9), version(week, "QA", "AI_MEAL_SWAP:$weekIndex"))
        }
        repeat(104) { index -> db.workoutDao().insert(WorkoutEntity(profileId = profileId, startedAtEpochMillis = epoch(start.plusDays(index.toLong())), type = "PESI", title = "QA workout $index", durationMinutes = 50, isRestDay = false)) }
        repeat(20) { index -> db.cheatEntryDao().insert(CheatEntryEntity(profileId = profileId, occurredAtEpochMillis = epoch(start.plusDays(index * 9L), 20), description = "QA sgarro $index", quantityText = "1 porzione", estimatedKcal = 350, estimatedProteinG = 10f, estimatedCarbsG = 40f, estimatedFatG = 12f, planVersionId = null)) }
        return counts(profileId)
    }

    suspend fun seedStress(): String {
        reset()
        repeat(5) { index ->
            val profileId = profiles.create(profile("QA Stress $index", "Moderatamente attivo", 89f, 94f))
            if (index == 0) active.selectProfile(profileId)
            repeat(48) { point ->
                val date = start.minusMonths(18).plusDays(point * 15L)
                db.biaMeasurementDao().insert(BiaMeasurementEntity(profileId = profileId, measuredAtEpochMillis = epoch(date), weightKg = 94f - point * .03f, bodyFatPercent = 22f, visceralFatLevel = 10f, muscleMassKg = 68f, skeletalMuscleKg = 34f, bodyWaterPercent = 55f, bmrKcal = null, fasting = true, justWokeUp = true, afterBathroom = true, noRecentWorkout = true))
                db.bodyMeasurementDao().insert(BodyMeasurementEntity(profileId = profileId, measuredAtEpochMillis = epoch(date), chestCm = 104f, waistCm = 96f, abdomenCm = 99f, shouldersCm = 49f, glutesCm = 103f, armLeftCm = 35f, armRightCm = 35f, thighLeftCm = 60f, thighRightCm = 60f, calfLeftCm = 39f, calfRightCm = 39f))
            }
            repeat(200) { workout -> db.workoutDao().insert(WorkoutEntity(profileId = profileId, startedAtEpochMillis = epoch(start.minusMonths(18).plusDays(workout.toLong())), type = "PESI", title = "Stress $workout", durationMinutes = 45, isRestDay = false)) }
            repeat(40) { cheat -> db.cheatEntryDao().insert(CheatEntryEntity(profileId = profileId, occurredAtEpochMillis = epoch(start.minusMonths(18).plusDays(cheat * 18L), 20), description = "Stress $cheat", quantityText = "1", estimatedKcal = 400, estimatedProteinG = 15f, estimatedCarbsG = 45f, estimatedFatG = 12f, planVersionId = null)) }
            repeat(104) { weekIndex ->
                val week = start.minusMonths(18).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusWeeks(weekIndex.toLong())
                val planId = plans.createPlan(profileId, week.toEpochDay(), epoch(week, 8))
                repeat(if (weekIndex < 92) 3 else 1) { versionIndex -> plans.appendVersion(profileId, planId, epoch(week, 8 + versionIndex), version(week, "STRESS", "v$versionIndex")) }
            }
        }
        return counts(active.currentIdOrNull() ?: 1L)
    }

    private suspend fun counts(profileId: Long): String = "profile=$profileId bia=${db.biaMeasurementDao().observeAll(profileId).first().size} body=${db.bodyMeasurementDao().observeAll(profileId).first().size} workouts=${db.workoutDao().observeAll(profileId).first().size} plans=${db.mealPlanDao().observePlans(profileId).first().size} cheats=${db.cheatEntryDao().observeAll(profileId).first().size}"

    private fun profile(name: String, activity: String, weight: Float, initial: Float) = UserProfileEntity(name = name, birthDateEpochDay = LocalDate.of(1983, 8, 9).toEpochDay(), heightCm = 186f, currentWeightKg = weight, goal = "Ricomposizione", activityLevel = activity, wakeTimeMinutes = 420, sleepTimeMinutes = 1410, dietaryPreferencesJson = null, createdAtEpochMillis = 1L, updatedAtEpochMillis = epoch(today), biologicalSex = "Maschio", initialWeightKg = initial)
    private fun version(week: LocalDate, source: String, reason: String?) = PlanVersionDraft(source, reason, 2400, 180f, 280f, 70f, (0..6).map { day -> DayDraft(week.plusDays(day.toLong()).toEpochDay(), 2400, 180f, 280f, 70f, listOf(meal("Colazione", 600, 420), meal("Pranzo", 800, 780), meal("Cena", 1000, 1200)), supplements = listOf(SupplementDraft("PROTEIN_POWDER", "Whey", 30f, "g", 1110, 120, 24f, 3f, 2f, "QA")), hydrationNote = "Idratazione QA prudente" ) })
    private fun meal(type: String, kcal: Int, time: Int) = MealDraft(type, "QA $type", time, kcal, 50f, 70f, 18f, "Preparazione QA", listOf(IngredientDraft("Riso", 100f, "g", "100 g", "RAW", "HIGH", "cereali")))
    private fun epoch(date: LocalDate, hour: Int = 7) = date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun oscillation(index: Int) = listOf(0f, .3f, -.2f, .15f, -.25f)[index % 5]
}
