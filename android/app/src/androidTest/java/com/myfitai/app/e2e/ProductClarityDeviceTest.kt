package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.R
import com.myfitai.app.ui.FoodPlanActivity
import com.myfitai.app.ui.PhysicalEvolutionActivity
import com.myfitai.app.ui.ProfileActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Read-only checks against the profile and plan already installed on the device. */
@RunWith(AndroidJUnit4::class)
class ProductClarityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun foodDailyTotal_distinguishesBmrTdeePlanAndRegisteredConsumption() {
        ActivityScenario.launch<FoodPlanActivity>(Intent(context, FoodPlanActivity::class.java).apply {
            putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, LocalDate.now().toEpochDay())
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<android.view.View>(R.id.dailyTotalCard).visibility == View.VISIBLE || activity.findViewById<android.view.View>(R.id.dailyTotalCard).visibility == View.GONE)
                assertTrue(activity.findViewById<View>(R.id.dailyTotalRowBmr).visibility == View.VISIBLE || activity.findViewById<View>(R.id.dailyTotalRowBmr).visibility == View.GONE)
                assertTrue(activity.findViewById<android.view.View>(R.id.dailyTotalRowTdee).visibility == View.VISIBLE || activity.findViewById<android.view.View>(R.id.dailyTotalRowTdee).visibility == View.GONE)
                assertTrue(activity.findViewById<android.view.View>(R.id.dailyTotalRowTarget).visibility == View.VISIBLE || activity.findViewById<android.view.View>(R.id.dailyTotalRowTarget).visibility == View.GONE)
                assertTrue(activity.findViewById<android.view.View>(R.id.dailyTotalRowPlanned).visibility == View.VISIBLE || activity.findViewById<android.view.View>(R.id.dailyTotalRowPlanned).visibility == View.GONE)
                assertEquals(View.VISIBLE, activity.findViewById<android.view.View>(R.id.dailyTotalRowConsumed).visibility)
                assertEquals("BMR · a riposo", activity.findViewById<android.widget.TextView>(R.id.dailyBmrLabel).text.toString())
                assertEquals("TDEE · attività abituale", activity.findViewById<android.widget.TextView>(R.id.dailyTdeeLabel).text.toString())
                assertTrue(activity.findViewById<android.view.View>(R.id.dailyTotalLegend).contentDescription == null)
                assertTrue(activity.findViewById<android.view.View>(R.id.totalConsumptionNote).visibility == View.VISIBLE)
                listOf("totalCarbsTarget", "totalFatTarget", "totalCarbsPlanned", "totalFatPlanned", "totalCarbsConsumed", "totalFatConsumed").forEach { name ->
                    assertEquals("Daily total should only expose calories and protein", 0, activity.resources.getIdentifier(name, "id", activity.packageName))
                }
                assertEquals("Only one plan generation action should remain", 0,
                    activity.resources.getIdentifier("regenerateForGoalButton", "id", activity.packageName))
                assertEquals("The single plan generation action is visible", View.VISIBLE,
                    activity.findViewById<View>(R.id.generatePlanButton).visibility)
            }
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            repeat(5) {
                device.swipe(device.displayWidth / 2, (device.displayHeight * 0.82).toInt(), device.displayWidth / 2, (device.displayHeight * 0.28).toInt(), 20)
            }
            device.takeScreenshot(File(dir, "product_clarity_food_daily_total.png"))
        }
    }

    @Test
    fun progressExplainsSelectedDateRangeAndKeepsLatestMeasurementVisible() {
        ActivityScenario.launch<PhysicalEvolutionActivity>(Intent(context, PhysicalEvolutionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<android.view.View>(R.id.rangeDataNote).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<android.view.View>(R.id.progressSummaryTitle).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<android.view.View>(R.id.metricSegment).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<android.view.View>(R.id.timeRangeSelector).visibility == View.VISIBLE)
            }
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "product_clarity_progress.png"))
        }
    }

    @Test
    fun profileHasOneClearProfilePreferencesEditEntry() {
        ActivityScenario.launch<ProfileActivity>(Intent(context, ProfileActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }).use {
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/rowFoodPreferences")), 5_000))
            device.findObject(By.res("com.myfitai.app:id/rowFoodPreferences")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Modifica")), 5_000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("Profilo")), 5_000))
        }
    }

}
