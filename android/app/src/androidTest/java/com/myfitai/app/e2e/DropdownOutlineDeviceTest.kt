package com.myfitai.app.e2e

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.widget.AutoCompleteTextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.textfield.TextInputLayout
import com.myfitai.app.R
import com.myfitai.app.ui.NewWorkoutActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only check that a dropdown's outlined box matches neighbouring text fields. */
@RunWith(AndroidJUnit4::class)
class DropdownOutlineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun workoutTypeComboUsesTheStandardOutlinedFieldAndKeepsItsOptions() {
        val data = com.myfitai.app.data.AppDataContainer.get(context)
        val profileId = data.activeProfileStore.currentIdOrNull() ?: error("Active profile missing")
        val wasEnabled = data.workoutPreferences.isEnabled(profileId)
        if (!wasEnabled) data.workoutPreferences.setEnabled(profileId, true)
        var comboBounds = Rect()
        var fieldBounds = Rect()
        try {
            ActivityScenario.launch<NewWorkoutActivity>(Intent(context, NewWorkoutActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                val combo = activity.findViewById<TextInputLayout>(R.id.typeLayout)
                val textField = activity.findViewById<TextInputLayout>(R.id.titleLayout)
                val input = activity.findViewById<AutoCompleteTextView>(R.id.typeInput)
                assertEquals("Combo and text field must use the same outlined box mode", textField.boxBackgroundMode, combo.boxBackgroundMode)
                assertEquals("Combo and text field must use the same resting outline width", textField.boxStrokeWidth, combo.boxStrokeWidth)
                assertEquals("Combo and text field must use the same resting outline color", textField.boxStrokeColor, combo.boxStrokeColor)
                assertEquals("Combo and text field must use the same corners", textField.shapeAppearanceModel.topLeftCornerSize, combo.shapeAppearanceModel.topLeftCornerSize)
                val comboLocation = IntArray(2).also(combo::getLocationOnScreen)
                comboBounds.set(comboLocation[0], comboLocation[1], comboLocation[0] + combo.width, comboLocation[1] + combo.height)
                val fieldLocation = IntArray(2).also(textField::getLocationOnScreen)
                fieldBounds.set(fieldLocation[0], fieldLocation[1], fieldLocation[0] + textField.width, fieldLocation[1] + textField.height)
                assertEquals("Workout type options remain intact", listOf("Pesi", "Cardio", "Mobilità", "Sport", "Altro"),
                    (0 until input.adapter.count).map { input.adapter.getItem(it).toString() })
            }
            val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
            val screenshot = File(dir, "workout-dropdown-outlined.png")
            device.takeScreenshot(screenshot)
            val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
            val comboPixel = bitmap.getPixel((comboBounds.left + comboBounds.right) / 2, comboBounds.top + 1)
            val fieldPixel = bitmap.getPixel((fieldBounds.left + fieldBounds.right) / 2, fieldBounds.top + 1)
            assertEquals("Combo and text field must paint the same top outline: combo=#${Integer.toHexString(comboPixel)} field=#${Integer.toHexString(fieldPixel)}", fieldPixel, comboPixel)
            val comboTop = (comboBounds.left..comboBounds.right).map { x -> bitmap.getPixel(x, comboBounds.top + 1) }
            val fieldTop = (fieldBounds.left..fieldBounds.right).map { x -> bitmap.getPixel(x, fieldBounds.top + 1) }
            val comboNonWhite = comboTop.count { it != android.graphics.Color.WHITE }
            val fieldNonWhite = fieldTop.count { it != android.graphics.Color.WHITE }
            assertTrue("Combo outline must be visible like the text field: comboNonWhite=$comboNonWhite fieldNonWhite=$fieldNonWhite", comboNonWhite >= fieldNonWhite * 0.75)
            bitmap.recycle()
            scenario.onActivity { activity -> activity.findViewById<AutoCompleteTextView>(R.id.typeInput).performClick() }
            assertTrue("dropdown options remain available", device.wait(Until.hasObject(By.text("Cardio")), 3_000))
            device.pressBack()
        }
        } finally {
            if (!wasEnabled) data.workoutPreferences.setEnabled(profileId, false)
        }
    }
}
