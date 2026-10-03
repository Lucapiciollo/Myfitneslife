package com.myfitai.app.e2e

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.myfitai.app.R
import com.myfitai.app.ui.HomeActivity
import com.myfitai.app.ui.WorkoutsActivity
import com.myfitai.app.ui.widgets.WorkoutCardView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica non distruttiva della card Home `nextWorkoutCard`: i testi sintetici sono impostati solo in memoria sulle
 * viste, nessun dato, profilo o repository viene scritto.
 * Argomenti opzionali: `case=normal|stress|real` (`real` non imposta alcun testo e mostra lo stato reale del profilo),
 * `holdMs=<ms>` (tiene la schermata aperta per uno screenshot adb).
 */
@RunWith(AndroidJUnit4::class)
class HomeWorkoutCardLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun workoutCard_wrapsValuesWithoutClipping() {
        val args = InstrumentationRegistry.getArguments()
        val case = args.getString("case") ?: "normal"
        val holdMs = args.getString("holdMs")?.toLongOrNull() ?: 0L

        launchHome().use { scenario ->
            Thread.sleep(2_500L)
            scenario.onActivity { activity ->
                assertTrue(
                    "workoutSectionCard non visibile: allenamenti disabilitati per il profilo attivo",
                    activity.findViewById<View>(R.id.workoutSectionCard).visibility == View.VISIBLE,
                )
                val card = activity.findViewById<WorkoutCardView>(R.id.nextWorkoutCard)
                when (case) {
                    "stress" -> card.apply {
                        setTime("Mercoledì 30 settembre 18:30")
                        setTitle("Allenamento di forza total body con circuito metabolico finale e defaticamento guidato")
                        setImage(R.drawable.img_workout_cardio)
                    }
                    "real" -> Unit
                    else -> card.apply {
                        setTime("Oggi 18:30")
                        setTitle("Forza parte superiore")
                        setImage(R.drawable.img_workout_weights)
                    }
                }
                val scroll = activity.findViewById<ScrollView>(R.id.homeScrollView)
                val rect = Rect()
                card.getDrawingRect(rect)
                scroll.offsetDescendantRectToMyCoords(card, rect)
                scroll.scrollTo(0, rect.top - activity.resources.getDimensionPixelSize(R.dimen.space_24) * 3)
            }
            Thread.sleep(800L)
            scenario.onActivity { activity ->
                val card = activity.findViewById<WorkoutCardView>(R.id.nextWorkoutCard)
                val texts = descendants(card).filterIsInstance<TextView>()
                assertTrue("expected 2 texts, found ${texts.size}", texts.size == 2)
                texts.forEach { view ->
                    val layout = view.layout
                    assertNotNull("layout not ready for '${view.text}'", layout)
                    for (line in 0 until layout.lineCount) {
                        assertTrue("ellipsized '${view.text}'", layout.getEllipsisCount(line) == 0)
                    }
                    assertTrue(
                        "'${view.text}' clipped vertically",
                        layout.height <= view.height - view.totalPaddingTop - view.totalPaddingBottom,
                    )
                    val parent = view.parent as ViewGroup
                    assertTrue("'${view.text}' overflows parent", view.right <= parent.width && view.left >= 0)
                }
                val textColumn = texts.first().parent as View
                val thumbnail = descendants(card).filterIsInstance<ImageView>().last()
                assertTrue("text column overlaps thumbnail", textColumn.right <= thumbnail.left)
                assertTrue("card content clipped", textColumn.bottom <= card.height && thumbnail.bottom <= card.height)
            }
            if (holdMs > 0) Thread.sleep(holdMs)
        }
    }

    @Test
    fun workoutCard_tapOpensWorkoutsActivity() {
        val monitor: Instrumentation.ActivityMonitor =
            instrumentation.addMonitor(WorkoutsActivity::class.java.name, null, false)
        try {
            launchHome().use { scenario ->
                Thread.sleep(2_500L)
                scenario.onActivity { activity ->
                    val card = activity.findViewById<WorkoutCardView>(R.id.nextWorkoutCard)
                    assertTrue("nextWorkoutCard non visibile", card.isShown)
                    assertTrue("click non eseguito", card.performClick())
                }
                val opened = instrumentation.waitForMonitorWithTimeout(monitor, 5_000L)
                assertNotNull("WorkoutsActivity non aperta dal tap su nextWorkoutCard", opened)
                opened.finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun launchHome(): ActivityScenario<HomeActivity> = ActivityScenario.launch(
        Intent(instrumentation.targetContext, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun descendants(view: View): List<View> = when (view) {
        is ViewGroup -> listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
        else -> listOf(view)
    }
}
