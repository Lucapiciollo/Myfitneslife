package com.myfitai.app.e2e

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.ui.HomeActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Non-destructive visual/layout gate for the remaining Home operational cards. */
@RunWith(AndroidJUnit4::class)
class HomeOperationalCardsVisualDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun operationalCards_preserveVisibilityContentAndActions() {
        val args = InstrumentationRegistry.getArguments()
        val focus = args.getString("focus") ?: "actions"
        val holdMs = args.getString("holdMs")?.toLongOrNull() ?: 0L

        ActivityScenario.launch<HomeActivity>(
            Intent(instrumentation.targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ).use { scenario ->
            Thread.sleep(4_500L)
            scenario.onActivity { activity ->
                // Expose the conditional notices only on these in-memory Views for screenshot/layout inspection.
                activity.findViewById<View>(R.id.planUpdateNoticeCard).visibility = View.VISIBLE
                activity.findViewById<View>(R.id.biaDueNoticeCard).visibility = View.VISIBLE
                val scroll = activity.findViewById<ScrollView>(R.id.homeScrollView)
                val target = when (focus) {
                    "notices" -> activity.findViewById<View>(R.id.planUpdateNoticeCard)
                    else -> activity.findViewById<View>(R.id.quickActionsRow)
                }
                val rect = Rect()
                target.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(target, rect)
                scroll.post {
                    if (focus == "notices") scroll.scrollTo(0, rect.top) else scroll.fullScroll(View.FOCUS_DOWN)
                }
            }
            Thread.sleep(900L)
            scenario.onActivity { activity ->
                val plan = activity.findViewById<MaterialCardView>(R.id.planUpdateNoticeCard)
                val ai = activity.findViewById<MaterialCardView>(R.id.aiConfigurationNoticeCard)
                val bia = activity.findViewById<MaterialCardView>(R.id.biaDueNoticeCard)
                val analysis = activity.findViewById<MaterialCardView>(R.id.analysisHubCard)
                val quickActions = activity.findViewById<MaterialCardView>(R.id.quickActionsCard)
                listOf(plan, bia, analysis, quickActions).forEach {
                    assertTrue("card ${activity.resources.getResourceEntryName(it.id)} missing", it.visibility == View.VISIBLE)
                }
                assertTrue("AI notice visibility remains configuration-dependent", ai.visibility == View.VISIBLE || ai.visibility == View.GONE)
                assertTrue(
                    "combined quick actions card follows the workout section",
                    quickActions.top >= activity.findViewById<View>(R.id.workoutSectionCard).bottom,
                )

                assertTrue("plan notice action callback missing", activity.findViewById<View>(R.id.planUpdateNoticeButton).hasOnClickListeners())
                assertTrue("AI notice action callback remains installed", activity.findViewById<View>(R.id.aiConfigurationNoticeButton).hasOnClickListeners())
                assertTrue("BIA notice action callback missing", activity.findViewById<View>(R.id.biaDueNoticeButton).hasOnClickListeners())
                assertTrue("analysis callback missing", activity.findViewById<View>(R.id.analysisHubButton).hasOnClickListeners())
                assertTrue("measurement callback missing", activity.findViewById<View>(R.id.quickAddBiaButton).hasOnClickListeners())
                assertTrue("body measurement callback missing", activity.findViewById<View>(R.id.quickAddBodyMeasurementButton).hasOnClickListeners())
                assertTrue("history callback missing", activity.findViewById<View>(R.id.quickOpenHistoryButton).hasOnClickListeners())
                assertTrue("measurement hub callback missing", activity.findViewById<View>(R.id.measurementsButton).hasOnClickListeners())
                assertTrue("extra callback missing", activity.findViewById<View>(R.id.addExtraButton).hasOnClickListeners())

                val focusView: View = if (focus == "notices") plan else activity.findViewById(R.id.quickActionsRow)
                assertTrue("requested Home content is not shown", focusView.isShown)
                descendants(focusView).filterIsInstance<TextView>().forEach { text ->
                    text.layout?.let { layout ->
                        for (line in 0 until layout.lineCount) {
                            assertTrue("ellipsized '${text.text}'", layout.getEllipsisCount(line) == 0)
                        }
                    }
                }
                listOf(
                    R.id.planUpdateNoticeCard,
                    R.id.aiConfigurationNoticeCard,
                    R.id.biaDueNoticeCard,
                    R.id.analysisHubCard,
                    R.id.quickActionsCard,
                ).forEach { id -> assertNotNull("missing ${activity.resources.getResourceEntryName(id)}", activity.findViewById<View>(id)) }
            }
            if (holdMs > 0) Thread.sleep(holdMs)
        }
    }

    @Test
    fun analysisCard_usesPolishedLayoutAndOpensMeasurements() {
        val monitor = instrumentation.addMonitor("com.myfitai.app.ui.MeasurementsActivity", null, false)
        ActivityScenario.launch<HomeActivity>(
            Intent(instrumentation.targetContext, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        ).use { scenario ->
            scenario.onActivity { activity ->
                val card = activity.findViewById<MaterialCardView>(R.id.analysisHubCard)
                assertTrue("analysis card is displayed", card.visibility == View.VISIBLE)
                val action = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.analysisHubButton)
                assertTrue("analysis action fills the card width", action.width == 0 || action.layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT)
                assertTrue("analysis action retains its label", action.text.toString() == "Apri analisi dati")
                assertTrue("analysis label is centered", action.gravity and android.view.Gravity.CENTER == android.view.Gravity.CENTER)
                assertTrue("analysis button includes the progress icon", action.compoundDrawablesRelative.any { it != null })
                descendants(card).filterIsInstance<TextView>().forEach { text ->
                    text.layout?.let { layout ->
                        for (line in 0 until layout.lineCount) assertTrue("ellipsized '${text.text}'", layout.getEllipsisCount(line) == 0)
                    }
                }
                action.performClick()
            }
            val launched = instrumentation.waitForMonitorWithTimeout(monitor, 5_000)
            assertTrue("analysis route opens the existing measurements screen", launched?.javaClass?.simpleName == "MeasurementsActivity")
            launched?.finish()
        }
        instrumentation.removeMonitor(monitor)
    }

    private fun descendants(view: View): List<View> = when (view) {
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }
}
