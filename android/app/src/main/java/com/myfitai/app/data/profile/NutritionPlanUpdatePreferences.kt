package com.myfitai.app.data.profile

import android.content.Context

class NutritionPlanUpdatePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPending(profileId: Long): Boolean = prefs.getBoolean(key(profileId), false)

    fun setPending(profileId: Long, pending: Boolean) {
        prefs.edit().putBoolean(key(profileId), pending).apply()
    }

    private fun key(profileId: Long): String = "pending_$profileId"

    private companion object {
        const val PREFS_NAME = "nutrition_plan_update_preferences"
    }
}
