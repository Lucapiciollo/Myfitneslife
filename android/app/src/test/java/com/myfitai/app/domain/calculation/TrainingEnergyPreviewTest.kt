package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.LocalCalculationEngine.ActivityLevel
import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import com.myfitai.app.domain.calculation.TrainingEnergyPlanner.ActivityBasis
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainingEnergyPreviewTest {
    private val threeDays = TrainingProgram(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
    private val reference = LocalDate.of(2026, 10, 7)

    private fun preview(
        basis: ActivityBasis = ActivityBasis.LEGACY_INCLUDES_TRAINING,
        program: TrainingProgram = threeDays,
        previous: Double? = 2527.0,
    ) = TrainingEnergyPreview.build(1918.0, 89.5, Goal.WEIGHT_LOSS, ActivityLevel.MODERATE, basis, program, previous, reference)

    @Test
    fun legacyProfileShowsRestTrainingAverageAndThePreviousSingleTarget() {
        val result = preview()!!

        assertEquals(2242, result.restKcal)
        assertEquals(2546, result.trainingKcal)
        assertEquals(2372, result.averageKcal)
        assertEquals(2527, result.previousKcal)
        assertTrue(result.levelWasLowered)
        assertEquals(ActivityLevel.LIGHT, result.everydayLevel)
    }

    @Test
    fun descriptionIsPlainItalianAndExplainsTheLoweredLevel() {
        val text = TrainingEnergyPreview.describe(preview()!!)

        assertTrue(text, text.contains("Allenamenti: Lun, Mer, Ven · 60 min · moderata."))
        assertTrue(text, text.contains("riposo 2.242 · allenamento 2.546 · media 2.372 kcal"))
        assertTrue(text, text.contains("prima 2.527 kcal uguali tutti i giorni"))
        assertTrue(text, text.contains("da «Moderatamente attivo» a «Leggermente attivo»"))
    }

    @Test
    fun confirmedProfilesDoNotMentionALoweredLevel() {
        val result = preview(basis = ActivityBasis.EVERYDAY_ONLY)!!

        assertFalse(result.levelWasLowered)
        assertEquals(2527, result.restKcal)
        assertFalse(TrainingEnergyPreview.describe(result).contains("ridotto"))
    }

    @Test
    fun aProgramWithoutRestDaysOmitsTheRestFigure() {
        val everyDay = TrainingProgram(DayOfWeek.entries.toSet())
        val result = preview(program = everyDay)!!

        assertNull(result.restKcal)
        assertFalse(TrainingEnergyPreview.describe(result).contains("riposo"))
    }

    @Test
    fun withoutAProgramOrDataThereIsNothingToShow() {
        assertNull(preview(program = TrainingProgram()))
        assertNull(TrainingEnergyPreview.build(null, 89.5, Goal.WEIGHT_LOSS, ActivityLevel.MODERATE, ActivityBasis.EVERYDAY_ONLY, threeDays, null, reference))
        assertNull(TrainingEnergyPreview.build(1918.0, 89.5, null, ActivityLevel.MODERATE, ActivityBasis.EVERYDAY_ONLY, threeDays, null, reference))
    }

    @Test
    fun missingPreviousTargetIsLeftOutOfTheText() {
        val text = TrainingEnergyPreview.describe(preview(previous = null)!!)

        assertFalse(text, text.contains("(prima"))
        assertFalse(text, text.contains("uguali tutti i giorni"))
    }
}
