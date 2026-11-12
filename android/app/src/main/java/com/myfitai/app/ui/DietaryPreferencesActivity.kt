package com.myfitai.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.DIETARY_ITEM_MAX_LENGTH
import com.myfitai.app.domain.food.DietaryCategory
import com.myfitai.app.domain.food.DietaryProfile
import com.myfitai.app.domain.food.addItems
import com.myfitai.app.domain.food.canonical
import com.myfitai.app.domain.food.items
import com.myfitai.app.domain.food.removeItem
import kotlinx.coroutines.launch

class DietaryPreferencesActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val isBootstrap by lazy { intent.getBooleanExtra(EXTRA_BOOTSTRAP, false) }
    private lateinit var dietStyle: AutoCompleteTextView
    private lateinit var notes: TextInputEditText
    private lateinit var saveButton: MaterialButton
    private lateinit var loadError: TextView
    private lateinit var saveError: TextView
    private val blocks = LinkedHashMap<DietaryCategory, CategoryBlock>()

    /** Profilo di cui la bozza e' stata caricata: il salvataggio scrive solo su questo e solo se e' ancora quello attivo. */
    private var loadedProfileId: Long? = null
    private var baseline: DietaryProfile? = null
    private var draft: DietaryProfile = DietaryProfile()
    private var saving = false

    private val exitGuard = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = requestExit()
    }

    private class CategoryBlock(
        val root: View,
        val title: TextView,
        val chips: ChipGroup,
        val empty: TextView,
        val input: TextInputEditText,
        val addButton: MaterialButton,
        val message: TextView,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dietary_preferences)
        onBackPressedDispatcher.addCallback(this, exitGuard)
        if (isBootstrap) findViewById<View>(R.id.backButton).visibility = View.INVISIBLE else {
            bindBack()
            findViewById<View>(R.id.backButton).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        bindViews()
        bindDropdown()
        saveButton.setOnClickListener { save() }
        if (!restoreState(savedInstanceState)) loadProfile()
    }

    override fun onResume() {
        super.onResume()
        // Il cambio profilo avviene da altre schermate: se l'attivo non e' piu' quello caricato non si deve scrivere altrove.
        val loaded = loadedProfileId ?: return
        if (!saving && data.activeProfileStore.currentIdOrNull() != loaded) reloadForActiveProfile()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val id = loadedProfileId ?: return
        val base = baseline ?: return
        outState.putLong(STATE_PROFILE_ID, id)
        outState.putString(STATE_BASELINE, base.toJson())
        outState.putString(STATE_DRAFT, currentProfile().toJson())
        outState.putStringArray(STATE_PENDING, DietaryCategory.values().map { pendingText(it) }.toTypedArray())
    }

    private fun restoreState(state: Bundle?): Boolean {
        if (state == null || !state.containsKey(STATE_PROFILE_ID)) return false
        val base = state.getString(STATE_BASELINE) ?: return false
        val saved = state.getString(STATE_DRAFT) ?: return false
        loadedProfileId = state.getLong(STATE_PROFILE_ID)
        baseline = DietaryProfile.parse(base).canonical()
        draft = DietaryProfile.parse(saved).canonical()
        dietStyle.setText(draft.dietStyle ?: "Nessuno", false)
        notes.setText(draft.notes.orEmpty())
        render()
        state.getStringArray(STATE_PENDING)?.forEachIndexed { index, text ->
            DietaryCategory.values().getOrNull(index)?.let { blocks.getValue(it).input.setText(text) }
        }
        return true
    }

    private fun bindViews() {
        dietStyle = findViewById(R.id.dietStyleInput)
        notes = findViewById(R.id.preferencesInput)
        saveButton = findViewById(R.id.saveDietaryPreferencesButton)
        loadError = findViewById(R.id.dietaryLoadError)
        saveError = findViewById(R.id.dietarySaveError)
        val container = findViewById<android.widget.LinearLayout>(R.id.dietaryCategoriesContainer)
        DietaryCategory.values().forEachIndexed { index, category ->
            val root = layoutInflater.inflate(R.layout.item_dietary_category, container, false)
            // I blocchi condividono gli id del layout: lo stato viene salvato manualmente, non dal framework.
            root.isSaveFromParentEnabled = false
            if (index > 0) (root.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin =
                resources.getDimensionPixelSize(R.dimen.space_16)
            val block = CategoryBlock(
                root = root,
                title = root.findViewById(R.id.categoryTitle),
                chips = root.findViewById(R.id.categoryChips),
                empty = root.findViewById(R.id.categoryEmpty),
                input = root.findViewById<TextInputEditText>(R.id.categoryInput).apply { id = inputId(category) },
                addButton = root.findViewById(R.id.categoryAddButton),
                message = root.findViewById(R.id.categoryMessage),
            )
            block.title.text = label(category)
            root.findViewById<TextView>(R.id.categoryHelper).setText(helperRes(category))
            block.empty.setText(
                if (category == DietaryCategory.ALLERGY || category == DietaryCategory.INTOLERANCE) R.string.dietary_empty_hard else R.string.dietary_empty_soft,
            )
            block.addButton.contentDescription = getString(R.string.dietary_add_action_description, label(category))
            block.addButton.setOnClickListener { addFromInput(category) }
            block.input.setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    addFromInput(category)
                    true
                } else false
            }
            block.input.setOnFocusChangeListener { view, focused ->
                if (focused) (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
            container.addView(root)
            blocks[category] = block
        }
        notes.setOnFocusChangeListener { view, focused ->
            if (focused) (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun bindDropdown() {
        dietStyle.setMyFitAiDropdownItems(listOf("Nessuno", "Onnivoro", "Vegetariano", "Vegano", "Pescetariano"))
    }

    private fun loadProfile() {
        setLoaded(false)
        lifecycleScope.launch {
            val id = data.activeProfileStore.currentIdOrNull() ?: return@launch
            val profile = runCatching { data.userProfileRepository.get(id) }.getOrElse {
                // Mai lasciare un form vuoto salvabile sopra dati che non si sono riusciti a leggere.
                showLoadBlocked()
                return@launch
            } ?: return@launch
            val dietary = DietaryProfile.parse(profile.dietaryPreferencesJson).canonical()
            loadedProfileId = id
            baseline = dietary
            draft = dietary
            dietStyle.setText(dietary.dietStyle ?: "Nessuno", false)
            notes.setText(dietary.notes.orEmpty())
            DietaryCategory.values().forEach { blocks.getValue(it).input.setText("") }
            render()
            setLoaded(true)
        }
    }

    private fun reloadForActiveProfile() {
        loadedProfileId = null
        baseline = null
        Toast.makeText(this, R.string.dietary_profile_changed, Toast.LENGTH_LONG).show()
        loadProfile()
    }

    private fun setLoaded(loaded: Boolean) {
        saveButton.isEnabled = loaded
        loadError.visibility = View.GONE
        saveError.visibility = View.GONE
    }

    private fun showLoadBlocked() {
        saveButton.isEnabled = false
        loadError.setText(R.string.dietary_save_blocked_load)
        loadError.visibility = View.VISIBLE
    }

    private fun render() {
        DietaryCategory.values().forEach(::renderCategory)
    }

    private fun renderCategory(category: DietaryCategory) {
        val block = blocks.getValue(category)
        val values = draft.items(category)
        block.chips.removeAllViews()
        values.forEach { value ->
            val chip = layoutInflater.inflate(R.layout.item_dietary_chip, block.chips, false) as Chip
            chip.text = value
            chip.closeIconContentDescription = getString(R.string.dietary_remove_chip, value, label(category))
            chip.setOnCloseIconClickListener { removeValue(category, value) }
            block.chips.addView(chip)
        }
        block.chips.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
        block.empty.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addFromInput(category: DietaryCategory, fromSave: Boolean = false): Boolean {
        val block = blocks.getValue(category)
        val raw = block.input.text?.toString().orEmpty()
        val result = draft.addItems(category, raw)
        when {
            result.tooLong.isNotEmpty() -> {
                showMessage(category, getString(R.string.dietary_error_too_long, DIETARY_ITEM_MAX_LENGTH), error = true)
                return false
            }
            result.isEmptyInput -> {
                showMessage(category, getString(R.string.dietary_error_empty), error = true)
                return false
            }
            result.added.isEmpty() -> {
                if (fromSave) {
                    // Voce gia' presente: non c'e' nulla da perdere, si puo' proseguire con il salvataggio.
                    block.input.setText("")
                    return true
                }
                showMessage(category, getString(R.string.dietary_error_duplicate, result.duplicates.first()), error = true)
                return false
            }
        }
        draft = result.profile
        block.input.setText("")
        saveError.visibility = View.GONE
        renderCategory(category)
        val message = getString(R.string.dietary_message_added, result.added.joinToString(", "), label(category))
        showMessage(category, message, error = false)
        return true
    }

    private fun removeValue(category: DietaryCategory, value: String) {
        val block = blocks.getValue(category)
        val index = draft.items(category).indexOf(value)
        draft = draft.removeItem(category, value)
        saveError.visibility = View.GONE
        renderCategory(category)
        showMessage(category, getString(R.string.dietary_message_removed, value, label(category)), error = false)
        // Il chip rimosso non esiste piu': porta il focus sul successivo o sul titolo della categoria.
        val target = block.chips.getChildAt(index.coerceAtMost(block.chips.childCount - 1)) ?: block.title
        target.requestFocus()
    }

    private fun showMessage(category: DietaryCategory, text: String, error: Boolean) {
        val message = blocks.getValue(category).message
        message.setTextColor(getColor(if (error) R.color.text_primary else R.color.text_secondary))
        message.setBackgroundResource(if (error) R.drawable.bg_error_soft else 0)
        val pad = if (error) resources.getDimensionPixelSize(R.dimen.card_content_padding_compact) else 0
        message.setPadding(pad, pad, pad, pad)
        message.text = text
        message.visibility = View.VISIBLE
    }

    private fun pendingText(category: DietaryCategory): String = blocks.getValue(category).input.text?.toString().orEmpty()

    private fun currentProfile(): DietaryProfile = draft.copy(
        dietStyle = dietStyle.text?.toString(),
        notes = notes.text?.toString(),
    ).canonical()

    private fun isDirty(): Boolean {
        val base = baseline ?: return false
        return DietaryCategory.values().any { pendingText(it).isNotBlank() } || currentProfile() != base
    }

    private fun requestExit() {
        if (!isDirty()) {
            exitGuard.isEnabled = false
            onBackPressedDispatcher.onBackPressed()
            exitGuard.isEnabled = true
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dietary_discard_title)
            .setMessage(R.string.dietary_discard_message)
            .setPositiveButton(R.string.dietary_discard_keep, null)
            .setNegativeButton(R.string.dietary_discard_exit) { _, _ ->
                exitGuard.isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
            .show()
    }

    private fun save() {
        if (saving) return
        val loaded = loadedProfileId
        if (loaded == null || baseline == null) {
            Toast.makeText(this, R.string.dietary_no_profile, Toast.LENGTH_SHORT).show()
            return
        }
        // Il testo digitato ma non ancora aggiunto non deve sparire in silenzio: lo si aggiunge, o si resta qui con l'errore.
        for (category in DietaryCategory.values()) {
            if (pendingText(category).isNotBlank() && !addFromInput(category, fromSave = true)) return
        }
        saving = true
        saveButton.isEnabled = false
        saveError.visibility = View.GONE
        lifecycleScope.launch {
            try {
                if (data.activeProfileStore.currentIdOrNull() != loaded) {
                    saving = false
                    reloadForActiveProfile()
                    return@launch
                }
                val dietary = currentProfile()
                val saved = runCatching {
                    val profile = data.userProfileRepository.get(loaded) ?: error("profile missing")
                    data.userProfileRepository.update(
                        profile.copy(dietaryPreferencesJson = dietary.toJson(), updatedAtEpochMillis = System.currentTimeMillis()),
                    )
                    profile
                }
                val profile = saved.getOrNull()
                if (profile == null) {
                    // La bozza resta intatta: nessun reset di chip o campi.
                    saveError.setText(R.string.dietary_save_error)
                    saveError.visibility = View.VISIBLE
                    saveButton.isEnabled = true
                    saving = false
                    return@launch
                }
                baseline = dietary
                draft = dietary
                Toast.makeText(this@DietaryPreferencesActivity, R.string.dietary_saved, Toast.LENGTH_SHORT).show()
                if (isBootstrap) {
                    val jobKey = if (aiProviderConfigured) data.nutritionPathTrigger.maybeEnqueue(profile.id) else null
                    startActivity(Intent(this@DietaryPreferencesActivity, NutritionPathActivity::class.java).apply {
                        putExtra(NutritionPathActivity.EXTRA_JOB_KEY, jobKey)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    })
                } else finish()
            } finally {
                saving = false
            }
        }
    }

    private fun label(category: DietaryCategory): String = getString(
        when (category) {
            DietaryCategory.PREFERRED -> R.string.dietary_category_preferred
            DietaryCategory.DISLIKED -> R.string.dietary_category_disliked
            DietaryCategory.EXCLUDED -> R.string.dietary_category_excluded
            DietaryCategory.INTOLERANCE -> R.string.dietary_category_intolerance
            DietaryCategory.ALLERGY -> R.string.dietary_category_allergy
        },
    )

    private fun helperRes(category: DietaryCategory): Int = when (category) {
        DietaryCategory.PREFERRED -> R.string.dietary_helper_preferred
        DietaryCategory.DISLIKED -> R.string.dietary_helper_disliked
        DietaryCategory.EXCLUDED -> R.string.dietary_helper_excluded
        DietaryCategory.INTOLERANCE -> R.string.dietary_helper_intolerance
        DietaryCategory.ALLERGY -> R.string.dietary_helper_allergy
    }

    /** Gli id storici dei campi di inserimento restano validi per i test e per l'accessibilita'. */
    private fun inputId(category: DietaryCategory): Int = when (category) {
        DietaryCategory.PREFERRED -> R.id.preferredFoodsInput
        DietaryCategory.DISLIKED -> R.id.dislikedFoodsInput
        DietaryCategory.EXCLUDED -> R.id.excludedFoodsInput
        DietaryCategory.INTOLERANCE -> R.id.intolerancesInput
        DietaryCategory.ALLERGY -> R.id.allergiesInput
    }

    companion object {
        const val EXTRA_BOOTSTRAP = "dietary_preferences_bootstrap"
        private const val STATE_PROFILE_ID = "dietary_state_profile_id"
        private const val STATE_BASELINE = "dietary_state_baseline"
        private const val STATE_DRAFT = "dietary_state_draft"
        private const val STATE_PENDING = "dietary_state_pending"
    }
}
