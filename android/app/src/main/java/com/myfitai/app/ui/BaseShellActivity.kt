package com.myfitai.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderAccess
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.navigation.BottomNavBinder
import kotlinx.coroutines.launch

abstract class BaseShellActivity : AppCompatActivity() {
    private val shellData by lazy { AppDataContainer.get(this) }
    private var shellProfiles: List<UserProfileEntity> = emptyList()
    private var profileSwitcher: AutoCompleteTextView? = null
    private var profileHeader: View? = null
    private var isTabRoot = false

    protected fun openFoodPlan(weekStartEpochDay: Long? = null): Boolean {
        if (weekStartEpochDay == null && !AiProviderAccess.requireConfigured(this)) return false
        (parent as? TabHostActivity)?.let {
            it.selectTab(BottomNavBinder.Tab.FOOD)
            weekStartEpochDay?.let { week -> it.currentFoodPlanActivity()?.selectWeekFromNavigation(week) }
            return true
        }
        val intent = Intent(this, TabHostActivity::class.java)
            .putExtra(BottomNavBinder.EXTRA_SELECTED_TAB, BottomNavBinder.Tab.FOOD.name)
            .putExtra(BottomNavBinder.EXTRA_TAB_ROOT, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        weekStartEpochDay?.let { intent.putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, it) }
        startActivity(intent)
        return true
    }

    protected fun showHelpCard(title: String, message: String) {
        runCatching {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(getColor(R.color.white))
                val contentPadding = dimen(R.dimen.space_4)
                setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
            }
            View(this).apply {
                setBackgroundColor(getColor(R.color.accent_green))
                content.addView(this, LinearLayout.LayoutParams(dimen(R.dimen.space_4), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = dimen(R.dimen.space_12)
                })
            }
            TextView(this).apply {
                text = formatHelpMessage(message)
                setTextAppearance(R.style.Text_MyFitAI_Body)
                setTextColor(getColor(R.color.text_primary))
                setPadding(dimen(R.dimen.space_4), dimen(R.dimen.space_8), dimen(R.dimen.space_8), dimen(R.dimen.space_8))
                content.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(content)
                .setPositiveButton("Ho capito", null)
                .show()
        }.onFailure {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Ho capito", null)
                .show()
        }
    }

    private fun formatHelpMessage(message: String): CharSequence {
        val paragraphs = message.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
        return SpannableStringBuilder().apply {
            paragraphs.forEachIndexed { index, paragraph ->
                val cleanParagraph = paragraph.trim().removePrefix("• ").trim()
                val separator = cleanParagraph.indexOf(": ")
                if (separator > 0) {
                    val heading = cleanParagraph.substring(0, separator)
                    val description = cleanParagraph.substring(separator + 2).trim()
                    val headingStart = length
                    append(heading)
                    setSpan(StyleSpan(Typeface.BOLD), headingStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("\n").append(description)
                } else {
                    append(cleanParagraph)
                }
                if (index < paragraphs.lastIndex) append("\n\n")
            }
        }
    }

    private val tabRootBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleBackNavigation()
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        shellData.activeProfileStore.refreshFromPersistence()
        isTabRoot = isRootTabIntent(intent)
        onBackPressedDispatcher.addCallback(this, tabRootBackCallback)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isTabRoot = isRootTabIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        shellData.activeProfileStore.refreshFromPersistence()
        isTabRoot = isRootTabIntent(intent)
    }

    @Deprecated("Use OnBackPressedDispatcher for in-app back handling")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    override fun setContentView(layoutResID: Int) {
        val content = layoutInflater.inflate(layoutResID, null, false)
        normalizeScreenHeader(content)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_primary))
        }
        if (this is HomeActivity) {
            profileHeader = buildProfileHeader().also { header ->
                shell.addView(header, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.profile_header_height),
                ))
            }
        }
        shell.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        super.setContentView(shell)
        content.alpha = 0f
        content.translationY = resources.getDimension(R.dimen.motion_screen_offset)
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(resources.getInteger(R.integer.motion_screen_enter_ms).toLong())
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        observeGlobalProfiles()
    }

    /**
     * Keeps navigation on detail screens without repeating section titles in the main tabs.
     * The Home, Food and Progress tabs already have their own content hierarchy.
     */
    private fun normalizeScreenHeader(content: View) {
        if (this is MealDetailActivity || this is NotificationsActivity) return
        val root = content as? ViewGroup ?: return
        val header = findHeaderWithBackButton(root) ?: return
        if (this is HomeActivity || this is FoodPlanActivity || this is PhysicalEvolutionActivity) {
            header.visibility = View.GONE
            return
        }

        // Detail screens retain the back action and a compact, neutral navigation label.
        header.layoutParams = header.layoutParams?.apply { height = dimen(R.dimen.screen_header_height) }
        header.setBackgroundResource(R.drawable.bg_screen_header)
        header.setPadding(dimen(R.dimen.space_8), 0, dimen(R.dimen.space_8), 0)
        if (header is LinearLayout) header.gravity = Gravity.CENTER_VERTICAL

        for (index in 0 until header.childCount) {
            val child = header.getChildAt(index)
            if (child is TextView && child.id != R.id.backButton && child.text.isNotBlank()) {
                child.setTextColor(getColor(R.color.text_primary))
                child.setTextAppearance(R.style.Text_MyFitAI_ScreenTitle)
                child.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                child.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            }
            if (child is ImageView && child.id == R.id.backButton) {
                child.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_primary))
            }
        }
    }

    private fun findHeaderWithBackButton(group: ViewGroup): ViewGroup? {
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is ViewGroup) {
                val directBack = (0 until child.childCount).any { child.getChildAt(it).id == R.id.backButton }
                if (directBack) return child
                findHeaderWithBackButton(child)?.let { return it }
            }
        }
        return null
    }

    private fun buildProfileHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dimen(R.dimen.page_gutter_compact), dimen(R.dimen.space_6), dimen(R.dimen.space_12), dimen(R.dimen.space_6))
        setBackgroundColor(getColor(R.color.admin_header_bg))

        addView(TextView(this@BaseShellActivity).apply {
            text = "Profilo"
            setTextAppearance(R.style.Text_MyFitAI_Micro)
            setTextColor(getColor(R.color.text_primary))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dimen(R.dimen.space_10)
        })

        profileSwitcher = AutoCompleteTextView(this@BaseShellActivity).apply {
            hint = "Seleziona profilo"
            background = null
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.surface_soft))
            setTextAppearance(R.style.Text_MyFitAI_BodyEmphasis)
            isSingleLine = true
            inputType = 0
            background = null
            setPadding(dimen(R.dimen.space_12), 0, dimen(R.dimen.space_8), 0)
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, position, _ -> handleProfileSelection(position) }
        }
        addView(profileSwitcher, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        addView(ImageView(this@BaseShellActivity).apply {
            setImageResource(R.drawable.ic_nutrition_ai)
            contentDescription = "Chiedi un consiglio alimentare all'IA"
            val iconPadding = dimen(R.dimen.space_8)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (this@BaseShellActivity !is NutritionAdviceActivity) {
                    startActivity(Intent(this@BaseShellActivity, NutritionAdviceActivity::class.java))
                }
            }
        }, LinearLayout.LayoutParams(dimen(R.dimen.icon_button_size), dimen(R.dimen.icon_button_size)).apply { marginStart = dimen(R.dimen.space_4) })
    }

    private fun observeGlobalProfiles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    shellData.userProfileRepository.profiles.collect { profiles ->
                        shellProfiles = profiles
                        profileHeader?.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                        val labels = profiles.map { it.name } + "+ Nuovo profilo"
                        profileSwitcher?.setAdapter(ArrayAdapter(this@BaseShellActivity, R.layout.item_dropdown_myfitai, labels))
                        renderActiveProfile()
                    }
                }
                launch {
                    shellData.activeProfileStore.activeProfileId.collect { renderActiveProfile() }
                }
            }
        }
    }

    private fun renderActiveProfile() {
        val activeId = shellData.activeProfileStore.currentIdOrNull()
        val profile = shellProfiles.firstOrNull { it.id == activeId }
        if (profile != null) profileSwitcher?.setText(profile.name, false)
    }

    private fun handleProfileSelection(position: Int) {
        if (position == shellProfiles.size) {
            startActivity(Intent(this, OnboardingWizardActivity::class.java).putExtra(OnboardingWizardActivity.EXTRA_NEW_PROFILE, true))
            renderActiveProfile()
            return
        }
        val profile = shellProfiles.getOrNull(position) ?: return
        if (profile.id == shellData.activeProfileStore.currentIdOrNull()) return
        shellData.activeProfileStore.selectProfile(profile.id, makeDefault = true)

        (parent as? TabHostActivity)?.let {
            it.selectTab(BottomNavBinder.Tab.HOME)
            return
        }

        // Le schermate legate a entità del vecchio profilo non devono restare aperte.
        // Si torna alla Home: i ViewModel profile-scoped ricostruiscono lo stato dal nuovo activeProfileId.
        startActivity(Intent(this, TabHostActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
        if (this !is HomeActivity) finish()
    }

    protected fun bindBack() {
        findViewById<View?>(R.id.backButton)?.let { backButton ->
            if (intent.getBooleanExtra(BottomNavBinder.EXTRA_EMBEDDED_TAB, false)) {
                backButton.visibility = View.GONE
            } else {
                backButton.setOnClickListener { finish() }
            }
        }
    }
    protected fun bindBottom(tab: BottomNavBinder.Tab) { BottomNavBinder.bind(this, tab) }
    protected val aiProviderConfigured: Boolean
        get() = AiProviderAccess.isConfigured(this)

    protected fun setAiActionEnabled(view: View, enabled: Boolean = true) {
        view.isEnabled = aiProviderConfigured && enabled
    }

    protected fun confirmAiRequest(action: String, onConfirmed: () -> Unit) {
        if (!aiProviderConfigured) {
            AiProviderAccess.requireConfigured(this)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Confermare richiesta IA?")
            .setMessage(
                "$action invia una richiesta al provider IA e consuma la quota disponibile. " +
                    "Il costo effettivo dipende dal provider, dal modello e dal tuo piano di billing.",
            )
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Conferma") { _, _ -> onConfirmed() }
            .show()
    }

    protected fun go(target: Class<out AppCompatActivity>) {
        if (target == HomeActivity::class.java ||
            target == FoodPlanActivity::class.java ||
            target == PhysicalEvolutionActivity::class.java ||
            target == SettingsActivity::class.java
        ) {
            val tab = when (target) {
                HomeActivity::class.java -> BottomNavBinder.Tab.HOME
                FoodPlanActivity::class.java -> BottomNavBinder.Tab.FOOD
                PhysicalEvolutionActivity::class.java -> BottomNavBinder.Tab.PROGRESS
                else -> BottomNavBinder.Tab.MORE
            }
            (parent as? TabHostActivity)?.let {
                it.selectTab(tab)
                return
            }
        }
        if (target != HomeActivity::class.java &&
            target != FoodPlanActivity::class.java &&
            target != PhysicalEvolutionActivity::class.java &&
            target != SettingsActivity::class.java
        ) {
            startActivity(Intent(this, target))
            return
        }
        val intent = Intent(this, TabHostActivity::class.java)
        val selectedTab = when (target) {
            HomeActivity::class.java -> BottomNavBinder.Tab.HOME
            FoodPlanActivity::class.java -> BottomNavBinder.Tab.FOOD
            PhysicalEvolutionActivity::class.java -> BottomNavBinder.Tab.PROGRESS
            else -> BottomNavBinder.Tab.MORE
        }
        intent.putExtra(BottomNavBinder.EXTRA_SELECTED_TAB, selectedTab.name)
        if (target == HomeActivity::class.java ||
            target == FoodPlanActivity::class.java ||
            target == PhysicalEvolutionActivity::class.java ||
            target == SettingsActivity::class.java
        ) {
            intent.putExtra(BottomNavBinder.EXTRA_TAB_ROOT, true)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    private fun isRootTabIntent(value: Intent): Boolean =
        value.getBooleanExtra(BottomNavBinder.EXTRA_TAB_ROOT, false) &&
            !value.getBooleanExtra(BottomNavBinder.EXTRA_INTERNAL_NAV, false)

    private fun handleBackNavigation() {
        if (intent.getBooleanExtra(BottomNavBinder.EXTRA_EMBEDDED_TAB, false)) {
            (parent as? TabHostActivity)?.showExitConfirmation()
            return
        }
        if (!isTabRoot) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Uscire da MyFitAI?")
            .setMessage("Vuoi chiudere l'app?")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Esci") { _, _ -> finishAffinity() }
            .show()
    }

    private fun dimen(dimenRes: Int): Int = resources.getDimensionPixelSize(dimenRes)
}
