package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.myfitai.app.R
import com.myfitai.app.ui.HomeActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class HomeActivityCheckInDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun activityCheckIn_updatesSummaryAndCanBeCleared() {
        ActivityScenario.launch<HomeActivity>(Intent(
            instrumentation.targetContext,
            HomeActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            try {
                scenario.onActivity { activity ->
                assertTrue(activity.findViewById<View>(R.id.activityCheckInCard).visibility == View.VISIBLE)
                assertTrue(activity.findViewById<View>(R.id.dashboardMetricsPanel) is androidx.compose.ui.platform.ComposeView)
                assertTrue(activity.findViewById<View>(R.id.dashboardMetricsPanel).isShown)
                }

                val restTdee = readTdeeWhenAvailable(scenario)
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.activityCheckInRestButton).performClick()
                }
                waitForActivityText(scenario, R.id.activityCheckInSummary, "Riposo registrato")
                assertTrue("REST TDEE must be available", restTdee > 0)
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.activityCheckInClearButton).performClick()
                }

                UiScrollable(UiSelector().scrollable(true)).scrollIntoView(
                    UiSelector().resourceId("com.myfitai.app:id/activityCheckInWorkoutButton"),
                )
                device.findObject(By.res("com.myfitai.app:id/activityCheckInWorkoutButton")).click()

                assertTrue(device.wait(Until.hasObject(By.text("Allenamento previsto oggi")), 5_000))
                assertTrue(device.hasObject(By.text("60 minuti · media")))
                device.findObject(By.text("60 minuti · media")).click()
                assertTrue(device.wait(Until.hasObject(By.textContains("Allenamento previsto: 60 min")), 3_000))
                assertTrue(device.hasObject(By.textContains("+140 kcal")))
                val workoutTdee = readTdeeWhenAvailable(scenario)
                assertTrue("Workout TDEE must exceed REST TDEE", workoutTdee > restTdee)
                waitForActivityText(scenario, R.id.caloriesTargetSourceText, "TDEE operativo")

                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.activityCheckInClearButton).performClick()
                }
                waitForActivityText(scenario, R.id.activityCheckInSummary, "Nessun allenamento registrato")
            } finally {
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.activityCheckInClearButton)?.performClick()
                }
            }
        }
    }

    private fun readTdeeWhenAvailable(scenario: ActivityScenario<HomeActivity>): Int {
        val value = AtomicReference<String>()
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                value.set(activity.findViewById<android.widget.TextView>(R.id.caloriesTdeeValue).text.toString())
            }
            value.get()?.filter { it.isDigit() }?.toIntOrNull()?.let { return it }
            Thread.sleep(100L)
        }
        throw AssertionError("TDEE did not render: ${value.get()}")
    }

    private fun waitForActivityText(scenario: ActivityScenario<HomeActivity>, id: Int, expected: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        var actual = ""
        while (System.currentTimeMillis() < deadline) {
            val value = AtomicReference<String>()
            scenario.onActivity { activity ->
                value.set(activity.findViewById<android.widget.TextView>(id).text.toString())
            }
            actual = value.get()
            if (actual.contains(expected)) return
            Thread.sleep(100L)
        }
        throw AssertionError("Expected '$expected' in '$actual'")
    }
}
