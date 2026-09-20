package com.myfitai.app.ui

import android.app.LocalActivityManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderAccess
import com.myfitai.app.navigation.BottomNavBinder

/** Single window host for the four persistent root tabs. Child screens remain normal Activities. */
class TabHostActivity : AppCompatActivity() {
    private lateinit var activityManager: LocalActivityManager
    private lateinit var content: FrameLayout
    private var currentTab = BottomNavBinder.Tab.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentTab = intent.getStringExtra(BottomNavBinder.EXTRA_SELECTED_TAB)
            ?.let { name -> BottomNavBinder.Tab.entries.firstOrNull { it.name == name } }
            ?: BottomNavBinder.Tab.HOME
        setContentView(R.layout.activity_tab_host)
        content = findViewById(R.id.tabContent)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        activityManager = LocalActivityManager(this, true)
        activityManager.dispatchCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitConfirmation()
            }
        })
        BottomNavBinder.bind(this, currentTab)
        showTab(currentTab)
    }

    fun selectTab(tab: BottomNavBinder.Tab) {
        if (tab == BottomNavBinder.Tab.FOOD && !AiProviderAccess.requireConfigured(this)) return
        if (tab == currentTab) return
        currentTab = tab
        showTab(tab)
        BottomNavBinder.bind(this, tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(BottomNavBinder.EXTRA_SELECTED_TAB)
            ?.let { name -> BottomNavBinder.Tab.entries.firstOrNull { it.name == name } }
            ?.takeIf { it != currentTab }
            ?.let { selectTab(it) }
    }

    fun showExitConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Uscire da MyFitAI?")
            .setMessage("Vuoi chiudere l'app?")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Esci") { _, _ -> finishAffinity() }
            .show()
    }

    private fun showTab(tab: BottomNavBinder.Tab) {
        val child = activityManager.startActivity(
            tab.name,
            Intent(this, tab.activityClass())
                .putExtra(BottomNavBinder.EXTRA_EMBEDDED_TAB, true)
                .putExtra(BottomNavBinder.EXTRA_TAB_ROOT, true),
        ) ?: return
        child.decorView.findViewById<View>(R.id.bottomNav)?.visibility = View.GONE
        content.removeAllViews()
        content.addView(child.decorView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onResume() {
        super.onResume()
        if (::activityManager.isInitialized) activityManager.dispatchResume()
    }

    override fun onPause() {
        if (::activityManager.isInitialized) activityManager.dispatchPause(isFinishing)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::activityManager.isInitialized) activityManager.dispatchDestroy(isFinishing)
        super.onDestroy()
    }

    private fun BottomNavBinder.Tab.activityClass(): Class<out Activity> = when (this) {
        BottomNavBinder.Tab.HOME -> HomeActivity::class.java
        BottomNavBinder.Tab.FOOD -> FoodPlanActivity::class.java
        BottomNavBinder.Tab.PROGRESS -> PhysicalEvolutionActivity::class.java
        BottomNavBinder.Tab.MORE -> SettingsActivity::class.java
    }
}
