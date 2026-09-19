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
class ActiveProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        synchronized(initLock) {
            if (!initialized) {
                sharedActiveProfileId.value = prefs.getLong(KEY_ACTIVE_PROFILE_ID, NO_PROFILE)
                initialized = true
            }
        }
    }

    val activeProfileId: StateFlow<Long> = sharedActiveProfileId.asStateFlow()

    fun currentIdOrNull(): Long? = sharedActiveProfileId.value.takeIf { it != NO_PROFILE }

    fun defaultIdOrNull(): Long? = prefs.getLong(KEY_DEFAULT_PROFILE_ID, NO_PROFILE).takeIf { it != NO_PROFILE }

    fun setActiveProfile(profileId: Long) {
        require(profileId > 0) { "profileId must be > 0" }
        prefs.edit().putLong(KEY_ACTIVE_PROFILE_ID, profileId).apply()
        sharedActiveProfileId.value = profileId
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
        sharedActiveProfileId.value = NO_PROFILE
    }

    companion object {
        private const val PREFS_NAME = "myfitai_profile_session"
        private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
        private const val KEY_DEFAULT_PROFILE_ID = "default_profile_id"
        private const val NO_PROFILE = -1L

        private val initLock = Any()
        private var initialized = false
        private val sharedActiveProfileId = MutableStateFlow(NO_PROFILE)
    }
}
