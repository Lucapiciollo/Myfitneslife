package com.myfitai.app.e2e

import android.content.Intent
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
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
