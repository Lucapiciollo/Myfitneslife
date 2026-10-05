package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.myfitai.app.R
import com.myfitai.app.ui.MealDetailActivity
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reproducible unavailable-meal state; the invalid ID is read-only and creates no records. */
@RunWith(AndroidJUnit4::class)
class MealDetailUnavailableVisualDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun unavailableMealShowsExistingErrorAndAllowsBack() {
        ActivityScenario.launch<MealDetailActivity>(
            Intent(context, MealDetailActivity::class.java)
                .putExtra(MealDetailActivity.EXTRA_MEAL_ID, -1L),
        ).use { scenario ->
            assertTrue(waitForError(scenario))
            scenario.onActivity { activity ->
                val error = activity.findViewById<android.widget.TextView>(R.id.mealError)
                assertEquals("Pasto non valido", error.text.toString())
                assertNotNull(error.background)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.ingredientsCard).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.preparationCard).visibility)
            }
            instrumentation.waitForIdleSync()
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "meal-detail-unavailable.png"))

            device.pressBack()
            instrumentation.waitForIdleSync()
        }
    }

    private fun waitForError(scenario: ActivityScenario<MealDetailActivity>): Boolean {
        val deadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < deadline) {
            var visible = false
            scenario.onActivity { activity ->
                visible = activity.findViewById<android.widget.TextView>(R.id.mealError)
                    .text.toString() == "Pasto non valido"
            }
            if (visible) return true
            Thread.sleep(100L)
        }
        return false
    }
}
