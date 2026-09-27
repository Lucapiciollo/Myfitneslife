package com.myfitai.app.domain.calculation

import kotlin.math.max

/**
 * Indicative energy-balance estimate from the CURRENT saved weekly plan.
 *
 * TDEE from LocalCalculationEngine already includes habitual activity: adding workout
 * calories here would count the same activity twice. This is not a measurement of fat
 * lost and must never be presented as an observed BIA change or a certain prediction.
 */
object WeeklyBodyExpectation {
    private const val KCAL_PER_KG_FAT = 7_700.0

    data class Result(
        val available: Boolean,
        val plannedDays: Int = 0,
        val isFullWeek: Boolean = false,
        val periodWeeks: Int = 1,
        val theoreticalDeficitKcal: Int? = null,
        val expectedFatLossKgMin: Double? = null,
        val expectedFatLossKgMax: Double? = null,
        val caution: String = "Servono un TDEE valido e almeno un giorno di dieta pianificato.",
    )

    /**
     * Only actual dated plan days in [weekStartEpochDay, weekStartEpochDay + 6] count.
     * Missing/null days are NOT replaced by a target and are NOT extrapolated to seven days.
     * A caloric surplus on one day offsets another day's deficit.
     */
    fun calculate(
        maintenanceKcal: Int?,
        weekStartEpochDay: Long,
        plannedDays: List<Pair<Long, Int?>>,
        periodWeeks: Int = 1,
    ): Result {
        require(periodWeeks in 1..4) { "INVALID_EXPECTATION_PERIOD_WEEKS" }
        if (maintenanceKcal == null || maintenanceKcal <= 0) return Result(available = false)
        val periodDays = periodWeeks * 7
        val days = plannedDays
            .filter { (day, kcal) ->
                day in weekStartEpochDay..(weekStartEpochDay + periodDays - 1) && kcal != null && kcal > 0
            }
            .distinctBy { it.first }
        if (days.isEmpty()) return Result(available = false)

        val deficit = days.sumOf { maintenanceKcal - requireNotNull(it.second) }
        val fullWeek = days.size == 7
        if (deficit <= 0) return Result(
            available = false,
            plannedDays = days.size,
            isFullWeek = fullWeek,
            periodWeeks = periodWeeks,
            theoreticalDeficitKcal = deficit,
            caution = "Il piano non presenta un deficit teorico complessivo nei giorni disponibili.",
        )

        // The illustrative band is NOT a statistical confidence interval or a claim
        // that this proportion of energy is necessarily taken from adipose tissue.
        val central = max(0, deficit) / KCAL_PER_KG_FAT
        return Result(
            available = true,
            plannedDays = days.size,
            isFullWeek = fullWeek,
            periodWeeks = periodWeeks,
            theoreticalDeficitKcal = deficit,
            expectedFatLossKgMin = central * 0.70,
            expectedFatLossKgMax = central * 1.20,
            caution = "Intervallo illustrativo del bilancio energetico, non previsione clinica: acqua, adattamenti e composizione del peso possono modificarne l'esito.",
        )
    }
}
