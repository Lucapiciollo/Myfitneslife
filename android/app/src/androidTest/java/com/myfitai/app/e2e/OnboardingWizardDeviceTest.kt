package com.myfitai.app.e2e

import android.content.Intent
import com.myfitai.app.qa.QaSeederActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import android.util.Log
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.ui.OnboardingWizardActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingWizardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun reprofileWizard_navigatesWithoutSaving() {
        instrumentation.startActivitySync(Intent(context, QaSeederActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("SEED_DEMO_12_MONTHS")), 5_000))
        device.findObject(By.text("SEED_DEMO_12_MONTHS")).click()
        assertTrue(waitForDemoSeed())

        val profileId = runBlocking { AppDataContainer.get(context).userProfileRepository.getFirst()?.id }
        assertTrue(profileId != null)
        instrumentation.startActivitySync(Intent(context, OnboardingWizardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(OnboardingWizardActivity.EXTRA_PROFILE_ID, profileId)
        })

        assertTrue(device.wait(Until.hasObject(By.text("Il tuo profilo")), 5_000))
        assertTrue(device.hasObject(By.text("Passo 1 di 4")))
        assertTrue(device.hasObject(By.text("Demo 12 mesi")))
        assertTrue(device.hasObject(By.text("Maschio")))

        val nextButton = device.findObject(By.res("com.myfitai.app:id/nextButton"))
        UiScrollable(UiSelector().className("android.widget.ScrollView")).scrollToEnd(10)
        assertTrue(nextButton != null && nextButton.isEnabled)
        val bounds = nextButton.visibleBounds
        assertTrue("nextButton is under navigation bar: $bounds", bounds.bottom < device.displayHeight - 120)
        nextButton.click()
        device.waitForIdle()
        device.findObject(By.res("com.myfitai.app:id/validationMessage"))?.text?.let {
            Log.i("MyFitAI.OnboardingTest", "validation=$it")
        }
        val advanced = device.wait(Until.hasObject(By.text("Pasti e giornata")), 5_000)
        if (!advanced) {
            val validationText = device.findObject(By.res("com.myfitai.app:id/validationMessage"))?.text
            throw AssertionError("Wizard did not advance. validation=$validationText")
        }
        device.findObject(By.text("Continua")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Piano settimanale e notifiche")), 5_000))
        device.findObject(By.text("Continua")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Intelligenza artificiale")), 5_000))
        assertTrue(device.hasObject(By.text("Salva e continua")))

        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("Piano settimanale e notifiche")), 5_000))
    }

    private fun waitForDemoSeed(): Boolean {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            val status = device.findObject(By.desc("qa_status"))
            if (status != null && status.text.contains("demo=")) return true
            Thread.sleep(250)
        }
        return false
    }
}
