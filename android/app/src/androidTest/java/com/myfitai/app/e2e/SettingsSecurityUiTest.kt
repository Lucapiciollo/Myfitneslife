package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.Until
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.SettingsActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSecurityUiTest {
    private lateinit var device: UiDevice
    private lateinit var scenario: ActivityScenario<SettingsActivity>
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setUp() {
        device = UiDevice.getInstance(instrumentation)
        val database = MyFitAiDatabase.getInstance(context)
        val profile = runBlocking {
            database.userProfileDao().getFirst() ?: database.userProfileDao().insert(UserProfileEntity(
                name = "UI Settings Test",
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
            )).let { database.userProfileDao().get(it)!! }
        }
        ActiveProfileStore(context).selectProfile(profile.id)
        scenario = ActivityScenario.launch(SettingsActivity::class.java)
        scenario.onActivity { activity ->
            assertTrue(activity.findViewById<TextView>(com.myfitai.app.R.id.activeProviderText).text.isNotBlank())
        }
    }

    @org.junit.After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun settings_showsUnconfiguredProviderAndProtectedKeyStatus() {
        scenario.onActivity { activity ->
            val providerText = activity.findViewById<TextView>(com.myfitai.app.R.id.activeProviderText).text.toString()
            assertTrue(providerText.contains("Provider") || providerText.contains("Gemini"))
            val geminiStatus = activity.findViewById<TextView>(com.myfitai.app.R.id.geminiKeyStatusText).text.toString()
            val openAiStatus = activity.findViewById<TextView>(com.myfitai.app.R.id.openAiKeyStatusText).text.toString()
            assertTrue(geminiStatus.contains("Gemini") || openAiStatus.contains("OpenAI"))
            val providerSwitch = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(com.myfitai.app.R.id.useGeminiSwitch)
            if (providerSwitch.isChecked) providerSwitch.performClick()
            assertTrue(activity.findViewById<View>(com.myfitai.app.R.id.openAiApiKeyInput).visibility == View.VISIBLE)
        }
    }

    @Test
    fun privacyDialog_explainsLocalStorageBackupAndKeystore() {
        scenario.onActivity { activity ->
            activity.findViewById<View>(com.myfitai.app.R.id.rowPrivacy).performClick()
        }
        assertTrue(device.wait(Until.hasObject(By.text("Privacy e dati")), 2_000))
        assertTrue(device.hasObject(By.textContains("storage locale")))
        assertTrue(device.hasObject(By.textContains("Android Keystore")))
        device.findObject(By.text("OK")).click()
    }

    private fun hasOpenAiSecurityStatus(): Boolean =
        device.hasObject(By.textContains("Chiave OpenAI non configurata")) ||
            device.hasObject(By.textContains("OpenAI configurato"))
}
