package com.myfitai.app.e2e

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.R
import com.myfitai.app.ui.HomeActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Non-destructive runtime gate for the Home metric ComposeView on the isolated AVD. */
@RunWith(AndroidJUnit4::class)
class HomeComposeMetricsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @get:Rule
    val composeRule = createAndroidComposeRule<HomeActivity>()

    @Before
    fun createOrReuseLocalAvdProfile() {
        val database = MyFitAiDatabase.getInstance(context)
        val profile = runBlocking {
            database.userProfileDao().getFirst()?.let { database.userProfileDao().get(it.id) }
                ?: run {
                    val id = database.userProfileDao().insert(
                        UserProfileEntity(
                            name = "Compose Pilot AVD",
                            birthDateEpochDay = LocalDate.of(1985, 5, 12).toEpochDay(),
                            heightCm = 186f,
                            currentWeightKg = 89f,
                            goal = "Ricomposizione",
                            activityLevel = "Moderatamente attivo",
                            wakeTimeMinutes = 420,
                            sleepTimeMinutes = 1410,
                            dietaryPreferencesJson = null,
                            createdAtEpochMillis = 1L,
                            updatedAtEpochMillis = 1L,
                            biologicalSex = "Maschio",
                            initialWeightKg = 89f,
                        ),
                    )
                    database.userProfileDao().get(id)
                }
        } ?: error("Unable to obtain the isolated AVD profile")
        ActiveProfileStore(context).selectProfile(profile.id)
    }

    @Test
    fun homeMetricsComposeView_rendersExistingLabelsValuesAndStates() {
        val device = UiDevice.getInstance(instrumentation)
        val denyNotifications = By.res("com.android.permissioncontroller:id/permission_deny_button")
        if (device.wait(Until.hasObject(denyNotifications), 1_000)) {
            device.findObject(denyNotifications).click()
        }

        composeRule.runOnIdle {
            assertTrue(composeRule.activity.findViewById<android.view.View>(R.id.dashboardMetricsPanel).isShown)
            val selector = composeRule.activity.findViewById<com.myfitai.app.ui.widgets.TimeRangeSelectorView>(R.id.timeRangeSelector)
            val selectedButton = (0 until selector.childCount)
                .map { selector.getChildAt(it) as com.google.android.material.button.MaterialButton }
                .single { it.isChecked }
            assertTrue("Home range selector defaults to 1Y", selectedButton.text.toString() == "1Y")
            val chart = composeRule.activity.findViewById<com.myfitai.app.ui.widgets.BodyMeasurementTrendView>(R.id.bodyMeasurementTrendChart)
            assertTrue("one-year range populates the real measurement chart on first render", chart.visibility == android.view.View.VISIBLE)
            assertTrue("chart receives the first real-series update without requiring range interaction", chart.hasReceivedRealSeries())
        }
        composeRule.runOnIdle {
            val metricsView = composeRule.activity.findViewById<android.view.View>(com.myfitai.app.R.id.dashboardMetricsPanel)
            assertTrue(metricsView is androidx.compose.ui.platform.ComposeView)
            assertTrue(metricsView.isShown)
        }
        composeRule.runOnUiThread {
            val metricsView = composeRule.activity.findViewById<android.view.View>(R.id.dashboardMetricsPanel)
            val scrollView = composeRule.activity.findViewById<android.widget.ScrollView>(R.id.homeScrollView)
            val metricsBounds = android.graphics.Rect()
            metricsView.getDrawingRect(metricsBounds)
            scrollView.offsetDescendantRectToMyCoords(metricsView, metricsBounds)
            scrollView.scrollTo(0, metricsBounds.top)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Peso", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Grasso", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Massa muscolare", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("89 kg").assertIsDisplayed()
        val unavailableMetrics = composeRule.onAllNodesWithText("Dati insufficienti", substring = true).fetchSemanticsNodes().size
        assertTrue("real profile history is represented either as a delta or insufficient-data state", unavailableMetrics in 0..3)
        val tileHeights = (0..2).map { index ->
            composeRule.onNodeWithTag("dashboardMetricTile$index").fetchSemanticsNode().boundsInRoot.height
        }
        assertTrue("metric tiles have equal heights", tileHeights.maxOrNull()!! - tileHeights.minOrNull()!! < 1f)
        fun heights(tag: String) = (0..2).map { index ->
            composeRule.onNodeWithTag("$tag$index").fetchSemanticsNode().boundsInRoot.height
        }
        listOf("dashboardMetricIcon", "dashboardMetricLabel", "dashboardMetricValue", "dashboardMetricDelta").forEach { tag ->
            val rowHeights = heights(tag)
            assertTrue("$tag row is aligned across all three cards", rowHeights.maxOrNull()!! - rowHeights.minOrNull()!! < 1f)
        }
        val screenshotFile = File(context.cacheDir, "home-compose-pilot.png")
        assertTrue("Unable to capture Home pilot screenshot", device.takeScreenshot(screenshotFile, 1f, 90))
        device.executeShellCommand("screencap -p /sdcard/Download/myfitai-home-compose-pilot.png")
        val screenshot = android.graphics.BitmapFactory.decodeFile(screenshotFile.absolutePath)
        assertTrue("Home screenshot should have non-zero dimensions", screenshot != null && screenshot.width > 0 && screenshot.height > 0)
        val expectedAccent = context.getColor(R.color.accent_green)
        val pixels = IntArray(screenshot!!.width * screenshot.height)
        screenshot.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
        assertTrue("Compose metric screen should render the green hero from the existing design", pixels.count { it == expectedAccent } > 0)
    }
}
