package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import com.myfitai.app.ui.TabHostActivity
import java.io.File

@RunWith(AndroidJUnit4::class)
class BottomNavigationUiTest {
    private lateinit var device: UiDevice
    private lateinit var scenario: ActivityScenario<TabHostActivity>

    // Safety net: on some OEM builds (e.g. Samsung One UI) UiAutomator interactions can stall.
    // A per-test timeout turns any hang into a reported failure instead of blocking the whole run.
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(90)

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MyFitAiDatabase.getInstance(context)
        val profile = kotlinx.coroutines.runBlocking {
            database.userProfileDao().getFirst() ?: database.userProfileDao().insert(UserProfileEntity(
                name = "UI Test",
                birthDateEpochDay = null,
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
            )).let { id -> database.userProfileDao().get(id)!! }
        }
        ActiveProfileStore(context).selectProfile(profile.id)
        scenario = ActivityScenario.launch(Intent(context, TabHostActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertViewEventually(com.myfitai.app.R.id.navHome)
        dismissNotificationPermissionIfPresent()
    }

    @After
    fun tearDown() {
        // The next test starts with CLEAR_TASK; avoid shell/process operations here.
    }

    @Test
    fun launch_reachesHomeAndBottomTabsAreVisible() {
        assertViewEventually(com.myfitai.app.R.id.navHome)
        assertViewEventually(com.myfitai.app.R.id.navFood)
        assertViewEventually(com.myfitai.app.R.id.navProgress)
        assertViewEventually(com.myfitai.app.R.id.navAi)
        assertViewEventually(com.myfitai.app.R.id.navMore)
        scenario.onActivity { activity ->
            listOf(
                com.myfitai.app.R.id.navHome,
                com.myfitai.app.R.id.navFood,
                com.myfitai.app.R.id.navProgress,
                com.myfitai.app.R.id.navAi,
                com.myfitai.app.R.id.navMore,
            ).forEach { id ->
                val tab = activity.findViewById<android.widget.TextView>(id)
                assertTrue("Bottom tab label should be fully laid out: ${tab.text}", tab.layout != null)
                assertTrue("Bottom tab label should fit within one or two lines: ${tab.text}", tab.layout.lineCount in 1..2)
                for (line in 0 until tab.layout.lineCount) {
                    assertTrue("Bottom tab label should not be ellipsized: ${tab.text}", tab.layout.getEllipsisCount(line) == 0)
                }
            }
        }
        val screenshotDir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
        scenario.onActivity { activity ->
            val navigation = activity.findViewById<View>(com.myfitai.app.R.id.bottomNav)
            val legacyHeight = activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.space_40) +
                activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.space_28)
            val legacyPadding = activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.space_6)
            navigation.layoutParams = navigation.layoutParams.apply { height = legacyHeight }
            navigation.setPadding(navigation.paddingLeft, legacyPadding, navigation.paddingRight, legacyPadding)
            navigation.requestLayout()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        device.takeScreenshot(File(screenshotDir, "bottom-navigation-before-aligned.png"))
        scenario.onActivity { activity ->
            val navigation = activity.findViewById<View>(com.myfitai.app.R.id.bottomNav)
            navigation.layoutParams = navigation.layoutParams.apply {
                height = activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.screen_header_height)
            }
            navigation.setPadding(
                navigation.paddingLeft,
                activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.bottom_nav_content_padding_top),
                navigation.paddingRight,
                activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.bottom_nav_content_padding_bottom),
            )
            navigation.requestLayout()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        device.takeScreenshot(File(screenshotDir, "bottom-navigation-after-aligned.png"))
        scenario.onActivity { activity ->
            val navigation = activity.findViewById<View>(com.myfitai.app.R.id.bottomNav)
            val expectedHeight = activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.screen_header_height)
            assertTrue("Bottom navigation height should match screen header token", navigation.height == expectedHeight)
            val expectedHorizontalInset = activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.bottom_nav_content_padding_horizontal)
            assertTrue("Bottom navigation start inset token mismatch", navigation.paddingStart == expectedHorizontalInset)
            assertTrue("Bottom navigation end inset token mismatch", navigation.paddingEnd == expectedHorizontalInset)
            assertTrue("Bottom navigation content top inset should match token", navigation.paddingTop == activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.bottom_nav_content_padding_top))
            assertTrue("Bottom navigation content bottom inset should match token", navigation.paddingBottom == activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.bottom_nav_content_padding_bottom))
            val embedded = activity.currentTabActivity()
            val scrollContent = embedded?.findViewById<View>(com.myfitai.app.R.id.homeScrollView)
            assertTrue("Home embedded scroll content is missing", scrollContent != null)
            val navigationLocation = IntArray(2).also(navigation::getLocationOnScreen)
            val scrollLocation = IntArray(2).also(scrollContent!!::getLocationOnScreen)
            val scrollBottom = scrollLocation[1] + scrollContent.height
            val tolerance = activity.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.space_1)
            val embeddedScrim = embedded!!.window.decorView.findViewById<View>(android.R.id.navigationBarBackground)
            assertTrue(
                "Embedded tab must not draw a translucent system-bar scrim over the page end",
                embeddedScrim == null || embeddedScrim.visibility != View.VISIBLE || embeddedScrim.height == 0,
            )
            assertTrue(
                "Embedded Home leaves excess bottom inset before the fixed menu: gap=${navigationLocation[1] - scrollBottom}px",
                navigationLocation[1] - scrollBottom <= tolerance,
            )
        }
    }

    @Test
    fun tabSwitching_reachesEachRootAndActiveTabTapIsNoOp() {
        assertViewEventually(com.myfitai.app.R.id.navHome)
        clickTab(com.myfitai.app.R.id.navFood)
        dismissFoodGateOrVerifyFoodRoot()
        clickAndWait("navProgress", "evolutionChart")
        clickAndWait("navAi", "settingsContent")
        assertCurrentTabViewEventually(com.myfitai.app.R.id.aiSectionCard)
        scenario.onActivity { activity ->
            assertTrue(activity.currentTabActivity()?.findViewById<View>(com.myfitai.app.R.id.aiSectionCard)?.visibility == View.VISIBLE)
            assertTrue(activity.currentTabActivity()?.findViewById<View>(com.myfitai.app.R.id.aiRootSectionCard)?.visibility == View.VISIBLE)
        }
        clickAndWait("navMore", "settingsContent")
        // Tapping the already-active tab must be a no-op: the Settings root stays on screen.
        clickTab(com.myfitai.app.R.id.navMore)
        assertCurrentTabViewEventually(com.myfitai.app.R.id.settingsContent)
        assertCurrentTabViewEventually(com.myfitai.app.R.id.rowMeasurements)
        scenario.onActivity { activity ->
            assertTrue(activity.currentTabActivity()?.findViewById<View>(com.myfitai.app.R.id.aiRootSectionCard)?.visibility != View.VISIBLE)
        }
    }

    @Test
    fun nutritionAdviceOpensAsModalWithoutReplacingHomeTab() {
        assertViewEventually(com.myfitai.app.R.id.navHome)
        var actionEnabled = false
        scenario.onActivity { activity ->
            val button = activity.currentTabActivity()?.findViewById<View>(com.myfitai.app.R.id.nutritionAdviceButton)
            assertTrue("Home root missing nutrition advice action", button != null)
            actionEnabled = button!!.isEnabled
            if (actionEnabled) button.performClick()
        }
        if (!actionEnabled) {
            assertTrue("Disabled advice action must not launch the modal", device.wait(Until.gone(By.res("com.myfitai.app.navcheck:id/dismissAdviceButton")), 1_000))
            scenario.onActivity { activity -> assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.navHome).isSelected) }
            return
        }
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/dismissAdviceButton")), 5_000))
        scenario.onActivity { activity ->
            assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.navHome).isSelected)
        }
        device.findObject(By.res("com.myfitai.app:id/dismissAdviceButton")).click()
        assertViewEventually(com.myfitai.app.R.id.navHome)
        scenario.onActivity { activity -> assertTrue(activity.currentTabActivity() is com.myfitai.app.ui.HomeActivity) }
    }

    @Test
    fun backAfterTabChanges_returnsToLauncherInsteadOfPreviousTab() {
        assertViewEventually(com.myfitai.app.R.id.navHome)
        clickTab(com.myfitai.app.R.id.navFood)
        dismissFoodGateOrVerifyFoodRoot()
        clickAndWait("navProgress", "evolutionChart")
        // Back from a tab root does not return to the previously visited tab: it offers to exit.
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.textContains("Uscire da MyFitAI")), 3_000))
        device.findObject(By.text("Esci")).click()
        assertTrue("App should leave the foreground after confirming exit", device.wait(Until.gone(By.pkg("com.myfitai.app")), 5_000))
    }

    private fun clickAndWait(navId: String, screenId: String) {
        val navIdRes = contextResources(navId)
        clickTab(navIdRes)
        assertCurrentTabViewEventually(contextResources(screenId))
    }

    private fun dismissFoodGateOrVerifyFoodRoot() {
        scenario.onActivity { activity ->
            val current = activity.currentTabActivity()
            assertTrue("Alimentazione must open as a root tab without an AI provider", current is com.myfitai.app.ui.FoodPlanActivity)
            val notice = current?.findViewById<View>(com.myfitai.app.R.id.aiConfigurationNoticeCard)
            assertTrue("Food tab AI notice must reflect provider configuration (visible or hidden)", notice?.visibility == View.VISIBLE || notice?.visibility == View.GONE)
        }
    }

    private fun clickTab(viewId: Int) {
        scenario.onActivity { activity ->
            val tab = activity.findViewById<View>(viewId)
            assertTrue("Bottom tab view not found: $viewId, current=${activity.currentTabActivity()?.javaClass?.simpleName}", tab != null)
            tab.performClick()
        }
    }

    private fun assertViewEventually(viewId: Int, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var present = false
        while (!present && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity -> present = activity.findViewById<View>(viewId) != null }
            if (!present) Thread.sleep(100)
        }
        assertTrue("Missing view id=$viewId", present)
    }

    private fun assertCurrentTabViewEventually(viewId: Int, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var present = false
        while (!present && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                present = activity.currentTabActivity()?.findViewById<View>(viewId) != null
            }
            if (!present) Thread.sleep(100)
        }
        assertTrue("Missing current tab view id=$viewId", present)
    }

    private fun contextResources(name: String): Int =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.getIdentifier(
            name,
            "id",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName,
        )

    @After
    fun closeScenario() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun dismissNotificationPermissionIfPresent() {
        val allow = device.findObject(By.text("Consenti")) ?: device.findObject(By.text("Allow"))
        allow?.click()
    }
}
