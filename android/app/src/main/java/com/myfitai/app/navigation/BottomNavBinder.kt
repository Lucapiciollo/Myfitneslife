package com.myfitai.app.navigation

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.TextView
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderAccess
import com.myfitai.app.ui.*

object BottomNavBinder {
    enum class Tab { HOME, FOOD, PROGRESS, MORE }
    const val EXTRA_TAB_ROOT = "myfitai_tab_root"
    const val EXTRA_INTERNAL_NAV = "myfitai_internal_nav"
    const val EXTRA_EMBEDDED_TAB = "myfitai_embedded_tab"
    const val EXTRA_SELECTED_TAB = "myfitai_selected_tab"

    fun bind(activity: Activity, selected: Tab) {
        val mapping = listOf(
            Triple(R.id.navHome, Tab.HOME, HomeActivity::class.java),
            Triple(R.id.navFood, Tab.FOOD, FoodPlanActivity::class.java),
            Triple(R.id.navProgress, Tab.PROGRESS, PhysicalEvolutionActivity::class.java),
            Triple(R.id.navMore, Tab.MORE, SettingsActivity::class.java),
        )

        mapping.forEach { (viewId, tab, target) ->
            activity.findViewById<TextView>(viewId)?.apply {
                isSelected = tab == selected
                val tintColor = activity.getColor(if (tab == selected) R.color.white else R.color.accent_green_dark)
                setTextColor(tintColor)
                compoundDrawableTintList = ColorStateList.valueOf(tintColor)
                background = if (tab == selected) activity.getDrawable(R.drawable.bg_nav_selected) else null
                setOnClickListener {
                    if (tab != selected) {
                        if (tab == Tab.FOOD && !AiProviderAccess.requireConfigured(activity)) return@setOnClickListener
                        val host = (activity as? TabHostActivity) ?: (activity.parent as? TabHostActivity)
                        if (host != null) {
                            host.selectTab(tab)
                            return@setOnClickListener
                        }
                        activity.startActivity(
                            Intent(activity, TabHostActivity::class.java)
                                .putExtra(EXTRA_SELECTED_TAB, tab.name)
                                .putExtra(EXTRA_TAB_ROOT, true)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                                )
                        )
                    }
                }
            }
        }
    }
}
