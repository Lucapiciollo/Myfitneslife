package com.myfitai.app.notifications

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.repository.DayDraft
import com.myfitai.app.data.repository.IngredientDraft
import com.myfitai.app.data.repository.MealDraft
import com.myfitai.app.data.repository.MealPlanRepository
import com.myfitai.app.data.repository.PlanVersionDraft
import com.myfitai.app.domain.time.TimeProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class NotificationSchedulerTest {
    private lateinit var db: MyFitAiDatabase
    private lateinit var store: ActiveProfileStore
    private var profileId: Long = 0L
    private lateinit var plans: MealPlanRepository
    private lateinit var settings: FakeNotificationSettings
    private lateinit var alarms: FakeNotificationAlarmGateway
    private val today = LocalDate.of(2026, 9, 14)
    private val time = FixedNotificationTime(today.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli())

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MyFitAiDatabase::class.java)
            .allowMainThreadQueries().build()
        store = ActiveProfileStore(ApplicationProvider.getApplicationContext())
        store.clear()
        profileId = db.userProfileDao().insert(UserProfileEntity(
            name = "Notification fixture",
            birthDateEpochDay = null,
            heightCm = null,
            currentWeightKg = null,
            goal = null,
            activityLevel = null,
            wakeTimeMinutes = null,
            sleepTimeMinutes = null,
            dietaryPreferencesJson = null,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        ))
        store.selectProfile(profileId)
        plans = MealPlanRepository(db)
        settings = FakeNotificationSettings()
        alarms = FakeNotificationAlarmGateway()
    }

    @After
    fun tearDown() {
        db.close()
        store.clear()
    }

    @Test
    fun refresh_schedulesOnlyFutureMealsWithinSixtyDayHorizonWithLeadTime() = runBlocking {
        insertPlan(today, listOf(
            meal("Past", 600, 700),
            meal("Future", 1200, 1140),
        ))
        insertPlan(today.plusDays(61), listOf(meal("Outside horizon", 600, 1200)))
        settings.mealLeadMinutes = 15

        scheduler().refresh(time.nowEpochMillis())

        assertEquals(listOf("Future"), alarms.meals.map { it.mealTitle })
        assertEquals(today.atTime(19, 45).toInstant(ZoneOffset.UTC).toEpochMilli(), alarms.meals.single().triggerAtEpochMillis)
        assertEquals(alarms.meals.map { it.requestCode }.toSet(), settings.scheduledRequestCodes())
    }

    @Test
    fun refresh_cancelsPreviousCodesAndReschedulesAfterProfileSwitch() = runBlocking {
        insertPlan(today.plusDays(1), listOf(meal("Profile A meal", 600, 720)))
        scheduler().refresh(time.nowEpochMillis())
        val firstCodes = alarms.meals.map { it.requestCode }.toSet()

        val otherProfileId = db.userProfileDao().insert(UserProfileEntity(
            name = "Other", birthDateEpochDay = null, heightCm = null, currentWeightKg = null,
            goal = null, activityLevel = null, wakeTimeMinutes = null, sleepTimeMinutes = null,
            dietaryPreferencesJson = null, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L,
        ))
        store.selectProfile(otherProfileId)
        alarms.meals.clear()
        scheduler().refresh(time.nowEpochMillis())

        assertTrue(firstCodes.all { it in alarms.cancelledCodes })
        assertTrue(alarms.meals.isEmpty())
        assertTrue(settings.scheduledRequestCodes().isEmpty())
    }

    @Test
    fun refresh_weeklyReview_rollsToNextMondayAfterMondayNine() = runBlocking {
        settings.mealRemindersEnabled = false
        settings.weeklyReviewEnabled = true
        scheduler().refresh(time.nowEpochMillis())

        assertEquals(1, alarms.weekly.size)
        assertEquals(today.plusWeeks(1).atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), alarms.weekly.single().triggerAtEpochMillis)
    }

    @Test
    fun snooze_clampsDelayToOneAndOneHundredTwentyMinutes() {
        val scheduler = scheduler()
        scheduler.snoozeMeal(42L, "Cena", "Pasto", profileId, delayMinutes = 0)
        scheduler.snoozeMeal(43L, "Cena", "Pasto", profileId, delayMinutes = 999)

        assertEquals(time.nowEpochMillis() + 60_000L, alarms.snoozes[0].triggerAtEpochMillis)
        assertEquals(time.nowEpochMillis() + 120 * 60_000L, alarms.snoozes[1].triggerAtEpochMillis)
    }

    private fun scheduler() = NotificationScheduler(
        context = ApplicationProvider.getApplicationContext(),
        plans = plans,
        activeProfileStore = store,
        time = time,
        alarmGateway = alarms,
        settings = settings,
    )

    private suspend fun insertPlan(date: LocalDate, meals: List<MealDraft>) {
        val planId = plans.createPlan(profileId, date.toEpochDay(), time.nowEpochMillis())
        plans.appendVersion(profileId, planId, time.nowEpochMillis(), PlanVersionDraft(
            source = "TEST",
            reason = null,
            targetKcal = 2200,
            targetProteinG = 160f,
            targetCarbsG = 230f,
            targetFatG = 70f,
            days = listOf(DayDraft(date.toEpochDay(), 2200, 160f, 230f, 70f, meals)),
        ))
    }

    private fun meal(title: String, timeMinutes: Int, kcal: Int) = MealDraft(
        type = "Pasto",
        title = title,
        timeMinutes = timeMinutes,
        kcal = kcal,
        proteinG = 40f,
        carbsG = 50f,
        fatG = 15f,
        preparation = "Fixture",
        ingredients = listOf(IngredientDraft("Ingrediente", 100f, "g", "100 g", "RAW", "HIGH", "fixture")),
    )
}

private data class FixedNotificationTime(private val now: Long) : TimeProvider {
    override val zoneId = ZoneOffset.UTC
    override fun nowEpochMillis(): Long = now
}

private class FakeNotificationSettings : NotificationSettings {
    override var mealRemindersEnabled = true
    override var weeklyReviewEnabled = false
    override var mealLeadMinutes = 15
    private var codes: Set<Int> = emptySet()
    override fun scheduledRequestCodes(): Set<Int> = codes
    override fun replaceScheduledRequestCodes(values: Set<Int>) { codes = values }
}

private class FakeNotificationAlarmGateway : NotificationAlarmGateway {
    val meals = mutableListOf<MealReminderSpec>()
    val weekly = mutableListOf<WeeklyReviewReminderSpec>()
    val snoozes = mutableListOf<SnoozeReminderSpec>()
    val cancelledCodes = mutableListOf<Int>()
    override fun scheduleMeal(spec: MealReminderSpec) { meals += spec }
    override fun scheduleWeeklyReview(spec: WeeklyReviewReminderSpec) { weekly += spec }
    override fun scheduleSnooze(spec: SnoozeReminderSpec) { snoozes += spec }
    override fun cancel(requestCode: Int) { cancelledCodes += requestCode }
}
