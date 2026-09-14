package com.myfitai.app.fixtures

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.local.entity.MealPlanEntity
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.local.entity.WeeklyReviewEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.PlanVersionDraft
import com.myfitai.app.data.repository.SupplementDraft
import com.myfitai.app.domain.calculation.LocalCalculationEngine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** Deterministic six-month history used by regression tests; no provider or system clock involved. */
object SixMonthHistoryFixture {
    const val SEED = 20260914
    val TODAY: LocalDate = LocalDate.of(2026, 9, 14)
    val START: LocalDate = TODAY.minusMonths(6)
    val ZONE = ZoneOffset.UTC

    data class Dataset(
        val profile: UserProfileEntity,
        val bia: List<BiaMeasurementEntity>,
        val body: List<BodyMeasurementEntity>,
        val workouts: List<WorkoutEntity>,
        val plans: List<PlanFixture>,
        val cheats: List<CheatEntryEntity>,
        val reviews: List<WeeklyReviewEntity>,
    )

    data class PlanFixture(
        val weekStart: LocalDate,
        val createdAtEpochMillis: Long,
        val versions: List<PlanVersionDraft>,
    )

    fun build(): Dataset {
        val profile = UserProfileEntity(
            name = "Test Sport",
            birthDateEpochDay = LocalDate.of(1983, 8, 9).toEpochDay(),
            heightCm = 186f,
            currentWeightKg = 89f,
            goal = "Ricomposizione",
            activityLevel = "Moderatamente attivo",
            wakeTimeMinutes = 420,
            sleepTimeMinutes = 1410,
            dietaryPreferencesJson = "{\"style\":\"onnivoro\"}",
            createdAtEpochMillis = epoch(START),
            updatedAtEpochMillis = epoch(TODAY),
            biologicalSex = "Maschio",
            initialWeightKg = 94f,
        )

        val bia = (0..12).map { index ->
            val date = START.plusDays(index * 15L)
            val oscillation = listOf(0.0, 0.35, -0.2, 0.25, -0.3, 0.15, -0.1)[index % 7]
            val progress = index / 12.0
            BiaMeasurementEntity(
                profileId = 1,
                measuredAtEpochMillis = epoch(date),
                weightKg = (94.0 - progress * 5.0 + oscillation).toFloat(),
                bodyFatPercent = (22.0 - progress * 4.0 + oscillation * 0.18).toFloat(),
                visceralFatLevel = (10.0 - progress * 2.0).toFloat(),
                muscleMassKg = (68.0 + progress * 1.7 - oscillation * 0.08).toFloat(),
                skeletalMuscleKg = (34.0 + progress * 0.9).toFloat(),
                bodyWaterPercent = (54.8 + progress * 1.6).toFloat(),
                bmrKcal = null,
                fasting = true,
                justWokeUp = true,
                afterBathroom = true,
                noRecentWorkout = true,
                notes = "Fixture seed $SEED",
            )
        }

        val body = (0..12).map { index ->
            val date = START.plusDays(index * 15L)
            val oscillation = listOf(0.0, 0.4, -0.25, 0.2, -0.35, 0.15)[index % 6]
            val progress = index / 12.0
            BodyMeasurementEntity(
                profileId = 1,
                measuredAtEpochMillis = epoch(date),
                chestCm = (104.0 - progress * 1.5 + oscillation).toFloat(),
                waistCm = (96.0 - progress * 7.5 + oscillation).toFloat(),
                abdomenCm = (99.0 - progress * 7.0 + oscillation).toFloat(),
                shouldersCm = 49f,
                glutesCm = (103.0 - progress * 2.0).toFloat(),
                armLeftCm = (35.0 + progress * 0.2).toFloat(),
                armRightCm = (35.0 + progress * 0.2).toFloat(),
                thighLeftCm = (60.0 + progress * 0.2).toFloat(),
                thighRightCm = (60.0 + progress * 0.2).toFloat(),
                calfLeftCm = 39f,
                calfRightCm = 39f,
                notes = "Fixture seed $SEED",
            )
        }

        val workouts = generateWorkouts()
        val plans = generatePlans()
        val cheats = generateCheats()
        val reviews = plans.map { plan ->
            WeeklyReviewEntity(
                profileId = 1,
                weekStartEpochDay = plan.weekStart.toEpochDay(),
                createdAtEpochMillis = epoch(plan.weekStart.plusDays(7)),
                adherencePercent = null,
                summary = "Review deterministica della settimana ${plan.weekStart}",
                structuredJson = "{\"fixtureSeed\":$SEED,\"weekStartEpochDay\":${plan.weekStart.toEpochDay()}}",
            )
        }
        return Dataset(profile, bia, body, workouts, plans, cheats, reviews)
    }

    fun epoch(date: LocalDate, hour: Int = 7): Long = date.atTime(hour, 0).toInstant(ZONE).toEpochMilli()

    fun modeForWeek(weekStart: LocalDate): LocalCalculationEngine.ActivityLevel =
        if (weekStart.isBefore(START.plusMonths(2))) LocalCalculationEngine.ActivityLevel.SEDENTARY
        else LocalCalculationEngine.ActivityLevel.MODERATE

    private fun generateWorkouts(): List<WorkoutEntity> = buildList {
        var date = START.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY))
        while (!date.isAfter(TODAY)) {
            val sport = !date.isBefore(START.plusMonths(2))
            if (sport) {
                listOf(1, 3, 5, 6).forEach { offset ->
                    val day = date.plusDays(offset.toLong())
                    if (!day.isAfter(TODAY)) add(workout(day, "Pesi", false))
                }
            } else {
                add(workout(date.plusDays(2), "Riposo", true))
            }
            date = date.plusWeeks(1)
        }
    }

    private fun generatePlans(): List<PlanFixture> {
        val firstMonday = START.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return (0 until 26).map { weekIndex ->
            val week = firstMonday.plusWeeks(weekIndex.toLong())
            PlanFixture(
                weekStart = week,
                createdAtEpochMillis = epoch(week, 8),
                versions = buildList {
                    add(planVersion(week, "AI_GENERATION", null, weekIndex))
                    if (weekIndex % 7 == 2) add(planVersion(week, "TEST_PROVIDER", "AI_MEAL_SWAP:fixture-$weekIndex", weekIndex))
                    if (weekIndex % 9 == 4) add(planVersion(week, "TEST_PROVIDER", "CHEAT_ADAPTATION:fixture-$weekIndex", weekIndex))
                },
            )
        }
    }

    private fun planVersion(week: LocalDate, source: String, reason: String?, index: Int): PlanVersionDraft {
        val kcal = 2540
        val protein = 206f
        val carbs = 248f
        val fat = 74f
        return PlanVersionDraft(
            source = source,
            reason = reason,
            targetKcal = kcal,
            targetProteinG = protein,
            targetCarbsG = carbs,
            targetFatG = fat,
            days = (0..6).map { dayOffset ->
                val date = week.plusDays(dayOffset.toLong())
                val meals = listOf(
                    meal("Colazione", "Yogurt e avena", 450, 32f, 55f, 12f, 450),
                    meal("Pranzo", "Riso e pollo", 720, 55f, 78f, 18f, 720),
                    meal("Cena", "Salmone e patate", 780, 50f, 62f, 27f, 930),
                    meal("Spuntino", "Frutta e whey", 470, 45f, 50f, 15f, 1110),
                )
                DayDraft(
                    dateEpochDay = date.toEpochDay(),
                    totalKcal = kcal,
                    proteinG = protein,
                    carbsG = carbs,
                    fatG = fat,
                    meals = meals,
                    supplements = listOf(
                        SupplementDraft("PROTEIN_POWDER", "Whey", 30f, "g", 1110, 120, 24f, 3f, 2f, "Opzionale"),
                    ) + if (!week.isBefore(START.plusMonths(2)) && dayOffset % 2 == 0) listOf(SupplementDraft("CREATINE", "Creatina monoidrato", 5f, "g", 1080, 0, 0f, 0f, 0f, "Sport")) else emptyList(),
                    hydrationNote = if (dayOffset % 3 == 0) "Distribuisci l'acqua nella giornata, senza interpretazioni diagnostiche." else null,
                )
            },
        )
    }

    private fun generateCheats(): List<CheatEntryEntity> {
        val descriptions = listOf("Gelato", "Pizza", "Hamburger", "Dolce", "Aperitivo", "Snack confezionato")
        return (0 until 18).map { index ->
            val date = START.plusDays(9L + index * 10L)
            CheatEntryEntity(
                profileId = 1,
                occurredAtEpochMillis = epoch(date, 20),
                description = descriptions[index % descriptions.size],
                quantityText = if (index % 4 == 0) "porzione ambigua" else "1 porzione",
                estimatedKcal = 280 + index * 35,
                estimatedProteinG = 8f + index % 4,
                estimatedCarbsG = 35f + index % 7,
                estimatedFatG = 10f + index % 5,
                planVersionId = null,
                notes = "Fixture storico",
            )
        }
    }

    private fun workout(date: LocalDate, title: String, rest: Boolean) = WorkoutEntity(
        profileId = 1,
        startedAtEpochMillis = epoch(date, if (rest) 10 else 18),
        type = if (rest) "REST" else "PESI",
        title = title,
        durationMinutes = if (rest) null else 55,
        isRestDay = rest,
        notes = "Fixture seed $SEED",
    )

    private fun meal(type: String, title: String, protein: Int, p: Float, c: Float, f: Float, time: Int) = MealDraft(
        type = type,
        title = title,
        timeMinutes = time,
        kcal = protein,
        proteinG = p,
        carbsG = c,
        fatG = f,
        preparation = "Preparazione deterministica",
        ingredients = listOf(
            IngredientDraft("Ingrediente principale", 100f, "g", "100 g", "RAW", "HIGH", "fixture"),
            IngredientDraft("Olio extravergine", 5f, "g", "1 cucchiaino", "RAW", "HIGH", "condimenti"),
        ),
    )
}
