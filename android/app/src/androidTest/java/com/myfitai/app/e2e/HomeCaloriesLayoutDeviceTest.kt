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
import com.myfitai.app.R
import com.myfitai.app.ui.HomeActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica di layout delle card "Calorie base giornaliere" e "Attività di oggi" con testi sintetici
 * impostati solo in memoria sulle viste: nessun dato, profilo o repository viene scritto.
 * Argomenti opzionali: `case=normal|stress`, `holdMs=<ms>` (tiene la schermata aperta per uno screenshot adb).
 */
@RunWith(AndroidJUnit4::class)
class HomeCaloriesLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun calorieCardAndCheckIn_wrapSyntheticValuesWithoutClipping() {
        val args = InstrumentationRegistry.getArguments()
        val stress = args.getString("case") == "stress"
        val holdMs = args.getString("holdMs")?.toLongOrNull() ?: 0L
        val targetCard = if (args.getString("focus") == "checkin") R.id.activityCheckInCard else R.id.bodyOverviewCard

        ActivityScenario.launch<HomeActivity>(
            Intent(instrumentation.targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use { scenario ->
            Thread.sleep(2_500L)
            scenario.onActivity { activity ->
                fun text(id: Int, value: String) { activity.findViewById<TextView>(id).text = value }
                if (stress) {
                    text(R.id.caloriesModeText, "Ricomposizione con un obiettivo dal nome molto lungo · +12% sul consumo")
                    text(R.id.caloriesBmrValue, "10000 kcal")
                    text(R.id.caloriesBiaBmrValue, "10000 kcal")
                    text(R.id.caloriesTdeeValue, "12345 kcal")
                    text(R.id.caloriesTargetValue, "12345 kcal")
                    text(R.id.caloriesConsumedText, "12345 kcal")
                    text(R.id.caloriesConsumedProteinText, "≈ 1234 g")
                    text(R.id.caloriesRemainingText, "12345 oltre")
                } else {
                    text(R.id.caloriesModeText, "Ricomposizione · +5% sul consumo")
                    text(R.id.caloriesBmrValue, "1780 kcal")
                    text(R.id.caloriesBiaBmrValue, "1812 kcal")
                    text(R.id.caloriesTdeeValue, "2760 kcal")
                    text(R.id.caloriesTargetValue, "2400 kcal")
                    text(R.id.caloriesConsumedText, "1250 kcal")
                    text(R.id.caloriesConsumedProteinText, "85 g")
                    text(R.id.caloriesRemainingText, "1150 kcal")
                }
                listOf(
                    R.id.caloriesBmrValue, R.id.caloriesBiaBmrValue, R.id.caloriesTdeeValue, R.id.caloriesTargetValue,
                    R.id.caloriesConsumedText, R.id.caloriesConsumedProteinText, R.id.caloriesRemainingText,
                ).forEach { com.myfitai.app.ui.widgets.ValueUnitFormatter.apply(activity.findViewById(it)) }
                text(
                    R.id.caloriesTargetSourceText,
                    "TDEE abituale 2620 kcal · TDEE operativo 2760 kcal · attività +140 kcal",
                )
                text(R.id.caloriesConsumptionNote, "Calorie e proteine sommano i soli elementi consumati (3).")
                text(
                    R.id.activityCheckInSummary,
                    "Allenamento previsto: 60 min · intensità media. Target operativo 2400 kcal (+140 kcal).",
                )
                activity.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.caloriesConsumedProgress).progress = 52
                activity.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.caloriesRemainingProgress).progress = 48

                val scroll = activity.findViewById<ScrollView>(R.id.homeScrollView)
                val card = activity.findViewById<View>(targetCard)
                val rect = Rect()
                card.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(card, rect)
                scroll.scrollTo(0, rect.top)
            }
            Thread.sleep(800L)
            scenario.onActivity { activity ->
                val ids = listOf(
                    R.id.caloriesModeText, R.id.caloriesBmrValue, R.id.caloriesBiaBmrValue, R.id.caloriesTdeeValue,
                    R.id.caloriesTargetValue, R.id.caloriesTargetSourceText, R.id.caloriesConsumedText,
                    R.id.caloriesConsumedProteinText, R.id.caloriesRemainingText, R.id.caloriesConsumptionNote,
                    R.id.activityCheckInSummary,
                )
                ids.forEach { id ->
                    val view = activity.findViewById<TextView>(id)
                    val layout = view.layout
                    assertTrue("layout not ready for $id", layout != null)
                    for (line in 0 until layout.lineCount) {
                        assertTrue("ellipsized text in $id", layout.getEllipsisCount(line) == 0)
                    }
                    val parent = view.parent as ViewGroup
                    assertTrue("view $id overflows parent", view.right <= parent.width && view.left >= 0)
                }
            }
            if (holdMs > 0) Thread.sleep(holdMs)
        }
    }
}
