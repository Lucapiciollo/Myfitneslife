package com.myfitai.app.e2e

import android.content.Intent
import android.view.View
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
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

    @Test
    fun deleteProfileAction_isAvailableInDataManagement() {
        scenario.onActivity { activity ->
            val row = activity.findViewById<View>(com.myfitai.app.R.id.rowDeleteProfile)
            assertTrue(row.visibility == View.VISIBLE)
            assertTrue(row.isEnabled)
            assertTrue(row.isClickable)
        }
    }

    @Test
    fun deleteDataUsesSingleEntryAndShowsCategoryPicker() {
        scenario.onActivity { activity ->
            val deleteRow = activity.findViewById<View>(com.myfitai.app.R.id.rowDeleteRecordedData)
            assertTrue(deleteRow.visibility == View.VISIBLE)
            assertTrue(deleteRow.isEnabled)
            assertTrue(activity.resources.getIdentifier("rowDeletePlans", "id", activity.packageName) == 0)
            deleteRow.performClick()
        }
        assertTrue(device.wait(Until.hasObject(By.text("Scegli cosa eliminare")), 3_000))
        val deletionList = UiScrollable(UiSelector().scrollable(true))
        assertTrue(deletionList.scrollIntoView(UiSelector().descriptionContains("piani alimentari salvati")))
        assertTrue(deletionList.scrollIntoView(UiSelector().descriptionContains("misurazioni BIA")))
        assertTrue(deletionList.scrollIntoView(UiSelector().descriptionContains("tutti i dati di attività")))
        device.pressBack()
    }

    @Test
    fun threeActionDialog_keepsAllButtonsOnOneBottomRow() {
        scenario.onActivity { activity ->
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Vuoi salvare i dati prima di procedere alla cancellazione del profilo?")
                .setMessage("Conferma il comportamento della barra azioni con tre pulsanti.")
                .setNegativeButton("Annulla", null)
                .setNeutralButton("Senza backup", null)
                .setPositiveButton("Salva backup", null)
                .show()
        }

        val cancel = By.text("Annulla")
        val withoutBackup = By.text("Senza backup")
        val saveBackup = By.text("Salva backup")
        assertTrue(device.wait(Until.hasObject(cancel), 2_000))
        assertTrue(device.hasObject(withoutBackup))
        assertTrue(device.hasObject(saveBackup))
        val cancelBounds = device.findObject(cancel).visibleBounds
        val withoutBackupBounds = device.findObject(withoutBackup).visibleBounds
        val saveBackupBounds = device.findObject(saveBackup).visibleBounds
        val rowTolerancePx = (8 * context.resources.displayMetrics.density).toInt()
        assertTrue("Cancel/neutral buttons should share the action row: $cancelBounds vs $withoutBackupBounds", kotlin.math.abs(cancelBounds.centerY() - withoutBackupBounds.centerY()) <= rowTolerancePx)
        assertTrue("Cancel/positive buttons should share the action row: $cancelBounds vs $saveBackupBounds", kotlin.math.abs(cancelBounds.centerY() - saveBackupBounds.centerY()) <= rowTolerancePx)
        device.pressBack()
    }

    @Test
    fun allSwitches_shareGreenEnabledAndGreyDisabledTints() {
        scenario.onActivity { activity ->
            val switches = mutableListOf<MaterialSwitch>()
            fun collect(view: View) {
                if (view is MaterialSwitch) switches += view
                if (view is android.view.ViewGroup) {
                    for (index in 0 until view.childCount) collect(view.getChildAt(index))
                }
            }
            collect(activity.findViewById(android.R.id.content))
            assertTrue("Expected the Settings screen to contain switches", switches.isNotEmpty())
            val enabledChecked = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked)
            val enabledUnchecked = intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked)
            val disabledChecked = intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked)
            val expectedGreen = activity.getColor(com.myfitai.app.R.color.accent_green)
            val expectedEnabledTrack = activity.getColor(com.myfitai.app.R.color.switch_unchecked_track)
            val expectedDisabled = activity.getColor(com.myfitai.app.R.color.switch_disabled_track)
            switches.forEach { switch ->
                assertNotNull("Switch track tint list missing", switch.trackTintList)
                assertEquals(expectedGreen, switch.trackTintList!!.getColorForState(enabledChecked, 0))
                assertEquals(expectedEnabledTrack, switch.trackTintList!!.getColorForState(enabledUnchecked, 0))
                assertEquals(expectedDisabled, switch.trackTintList!!.getColorForState(disabledChecked, 0))
            }
        }
    }

    private fun hasOpenAiSecurityStatus(): Boolean =
        device.hasObject(By.textContains("Chiave OpenAI non configurata")) ||
            device.hasObject(By.textContains("OpenAI configurato"))
}
