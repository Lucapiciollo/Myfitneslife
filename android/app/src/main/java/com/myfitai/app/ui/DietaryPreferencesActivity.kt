package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.food.DietaryProfile
import kotlinx.coroutines.launch

class DietaryPreferencesActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val isBootstrap by lazy { intent.getBooleanExtra(EXTRA_BOOTSTRAP, false) }
    private lateinit var preferredFoods: TextInputEditText
    private lateinit var dislikedFoods: TextInputEditText
    private lateinit var excludedFoods: TextInputEditText
    private lateinit var intolerances: TextInputEditText
    private lateinit var allergies: TextInputEditText
    private lateinit var dietStyle: AutoCompleteTextView
    private lateinit var notes: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dietary_preferences)
        if (isBootstrap) findViewById<View>(R.id.backButton).visibility = View.INVISIBLE else bindBack()
        bindViews()
        bindDropdown()
        loadProfile()
        findViewById<MaterialButton>(R.id.saveDietaryPreferencesButton).setOnClickListener { save() }
    }

    private fun bindViews() {
        preferredFoods = findViewById(R.id.preferredFoodsInput)
        dislikedFoods = findViewById(R.id.dislikedFoodsInput)
        excludedFoods = findViewById(R.id.excludedFoodsInput)
        intolerances = findViewById(R.id.intolerancesInput)
        allergies = findViewById(R.id.allergiesInput)
        dietStyle = findViewById(R.id.dietStyleInput)
        notes = findViewById(R.id.preferencesInput)
        listOf(preferredFoods, dislikedFoods, excludedFoods, intolerances, allergies, notes).forEach { input ->
            input.setOnFocusChangeListener { view, focused ->
                if (focused) (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun bindDropdown() {
        dietStyle.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf("Nessuno", "Onnivoro", "Vegetariano", "Vegano", "Pescetariano")))
        dietStyle.setOnClickListener { dietStyle.showDropDown() }
    }

    private fun loadProfile() {
        lifecycleScope.launch {
            val id = data.activeProfileStore.currentIdOrNull() ?: return@launch
            val profile = data.userProfileRepository.get(id) ?: return@launch
            val dietary = DietaryProfile.parse(profile.dietaryPreferencesJson)
            preferredFoods.setText(dietary.preferredFoods.joinToString(", "))
            dislikedFoods.setText(dietary.dislikedFoods.joinToString(", "))
            excludedFoods.setText(dietary.excludedFoods.joinToString(", "))
            intolerances.setText(dietary.intolerances.joinToString(", "))
            allergies.setText(dietary.allergies.joinToString(", "))
            dietStyle.setText(dietary.dietStyle ?: "Nessuno", false)
            notes.setText(dietary.notes.orEmpty())
        }
    }

    private fun save() {
        lifecycleScope.launch {
            val id = data.activeProfileStore.currentIdOrNull()
            val profile = id?.let { data.userProfileRepository.get(it) }
            if (profile == null) {
                Toast.makeText(this@DietaryPreferencesActivity, "Nessun profilo attivo", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dietary = DietaryProfile(
                preferredFoods = DietaryProfile.csv(preferredFoods.text?.toString()),
                dislikedFoods = DietaryProfile.csv(dislikedFoods.text?.toString()),
                excludedFoods = DietaryProfile.csv(excludedFoods.text?.toString()),
                intolerances = DietaryProfile.csv(intolerances.text?.toString()),
                allergies = DietaryProfile.csv(allergies.text?.toString()),
                dietStyle = dietStyle.text?.toString()?.trim()?.takeIf { it.isNotBlank() && !it.equals("Nessuno", true) },
                notes = notes.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
            )
            data.userProfileRepository.update(profile.copy(dietaryPreferencesJson = dietary.toJson(), updatedAtEpochMillis = System.currentTimeMillis()))
            Toast.makeText(this@DietaryPreferencesActivity, "Preferenze alimentari salvate", Toast.LENGTH_SHORT).show()
            if (isBootstrap) {
                val jobKey = data.nutritionPathTrigger.maybeEnqueue(profile.id)
                startActivity(Intent(this@DietaryPreferencesActivity, NutritionPathActivity::class.java).apply {
                    putExtra(NutritionPathActivity.EXTRA_JOB_KEY, jobKey)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })
            } else finish()
        }
    }

    companion object { const val EXTRA_BOOTSTRAP = "dietary_preferences_bootstrap" }
}
