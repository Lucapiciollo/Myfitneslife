package com.myfitai.app.data.profile

import android.content.Context

class MealCountPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(profileId: Long): Int = prefs.getInt(key(profileId), DEFAULT).takeIf { it in ALLOWED } ?: DEFAULT

    fun set(profileId: Long, count: Int) {
        require(count in ALLOWED) { "MEAL_COUNT_NOT_SUPPORTED" }
        prefs.edit().putInt(key(profileId), count).apply()
    }

    private fun key(profileId: Long) = "meals_per_day_$profileId"

    companion object {
        const val DEFAULT = 5
        val ALLOWED = setOf(4, 5, 6)
        private const val PREFS_NAME = "meal_preferences"
    }
}
