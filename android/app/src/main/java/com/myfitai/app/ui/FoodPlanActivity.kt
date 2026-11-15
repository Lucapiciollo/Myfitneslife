package com.myfitai.app.ui

import com.myfitai.app.navigation.AiSettingsEntry
import android.app.Activity
import android.content.Intent
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.FoodMeal
import com.myfitai.app.domain.food.FoodSupplement
import com.myfitai.app.domain.food.FoodPlanDay
import com.myfitai.app.domain.food.FoodPlanMetrics
import com.myfitai.app.domain.food.FoodPlanVersion
import com.myfitai.app.domain.food.NutritionPlanPeriod
import com.myfitai.app.domain.profile.ProfileAgeRule
import com.myfitai.app.data.profile.NutritionPlanSchedulePreferences
import com.myfitai.app.domain.food.FoodConsumptionMetrics
import com.myfitai.app.domain.food.FoodConsumptionStatus
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.food.FoodPlanPresentation
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
import com.myfitai.app.domain.display.DisplayNumberFormatter
import com.myfitai.app.domain.display.DayTargetResolver
import com.myfitai.app.domain.display.FoodTotalsPresentation
import com.myfitai.app.domain.calculation.PlanStaleness
import com.myfitai.app.domain.calculation.ProfileCalculationMapper
import java.time.Instant
import java.time.ZoneId

class FoodPlanActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: FoodPlanViewModel by viewModels {
         FoodPlanViewModel.Factory(data.mealPlanRepository, data.activeProfileStore, data.nutritionPlanGenerationService, data.profileCalculationService, data.notificationScheduler, data.foodConsumptionRepository, data.aiJobScheduler, data.userProfileRepository, data.biaRepository, data.bodyMeasurementRepository, data.nutritionPlanSchedulePreferences)
    }

    private val mealAlternativeLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) recreate()
    }
    private val weekDaySelector by lazy { findViewById<WeekDaySelectorView>(R.id.weekDaySelector) }
    private val animatedVisibilityTargets = mutableMapOf<Int, Boolean>()
    private var suppressDaySelectionMotion = true
    private var lastCalorieReference: FoodPlanViewModel.CalorieReference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_food_plan)
        bindBottom(BottomNavBinder.Tab.FOOD)
        intent.takeIf { it.hasExtra(EXTRA_WEEK_START_EPOCH_DAY) }?.getLongExtra(EXTRA_WEEK_START_EPOCH_DAY, LocalDate.now().toEpochDay())?.let(viewModel::selectWeek)
        intent.takeIf { it.hasExtra(EXTRA_SELECTED_DATE_EPOCH_DAY) }
            ?.getLongExtra(EXTRA_SELECTED_DATE_EPOCH_DAY, LocalDate.now().toEpochDay())
            ?.let { viewModel.selectWeekAndDay(it) }
        findViewById<View>(R.id.prevWeekButton).setOnClickListener { viewModel.previousWeek() }
        findViewById<View>(R.id.nextWeekButton).setOnClickListener { viewModel.nextWeek() }
        findViewById<View>(R.id.shoppingButton).setOnClickListener {
            startActivity(Intent(this, ShoppingListActivity::class.java).putExtra(ShoppingListActivity.EXTRA_WEEK_START_EPOCH_DAY, viewModel.state.value.weekStart.toEpochDay()))
        }
        findViewById<View>(R.id.cheatButton).setOnClickListener {
            val state = viewModel.state.value
            val selectedDate = state.selectedDay?.dateEpochDay
                ?: state.weekStart.plusDays(state.selectedDayIndex.toLong()).toEpochDay()
            startActivity(Intent(this, CheatEntryActivity::class.java)
                .putExtra(CheatEntryActivity.EXTRA_OCCURRED_DATE_EPOCH_DAY, selectedDate))
        }
        gateAiClick(findViewById(R.id.generatePlanButton)) { confirmPlanGeneration() }
        findViewById<View>(R.id.planSettingsButton).setOnClickListener { startActivity(Intent(this, NutritionPlanSettingsActivity::class.java)) }
        findViewById<View>(R.id.aiConfigurationNoticeButton).setOnClickListener { AiSettingsEntry.open(this) }
        bindFoodHelp()
        findViewById<View>(R.id.dailyTotalHelpButton).setOnClickListener { showTotalsHelp() }
        weekDaySelector.setOnDaySelectedListener(viewModel::selectDay)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect(::render) } }
    }

    fun selectWeekFromNavigation(weekStartEpochDay: Long) {
        viewModel.selectWeek(weekStartEpochDay)
    }

    fun selectDateFromNavigation(dateEpochDay: Long) {
        viewModel.selectWeekAndDay(dateEpochDay)
    }

    private fun bindFoodHelp() {
        findViewById<View>(R.id.weekActionsHelpButton).setOnClickListener {
            showHelpCard(
                "Azioni settimana",
                "Apri la lista della spesa per raccogliere gli ingredienti del piano oppure registra uno sgarro per tenerne conto nello storico dei consumi.",
            )
        }
        // Un solo '?' per card dei pasti: spiega pasti del giorno e generazione del piano.
        findViewById<View>(R.id.dayMealsHelpButton).setOnClickListener {
            showHelpCard(
                getString(R.string.food_plan_ux2_meals_help_title),
                getString(R.string.food_plan_ux2_meals_help_body),
            )
        }
    }

    private fun renderMealCountPreference() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        findViewById<TextView>(R.id.mealCountHint).text =
            getString(R.string.food_plan_ux2_meal_count_hint, data.mealCountPreferences.get(profileId))
    }

    /**
     * Il pulsante Genera è uno solo: senza piano vive nella card dello stato vuoto (CTA),
     * con un piano in fondo alla schermata, dopo le azioni della settimana.
     */
    private fun placeGenerateGroup(inEmptyCard: Boolean) {
        val group = findViewById<View>(R.id.planGenerateGroup)
        val target = findViewById<LinearLayout>(if (inEmptyCard) R.id.planGenerateSlotEmpty else R.id.planGenerateSlotBottom)
        if (group.parent === target) return
        (group.parent as? android.view.ViewGroup)?.removeView(group)
        target.addView(group, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun openProfileEditor() = startActivity(Intent(this, ProfileEditActivity::class.java))

    private fun confirmPlanGeneration() {
        if (viewModel.state.value.profileAgeStatus != ProfileAgeRule.Status.VALID) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.food_plan_profile_age_dialog_title)
                .setMessage(getString(R.string.food_plan_profile_age_warning, ProfileAgeRule.MIN_AGE_YEARS, ProfileAgeRule.MAX_AGE_YEARS))
                .setPositiveButton(R.string.food_plan_open_profile) { _, _ -> openProfileEditor() }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        confirmAiRequest("La generazione del piano alimentare") {
            viewModel.generateCurrentWeek()
        }
    }

    private fun render(state: FoodPlanViewModel.State) {
        val weekEnd = state.weekStart.plusDays(6)
        val currentWeek = state.weekStart == LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val profileId = data.activeProfileStore.currentIdOrNull()
        val periodWeeks = profileId?.let(data.nutritionPlanSchedulePreferences::periodWeeks) ?: 1
        val periodCanBeGenerated = !NutritionPlanPeriod.isEntirelyPast(state.weekStart, periodWeeks, LocalDate.now())
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
        // UX7: UNA regola ([PlanStaleness]) condivisa con Home, review e PDF; il flag vale solo per i piani senza impronta.
        val planUpdatePending = PlanStaleness.isStale(
            planFingerprint = state.snapshot?.version?.inputsFingerprint,
            currentFingerprint = state.currentInputsFingerprint,
            legacyPendingFlag = data.activeProfileStore.currentIdOrNull()
                ?.let(data.nutritionPlanUpdatePreferences::isPending) == true,
        )
        val aiConfigurationNoticeCard = findViewById<View>(R.id.aiConfigurationNoticeCard)
        revealState(aiConfigurationNoticeCard, !providerConfigured)
        val day = state.selectedDay
        val hasDayContent = day != null && (day.meals.isNotEmpty() || day.supplements.isNotEmpty() || !day.hydrationNote.isNullOrBlank())
        val scheduleActive = profileId?.let { data.nutritionPlanSchedulePreferences.get(it).enabled } == true
        val visibility = FoodPlanPresentation.resolve(
            FoodPlanPresentation.Inputs(
                hasPlan = state.hasPlan,
                currentWeek = currentWeek,
                periodCanBeGenerated = periodCanBeGenerated,
                generationRunning = state.generation.running,
                generationError = state.generation.error != null,
                generationSuccess = state.generation.successMessage != null,
                profileAgeInvalid = state.profileAgeStatus != ProfileAgeRule.Status.VALID,
                goalChanged = planUpdatePending,
                hasDayContent = hasDayContent,
                scheduleActive = scheduleActive,
            ),
        )
        revealState(findViewById(R.id.goalChangedNotice), visibility.goalChangedBanner)
        revealState(findViewById(R.id.dayMealsCard), visibility.mealsCard)
        revealState(findViewById(R.id.emptyPlanCard), visibility.emptyCard)
        placeGenerateGroup(visibility.generateInEmptyCard)
        revealState(findViewById(R.id.mealCountHint), visibility.mealCountHint)
        if (visibility.mealCountHint) renderMealCountPreference()
        if (!state.hasPlan) {
            empty.text = if (currentWeek) getString(R.string.food_plan_empty_current) else getString(R.string.food_plan_empty_history)
        }

        val weekActionsCard = findViewById<View>(R.id.weekActionsCard)
        val dailyTotalCard = findViewById<View>(R.id.dailyTotalCard)
        val nutritionEstimateCard = findViewById<View>(R.id.nutritionEstimateCard)
        val generatedContentVisible = state.hasPlan
        val selectedDate = state.weekStart.plusDays(state.selectedDayIndex.toLong())
        revealState(weekActionsCard, generatedContentVisible || !selectedDate.isAfter(LocalDate.now()))
        val shoppingButton = findViewById<View>(R.id.shoppingButton)
        val cheatButton = findViewById<View>(R.id.cheatButton)
        shoppingButton.visibility = if (generatedContentVisible) View.VISIBLE else View.GONE
        val canRegisterCheat = !selectedDate.isAfter(LocalDate.now())
        cheatButton.visibility = if (canRegisterCheat) View.VISIBLE else View.GONE
        val showBothWeekActions = generatedContentVisible && canRegisterCheat
        listOf(shoppingButton, cheatButton).forEach { action ->
            action.layoutParams = (action.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (showBothWeekActions) 0 else LinearLayout.LayoutParams.MATCH_PARENT
                weight = if (showBothWeekActions) 1f else 0f
            }
        }
        revealState(dailyTotalCard, generatedContentVisible)
        revealState(nutritionEstimateCard, generatedContentVisible)
        renderNutritionInfo(state.snapshot?.version, day)

        renderGeneration(state, visibility, currentWeek, periodCanBeGenerated)
        renderPlanScheduleStatus(visibility.scheduleStatus)
        renderMeals(state.weekStart, day, state.consumptionRecords)
        renderTotals(day, state.snapshot?.version, state.consumptionRecords, state.calorieReference, planUpdatePending)
    }

    /**
     * L6a: il riquadro informativo dichiara l'origine dei valori del piano (dichiarati dal modello, calcolati dal
     * catalogo o misti); i piani dichiarati mantengono i testi storici. L'avviso proteine e' neutro e riguarda il giorno scelto.
     */
    private fun renderNutritionInfo(version: FoodPlanVersion?, day: FoodPlanDay?) {
        val basis = NutritionSourcePresentation.planBasis(version)
        findViewById<TextView>(R.id.nutritionEstimateTitle).setText(NutritionSourcePresentation.infoTitleRes(basis))
        findViewById<TextView>(R.id.nutritionEstimateBody).setText(NutritionSourcePresentation.infoBodyRes(basis))
        findViewById<View>(R.id.nutritionEstimateIcon).contentDescription =
            getString(NutritionSourcePresentation.infoIconDescriptionRes(basis))
        val note = day?.let { NutritionSourcePresentation.proteinBelowTarget(version?.appValidationJson, it.dateEpochDay) }
        val proteinNote = findViewById<TextView>(R.id.proteinBelowTargetNote)
        proteinNote.visibility = if (note == null) View.GONE else View.VISIBLE
        proteinNote.text = when {
            note == null -> ""
            note.actual != null && note.target != null -> getString(R.string.food_plan_protein_below_target, note.actual, note.target)
            else -> getString(R.string.food_plan_protein_below_target_plain)
        }
    }

    private fun renderGeneration(state: FoodPlanViewModel.State, visibility: FoodPlanPresentation.Visibility, currentWeek: Boolean, periodCanBeGenerated: Boolean) {
        // The pending flag describes the current week's plan: a plan generated for another week does not clear it.
        if (state.generation.successMessage != null && currentWeek) {
            data.activeProfileStore.currentIdOrNull()?.let { profileId ->
                data.nutritionPlanUpdatePreferences.setPending(profileId, false)
            }
        }
        val button = findViewById<MaterialButton>(R.id.generatePlanButton)
        val statusContainer = findViewById<View>(R.id.generationStatusContainer)
        val progress = findViewById<ProgressBar>(R.id.generationProgress)
        val status = findViewById<TextView>(R.id.generationStatusText)
        setAiActionEnabled(findViewById(R.id.aiConfigurationNoticeButton))
        val stateDot = findViewById<View>(R.id.planStateDot)
        val generation = state.generation
        revealState(button, periodCanBeGenerated)
        setAiActionEnabled(button, periodCanBeGenerated && !generation.running)
        button.text = when { generation.running -> "Generazione in corso…"; state.hasPlan -> "Rigenera piano con IA"; else -> "Genera piano con IA" }
        // Stesso pulsante, due stili condivisi: cambiano solo lo stile (Primary/Outlined) e l'enabled.
        applyButtonStyle(button, if (state.hasPlan && !generation.running) R.style.Widget_MyFitAI_Button_Outlined else R.style.Widget_MyFitAI_Button_Primary)
        val statusKind = visibility.statusKind
        val ageWarning = statusKind == FoodPlanPresentation.StatusKind.AGE_WARNING
        val message = when (statusKind) {
            FoodPlanPresentation.StatusKind.RUNNING -> "Il piano viene generato e validato localmente prima del salvataggio."
            FoodPlanPresentation.StatusKind.ERROR -> if (generation.fixProfile) "${generation.error}\n${getString(R.string.food_plan_profile_fix_hint)}" else generation.error
            FoodPlanPresentation.StatusKind.AGE_WARNING -> getString(R.string.food_plan_profile_age_warning, ProfileAgeRule.MIN_AGE_YEARS, ProfileAgeRule.MAX_AGE_YEARS)
            FoodPlanPresentation.StatusKind.SUCCESS -> listOfNotNull(generation.successMessage, generation.usageMessage).joinToString("\n")
            FoodPlanPresentation.StatusKind.GOAL_CHANGED -> "Hai modificato impostazioni che influenzano le calorie: rigenera il piano per applicare i nuovi obiettivi."
            FoodPlanPresentation.StatusKind.NONE -> null
        }
        stateDot.background = getDrawable(
            when (statusKind) {
                FoodPlanPresentation.StatusKind.ERROR -> R.drawable.bg_status_dot_error
                FoodPlanPresentation.StatusKind.AGE_WARNING, FoodPlanPresentation.StatusKind.RUNNING, FoodPlanPresentation.StatusKind.GOAL_CHANGED -> R.drawable.bg_status_dot_warning
                FoodPlanPresentation.StatusKind.SUCCESS -> R.drawable.bg_status_dot_success
                FoodPlanPresentation.StatusKind.NONE -> R.drawable.bg_status_dot_neutral
            }
        )
        // Banner sopra la card dei pasti: ruolo semantico (errore/avviso/info) dai drawable condivisi.
        statusContainer.background = getDrawable(
            when (statusKind) {
                FoodPlanPresentation.StatusKind.ERROR -> R.drawable.bg_error_soft
                FoodPlanPresentation.StatusKind.AGE_WARNING, FoodPlanPresentation.StatusKind.GOAL_CHANGED -> R.drawable.bg_banner_warning
                else -> R.drawable.bg_banner_info
            }
        )
        revealState(statusContainer, visibility.statusBanner)
        revealState(progress, generation.running)
        status.text = message.orEmpty()
        // L'avviso porta all'editor profilo, dove si corregge il dato che blocca la generazione.
        val opensProfile = ageWarning || (statusKind == FoodPlanPresentation.StatusKind.ERROR && generation.fixProfile)
        status.isClickable = opensProfile
        status.setOnClickListener(if (opensProfile) View.OnClickListener { openProfileEditor() } else null)
    }

    /**
     * Applica a un MaterialButton già inflato i valori di uno stile condiviso (Primary/Outlined):
     * il pulsante che cambia ruolo a runtime non ricolora nulla a mano e non duplica i token.
     */
    private fun applyButtonStyle(button: MaterialButton, @androidx.annotation.StyleRes style: Int) {
        val backgroundTint = androidx.appcompat.R.attr.backgroundTint
        val strokeColor = com.google.android.material.R.attr.strokeColor
        val strokeWidth = com.google.android.material.R.attr.strokeWidth
        val textColor = android.R.attr.textColor
        val attrs = intArrayOf(backgroundTint, strokeColor, strokeWidth, textColor).also { it.sort() }
        val array = obtainStyledAttributes(style, attrs)
        try {
            array.getColorStateList(attrs.indexOf(backgroundTint))?.let { button.backgroundTintList = it }
            button.strokeColor = array.getColorStateList(attrs.indexOf(strokeColor))
            button.strokeWidth = array.getDimensionPixelSize(attrs.indexOf(strokeWidth), 0)
            array.getColorStateList(attrs.indexOf(textColor))?.let { button.setTextColor(it) }
        } finally {
            array.recycle()
        }
    }

    private fun revealState(view: View, visible: Boolean) {
        val previous = animatedVisibilityTargets.put(view.id, visible)
        UiMotion.reveal(view, visible, animateChange = previous != null)
    }

    private fun renderMeals(weekStart: LocalDate, day: FoodPlanDay?, records: List<com.myfitai.app.data.local.entity.FoodConsumptionEntity>) {
        val container = findViewById<LinearLayout>(R.id.mealsContainer)
        container.removeAllViews()
        day?.meals?.sortedWith(compareBy<FoodMeal> { it.timeMinutes ?: Int.MAX_VALUE }.thenBy { it.sortOrder })?.forEach { meal ->
        val changeEnabled = canChangeMeal(day.dateEpochDay, meal.timeMinutes) && meal.kcal != null
        val status = records.firstOrNull { it.mealId == meal.id }?.status
            val row = MealPlanRowView(this).apply {
                setTitle(displayMealType(meal.type)); setKcal(NutritionEstimateFormatter.formatEstimatedKcal(meal.kcal)); setDescription(meal.title); setMeal(meal.type, meal.timeMinutes, describe = false)
                setStatus(when (status) {
                    FoodConsumptionStatus.CONSUMED.name -> "✓ Consumato"
                    FoodConsumptionStatus.SKIPPED.name -> "Saltato"
                    else -> null
                })
                setOnClickListener { openMeal(meal.id) }; setChangeEnabled(changeEnabled)
                val consumed = status == FoodConsumptionStatus.CONSUMED.name
                // UX5b: il tocco rapido esiste solo per oggi e per i giorni passati; nei giorni futuri la riga resta apribile.
                val quickConsume: (() -> Unit)? = if (FoodTotalsPresentation.quickConsumeAvailable(day.dateEpochDay, LocalDate.now())) {
                    { onQuickConsume(meal.id, consumed) }
                } else null
                setQuickConsume(displayMealType(meal.type), consumed, quickConsume)
                setOnChangeClickListener { if (changeEnabled) openMealAlternatives(weekStart, day, meal) }
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
        day?.hydrationNote?.takeIf { it.isNotBlank() }?.let {
            container.addView(infoRow("Idratazione", formatHydrationNote(it)))
        }
    }

    /**
     * UX4: registra il pasto come consumato con un tocco, con la stessa logica del dettaglio (MealStatusRecorder).
     * Se e' gia consumato non cambia nulla al tocco: il ripristino resta dietro l'azione "Annulla consumo" dello snackbar.
     * Le righe si ridisegnano da sole: i consumi sono osservati da FoodPlanViewModel (consumptionRepository.all).
     */
    private fun onQuickConsume(mealId: Long, alreadyConsumed: Boolean) {
        val recorder = data.mealStatusRecorder
        if (alreadyConsumed) {
            showMealSnackbar(R.string.meal_ux4_snackbar_already_consumed, R.string.meal_detail_ux4_undo_consumed) {
                lifecycleScope.launch { runCatching { recorder.tap(mealId, FoodConsumptionStatus.CONSUMED) } }
            }
            return
        }
        lifecycleScope.launch {
            val change = runCatching { recorder.markConsumed(mealId) }.getOrNull()
            when {
                change == null -> showMealSnackbar(R.string.meal_ux4_snackbar_error)
                change.changed -> showMealSnackbar(R.string.meal_ux4_snackbar_consumed, R.string.meal_ux4_snackbar_undo) {
                    lifecycleScope.launch { runCatching { recorder.restore(change) } }
                }
            }
        }
    }

    private fun showMealSnackbar(@androidx.annotation.StringRes message: Int, @androidx.annotation.StringRes action: Int? = null, onAction: (() -> Unit)? = null) {
        val snackbar = SnackbarAnchors.make(this, message)
        if (action != null && onAction != null) snackbar.setAction(action) { onAction() }
        snackbar.show()
    }

    private fun renderPlanScheduleStatus(visible: Boolean) {
        val status = findViewById<TextView>(R.id.planScheduleStatusText)
        // Mostrata solo quando rilevante (schedulazione attiva): "non attiva" non è un'informazione utile.
        revealState(status, visible)
        if (!visible) return
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        val config = data.nutritionPlanSchedulePreferences.get(profileId)
        val key = data.nutritionPlanSchedulePreferences.scheduledJobKey(profileId)
        val due = key?.removePrefix("auto-")?.toLongOrNull()
        val frequency = when (config.frequency) {
            com.myfitai.app.data.profile.NutritionPlanSchedulePreferences.Frequency.DAILY -> "giornaliera"
            com.myfitai.app.data.profile.NutritionPlanSchedulePreferences.Frequency.WEEKLY -> "settimanale"
            com.myfitai.app.data.profile.NutritionPlanSchedulePreferences.Frequency.BIWEEKLY -> "bisettimanale"
            com.myfitai.app.data.profile.NutritionPlanSchedulePreferences.Frequency.MONTHLY -> "mensile"
        }
        val next = due?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ITALIAN))
        } ?: "in attesa di programmazione"
        status.text = "Schedulazione attiva: $frequency · prossimo ricalcolo: $next"
    }

    private fun formatHydrationNote(raw: String): String {
        val parts = raw.split('|').map { it.trim() }.filter { it.isNotBlank() && it != "?" }
        if (parts.isEmpty()) return "Segui una regolare idratazione durante la giornata."
        if (parts.size == 1) return parts.single()
        return parts.drop(1).fold(parts.first()) { description, part ->
            when {
                description.endsWith(':') -> "$description $part"
                description.endsWith('.') -> "$description $part"
                else -> "$description. $part"
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun supplementsCard(supplements: List<FoodSupplement>): MaterialCardView = MaterialCardView(this).apply {
        setCardBackgroundColor(getColor(R.color.white))
        radius = resources.getDimension(R.dimen.radius_card)
        strokeWidth = resources.getDimensionPixelSize(R.dimen.border_width_default)
        setStrokeColor(getColor(R.color.divider))
        cardElevation = resources.getDimension(R.dimen.elevation_none)
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
        }, marginTopParams(R.dimen.space_2))

        supplements.forEachIndexed { index, supplement ->
            if (index > 0) content.addView(View(this@FoodPlanActivity).apply {
                setBackgroundColor(getColor(R.color.divider))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.divider_thickness)).apply { topMargin = resources.getDimensionPixelSize(R.dimen.space_4) })
            content.addView(supplementRow(supplement), marginTopParams(R.dimen.space_4))
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
            }, marginTopParams(R.dimen.space_2))
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
            }, marginTopParams(R.dimen.space_2))
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
        planUpdatePending: Boolean,
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
        val planTargetKcal = day.targetKcal ?: version?.targetKcal
        val planTargetProtein = day.targetProteinG ?: version?.targetProteinG
        // Same single target as Home: the plan day's (adaptation and recovery included), or the labelled theoretical one.
        val resolvedTarget = DayTargetResolver.resolve(
            theoreticalKcal = calorieReference.operationalTargetKcal,
            theoreticalProteinG = calorieReference.operationalProteinG,
            planDayKcal = day.targetKcal,
            planDayProteinG = planTargetProtein?.toDouble(),
            planWeekKcal = version?.targetKcal,
        )
        val dayTargetKcal = resolvedTarget.kcal
        val dayTargetProtein = resolvedTarget.proteinG
        val isToday = selectedDate == LocalDate.now()
        val targetLabel = getString(
            if (resolvedTarget.source == DayTargetResolver.Source.PLAN_DAY) { if (isToday) R.string.food_target_label_plan_day else R.string.food_target_label_plan_day_other }
            else if (isToday) R.string.food_target_label_theoretical else R.string.food_target_label_theoretical_other,
        )
        lastCalorieReference = calorieReference
        // UX5b: una sola riga di origine; BMR/TDEE e la spiegazione lunga restano nell'help (showTotalsHelp).
        val origin = FoodTotalsPresentation.origin(resolvedTarget, planUpdatePending)
        val originText = when (origin.kind) {
            FoodTotalsPresentation.Origin.Kind.PLAN_DAY -> getString(R.string.food_ux5b_origin_plan_day)
            FoodTotalsPresentation.Origin.Kind.PLAN_WEEK -> getString(R.string.food_ux5b_origin_plan_week)
            FoodTotalsPresentation.Origin.Kind.PLAN_STALE -> getString(R.string.target_source_plan_stale)
            FoodTotalsPresentation.Origin.Kind.PROFILE -> getString(R.string.food_ux5b_origin_profile)
            FoodTotalsPresentation.Origin.Kind.NONE -> getString(R.string.target_source_none)
        }
        findViewById<TextView>(R.id.dailyTotalLegend).text = origin.profileKcal
            ?.let { "$originText. ${getString(R.string.food_ux5b_profile_target, formatKcal(it))}" }
            ?: originText
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
        findViewById<TextView>(R.id.dailyTdeeLabel).text =
            "Consumo giornaliero (TDEE)"
        findViewById<TextView>(R.id.dailyTargetLabel).text = targetLabel
        findViewById<View>(R.id.dailyTotalRowTarget).visibility = if (dayTargetKcal != null || dayTargetProtein != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.totalKcalTarget).text = formatKcal(dayTargetKcal)
        findViewById<TextView>(R.id.totalProteinTarget).text = formatValue(dayTargetProtein, "g")
        findViewById<TextView>(R.id.totalKcalPlanned).text = NutritionEstimateFormatter.formatEstimatedKcal(totals.kcal)
        findViewById<TextView>(R.id.totalProteinPlanned).text = NutritionEstimateFormatter.formatEstimatedMacro(totals.proteinG, "g")
        findViewById<TextView>(R.id.totalKcalConsumed).text = formatConsumed(consumedKcal, "kcal")
        findViewById<TextView>(R.id.totalProteinConsumed).text = formatConsumed(consumedProtein, "g")
        findViewById<View>(R.id.dailyTotalRowBmr).contentDescription = calorieReference.bmrKcal?.let { "Metabolismo a riposo (BMR), ${formatKcal(it)}" }
         findViewById<View>(R.id.dailyTotalRowTdee).contentDescription = calorieReference.tdeeKcal?.let { "Consumo giornaliero (TDEE), ${formatKcal(it)}" }
        findViewById<View>(R.id.dailyTotalRowTarget).contentDescription = getString(R.string.food_target_description, targetLabel, formatKcal(dayTargetKcal), formatValue(dayTargetProtein, "g"), formatKcal(resolvedTarget.theoreticalKcal))
        findViewById<View>(R.id.dailyTotalRowPlanned).contentDescription = "Nel menu: ${NutritionEstimateFormatter.formatEstimatedKcal(totals.kcal)}, proteine ${NutritionEstimateFormatter.formatEstimatedMacro(totals.proteinG, "g")}"
        findViewById<View>(R.id.dailyTotalRowConsumed).contentDescription = "Consumate: ${formatConsumed(consumedKcal, "kcal")}, proteine ${formatConsumed(consumedProtein, "g")}"
        findViewById<View>(R.id.dailyTotalRowPlanned).visibility = if (totals.kcal != null || totals.proteinG != null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.dailyTotalRowConsumed).visibility = View.VISIBLE
        val macrosVisible = FoodPlanPresentation.macroColumnsVisible(totals)
        findViewById<View>(R.id.dailyTotalMacros).visibility = if (macrosVisible) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.totalCarbsPlanned).text = NutritionEstimateFormatter.formatEstimatedMacro(totals.carbsG, "g")
        findViewById<TextView>(R.id.totalFatPlanned).text = NutritionEstimateFormatter.formatEstimatedMacro(totals.fatG, "g")
        findViewById<View>(R.id.dailyTotalRowMacrosPlanned).contentDescription = getString(
            R.string.food_plan_ux2_macro_menu_description,
            NutritionEstimateFormatter.formatEstimatedMacro(totals.carbsG, "g"),
            NutritionEstimateFormatter.formatEstimatedMacro(totals.fatG, "g"),
        )
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
            consumedItems.size < dayRecords.size ->
                resources.getQuantityString(R.plurals.food_ux6_total_only_consumed, consumedItems.size, consumedItems.size)
            else -> resources.getQuantityString(R.plurals.food_ux6_total_all_consumed, consumedItems.size, consumedItems.size)
        }
        val expected = day.meals.size + day.supplements.size
        findViewById<TextView>(R.id.consumptionCoverage).text = if (dayRecords.isEmpty()) {
            resources.getQuantityString(R.plurals.food_ux6_coverage_none, expected, expected)
        } else {
            resources.getQuantityString(
                R.plurals.food_ux6_coverage_recorded, expected,
                expected, consumed.recordedCount, consumed.consumedCount, consumed.skippedCount,
            )
        }
    }

    private fun showTotalsHelp() {
        val reference = lastCalorieReference
        val bmr = reference?.bmrKcal?.let { formatKcal(it) } ?: "non disponibile"
        val tdee = reference?.tdeeKcal?.let { formatKcal(it) } ?: "non disponibile"
        showHelpCard(
            "Come leggere le calorie",
            "Metabolismo a riposo (BMR): $bmr. Energia stimata senza applicare il livello di attività.\n\n" +
                    "Consumo giornaliero (TDEE): $tdee. Stima locale calcolata sul profilo e sul livello di attività scelto, uguale per tutti i giorni.\n\n" +
                    "Obiettivo di oggi (o del giorno selezionato): se il piano ha una voce per quel giorno è l'obiettivo del piano, con adattamento e recupero già applicati, uguale a quello mostrato in Home. Senza piano per il giorno è l'obiettivo da profilo (profilo e attività), indicato come tale; viene mostrato anche quello da profilo quando differisce.\n\n" +
                    "Piano: somma nutrizionale degli alimenti e degli integratori programmati.\n\n" +
                    "Consumate: somma dei soli elementi segnati come consumati nel giorno selezionato. Le portate non registrate non vengono conteggiate come zero effettivo."
        )
    }

    private fun formatKcal(value: Number?): String = value?.let { DisplayNumberFormatter.kcal(it.toDouble()) } ?: "-"

    private fun formatValue(value: Number?, unit: String): String = value?.let {
        if (unit == "kcal") DisplayNumberFormatter.kcal(it.toDouble()) else "${formatMacro(it.toDouble())} $unit"
    } ?: "-"

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

    private fun displayMealType(type: String): String = type.trim().ifBlank { "Pasto" }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
    private fun formatWeekRange(start: LocalDate, end: LocalDate): String {
        val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ITALIAN)
        return if (start.month == end.month) "${start.dayOfMonth} – ${end.dayOfMonth} ${end.format(monthFormatter).replaceFirstChar { it.uppercase() }}" else "${start.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN))} – ${end.format(DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN))} ${end.year}"
    }
    private fun formatMacro(value: Float): String = DisplayNumberFormatter.decimal(value)
    private fun formatMacro(value: Double): String = DisplayNumberFormatter.decimal(value)
    companion object {
        const val EXTRA_WEEK_START_EPOCH_DAY = "week_start_epoch_day"
        const val EXTRA_SELECTED_DATE_EPOCH_DAY = "selected_date_epoch_day"
    }
}
