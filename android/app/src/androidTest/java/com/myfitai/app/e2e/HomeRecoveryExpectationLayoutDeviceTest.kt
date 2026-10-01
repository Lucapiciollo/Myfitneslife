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
import com.myfitai.app.ui.widgets.ValueUnitFormatter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica di layout delle card "Riserva di recupero" e "Possibile calo teorico" con testi e visibilità
 * sintetici impostati solo in memoria sulle viste: nessun dato, profilo o repository viene scritto.
 * Argomenti opzionali: `case=normal|stress`, `focus=recovery|expectation`, `fmt=1` (applica
 * [ValueUnitFormatter] come fa `renderRecovery`), `holdMs=<ms>` (tiene la schermata aperta per uno screenshot adb).
 */
@RunWith(AndroidJUnit4::class)
class HomeRecoveryExpectationLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun recoveryAndExpectation_wrapSyntheticValuesWithoutClipping() {
        val args = InstrumentationRegistry.getArguments()
        val stress = args.getString("case") == "stress"
        val holdMs = args.getString("holdMs")?.toLongOrNull() ?: 0L
        val formatUnits = args.getString("fmt") == "1"
        val targetCard = if (args.getString("focus") == "expectation") R.id.weeklyExpectationCard else R.id.recoveryCard

        ActivityScenario.launch<HomeActivity>(
            Intent(instrumentation.targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use { scenario ->
            Thread.sleep(2_500L)
            scenario.onActivity { activity ->
                fun text(id: Int, value: String) { activity.findViewById<TextView>(id).text = value }
                fun show(id: Int) { activity.findViewById<View>(id).visibility = View.VISIBLE }
                if (stress) {
                    text(R.id.recoveryValueText, "12345 kcal")
                    text(
                        R.id.recoveryHintText,
                        "Da distribuire gradualmente nei prossimi giorni · 12 sgarri recenti · prima scadenza 06/10. " +
                            "Evita compensazioni drastiche.",
                    )
                    text(R.id.weeklyExpectationStatus, "≈ 12.5–18.5 kg di grasso previsti in totale")
                    text(
                        R.id.weeklyExpectationDetail,
                        "Periodo 29/09–26/10 · 28/28 giorni pianificati · deficit ≈ 123456 kcal",
                    )
                } else {
                    text(R.id.recoveryValueText, "420 kcal")
                    text(
                        R.id.recoveryHintText,
                        "Da distribuire gradualmente nei prossimi giorni · 2 sgarri recenti · prima scadenza 06/10. " +
                            "Evita compensazioni drastiche.",
                    )
                    text(R.id.weeklyExpectationStatus, "≈ 3–4 etti di grasso")
                    text(
                        R.id.weeklyExpectationDetail,
                        "Periodo 29/09–05/10 · 5/7 giorni pianificati · deficit ≈ 3500 kcal",
                    )
                }
                if (formatUnits) ValueUnitFormatter.apply(activity.findViewById(R.id.recoveryValueText))
                show(R.id.weeklyExpectationActionButton)
                show(R.id.weeklyExpectationNegativeButton)
                show(R.id.weeklyExpectationOutcomeText)
                text(R.id.weeklyExpectationOutcomeText, "Esito registrato: sì, traguardo raggiunto.")

                val scroll = activity.findViewById<ScrollView>(R.id.homeScrollView)
                val card = activity.findViewById<View>(targetCard)
                val rect = Rect()
                card.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(card, rect)
                scroll.scrollTo(0, rect.top)
            }
            Thread.sleep(800L)
            scenario.onActivity { activity ->
                listOf(
                    R.id.recoveryValueText, R.id.recoveryHintText, R.id.weeklyExpectationStatus,
                    R.id.weeklyExpectationDetail, R.id.weeklyExpectationCaution, R.id.weeklyExpectationOutcomeText,
                    R.id.weeklyExpectationActionButton, R.id.weeklyExpectationNegativeButton,
                ).forEach { id ->
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
