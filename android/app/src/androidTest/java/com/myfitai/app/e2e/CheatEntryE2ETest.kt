package com.myfitai.app.e2e

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.ui.CheatEntryActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CheatEntryE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var device: UiDevice

    @Before
    fun seedProfileAndOpenScreen() {
        device = UiDevice.getInstance(instrumentation)
        context.startActivity(Intent().apply {
            component = ComponentName(context.packageName, "${context.packageName}.qa.QaSeederActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.text("SEED_6_MONTHS")), 8_000))
        device.findObject(By.text("SEED_6_MONTHS")).click()
        assertTrue(waitForSeed())

        context.startActivity(Intent(context, CheatEntryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/descriptionInput")), 8_000))
    }

    @Test
    fun description_andAiGate_doNotPersistBeforeConfirmation() {
        val database = MyFitAiDatabase.getInstance(context)
        val profileId = ActiveProfileStore(context).currentIdOrNull()!!
        val before = runBlocking { database.cheatEntryDao().observeAll(profileId).first().size }

        val description = device.findObject(By.res("com.myfitai.app:id/descriptionInput"))
        assertTrue(description != null)
        description.text = "Pizza margherita"
        assertTrue(scrollUntilVisible(By.res("com.myfitai.app:id/analyzeButton")))
        device.findObject(By.res("com.myfitai.app:id/analyzeButton")).click()

        assertTrue(device.wait(Until.hasObject(By.text("Confermare richiesta IA?")), 4_000))
        assertTrue(device.hasObject(By.textContains("valutazione dello sgarro")))
        device.findObject(By.text("Annulla")).click()

        assertEquals(before, runBlocking { database.cheatEntryDao().observeAll(profileId).first().size })
    }

    @Test
    fun labelPhotoDialog_isReachableWithoutOpeningCamera() {
        device.findObject(By.res("com.myfitai.app:id/addLabelPhotoButton")).click()
        assertTrue(device.wait(Until.hasObject(By.text("Foto etichetta nutrizionale")), 3_000))
        assertTrue(device.hasObject(By.text("Scatta foto")))
        assertTrue(device.hasObject(By.text("Scegli dalla galleria")))
        device.pressBack()
        assertTrue(device.hasObject(By.res("com.myfitai.app:id/descriptionInput")))
    }

    private fun waitForSeed(): Boolean {
        val deadline = System.currentTimeMillis() + 90_000L
        while (System.currentTimeMillis() < deadline) {
            val status = device.findObject(By.desc("qa_status"))
            if (status != null && status.text.contains("profile=")) return true
            Thread.sleep(250L)
        }
        return false
    }

    private fun scrollUntilVisible(selector: androidx.test.uiautomator.BySelector): Boolean {
        if (device.wait(Until.hasObject(selector), 1_000)) return true
        repeat(6) {
            device.swipe(device.displayWidth / 2, (device.displayHeight * 0.75).toInt(), device.displayWidth / 2, (device.displayHeight * 0.25).toInt(), 20)
            if (device.wait(Until.hasObject(selector), 1_000)) return true
        }
        return false
    }
}
