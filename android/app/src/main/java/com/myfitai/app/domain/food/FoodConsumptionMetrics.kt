package com.myfitai.app.domain.food

import com.myfitai.app.data.local.entity.FoodConsumptionEntity

object FoodConsumptionMetrics {
    data class Totals(
        val kcal: Double,
        val proteinG: Double,
        val carbsG: Double,
        val fatG: Double,
        val consumedCount: Int,
        val skippedCount: Int,
        val recordedCount: Int,
    )

    fun dayTotals(records: List<FoodConsumptionEntity>): Totals {
        val consumed = records.filter { it.status == FoodConsumptionStatus.CONSUMED.name }
        val skipped = records.count { it.status == FoodConsumptionStatus.SKIPPED.name }
        return Totals(
            kcal = consumed.sumOf { it.kcal?.toDouble() ?: 0.0 },
            proteinG = consumed.sumOf { it.proteinG?.toDouble() ?: 0.0 },
            carbsG = consumed.sumOf { it.carbsG?.toDouble() ?: 0.0 },
            fatG = consumed.sumOf { it.fatG?.toDouble() ?: 0.0 },
            consumedCount = consumed.size,
            skippedCount = skipped,
            recordedCount = records.size,
        )
    }
}
