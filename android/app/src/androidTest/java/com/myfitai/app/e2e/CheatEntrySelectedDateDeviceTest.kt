package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfitai.app.R
import com.myfitai.app.ui.CheatEntryActivity
import com.myfitai.app.ui.FoodPlanActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.view.View

@RunWith(AndroidJUnit4::class)
class CheatEntrySelectedDateDeviceTest {
    @Test
    fun selectedHistoricalDate_isPrefilledWithoutSaving() {
        val selectedWeek = LocalDate.now().minusWeeks(2)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val selectedDate = selectedWeek
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, FoodPlanActivity::class.java)
            .putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, selectedWeek.toEpochDay())

        ActivityScenario.launch<FoodPlanActivity>(intent).use { foodScenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(CheatEntryActivity::class.java.name, null, false)
            foodScenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.cheatButton).visibility)
                if (activity.findViewById<View>(R.id.emptyPlanText).visibility == View.VISIBLE) {
                    assertFalse(activity.findViewById<View>(R.id.shoppingButton).visibility == View.VISIBLE)
                }
                activity.findViewById<View>(R.id.cheatButton).performClick()
            }
            val cheatActivity = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
            assertNotNull(cheatActivity)
            assertEquals(
                selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN)),
                cheatActivity.findViewById<android.widget.TextView>(R.id.dateValue).text.toString(),
            )
        }
    }
}
