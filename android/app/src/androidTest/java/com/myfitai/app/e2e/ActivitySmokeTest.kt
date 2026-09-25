package com.myfitai.app.e2e

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.data.profile.WorkoutPreferences
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.ai.AiJobWorker
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
import com.myfitai.app.domain.progress.ProgressAnalysisCompactContract
import com.myfitai.app.domain.progress.ProgressAnalysisPreferences
import org.junit.Assert.assertEquals
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
        // The smoke suite must not depend on a persisted Settings toggle from a previous test run.
        WorkoutPreferences(context).setEnabled(profileId, WorkoutPreferences.DEFAULT_ENABLED)
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

    @Test
    fun progressAnalysisPatternRows_keepWrappedTextAlignedBesideSemaphore() {
        val profileId = ActiveProfileStore(context).currentIdOrNull() ?: error("Test profile missing")
        val preferences = ProgressAnalysisPreferences(context)
        val previousSummary = preferences.lastSummary(profileId)
        val previousLastSuccess = preferences.lastSuccessEpochMillis(profileId)
        val intent = Intent(context, PhysicalEvolutionActivity::class.java)
        ActivityScenario.launch<PhysicalEvolutionActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val details = activity.javaClass.getDeclaredField("analysisDetailsContainer").run {
                    isAccessible = true
                    get(activity) as LinearLayout
                }
                val patterns = listOf(
                    ProgressAnalysisCompactContract.Pattern(
                        ProgressAnalysisCompactContract.PatternCode.WEIGHT,
                        ProgressAnalysisCompactContract.Direction.FAVORABLE,
                        ProgressAnalysisCompactContract.Confidence.HIGH,
                    ),
                    ProgressAnalysisCompactContract.Pattern(
                        ProgressAnalysisCompactContract.PatternCode.BODY_FAT,
                        ProgressAnalysisCompactContract.Direction.UNFAVORABLE,
                        ProgressAnalysisCompactContract.Confidence.MEDIUM,
                    ),
                )
                val render = activity.javaClass.getDeclaredMethod(
                    "renderAnalysisDetails",
                    List::class.java,
                ).apply { isAccessible = true }
                render.invoke(activity, patterns)

                assertEquals(2, details.childCount)
                val firstRow = details.getChildAt(0) as LinearLayout
                val secondRow = details.getChildAt(1) as LinearLayout
                assertEquals(2, firstRow.childCount)
                val indicator = firstRow.getChildAt(0) as FrameLayout
                val textColumn = firstRow.getChildAt(1) as TextView
                assertTrue(textColumn.text.toString().contains("confidenza"))
                assertEquals(firstRow.getChildAt(1).layoutParams.width, secondRow.getChildAt(1).layoutParams.width)
                assertTrue(firstRow.contentDescription.toString().contains("Peso"))
                val indicatorParams = indicator.layoutParams as LinearLayout.LayoutParams
                val params = firstRow.getChildAt(1).layoutParams as LinearLayout.LayoutParams
                assertEquals(
                    activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.progress_pattern_indicator_width),
                    indicatorParams.width,
                )
                assertEquals(activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.space_4), indicatorParams.marginEnd)
                assertEquals(0, params.leftMargin)
                assertEquals(1, indicator.childCount)
            }
        }
        assertEquals(previousSummary, preferences.lastSummary(profileId))
        assertEquals(previousLastSuccess, preferences.lastSuccessEpochMillis(profileId))
    }

    @Test
    fun queuedProgressAnalysis_remainsVisibleAfterActivityRecreation() {
        val profileId = ActiveProfileStore(context).currentIdOrNull() ?: error("Test profile missing")
        val uniqueName = "ai-progress_analysis-$profileId-manual-ui-reopen-test"
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<AiJobWorker>()
            .setInputData(workDataOf(
                AiJobWorker.KEY_TYPE to AiJobType.PROGRESS_ANALYSIS.name,
                AiJobWorker.KEY_PROFILE_ID to profileId,
                AiJobWorker.KEY_JOB_KEY to "manual-ui-reopen-test",
            ))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(1, TimeUnit.DAYS)
            .addTag("ai-progress_analysis-profile-$profileId")
            .addTag("ai-progress_analysis-profile-$profileId-manual")
            .build()
        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request).result.get()

        try {
            ActivityScenario.launch<PhysicalEvolutionActivity>(Intent(context, PhysicalEvolutionActivity::class.java)).use { scenario ->
                assertTrue("Queued progress analysis not reflected in UI", waitForQueuedAnalysis(scenario))
                scenario.recreate()
                assertTrue("Progress UI lost its queued worker after recreation", waitForQueuedAnalysis(scenario))
            }
        } finally {
            workManager.cancelUniqueWork(uniqueName)
        }
    }

    private fun waitForQueuedAnalysis(scenario: ActivityScenario<PhysicalEvolutionActivity>): Boolean {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            var found = false
            scenario.onActivity { activity ->
                val root = activity.findViewById<View>(com.myfitai.app.R.id.dynamicProgressCards)
                fun containsQueuedText(view: View): Boolean = when (view) {
                    is TextView -> view.text?.toString()?.contains("Analisi in coda") == true
                    is android.view.ViewGroup -> (0 until view.childCount).any { containsQueuedText(view.getChildAt(it)) }
                    else -> false
                }
                found = containsQueuedText(root)
            }
            if (found) return true
            instrumentation.waitForIdleSync()
            Thread.sleep(100L)
        }
        return false
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
