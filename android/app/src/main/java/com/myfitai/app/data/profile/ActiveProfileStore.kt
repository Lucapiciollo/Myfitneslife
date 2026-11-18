package com.myfitai.app.data.profile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mantiene gli id del profilo attivo e del profilo predefinito.
 * I dati applicativi restano in Room e tutti i repository sono sempre profile-scoped.
 *
 * Il profilo attivo è una sessione unica di processo: lo stato osservabile è condiviso da tutte
 * le istanze, mentre le SharedPreferences restano la persistenza tra avvii. Senza stato condiviso
 * un componente che cambia profilo lascerebbe le altre istanze su un profileId obsoleto e le
 * query profile-scoped non troverebbero i dati del profilo appena selezionato.
 */
class ActiveProfileStore(context: Context, private val prefsName: String = PREFS_NAME) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val activeProfileState = synchronized(stateLock) {
        sessionStates.getOrPut(prefsName) {
            MutableStateFlow(prefs.getLong(KEY_ACTIVE_PROFILE_ID, NO_PROFILE))
        }
    }

    val activeProfileId: StateFlow<Long> = activeProfileState.asStateFlow()

    fun currentIdOrNull(): Long? = activeProfileState.value.takeIf { it != NO_PROFILE }

    /** Re-reads the persisted session when another app-process component changed it. */
    fun refreshFromPersistence() {
        val persisted = prefs.getLong(KEY_ACTIVE_PROFILE_ID, NO_PROFILE)
        if (persisted != NO_PROFILE && persisted != activeProfileState.value) {
            activeProfileState.value = persisted
        }
    }

    fun defaultIdOrNull(): Long? = prefs.getLong(KEY_DEFAULT_PROFILE_ID, NO_PROFILE).takeIf { it != NO_PROFILE }

    fun setActiveProfile(profileId: Long) {
        require(profileId > 0) { "profileId must be > 0" }
        prefs.edit().putLong(KEY_ACTIVE_PROFILE_ID, profileId).apply()
        activeProfileState.value = profileId
    }

    fun setDefaultProfile(profileId: Long) {
        require(profileId > 0) { "profileId must be > 0" }
        prefs.edit().putLong(KEY_DEFAULT_PROFILE_ID, profileId).apply()
    }

    fun selectProfile(profileId: Long, makeDefault: Boolean = true) {
        setActiveProfile(profileId)
        if (makeDefault) setDefaultProfile(profileId)
    }

    /** Clears only session pointers that reference the deleted profile. */
    fun onProfileDeleted(profileId: Long, replacementProfileId: Long?) {
        val editor = prefs.edit()
        val activeWasDeleted = currentIdOrNull() == profileId
        val defaultWasDeleted = defaultIdOrNull() == profileId
        if (activeWasDeleted) {
            if (replacementProfileId != null) editor.putLong(KEY_ACTIVE_PROFILE_ID, replacementProfileId)
            else editor.remove(KEY_ACTIVE_PROFILE_ID)
        }
        if (defaultWasDeleted) {
            if (replacementProfileId != null) editor.putLong(KEY_DEFAULT_PROFILE_ID, replacementProfileId)
            else editor.remove(KEY_DEFAULT_PROFILE_ID)
        }
        editor.apply()
        if (activeWasDeleted) activeProfileState.value = replacementProfileId ?: NO_PROFILE
    }

    fun restoreDefaultProfile(): Long? {
        val id = defaultIdOrNull() ?: return null
        setActiveProfile(id)
        return id
    }

    fun clear() {
        prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).remove(KEY_DEFAULT_PROFILE_ID).apply()
        activeProfileState.value = NO_PROFILE
    }

    companion object {
        private const val PREFS_NAME = "myfitai_profile_session"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_DEFAULT_PROFILE_ID = "default_profile_id"
        private const val NO_PROFILE = -1L

        private val stateLock = Any()
        private val sessionStates = mutableMapOf<String, MutableStateFlow<Long>>()
    }
}
