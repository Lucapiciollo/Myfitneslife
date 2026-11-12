package com.myfitai.app.ui.widgets

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.myfitai.app.R
import com.myfitai.app.ai.AiProviderStatus
import com.myfitai.app.navigation.AiSettingsEntry

/**
 * Rotellina "Impostazioni IA" condivisa dalle schermate radice. Sempre attiva (anche senza provider):
 * con provider non utilizzabile mostra un punto d'avviso e lo dichiara anche nel nome accessibile.
 * Nessun valore visivo è letterale: icona, colori e misure arrivano da risorse centrali.
 */
class AiSettingsGearButton(context: Context) : FrameLayout(context) {
    private val badge: View

    init {
        id = R.id.aiSettingsButton
        isClickable = true
        isFocusable = true
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)

        val iconInset = resources.getDimensionPixelSize(R.dimen.space_12)
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_setting_gear)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.text_primary))
                setPadding(iconInset, iconInset, iconInset, iconInset)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        val badgeSize = resources.getDimensionPixelSize(R.dimen.ai_gear_badge_size)
        val badgeInset = resources.getDimensionPixelSize(R.dimen.space_8)
        badge = View(context).apply {
            id = R.id.aiSettingsBadge
            setBackgroundResource(R.drawable.bg_status_dot_warning)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = GONE
        }
        addView(
            badge,
            LayoutParams(badgeSize, badgeSize, android.view.Gravity.TOP or android.view.Gravity.END).apply {
                topMargin = badgeInset
                marginEnd = badgeInset
            },
        )
        render(needsAttention = false)
    }

    fun render(status: AiProviderStatus) {
        render(status.needsAttention)
    }

    private fun render(needsAttention: Boolean) {
        badge.visibility = if (needsAttention) VISIBLE else GONE
        contentDescription = context.getString(
            if (needsAttention) R.string.ai_settings_button_cd_unconfigured else R.string.ai_settings_button_cd,
        )
        tooltipText = context.getString(R.string.ai_settings_button_tooltip)
    }

    fun bindNavigation() {
        setOnClickListener { AiSettingsEntry.open(context) }
    }
}
