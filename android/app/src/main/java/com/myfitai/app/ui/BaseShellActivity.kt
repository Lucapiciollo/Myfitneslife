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
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
        if (!AiProviderAccess.requireConfigured(this)) return false
        val intent = Intent(this, FoodPlanActivity::class.java)
        weekStartEpochDay?.let { intent.putExtra(FoodPlanActivity.EXTRA_WEEK_START_EPOCH_DAY, it) }
        startActivity(intent)
        return true
    }

    private val tabRootBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            handleBackNavigation()
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
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
        isTabRoot = isRootTabIntent(intent)
    }

    @Deprecated("Use OnBackPressedDispatcher for in-app back handling")
    override fun onBackPressed() {
        handleBackNavigation()
    }

    override fun setContentView(layoutResID: Int) {
        val content = layoutInflater.inflate(layoutResID, null, false)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_primary))
        }
        profileHeader = buildProfileHeader().also { header ->
            shell.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        }
        shell.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        super.setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(shell)
        observeGlobalProfiles()
    }

    private fun buildProfileHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(6), dp(12), dp(6))
        setBackgroundColor(getColor(R.color.surface_primary))

        addView(TextView(this@BaseShellActivity).apply {
            text = "Profilo"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(10)
        })

        profileSwitcher = AutoCompleteTextView(this@BaseShellActivity).apply {
            hint = "Seleziona profilo"
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
            textSize = 15f
            isSingleLine = true
            inputType = 0
            setPadding(dp(12), 0, dp(8), 0)
            setOnClickListener { showDropDown() }
            setOnItemClickListener { _, _, position, _ -> handleProfileSelection(position) }
        }
        addView(profileSwitcher, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        addView(ImageView(this@BaseShellActivity).apply {
            setImageResource(R.drawable.ic_nutrition_ai)
            contentDescription = "Chiedi un consiglio alimentare all'IA"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (this@BaseShellActivity !is NutritionAdviceActivity) {
                    startActivity(Intent(this@BaseShellActivity, NutritionAdviceActivity::class.java))
                }
            }
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(4) })
    }

    private fun observeGlobalProfiles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    shellData.userProfileRepository.profiles.collect { profiles ->
                        shellProfiles = profiles
                        profileHeader?.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                        val labels = profiles.map { it.name } + "+ Nuovo profilo"
                        profileSwitcher?.setAdapter(ArrayAdapter(this@BaseShellActivity, android.R.layout.simple_dropdown_item_1line, labels))
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
            startActivity(Intent(this, ProfileEditActivity::class.java).putExtra(ProfileEditActivity.EXTRA_CREATE, true))
            renderActiveProfile()
            return
        }
        val profile = shellProfiles.getOrNull(position) ?: return
        if (profile.id == shellData.activeProfileStore.currentIdOrNull()) return
        shellData.activeProfileStore.selectProfile(profile.id, makeDefault = true)

        // Le schermate legate a entità del vecchio profilo non devono restare aperte.
        // Si torna alla Home: i ViewModel profile-scoped ricostruiscono lo stato dal nuovo activeProfileId.
        startActivity(Intent(this, HomeActivity::class.java).apply {
            putExtra(BottomNavBinder.EXTRA_TAB_ROOT, true)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        })
        if (this !is HomeActivity) finish()
    }

    protected fun bindBack() { findViewById<View?>(R.id.backButton)?.setOnClickListener { finish() } }
    protected fun bindBottom(tab: BottomNavBinder.Tab) { BottomNavBinder.bind(this, tab) }
    protected fun go(target: Class<out AppCompatActivity>) {
        val intent = Intent(this, target)
        if (target == HomeActivity::class.java ||
            target == FoodPlanActivity::class.java ||
            target == PhysicalEvolutionActivity::class.java ||
            target == SettingsActivity::class.java
        ) {
            intent.putExtra(BottomNavBinder.EXTRA_INTERNAL_NAV, true)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    private fun isRootTabIntent(value: Intent): Boolean =
        value.getBooleanExtra(BottomNavBinder.EXTRA_TAB_ROOT, false) &&
            !value.getBooleanExtra(BottomNavBinder.EXTRA_INTERNAL_NAV, false)

    private fun handleBackNavigation() {
        if (isTabRoot) finishAffinity() else finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
