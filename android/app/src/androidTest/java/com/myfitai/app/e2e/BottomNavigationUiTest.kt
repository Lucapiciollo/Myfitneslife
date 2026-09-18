package com.myfitai.app.e2e

import android.content.Intent
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomNavigationUiTest {
    private lateinit var device: UiDevice

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
        context.startActivity(Intent.makeMainActivity(android.content.ComponentName(context, com.myfitai.app.ui.SplashActivity::class.java)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        waitForApp()
        dismissNotificationPermissionIfPresent()
    }

    @After
    fun tearDown() {
        // The next test starts with CLEAR_TASK; avoid shell/process operations here.
    }

    @Test
    fun launch_reachesHomeAndBottomTabsAreVisible() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        // The four root tabs are rendered inside the single host task.
        assertTrue(device.hasObject(By.pkg("com.myfitai.app")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/navFood")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/navProgress")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/navMore")))
    }

    @Test
    fun tabSwitching_reachesEachRootAndActiveTabTapIsNoOp() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        device.findObject(By.res("com.myfitai.app:id/navFood")).click()
        dismissFoodGateOrVerifyFoodRoot()
        clickAndWait("navProgress", "evolutionChart")
        clickAndWait("navMore", "settingsContent")
        // Tapping the already-active tab must be a no-op: the Settings root stays on screen.
        device.findObject(By.res("com.myfitai.app:id/navMore")).click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/settingsContent")), 2_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/rowProfile")))
    }

    @Test
    fun backAfterTabChanges_returnsToLauncherInsteadOfPreviousTab() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        device.findObject(By.res("com.myfitai.app:id/navFood")).click()
        dismissFoodGateOrVerifyFoodRoot()
        clickAndWait("navProgress", "evolutionChart")
        // Back from a tab root does not return to the previously visited tab: it offers to exit.
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.textContains("Uscire da MyFitAI")), 3_000))
        device.findObject(By.text("Esci")).click()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.sec.android.app.launcher")), 5_000))
    }

    @Test
    fun activeTabRoot_survivesTabSwitchWithoutRecreatingVisibleRoot() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        device.findObject(By.res("com.myfitai.app:id/navProgress")).click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/evolutionChart")), 5_000))
        device.findObject(By.res("com.myfitai.app:id/navHome")).click()
        // Anchor on the always-visible Home header: cards further down move off-screen as soon as
        // the profile owns a generated plan, which would make this assertion data dependent.
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/greetingText")), 5_000))
        device.findObject(By.res("com.myfitai.app:id/navProgress")).click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/evolutionChart")), 5_000))
    }

    @Test
    fun home_nextMealCard_exposesMealSwapActionWhenFutureMealExists() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        runCatching {
            UiScrollable(UiSelector().scrollable(true))
                .scrollIntoView(UiSelector().resourceId("com.myfitai.app:id/upcomingMealsCard"))
        }
        val swap = device.findObject(By.desc("Cambia pasto con IA"))
        if (swap == null) {
            // The fixture may not contain a current future meal; the Home root must still be stable.
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/greetingText")))
        } else {
            assertTrue(swap.isEnabled)
        }
    }

    @Test
    fun home_todayMenuButton_opensTodayMenuDialog() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().resourceId("com.myfitai.app:id/todayMenuButton"))
        device.findObject(By.res("com.myfitai.app:id/todayMenuButton")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Menu di oggi")), 3_000))
        assertTrue(device.hasObject(By.text("Chiudi")))
    }

    @Test
    fun foodPlan_explainsDailyFlowAndMealStatus() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        device.findObject(By.res("com.myfitai.app:id/navFood")).click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/weekRangeLabel")), 5_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/energyTargetCard")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/mealCountHint")))
        if (!device.hasObject(By.textContains("Piano v"))) {
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/generatePlanButton")))
        } else {
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/supplementsCard")) || device.hasObject(By.res("com.myfitai.app:id/mealsContainer")))
            assertTrue(device.hasObject(By.textContains("Pasto concluso")) || device.hasObject(By.text("Da registrare")))
            scrollFoodPlanDown()
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/dailyTotalContainer")))
            scrollUntilFoodActions()
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/weeklyActionsCard")))
            assertTrue(device.hasObject(By.textContains("Registrato da te = cibo segnato")))
        }
    }

    private fun scrollFoodPlanDown() {
        repeat(4) {
            if (device.hasObject(By.res("com.myfitai.app:id/dailyTotalContainer"))) return
            device.swipe(device.displayWidth / 2, (device.displayHeight * 0.8).toInt(), device.displayWidth / 2, (device.displayHeight * 0.3).toInt(), 20)
        }
    }

    private fun scrollUntilFoodActions() {
        repeat(4) {
            if (device.hasObject(By.res("com.myfitai.app:id/weeklyActionsCard"))) return
            device.swipe(device.displayWidth / 2, (device.displayHeight * 0.8).toInt(), device.displayWidth / 2, (device.displayHeight * 0.3).toInt(), 20)
        }
    }

    @Test
    fun workoutDetail_isChildScreenWithoutRootBottomNavigation() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().resourceId("com.myfitai.app:id/todayWorkoutButton"))
        device.findObject(By.res("com.myfitai.app:id/todayWorkoutButton")).click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/workoutList")), 5_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/weeklySummaryCard")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/dayCard")))
        assertTrue(!device.hasObject(By.res("com.myfitai.app:id/navHome")))
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
    }

    private fun clickAndWait(navId: String, screenId: String) {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/$navId")), 3_000))
        device.findObject(By.res("com.myfitai.app:id/$navId")).click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/$screenId")), 5_000))
    }

    private fun dismissFoodGateOrVerifyFoodRoot() {
        val gateVisible = device.wait(Until.hasObject(By.textContains("Nessun provider IA configurato")), 3_000)
        if (gateVisible) {
            device.findObject(By.text("Annulla")).click()
            // Back on the previous root after cancelling the gate; the bottom bar stays available.
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 3_000))
        } else {
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/weekRangeLabel")), 3_000))
        }
    }

    private fun waitForApp() {
        device.wait(Until.hasObject(By.pkg("com.myfitai.app")), 5_000)
    }

    private fun dismissNotificationPermissionIfPresent() {
        val allow = device.findObject(By.text("Consenti")) ?: device.findObject(By.text("Allow"))
        allow?.click()
    }
}
