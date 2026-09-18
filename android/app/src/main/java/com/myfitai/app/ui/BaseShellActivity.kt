package com.myfitai.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderAccess
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.navigation.BottomNavBinder
import kotlinx.coroutines.launch

/** Shared shell for detail Activities. Root tabs are owned by TabHostActivity. */
abstract class BaseShellActivity : AppCompatActivity() {
    private val shellData by lazy { AppDataContainer.get(this) }
    private var shellProfiles: List<UserProfileEntity> = emptyList()
    private var profileSwitcher: AutoCompleteTextView? = null
    private var profileHeader: View? = null

    protected fun openFoodPlan(weekStartEpochDay: Long? = null): Boolean {
        if (!AiProviderAccess.requireConfigured(this)) return false
        val intent = Intent(this, TabHostActivity::class.java)
            .putExtra(BottomNavBinder.EXTRA_INITIAL_TAB, BottomNavBinder.Tab.FOOD.name)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        weekStartEpochDay?.let { intent.putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, it) }
        startActivity(intent)
        return true
    }

    override fun setContentView(layoutResID: Int) {
        val content = layoutInflater.inflate(layoutResID, null, false)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_primary))
        }
        profileHeader = buildProfileHeader().also { shell.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))) }
        shell.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        super.setContentView(shell)
        content.findViewById<View?>(R.id.bottomNav)?.visibility = View.GONE
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        observeGlobalProfiles()
    }

    private fun buildProfileHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(6), dp(12), dp(6))
        setBackgroundColor(getColor(R.color.surface_primary))
        addView(TextView(this@BaseShellActivity).apply {
            text = "Profilo"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        profileSwitcher = AutoCompleteTextView(this@BaseShellActivity).apply {
            hint = "Seleziona profilo"
            inputType = 0
            isSingleLine = true
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, position, _ -> selectProfile(position) }
        }
        addView(profileSwitcher, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(ImageView(this@BaseShellActivity).apply {
            setImageResource(R.drawable.ic_nutrition_ai)
            contentDescription = "Chiedi un consiglio alimentare all'IA"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { startActivity(Intent(this@BaseShellActivity, NutritionAdviceActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
    }

    private fun observeGlobalProfiles() {
        lifecycleScope.launch {
            shellData.userProfileRepository.profiles.collect { profiles ->
                shellProfiles = profiles
                profileHeader?.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                profileSwitcher?.setAdapter(ArrayAdapter(this@BaseShellActivity, android.R.layout.simple_dropdown_item_1line, profiles.map { it.name } + "+ Nuovo profilo"))
                renderActiveProfile()
            }
        }
    }

    private fun renderActiveProfile() {
        shellProfiles.firstOrNull { it.id == shellData.activeProfileStore.currentIdOrNull() }?.let { profileSwitcher?.setText(it.name, false) }
    }

    private fun selectProfile(position: Int) {
        if (position == shellProfiles.size) {
            startActivity(Intent(this, ProfileEditActivity::class.java).putExtra(ProfileEditActivity.EXTRA_CREATE, true))
            return
        }
        shellProfiles.getOrNull(position)?.let {
            shellData.activeProfileStore.selectProfile(it.id, makeDefault = true)
            startActivity(Intent(this, TabHostActivity::class.java).putExtra(BottomNavBinder.EXTRA_INITIAL_TAB, BottomNavBinder.Tab.HOME.name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    protected fun bindBack() { findViewById<View?>(R.id.backButton)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() } }
    protected fun bindBottom(tab: BottomNavBinder.Tab) { BottomNavBinder.bind(this, tab) }

    protected fun confirmAiRequest(action: String, onConfirmed: () -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle("Confermare richiesta IA?")
            .setMessage("$action invia una richiesta al provider IA e consuma la quota disponibile.")
            .setNegativeButton("Annulla", null).setPositiveButton("Conferma") { _, _ -> onConfirmed() }.show()
    }

    protected fun go(target: Class<out AppCompatActivity>) {
        when (target) {
            HomeActivity::class.java -> openRootTab(BottomNavBinder.Tab.HOME)
            FoodPlanActivity::class.java -> openFoodPlan()
            PhysicalEvolutionActivity::class.java -> openRootTab(BottomNavBinder.Tab.PROGRESS)
            SettingsActivity::class.java -> openRootTab(BottomNavBinder.Tab.MORE)
            else -> startActivity(Intent(this, target))
        }
    }

    private fun openRootTab(tab: BottomNavBinder.Tab) {
        startActivity(Intent(this, TabHostActivity::class.java).putExtra(BottomNavBinder.EXTRA_INITIAL_TAB, tab.name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
