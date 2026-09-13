package com.myfitai.app.domain.shopping

import android.content.Context

/**
 * Lightweight local state for a generated shopping list. No nutritional data or secrets.
 * State is scoped by profile + week + normalized ingredient key so matching items survive
 * a plan re-generation while unrelated profiles/weeks remain isolated.
 */
class ShoppingListStateStore(context: Context) {
    enum class Status { TO_BUY, PURCHASED, PANTRY }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(profileId: Long, weekStartEpochDay: Long, itemKey: String): Status {
        val raw = prefs.getString(key(profileId, weekStartEpochDay, itemKey), null)
        return runCatching { raw?.let(Status::valueOf) }.getOrNull() ?: Status.TO_BUY
    }

    fun setStatus(profileId: Long, weekStartEpochDay: Long, itemKey: String, status: Status) {
        prefs.edit().putString(key(profileId, weekStartEpochDay, itemKey), status.name).apply()
    }

    fun reset(profileId: Long, weekStartEpochDay: Long, itemKeys: Collection<String>) {
        if (itemKeys.isEmpty()) return
        prefs.edit().also { editor ->
            itemKeys.forEach { editor.remove(key(profileId, weekStartEpochDay, it)) }
        }.apply()
    }

    private fun key(profileId: Long, weekStartEpochDay: Long, itemKey: String): String =
        "p${profileId}_w${weekStartEpochDay}_${itemKey}"

    private companion object {
        const val PREFS = "shopping_list_state_v1"
    }
}