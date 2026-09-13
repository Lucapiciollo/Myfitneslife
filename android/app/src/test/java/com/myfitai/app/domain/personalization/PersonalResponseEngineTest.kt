package com.myfitai.app.domain.personalization

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.data.local.entity.CheatEntryEntity
import com.myfitai.app.data.local.entity.WorkoutEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalResponseEngineTest {

    @Test
    fun analyze_requiresEvidenceBeforeRecurringPatterns() {
        val now = 10_000_000_000L
        val summary = PersonalResponseEngine.analyze(
            PersonalResponseEngine.Input(
                plans = emptyList(),
                cheats = listOf(cheat(1, now - day(1))),
                workouts = listOf(workout(1, now - day(1))),
                bia = emptyList(),
                bodyMeasurements = emptyList(),
                nowEpochMillis = now,
            )
        )

        assertTrue(summary.patterns.none { it.code == "RECURRENT_DEVIATIONS" })
        assertTrue(summary.patterns.none { it.code == "TRAINING_ROUTINE" })
    }

    @Test
    fun analyze_buildsDescriptivePatternsWithoutCausalLanguage() {
        val now = 10_000_000_000L
        val summary = PersonalResponseEngine.analyze(
            PersonalResponseEngine.Input(
                plans = emptyList(),
                planVersionReasons = listOf("AI_GENERATION", "CHEAT_ADAPTATION:1", "CHEAT_ADAPTATION:2"),
                cheats = listOf(
                    cheat(1, now - day(10), 300),
                    cheat(2, now - day(6), 450),
                    cheat(3, now - day(2), 250),
                ),
                workouts = listOf(
                    workout(1, now - day(8), "Pesi", 60),
                    workout(2, now - day(5), "Pesi", 55),
                    workout(3, now - day(1), "Cardio", 40),
                ),
                bia = listOf(
                    bia(1, now - day(20), weight = 80f, fat = 18f, muscle = 61f),
                    bia(2, now - day(1), weight = 79f, fat = 17f, muscle = 61.5f),
                ),
                bodyMeasurements = listOf(
                    body(1, now - day(20), waist = 90f),
                    body(2, now - day(1), waist = 88f),
                ),
                nowEpochMillis = now,
            )
        )

        assertEquals(3, summary.cheatCount)
        assertEquals(3, summary.workoutCount)
        assertEquals(3, summary.planVersions)
        assertTrue(summary.patterns.any { it.code == "RECURRENT_DEVIATIONS" })
        assertTrue(summary.patterns.any { it.code == "TRAINING_ROUTINE" })
        assertTrue(summary.patterns.any { it.code == "REPEATED_PLAN_ADAPTATION" })
        assertTrue(summary.patterns.any { it.code == "SIMULTANEOUS_BODY_TRENDS" })
        assertEquals(-1.0, summary.weightDeltaKg!!, 0.001)
        assertEquals(-1.0, summary.bodyFatDeltaPoints!!, 0.001)
        assertEquals(0.5, summary.muscleMassDeltaKg!!, 0.001)
        assertEquals(-2.0, summary.waistDeltaCm!!, 0.001)

        val context = summary.toPromptContext()
        assertTrue(context.contains("never causal"))
        assertTrue(context.contains("Do not alter local numerical targets"))
        assertFalse(context.contains("caused by", ignoreCase = true))
    }

    @Test
    fun promptContext_isBounded() {
        val summary = PersonalResponseEngine.Summary(
            lookbackDays = 56,
            planWeeks = 8,
            planVersions = 12,
            cheatCount = 5,
            workoutCount = 20,
            restDayCount = 3,
            weightDeltaKg = -1.0,
            bodyFatDeltaPoints = -0.5,
            muscleMassDeltaKg = 0.4,
            waistDeltaCm = -1.5,
            patterns = List(20) { index -> PersonalResponseEngine.Pattern("P$index", 3, "x".repeat(200)) },
        )
        val text = summary.toPromptContext(maxChars = 600)
        assertNotNull(text)
        assertTrue(text.length <= 600)
    }

    private fun day(value: Int): Long = value * 86_400_000L

    private fun cheat(id: Long, time: Long, kcal: Int? = null) = CheatEntryEntity(
        id = id,
        profileId = 1,
        occurredAtEpochMillis = time,
        description = "evento",
        quantityText = null,
        estimatedKcal = kcal,
        estimatedProteinG = null,
        estimatedCarbsG = null,
        estimatedFatG = null,
        planVersionId = null,
        notes = null,
    )

    private fun workout(id: Long, time: Long, type: String = "Pesi", duration: Int = 60) = WorkoutEntity(
        id = id,
        profileId = 1,
        startedAtEpochMillis = time,
        type = type,
        title = type,
        durationMinutes = duration,
        isRestDay = false,
        notes = null,
    )

    private fun bia(id: Long, time: Long, weight: Float, fat: Float, muscle: Float) = BiaMeasurementEntity(
        id = id,
        profileId = 1,
        measuredAtEpochMillis = time,
        weightKg = weight,
        bodyFatPercent = fat,
        visceralFatLevel = null,
        muscleMassKg = muscle,
        skeletalMuscleKg = null,
        bodyWaterPercent = null,
        bmrKcal = null,
        fasting = false,
        justWokeUp = false,
        afterBathroom = false,
        noRecentWorkout = false,
        notes = null,
    )

    private fun body(id: Long, time: Long, waist: Float) = BodyMeasurementEntity(
        id = id,
        profileId = 1,
        measuredAtEpochMillis = time,
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
        notes = null,
    )
}
