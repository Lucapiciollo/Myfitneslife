package com.myfitai.app.domain.food

object FoodPlanMetrics {
    data class Totals(
        val kcal: Double?,
        val proteinG: Double?,
        val carbsG: Double?,
        val fatG: Double?,
    )

    fun dayTotals(day: FoodPlanDay): Totals = Totals(
        kcal = metricOrPersisted(day.totalKcal?.toDouble(), day.meals.map { it.kcal?.toDouble() }, day.supplements.map { it.kcal.toDouble() }),
        proteinG = metricOrPersisted(day.proteinG?.toDouble(), day.meals.map { it.proteinG?.toDouble() }, day.supplements.map { it.proteinG.toDouble() }),
        carbsG = metricOrPersisted(day.carbsG?.toDouble(), day.meals.map { it.carbsG?.toDouble() }, day.supplements.map { it.carbsG.toDouble() }),
        fatG = metricOrPersisted(day.fatG?.toDouble(), day.meals.map { it.fatG?.toDouble() }, day.supplements.map { it.fatG.toDouble() }),
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

    private fun metricOrPersisted(persisted: Double?, meals: List<Double?>, supplements: List<Double>): Double? {
        val calculated = sumNullable(meals)
        return if (calculated != null || supplements.isNotEmpty()) {
            (calculated ?: 0.0) + supplements.sum()
        } else {
            persisted
        }
    }

    private fun averageNullable(values: List<Double?>): Double? {
        val present = values.filterNotNull()
        return present.takeIf { it.isNotEmpty() }?.average()
    }
}
