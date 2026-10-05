package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.ui.HomeActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeQuickMeasurementsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun home_exposesDirectMeasurementAndHistoryActions() {
        ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<android.widget.ScrollView>(com.myfitai.app.R.id.homeScrollView)
                    .fullScroll(android.view.View.FOCUS_DOWN)
            }
            device.waitForIdle()
            assertTrue(device.hasObject(By.res(context.packageName, "quickAddBiaButton")))
            assertTrue(device.hasObject(By.res(context.packageName, "quickAddBodyMeasurementButton")))
            assertTrue(device.hasObject(By.res(context.packageName, "quickOpenHistoryButton")))

            device.findObject(By.res(context.packageName, "quickAddBiaButton")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Bioimpedenziometria")), 5_000))
            device.pressBack()

            device.findObject(By.res(context.packageName, "quickAddBodyMeasurementButton")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Nuova misurazione")), 5_000))
            device.pressBack()

            device.findObject(By.res(context.packageName, "quickOpenHistoryButton")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Storico")), 5_000))
            device.pressBack()
        }
    }
}
