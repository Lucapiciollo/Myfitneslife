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
import com.myfitai.app.ui.widgets.MealCardView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica di layout di `nextMealCard` e `secondNextMealCard` con testi sintetici impostati solo in memoria
 * sulle viste: nessun dato, profilo o repository viene scritto.
 * Argomenti opzionali: `case=normal|stress|real` (`real` non imposta alcun testo e mostra lo stato reale del profilo),
 * `holdMs=<ms>` (tiene la schermata aperta per uno screenshot adb).
 */
@RunWith(AndroidJUnit4::class)
class HomeMealCardsLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun mealCards_wrapSyntheticValuesWithoutClipping() {
        val args = InstrumentationRegistry.getArguments()
        val stress = args.getString("case") == "stress"
        val real = args.getString("case") == "real"
        val holdMs = args.getString("holdMs")?.toLongOrNull() ?: 0L

        ActivityScenario.launch<HomeActivity>(
            Intent(instrumentation.targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ).use { scenario ->
            Thread.sleep(2_500L)
            scenario.onActivity { activity ->
                if (!real) activity.findViewById<MealCardView>(R.id.nextMealCard).apply {
                    setTime(if (stress) "Mercoledì 30 settembre 13:30" else "Oggi 13:30")
                    setTitle(
                        if (stress) {
                            "Petto di pollo al curry con riso basmati, verdure di stagione saltate e salsa allo yogurt"
                        } else {
                            "Pollo al curry con riso basmati"
                        },
                    )
                    setKcal(if (stress) "≈ 1234 kcal" else "≈ 650 kcal")
                }
                if (!real) activity.findViewById<MealCardView>(R.id.secondNextMealCard).apply {
                    setTime("Oggi 20:00")
                    setTitle("Salmone con patate e insalata")
                    setKcal("≈ 580 kcal")
                }
                val scroll = activity.findViewById<ScrollView>(R.id.homeScrollView)
                val card = activity.findViewById<View>(R.id.nextMealCard)
                val rect = Rect()
                card.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(card, rect)
                scroll.scrollTo(0, rect.top - activity.resources.getDimensionPixelSize(R.dimen.space_24) * 3)
            }
            Thread.sleep(800L)
            scenario.onActivity { activity ->
                fun texts(v: View): List<TextView> = when (v) {
                    is TextView -> listOf(v)
                    is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
                    else -> emptyList()
                }
                val all = listOf(R.id.nextMealCard, R.id.secondNextMealCard).flatMap { texts(activity.findViewById(it)) }
                assertTrue("expected 6 texts, found ${all.size}", all.size == 6)
                all.forEach { view ->
                    val layout = view.layout
                    assertTrue("layout not ready for '${view.text}'", layout != null)
                    for (line in 0 until layout.lineCount) {
                        assertTrue("ellipsized '${view.text}'", layout.getEllipsisCount(line) == 0)
                    }
                    val parent = view.parent as ViewGroup
                    assertTrue("'${view.text}' overflows parent", view.right <= parent.width && view.left >= 0)
                }
            }
            if (holdMs > 0) Thread.sleep(holdMs)
        }
    }
}
