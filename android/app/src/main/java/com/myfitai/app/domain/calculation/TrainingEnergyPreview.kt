package com.myfitai.app.domain.calculation

import com.myfitai.app.domain.calculation.LocalCalculationEngine.ActivityLevel
import com.myfitai.app.domain.calculation.LocalCalculationEngine.Goal
import com.myfitai.app.domain.calculation.TrainingEnergyPlanner.ActivityBasis
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Informational summary shown before generating a plan: what the weekly program means in calories and how
 * it compares with the single habitual target used before. It never blocks the generation.
 */
object TrainingEnergyPreview {
    data class Preview(
        val program: TrainingProgram,
        val restKcal: Int?,
        val trainingKcal: Int?,
        val averageKcal: Int,
        val previousKcal: Int?,
        val storedLevel: ActivityLevel,
        val everydayLevel: ActivityLevel,
    ) {
        val levelWasLowered: Boolean get() = everydayLevel != storedLevel
    }

    /** @return null when there is no program or the profile data cannot support a number. */
    fun build(
        bmrKcal: Double?,
        weightKg: Double?,
        goal: Goal?,
        activityLevel: ActivityLevel?,
        basis: ActivityBasis,
        program: TrainingProgram,
        previousTargetKcal: Double?,
        reference: LocalDate = LocalDate.now(),
    ): Preview? {
        if (bmrKcal == null || weightKg == null || goal == null || activityLevel == null) return null
        val monday = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val days = TrainingEnergyPlanner.plan(
            dates = (0L until 7L).map { monday.plusDays(it) },
            inputs = TrainingEnergyPlanner.Inputs(bmrKcal, weightKg, goal, activityLevel, basis, program),
        ) ?: return null
        return Preview(
            program = program,
            restKcal = days.firstOrNull { !it.resolution.isTraining }?.baseTargets?.kcal?.roundToInt(),
            trainingKcal = days.firstOrNull { it.resolution.isTraining }?.baseTargets?.kcal?.roundToInt(),
            averageKcal = TrainingEnergyPlanner.averageBaseKcal(days)!!.roundToInt(),
            previousKcal = previousTargetKcal?.takeIf { it.isFinite() && it > 0.0 }?.roundToInt(),
            storedLevel = activityLevel,
            everydayLevel = TrainingEnergyPlanner.everydayLevel(activityLevel, basis),
        )
    }

    fun levelLabel(level: ActivityLevel): String = when (level) {
        ActivityLevel.SEDENTARY -> "Sedentario"
        ActivityLevel.LIGHT -> "Leggermente attivo"
        ActivityLevel.MODERATE -> "Moderatamente attivo"
        ActivityLevel.VERY_ACTIVE -> "Molto attivo"
        ActivityLevel.EXTREME -> "Estremamente attivo"
    }

    fun describe(preview: Preview): String = buildString {
        append("Allenamenti: ").append(preview.program.summary()).append('.')
        append("\nCalorie al giorno: ")
        val parts = buildList {
            preview.restKcal?.let { add("riposo ${format(it)}") }
            preview.trainingKcal?.let { add("allenamento ${format(it)}") }
            add("media ${format(preview.averageKcal)} kcal")
        }
        append(parts.joinToString(" · "))
        preview.previousKcal?.let { append(" (prima ${format(it)} kcal uguali tutti i giorni)") }
        append('.')
        if (preview.levelWasLowered) {
            append("\nIl livello di attività quotidiana è stato ridotto da «")
            append(levelLabel(preview.storedLevel)).append("» a «").append(levelLabel(preview.everydayLevel))
            append("» perché prima includeva gli allenamenti. Puoi cambiarlo dal profilo.")
        }
    }

    private fun format(value: Int): String = String.format(Locale.ITALIAN, "%,d", value)
}
