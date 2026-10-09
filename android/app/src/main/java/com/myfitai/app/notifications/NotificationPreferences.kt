package com.myfitai.app.notifications

import android.content.Context

class NotificationPreferences(context: Context) : NotificationSettings {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override var mealRemindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEALS_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEALS_ENABLED, value).apply()

    override var weeklyReviewEnabled: Boolean
        get() = prefs.getBoolean(KEY_REVIEW_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REVIEW_ENABLED, value).apply()

    var aiBackgroundUpdatesEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_BACKGROUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AI_BACKGROUND_ENABLED, value).apply()

    override var menuReminderHour: Int
        get() = prefs.getInt(KEY_MENU_REMINDER_HOUR, DEFAULT_MENU_REMINDER_HOUR).coerceIn(0, 23)
        set(value) = prefs.edit().putInt(KEY_MENU_REMINDER_HOUR, value.coerceIn(0, 23)).apply()

    /** The exact-alarm access is explained once; the notification settings keep offering it afterwards. */
    var exactAlarmPrompted: Boolean
        get() = prefs.getBoolean(KEY_EXACT_ALARM_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_EXACT_ALARM_PROMPTED, value).apply()

    var permissionPrompted: Boolean
        get() = prefs.getBoolean(KEY_PERMISSION_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_PERMISSION_PROMPTED, value).apply()

    override var menuPreviewMigrationDone: Boolean
        get() = prefs.getBoolean(KEY_MENU_PREVIEW_MIGRATION_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_MENU_PREVIEW_MIGRATION_DONE, value).apply()


    override fun scheduledRequestCodes(): Set<Int> =
        prefs.getStringSet(KEY_REQUEST_CODES, emptySet()).orEmpty().mapNotNull(String::toIntOrNull).toSet()

    override fun replaceScheduledRequestCodes(values: Set<Int>) {
        prefs.edit().putStringSet(KEY_REQUEST_CODES, values.map(Int::toString).toSet()).apply()
    }

    companion object {
        private const val PREFS = "myfitai_notifications"
        private const val KEY_MEALS_ENABLED = "meal_reminders_enabled"
        private const val KEY_REVIEW_ENABLED = "weekly_review_enabled"
        private const val KEY_AI_BACKGROUND_ENABLED = "ai_background_updates_enabled"
        private const val KEY_MENU_REMINDER_HOUR = "menu_reminder_hour"
        const val DEFAULT_MENU_REMINDER_HOUR = 20
        private const val KEY_PERMISSION_PROMPTED = "permission_prompted"
        private const val KEY_EXACT_ALARM_PROMPTED = "exact_alarm_prompted"
        private const val KEY_MENU_PREVIEW_MIGRATION_DONE = "menu_preview_migration_done"
        private const val KEY_REQUEST_CODES = "scheduled_request_codes"
    }
}
