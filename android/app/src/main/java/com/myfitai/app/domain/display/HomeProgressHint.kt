package com.myfitai.app.domain.display

/**
 * Which line the Home header shows under the date. The mock's encouragement is only truthful once something was
 * recorded today; before that the line points at the next useful action, and without a target it is hidden.
 */
object HomeProgressHint {
    enum class Kind { ENCOURAGEMENT, REGISTER_MEALS, HIDDEN }

    fun of(target: Int?, consumedCount: Int, consumedKcal: Int?): Kind = when {
        target == null || target <= 0 -> Kind.HIDDEN
        consumedCount > 0 || (consumedKcal ?: 0) > 0 -> Kind.ENCOURAGEMENT
        else -> Kind.REGISTER_MEALS
    }
}
