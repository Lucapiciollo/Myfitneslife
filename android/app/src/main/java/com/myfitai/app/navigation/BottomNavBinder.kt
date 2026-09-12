package com.myfitai.app.navigation

import android.app.Activity
import android.content.Intent
import android.widget.TextView
import com.myfitai.app.R
import com.myfitai.app.ui.*

object BottomNavBinder {
    enum class Tab { HOME, MEASURES, FOOD, WORKOUT, MORE }

    fun bind(activity: Activity, selected: Tab) {
        val mapping = listOf(
            Triple(R.id.navHome, Tab.HOME, HomeActivity::class.java),
            Triple(R.id.navMeasures, Tab.MEASURES, BiaActivity::class.java),
            Triple(R.id.navFood, Tab.FOOD, FoodPlanActivity::class.java),
            Triple(R.id.navWorkout, Tab.WORKOUT, WorkoutsActivity::class.java),
            Triple(R.id.navMore, Tab.MORE, SettingsActivity::class.java),
        )

        mapping.forEach { (viewId, tab, target) ->
            activity.findViewById<TextView>(viewId)?.apply {
                isSelected = tab == selected
                setTextColor(activity.getColor(if (tab == selected) R.color.accent_green else R.color.text_secondary))
                background = if (tab == selected) activity.getDrawable(R.drawable.bg_nav_selected) else null
                setOnClickListener {
                    if (tab != selected) {
                        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                }
            }
        }
    }
}
