package com.myfitai.app.e2e

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.google.android.material.textfield.TextInputLayout
import com.myfitai.app.ui.CheatEntryActivity
import com.myfitai.app.ui.DietaryPreferencesActivity
import com.myfitai.app.ui.NewBodyMeasurementActivity
import com.myfitai.app.ui.NewWorkoutActivity
import com.myfitai.app.ui.NutritionPlanSettingsActivity
import com.myfitai.app.ui.ProfileActivity
import com.myfitai.app.ui.ProfileEditActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Read-only audit of every text/dropdown input and of the modal content.
 * It records real geometry per field to `qa-artifacts/input-audit.txt` and asserts that the shared
 * input pattern (same minimum height and text padding) is respected. It never saves or deletes data.
 */
@RunWith(AndroidJUnit4::class)
class InputModalStyleAuditDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val dir = File(context.getExternalFilesDir(null), "qa-artifacts").apply { mkdirs() }
    private val report = StringBuilder()

    private data class Field(
        val screen: String,
        val id: String,
        val dropdown: Boolean,
        val heightPx: Int,
        val editHeightPx: Int,
        val padStartPx: Int,
        val textSizePx: Float,
        val strokeColor: Int,
        val strokeWidthPx: Int,
        val boxMode: Int,
    )

    private val fields = mutableListOf<Field>()
    private val pickerRows = mutableListOf<Int>()

    private fun inputs(root: View): List<TextInputLayout> {
        val out = mutableListOf<TextInputLayout>()
        fun visit(v: View) {
            if (v is TextInputLayout && v.visibility == View.VISIBLE) out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        return out
    }

    private fun idName(view: View): String =
        if (view.id == View.NO_ID) "(no-id)" else runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("?")

    private fun collect(screen: String, activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        inputs(root).forEach { layout ->
            val edit = layout.editText ?: return@forEach
            if (edit.height <= 0) return@forEach
            val dropdown = layout.endIconMode == TextInputLayout.END_ICON_DROPDOWN_MENU
            // Multiline fields have an intentionally larger explicit height; only single-line fields are compared.
            val multiline = (edit.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            if (multiline) {
                report.appendLine("$screen\t${idName(edit)}\tmultiline\th=${layout.height}\tpadStart=${edit.paddingStart}\ttextPx=${edit.textSize}")
                return@forEach
            }
            val f = Field(
                screen = screen,
                id = idName(edit),
                dropdown = dropdown,
                heightPx = layout.height,
                editHeightPx = edit.height,
                padStartPx = edit.paddingStart,
                textSizePx = edit.textSize,
                strokeColor = layout.boxStrokeColor,
                strokeWidthPx = layout.boxStrokeWidth,
                boxMode = layout.boxBackgroundMode,
            )
            fields += f
            report.appendLine("$screen\t${f.id}\t${if (dropdown) "dropdown" else "text"}\tboxMode=${f.boxMode}\tstroke=${f.strokeWidthPx}\tstrokeColor=#${Integer.toHexString(f.strokeColor)}\th=${f.heightPx}\teditH=${f.editHeightPx}\tpadStart=${f.padStartPx}\ttextPx=${f.textSizePx}")
        }
    }

    private fun <T : Activity> audit(screen: String, cls: Class<T>, configure: (Intent) -> Unit = {}, prepare: (T) -> Unit = {}) {
        ActivityScenario.launch<T>(Intent(context, cls).apply { configure(this) }).use { scenario ->
            scenario.onActivity { prepare(it) }
            instrumentation.waitForIdleSync()
            Thread.sleep(600)
            scenario.onActivity { collect(screen, it) }
            device.takeScreenshot(File(dir, "audit-$screen.png"))
        }
    }

    @Test
    fun allInputsShareTheSamePattern() {
        audit("new_body_measurement", NewBodyMeasurementActivity::class.java)
        audit("profile_edit", ProfileEditActivity::class.java)
        audit("dietary_preferences", DietaryPreferencesActivity::class.java)
        audit("nutrition_plan_settings", NutritionPlanSettingsActivity::class.java)
        ActivityScenario.launch<CheatEntryActivity>(Intent(context, CheatEntryActivity::class.java)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("Dettagliato")), 8_000))
            device.findObject(By.text("Dettagliato")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Etichetta nutrizionale (opzionale)")), 3_000))
            Thread.sleep(600)
            scenario.onActivity { activity ->
                collect("cheat_detailed", activity)
                // Date/time pickers are fields too, even when built as custom rows.
                listOf(com.myfitai.app.R.id.dateField, com.myfitai.app.R.id.timeField).forEach { id ->
                    val v = activity.findViewById<View>(id)
                    pickerRows += v.height
                    report.appendLine("cheat_detailed\t${idName(v)}\tpicker-row\th=${v.height}\tpadStart=${v.paddingStart}")
                }
            }
            device.takeScreenshot(File(dir, "audit-cheat_detailed.png"))
        }
        // Notification settings modal (Profile): open through the real row, measure the dropdown inside the dialog.
        ActivityScenario.launch<ProfileActivity>(Intent(context, ProfileActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/rowNotifications")), 8_000))
            device.findObject(By.res("com.myfitai.app:id/rowNotifications")).click()
            assertTrue(device.wait(Until.hasObject(By.res("com.myfitai.app:id/menuReminderTimeInput")), 5_000))
            val bounds = device.findObject(By.res("com.myfitai.app:id/menuReminderTimeInput")).visibleBounds
            val dialogLeft = device.findObject(By.res("com.myfitai.app:id/mealRemindersSwitch")).visibleBounds
            report.appendLine("notification_dialog\tmenuReminderTimeInput\tdropdown-bounds\th=${bounds.height()}\tleft=${bounds.left}\tswitchLeft=${dialogLeft.left}")
            device.takeScreenshot(File(dir, "audit-notification_dialog.png"))
            device.pressBack()
        }

        File(dir, "input-audit.txt").writeText(report.toString())

        val text = fields.filter { !it.dropdown }
        val drop = fields.filter { it.dropdown }
        fun distinct(list: List<Field>, sel: (Field) -> Number) = list.map(sel).map { it.toFloat() }.toSortedSet()
        val msg = "text heights=${distinct(text) { it.heightPx }} padStart=${distinct(text) { it.padStartPx }} | dropdown heights=${distinct(drop) { it.heightPx }} padStart=${distinct(drop) { it.padStartPx }}"
        report.appendLine(msg)
        File(dir, "input-audit.txt").writeText(report.toString())

        assertTrue("single-line text fields must share one text padding: $msg", distinct(text) { it.padStartPx }.size == 1)
        assertTrue("single-line text fields must share one box height: $msg", distinct(text) { it.heightPx }.size == 1)
        assertTrue("single-line text fields must share one text size: $msg", distinct(text) { it.textSizePx }.size == 1)
        assertTrue("dropdown fields must share the box height of text fields: $msg", (distinct(text) { it.heightPx } + distinct(drop) { it.heightPx }).size == 1)
        // Custom date/time rows are fields too: within 10% of a standard field (text scaling is non-linear on large fonts) and never shorter than readable.
        val standardHeight = text.first().heightPx
        pickerRows.forEach { assertTrue("picker row too short vs a standard field: ${it}px vs ${standardHeight}px", it >= standardHeight * 0.9f) }
        assertTrue("dropdown fields must share the text padding of text fields: $msg", (distinct(text) { it.padStartPx } + distinct(drop) { it.padStartPx }).size == 1)
    }

    /** Left inset of [target] relative to [root], summing the offsets of the ancestors chain. */
    private fun leftInset(root: View, target: View): Int {
        var x = 0
        var v: View? = target
        while (v != null && v !== root) { x += v.left; v = v.parent as? View }
        return x
    }

    @Test
    fun dialogContentUsesTheSameInsetAndFieldPattern() {
        val themed = android.view.ContextThemeWrapper(context, com.myfitai.app.R.style.Theme_MyFitAI)
        val width = 1000
        fun inflate(layoutId: Int): View {
            val view = android.view.LayoutInflater.from(themed).inflate(layoutId, null, false)
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            return view
        }
        fun firstField(v: View): TextInputLayout? {
            if (v is TextInputLayout) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) firstField(v.getChildAt(i))?.let { return it }
            return null
        }
        val expectedInset = context.resources.getDimensionPixelSize(com.myfitai.app.R.dimen.space_12)
        val samples = listOf(
            "dialog_pricing_editor" to com.myfitai.app.R.layout.dialog_pricing_editor,
            "dialog_bia_value_input" to com.myfitai.app.R.layout.dialog_bia_value_input,
            "dialog_notification_settings" to com.myfitai.app.R.layout.dialog_notification_settings,
        )
        val lines = samples.map { (name, id) ->
            val root = inflate(id)
            val field = firstField(root) ?: error("$name has no field")
            val inset = leftInset(root, field)
            val fieldHeight = field.height
            File(dir, "dialog-content-audit.txt").appendText("$name\tfieldLeftInset=$inset\tfieldHeight=$fieldHeight\trootPaddingStart=${root.paddingStart}\n")
            Triple(name, inset, fieldHeight)
        }
        lines.forEach { (name, inset, _) ->
            assertTrue("$name: content must start at the shared dialog inset ($expectedInset px), found $inset px", inset == expectedInset)
        }
        val heights = lines.map { it.third }.toSet()
        assertTrue("dialog fields must share one box height: $lines", heights.size == 1)
    }
}
