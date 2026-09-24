package com.myfitai.app.domain.progress

import com.myfitai.app.domain.calculation.ProfileCalculationService

object ProgressAnalysisRequirements {
    fun missing(snapshot: ProfileCalculationService.Snapshot?): List<String> {
        if (snapshot == null) return listOf("dati del profilo")
        return buildList {
            if (snapshot.latestBiaTimestamp == null) add("almeno una rilevazione BIA")
            if (snapshot.latestBodyMeasurementTimestamp == null) add("almeno una misura corporea")
        }
    }

    fun message(missing: List<String>): String =
        "Per avviare l'analisi IA aggiungi: ${missing.joinToString(", ")}."
}