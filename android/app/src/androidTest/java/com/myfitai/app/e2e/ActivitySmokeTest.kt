package com.myfitai.app.e2e

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.BiaActivity
import com.myfitai.app.ui.BodyMeasuresActivity
import com.myfitai.app.ui.ExportActivity
import com.myfitai.app.ui.FoodPlanActivity
import com.myfitai.app.ui.HistoryActivity
import com.myfitai.app.ui.HomeActivity
import com.myfitai.app.ui.MeasurementsActivity
import com.myfitai.app.ui.NewBodyMeasurementActivity
import com.myfitai.app.ui.NutritionPathActivity
import com.myfitai.app.ui.NutritionPlanSettingsActivity
import com.myfitai.app.ui.NewWorkoutActivity
import com.myfitai.app.ui.NotificationsActivity
import com.myfitai.app.ui.PhysicalEvolutionActivity
import com.myfitai.app.ui.ProfileActivity
import com.myfitai.app.ui.ProfileEditActivity
import com.myfitai.app.ui.SettingsActivity
import com.myfitai.app.ui.ShoppingListActivity
import com.myfitai.app.ui.WeeklyReviewActivity
import com.myfitai.app.ui.WorkoutsActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Smoke coverage for local screens; provider-dependent actions are intentionally not invoked. */
@RunWith(AndroidJUnit4::class)
class ActivitySmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun seedProfile() {
        val database = MyFitAiDatabase.getInstance(context)
        val profileId = kotlinx.coroutines.runBlocking {
            database.userProfileDao().getFirst()?.id ?: database.userProfileDao().insert(UserProfileEntity(
                name = "Activity Smoke Test",
                birthDateEpochDay = null,
                heightCm = 186f,
                currentWeightKg = 89f,
                goal = "Ricomposizione",
                activityLevel = "Moderatamente attivo",
                wakeTimeMinutes = 420,
                sleepTimeMinutes = 1410,
                dietaryPreferencesJson = null,
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                biologicalSex = "Maschio",
                initialWeightKg = 89f,
            ))
        }
        ActiveProfileStore(context).selectProfile(profileId)
    }

    @Test
    fun localActivities_reachResumedWithContentView() {
        smoke(HomeActivity::class.java)
        smoke(PhysicalEvolutionActivity::class.java)
        smoke(SettingsActivity::class.java)
        smoke(ProfileActivity::class.java)
        smoke(ProfileEditActivity::class.java)
        smoke(MeasurementsActivity::class.java)
        smoke(BiaActivity::class.java, BiaActivity.EXTRA_OPEN_HISTORY to true)
        smoke(BodyMeasuresActivity::class.java, BodyMeasuresActivity.EXTRA_OPEN_HISTORY to true)
        smoke(HistoryActivity::class.java)
        smoke(WorkoutsActivity::class.java)
        smoke(NewWorkoutActivity::class.java, NewWorkoutActivity.EXTRA_DATE_EPOCH_DAY to LocalDate.now().toEpochDay())
        smoke(NewBodyMeasurementActivity::class.java)
        smoke(NutritionPathActivity::class.java)
        smoke(NutritionPlanSettingsActivity::class.java)
        smoke(FoodPlanActivity::class.java)
        smoke(ShoppingListActivity::class.java)
        smoke(WeeklyReviewActivity::class.java)
        smoke(NotificationsActivity::class.java)
        smoke(ExportActivity::class.java)
    }

    private fun <T : Activity> smoke(activityClass: Class<T>, vararg extras: Pair<String, Any>) {
        val intent = Intent(context, activityClass).apply {
            extras.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is String -> putExtra(key, value)
                    else -> error("Unsupported smoke extra type: ${value::class.java.name}")
                }
            }
        }
        ActivityScenario.launch<T>(intent).use { scenario ->
            assertTrue(
                "${activityClass.simpleName} did not reach STARTED",
                scenario.state == Lifecycle.State.STARTED || scenario.state == Lifecycle.State.RESUMED,
            )
            scenario.onActivity { activity ->
                assertNotNull("${activityClass.simpleName} has no decor view", activity.window?.decorView)
                assertNotNull(
                    "${activityClass.simpleName} has no content view",
                    activity.findViewById<View>(android.R.id.content),
                )
            }
        }
    }
}
