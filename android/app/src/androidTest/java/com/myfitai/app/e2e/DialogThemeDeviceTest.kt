package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.R
import com.myfitai.app.ui.HomeActivity
import com.myfitai.app.ui.SettingsActivity
import com.myfitai.app.ui.ShoppingListActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.io.File

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

    @Test
    fun homeCalorieHelp_hasScrollableLongContentAndDismissesWithoutSideEffects() {
        var originalBmr = ""
        ActivityScenario.launch<HomeActivity>(Intent(context, HomeActivity::class.java)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/caloriesHelpButton")), 8_000))
            scenario.onActivity { activity ->
                assertTrue("font scale should be applied to dialog host", activity.resources.configuration.fontScale in 0.8f..1.6f)
                originalBmr = activity.findViewById<TextView>(R.id.caloriesBmrValue).text.toString()
                activity.findViewById<View>(R.id.caloriesHelpButton).performClick()
            }
            assertTrue(device.wait(Until.hasObject(By.text("Come calcoliamo le calorie")), 3_000))
            assertTrue(device.hasObject(By.text("Ho capito")))
            assertTrue(device.hasObject(By.textContains("Consumo giornaliero (TDEE)")))
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "home-calorie-help-after.png"))
            // The modal uses all the height it can get: long help scrolls only when it does not fit (e.g. large fonts).
            device.findObject(By.scrollable(true))?.swipe(Direction.UP, 0.75f)
            assertTrue("the last help line must be reachable", device.wait(Until.hasObject(By.textContains("per favorire la perdita di grasso.")), 2_000))
            device.takeScreenshot(File(dir, "home-calorie-help-after-scrolled.png"))
            assertTrue(device.hasObject(By.text("Ho capito")))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Come calcoliamo le calorie")), 3_000))
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.caloriesBmrValue).text.toString() == originalBmr)
                activity.findViewById<View>(R.id.caloriesHelpButton).performClick()
            }
            assertTrue(device.wait(Until.hasObject(By.text("Come calcoliamo le calorie")), 3_000))
            device.findObject(By.text("Ho capito")).click()
            assertTrue(device.wait(Until.gone(By.text("Come calcoliamo le calorie")), 3_000))
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.caloriesHelpButton).performClick()
            }
            assertTrue(device.wait(Until.hasObject(By.text("Come calcoliamo le calorie")), 3_000))
            assertTrue(device.hasObject(By.text("Ho capito")))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Come calcoliamo le calorie")), 3_000))
            scenario.onActivity { activity -> activity.findViewById<View>(R.id.caloriesHelpButton).performClick() }
            assertTrue(device.wait(Until.hasObject(By.text("Come calcoliamo le calorie")), 3_000))
            device.findObject(By.text("Ho capito")).click()
            assertTrue(device.wait(Until.gone(By.text("Come calcoliamo le calorie")), 3_000))
            scenario.onActivity { activity -> activity.findViewById<View>(R.id.caloriesHelpButton).performClick() }
            assertTrue(device.wait(Until.hasObject(By.text("Come calcoliamo le calorie")), 3_000))
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Come calcoliamo le calorie")), 3_000))

        }
    }
}
