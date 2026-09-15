package com.myfitai.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.MealAlternativeContract
import com.myfitai.app.domain.food.MealAlternativeService
import com.myfitai.app.navigation.BottomNavBinder
import kotlinx.coroutines.launch
import java.util.Locale

class MealAlternativeActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private var generated: MealAlternativeService.Alternatives? = null
    private var applying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meal_alternative)
        bindBack()
        bindBottom(BottomNavBinder.Tab.FOOD)

        val weekStart = intent.getLongExtra(EXTRA_WEEK_START_EPOCH_DAY, Long.MIN_VALUE)
        val day = intent.getLongExtra(EXTRA_DAY_EPOCH_DAY, Long.MIN_VALUE)
        val mealId = intent.getLongExtra(EXTRA_MEAL_ID, -1L)
        val title = intent.getStringExtra(EXTRA_MEAL_TITLE).orEmpty()
        val type = intent.getStringExtra(EXTRA_MEAL_TYPE).orEmpty()
        val kcal = intent.getIntExtra(EXTRA_MEAL_KCAL, -1)

        findViewById<TextView>(R.id.sourceMealText).text = buildString {
            append(type.ifBlank { "Pasto" })
            if (title.isNotBlank()) append(" · $title")
            if (kcal > 0) append(" · $kcal kcal")
        }

        if (weekStart == Long.MIN_VALUE || day == Long.MIN_VALUE || mealId <= 0L) {
            showError("Pasto non disponibile.")
            return
        }

        confirmAiRequest("La generazione delle alternative per questo pasto") {
            lifecycleScope.launch {
                runCatching { data.mealAlternativeService.generate(weekStart, day, mealId) }
                    .onSuccess { result ->
                        generated = result
                        findViewById<View>(R.id.loadingRow).visibility = View.GONE
                        findViewById<TextView>(R.id.providerText).apply {
                            text = "${result.provider} · ${result.model}"
                            visibility = View.VISIBLE
                        }
                        renderAlternatives(result.items)
                    }
                    .onFailure { error ->
                        findViewById<View>(R.id.loadingRow).visibility = View.GONE
                        showError(error.message ?: "Impossibile generare alternative.")
                    }
            }
        }
    }

    private fun renderAlternatives(items: List<MealAlternativeContract.Alternative>) {
        val container = findViewById<LinearLayout>(R.id.alternativesContainer)
        container.removeAllViews()
        items.forEachIndexed { index, alternative ->
            val card = MaterialCardView(this).apply {
                radius = dp(14).toFloat()
                setCardBackgroundColor(getColor(R.color.surface_primary))
                strokeColor = getColor(R.color.divider)
                strokeWidth = dp(1)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            body.addView(TextView(this).apply {
                text = "${index + 1}. ${alternative.title}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            body.addView(TextView(this).apply {
                text = String.format(
                    Locale.ITALIAN,
                    "%d kcal · P %.0fg · C %.0fg · F %.0fg",
                    alternative.kcal,
                    alternative.proteinG,
                    alternative.carbsG,
                    alternative.fatG,
                )
                setTextColor(getColor(R.color.accent_green_dark))
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
            })
            body.addView(TextView(this).apply {
                text = alternative.ingredients.joinToString(" · ") { it.displayDose }
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
            body.addView(TextView(this).apply {
                text = alternative.reason
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, dp(5), 0, 0)
            })
            body.addView(MaterialButton(this).apply {
                text = "Sostituisci"
                isAllCaps = false
                setOnClickListener { applyAlternative(alternative) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply { topMargin = dp(10) })
            card.addView(body)
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) })
        }
    }

    private fun applyAlternative(alternative: MealAlternativeContract.Alternative) {
        val source = generated ?: return
        if (applying) return
        applying = true
        setButtonsEnabled(false)
        findViewById<TextView>(R.id.statusText).apply {
            visibility = View.VISIBLE
            text = "Sto sostituendo il pasto e creando una nuova versione del piano…"
        }
        lifecycleScope.launch {
            runCatching { data.mealAlternativeService.apply(source, alternative) }
                .onSuccess {
                    runCatching { data.notificationScheduler.refresh() }
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                .onFailure { error ->
                    applying = false
                    setButtonsEnabled(true)
                    showError(error.message ?: "Impossibile sostituire il pasto.")
                }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        val container = findViewById<LinearLayout>(R.id.alternativesContainer)
        for (i in 0 until container.childCount) {
            container.getChildAt(i).isEnabled = enabled
            val card = container.getChildAt(i) as? MaterialCardView ?: continue
            val body = card.getChildAt(0) as? LinearLayout ?: continue
            for (j in 0 until body.childCount) {
                body.getChildAt(j).isEnabled = enabled
            }
        }
    }

    private fun showError(message: String) {
        findViewById<TextView>(R.id.statusText).apply {
            visibility = View.VISIBLE
            text = message
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_WEEK_START_EPOCH_DAY = "meal_alt_week_start"
        const val EXTRA_DAY_EPOCH_DAY = "meal_alt_day"
        const val EXTRA_MEAL_ID = "meal_alt_id"
        const val EXTRA_MEAL_TITLE = "meal_alt_title"
        const val EXTRA_MEAL_TYPE = "meal_alt_type"
        const val EXTRA_MEAL_KCAL = "meal_alt_kcal"
    }
}
