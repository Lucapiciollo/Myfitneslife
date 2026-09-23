package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.shopping.ShoppingListStateStore
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.shopping.ShoppingListViewModel
import com.myfitai.app.ui.widgets.SectionHeaderView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import com.myfitai.app.ui.widgets.ShoppingItemRowView
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class ShoppingListActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val stateStore by lazy { ShoppingListStateStore(this) }
    private val viewModel: ShoppingListViewModel by viewModels {
        ShoppingListViewModel.Factory(
            plans = data.mealPlanRepository,
            activeProfileStore = data.activeProfileStore,
            stateStore = stateStore,
            initialWeekStartEpochDay = intent.getLongExtra(EXTRA_WEEK_START_EPOCH_DAY, Long.MIN_VALUE)
                .takeUnless { it == Long.MIN_VALUE },
        )
    }
    private var confirmationShownFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shopping_list)
        bindBack()
        bindBottom(BottomNavBinder.Tab.FOOD)
        findViewById<MaterialCardView>(R.id.itemsCard).apply {
            setCardBackgroundColor(getColor(R.color.white))
            strokeColor = getColor(R.color.divider)
            strokeWidth = dp(1)
            cardElevation = 0f
        }

        findViewById<SelectableSegmentView>(R.id.viewModeSegment).apply {
            setSegments(listOf("Settimana", "Categorie"), selectedIndex = 0)
            setOnSegmentSelectedListener(viewModel::setViewMode)
        }
        findViewById<SelectableSegmentView>(R.id.filterSegment).apply {
            setSegments(listOf("Tutte", "Da comprare", "Presi", "Dispensa"), selectedIndex = 0)
            setOnSegmentSelectedListener(viewModel::setFilter)
        }
        findViewById<View>(R.id.resetButton).setOnClickListener { confirmReset() }
        findViewById<View>(R.id.exportButton).setOnClickListener { shareCurrentList() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: ShoppingListViewModel.State) {
        val monday = LocalDate.ofEpochDay(state.weekStartEpochDay)
        val sunday = monday.plusDays(6)
        findViewById<TextView>(R.id.weekLabel).text = formatWeek(monday, sunday)
        findViewById<TextView>(R.id.versionLabel).apply {
            visibility = if (state.versionNumber != null) View.VISIBLE else View.GONE
            text = state.versionNumber?.let { "Lista dal piano v$it" }.orEmpty()
        }
        if (state.requiresFirstOpenConfirmation && state.versionId != null) {
            val confirmationKey = "${state.profileId}:${state.weekStartEpochDay}:${state.versionId}"
            if (confirmationShownFor != confirmationKey) {
                confirmationShownFor = confirmationKey
                showFirstOpenConfirmation(state.versionNumber)
            }
        }
        findViewById<ProgressBar>(R.id.loadingProgress).visibility = if (state.loading) View.VISIBLE else View.GONE

        val toBuy = state.rows.count { it.status == ShoppingListStateStore.Status.TO_BUY }
        val purchased = state.rows.count { it.status == ShoppingListStateStore.Status.PURCHASED }
        val pantry = state.rows.count { it.status == ShoppingListStateStore.Status.PANTRY }
        findViewById<TextView>(R.id.statusSummary).text =
            "${state.rows.size} articoli · $toBuy da comprare · $purchased presi · $pantry in dispensa"

        val empty = findViewById<TextView>(R.id.emptyText)
        val visible = state.visibleRows
        empty.visibility = if (!state.loading && visible.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            state.error != null -> state.error
            state.rows.isEmpty() -> "Nessun piano disponibile per questa settimana, oppure il piano non contiene ingredienti."
            else -> "Nessun articolo per il filtro selezionato."
        }

        val container = findViewById<LinearLayout>(R.id.itemsContainer)
        container.removeAllViews()
        findViewById<View>(R.id.itemsCard).visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.exportButton).isEnabled = visible.isNotEmpty()
        if (visible.isEmpty()) return

        if (state.viewMode == ShoppingListViewModel.ViewMode.CATEGORY) {
            visible.groupBy { it.item.category }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (category, rows) ->
                addHeader(container, category)
                rows.sortedBy { it.item.name.lowercase(Locale.ROOT) }.forEach { addRow(container, it) }
            }
        } else {
            addHeader(container, "Lista settimanale")
            visible.sortedBy { it.item.name.lowercase(Locale.ROOT) }.forEach { addRow(container, it) }
        }
    }

    private fun showFirstOpenConfirmation(versionNumber: Int?) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Salvare la lista della spesa?")
            .setMessage(
                "Questa lista viene aggregata localmente dal piano alimentare e non effettua chiamate IA: " +
                    "non consuma quota IA e non genera costi IA. Verrà memorizzata per il piano v${versionNumber ?: "corrente"}. " +
                    "Se il piano verrà rigenerato, la lista verrà aggiornata e ti verrà chiesta una nuova conferma."
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Conferma", { _, _ -> viewModel.confirmListStored() })
            .show()
    }

    private fun addHeader(container: LinearLayout, title: String) {
        container.addView(
            SectionHeaderView(this).apply { text = title },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            },
        )
    }

    private fun addRow(container: LinearLayout, row: ShoppingListViewModel.Row) {
        val item = row.item
        val view = ShoppingItemRowView(this).apply {
            setName(if (row.status == ShoppingListStateStore.Status.PANTRY) "${item.name} · dispensa" else item.name)
            setQuantity(item.displayQuantity() + item.weightState?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
            setIcon(iconFor(item.category))
            alpha = if (row.status == ShoppingListStateStore.Status.PANTRY) 0.65f else 1f
            setChecked(row.status == ShoppingListStateStore.Status.PURCHASED)
            setOnCheckedChangeListener { checked -> viewModel.togglePurchased(item.key, checked) }
            setOnClickListener { chooseStatus(row) }
            contentDescription = "${item.name}, ${item.displayQuantity()}, ${statusLabel(row.status)}"
        }
        container.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun chooseStatus(row: ShoppingListViewModel.Row) {
        val labels = arrayOf("Da comprare", "Preso", "Già in dispensa")
        val statuses = arrayOf(
            ShoppingListStateStore.Status.TO_BUY,
            ShoppingListStateStore.Status.PURCHASED,
            ShoppingListStateStore.Status.PANTRY,
        )
        val checked = statuses.indexOf(row.status).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(row.item.name)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.setStatus(row.item.key, statuses[which])
                dialog.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confirmReset() {
        if (viewModel.state.value.rows.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Azzera stato lista?")
            .setMessage("Tutti gli articoli torneranno a Da comprare. Le quantità generate dal piano non cambiano.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Azzera") { _, _ -> viewModel.resetStatuses() }
            .show()
    }

    private fun shareCurrentList() {
        val state = viewModel.state.value
        if (state.rows.isEmpty()) return
        val monday = LocalDate.ofEpochDay(state.weekStartEpochDay)
        val text = buildString {
            appendLine("MyFitAI · Lista spesa ${formatWeek(monday, monday.plusDays(6))}")
            state.rows.groupBy { it.item.category }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (category, rows) ->
                appendLine()
                appendLine(category)
                rows.sortedBy { it.item.name.lowercase(Locale.ROOT) }.forEach { row ->
                    val marker = when (row.status) {
                        ShoppingListStateStore.Status.TO_BUY -> "[ ]"
                        ShoppingListStateStore.Status.PURCHASED -> "[x]"
                        ShoppingListStateStore.Status.PANTRY -> "[dispensa]"
                    }
                    appendLine("$marker ${row.item.name}: ${row.item.displayQuantity()}${row.item.weightState?.let { " · $it" }.orEmpty()}")
                }
            }
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Condividi lista della spesa",
            )
        )
    }

    private fun iconFor(category: String): Int {
        val value = category.lowercase(Locale.ROOT)
        return when {
            value.contains("verd") || value.contains("ort") -> R.drawable.img_food_vegetables
            value.contains("frutt") -> R.drawable.img_food_fruits
            value.contains("lat") || value.contains("yog") || value.contains("dairy") -> R.drawable.img_food_dairy
            value.contains("cereal") || value.contains("carbo") || value.contains("pane") || value.contains("pasta") -> R.drawable.img_food_carbs
            else -> R.drawable.img_food_protein
        }
    }

    private fun statusLabel(status: ShoppingListStateStore.Status): String = when (status) {
        ShoppingListStateStore.Status.TO_BUY -> "da comprare"
        ShoppingListStateStore.Status.PURCHASED -> "preso"
        ShoppingListStateStore.Status.PANTRY -> "già in dispensa"
    }

    private fun formatWeek(start: LocalDate, end: LocalDate): String {
        val short = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)
        return "${start.format(short)} – ${end.format(short)} ${end.year}"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_WEEK_START_EPOCH_DAY = "shopping_week_start_epoch_day"
    }
}
