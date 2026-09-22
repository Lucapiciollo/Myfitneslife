package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.ui.NewBodyMeasurementActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NewBodyMeasurementDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun newMeasurementForm_isReadableOnDevice() {
        instrumentation.startActivitySync(Intent(context, NewBodyMeasurementActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })

        assertTrue(device.wait(Until.hasObject(By.text("Nuova misurazione")), 5_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/dateInput")))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/bodyWeightInput")))
        assertTrue(device.hasObject(By.text("Circonferenze")))

        val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
        device.takeScreenshot(File(dir, "new_body_measurement_top.png"))

        val scroll = device.findObject(By.clazz("android.widget.ScrollView"))
        assertTrue(scroll != null)
        device.swipe(540, 1800, 540, 500, 20)
        assertTrue(device.wait(Until.hasObject(By.text("Arti")), 2_000))
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/saveMeasurementButton")))
        device.takeScreenshot(File(dir, "new_body_measurement_bottom.png"))
    }
}
