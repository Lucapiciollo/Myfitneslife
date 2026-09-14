package com.myfitai.app.notifications

data class MealReminderSpec(val requestCode: Int, val triggerAtEpochMillis: Long, val mealId: Long, val mealType: String, val mealTitle: String, val profileId: Long)
data class WeeklyReviewReminderSpec(val requestCode: Int, val triggerAtEpochMillis: Long)
data class SnoozeReminderSpec(val requestCode: Int, val triggerAtEpochMillis: Long, val mealId: Long, val mealType: String, val mealTitle: String, val profileId: Long)

interface NotificationAlarmGateway {
    fun scheduleMeal(spec: MealReminderSpec)
    fun scheduleWeeklyReview(spec: WeeklyReviewReminderSpec)
    fun scheduleSnooze(spec: SnoozeReminderSpec)
    fun cancel(requestCode: Int)
}

interface NotificationSettings {
    var mealRemindersEnabled: Boolean
    var weeklyReviewEnabled: Boolean
    var mealLeadMinutes: Int
    fun scheduledRequestCodes(): Set<Int>
    fun replaceScheduledRequestCodes(values: Set<Int>)
}
