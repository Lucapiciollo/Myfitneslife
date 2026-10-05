package com.myfitai.app.e2e

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.myfitai.app.ui.widgets.TargetProgressRingView
import java.io.File
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.myfitai.app.R
import com.myfitai.app.ui.HomeActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifica il rendering dei valori e progressi effettivi senza modificare dati o viste. */
@RunWith(AndroidJUnit4::class)
class HomeCaloriesLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun calorieCardAndCheckIn_useCurrentTargetsWithoutClippingOrSyntheticData() {
        ActivityScenario.launch<HomeActivity>(
            Intent(instrumentation.targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use { scenario ->
            Thread.sleep(2_500L)
            scenario.onActivity { activity ->
                val targetText = activity.findViewById<TextView>(R.id.caloriesTargetValue).text.toString()
                val consumedText = activity.findViewById<TextView>(R.id.caloriesConsumedText).text.toString()
                val target = Regex("^\\s*(\\d+)").find(targetText)?.groupValues?.get(1)?.toIntOrNull()
                val consumed = consumedText.trim().toIntOrNull()
                val ring = activity.findViewById<View>(R.id.caloriesConsumedProgressRing)
                assertTrue("existing consumed label remains visible", activity.findViewById<View>(R.id.caloriesConsumedLabel).isShown)
                assertEqualsText(activity, R.id.caloriesConsumedLabel, "Consumate oggi (kcal)")
                assertEqualsText(activity, R.id.caloriesConsumedProteinLabel, "Proteine consumate (g)")
                assertEqualsText(activity, R.id.caloriesRemainingLabel, "Rimangono (kcal)")
                val metricRings = listOf(
                    activity.findViewById<View>(R.id.caloriesConsumedProgressRing),
                    activity.findViewById<View>(R.id.caloriesConsumedProteinProgressRing),
                    activity.findViewById<View>(R.id.caloriesRemainingProgressRing),
                )
                assertTrue("all three metric cards share the same horizontal row", metricRings.map { (it.parent as View).parent }.distinct().size == 1)
                if (target != null && target > 0) {
                    assertEqualsText(activity, R.id.caloriesConsumedMaximumText, target.toString())
                    assertEqualsText(activity, R.id.caloriesRemainingMaximumText, target.toString())
                }
                val proteinRing = activity.findViewById<TargetProgressRingView>(R.id.caloriesConsumedProteinProgressRing)
                val proteinTarget = (proteinRing.getTag(R.id.protein_target_tag) as? Double)?.toInt()
                val proteinConsumedText = activity.findViewById<TextView>(R.id.caloriesConsumedProteinText).text.toString()
                if (proteinTarget == null) {
                    assertTrue("protein ring hides when no valid target exists", proteinRing.visibility == View.GONE)
                    assertTrue("protein reading remains explicit", proteinConsumedText == "—")
                } else {
                    val proteinValue = proteinConsumedText.trim().removePrefix("≈ ").toIntOrNull()
                    val indicator = proteinRing.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.targetProgressIndicator)
                    if (proteinValue == null) {
                        assertTrue("missing protein consumption stays unavailable", proteinConsumedText == "—")
                        assertTrue("missing protein consumption draws no progress", indicator.progress == 0)
                    } else {
                        assertTrue("protein ring shows the current intake ratio", indicator.progress == (proteinValue * 100 / proteinTarget).coerceIn(0, 100))
                    }
                }
                if (target == null || target <= 0) {
                    assertTrue("without a live target the ring stays hidden", ring.visibility == View.GONE)
                } else {
                    val ringProgress = ring.findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.targetProgressIndicator)
                    assertTrue("circular drawable uses its normalized percentage scale", ringProgress.max == 100)
                    if (consumed != null) {
                        val expectedProgress = ((consumed.toLong() * 100L) / target).toInt().coerceIn(0, 100)
                        assertTrue("ring progress must reflect recorded consumption against current target", ringProgress.progress == expectedProgress)
                        assertTrue("available consumption must show the ring", ringProgress.visibility == View.VISIBLE)
                        val remainingRing = activity.findViewById<TargetProgressRingView>(R.id.caloriesRemainingProgressRing)
                            .findViewById<com.google.android.material.progressindicator.CircularProgressIndicator>(R.id.targetProgressIndicator)
                        assertTrue("remaining ring reflects the target remainder", remainingRing.progress == ((target - consumed) * 100 / target).coerceIn(0, 100))
                    } else {
                        assertTrue("missing consumption stays unavailable, not zero", consumedText == "—")
                        assertTrue("missing intake preserves the ring placeholder without drawing a false arc", ringProgress.progress == 0)
                        assertTrue("neutral target track remains visible without consumption", ringProgress.visibility == View.VISIBLE)
                        assertTrue("existing unavailable value remains inside the ring", activity.findViewById<TextView>(R.id.caloriesConsumedText).isShown)
                    }
                    val consumedValue = activity.findViewById<TextView>(R.id.caloriesConsumedText)
                    assertTrue("existing calorie value remains visible when a target exists", consumedValue.visibility == View.VISIBLE)
                    if (consumed == null) assertTrue("missing consumption is not displayed as zero", consumedValue.text == "—")
                }
                val scroll = activity.findViewById<ScrollView>(R.id.homeScrollView)
                val card = activity.findViewById<View>(R.id.caloriesCard)
                val rect = Rect()
                card.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(card, rect)
                scroll.scrollTo(0, rect.top)
            }
            instrumentation.waitForIdleSync()
            device.takeScreenshot(File(instrumentation.targetContext.getExternalFilesDir(null), "qa-artifacts/home-calorie-ring.png").apply { parentFile?.mkdirs() })
            scenario.onActivity { activity ->
                val ids = listOf(
                    R.id.caloriesModeText, R.id.caloriesBmrValue, R.id.caloriesBiaBmrValue, R.id.caloriesTdeeValue,
                    R.id.caloriesTargetValue, R.id.caloriesTargetSourceText, R.id.caloriesConsumedText,
                    R.id.caloriesConsumedProteinText, R.id.caloriesRemainingText, R.id.caloriesConsumptionNote,
                    R.id.caloriesConsumedMaximumText, R.id.caloriesConsumedProteinMaximumText, R.id.caloriesRemainingMaximumText,
                     R.id.activityCheckInSummary,
                )
                ids.forEach { id ->
                    val view = activity.findViewById<TextView>(id)
                    val layout = view.layout
                    assertTrue("layout not ready for $id", layout != null)
                    for (line in 0 until layout.lineCount) {
                        assertTrue("ellipsized text in $id", layout.getEllipsisCount(line) == 0)
                    }
                    val parent = view.parent as ViewGroup
                    assertTrue("view $id overflows parent", view.right <= parent.width && view.left >= 0)
                }
            }
        }
    }

    private fun assertEqualsText(activity: android.app.Activity, id: Int, expected: String) {
        assertTrue("${activity.resources.getResourceEntryName(id)} should show '$expected'", activity.findViewById<TextView>(id).text.toString() == expected)
    }
}
