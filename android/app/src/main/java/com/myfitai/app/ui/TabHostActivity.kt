package com.myfitai.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderAccess
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.navigation.BottomNavBinder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The only root tab Activity. Tabs are real Fragment instances kept by FragmentManager. */
class TabHostActivity : AppCompatActivity() {
    private val scope = MainScope()
    private val data by lazy { AppDataContainer.get(this) }
    private var currentTab = BottomNavBinder.Tab.HOME
    private var profileSwitcher: AutoCompleteTextView? = null
    private var profileHeader: View? = null
    private var profiles: List<UserProfileEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_tab_host)
        installWindowInsets()
        buildHeader()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    showExitConfirmation()
                }
            }
        })
        currentTab = savedInstanceState?.getString(STATE_TAB)?.let { runCatching { BottomNavBinder.Tab.valueOf(it) }.getOrNull() }
            ?: intent.getStringExtra(BottomNavBinder.EXTRA_INITIAL_TAB)?.let { runCatching { BottomNavBinder.Tab.valueOf(it) }.getOrNull() }
            ?: BottomNavBinder.Tab.HOME
        if (currentTab == BottomNavBinder.Tab.FOOD && !AiProviderAccess.isConfigured(this)) {
            currentTab = BottomNavBinder.Tab.HOME
        }
        BottomNavBinder.bind(this, currentTab)
        initializeTabs(currentTab)
        intent.getLongExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(::pendingFoodWeek)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(BottomNavBinder.EXTRA_INITIAL_TAB)?.let {
            runCatching { BottomNavBinder.Tab.valueOf(it) }.getOrNull()?.let(::selectTab)
        }
        intent.getLongExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(::pendingFoodWeek)
    }

    fun selectTab(tab: BottomNavBinder.Tab) {
        if (tab == BottomNavBinder.Tab.FOOD && !AiProviderAccess.requireConfigured(this)) return
        if (tab == currentTab) return
        currentTab = tab
        showTab(tab)
        BottomNavBinder.bind(this, tab)
    }

    private fun pendingFoodWeek(epochDay: Long) {
        if (currentTab != BottomNavBinder.Tab.FOOD) selectTab(BottomNavBinder.Tab.FOOD)
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentByTag(BottomNavBinder.Tab.FOOD.fragmentTag) as? FoodPlanFragment)?.selectWeek(epochDay)
    }

    private fun showTab(tab: BottomNavBinder.Tab) {
        val transaction = supportFragmentManager.beginTransaction()
        BottomNavBinder.Tab.values().forEach { other ->
            supportFragmentManager.findFragmentByTag(other.fragmentTag)?.let { transaction.hide(it) }
        }
        supportFragmentManager.findFragmentByTag(tab.fragmentTag)?.let(transaction::show)
        transaction.commit()
    }

    private fun initializeTabs(selected: BottomNavBinder.Tab) {
        val transaction = supportFragmentManager.beginTransaction()
        BottomNavBinder.Tab.values().forEach { tab ->
            val fragment = supportFragmentManager.findFragmentByTag(tab.fragmentTag) ?: tab.newFragment()
            if (!fragment.isAdded) transaction.add(R.id.tabContent, fragment, tab.fragmentTag)
            if (tab == selected) transaction.show(fragment) else transaction.hide(fragment)
        }
        transaction.commitNow()
    }

    private fun buildHeader() {
        val container = findViewById<FrameLayout>(R.id.tabHeader)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(12), dp(6))
            setBackgroundColor(getColor(R.color.surface_primary))
        }
        header.addView(TextView(this).apply {
            text = "Profilo"
            textSize = 12f
            setTextColor(getColor(R.color.text_secondary))
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        profileSwitcher = AutoCompleteTextView(this).apply {
            hint = "Seleziona profilo"
            inputType = 0
            isSingleLine = true
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
            setPadding(dp(12), 0, dp(8), 0)
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, position, _ -> selectProfile(position) }
        }
        header.addView(profileSwitcher, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_nutrition_ai)
            contentDescription = "Chiedi un consiglio alimentare all'IA"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { startActivity(Intent(this@TabHostActivity, NutritionAdviceActivity::class.java)) }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        container.addView(header)
        profileHeader = header
        scope.launch {
            data.userProfileRepository.profiles.collectLatest { profiles ->
                this@TabHostActivity.profiles = profiles
                profileHeader?.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                profileSwitcher?.setAdapter(ArrayAdapter(this@TabHostActivity, android.R.layout.simple_dropdown_item_1line, profiles.map { it.name } + "+ Nuovo profilo"))
                renderActiveProfile(profiles)
            }
        }
        scope.launch {
            data.activeProfileStore.activeProfileId.collectLatest { renderActiveProfile(profiles) }
        }
    }

    private fun renderActiveProfile(profiles: List<UserProfileEntity>) {
        profiles.firstOrNull { it.id == data.activeProfileStore.currentIdOrNull() }?.let { profileSwitcher?.setText(it.name, false) }
    }

    private fun selectProfile(position: Int) {
        if (position == profiles.size) {
            startActivity(Intent(this, ProfileEditActivity::class.java).putExtra(ProfileEditActivity.EXTRA_CREATE, true))
            return
        }
        profiles.getOrNull(position)?.let {
            data.activeProfileStore.selectProfile(it.id, makeDefault = true)
            selectTab(BottomNavBinder.Tab.HOME)
        }
    }

    fun showExitConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Uscire da MyFitAI?")
            .setMessage("Vuoi chiudere l'app?")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Esci") { _, _ -> finishAffinity() }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TAB, currentTab.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun installWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private val BottomNavBinder.Tab.fragmentTag: String
        get() = "root-tab-${name.lowercase()}"

    private fun BottomNavBinder.Tab.newFragment(): Fragment = when (this) {
        BottomNavBinder.Tab.HOME -> HomeFragment()
        BottomNavBinder.Tab.FOOD -> FoodPlanFragment()
        BottomNavBinder.Tab.PROGRESS -> PhysicalEvolutionFragment()
        BottomNavBinder.Tab.MORE -> SettingsFragment()
    }

    companion object { private const val STATE_TAB = "current_tab" }
}
