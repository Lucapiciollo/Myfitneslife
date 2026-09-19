package com.myfitai.app.domain.food

/** Keeps the paid/remote qualitative review limited to meaningful full-plan changes. */
object PlanReviewPolicy {
    enum class Reason {
        NEW_WEEKLY_PLAN,
        FULL_REGENERATION,
        SIGNIFICANT_TARGET_CHANGE,
        MEAL_SWAP,
        DEVIATION_ADJUSTMENT,
        TIME_ONLY_CHANGE,
    }

    fun shouldReview(reason: Reason): Boolean = when (reason) {
        Reason.NEW_WEEKLY_PLAN,
        Reason.FULL_REGENERATION,
        Reason.SIGNIFICANT_TARGET_CHANGE -> true
        Reason.MEAL_SWAP,
        Reason.DEVIATION_ADJUSTMENT,
        Reason.TIME_ONLY_CHANGE -> false
    }
}
