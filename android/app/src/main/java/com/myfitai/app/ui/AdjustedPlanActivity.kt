package com.myfitai.app.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.ui.widgets.StatusRowView

class AdjustedPlanActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adjusted_plan)
        bindBack()

        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val estimate = intent.getStringExtra(EXTRA_ESTIMATE).orEmpty()
        val adapted = intent.getBooleanExtra(EXTRA_ADAPTED, false)
        val summary = intent.getStringExtra(EXTRA_SUMMARY).orEmpty()
        val modifiedMeals = intent.getStringArrayListExtra(EXTRA_MODIFIED_MEALS).orEmpty()

        findViewById<TextView>(R.id.resultTitle).text = if (adapted) "Piano aggiornato" else "Sgarro registrato"
        findViewById<TextView>(R.id.resultDescription).text = buildString {
            if (description.isNotBlank()) appendLine(description)
            if (estimate.isNotBlank()) append(estimate)
        }.trim()
        findViewById<TextView>(R.id.resultSummary).text = summary.ifBlank {
            if (adapted) "Il surplus è stato inserito nel serbatoio e distribuito gradualmente sui giorni futuri della settimana." else "Il piano non è stato modificato."
        }
        findViewById<TextView>(R.id.modifiedHeader).visibility = if (modifiedMeals.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.modifiedMealsCard).visibility = if (modifiedMeals.isEmpty()) View.GONE else View.VISIBLE

        val container = findViewById<LinearLayout>(R.id.modifiedMealsContainer)
        container.removeAllViews()
        modifiedMeals.forEach { label ->
            val card = MaterialCardView(this).apply {
                setCardBackgroundColor(getColor(R.color.white))
                radius = resources.getDimension(R.dimen.radius_card)
                cardElevation = 0f
                strokeColor = getColor(R.color.divider)
                strokeWidth = resources.getDimensionPixelSize(R.dimen.space_1)
                addView(StatusRowView(this@AdjustedPlanActivity).apply {
                    setLabel(label)
                    setState("Pasto futuro")
                    setStatus(StatusRowView.Status.POSITIVE)
                    val horizontalPadding = resources.getDimensionPixelSize(R.dimen.space_16)
                    val verticalPadding = resources.getDimensionPixelSize(R.dimen.space_4)
                    setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                })
            }
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.space_8) })
        }

        findViewById<View>(R.id.okButton).setOnClickListener {
            openFoodPlan()
            finish()
        }
    }

    companion object {
        const val EXTRA_DESCRIPTION = "cheat_description"
        const val EXTRA_ESTIMATE = "cheat_estimate"
        const val EXTRA_ADAPTED = "cheat_adapted"
        const val EXTRA_SUMMARY = "cheat_summary"
        const val EXTRA_MODIFIED_MEALS = "cheat_modified_meals"
    }
}
