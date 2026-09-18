package com.myfitai.app.navigation

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.widget.TextView
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderAccess
import com.myfitai.app.ui.TabHostActivity

object BottomNavBinder {
    enum class Tab { HOME, FOOD, PROGRESS, MORE }
    const val EXTRA_INITIAL_TAB = "myfitai_initial_tab"

    fun bind(activity: Activity, selected: Tab) {
        val mapping = listOf(
            R.id.navHome to Tab.HOME,
            R.id.navFood to Tab.FOOD,
            R.id.navProgress to Tab.PROGRESS,
            R.id.navMore to Tab.MORE,
        )
        mapping.forEach { (viewId, tab) ->
            activity.findViewById<TextView>(viewId)?.apply {
                isSelected = tab == selected
                val tint = activity.getColor(if (tab == selected) R.color.accent_green else R.color.text_secondary)
                setTextColor(tint)
                compoundDrawableTintList = ColorStateList.valueOf(tint)
                background = if (tab == selected) activity.getDrawable(R.drawable.bg_nav_selected) else null
                setOnClickListener {
                    if (tab == selected) return@setOnClickListener
                    if (tab == Tab.FOOD && !AiProviderAccess.requireConfigured(activity)) return@setOnClickListener
                    val host = activity as? TabHostActivity
                    if (host != null) host.selectTab(tab) else activity.startActivity(
                        Intent(activity, TabHostActivity::class.java)
                            .putExtra(EXTRA_INITIAL_TAB, tab.name)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
        }
    }
}
