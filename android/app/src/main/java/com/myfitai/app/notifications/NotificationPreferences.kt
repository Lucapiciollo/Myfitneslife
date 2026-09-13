package com.myfitai.app.notifications

import android.content.Context

class NotificationPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var mealRemindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEALS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEALS_ENABLED, value).apply()

    var weeklyReviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_REVIEW_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REVIEW_ENABLED, value).apply()

    var mealLeadMinutes: Int
        get() = prefs.getInt(KEY_MEAL_LEAD, 15).coerceIn(0, 120)
        set(value) = prefs.edit().putInt(KEY_MEAL_LEAD, value.coerceIn(0, 120)).apply()

    fun scheduledRequestCodes(): Set<Int> =
        prefs.getStringSet(KEY_REQUEST_CODES, emptySet()).orEmpty().mapNotNull(String::toIntOrNull).toSet()

    fun replaceScheduledRequestCodes(values: Set<Int>) {
        prefs.edit().putStringSet(KEY_REQUEST_CODES, values.map(Int::toString).toSet()).apply()
    }

    companion object {
        private const val PREFS = "myfitai_notifications"
        private const val KEY_MEALS_ENABLED = "meal_reminders_enabled"
        private const val KEY_REVIEW_ENABLED = "weekly_review_enabled"
        private const val KEY_MEAL_LEAD = "meal_lead_minutes"
        private const val KEY_REQUEST_CODES = "scheduled_request_codes"
    }
}
