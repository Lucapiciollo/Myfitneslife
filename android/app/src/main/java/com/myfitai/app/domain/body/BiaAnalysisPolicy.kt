package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Regole locali che decidono, PRIMA della chiamata IA a pagamento, se l'analisi BIA deve partire.
 *
 * - Analisi automatica: non riparte se la rilevazione più recente è già stata analizzata e i suoi
 *   valori non sono cambiati dopo l'analisi (impronta in [fingerprint]).
 * - Dati insufficienti: serve almeno una BIA con un valore; con una sola BIA l'analisi è consentita
 *   (il modello risponde `INSUFFICIENT_DATA`).
 * - Analisi manuale: l'utente l'ha chiesta esplicitamente (con conferma IA) e non viene bloccata.
 *
 * "Obsoleta" non richiede migrazioni: un'analisi è obsoleta se l'impronta salvata nel suo payload
 * non coincide più con i valori attuali della rilevazione (modifica in place). I risultati salvati
 * prima di questa versione non hanno impronta e non vengono mai marcati obsoleti.
 */
object BiaAnalysisPolicy {
    /** Versione del contratto di risposta (`BA2`) salvata nel risultato. */
    const val CONTRACT_VERSION = "BA2"
    const val SKIP_ALREADY_ANALYZED = "ALREADY_ANALYZED"
    const val SKIP_NO_DATA = "NO_VALID_BIA"

    sealed interface Decision {
        object Run : Decision
        object NoData : Decision
        data class Skip(val reason: String) : Decision
    }

    fun decide(automatic: Boolean, report: BiaAnalysisReport.Report?, storedPayload: String?): Decision {
        if (report == null) return Decision.NoData
        if (automatic && storedPayload != null && !isObsolete(storedPayload, report.latest)) {
            return Decision.Skip(SKIP_ALREADY_ANALYZED)
        }
        return Decision.Run
    }

    /** Impronta dei soli valori e condizioni che l'analisi ha letto: cambia se la BIA viene modificata. */
    fun fingerprint(m: BiaMeasurementEntity): String {
        val canonical = listOf(
            m.measuredAtEpochMillis, m.weightKg, m.bodyFatPercent, m.visceralFatLevel, m.muscleMassKg,
            m.skeletalMuscleKg, m.bodyWaterPercent, m.bmrKcal, m.fatMassKg, m.leanMassKg, m.bodyWaterKg,
            m.subcutaneousFatPercent, m.boneMassKg, m.proteinPercent, m.proteinKg, m.bodyAgeYears, m.bmi,
            m.fasting, m.justWokeUp, m.afterBathroom, m.noRecentWorkout,
        ).joinToString("|") { it?.toString() ?: "-" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    /** True solo se il payload porta un'impronta diversa da quella attuale. */
    fun isObsolete(payloadJson: String, current: BiaMeasurementEntity): Boolean {
        val stored = runCatching { JSONObject(payloadJson).optString("dataFingerprint") }.getOrDefault("")
        return stored.isNotBlank() && stored != fingerprint(current)
    }
}
