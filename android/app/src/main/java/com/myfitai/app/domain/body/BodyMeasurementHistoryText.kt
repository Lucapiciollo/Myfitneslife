package com.myfitai.app.domain.body

import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import java.util.Locale

/** Testo dello storico misure: stessa sorgente per la riga visibile e per la contentDescription della card. */
object BodyMeasurementHistoryText {

    fun kg(value: Float): String = String.format(Locale.ITALIAN, "%.1f kg", value)

    fun cm(value: Float): String =
        if (value % 1f == 0f) "${value.toInt()} cm" else String.format(Locale.ITALIAN, "%.1f cm", value)

    fun values(entity: BodyMeasurementEntity, weightKg: Float?, weightFromBia: Boolean): String {
        val items = listOfNotNull(
            weightKg?.let { if (weightFromBia) "Peso BIA (stessa data) ${kg(it)}" else "Peso corporeo ${kg(it)}" },
            entity.hipsCm?.let { "Fianchi ${cm(it)}" },
            entity.chestCm?.let { "Torace ${cm(it)}" },
            entity.waistCm?.let { "Vita ${cm(it)}" },
            entity.abdomenCm?.let { "Addome ${cm(it)}" },
            entity.shouldersCm?.let { "Spalle ${cm(it)}" },
            entity.glutesCm?.let { "Glutei ${cm(it)}" },
            entity.armLeftCm?.let { "Braccio sx ${cm(it)}" },
            entity.armRightCm?.let { "Braccio dx ${cm(it)}" },
            entity.thighLeftCm?.let { "Coscia sx ${cm(it)}" },
            entity.thighRightCm?.let { "Coscia dx ${cm(it)}" },
            entity.calfLeftCm?.let { "Polpaccio sx ${cm(it)}" },
            entity.calfRightCm?.let { "Polpaccio dx ${cm(it)}" },
        )
        return items.ifEmpty { listOf("Rilevazione senza valori") }.joinToString(" · ")
    }

    /** [template] ha due segnaposto: data (%1$s) e valori (%2$s). */
    fun cardDescription(template: String, date: String, values: String, waistNote: String?): String {
        val base = String.format(Locale.ITALIAN, template, date, values)
        return if (waistNote.isNullOrBlank()) base else "$base $waistNote."
    }
}
