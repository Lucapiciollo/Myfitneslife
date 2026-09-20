package com.myfitai.app.data.profile

import android.content.Context

class WorkoutPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(profileId: Long): Boolean =
        prefs.getBoolean(key(profileId), DEFAULT_ENABLED)

    fun setEnabled(profileId: Long, enabled: Boolean) {
        prefs.edit().putBoolean(key(profileId), enabled).apply()
    }

    private fun key(profileId: Long) = "workouts_enabled_$profileId"

    companion object {
        private const val PREFS_NAME = "workout_preferences"
        const val DEFAULT_ENABLED = true
    }
}
