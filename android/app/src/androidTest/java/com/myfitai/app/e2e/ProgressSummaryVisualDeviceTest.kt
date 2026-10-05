package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.myfitai.app.R
import com.myfitai.app.ui.PhysicalEvolutionActivity
import com.myfitai.app.ui.widgets.SelectableSegmentView
import com.myfitai.app.ui.widgets.TimeRangeSelectorView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Non-destructive presentation checks for the existing Progresso root screen. */
@RunWith(AndroidJUnit4::class)
class ProgressSummaryVisualDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun rangeAndMetricControlsRemainFunctionalAndSummaryIsReadable() {
        ActivityScenario.launch<PhysicalEvolutionActivity>(
            Intent(context, PhysicalEvolutionActivity::class.java),
        ).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertNotNull(activity.findViewById(R.id.metricSegment))
                assertNotNull(activity.findViewById(R.id.timeRangeSelector))
                assertNotNull(activity.findViewById(R.id.evolutionChart))
                assertNotNull(activity.findViewById(R.id.progressSummaryCard))

                val metric = activity.findViewById<SelectableSegmentView>(R.id.metricSegment)
                val range = activity.findViewById<TimeRangeSelectorView>(R.id.timeRangeSelector)
                assertEquals(3, metric.childCount)
                assertEquals(4, range.childCount)
                assertTrue(activity.findViewById<View>(R.id.progressMetricHelpButton).hasOnClickListeners())

                metric.getChildAt(1).performClick()
                range.getChildAt(2).performClick()
                assertEquals("Grasso corporeo", activity.findViewById<TextView>(R.id.metricLabel).text.toString())
                assertEquals("Dati insufficienti", activity.findViewById<TextView>(R.id.metricDelta).text.toString())
                assertTrue(activity.findViewById<TextView>(R.id.progressSummaryTitle).text.toString().contains("6M"))
                metric.getChildAt(0).performClick()
                range.getChildAt(1).performClick()
                assertEquals("Peso", activity.findViewById<TextView>(R.id.metricLabel).text.toString())
                assertTrue(activity.findViewById<TextView>(R.id.progressSummaryTitle).text.toString().contains("3M"))

                val scroll = descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<ScrollView>()
                    .single()
                scroll.fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "progress-summary.png"))
            scenario.onActivity { activity ->
                val summary = activity.findViewById<TextView>(R.id.progressSummaryText)
                assertTrue(summary.text.isNotBlank())
                assertTrue(summary.layout != null)
                for (line in 0 until summary.layout.lineCount) {
                    assertEquals("summary has unexpected ellipsis", 0, summary.layout.getEllipsisCount(line))
                }
                assertTrue(activity.findViewById<View>(R.id.progressSummaryCard).isShown)
            }
        }
    }

    private fun descendants(view: View): List<View> = when (view) {
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }
}
