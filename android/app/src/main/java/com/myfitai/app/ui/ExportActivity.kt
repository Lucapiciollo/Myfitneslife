package com.myfitai.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.body.BiaHistoryImportContract
import com.myfitai.app.domain.export.ProfileExportService
import com.myfitai.app.navigation.BottomNavBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val service by lazy { AppDataContainer.get(this).profileExportService }
    private val biaHistoryJsonLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importBiaHistoryJson(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

        findViewById<View>(R.id.importBiaHistoryRow).setOnClickListener { biaHistoryJsonLauncher.launch("application/json") }
        findViewById<View>(R.id.exportJsonRow).setOnClickListener { export(ProfileExportService.Format.JSON) }
        findViewById<View>(R.id.exportCsvRow).setOnClickListener { export(ProfileExportService.Format.CSV_ZIP) }
        findViewById<View>(R.id.exportPdfRow).setOnClickListener { export(ProfileExportService.Format.PDF) }
        findViewById<View>(R.id.exportWeeklyPlanPdfRow).setOnClickListener { export(ProfileExportService.Format.WEEKLY_PLAN_PDF) }
    }

    private fun export(format: ProfileExportService.Format) {
        setBusy(true, "Preparazione export…")
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { service.export(format) } }
                .onSuccess { exported ->
                    setBusy(false, "Export pronto: ${exported.file.name}")
                    val uri = service.contentUri(exported.file)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = exported.mimeType
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Condividi export MyFitAI"))
                }
                .onFailure { error -> setBusy(false, error.message ?: "Export non riuscito") }
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        findViewById<View>(R.id.exportProgress).visibility = if (busy) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.exportStatus).apply { visibility = View.VISIBLE; text = message }
        listOf(
            R.id.exportJsonRow,
            R.id.exportCsvRow,
            R.id.exportPdfRow,
            R.id.exportWeeklyPlanPdfRow,
        ).forEach { findViewById<View>(it).isEnabled = !busy }
    }

    private fun importBiaHistoryJson(uri: Uri) {
        val profileId = data.activeProfileStore.currentIdOrNull()
        if (profileId == null) {
            Toast.makeText(this, "Seleziona prima un profilo", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        val output = java.io.ByteArrayOutputStream()
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            require(output.size() <= 1_000_000) { "File JSON troppo grande" }
                        }
                        output.toString("UTF-8")
                    } ?: error("Impossibile aprire il file")
                    BiaHistoryImportContract.parse(json, profileId)
                }
            }
            result.onSuccess { readings ->
                val known = withContext(Dispatchers.IO) {
                    data.biaRepository.all(profileId).first()
                }.mapTo(hashSetOf()) { BiaHistoryImportContract.dayKey(it.measuredAtEpochMillis) }
                val newCount = readings.count { BiaHistoryImportContract.dayKey(it.measuredAtEpochMillis) !in known }
                val skipped = readings.size - newCount
                MaterialAlertDialogBuilder(this@ExportActivity)
                    .setTitle("Importa storico BIA")
                    .setMessage(
                        "Rilevazioni nel file: " + readings.size +
                            "\nNuove: " + newCount +
                            "\nDate già presenti: " + skipped +
                            "\n\nLe date già presenti non verranno sovrascritte. I dati mancanti resteranno vuoti. Continuare?"
                    )
                    .setNegativeButton("Annulla", null)
                    .setPositiveButton("Importa") { _, _ ->
                        lifecycleScope.launch {
                            val imported = withContext(Dispatchers.IO) {
                                runCatching { data.biaRepository.importMissing(profileId, readings) }
                            }
                            imported.onSuccess { (added, duplicates) ->
                                Toast.makeText(this@ExportActivity, "Importate $added BIA · $duplicates date già presenti", Toast.LENGTH_LONG).show()
                            }.onFailure {
                                Toast.makeText(this@ExportActivity, "Importazione non riuscita: ${it.message ?: "Errore"}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .show()
            }.onFailure {
                MaterialAlertDialogBuilder(this@ExportActivity)
                    .setTitle("File BIA non valido")
                    .setMessage(it.message ?: "Controlla il formato JSON")
                    .setPositiveButton("Chiudi", null)
                    .show()
            }
        }
    }

}
