package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.ui.SettingsActivity
import com.myfitai.app.ui.ShoppingListActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DialogThemeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun shoppingListFirstOpenDialog_hasLeftAlignedOptions() {
        ActivityScenario.launch<ShoppingListActivity>(Intent(context, ShoppingListActivity::class.java).apply {
            putExtra(ShoppingListActivity.EXTRA_WEEK_START_EPOCH_DAY, LocalDate.of(2026, 8, 31).toEpochDay())
        }).use {
            assertTrue(device.wait(Until.hasObject(By.text("Salvare la lista della spesa?")), 8_000))
            assertTrue(device.hasObject(By.text("Annulla")))
            assertTrue(device.hasObject(By.text("Conferma")))
            device.findObject(By.text("Annulla")).click()
        }
    }

    @Test
    fun privacyDialog_hasReadableTitleAndMessage() {
        ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.text("Privacy e dati")), 5_000))
            device.findObject(By.text("Privacy e dati")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Privacy e dati")), 3_000))
            assertTrue(device.hasObject(By.textContains("storage locale")))
            assertTrue(device.hasObject(By.text("OK")))
            device.findObject(By.text("OK")).click()
        }
    }
}
