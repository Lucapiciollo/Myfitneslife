package com.myfitai.app.domain.display

/** UX5a: the quick "mark as consumed" control on the Home meal card is only for today's (or past) meals. */
object QuickConsumeRule {
    enum class State {
        /** Future day: the control is not shown (the detail stays reachable by tapping the card). */
        HIDDEN,
        AVAILABLE,
        /** Already consumed: the control reads as done and a tap offers the undo, never a silent clear. */
        DONE,
    }

    fun stateOf(mealDateEpochDay: Long, todayEpochDay: Long, alreadyConsumed: Boolean): State = when {
        mealDateEpochDay > todayEpochDay -> State.HIDDEN
        alreadyConsumed -> State.DONE
        else -> State.AVAILABLE
    }
}

/**
 * UX5a: while the plan update is pending the weekly estimate compares the NEW consumption (TDEE) with the kcal of the
 * OLD plan. Same pending flag as [TargetModeLine.isStale]; with no planned day there is no old plan in the estimate.
 */
object WeeklyEstimateNote {
    fun shouldShow(planUpdatePending: Boolean, plannedDays: Int): Boolean = planUpdatePending && plannedDays > 0
}

/** UX5a: the single note under the three rings. [kindOf] keeps the wording rules the two old notes already had. */
object ConsumptionNote {
    enum class Kind { ALL_SKIPPED, NONE_REGISTERED, PARTIAL, ALL_CONSUMED }

    fun kindOf(consumedCount: Int, recordedCount: Int): Kind = when {
        consumedCount == 0 && recordedCount > 0 -> Kind.ALL_SKIPPED
        consumedCount == 0 -> Kind.NONE_REGISTERED
        consumedCount < recordedCount -> Kind.PARTIAL
        else -> Kind.ALL_CONSUMED
    }
}

/** Home, Dettagli del calcolo: il BMR da BIA si mostra solo se c'e e aggiunge informazione (se coincide col BMR locale e una riga doppia). */
object BiaBmrRowRule {
    fun visible(bmr: Int?, biaBmr: Int?): Boolean = biaBmr != null && biaBmr != bmr
}
