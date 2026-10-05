package com.myfitai.app.e2e

import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.google.android.material.imageview.ShapeableImageView
import com.myfitai.app.R
import com.myfitai.app.ui.ProfileActivity
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Non-destructive visual checks for the profile summary. */
@RunWith(AndroidJUnit4::class)
class ProfileSummaryVisualDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun profileSummaryKeepsExistingFieldsActionsAndWrapping() {
        ActivityScenario.launch<ProfileActivity>(Intent(context, ProfileActivity::class.java)).use { scenario ->
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val avatar = activity.findViewById<ShapeableImageView>(R.id.profileAvatar)
                val name = activity.findViewById<TextView>(R.id.profileName)
                val stats = activity.findViewById<TextView>(R.id.profileStats)
                val goal = activity.findViewById<TextView>(R.id.profileGoal)
                val hint = activity.findViewById<TextView>(R.id.profileSwitcherHint)
                assertNotNull(avatar)
                listOf(name, stats, goal, hint).forEach { assertTrue(it.text.isNotBlank()) }
                assertTrue("profile name action missing", name.hasOnClickListeners())
                assertTrue("avatar action missing", avatar.hasOnClickListeners())
                listOf(R.id.rowFoodPreferences, R.id.rowNotifications, R.id.rowExport).forEach { id ->
                    assertTrue("profile row $id action missing", activity.findViewById<View>(id).hasOnClickListeners())
                }

                listOf(name, stats, goal, hint).forEach { text ->
                        text.layout?.let { layout ->
                            for (line in 0 until layout.lineCount) {
                                assertTrue("ellipsized '${text.text}'", layout.getEllipsisCount(line) == 0)
                            }
                        }
                }
            }
            instrumentation.waitForIdleSync()
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            device.takeScreenshot(File(dir, "profile-summary.png"))
            device.findObject(By.res(context.packageName, "profileName")).click()
            assertTrue(device.wait(Until.hasObject(By.textContains("Activity Smoke Test")), 2_000))
            device.pressBack()
            device.findObject(By.res(context.packageName, "profileAvatar")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Foto profilo")), 2_000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.res(context.packageName, "profileAvatar")), 2_000))
        }
    }

    @Test
    fun profileScrollRetainsSettingsRows() {
        ActivityScenario.launch<ProfileActivity>(Intent(context, ProfileActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val scroll = descendants(activity.findViewById<ViewGroup>(android.R.id.content)).filterIsInstance<ScrollView>().single()
                scroll.fullScroll(View.FOCUS_DOWN)
                listOf(R.id.rowFoodPreferences, R.id.rowNotifications, R.id.rowExport).forEach { id ->
                    assertTrue("profile row $id not reachable", activity.findViewById<View>(id).isShown)
                }
            }
        }
    }

    private fun descendants(view: View): List<View> = when (view) {
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }
}
