package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.domain.export.ProfileExportService
import com.myfitai.app.navigation.BottomNavBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExportActivity : BaseShellActivity() {
    private val service by lazy { AppDataContainer.get(this).profileExportService }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

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
}
