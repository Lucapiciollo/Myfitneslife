package com.myfitai.app.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodSupplement
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanMetrics
import com.myfitai.app.domain.food.FoodPlanVersion
import com.myfitai.app.domain.food.FoodConsumptionMetrics
import com.myfitai.app.domain.food.FoodConsumptionStatus
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.food.FoodPlanViewModel
import com.myfitai.app.ui.widgets.MealPlanRowView
import com.myfitai.app.ui.widgets.KeyValueRowView
import com.myfitai.app.ui.widgets.WeekDaySelectorView
import com.myfitai.app.ui.motion.UiMotion
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class FoodPlanActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: FoodPlanViewModel by viewModels {
         FoodPlanViewModel.Factory(data.mealPlanRepository, data.activeProfileStore, data.nutritionPlanGenerationService, data.profileCalculationService, data.notificationScheduler, data.foodConsumptionRepository, data.aiJobScheduler, data.userProfileRepository, data.biaRepository, data.bodyMeasurementRepository)
    }

    private val mealAlternativeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) recreate()
    }
    private val weekDaySelector by lazy { findViewById<WeekDaySelectorView>(R.id.weekDaySelector) }
    private val animatedVisibilityTargets = mutableMapOf<Int, Boolean>()
    private var suppressDaySelectionMotion = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_food_plan)
        bindBottom(BottomNavBinder.Tab.FOOD)
        intent.takeIf { it.hasExtra(EXTRA_WEEK_START_EPOCH_DAY) }?.getLongExtra(EXTRA_WEEK_START_EPOCH_DAY, LocalDate.now().toEpochDay())?.let(viewModel::selectWeek)
        findViewById<View>(R.id.prevWeekButton).setOnClickListener { viewModel.previousWeek() }
        findViewById<View>(R.id.nextWeekButton).setOnClickListener { viewModel.nextWeek() }
        findViewById<View>(R.id.shoppingButton).setOnClickListener {
            startActivity(Intent(this, ShoppingListActivity::class.java).putExtra(ShoppingListActivity.EXTRA_WEEK_START_EPOCH_DAY, viewModel.state.value.weekStart.toEpochDay()))
        }
        findViewById<View>(R.id.cheatButton).setOnClickListener { go(CheatEntryActivity::class.java) }
        findViewById<View>(R.id.generatePlanButton).setOnClickListener { confirmPlanGeneration() }
        findViewById<View>(R.id.planSettingsButton).setOnClickListener { startActivity(Intent(this, NutritionPlanSettingsActivity::class.java)) }
        findViewById<View>(R.id.aiConfigurationNoticeButton).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        bindFoodHelp()
        findViewById<View>(R.id.dailyTotalHelpButton).setOnClickListener { showTotalsHelp() }
        renderMealCountPreference()
        weekDaySelector.setOnDaySelectedListener(viewModel::selectDay)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) } }
    }

    fun selectWeekFromNavigation(weekStartEpochDay: Long) {
        viewModel.selectWeek(weekStartEpochDay)
    }

    private fun bindFoodHelp() {
        findViewById<View>(R.id.weekActionsHelpButton).setOnClickListener {
            showHelpCard(
                "Azioni settimana",
                "Apri la lista della spesa per raccogliere gli ingredienti del piano oppure registra uno sgarro per tenerne conto nello storico dei consumi.",
            )
        }
        findViewById<View>(R.id.foodPlanHelpButton).setOnClickListener {
            showHelpCard(
                "Piano alimentare",
                "Qui puoi generare o rigenerare con IA il piano della settimana selezionata. Il piano viene controllato localmente prima di essere salvato.",
            )
        }
        findViewById<View>(R.id.dayMealsHelpButton).setOnClickListener {
            showHelpCard(
                "Pasti del giorno",
                "Mostra i pasti previsti per il giorno selezionato. Tocca un pasto per vedere i dettagli e, quando disponibile, scegliere un'alternativa.",
            )
        }
    }

    private fun renderMealCountPreference() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        findViewById<TextView>(R.id.mealCountHint).text = "${data.mealCountPreferences.get(profileId)} pasti al giorno"
    }

    private fun confirmPlanGeneration() {
        confirmAiRequest("La generazione del piano alimentare") {
            viewModel.generateCurrentWeek()
        }
    }

    private fun render(state: FoodPlanViewModel.State) {
        val weekEnd = state.weekStart.plusDays(6)
        val currentWeek = state.weekStart == LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        findViewById<TextView>(R.id.weekRangeLabel).text = formatWeekRange(state.weekStart, weekEnd)
        suppressDaySelectionMotion = true
        weekDaySelector.setDays((0..6).map { offset ->
            val date = state.weekStart.plusDays(offset.toLong())
            WeekDaySelectorView.Day(date.format(DateTimeFormatter.ofPattern("EEE", Locale.ITALIAN)).replaceFirstChar { it.uppercase() }.take(3), date.dayOfMonth.toString())
        }, state.selectedDayIndex)
        suppressDaySelectionMotion = false
        weekDaySelector.setOnDaySelectedListener(viewModel::selectDay)

        val versionLabel = findViewById<TextView>(R.id.planVersionLabel)
        val historicalBanner = findViewById<TextView>(R.id.historicalPlanBanner)
        val snapshot = state.snapshot
        if (snapshot != null) {
            versionLabel.visibility = View.VISIBLE
            val createdAt = java.time.Instant.ofEpochMilli(snapshot.version.createdAtEpochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN))
            versionLabel.text = "Piano alimentare generato il $createdAt"
        } else versionLabel.visibility = View.GONE
        historicalBanner.visibility = if (!currentWeek && state.hasPlan) View.VISIBLE else View.GONE

        val empty = findViewById<TextView>(R.id.emptyPlanText)
        val providerConfigured = aiProviderConfigured
        val aiConfigurationNoticeCard = findViewById<View>(R.id.aiConfigurationNoticeCard)
        revealState(aiConfigurationNoticeCard, !providerConfigured)
        val goalChangedNotice = findViewById<TextView>(R.id.goalChangedNotice)
        revealState(goalChangedNotice, currentWeek && state.goalChangedSinceGeneration)
        val day = state.selectedDay
        val dayMealsCard = findViewById<View>(R.id.dayMealsCard)
        val hasDayContent = day != null && (day.meals.isNotEmpty() || day.supplements.isNotEmpty() || !day.hydrationNote.isNullOrBlank())
        revealState(dayMealsCard, state.hasPlan && hasDayContent)
        revealState(empty, !state.hasPlan)
        if (!state.hasPlan) {
            empty.text = if (currentWeek) getString(R.string.food_plan_empty_current) else getString(R.string.food_plan_empty_history)
        }

        val planStateCard = findViewById<View>(R.id.planStateCard)
        val weekActionsCard = findViewById<View>(R.id.weekActionsCard)
        val dailyTotalCard = findViewById<View>(R.id.dailyTotalCard)
        val nutritionEstimateCard = findViewById<View>(R.id.nutritionEstimateCard)
        val generatedContentVisible = state.hasPlan
        val hasPlanStateMessage = state.goalChangedSinceGeneration || state.generation.running || state.generation.error != null || state.generation.successMessage != null
        revealState(planStateCard, hasPlanStateMessage)
        revealState(weekActionsCard, generatedContentVisible)
        revealState(dailyTotalCard, generatedContentVisible)
        revealState(nutritionEstimateCard, generatedContentVisible)

        renderGeneration(state, currentWeek)
        renderMeals(state.weekStart, day, state.consumptionRecords)
        renderTotals(day, state.snapshot?.version, state.consumptionRecords, state.calorieReference)
    }

    private fun renderGeneration(state: FoodPlanViewModel.State, currentWeek: Boolean) {
        if (state.generation.successMessage != null) {
            data.activeProfileStore.currentIdOrNull()?.let { profileId ->
                data.nutritionPlanUpdatePreferences.setPending(profileId, false)
            }
        }
        val button = findViewById<MaterialButton>(R.id.generatePlanButton)
        val statusContainer = findViewById<View>(R.id.generationStatusContainer)
        val progress = findViewById<ProgressBar>(R.id.generationProgress)
        val status = findViewById<TextView>(R.id.generationStatusText)
        val stateDot = findViewById<View>(R.id.planStateDot)
        val generation = state.generation
        revealState(button, currentWeek)
        setAiActionEnabled(button, currentWeek && !generation.running)
        button.text = when { generation.running -> "Generazione in corso…"; state.hasPlan -> "Rigenera piano con IA"; else -> "Genera piano con IA" }
        if (state.hasPlan && !generation.running) {
            button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.surface_primary))
            button.setTextColor(getColor(R.color.accent_green_dark))
            button.strokeWidth = resources.getDimensionPixelSize(R.dimen.space_1)
            button.strokeColor = ColorStateList.valueOf(getColor(R.color.accent_green))
        } else {
            button.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent_green))
            button.setTextColor(getColor(R.color.white))
            button.strokeWidth = 0
        }
        val message = when {
            generation.running -> "Il piano viene generato e validato localmente prima del salvataggio."
            generation.error != null -> generation.error
            generation.successMessage != null -> listOfNotNull(generation.successMessage, generation.usageMessage).joinToString("\n")
            state.goalChangedSinceGeneration -> "I dati del profilo sono cambiati: puoi rigenerare il piano per aggiornarlo."
            else -> null
        }
        stateDot.background = getDrawable(
            when {
                generation.error != null -> R.drawable.bg_status_dot_error
                generation.running -> R.drawable.bg_status_dot_warning
                generation.successMessage != null -> R.drawable.bg_status_dot_success
                state.goalChangedSinceGeneration -> R.drawable.bg_status_dot_warning
                else -> R.drawable.bg_status_dot_neutral
            }
        )
        revealState(statusContainer, currentWeek && message != null)
        revealState(progress, generation.running)
        status.text = message.orEmpty()
    }

    private fun revealState(view: View, visible: Boolean) {
        val previous = animatedVisibilityTargets.put(view.id, visible)
        UiMotion.reveal(view, visible, animateChange = previous != null)
    }

    private fun renderMeals(weekStart: LocalDate, day: FoodPlanDay?, records: List<com.myfitai.app.data.local.entity.FoodConsumptionEntity>) {
        val container = findViewById<LinearLayout>(R.id.mealsContainer)
        container.removeAllViews()
        day?.meals?.sortedWith(compareBy<FoodMeal> { it.timeMinutes ?: Int.MAX_VALUE }.thenBy { it.sortOrder })?.forEach { meal ->
            val changeEnabled = aiProviderConfigured && canChangeMeal(day.dateEpochDay, meal.timeMinutes) && meal.kcal != null
            val status = records.firstOrNull { it.mealId == meal.id }?.status
            val row = MealPlanRowView(this).apply {
                setTitle(displayMealType(meal.type)); setKcal(NutritionEstimateFormatter.formatEstimatedKcal(meal.kcal)); setDescription(meal.title); setImage(imageFor(meal))
                setStatus(when (status) {
                    FoodConsumptionStatus.CONSUMED.name -> "✓ Consumato"
                    FoodConsumptionStatus.SKIPPED.name -> "Saltato"
                    else -> null
                })
                setOnClickListener { openMeal(meal.id) }; setChangeEnabled(changeEnabled)
                if (changeEnabled) setOnChangeClickListener { openMealAlternatives(weekStart, day, meal) }
                contentDescription = "${displayMealType(meal.type)}: ${meal.title}"
            }
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = resources.getDimensionPixelSize(R.dimen.space_8) })
        }
        day?.supplements?.takeIf { it.isNotEmpty() }?.let { supplements ->
            container.addView(supplementsCard(supplements), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.space_12) })
        }
        day?.hydrationNote?.takeIf { it.isNotBlank() }?.let { container.addView(infoRow("Idratazione", it)) }
    }

    private fun supplementsCard(supplements: List<FoodSupplement>): MaterialCardView = MaterialCardView(this).apply {
        setCardBackgroundColor(getColor(R.color.white))
        radius = resources.getDimension(R.dimen.radius_card)
        strokeWidth = resources.getDimensionPixelSize(R.dimen.space_1)
        setStrokeColor(getColor(R.color.divider))
        cardElevation = 0f
        val content = LinearLayout(this@FoodPlanActivity).apply {
            orientation = LinearLayout.VERTICAL
            val cardPadding = resources.getDimensionPixelSize(R.dimen.card_content_padding)
            setPadding(cardPadding, resources.getDimensionPixelSize(R.dimen.space_12), cardPadding, resources.getDimensionPixelSize(R.dimen.space_12))
        }
        addView(content)
        content.addView(TextView(this@FoodPlanActivity).apply {
            text = "Integrazione"
            setTextAppearance(R.style.Text_MyFitAI_Section)
        })
        content.addView(TextView(this@FoodPlanActivity).apply {
            text = "Dose, orario e valori nutrizionali"
            setTextAppearance(R.style.Text_MyFitAI_Caption)
        }, marginTopParams(2))

        supplements.forEachIndexed { index, supplement ->
            if (index > 0) content.addView(View(this@FoodPlanActivity).apply {
                setBackgroundColor(getColor(R.color.divider))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.space_1)).apply { topMargin = resources.getDimensionPixelSize(R.dimen.space_4) })
            content.addView(supplementRow(supplement), marginTopParams(4))
        }
    }

    private fun supplementRow(supplement: FoodSupplement): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val verticalPadding = resources.getDimensionPixelSize(R.dimen.space_6)
        setPadding(0, verticalPadding, 0, verticalPadding)
        val timing = supplement.timeMinutes?.let { "%02d:%02d".format(it / 60, it % 60) }
        val details = listOfNotNull(
            timing,
            supplement.notes?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        addView(LinearLayout(this@FoodPlanActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(KeyValueRowView(this@FoodPlanActivity).apply {
                setKey(supplement.name)
                setValue("${formatMacro(supplement.dose)} ${supplement.unit}")
            })
            addView(TextView(this@FoodPlanActivity).apply {
                text = details
                setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
            }, marginTopParams(2))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(LinearLayout(this@FoodPlanActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END
            addView(TextView(this@FoodPlanActivity).apply {
                text = NutritionEstimateFormatter.formatEstimatedKcal(supplement.kcal)
                setTextAppearance(R.style.Text_MyFitAI_KeyValueValue)
                gravity = android.view.Gravity.END
            })
            addView(TextView(this@FoodPlanActivity).apply {
                text = listOf(
                    "P ${NutritionEstimateFormatter.formatEstimatedMacro(supplement.proteinG, "g")}",
                    "C ${NutritionEstimateFormatter.formatEstimatedMacro(supplement.carbsG, "g")}",
                    "G ${NutritionEstimateFormatter.formatEstimatedMacro(supplement.fatG, "g")}",
                ).joinToString(" · ")
                setTextAppearance(R.style.Text_MyFitAI_Micro)
                gravity = android.view.Gravity.END
            }, marginTopParams(2))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun marginTopParams(dimenRes: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = resources.getDimensionPixelSize(dimenRes) }

    private fun infoRow(title: String, body: String) = TextView(this).apply {
        text = "$title\n$body"
        setTextAppearance(R.style.Text_MyFitAI_Body)
        val hPadding = resources.getDimensionPixelSize(R.dimen.card_content_padding)
        val vPadding = resources.getDimensionPixelSize(R.dimen.space_12)
        setPadding(hPadding, vPadding, hPadding, vPadding)
        contentDescription = "$title: $body"
    }

    private fun renderTotals(
        day: FoodPlanDay?,
        version: FoodPlanVersion?,
        records: List<com.myfitai.app.data.local.entity.FoodConsumptionEntity>,
        calorieReference: FoodPlanViewModel.CalorieReference,
    ) {
        val totalContainer = findViewById<View>(R.id.dailyTotalContainer); val totalHeader = findViewById<View>(R.id.dailyTotalHeader)
        if (day == null) { totalContainer.visibility = View.GONE; totalHeader.visibility = View.GONE; return }
        val totals = FoodPlanMetrics.dayTotals(day)
        val dayRecords = records.filter { it.planVersionId == version?.id && it.plannedDateEpochDay == day.dateEpochDay }
        val consumed = FoodConsumptionMetrics.dayTotals(dayRecords)
        val consumedItems = dayRecords.filter { it.status == FoodConsumptionStatus.CONSUMED.name }
        val consumedKcal = consumedItems.takeIf { items -> items.isNotEmpty() && items.all { it.kcal != null } }
            ?.sumOf { it.kcal!!.toDouble() }
        val consumedProtein = consumedItems.takeIf { items -> items.isNotEmpty() && items.all { it.proteinG != null } }
            ?.sumOf { it.proteinG!!.toDouble() }
        totalContainer.visibility = View.VISIBLE; totalHeader.visibility = View.VISIBLE
        val selectedDate = LocalDate.ofEpochDay(day.dateEpochDay)
        val dayLabel = selectedDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ITALIAN))
            .replaceFirstChar { it.uppercase() }
        findViewById<TextView>(R.id.dailyTotalTitle).text = "Totale giornaliero · $dayLabel"
        val dayTargetKcal = day.targetKcal ?: version?.targetKcal
        val dayTargetProtein = day.targetProteinG ?: version?.targetProteinG
        val baseTargetKcal = day.baseTargetKcal ?: day.targetKcal ?: version?.targetKcal
        val targetAdjustment = if (baseTargetKcal != null && dayTargetKcal != null) baseTargetKcal - dayTargetKcal else null
        findViewById<TextView>(R.id.dailyTotalLegend).text =
            "BMR: ${calorieReference.bmrKcal?.let { formatKcal(it) } ?: "non disponibile"} a riposo · " +
                "TDEE: ${calorieReference.tdeeKcal?.let { formatKcal(it) } ?: "non disponibile"} con attività abituale. " +
                "Non è una stima dell'allenamento singolo."
        val rows = listOf(
            R.id.dailyTotalRowBmr,
            R.id.dailyTotalRowTdee,
            R.id.dailyTotalRowTarget,
            R.id.dailyTotalRowPlanned,
            R.id.dailyTotalRowConsumed,
        )
        rows.forEach { findViewById<View>(it).contentDescription = null }
        findViewById<View>(R.id.dailyTotalRowBmr).visibility = if (calorieReference.bmrKcal != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.totalKcalBmr).text = formatKcal(calorieReference.bmrKcal)
        findViewById<TextView>(R.id.totalKcalTdee).text = formatKcal(calorieReference.tdeeKcal)
        findViewById<TextView>(R.id.dailyTargetLabel).text = when {
            targetAdjustment != null && targetAdjustment != 0 -> if (targetAdjustment > 0) "Target adattato · −${targetAdjustment} kcal" else "Target adattato · +${-targetAdjustment} kcal"
            day.targetKcal != null -> "Target del giorno"
            else -> "Target medio piano"
        }
        findViewById<View>(R.id.dailyTotalRowTarget).visibility = if (dayTargetKcal != null || dayTargetProtein != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.totalKcalTarget).text = formatKcal(dayTargetKcal)
        findViewById<TextView>(R.id.totalProteinTarget).text = formatValue(dayTargetProtein, "g")
        findViewById<TextView>(R.id.totalKcalPlanned).text = NutritionEstimateFormatter.formatEstimatedKcal(totals.kcal)
        findViewById<TextView>(R.id.totalProteinPlanned).text = NutritionEstimateFormatter.formatEstimatedMacro(totals.proteinG, "g")
        findViewById<TextView>(R.id.totalKcalConsumed).text = formatConsumed(consumedKcal, "kcal")
        findViewById<TextView>(R.id.totalProteinConsumed).text = formatConsumed(consumedProtein, "g")
        findViewById<View>(R.id.dailyTotalRowBmr).contentDescription = calorieReference.bmrKcal?.let { "Metabolismo a riposo, ${formatKcal(it)}" }
        findViewById<View>(R.id.dailyTotalRowTdee).contentDescription = calorieReference.tdeeKcal?.let { "Consumo con attività abituale, ${formatKcal(it)}" }
        findViewById<View>(R.id.dailyTotalRowTarget).contentDescription = "${findViewById<TextView>(R.id.dailyTargetLabel).text}: ${formatKcal(dayTargetKcal)}, proteine ${formatValue(dayTargetProtein, "g")}"
        findViewById<View>(R.id.dailyTotalRowPlanned).contentDescription = "Nel menu: ${NutritionEstimateFormatter.formatEstimatedKcal(totals.kcal)}, proteine ${NutritionEstimateFormatter.formatEstimatedMacro(totals.proteinG, "g")}"
        findViewById<View>(R.id.dailyTotalRowConsumed).contentDescription = "Consumate: ${formatConsumed(consumedKcal, "kcal")}, proteine ${formatConsumed(consumedProtein, "g")}"
        findViewById<View>(R.id.dailyTotalRowPlanned).visibility = if (totals.kcal != null || totals.proteinG != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.dailyTotalRowConsumed).visibility = View.VISIBLE
        val consumedRowLabel = findViewById<View>(R.id.dailyTotalRowConsumed).findViewById<TextView>(R.id.dailyConsumedLabel)
        consumedRowLabel.text = when {
            dayRecords.isEmpty() -> "Consumate · non registrate"
            consumedItems.isEmpty() && consumed.skippedCount > 0 -> "Consumate · tutte saltate"
            else -> "Consumate"
        }
        findViewById<TextView>(R.id.totalConsumptionNote).text = when {
            dayRecords.isEmpty() -> "Nessun pasto o integratore registrato."
            consumedItems.isEmpty() && consumed.skippedCount > 0 -> "Tutti gli elementi registrati risultano saltati."
            consumedItems.any { it.kcal == null || it.proteinG == null } -> "Alcuni valori nutrizionali degli elementi consumati non sono disponibili."
            consumedItems.size < dayRecords.size -> "Totale dei soli ${consumedItems.size} elementi segnati come consumati."
            else -> "Totale dei ${consumedItems.size} elementi segnati come consumati."
        }
        val expected = day.meals.size + day.supplements.size
        findViewById<TextView>(R.id.consumptionCoverage).text = if (dayRecords.isEmpty()) {
            "Nessuna registrazione su $expected elementi pianificati"
        } else {
            "Registrati: ${consumed.recordedCount} di $expected elementi · consumati ${consumed.consumedCount} · saltati ${consumed.skippedCount}"
        }
    }

    private fun showTotalsHelp() {
        showHelpCard(
            "Come leggere le calorie",
            
            "BMR a riposo: energia stimata senza applicare il livello di attività.\n\n" +
                    "TDEE / attività abituale: stima che applica il livello di attività selezionato nel profilo. Non è una misurazione dell'allenamento singolo.\n\n" +
                    "Target del piano: calorie e proteine previste per il giorno selezionato; possono variare per adattamento dello storico o recupero distribuito.\n\n" +
                    "Piano: somma nutrizionale degli alimenti e degli integratori programmati.\n\n" +
                    "Consumate: somma dei soli elementi segnati come consumati nel giorno selezionato. Le portate non registrate non vengono conteggiate come zero effettivo."
        )
    }

    private fun formatKcal(value: Number?): String = value?.let { "${it.toDouble().toInt()} kcal" } ?: "—"

    private fun formatValue(value: Number?, unit: String): String = value?.let {
        if (unit == "kcal") "${it.toInt()} $unit" else "${formatMacro(it.toDouble())} $unit"
    } ?: "—"

    private fun formatConsumed(value: Double?, unit: String): String = formatValue(value, unit)

    private fun openMeal(mealId: Long) = startActivity(Intent(this, MealDetailActivity::class.java).putExtra(MealDetailActivity.EXTRA_MEAL_ID, mealId))
    private fun openMealAlternatives(weekStart: LocalDate, day: FoodPlanDay, meal: FoodMeal) {
        mealAlternativeLauncher.launch(Intent(this, MealAlternativeActivity::class.java)
            .putExtra(MealAlternativeActivity.EXTRA_WEEK_START_EPOCH_DAY, weekStart.toEpochDay())
            .putExtra(MealAlternativeActivity.EXTRA_DAY_EPOCH_DAY, day.dateEpochDay)
            .putExtra(MealAlternativeActivity.EXTRA_MEAL_ID, meal.id)
            .putExtra(MealAlternativeActivity.EXTRA_MEAL_TITLE, meal.title)
            .putExtra(MealAlternativeActivity.EXTRA_MEAL_TYPE, displayMealType(meal.type))
            .putExtra(MealAlternativeActivity.EXTRA_MEAL_KCAL, meal.kcal ?: -1))
    }

    private fun canChangeMeal(dayEpochDay: Long, timeMinutes: Int?): Boolean {
        val date = LocalDate.ofEpochDay(dayEpochDay); val today = LocalDate.now()
        if (date.isBefore(today)) return false
        if (date.isAfter(today) || timeMinutes == null) return true
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        return timeMinutes > now
    }

    private fun imageFor(meal: FoodMeal): Int = when (meal.type.trim().lowercase(Locale.ROOT)) {
        "colazione", "breakfast" -> R.drawable.img_meal_breakfast; "spuntino", "snack" -> R.drawable.img_meal_snack; "pranzo", "lunch" -> R.drawable.img_meal_lunch; "pre-workout", "preworkout" -> R.drawable.img_meal_preworkout; "cena", "dinner" -> R.drawable.img_meal_dinner; else -> R.drawable.img_meal_lunch
    }
    private fun displayMealType(type: String): String = type.trim().ifBlank { "Pasto" }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
    private fun formatWeekRange(start: LocalDate, end: LocalDate): String {
        val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)
        return if (start.month == end.month) "${start.dayOfMonth} – ${end.dayOfMonth} ${end.format(monthFormatter).replaceFirstChar { it.uppercase() }}" else "${start.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN))} – ${end.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN))} ${end.year}"
    }
    private fun formatMacro(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    private fun formatMacro(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)
    companion object { const val EXTRA_WEEK_START_EPOCH_DAY = "week_start_epoch_day" }
}
