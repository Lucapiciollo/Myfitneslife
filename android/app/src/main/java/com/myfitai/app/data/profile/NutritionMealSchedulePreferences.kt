package com.myfitai.app.data.profile

import android.content.Context

/** Per-profile meal times used by reminders and by the automatic nutrition configuration. */
class NutritionMealSchedulePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun times(profileId: Long, mealsPerDay: Int): List<Int> {
        val defaults = defaultTimes(mealsPerDay)
        val stored = prefs.getString(key(profileId), null)
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull()?.takeIf { value -> value in 0..1439 } }
            .orEmpty()
        return if (stored.size == mealsPerDay) stored else defaults
    }

    fun setTime(profileId: Long, mealIndex: Int, minutes: Int, mealsPerDay: Int) {
        require(mealIndex in 0 until mealsPerDay && minutes in 0..1439)
        val values = times(profileId, mealsPerDay).toMutableList()
        values[mealIndex] = minutes
        prefs.edit().putString(key(profileId), values.joinToString(",")).apply()
    }

    fun setTimes(profileId: Long, values: List<Int>) {
        require(values.isNotEmpty() && values.all { it in 0..1439 })
        prefs.edit().putString(key(profileId), values.joinToString(",")).apply()
    }

    companion object {
        const val DEFAULT_FIVE_MEAL_TIMES = "08:00,11:00,13:00,16:00,20:00"
        const val DEFAULT_SIX_MEAL_TIMES = "08:00,11:00,13:00,16:00,18:00,20:00"
        private const val PREFS_NAME = "nutrition_meal_schedule"

        fun defaultTimes(mealsPerDay: Int): List<Int> = when (mealsPerDay) {
            4 -> listOf(8 * 60, 13 * 60, 16 * 60, 20 * 60)
            5 -> listOf(8 * 60, 11 * 60, 13 * 60, 16 * 60, 20 * 60)
            6 -> listOf(8 * 60, 11 * 60, 13 * 60, 16 * 60, 18 * 60, 20 * 60)
            else -> error("MEAL_COUNT_NOT_SUPPORTED")
        }

        fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
        fun parse(hour: Int, minute: Int): Int = hour * 60 + minute
    }

    private fun key(profileId: Long): String = "times_$profileId"
}
