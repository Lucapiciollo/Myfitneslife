package com.myfitai.app.e2e

import android.content.Intent
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BottomNavigationUiTest {
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
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
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/navFood")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/navProgress")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/navMore")))
    }

    @Test
    fun tabSwitching_reachesEachRootAndActiveTabTapIsNoOp() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        clickAndWait("navFood", "com.myfitai.app/.ui.FoodPlanActivity")
        clickAndWait("navProgress", "com.myfitai.app/.ui.PhysicalEvolutionActivity")
        clickAndWait("navMore", "com.myfitai.app/.ui.SettingsActivity")
        val before = focusedActivity()
        device.findObject(By.res("com.myfitai.app:id/navMore")).click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navMore")), 1_000))
        assertTrue(before == focusedActivity())
    }

    @Test
    fun backAfterTabChanges_returnsToLauncherInsteadOfPreviousTab() {
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
        clickAndWait("navFood", "com.myfitai.app/.ui.FoodPlanActivity")
        clickAndWait("navProgress", "com.myfitai.app/.ui.PhysicalEvolutionActivity")
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.sec.android.app.launcher")), 3_000))
    }

    private fun clickAndWait(id: String, activity: String) {
        device.findObject(By.res("com.myfitai.app:id/$id")).click()
        assertTrue(device.wait(Until.hasObject(By.pkg("com.myfitai.app")), 2_000))
        assertTrue(focusedActivity().contains(activity.substringAfter("/")))
    }

    private fun focusedActivity(): String = Regex("mFocusedApp=.*com\\.myfitai\\.app/([^\\s}]+)")
        .find(device.executeShellCommand("dumpsys activity activities"))
        ?.groupValues?.get(1)
        .orEmpty()

    private fun waitForApp() {
        device.wait(Until.hasObject(By.pkg("com.myfitai.app")), 5_000)
    }

    private fun dismissNotificationPermissionIfPresent() {
        val allow = device.findObject(By.text("Consenti")) ?: device.findObject(By.text("Allow"))
        allow?.click()
    }
}
