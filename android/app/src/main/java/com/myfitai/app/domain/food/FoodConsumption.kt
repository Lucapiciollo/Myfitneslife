package com.myfitai.app.domain.food

enum class FoodConsumptionStatus {
    CONSUMED,
    SKIPPED,
}

enum class FoodConsumptionItemType {
    MEAL,
    SUPPLEMENT,
}

object FoodConsumptionKeys {
    fun meal(mealId: Long): String = "MEAL:$mealId"

    fun supplement(key: String): String = "SUPPLEMENT:$key"
}
