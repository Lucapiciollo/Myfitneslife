package com.myfitai.app.data.profile

import android.content.Context
import java.time.DayOfWeek

class NutritionPlanSchedulePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Config(
        val enabled: Boolean,
        val dayOfWeek: DayOfWeek,
        val timeMinutes: Int,
    )

    fun get(profileId: Long): Config = Config(
        enabled = prefs.getBoolean(keyEnabled(profileId), false),
        dayOfWeek = DayOfWeek.of(prefs.getInt(keyDay(profileId), DEFAULT_DAY.value).coerceIn(1, 7)),
        timeMinutes = prefs.getInt(keyTime(profileId), DEFAULT_TIME_MINUTES).coerceIn(0, 1439),
    )

    fun setEnabled(profileId: Long, enabled: Boolean) {
        prefs.edit().putBoolean(keyEnabled(profileId), enabled).apply()
    }

    fun setDayOfWeek(profileId: Long, dayOfWeek: DayOfWeek) {
        prefs.edit().putInt(keyDay(profileId), dayOfWeek.value).apply()
    }

    fun setTimeMinutes(profileId: Long, timeMinutes: Int) {
        require(timeMinutes in 0..1439) { "INVALID_TIME_MINUTES" }
        prefs.edit().putInt(keyTime(profileId), timeMinutes).apply()
    }

    fun scheduledJobKey(profileId: Long): String? =
        prefs.getString(keyScheduledJob(profileId), null)?.takeIf { it.isNotBlank() }

    fun setScheduledJobKey(profileId: Long, jobKey: String?) {
        prefs.edit().apply {
            if (jobKey == null) remove(keyScheduledJob(profileId)) else putString(keyScheduledJob(profileId), jobKey)
        }.apply()
    }

    private fun keyEnabled(profileId: Long) = "auto_plan_enabled_$profileId"
    private fun keyDay(profileId: Long) = "auto_plan_day_$profileId"
    private fun keyTime(profileId: Long) = "auto_plan_time_$profileId"
    private fun keyScheduledJob(profileId: Long) = "auto_plan_job_$profileId"

    companion object {
        private const val PREFS_NAME = "nutrition_plan_schedule"
        val DEFAULT_DAY: DayOfWeek = DayOfWeek.SUNDAY
        const val DEFAULT_TIME_MINUTES = 18 * 60
    }
}
