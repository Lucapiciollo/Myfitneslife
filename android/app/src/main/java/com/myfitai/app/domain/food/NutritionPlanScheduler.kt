package com.myfitai.app.domain.food

import androidx.work.workDataOf
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.domain.ai.AiJobScheduler
import com.myfitai.app.domain.ai.AiJobType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Schedules the next automatic weekly-plan generation from the profile configuration.
 * Uses one delayed WorkManager job at a time so day/time changes can be applied immediately.
 */
class NutritionPlanScheduler(
    private val preferences: NutritionPlanSchedulePreferences,
    private val aiJobScheduler: AiJobScheduler,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun reschedule(profileId: Long, nowEpochMillis: Long = System.currentTimeMillis()) {
        // A due job can be waiting for network or already executing. Do not cancel it
        // just because the app was opened or the active profile was refreshed.
        val previous = preferences.scheduledJobKey(profileId)
        if (preferences.get(profileId).enabled && isDueJob(previous, nowEpochMillis)) return
        cancel(profileId)
        scheduleNext(profileId, nowEpochMillis)
    }

    fun scheduleNextAfterAutomaticRun(profileId: Long, nowEpochMillis: Long = System.currentTimeMillis()) {
        preferences.setScheduledJobKey(profileId, null)
        scheduleNext(profileId, nowEpochMillis)
    }

    fun cancel(profileId: Long) {
        preferences.scheduledJobKey(profileId)?.let { key ->
            aiJobScheduler.cancel(AiJobType.WEEKLY_PLAN, profileId, key)
        }
        preferences.setScheduledJobKey(profileId, null)
    }

    private fun scheduleNext(profileId: Long, nowEpochMillis: Long) {
        val config = preferences.get(profileId)
        if (!config.enabled) return

        val now = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId)
        val due = nextOccurrence(now, config.dayOfWeek, config.timeMinutes, zoneId)
        val targetWeek = due.toLocalDate()
            .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val dueEpochMillis = due.toInstant().toEpochMilli()
        val jobKey = "auto-$dueEpochMillis"
        val delay = (dueEpochMillis - nowEpochMillis).coerceAtLeast(0L)

        aiJobScheduler.enqueue(
            type = AiJobType.WEEKLY_PLAN,
            profileId = profileId,
            jobKey = jobKey,
            initialDelayMillis = delay,
            params = workDataOf(
                WeeklyPlanAiJobHandler.KEY_WEEK_START_EPOCH_DAY to targetWeek.toEpochDay(),
                WeeklyPlanAiJobHandler.KEY_AUTOMATIC to true,
            ),
        )
        preferences.setScheduledJobKey(profileId, jobKey)
    }

    companion object {
        internal fun isDueJob(jobKey: String?, nowEpochMillis: Long): Boolean =
            jobKey?.takeIf { it.startsWith("auto-") }
                ?.removePrefix("auto-")
                ?.toLongOrNull()?.let { it <= nowEpochMillis } == true

        internal fun nextOccurrence(
            now: ZonedDateTime,
            dayOfWeek: DayOfWeek,
            timeMinutes: Int,
            zoneId: ZoneId,
        ): ZonedDateTime {
            val hour = timeMinutes / 60
            val minute = timeMinutes % 60
            var candidate = now.toLocalDate()
                .with(TemporalAdjusters.nextOrSame(dayOfWeek))
                .atTime(hour, minute)
                .atZone(zoneId)
            if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1)
            return candidate
        }
    }
}
