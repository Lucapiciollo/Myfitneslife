package com.myfitai.app.domain.profile

/**
 * Opzioni del livello di attivita nel profilo. Le 3 scelte correnti sono fisse; se il profilo salvato porta un valore
 * storico che non e tra le 3 (es. "Leggermente attivo", "Estremamente attivo", "Moderatamente attivo") lo si mostra com'e
 * in coda alla lista, senza sostituirlo in silenzio: resta quello salvato finche l'utente non sceglie altro.
 */
object ActivityLevelChoices {
    fun options(base: List<String>, stored: String?): List<String> =
        if (isLegacy(base, stored)) base + stored!!.trim() else base

    /** True se [stored] e valorizzato ma non coincide (senza maiuscole e spazi) con nessuna delle scelte correnti. */
    fun isLegacy(base: List<String>, stored: String?): Boolean {
        val value = stored?.trim().orEmpty()
        return value.isNotEmpty() && base.none { it.trim().equals(value, ignoreCase = true) }
    }
}

