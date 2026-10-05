package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.MealDetailActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reads existing meal data only; never seeds, consumes, or writes profile data. */
@RunWith(AndroidJUnit4::class)
class MealDetailVisualDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun existingMealDetailKeepsTabsActionsAndWrapping() {
        val mealId = findExistingMealId()
        ActivityScenario.launch<MealDetailActivity>(
            Intent(context, MealDetailActivity::class.java)
                .putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId),
        ).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "mealTitle")), 10_000))
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById(R.id.mealImage))
                assertNotNull(activity.findViewById(R.id.mealTitle))
                assertNotNull(activity.findViewById(R.id.detailSegment))
                assertNotNull(activity.findViewById(R.id.ingredientsCard))
                assertNotNull(activity.findViewById(R.id.preparationCard))
                assertTrue(activity.findViewById<View>(R.id.consumedButton).hasOnClickListeners())
                assertTrue(activity.findViewById<View>(R.id.skippedButton).hasOnClickListeners())
            }
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "meal-detail.png"))
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.mealTitle).text.isNotBlank())
            }

            device.findObject(By.text("Preparazione")).click()
            assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "preparationContainer")), 3_000))
            scenario.onActivity { activity ->
                val scroll = descendants(activity.findViewById<ViewGroup>(android.R.id.content))
                    .filterIsInstance<ScrollView>().single()
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val preparation = activity.findViewById<TextView>(R.id.preparationContainer)
                val layout = requireNotNull(preparation.layout)
                for (line in 0 until layout.lineCount) {
                    assertTrue("preparation text clipped", layout.getEllipsisCount(line) == 0)
                }
            }
            device.pressBack()
            instrumentation.waitForIdleSync()
            assertTrue("Back did not leave the meal detail", !device.hasObject(By.res(context.packageName, "mealTitle")))
        }
    }

    private fun findExistingMealId(): Long = runBlocking {
        val profileId = ActiveProfileStore(context).currentIdOrNull() ?: return@runBlocking -1L
        val database = MyFitAiDatabase.getInstance(context)
        val plans = database.mealPlanDao().observePlans(profileId).first()
            .sortedByDescending { it.weekStartEpochDay }
        plans.forEach { plan ->
            val version = database.mealPlanDao().getLatestVersion(profileId, plan.id) ?: return@forEach
            database.mealPlanDao().getDays(profileId, version.id).forEach { day ->
                database.mealPlanDao().getMeals(profileId, day.id).firstOrNull()?.let { return@runBlocking it.id }
            }
        }
        -1L
    }

    private fun descendants(view: View): List<View> = when (view) {
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }
}
