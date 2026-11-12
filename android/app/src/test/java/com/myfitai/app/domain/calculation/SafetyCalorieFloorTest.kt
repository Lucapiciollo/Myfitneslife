package com.myfitai.app.domain.calculation

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.ai.AiUserContext
import com.myfitai.app.domain.food.CalorieRecoveryEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** K2: minimo kcal unico (1) e scelta del grasso per Katch-McArdle (2). */
class SafetyCalorieFloorTest {

    private val female = LocalCalculationEngine.BiologicalSex.FEMALE
    private val male = LocalCalculationEngine.BiologicalSex.MALE

    private fun calc(
        sex: LocalCalculationEngine.BiologicalSex,
        age: Int,
        height: Double,
        weight: Double,
        goal: LocalCalculationEngine.Goal = LocalCalculationEngine.Goal.WEIGHT_LOSS,
        activity: LocalCalculationEngine.ActivityLevel = LocalCalculationEngine.ActivityLevel.SEDENTARY,
    ) = LocalCalculationEngine.calculate(
        LocalCalculationEngine.Input(
            weightKg = weight,
            heightCm = height,
            ageYears = age,
            biologicalSex = sex,
            activityLevel = activity,
            goal = goal,
        )
    )

    // ---- (1) minimo unico -------------------------------------------------------------------

    @Test
    fun floor_isMaxOfBmrAndAbsoluteMinimum() {
        assertEquals(1334.0, SafetyCalorieFloor.minimumKcal(1334.0, female), 0.001)
        assertEquals(1200.0, SafetyCalorieFloor.minimumKcal(1139.0, female), 0.001)
        assertEquals(1500.0, SafetyCalorieFloor.minimumKcal(1367.5, male), 0.001)
        assertEquals(1500.0, SafetyCalorieFloor.minimumKcal(null, null), 0.001)
        assertEquals(1825.0, SafetyCalorieFloor.minimumKcal(1825.0, male), 0.001)
    }

    @Test
    fun womanSedentary_164cm_78kg_targetAboveBmr_noClamp() {
        val r = calc(female, 62, 164.0, 78.0)
        assertEquals(1334.0, r.bmrKcal!!, 0.001)
        assertEquals(1360.68, r.targetKcal!!, 0.01)
        assertEquals(1334.0, r.minimumKcal!!, 0.001)
        assertFalse(r.floorApplied)
    }

    @Test
    fun smallWoman_50kg_160cm_targetClampedTo1200_andFlagged() {
        val r = calc(female, 40, 160.0, 50.0)
        assertEquals(1139.0, r.bmrKcal!!, 0.001)
        assertEquals(1161.78, r.unclampedTargetKcal!!, 0.01)
        assertEquals(1200.0, r.targetKcal!!, 0.001)
        assertTrue(r.floorApplied)
        // K6: I macro derivano dal target gia clampato (1200 kcal): proteine = max(min(100; 90); 60) = 90, grassi =
        // max(30; 26,7) = 30, carbo = (1200 - 360 - 270) / 4 = 142,5 (prima 110).
        assertEquals(142.5, r.carbsG!!, 0.01)
    }

    @Test
    fun man_60y_170cm_60kg_targetClampedTo1500() {
        val r = calc(male, 60, 170.0, 60.0)
        assertEquals(1367.5, r.bmrKcal!!, 0.001)
        assertEquals(1394.85, r.unclampedTargetKcal!!, 0.01)
        assertEquals(1500.0, r.targetKcal!!, 0.001)
        assertTrue(r.floorApplied)
    }

    @Test
    fun maintenance_isNeverRaisedByTheFloor() {
        val r = calc(female, 70, 160.0, 50.0, goal = LocalCalculationEngine.Goal.MAINTENANCE)
        assertEquals(1186.8, r.targetKcal!!, 0.01)
        assertFalse(r.floorApplied)
    }

    @Test
    fun adaptive_sustainedStall_neverGoesBelowFloor() {
        fun evidence(delta: Double) = AdaptiveNutritionTargetEngine.Evidence(count = 3, spanDays = 28, delta = delta)
        fun input(minimum: Double?) = AdaptiveNutritionTargetEngine.Input(
            goal = LocalCalculationEngine.Goal.WEIGHT_LOSS,
            tdeeKcal = 1600.8,
            baseTargetKcal = 1360.68,
            currentWeightKg = 78.0,
            weight = evidence(0.0),
            bodyFat = evidence(0.0),
            muscleMass = evidence(0.0),
            waist = evidence(0.0),
            abdomen = evidence(0.0),
            minimumKcal = minimum,
        )

        // Senza minimo: 0,825 x TDEE = 1320,7 kcal, sotto il BMR 1334 (il difetto dell'audit).
        val without = AdaptiveNutritionTargetEngine.adjust(input(null))
        assertEquals(AdaptiveNutritionTargetEngine.Decision.INCREASE_DEFICIT, without.decision)
        assertEquals(1320.66, without.targetKcal!!, 0.01)
        assertFalse(without.floorApplied)

        val with = AdaptiveNutritionTargetEngine.adjust(input(1334.0))
        assertEquals(1334.0, with.targetKcal!!, 0.001)
        assertTrue(with.floorApplied)
    }

    @Test
    fun recovery_womanSedentary_neverBelowBmr() {
        val monday = LocalDate.of(2026, 9, 14)
        val base = NutritionBusinessValidator.Targets(1360.68, 156.0, 43.8, 62.4)
        val credits = listOf(CalorieRecoveryEngine.Credit(1, monday.minusDays(1), 1000))

        val result = CalorieRecoveryEngine.plan(
            monday, monday, base, 78.0, LocalCalculationEngine.Goal.WEIGHT_LOSS, credits,
            minimumKcal = SafetyCalorieFloor.minimumKcal(1334.0, female),
        )
        // Prima: 1360,68 x 0,9 = 1224,6 < BMR. Ora ogni giorno resta >= 1334.
        assertTrue(result.days.all { it.targets.kcal >= 1334.0 })
        assertTrue(result.plannedRecoveryKcal <= 7 * 26)
    }

    @Test
    fun recovery_withoutExplicitMinimum_usesPrudentDefault_notNoFloor() {
        val monday = LocalDate.of(2026, 9, 14)
        val base = NutritionBusinessValidator.Targets(1360.68, 156.0, 43.8, 62.4)
        val result = CalorieRecoveryEngine.plan(
            monday, monday, base, 78.0, LocalCalculationEngine.Goal.WEIGHT_LOSS,
            listOf(CalorieRecoveryEngine.Credit(1, monday.minusDays(1), 1000)),
        )
        assertEquals(0, result.plannedRecoveryKcal)
        assertTrue(result.days.all { it.targets.kcal == base.kcal })
    }

    @Test
    fun aiSafetyFlag_usesSameConstantsAsTheFloor() {
        fun flag(kcal: Double, sex: String) = AiUserContext.safetyFlag(
            40, 22.0, LocalCalculationEngine.Goal.WEIGHT_LOSS, emptyList(), kcal, sex,
        ).reasons.contains("EXTREME_LOW_TARGET")
        assertTrue(flag(SafetyCalorieFloor.MIN_KCAL_FEMALE - 0.1, "Femmina"))
        assertFalse(flag(SafetyCalorieFloor.MIN_KCAL_FEMALE, "Femmina"))
        assertTrue(flag(SafetyCalorieFloor.MIN_KCAL_OTHER - 0.1, "Maschio"))
        assertFalse(flag(SafetyCalorieFloor.MIN_KCAL_OTHER, "Maschio"))
    }

    // ---- (2) Katch: grasso piu recente disponibile, con limite di eta, stessa rilevazione -------

    private val rome = ZoneId.of("Europe/Rome")

    private fun bia(day: LocalDate, hour: Int = 12, weight: Float?, fat: Float?) = BiaMeasurementEntity(
        profileId = 1,
        measuredAtEpochMillis = day.atTime(hour, 0).atZone(rome).toInstant().toEpochMilli(),
        weightKg = weight,
        bodyFatPercent = fat,
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

    @Test
    fun katchSelector_skipsWeightOnlyLatestRow_andUsesWeightOfSameRow() {
        val today = LocalDate.of(2026, 11, 12)
        val history = listOf(
            bia(today.minusDays(60), weight = 80f, fat = 30f),
            bia(today.minusDays(3), weight = 78f, fat = null),
        )
        val s = KatchBodyFatSelector.select(history, today, rome)
        assertEquals(30f, s.bodyFatPercent!!, 0.001f)
        assertEquals(80f, s.referenceWeightKg!!, 0.001f)
        assertEquals(60L, s.ageDays)
        assertNull(s.fallbackReason)
    }

    @Test
    fun katchSelector_tooOldOrMissing_fallsBackAndDeclaresWhy() {
        val today = LocalDate.of(2026, 11, 12)
        val old = KatchBodyFatSelector.select(listOf(bia(today.minusDays(181), weight = 80f, fat = 30f)), today, rome)
        assertNull(old.bodyFatPercent)
        assertEquals(KatchBodyFatSelector.REASON_TOO_OLD, old.fallbackReason)
        assertEquals(181L, old.ageDays)

        val none = KatchBodyFatSelector.select(listOf(bia(today, weight = 78f, fat = null)), today, rome)
        assertEquals(KatchBodyFatSelector.REASON_MISSING, none.fallbackReason)
        assertNull(none.measuredAtEpochMillis)
    }

    @Test
    fun katchSelector_ageIsCalendarDays_acrossDstChange() {
        // 29 aprile 23:30 -> 26 ottobre: fine DST il 25 ottobre. 180 giorni di calendario: ancora valido.
        val today = LocalDate.of(2026, 10, 26)
        val edge = KatchBodyFatSelector.select(listOf(bia(LocalDate.of(2026, 4, 29), hour = 23, weight = 80f, fat = 30f)), today, rome)
        assertEquals(180L, edge.ageDays)
        assertNull(edge.fallbackReason)
        val over = KatchBodyFatSelector.select(listOf(bia(LocalDate.of(2026, 4, 28), hour = 23, weight = 80f, fat = 30f)), today, rome)
        assertEquals(KatchBodyFatSelector.REASON_TOO_OLD, over.fallbackReason)
    }

    @Test
    fun katch_leanMassUsesWeightOfTheSameReading_notTheNewWeight() {
        fun input(referenceWeight: Double?) = LocalCalculationEngine.Input(
            weightKg = 78.0,
            heightCm = 164.0,
            ageYears = 62,
            biologicalSex = female,
            bodyFatPercent = 30.0,
            bodyFatReferenceWeightKg = referenceWeight,
            activityLevel = LocalCalculationEngine.ActivityLevel.SEDENTARY,
            goal = LocalCalculationEngine.Goal.MAINTENANCE,
        )
        // Stessa rilevazione: 80 kg al 30% = 56 kg magri.
        assertEquals(370.0 + 21.6 * 56.0, LocalCalculationEngine.calculate(input(80.0)).bmrKcal!!, 0.01)
        // Senza peso di riferimento resta il comportamento precedente (peso corrente).
        assertEquals(370.0 + 21.6 * 54.6, LocalCalculationEngine.calculate(input(null)).bmrKcal!!, 0.01)
    }

    @Test
    fun mifflinFallback_isDeclaredInTheResult() {
        val stale = LocalCalculationEngine.calculate(
            LocalCalculationEngine.Input(
                weightKg = 78.0,
                heightCm = 164.0,
                ageYears = 62,
                biologicalSex = female,
                bodyFatPercent = null,
                bodyFatMeasuredAtEpochMillis = 123L,
                bodyFatFallbackReason = KatchBodyFatSelector.REASON_TOO_OLD,
                activityLevel = LocalCalculationEngine.ActivityLevel.MODERATE,
                goal = LocalCalculationEngine.Goal.WEIGHT_LOSS,
            )
        )
        assertEquals("MIFFLIN_ST_JEOR", stale.bmrMethod)
        assertEquals(KatchBodyFatSelector.REASON_TOO_OLD, stale.bmrFallbackReason)
        assertEquals(123L, stale.bmrBodyFatMeasuredAtEpochMillis)
    }
}
