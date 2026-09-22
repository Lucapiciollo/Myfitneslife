package com.myfitai.app.e2e

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.R
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.ProfileEditActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfileEditDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun seedProfile() {
        val database = MyFitAiDatabase.getInstance(context)
        val profileId = runBlocking {
            database.userProfileDao().getFirst()?.id ?: database.userProfileDao().insert(
                UserProfileEntity(
                    name = "Profile UI Test",
                    birthDateEpochDay = 10_000L,
                    heightCm = 180f,
                    currentWeightKg = 82f,
                    goal = "Ricomposizione",
                    activityLevel = "Moderatamente attivo",
                    wakeTimeMinutes = 420,
                    sleepTimeMinutes = 1_380,
                    dietaryPreferencesJson = null,
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    biologicalSex = "Maschio",
                    initialWeightKg = 82f,
                ),
            )
        }
        ActiveProfileStore(context).selectProfile(profileId)
    }

    @Test
    fun profileEditForm_isReadableWithoutSaving() {
        ActivityScenario.launch<ProfileEditActivity>(Intent(context, ProfileEditActivity::class.java)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.textContains("Modifica")), 5_000))
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/nameInput")))
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/birthDateInput")))
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/heightInput")))
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/weightInput")))
            assertTrue(device.hasObject(By.text("Dati personali")))

            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "profile_edit_top.png"))

            scenario.onActivity { activity ->
                val scroll = activity.findViewById<androidx.core.widget.NestedScrollView>(R.id.profileEditScroll)
                assertTrue(activity.findViewById<android.view.View>(R.id.preferredFoodsInput).visibility == android.view.View.VISIBLE)
                assertTrue(activity.findViewById<android.view.View>(R.id.saveProfileButton).visibility == android.view.View.VISIBLE)
                scroll.fullScroll(android.view.View.FOCUS_DOWN)
            }
            assertTrue(device.hasObject(By.res("com.myfitai.app:id/saveProfileButton")))
            device.takeScreenshot(File(dir, "profile_edit_bottom.png"))
        }
    }
}
