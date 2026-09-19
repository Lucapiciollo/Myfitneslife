package com.myfitai.app.domain.review

import com.myfitai.app.data.local.entity.FoodConsumptionEntity
import com.myfitai.app.domain.food.FoodConsumptionItemType
import com.myfitai.app.domain.food.FoodConsumptionStatus

object WeeklyConsumptionMetrics {
    data class Result(
        val plannedMealCount: Int,
        val recordedItemCount: Int,
        val consumedMealCount: Int,
        val skippedMealCount: Int,
        val trackingCoveragePercent: Int?,
        val adherencePercent: Int?,
        val consumedKcal: Int?,
    )

    fun calculate(
        plannedMealCount: Int,
        plannedItemCount: Int,
        records: List<FoodConsumptionEntity>,
    ): Result {
        val consumedMeals = records.count { isMeal(it) && it.status == FoodConsumptionStatus.CONSUMED.name }
        val skippedMeals = records.count { isMeal(it) && it.status == FoodConsumptionStatus.SKIPPED.name }
        val consumedKcal = records
            .filter { it.status == FoodConsumptionStatus.CONSUMED.name }
            .sumOf { it.kcal ?: 0 }
            .takeIf { records.isNotEmpty() }
        return Result(
            plannedMealCount = plannedMealCount,
            recordedItemCount = records.size,
            consumedMealCount = consumedMeals,
            skippedMealCount = skippedMeals,
            trackingCoveragePercent = (records.size * 100 / plannedItemCount).takeIf { plannedItemCount > 0 && records.isNotEmpty() },
            adherencePercent = (consumedMeals * 100 / plannedMealCount).takeIf { plannedMealCount > 0 && records.any(::isMeal) },
            consumedKcal = consumedKcal,
        )
    }

    private fun isMeal(record: FoodConsumptionEntity): Boolean = record.itemType == FoodConsumptionItemType.MEAL.name
}
