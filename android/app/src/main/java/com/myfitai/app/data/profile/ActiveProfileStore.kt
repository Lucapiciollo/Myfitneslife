package com.myfitai.app.data.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiene solo l'id del profilo attivo. Non contiene dati sensibili e non sostituisce Room.
 * Tutti i repository devono ricevere esplicitamente profileId nelle query dati.
 */
class ActiveProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _activeProfileId = MutableStateFlow(prefs.getLong(KEY_ACTIVE_PROFILE_ID, NO_PROFILE))

    val activeProfileId: StateFlow<Long> = _activeProfileId.asStateFlow()

    fun currentIdOrNull(): Long? = _activeProfileId.value.takeIf { it != NO_PROFILE }

    fun setActiveProfile(profileId: Long) {
        require(profileId > 0) { "profileId must be > 0" }
        prefs.edit().putLong(KEY_ACTIVE_PROFILE_ID, profileId).apply()
        _activeProfileId.value = profileId
    }

    fun clear() {
        prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
        _activeProfileId.value = NO_PROFILE
    }

    companion object {
        private const val PREFS_NAME = "myfitai_profile_session"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val NO_PROFILE = -1L
    }
}
