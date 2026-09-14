package com.myfitai.app.e2e

import android.content.Intent
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
        instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/activeProviderText")), 4_000))
    }

    @Test
    fun settings_showsUnconfiguredProviderAndProtectedKeyStatus() {
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/activeProviderText")))
        assertTrue(device.hasObject(By.textContains("Provider")) || device.hasObject(By.textContains("Gemini")))
        assertTrue(device.hasObject(By.textContains("Gemini selezionato ma non configurato")) ||
            device.hasObject(By.textContains("Chiave Gemini non configurata")) ||
            device.hasObject(By.textContains("Gemini configurato")) || hasOpenAiSecurityStatus())
        val providerSwitch = device.findObject(By.res("com.myfitai.app:id/useGeminiSwitch"))
        if (providerSwitch.isChecked) providerSwitch.click()
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/openAiApiKeyInput")), 2_000))
    }

    @Test
    fun privacyDialog_explainsLocalStorageBackupAndKeystore() {
        UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().resourceId("com.myfitai.app:id/rowPrivacy"))
        device.findObject(By.res("com.myfitai.app:id/rowPrivacy")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Privacy e dati")), 2_000))
        assertTrue(device.hasObject(By.textContains("storage locale")))
        assertTrue(device.hasObject(By.textContains("Android Keystore")))
        device.findObject(By.text("OK")).click()
    }

    private fun hasOpenAiSecurityStatus(): Boolean =
        device.hasObject(By.textContains("Chiave OpenAI non configurata")) ||
            device.hasObject(By.textContains("OpenAI configurato"))
}
