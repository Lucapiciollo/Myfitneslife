package com.myfitai.app.data.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mantiene gli id del profilo attivo e del profilo predefinito.
 * I dati applicativi restano in Room e tutti i repository sono sempre profile-scoped.
 */
class ActiveProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _activeProfileId = MutableStateFlow(prefs.getLong(KEY_ACTIVE_PROFILE_ID, NO_PROFILE))

    val activeProfileId: StateFlow<Long> = _activeProfileId.asStateFlow()

    fun currentIdOrNull(): Long? = _activeProfileId.value.takeIf { it != NO_PROFILE }

    fun defaultIdOrNull(): Long? = prefs.getLong(KEY_DEFAULT_PROFILE_ID, NO_PROFILE).takeIf { it != NO_PROFILE }

    fun setActiveProfile(profileId: Long) {
        require(profileId > 0) { "profileId must be > 0" }
        prefs.edit().putLong(KEY_ACTIVE_PROFILE_ID, profileId).apply()
        _activeProfileId.value = profileId
    }

    fun setDefaultProfile(profileId: Long) {
        require(profileId > 0) { "profileId must be > 0" }
        prefs.edit().putLong(KEY_DEFAULT_PROFILE_ID, profileId).apply()
    }

    fun selectProfile(profileId: Long, makeDefault: Boolean = true) {
        setActiveProfile(profileId)
        if (makeDefault) setDefaultProfile(profileId)
    }

    fun restoreDefaultProfile(): Long? {
        val id = defaultIdOrNull() ?: return null
        setActiveProfile(id)
        return id
    }

    fun clear() {
        prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).remove(KEY_DEFAULT_PROFILE_ID).apply()
        _activeProfileId.value = NO_PROFILE
    }

    companion object {
        private const val PREFS_NAME = "myfitai_profile_session"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_DEFAULT_PROFILE_ID = "default_profile_id"
        private const val NO_PROFILE = -1L
    }
}
