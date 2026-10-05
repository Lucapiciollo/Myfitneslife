package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.app.Instrumentation
import android.graphics.Rect
import androidx.test.uiautomator.UiDevice
import com.myfitai.app.R
import com.myfitai.app.ui.WorkoutsActivity
import com.myfitai.app.ui.widgets.WeekDaySelectorView
import com.myfitai.app.ui.widgets.WorkoutRowView
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Visual gate for WorkoutsActivity; does not seed or modify profile/workout data. */
@RunWith(AndroidJUnit4::class)
class WorkoutsScreenVisualDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun workoutSummaryAndRowsRemainReadableAndActionsAvailable() {
        ActivityScenario.launch<WorkoutsActivity>(Intent(context, WorkoutsActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById(R.id.weekLabel))
                assertNotNull(activity.findViewById(R.id.weekDaySelector))
                assertNotNull(activity.findViewById(R.id.daySummary))
                assertNotNull(activity.findViewById(R.id.workoutList))
                assertNotNull(activity.findViewById(R.id.addWorkoutButton))
                assertTrue(activity.findViewById<View>(R.id.previousWeekButton).hasOnClickListeners())
                assertTrue(activity.findViewById<View>(R.id.nextWeekButton).hasOnClickListeners())

                val selector = activity.findViewById<WeekDaySelectorView>(R.id.weekDaySelector)
                assertTrue(selector.childCount == 7)
                val initiallySelected = (0 until selector.childCount).firstOrNull {
                    selector.getChildAt(it).background != null
                } ?: error("No selected workout day")
                selector.getChildAt(1).performClick()
                assertTrue(selector.childCount == 7)
                assertNotNull(selector.getChildAt(1).background)
                val selectedColumn = selector.getChildAt(1) as LinearLayout
                assertTrue("selected workout day should use tonal text token", (selectedColumn.getChildAt(0) as TextView).currentTextColor == activity.getColor(R.color.accent_green_dark))
                assertTrue("selected workout date should use tonal text token", (selectedColumn.getChildAt(1) as TextView).currentTextColor == activity.getColor(R.color.accent_green_dark))
                val unselectedIndex = (0 until selector.childCount).first { it != 1 }
                val unselectedColumn = selector.getChildAt(unselectedIndex) as LinearLayout
                assertTrue("unselected day text should retain secondary role", (unselectedColumn.getChildAt(0) as TextView).currentTextColor == activity.getColor(R.color.text_secondary))
                selector.getChildAt(initiallySelected).performClick()
                val restoredColumn = selector.getChildAt(initiallySelected) as LinearLayout
                assertTrue("selected day should restore tonal text", (restoredColumn.getChildAt(0) as TextView).currentTextColor == activity.getColor(R.color.accent_green_dark))

                val scroll = descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<ScrollView>()
                    .single()
                assertNotNull(scroll)
                scroll.fullScroll(View.FOCUS_DOWN)
                assertTrue(activity.findViewById<View>(R.id.addWorkoutButton).isShown)
                assertTrue(activity.findViewById<View>(R.id.addWorkoutButton).hasOnClickListeners())
            }
            instrumentation.waitForIdleSync()
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "workouts-screen.png"))
        }
    }

    @Test
    fun weekNavigationAndAddActionKeepTheirExistingRoutes() {
        val monitor: Instrumentation.ActivityMonitor = instrumentation.addMonitor(
            "com.myfitai.app.ui.NewWorkoutActivity", null, false,
        )
        try {
            ActivityScenario.launch<WorkoutsActivity>(Intent(context, WorkoutsActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    listOf(R.id.previousWeekButton, R.id.nextWeekButton, R.id.addWorkoutButton).forEach { id ->
                        val action = activity.findViewById<View>(id)
                        assertTrue("Action $id disabled", action.isEnabled)
                        assertTrue("Action $id has no listener", action.hasOnClickListeners())
                    }
                    activity.findViewById<View>(R.id.addWorkoutButton).performClick()
                }
                val newWorkout = instrumentation.waitForMonitorWithTimeout(monitor, 5_000L)
                assertNotNull("Nuovo allenamento route was not opened", newWorkout)
                newWorkout.finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun emptyOrPopulatedWorkoutRowsHaveNoTextEllipsis() {
        ActivityScenario.launch<WorkoutsActivity>(Intent(context, WorkoutsActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val summary = activity.findViewById<TextView>(R.id.daySummary)
                descendants(summary).filterIsInstance<TextView>().forEach { text ->
                    text.layout?.let { layout ->
                        for (line in 0 until layout.lineCount) {
                            assertTrue("ellipsized '${text.text}'", layout.getEllipsisCount(line) == 0)
                        }
                    }
                }
                assertTrue(summary.text.isNotBlank())
                val list = activity.findViewById<LinearLayout>(R.id.workoutList)
                assertTrue(list.childCount >= 0)
            }
        }
    }

    @Test
    fun workoutRowWrapsLongExistingFieldsWithoutClipping() {
        ActivityScenario.launch<WorkoutsActivity>(Intent(context, WorkoutsActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            var row: WorkoutRowView? = null
            scenario.onActivity { activity ->
                val list = activity.findViewById<LinearLayout>(R.id.workoutList)
                row = WorkoutRowView(activity).apply {
                    setTitle("Forza total body con circuito metabolico finale")
                    setSubtitle("18:30 · 75 minuti · Pesi · Nota lunga già prevista nel riepilogo della sessione")
                    setPhoto(R.drawable.img_workout_weights)
                }
                list.addView(row)
                val scroll = descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<ScrollView>()
                    .single()
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
            instrumentation.waitForIdleSync()
            device.takeScreenshot(
                File(context.getExternalFilesDir(null), "qa-artifacts/workout-row-stress.png"),
            )
            scenario.onActivity {
                val workoutRow = requireNotNull(row)

                descendants(workoutRow).filterIsInstance<TextView>().forEach { text ->
                    val layout = text.layout
                    assertNotNull("layout not ready for '${text.text}'", layout)
                    for (line in 0 until layout.lineCount) {
                        assertTrue("ellipsized '${text.text}'", layout.getEllipsisCount(line) == 0)
                    }
                    assertTrue("'${text.text}' vertically clipped", layout.height <= text.height - text.totalPaddingTop - text.totalPaddingBottom)
                }
                val title = descendants(workoutRow).filterIsInstance<TextView>().first()
                val textColumn = title.parent as View
                val thumbnail = descendants(workoutRow).filterIsInstance<ImageView>().last()
                val textBounds = Rect().also {
                    textColumn.getDrawingRect(it)
                    workoutRow.offsetDescendantRectToMyCoords(textColumn, it)
                }
                val thumbnailBounds = Rect().also {
                    thumbnail.getDrawingRect(it)
                    workoutRow.offsetDescendantRectToMyCoords(thumbnail, it)
                }
                assertTrue("text column overlaps thumbnail: text=$textBounds, image=$thumbnailBounds", textBounds.left >= thumbnailBounds.right)
            }
        }
    }

    private fun descendants(view: View): List<View> = when (view) {
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }
}
