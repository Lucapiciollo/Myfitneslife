package com.myfitai.app.domain.food

import com.myfitai.app.domain.calculation.LocalCalculationEngine
import com.myfitai.app.domain.calculation.MacroPolicy
import com.myfitai.app.domain.calculation.NutritionBusinessValidator
import com.myfitai.app.domain.calculation.SafetyCalorieFloor
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Deterministic recovery planner for confirmed extra kcal.
 * It never lets the LLM decide the deficit and never reduces protein targets: protein and fat of every day are
 * those of its base target (decided once by [MacroPolicy]); only carbohydrates follow the recovered calories.
 * [plan] keeps the `weightKg`/`goal` parameters for compatibility; the macros no longer depend on them.
 */
object CalorieRecoveryEngine {
    const val WINDOW_DAYS = 7L
    const val MAX_DAILY_REDUCTION_RATIO = 0.10

    data class Credit(
        val sourceId: Long,
        val occurredOn: LocalDate,
        /** Still-unallocated kcal for this source. */
        val kcal: Int,
    )

    data class DayPlan(
        val date: LocalDate,
        val recoveredKcal: Int,
        val targets: NutritionBusinessValidator.Targets,
    )

    data class Result(
        val availableBeforeKcal: Int,
        val plannedRecoveryKcal: Int,
        val remainingKcal: Int,
        /** Exact amount allocated in this generated week for each source cheat. */
        val plannedBySource: Map<Long, Int>,
        val days: List<DayPlan>,
    )

    fun plan(
        monday: LocalDate,
        today: LocalDate,
        baseTargets: NutritionBusinessValidator.Targets,
        weightKg: Double,
        goal: LocalCalculationEngine.Goal,
        credits: List<Credit>,
        periodWeeks: Int = 1,
        /**
         * Safety floor: recovery never pushes a day below min(this value, the base target).
         * Default = soglia assoluta prudente ([SafetyCalorieFloor]); i chiamanti con BMR/sesso noti passano
         * [SafetyCalorieFloor.minimumKcal]. Non e piu possibile pianificare un recupero senza minimo.
         */
        minimumKcal: Double = SafetyCalorieFloor.MIN_KCAL_OTHER,
    ): Result {
        require(baseTargets.kcal > 0.0)
        require(weightKg > 0.0)
        require(minimumKcal.isFinite() && minimumKcal >= 0.0) { "MINIMUM_KCAL_INVALID" }

        val dates = NutritionPlanPeriod.dates(monday, periodWeeks)
        val validCredits = credits
            .filter { it.kcal > 0 }
            .sortedWith(compareBy<Credit> { it.occurredOn }.thenBy { it.sourceId })
        val availableBefore = validCredits.sumOf { it.kcal }
        // The cap is a share of the single base target and never goes below the safety floor.
        fun capFor(date: LocalDate): Int {
            val base = baseTargets.kcal
            val byRatio = (base * MAX_DAILY_REDUCTION_RATIO).roundToInt().coerceAtLeast(0)
            val byFloor = (base - minimumKcal).toInt().coerceAtLeast(0)
            return min(byRatio, byFloor)
        }
        val recoveryByDate = dates.associateWith { 0 }.toMutableMap()
        val plannedBySource = linkedMapOf<Long, Int>()

        for (credit in validCredits) {
            var remaining = credit.kcal
            val eligible = dates.filter { date ->
                !date.isBefore(today) &&
                    date.isAfter(credit.occurredOn) &&
                    !date.isAfter(credit.occurredOn.plusDays(WINDOW_DAYS))
            }
            if (eligible.isEmpty()) continue

            while (remaining > 0) {
                val withCapacity = eligible.filter { (recoveryByDate[it] ?: 0) < capFor(it) }
                if (withCapacity.isEmpty()) break
                val share = maxOf(1, (remaining.toDouble() / withCapacity.size).roundToInt())
                var progressed = false
                for (date in withCapacity) {
                    if (remaining <= 0) break
                    val current = recoveryByDate[date] ?: 0
                    val capacity = capFor(date) - current
                    if (capacity <= 0) continue
                    val amount = min(remaining, min(capacity, share))
                    if (amount > 0) {
                        recoveryByDate[date] = current + amount
                        plannedBySource[credit.sourceId] = (plannedBySource[credit.sourceId] ?: 0) + amount
                        remaining -= amount
                        progressed = true
                    }
                }
                if (!progressed) break
            }
        }

        val dayPlans = dates.map { date ->
            val recovery = recoveryByDate[date] ?: 0
            val base = baseTargets
            val effectiveKcal = (base.kcal - recovery)
                .coerceAtLeast(base.kcal * (1.0 - MAX_DAILY_REDUCTION_RATIO))
                .let { value -> maxOf(value, minOf(minimumKcal, base.kcal)) }
            val macros = MacroPolicy.targetsFor(effectiveKcal, MacroPolicy.Anchor(base.proteinG, base.fatG))
            DayPlan(
                date = date,
                recoveredKcal = recovery,
                targets = NutritionBusinessValidator.Targets(
                    kcal = effectiveKcal,
                    proteinG = macros.proteinG,
                    carbsG = macros.carbsG,
                    fatG = macros.fatG,
                ),
            )
        }

        val planned = plannedBySource.values.sum()
        return Result(
            availableBeforeKcal = availableBefore,
            plannedRecoveryKcal = planned,
            remainingKcal = (availableBefore - planned).coerceAtLeast(0),
            plannedBySource = plannedBySource.toMap(),
            days = dayPlans,
        )
    }
}
