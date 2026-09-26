package com.myfitai.app.e2e

import android.content.ComponentName
import android.content.Intent
import android.view.View
import java.io.File
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.R
import com.myfitai.app.ui.MealDetailActivity
import com.myfitai.app.ui.FoodPlanActivity
import com.myfitai.app.ui.ShoppingListActivity
import com.myfitai.app.ui.WeeklyReviewActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class FoodReviewScreensDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val weekStart = LocalDate.of(2026, 8, 31).toEpochDay()

    @Before
    fun seedQaData() {
        context.startActivity(Intent().apply {
            component = ComponentName(context.packageName, "${context.packageName}.qa.QaSeederActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        if (!device.wait(Until.hasObject(By.text("SEED_6_MONTHS")), 15_000)) {
            context.startActivity(Intent().apply {
                component = ComponentName(context.packageName, "${context.packageName}.qa.QaSeederActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
        }
        assertTrue(device.wait(Until.hasObject(By.text("SEED_6_MONTHS")), 15_000))
        device.findObject(By.text("SEED_6_MONTHS")).click()
        assertTrue(waitForSeed())
    }

    @Test
    fun mealDetail_shoppingList_andWeeklyReview_areReadable() {
        val mealId = runBlocking {
            val database = MyFitAiDatabase.getInstance(context)
            val profileId = ActiveProfileStore(context).currentIdOrNull()!!
            val plan = database.mealPlanDao().getPlanForWeek(profileId, weekStart)!!
            val version = database.mealPlanDao().getLatestVersion(profileId, plan.id)!!
            val day = database.mealPlanDao().getDays(profileId, version.id).first { it.dateEpochDay == weekStart }
            database.mealPlanDao().getMeals(profileId, day.id).first().id
        }

        context.startActivity(Intent(context, MealDetailActivity::class.java).apply {
            putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("QA Colazione")), 10_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/detailSegment")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/consumedButton")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/skippedButton")))
        assertTrue(device.hasObject(By.text("Ingredienti")))
        device.findObject(By.text("Preparazione")).click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/preparationContainer")), 2_000))

        ActivityScenario.launch<ShoppingListActivity>(Intent(context, ShoppingListActivity::class.java).apply {
            putExtra(ShoppingListActivity.EXTRA_WEEK_START_EPOCH_DAY, weekStart)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.weekLabel).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.viewModeSegment).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.filterSegment).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.statusSummary).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.exportButton).visibility == View.VISIBLE)
            }
            dismissShoppingConfirmationIfPresent()
        }

        ActivityScenario.launch<WeeklyReviewActivity>(Intent(context, WeeklyReviewActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.weekLabel).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.trackingCoverageText).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.generateReviewButton).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.adherenceValue).visibility == View.VISIBLE)
            }
        }
    }

    @Test
    fun dailyTotals_showOnlyKcalAndProteinWithClearBasePlanAndConsumptionRows() {
        ActivityScenario.launch<FoodPlanActivity>(Intent(context, FoodPlanActivity::class.java).apply {
            putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, weekStart)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/dailyTotalCard")), 10_000))
            val expectedIds = listOf(
                "totalKcalBmr", "totalKcalTdee", "totalKcalTarget", "totalProteinTarget",
                "totalKcalPlanned", "totalProteinPlanned", "totalKcalConsumed", "totalProteinConsumed",
            )
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.dailyTotalRowBmr) != null)
                assertTrue(activity.findViewById<View>(R.id.dailyTotalRowTdee) != null)
                assertTrue(activity.findViewById<View>(R.id.dailyTotalRowTarget) != null)
                assertTrue(activity.findViewById<View>(R.id.dailyTotalRowPlanned) != null)
                assertTrue(activity.findViewById<View>(R.id.dailyTotalRowConsumed) != null)
                assertTrue(activity.findViewById<View>(R.id.dailyTotalLegend).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<View>(R.id.totalConsumptionNote).visibility == View.VISIBLE)
                listOf(
                    "totalCarbsTarget", "totalFatTarget",
                    "totalCarbsPlanned", "totalFatPlanned",
                    "totalCarbsConsumed", "totalFatConsumed",
                ).forEach { name ->
                    assertEquals("Macro field $name should not be in the daily summary", 0,
                        activity.resources.getIdentifier(name, "id", activity.packageName))
                }
            }
            expectedIds.forEach { id -> assertTrue("Missing daily total field $id", device.hasObject(By.res("com.myfitai.app:id/$id"))) }
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "food_plan_daily_totals.png"))
        }
    }

    private fun dismissShoppingConfirmationIfPresent() {
        if (device.wait(Until.hasObject(By.text("Salvare la lista della spesa?")), 2_000)) {
            device.findObject(By.text("Conferma")).click()
            device.waitForIdle()
        }
    }

    private fun waitForSeed(): Boolean {
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val status = device.findObject(By.desc("qa_status"))
            if (status != null && status.text.contains("profile=")) return true
            Thread.sleep(250L)
        }
        return false
    }
}
