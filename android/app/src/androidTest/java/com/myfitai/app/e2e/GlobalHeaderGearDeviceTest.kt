package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderAccess
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.TabHostActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** La rotellina "Impostazioni IA" è presente, visibile e funzionante in Home, Cibo, Progresso e Altro. */
@RunWith(AndroidJUnit4::class)
class GlobalHeaderGearDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var scenario: ActivityScenario<TabHostActivity>

    @Before
    fun setUp() {
        val database = MyFitAiDatabase.getInstance(context)
        val profile = runBlocking {
            database.userProfileDao().getFirst() ?: database.userProfileDao().insert(
                UserProfileEntity(
                    name = "UI Gear Test",
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
                ),
            ).let { database.userProfileDao().get(it)!! }
        }
        ActiveProfileStore(context).selectProfile(profile.id)
        scenario = ActivityScenario.launch(
            Intent(context, TabHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    private fun rootGear(tab: BottomNavBinder.Tab): View {
        var gear: View? = null
        scenario.onActivity { host -> host.selectTab(tab) }
        val deadline = System.currentTimeMillis() + 8_000
        while (gear == null && System.currentTimeMillis() < deadline) {
            scenario.onActivity { host -> gear = host.currentTabActivity()?.findViewById<View>(R.id.aiSettingsButton) }
            if (gear == null) Thread.sleep(100)
        }
        assertTrue("Impostazioni IA gear missing on $tab", gear != null)
        return gear!!
    }

    @Test
    fun gearIsVisibleAndLargeEnoughOnAllFourRoots() {
        BottomNavBinder.Tab.entries.forEach { tab ->
            rootGear(tab)
            scenario.onActivity { host ->
                val gear = host.currentTabActivity()!!.findViewById<View>(R.id.aiSettingsButton)
                val minPx = host.resources.getDimensionPixelSize(R.dimen.icon_button_size)
                assertTrue("Gear must be shown on $tab", gear.isShown)
                assertTrue("Gear on $tab must be at least 48dp wide: ${gear.width}", gear.width >= minPx)
                assertTrue("Gear on $tab must be at least 48dp tall: ${gear.height}", gear.height >= minPx)
                assertTrue(gear.isClickable && gear.isFocusable)
                assertTrue(
                    "Gear on $tab needs the shared accessible name",
                    gear.contentDescription.toString().startsWith("Impostazioni IA"),
                )
                assertTrue(host.currentTabActivity()!!.findViewById<View>(R.id.globalProfileHeader).isShown)
            }
        }
    }

    @Test
    fun gearNameAndBadgeFollowProviderState() {
        BottomNavBinder.Tab.entries.forEach { tab ->
            rootGear(tab)
            scenario.onActivity { host ->
                val root = host.currentTabActivity()!!
                val gear = root.findViewById<View>(R.id.aiSettingsButton)
                val badge = root.findViewById<View>(R.id.aiSettingsBadge)
                val needsAttention = AiProviderAccess.status(root).needsAttention
                if (needsAttention) {
                    assertEquals("Impostazioni IA. Provider non configurato", gear.contentDescription.toString())
                    assertEquals(View.VISIBLE, badge.visibility)
                } else {
                    assertEquals("Impostazioni IA", gear.contentDescription.toString())
                    assertEquals(View.GONE, badge.visibility)
                }
                assertEquals("Impostazioni IA, valide per tutti i profili", gear.tooltipText?.toString())
            }
        }
    }

    @Test
    fun gearOpensAiSettingsAndBackReturnsToTheSameTab() {
        BottomNavBinder.Tab.entries.forEach { tab ->
            rootGear(tab)
            scenario.onActivity { host -> host.currentTabActivity()!!.findViewById<View>(R.id.aiSettingsButton).performClick() }
            assertTrue(
                "Gear on $tab must open Impostazioni IA",
                device.wait(Until.hasObject(By.res("com.myfitai.app:id/aiScopeBanner")), 8_000),
            )
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/navHome")), 5_000))
            scenario.onActivity { host ->
                assertEquals(
                    "Back must return to the tab the gear was tapped from",
                    tab.name,
                    BottomNavBinder.Tab.entries.first { host.findViewById<View>(tabViewId(it)).isSelected }.name,
                )
            }
        }
    }

    private fun tabViewId(tab: BottomNavBinder.Tab): Int = when (tab) {
        BottomNavBinder.Tab.HOME -> R.id.navHome
        BottomNavBinder.Tab.FOOD -> R.id.navFood
        BottomNavBinder.Tab.PROGRESS -> R.id.navProgress
        BottomNavBinder.Tab.MORE -> R.id.navMore
    }
}
