package com.myfitai.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.util.Pair as AndroidPair
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.body.BiaHistoryImportContract
import com.myfitai.app.domain.export.PreparedPlanRangePdf
import com.myfitai.app.domain.export.ProfileExportService
import com.myfitai.app.domain.food.DateRange
import com.myfitai.app.domain.food.NutritionPlanPeriod
import com.myfitai.app.navigation.BottomNavBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

class ExportActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val service by lazy { AppDataContainer.get(this).profileExportService }
    private var pendingPlanRangePdfRequest: PendingPlanRangePdf? = null
    private var pendingPlanRangePdfSnapshot: PreparedPlanRangePdf? = null
    private var planRangePdfWarningVisible = false
    private val biaHistoryJsonLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importBiaHistoryJson(uri)
    }
    private val backupCreateLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) saveBackup(uri)
    }
    private val backupOpenLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBackup(uri)
    }
    private val planRangePdfCreateLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val request = pendingPlanRangePdfRequest
        val snapshot = pendingPlanRangePdfSnapshot
        pendingPlanRangePdfRequest = null
        pendingPlanRangePdfSnapshot = null
        if (uri == null) {
            setBusy(false, "Salvataggio PDF annullato")
        } else {
            if (!persistPendingPlanRangePdf(uri, request)) {
                failPlanRangePdf(
                    uri,
                    request,
                    IllegalStateException("Impossibile registrare in modo sicuro la destinazione PDF scelta"),
                    PlanRangePdfSaveStage.PREPARATION,
                )
            } else if (request == null) {
                failPlanRangePdf(uri, null, IllegalStateException("Anteprima non più disponibile: rigenera e conferma il PDF"), PlanRangePdfSaveStage.PREPARATION)
            } else {
                savePlanRangePdf(uri, request, snapshot)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPlanRangePdfRequest = restorePendingPlanRangePdf(savedInstanceState)
        setContentView(R.layout.activity_export)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

        findViewById<View>(R.id.importBiaHistoryRow).setOnClickListener { biaHistoryJsonLauncher.launch("application/json") }
        findViewById<View>(R.id.saveBackupRow).setOnClickListener { backupCreateLauncher.launch("myfitai-backup.json") }
        findViewById<View>(R.id.importBackupRow).setOnClickListener { backupOpenLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }
        findViewById<View>(R.id.exportJsonRow).setOnClickListener { export(ProfileExportService.Format.JSON) }
        findViewById<View>(R.id.exportCsvRow).setOnClickListener { export(ProfileExportService.Format.CSV_ZIP) }
        findViewById<View>(R.id.exportPdfRow).setOnClickListener { export(ProfileExportService.Format.PDF) }
        findViewById<com.myfitai.app.ui.widgets.SettingRowView>(R.id.exportWeeklyPlanPdfRow).apply {
            setLabel("Piano alimentare PDF")
            setDescription("Scegli un intervallo fino a 31 giorni; date senza piano indicate prima del salvataggio")
            setOnClickListener { choosePlanRange() }
        }
        recoverPendingPlanRangePdf()
    }

    override fun onResume() {
        super.onResume()
        showPendingPlanRangePdfWarning()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPlanRangePdfRequest?.let { request ->
            outState.putLong(STATE_PLAN_RANGE_PROFILE_ID, request.profileId)
            outState.putLong(STATE_PLAN_RANGE_START, request.range.start.toEpochDay())
            outState.putLong(STATE_PLAN_RANGE_END, request.range.endInclusive.toEpochDay())
            outState.putString(STATE_PLAN_RANGE_FINGERPRINT, request.fingerprint)
        }
    }

    private fun restorePendingPlanRangePdf(state: Bundle?): PendingPlanRangePdf? {
        if (state == null || !state.containsKey(STATE_PLAN_RANGE_PROFILE_ID) ||
            !state.containsKey(STATE_PLAN_RANGE_START) || !state.containsKey(STATE_PLAN_RANGE_END)
        ) return null
        val profileId = state.getLong(STATE_PLAN_RANGE_PROFILE_ID)
        val fingerprint = state.getString(STATE_PLAN_RANGE_FINGERPRINT).orEmpty()
        if (profileId <= 0 || fingerprint.isBlank()) return null
        val range = runCatching {
            DateRange(
                LocalDate.ofEpochDay(state.getLong(STATE_PLAN_RANGE_START)),
                LocalDate.ofEpochDay(state.getLong(STATE_PLAN_RANGE_END)),
            )
        }.getOrNull()?.takeIf { it.dayCount <= NutritionPlanPeriod.MAX_RANGE_DAYS } ?: return null
        return PendingPlanRangePdf(profileId, range, fingerprint)
    }

    private fun choosePlanRange() {
        if (readPendingPlanRangePdf() != null) {
            recoverPendingPlanRangePdf()
            setBusy(false, "Completa il recupero del PDF precedente prima di crearne un altro")
            showPendingPlanRangePdfWarning()
            return
        }
        val today = LocalDate.now()
        val defaultStart = NutritionPlanPeriod.monday(today)
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTheme(R.style.ThemeOverlay_MyFitAI_MaterialCalendar)
            .setTitleText("Intervallo da esportare")
            .setSelection(AndroidPair(defaultStart.toUtcMillis(), defaultStart.plusDays(6).toUtcMillis()))
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val range = runCatching {
                DateRange(selection.first.toLocalDateUtc(), selection.second.toLocalDateUtc())
            }.getOrElse {
                showExportError("Intervallo date non valido")
                return@addOnPositiveButtonClickListener
            }
            if (range.dayCount > NutritionPlanPeriod.MAX_RANGE_DAYS) {
                showExportError("Seleziona al massimo ${NutritionPlanPeriod.MAX_RANGE_DAYS} giorni inclusivi.")
            } else {
                preparePlanRange(range)
            }
        }
        picker.show(supportFragmentManager, "plan_range_export_picker")
    }

    private fun preparePlanRange(range: DateRange) {
        setBusy(true, "Verifica copertura del piano…")
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { service.preparePlanRangePdf(range) } }
            result.onSuccess { prepared ->
                setBusy(false, "Copertura verificata per ${formatExportDate(range.start)} – ${formatExportDate(range.endInclusive)}")
                showPlanRangePreview(prepared)
            }.onFailure { error ->
                setBusy(false, error.message ?: "Impossibile leggere il piano per l'intervallo")
            }
        }
    }

    private fun showPlanRangePreview(prepared: PreparedPlanRangePdf) {
        val uncovered = prepared.uncoveredDates.sorted()
        val message = buildString {
            append("Profilo: ${prepared.profileName}")
            append("\n")
            append("Intervallo inclusivo: ${formatExportDate(prepared.range.start)} – ${formatExportDate(prepared.range.endInclusive)}")
            append("\nCopertura: ${prepared.days.size}/${prepared.range.dayCount} giornate pianificate")
            if (uncovered.isEmpty()) {
                append("\nDate scoperte: nessuna")
            } else {
                append("\nDate scoperte (${uncovered.size}):\n")
                append(uncovered.joinToString(", ") { formatExportDate(it) })
            }
            append("\n\nNel PDF ogni giornata riporta il piano e la versione di provenienza.")
        }
        if (prepared.days.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Nessun giorno pianificato")
                .setMessage("$message\n\nNon verrà creato alcun PDF.")
                .setPositiveButton("Chiudi", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Verifica copertura prima del salvataggio")
            .setMessage(message)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva PDF…") { _, _ ->
                pendingPlanRangePdfRequest = PendingPlanRangePdf(
                    profileId = prepared.profileId,
                    range = prepared.range,
                    fingerprint = prepared.fingerprint(),
                )
                pendingPlanRangePdfSnapshot = prepared
                val fileName = "myfitai-piano-${prepared.range.start}-${prepared.range.endInclusive}.pdf"
                planRangePdfCreateLauncher.launch(fileName)
            }
            .show()
    }

    private fun savePlanRangePdf(uri: Uri, request: PendingPlanRangePdf, previewSnapshot: PreparedPlanRangePdf?) {
        setBusy(true, "Creazione PDF…")
        PlanRangePdfSaveLifecycle.beginWrite(uri)
        lifecycleScope.launch {
            var stage = PlanRangePdfSaveStage.PREPARATION
            try {
                withContext(Dispatchers.IO) {
                    val prepared = previewSnapshot ?: service.preparePlanRangePdf(request.profileId, request.range)
                    if (prepared.profileId != request.profileId || prepared.range != request.range ||
                        prepared.fingerprint() != request.fingerprint
                    ) {
                        throw PlanRangePreviewChangedException()
                    }
                    check(prepared.days.isNotEmpty()) { "Nessun giorno pianificato nell'intervallo selezionato" }
                    stage = PlanRangePdfSaveStage.OPEN_DESTINATION
                    val output = contentResolver.openOutputStream(uri) ?: error("Impossibile aprire la destinazione scelta")
                    stage = PlanRangePdfSaveStage.STREAM_WRITE
                    output.use { stream ->
                        service.writePreparedPlanRangePdf(prepared, stream)
                        stream.flush()
                    }
                }
                val cleared = withContext(Dispatchers.IO) { clearPendingPlanRangePdf(uri) }
                check(cleared) { "Impossibile confermare in modo sicuro il salvataggio PDF" }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    setBusy(false, "PDF salvato su disco")
                    Toast.makeText(this@ExportActivity, "PDF salvato su disco", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                handlePlanRangePdfFailure(uri, request, error, stage)
            } finally {
                PlanRangePdfSaveLifecycle.finishWrite(uri)
            }
        }
    }

    private fun failPlanRangePdf(
        uri: Uri,
        request: PendingPlanRangePdf?,
        error: Throwable,
        stage: PlanRangePdfSaveStage,
    ) {
        lifecycleScope.launch { handlePlanRangePdfFailure(uri, request, error, stage) }
    }

    private suspend fun handlePlanRangePdfFailure(
        uri: Uri,
        request: PendingPlanRangePdf?,
        error: Throwable,
        stage: PlanRangePdfSaveStage,
    ) {
        val reason = when (error) {
            is PlanRangePreviewChangedException -> error.message.orEmpty()
            else -> "${stage.message}: ${error.message ?: "errore"}"
        }
        persistPendingPlanRangePdf(uri, request)
        val cleanup = cleanupPlanRangePdf(uri, request?.suggestedFileName(), reason)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            setBusy(false, "PDF non salvato")
            MaterialAlertDialogBuilder(this@ExportActivity)
                .setTitle("Salvataggio PDF non riuscito")
                .setMessage("$reason\n\n${cleanup.message}")
                .setPositiveButton("Chiudi", null)
                .show()
        }
    }

    private fun recoverPendingPlanRangePdf() {
        val pending = readPendingPlanRangePdf() ?: return
        lifecycleScope.launch {
            PlanRangePdfSaveLifecycle.awaitWrite(pending.uri)
            val latest = readPendingPlanRangePdf()?.takeIf { it.uri == pending.uri } ?: return@launch
            val cleanup = cleanupPlanRangePdf(
                latest.uri,
                latest.suggestedName,
                "Il salvataggio PDF è stato interrotto prima della conferma.",
            )
            if (!cleanup.deleted) showPendingPlanRangePdfWarning(cleanup.message)
        }
    }

    private suspend fun cleanupPlanRangePdf(uri: Uri, suggestedName: String?, reason: String): PlanRangePdfCleanupResult =
        withContext(NonCancellable) {
            PlanRangePdfSaveLifecycle.withCleanupLock {
                withContext(Dispatchers.IO) {
                    val prefs = planRangePdfRecoveryPreferences()
                    val isTracked = prefs.getString(KEY_PLAN_RANGE_PENDING_URI, null) == uri.toString()
                    val deleted = runCatching {
                        DocumentsContract.deleteDocument(applicationContext.contentResolver, uri)
                    }.getOrDefault(false)
                    val warning = if (deleted) null else buildPlanRangePdfWarning(suggestedName)
                    if (isTracked) {
                        val editor = prefs.edit()
                        if (deleted) {
                            editor.remove(KEY_PLAN_RANGE_PENDING_URI)
                                .remove(KEY_PLAN_RANGE_PENDING_NAME)
                                .remove(KEY_PLAN_RANGE_WARNING)
                        } else {
                            editor.putString(KEY_PLAN_RANGE_WARNING, "$reason\n\n$warning")
                        }
                        runCatching { editor.commit() }
                    }
                    PlanRangePdfCleanupResult(
                        deleted,
                        warning ?: "Il documento creato nella destinazione scelta è stato eliminato.",
                    )
                }
            }
        }

    private fun persistPendingPlanRangePdf(uri: Uri, request: PendingPlanRangePdf?): Boolean {
        val prefs = planRangePdfRecoveryPreferences()
        val existingUri = prefs.getString(KEY_PLAN_RANGE_PENDING_URI, null)
        if (existingUri != null && existingUri != uri.toString()) return false
        return runCatching {
            prefs.edit()
                .putString(KEY_PLAN_RANGE_PENDING_URI, uri.toString())
                .putString(KEY_PLAN_RANGE_PENDING_NAME, request?.suggestedFileName())
                .remove(KEY_PLAN_RANGE_WARNING)
                .commit()
        }.getOrDefault(false)
    }

    private fun clearPendingPlanRangePdf(uri: Uri): Boolean {
        val prefs = planRangePdfRecoveryPreferences()
        if (prefs.getString(KEY_PLAN_RANGE_PENDING_URI, null) != uri.toString()) return false
        return runCatching {
            prefs.edit()
                .remove(KEY_PLAN_RANGE_PENDING_URI)
                .remove(KEY_PLAN_RANGE_PENDING_NAME)
                .remove(KEY_PLAN_RANGE_WARNING)
                .commit()
        }.getOrDefault(false)
    }

    private fun readPendingPlanRangePdf(): PendingPlanRangePdfCleanup? {
        val prefs = planRangePdfRecoveryPreferences()
        val uri = prefs.getString(KEY_PLAN_RANGE_PENDING_URI, null)?.let(Uri::parse) ?: return null
        return PendingPlanRangePdfCleanup(uri, prefs.getString(KEY_PLAN_RANGE_PENDING_NAME, null))
    }

    private fun showPendingPlanRangePdfWarning(
        message: String? = planRangePdfRecoveryPreferences().getString(KEY_PLAN_RANGE_WARNING, null),
    ) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || planRangePdfWarningVisible) return
        val warning = message ?: return
        planRangePdfWarningVisible = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Controlla il PDF non completato")
            .setMessage(warning)
            .setPositiveButton("Chiudi", null)
            .setOnDismissListener { planRangePdfWarningVisible = false }
            .show()
    }

    private fun planRangePdfRecoveryPreferences() =
        applicationContext.getSharedPreferences(PREFS_PLAN_RANGE_PDF_RECOVERY, Context.MODE_PRIVATE)

    private fun buildPlanRangePdfWarning(suggestedName: String?): String {
        val identifier = suggestedName?.let {
            "Cerca il PDF creato in questa operazione (nome iniziale: $it, eventualmente rinominato nella finestra di salvataggio)"
        } ?: "Cerca il PDF appena creato nella destinazione scelta"
        return "$identifier: il provider non ha consentito la rimozione. Il file può essere vuoto o incompleto; non condividerlo e cancellalo manualmente."
    }

    private fun PendingPlanRangePdf.suggestedFileName() = "myfitai-piano-${range.start}-${range.endInclusive}.pdf"

    private fun PreparedPlanRangePdf.fingerprint(): String {
        // Data-class rendering includes profile, range, effective plan/version provenance and all PDF input data.
        val bytes = MessageDigest.getInstance("SHA-256").digest(toString().toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun showExportError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Intervallo non valido")
            .setMessage(message)
            .setPositiveButton("Chiudi", null)
            .show()
    }

    private fun formatExportDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALIAN))

    private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun Long.toLocalDateUtc(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

    private companion object {
        const val PREFS_PLAN_RANGE_PDF_RECOVERY = "plan_range_pdf_recovery"
        const val KEY_PLAN_RANGE_PENDING_URI = "pending_uri"
        const val KEY_PLAN_RANGE_PENDING_NAME = "pending_name"
        const val KEY_PLAN_RANGE_WARNING = "warning"
        const val STATE_PLAN_RANGE_PROFILE_ID = "export.plan_range.profile_id"
        const val STATE_PLAN_RANGE_START = "export.plan_range.start_epoch_day"
        const val STATE_PLAN_RANGE_END = "export.plan_range.end_epoch_day"
        const val STATE_PLAN_RANGE_FINGERPRINT = "export.plan_range.fingerprint"
    }

    private data class PendingPlanRangePdf(val profileId: Long, val range: DateRange, val fingerprint: String)

    private data class PendingPlanRangePdfCleanup(val uri: Uri, val suggestedName: String?)

    private data class PlanRangePdfCleanupResult(val deleted: Boolean, val message: String)

    private enum class PlanRangePdfSaveStage(val message: String) {
        PREPARATION("Preparazione o verifica della preview non riuscita"),
        OPEN_DESTINATION("Apertura della destinazione PDF non riuscita"),
        STREAM_WRITE("Scrittura del PDF non riuscita"),
    }

    private class PlanRangePreviewChangedException : IllegalStateException(
        "Il piano o il profilo è cambiato dopo l'anteprima. Nessun dato è stato scritto: rigenera la preview e conferma di nuovo."
    )

    private fun saveBackup(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val exported = withContext(Dispatchers.IO) { service.export(ProfileExportService.Format.JSON) }
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output -> exported.file.inputStream().use { input -> input.copyTo(output) } }
                        ?: error("Impossibile salvare il backup")
                }
            }.onSuccess { Toast.makeText(this@ExportActivity, "Backup salvato su disco", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this@ExportActivity, "Backup non salvato: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun importBackup(uri: Uri) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Importa backup completo?")
             .setMessage("Il profilo attivo e tutti i suoi dati verranno sostituiti dai dati del file: BIA, misure, alimentazione, storico, consumi e versioni dei piani. Le chiavi IA non fanno parte del backup. L'operazione è transazionale.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Importa") { _, _ ->
                lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { data.profileBackupService.restore(uri) } }
                        .onSuccess { name ->
                            Toast.makeText(this@ExportActivity, "Profilo ripristinato: $name", Toast.LENGTH_LONG).show()
                            restartApp()
                        }
                        .onFailure { Toast.makeText(this@ExportActivity, "Importazione non riuscita: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }.show()
    }

    private fun restartApp() {
        startActivity(Intent(this, SplashActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        finish()
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

private object PlanRangePdfSaveLifecycle {
    private val lock = Any()
    private val cleanupMutex = Mutex()
    private val activeWrites = mutableMapOf<String, CompletableDeferred<Unit>>()

    fun beginWrite(uri: Uri) {
        synchronized(lock) { activeWrites[uri.toString()] = CompletableDeferred() }
    }

    fun finishWrite(uri: Uri) {
        val completion = synchronized(lock) { activeWrites.remove(uri.toString()) }
        completion?.complete(Unit)
    }

    suspend fun awaitWrite(uri: Uri) {
        val completion = synchronized(lock) { activeWrites[uri.toString()] }
        completion?.await()
    }

    suspend fun <T> withCleanupLock(block: suspend () -> T): T {
        cleanupMutex.lock()
        return try {
            block()
        } finally {
            cleanupMutex.unlock()
        }
    }
}
