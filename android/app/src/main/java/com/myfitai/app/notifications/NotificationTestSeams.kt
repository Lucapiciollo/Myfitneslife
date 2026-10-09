package com.myfitai.app.notifications

data class MealReminderSpec(
    val requestCode: Int,
    val triggerAtEpochMillis: Long,
    val menuDateEpochDay: Long,
    val mealTitles: List<String>,
    val profileId: Long,
)
data class WeeklyReviewReminderSpec(val requestCode: Int, val triggerAtEpochMillis: Long)
data class SnoozeReminderSpec(val requestCode: Int, val triggerAtEpochMillis: Long, val mealId: Long, val mealType: String, val mealTitle: String, val profileId: Long)

interface NotificationAlarmGateway {
    fun scheduleMeal(spec: MealReminderSpec)
    fun scheduleWeeklyReview(spec: WeeklyReviewReminderSpec)
    fun scheduleSnooze(spec: SnoozeReminderSpec)
    fun cancelLegacyMeal(requestCode: Int)
    fun cancel(requestCode: Int)
}

interface NotificationSettings {
    var mealRemindersEnabled: Boolean
    var weeklyReviewEnabled: Boolean
    /** Local evening hour when the preview of the next planned menu is sent. */
    var menuReminderHour: Int
    var menuPreviewMigrationDone: Boolean
    fun scheduledRequestCodes(): Set<Int>
    fun replaceScheduledRequestCodes(values: Set<Int>)
}
