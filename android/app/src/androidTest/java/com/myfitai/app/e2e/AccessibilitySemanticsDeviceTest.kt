package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.MeasurementsActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun seedProfile() {
        val profileId = kotlinx.coroutines.runBlocking {
            MyFitAiDatabase.getInstance(context).userProfileDao().getFirst()?.id
        }
        if (profileId != null) ActiveProfileStore(context).selectProfile(profileId)
    }

    @Test
    fun measurementsScreen_exposesReadableNavigationAndContent() {
        ActivityScenario.launch<MeasurementsActivity>(Intent(context, MeasurementsActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.text("Rilevazioni")), 5_000))
            assertTrue(device.hasObject(By.desc("Home")))
            assertTrue(device.hasObject(By.desc("Alimentazione")))
            assertTrue(device.hasObject(By.desc("Progresso")))
            assertTrue(device.hasObject(By.desc("Altro")))
        }
    }
}
