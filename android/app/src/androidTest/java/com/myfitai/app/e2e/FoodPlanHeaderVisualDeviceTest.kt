package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.myfitai.app.R
import com.myfitai.app.ui.FoodPlanActivity
import com.myfitai.app.ui.widgets.WeekDaySelectorView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodPlanHeaderVisualDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun weekHeaderAndDaySelectionRemainAvailable() {
        ActivityScenario.launch<FoodPlanActivity>(Intent(context, FoodPlanActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById(R.id.prevWeekButton))
                assertNotNull(activity.findViewById(R.id.nextWeekButton))
                assertNotNull(activity.findViewById(R.id.weekRangeLabel))
                assertNotNull(activity.findViewById(R.id.weekDaySelector))
                val previousWeek = activity.findViewById<View>(R.id.prevWeekButton)
                val nextWeek = activity.findViewById<View>(R.id.nextWeekButton)
                assertTrue(previousWeek.isEnabled)
                assertTrue(nextWeek.isEnabled)
                assertTrue(previousWeek.hasOnClickListeners())
                assertTrue(nextWeek.hasOnClickListeners())

                val selector = activity.findViewById<WeekDaySelectorView>(R.id.weekDaySelector)
                assertEquals(7, selector.childCount)
                val screenshotDir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
                device.takeScreenshot(File(screenshotDir, "food-plan-header.png"))
                selector.getChildAt(1).performClick()
                assertEquals(7, selector.childCount)
                assertNotNull("Selected weekday has no visual selection", selector.getChildAt(1).background)
                val selectedColumn = selector.getChildAt(1) as android.widget.LinearLayout
                assertTrue(
                    "FoodPlan caller keeps the existing brand selection",
                    (selectedColumn.getChildAt(0) as android.widget.TextView).currentTextColor == activity.getColor(R.color.white),
                )
            }
        }
    }
}
