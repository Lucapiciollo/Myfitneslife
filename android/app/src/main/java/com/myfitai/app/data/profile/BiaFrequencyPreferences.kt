package com.myfitai.app.data.profile

import android.content.Context

class BiaFrequencyPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var intervalDays: Int
        get() = prefs.getInt(KEY_INTERVAL_DAYS, DEFAULT_INTERVAL_DAYS).let { value ->
            ALLOWED_INTERVALS.firstOrNull { it == value } ?: DEFAULT_INTERVAL_DAYS
        }
        set(value) {
            prefs.edit().putInt(KEY_INTERVAL_DAYS, value.takeIf { it in ALLOWED_INTERVALS } ?: DEFAULT_INTERVAL_DAYS).apply()
        }

    companion object {
        const val WEEKLY = 7
        const val MONTHLY = 30
        const val BIMONTHLY = 60
        val ALLOWED_INTERVALS = setOf(WEEKLY, MONTHLY, BIMONTHLY)
        const val DEFAULT_INTERVAL_DAYS = MONTHLY

        private const val PREFS = "myfitai_bia_preferences"
        private const val KEY_INTERVAL_DAYS = "bia_interval_days"
    }
}
