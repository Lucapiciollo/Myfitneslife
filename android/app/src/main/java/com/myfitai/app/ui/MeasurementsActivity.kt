package com.myfitai.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.navigation.BottomNavBinder
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class MeasurementsActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ITALIAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measurements)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()

        findViewById<android.view.View>(R.id.newBiaButton).setOnClickListener { startActivity(Intent(this, BiaActivity::class.java)) }
        findViewById<android.view.View>(R.id.biaHistoryButton).setOnClickListener {
            startActivity(Intent(this, BiaActivity::class.java).putExtra(BiaActivity.EXTRA_OPEN_HISTORY, true))
        }
        findViewById<android.view.View>(R.id.newBodyButton).setOnClickListener { startActivity(Intent(this, BodyMeasuresActivity::class.java)) }
        findViewById<android.view.View>(R.id.bodyHistoryButton).setOnClickListener {
            startActivity(Intent(this, BodyMeasuresActivity::class.java).putExtra(BodyMeasuresActivity.EXTRA_OPEN_HISTORY, true))
        }
        findViewById<android.view.View>(R.id.allHistoryButton).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest(data.biaRepository::latest)
                        .collect(::renderBia)
                }
                launch {
                    data.activeProfileStore.activeProfileId
                        .flatMapLatest(data.bodyMeasurementRepository::latest)
                        .collect(::renderBody)
                }
            }
        }
    }

    private fun renderBia(value: BiaMeasurementEntity?) {
        findViewById<TextView>(R.id.biaLatestText).text = value?.let {
            val date = formatDate(it.measuredAtEpochMillis)
            val details = listOfNotNull(
                it.weightKg?.let { weight -> "Peso ${formatNumber(weight)} kg" },
                it.bodyFatPercent?.let { fat -> "Grasso ${formatNumber(fat)}%" },
                it.muscleMassKg?.let { muscle -> "Massa muscolare ${formatNumber(muscle)} kg" },
            ).joinToString(" · ")
            "Ultima rilevazione: $date\n${details.ifBlank { "Valori parziali" }}"
        } ?: "Nessuna rilevazione BIA disponibile"
    }

    private fun renderBody(value: BodyMeasurementEntity?) {
        findViewById<TextView>(R.id.bodyLatestText).text = value?.let {
            val details = listOfNotNull(
                it.waistCm?.let { waist -> "Vita ${formatNumber(waist)} cm" },
                it.abdomenCm?.let { abdomen -> "Addome ${formatNumber(abdomen)} cm" },
                it.chestCm?.let { chest -> "Torace ${formatNumber(chest)} cm" },
            ).joinToString(" · ")
            "Ultima rilevazione: ${formatDate(it.measuredAtEpochMillis)}\n${details.ifBlank { "Valori parziali" }}"
        } ?: "Nessuna misura corporea disponibile"
    }

    private fun formatDate(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(dateFormatter)

    private fun formatNumber(value: Float): String = if (value % 1f == 0f) {
        value.toInt().toString()
    } else {
        String.format(Locale.ITALIAN, "%.1f", value)
    }
}
