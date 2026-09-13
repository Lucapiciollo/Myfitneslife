package com.myfitai.app.domain.food

object FoodPlanMetrics {
    data class Totals(
        val kcal: Double?,
        val proteinG: Double?,
        val carbsG: Double?,
        val fatG: Double?,
    )

    fun dayTotals(day: FoodPlanDay): Totals = Totals(
        kcal = day.totalKcal?.toDouble() ?: sumNullable(day.meals.map { it.kcal?.toDouble() }),
        proteinG = day.proteinG?.toDouble() ?: sumNullable(day.meals.map { it.proteinG?.toDouble() }),
        carbsG = day.carbsG?.toDouble() ?: sumNullable(day.meals.map { it.carbsG?.toDouble() }),
        fatG = day.fatG?.toDouble() ?: sumNullable(day.meals.map { it.fatG?.toDouble() }),
    )

    /** Media solo sui giorni che contengono il dato; non trasforma un giorno mancante in zero. */
    fun weeklyAverage(days: List<FoodPlanDay>): Totals {
        val totals = days.map(::dayTotals)
        return Totals(
            kcal = averageNullable(totals.map { it.kcal }),
            proteinG = averageNullable(totals.map { it.proteinG }),
            carbsG = averageNullable(totals.map { it.carbsG }),
            fatG = averageNullable(totals.map { it.fatG }),
        )
    }

    private fun sumNullable(values: List<Double?>): Double? {
        val present = values.filterNotNull()
        return present.takeIf { it.isNotEmpty() }?.sum()
    }

    private fun averageNullable(values: List<Double?>): Double? {
        val present = values.filterNotNull()
        return present.takeIf { it.isNotEmpty() }?.average()
    }
}
