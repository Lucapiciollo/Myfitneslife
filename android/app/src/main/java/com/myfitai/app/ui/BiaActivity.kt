package com.myfitai.app.ui

import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.domain.body.AiImageProcessor
import com.myfitai.app.domain.body.AiImageTempStore
import com.myfitai.app.domain.body.BiaImportContract
import com.myfitai.app.data.local.entity.AiJobResultEntity
import com.myfitai.app.domain.ai.AiJobState
import com.myfitai.app.domain.ai.AiJobType
import com.myfitai.app.domain.ai.AiJobWorker
import com.myfitai.app.domain.ai.BiaImportAiJobHandler
import com.myfitai.app.domain.ai.AiImageJobStore
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.bia.BiaViewModel
import com.myfitai.app.ui.widgets.MeasurementRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BiaActivity : BaseShellActivity() {

    private val data by lazy { AppDataContainer.get(this) }
    private val viewModel: BiaViewModel by viewModels {
        BiaViewModel.Factory(data.biaRepository, data.activeProfileStore, data.nutritionPathTrigger)
    }

    private lateinit var dateInput: TextInputEditText
    private lateinit var timeInput: TextInputEditText
    private lateinit var newMeasurementContainer: View
    private lateinit var historyContainer: View
    private lateinit var historyList: LinearLayout
    private lateinit var historySummary: TextView

    private val values = linkedMapOf<String, Float?>(
        KEY_WEIGHT to null,
        KEY_BODY_FAT to null,
        KEY_VISCERAL_FAT to null,
        KEY_MUSCLE_MASS to null,
        KEY_SKELETAL_MUSCLE to null,
        KEY_BODY_WATER to null,
        KEY_BMR to null,
    )

    private var selectedDateMillis: Long = System.currentTimeMillis()
    private var selectedHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private var selectedMinute: Int = Calendar.getInstance().get(Calendar.MINUTE)
    private var pendingImportFile: File? = null
    private val imageTempStore by lazy { AiImageTempStore(this) }
    private val backgroundImageStore by lazy { AiImageJobStore(this) }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) processImportUri(uri)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingImportFile
        pendingImportFile = null
        if (success && file != null) processImportFile(file) else imageTempStore.delete(file)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bia)
        bindBottom(BottomNavBinder.Tab.MORE)
        bindBack()
        bindViews()
        bindSegments()
        bindDateTime()
        bindMeasurementRows()
        bindSave()
        bindPhotoImport()
        observeState()
        renderDateTime()
        intent.getStringExtra(EXTRA_AI_JOB_KEY)?.let(::reattachImportJob)
        if (intent.getBooleanExtra(EXTRA_OPEN_HISTORY, false)) {
            findViewById<SelectableSegmentView>(R.id.biaSegment).getChildAt(1)?.performClick()
        }
    }

    private fun bindViews() {
        dateInput = findViewById(R.id.dateInput)
        timeInput = findViewById(R.id.timeInput)
        newMeasurementContainer = findViewById(R.id.newMeasurementContainer)
        historyContainer = findViewById(R.id.historyContainer)
        historyList = findViewById(R.id.historyList)
        historySummary = findViewById(R.id.historySummary)
    }

    private fun bindSegments() {
        val segment = findViewById<SelectableSegmentView>(R.id.biaSegment)
        segment.setSegments(listOf("Nuova misurazione", "Storico"), selectedIndex = 0)
        segment.setOnSegmentSelectedListener { index ->
            newMeasurementContainer.visibility = if (index == 0) View.VISIBLE else View.GONE
            historyContainer.visibility = if (index == 1) View.VISIBLE else View.GONE
            if (index == 1) renderHistory(viewModel.history.value)
        }
    }

    private fun bindDateTime() {
        dateInput.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Data misurazione")
                .setSelection(selectedDateMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                selectedDateMillis = selection
                renderDateTime()
            }
            picker.show(supportFragmentManager, "bia_date_picker")
        }

        timeInput.setOnClickListener {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(selectedHour)
                .setMinute(selectedMinute)
                .setTitleText("Ora misurazione")
                .build()
            picker.addOnPositiveButtonClickListener {
                selectedHour = picker.hour
                selectedMinute = picker.minute
                renderDateTime()
            }
            picker.show(supportFragmentManager, "bia_time_picker")
        }
    }

    private fun bindMeasurementRows() {
        bindRow(R.id.rowWeight, KEY_WEIGHT, "Peso", "kg")
        bindRow(R.id.rowBodyFat, KEY_BODY_FAT, "Grasso corporeo", "%")
        bindRow(R.id.rowVisceralFat, KEY_VISCERAL_FAT, "Grasso viscerale", "")
        bindRow(R.id.rowMuscleMass, KEY_MUSCLE_MASS, "Massa muscolare", "kg")
        bindRow(R.id.rowSkeletalMuscle, KEY_SKELETAL_MUSCLE, "Muscolo scheletrico", "kg")
        bindRow(R.id.rowBodyWater, KEY_BODY_WATER, "Acqua corporea", "%")
        bindRow(R.id.rowBmr, KEY_BMR, "BMR", "kcal")
    }

    private fun bindRow(viewId: Int, key: String, label: String, unit: String) {
        findViewById<MeasurementRowView>(viewId).apply {
            showIcon()
            setLabel(label)
            setValue(values[key]?.let { formatValue(it, unit) } ?: "—")
            isClickable = true
            isFocusable = true
            setOnClickListener { showValueDialog(key, label, unit, this) }
        }
    }

    private fun showValueDialog(key: String, label: String, unit: String, row: MeasurementRowView) {
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = if (unit.isBlank()) "Valore" else "Valore in $unit"
            values[key]?.let { setText(formatNumber(it)) }
            setSelectAllOnFocus(true)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setView(container)
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Svuota") { _, _ ->
                values[key] = null
                row.setValue("—")
            }
            .setPositiveButton("Conferma") { _, _ ->
                val value = parseFloat(input.text?.toString())
                values[key] = value
                row.setValue(value?.let { formatValue(it, unit) } ?: "—")
            }
            .show()
    }

    private fun bindSave() {
        findViewById<View>(R.id.saveButton).setOnClickListener {
            viewModel.save(
                measuredAtEpochMillis = composeMeasurementMillis(),
                weightKg = values[KEY_WEIGHT],
                bodyFatPercent = values[KEY_BODY_FAT],
                visceralFatLevel = values[KEY_VISCERAL_FAT],
                muscleMassKg = values[KEY_MUSCLE_MASS],
                skeletalMuscleKg = values[KEY_SKELETAL_MUSCLE],
                bodyWaterPercent = values[KEY_BODY_WATER],
                bmrKcal = values[KEY_BMR],
                fasting = findViewById<MaterialCheckBox>(R.id.checkFasting).isChecked,
                justWokeUp = findViewById<MaterialCheckBox>(R.id.checkJustWoken).isChecked,
                afterBathroom = findViewById<MaterialCheckBox>(R.id.checkAfterShower).isChecked,
                noRecentWorkout = findViewById<MaterialCheckBox>(R.id.checkNoWorkout).isChecked,
            )
        }
    }

    private fun bindPhotoImport() {
        findViewById<View>(R.id.importPhotoButton).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Importa BIA da foto")
                .setItems(arrayOf("Scatta foto", "Scegli dalla galleria")) { _, which ->
                    if (which == 0) {
                        val file = imageTempStore.create()
                        pendingImportFile = file
                        cameraLauncher.launch(FileProvider.getUriForFile(this, "$packageName.fileprovider", file))
                    } else {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                .show()
        }
    }

    private fun processImportUri(uri: Uri) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { AiImageProcessor.fromUri(this@BiaActivity, uri) } }
                .onSuccess { importImage(it) }
                .onFailure { Toast.makeText(this@BiaActivity, "Impossibile leggere la foto", Toast.LENGTH_LONG).show() }
        }
    }

    private fun processImportFile(file: File) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { AiImageProcessor.fromFile(file) } }
                .onSuccess { importImage(it) }
                .onFailure { Toast.makeText(this@BiaActivity, "Impossibile leggere la foto", Toast.LENGTH_LONG).show() }
                .also { withContext(Dispatchers.IO) { imageTempStore.delete(file) } }
        }
    }

    private fun importImage(image: com.myfitai.app.ai.AiImageInput) {
        confirmAiRequest("La lettura IA dei valori BIA dalla foto") {
            val profileId = data.activeProfileStore.currentIdOrNull() ?: return@confirmAiRequest
            val jobKey = "${System.currentTimeMillis()}"
            val imagePath = backgroundImageStore.write(image)
            data.aiJobScheduler.enqueue(
                AiJobType.BIA_IMPORT,
                profileId,
                jobKey,
                androidx.work.Data.Builder().putString(AiJobWorker.KEY_IMAGE_PATH, imagePath).build(),
            )
            observeImportJob(profileId, jobKey)
        }
    }

    private fun observeImportJob(profileId: Long, jobKey: String) {
        lifecycleScope.launch {
            data.aiJobScheduler.observe(AiJobType.BIA_IMPORT, profileId, jobKey).collect { state ->
                when (state) {
                    AiJobState.Idle -> Unit
                    AiJobState.Running -> Toast.makeText(this@BiaActivity, "Lettura BIA in corso…", Toast.LENGTH_SHORT).show()
                    is AiJobState.Succeeded -> {
                        data.aiJobScheduler.consume(state.id)
                        val row = data.aiJobResultRepository.find(profileId, AiJobType.BIA_IMPORT, jobKey)
                        row?.payloadJson?.let { result ->
                            val decoded = BiaImportAiJobHandler.decode(result)
                            showImportPreview(decoded.preview, decoded.provider, decoded.model)
                        }
                    }
                    is AiJobState.Failed -> {
                        data.aiJobScheduler.consume(state.id)
                        Toast.makeText(this@BiaActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun reattachImportJob(jobKey: String) {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        lifecycleScope.launch {
            val row = data.aiJobResultRepository.find(profileId, AiJobType.BIA_IMPORT, jobKey)
            when {
                row?.status == AiJobResultEntity.STATUS_SUCCEEDED && row.payloadJson != null -> {
                    val decoded = BiaImportAiJobHandler.decode(row.payloadJson)
                    showImportPreview(decoded.preview, decoded.provider, decoded.model)
                }
                row?.status == AiJobResultEntity.STATUS_FAILED -> Toast.makeText(this@BiaActivity, row.errorMessage ?: "Importazione BIA non riuscita", Toast.LENGTH_LONG).show()
                else -> observeImportJob(profileId, jobKey)
            }
        }
    }

    private fun showImportPreview(preview: BiaImportContract.Preview, provider: String, model: String) {
        val fields = linkedMapOf(
            "Peso" to (preview.weightKg to "kg"),
            "Grasso corporeo" to (preview.bodyFatPercent to "%"),
            "Grasso viscerale" to (preview.visceralFatLevel to "livello"),
            "Massa muscolare" to (preview.muscleMassKg to "kg"),
            "Muscolo scheletrico" to (preview.skeletalMuscleKg to "kg"),
            "Acqua corporea" to (preview.bodyWaterPercent to "%"),
            "BMR" to (preview.bmrKcal to "kcal"),
        )
        val inputs = linkedMapOf<String, TextInputEditText>()
        val importCalendar = Calendar.getInstance().apply {
            timeInMillis = preview.measuredAtEpochMillis ?: composeMeasurementMillis()
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(12))
        }

        val statusCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.surface_positive_soft))
            strokeColor = getColor(R.color.positive_soft_stroke)
            strokeWidth = dp(1)
        }
        statusCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))

            addView(TextView(this@BiaActivity).apply {
                text = "BIA"
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                background = roundedBackground(getColor(R.color.accent_green), dp(12))
            }, LinearLayout.LayoutParams(dp(48), dp(48)))

            addView(LinearLayout(this@BiaActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(this@BiaActivity).apply {
                    text = "Lettura pronta da controllare"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(this@BiaActivity).apply {
                    text = "${provider.replace('_', ' ')} · ${model.replace('_', ' ')}"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 12f
                    setPadding(0, dp(3), 0, 0)
                })
                addView(TextView(this@BiaActivity).apply {
                    text = "Confidenza ${preview.confidence.lowercase(Locale.ITALIAN)}"
                    setTextColor(getColor(R.color.accent_green_dark))
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        content.addView(statusCard, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            text = "DATA E ORA DELLA MISURAZIONE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text_muted))
            setPadding(dp(2), dp(16), 0, dp(6))
        })

        val dateValue = TextView(this).apply {
            text = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(importCalendar.time)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.accent_green_dark))
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        val timeValue = TextView(this).apply {
            text = String.format(
                Locale.ITALIAN,
                "%02d:%02d",
                importCalendar.get(Calendar.HOUR_OF_DAY),
                importCalendar.get(Calendar.MINUTE),
            )
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.accent_green_dark))
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }

        val dateTimeCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.surface_primary))
            strokeColor = getColor(R.color.divider)
            strokeWidth = dp(1)
        }
        val dateTimeBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun dateTimeRow(label: String, icon: Int, value: TextView, onClick: () -> Unit): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }

                addView(android.widget.ImageView(this@BiaActivity).apply {
                    setImageResource(icon)
                    imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent_green_dark))
                }, LinearLayout.LayoutParams(dp(22), dp(22)))

                addView(TextView(this@BiaActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(getColor(R.color.text_primary))
                    setPadding(dp(10), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })

                addView(value, LinearLayout.LayoutParams(dp(110), dp(42)))
            }

        dateTimeBody.addView(dateTimeRow("Data", R.drawable.ic_calendar, dateValue) {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Data misurazione")
                .setSelection(importCalendar.timeInMillis)
                .build()
            picker.addOnPositiveButtonClickListener { selection ->
                val picked = Calendar.getInstance().apply { timeInMillis = selection }
                importCalendar.set(Calendar.YEAR, picked.get(Calendar.YEAR))
                importCalendar.set(Calendar.MONTH, picked.get(Calendar.MONTH))
                importCalendar.set(Calendar.DAY_OF_MONTH, picked.get(Calendar.DAY_OF_MONTH))
                dateValue.text = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(importCalendar.time)
            }
            picker.show(supportFragmentManager, "bia_import_date_picker")
        })
        dateTimeBody.addView(View(this).apply {
            setBackgroundColor(getColor(R.color.divider))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
        })
        dateTimeBody.addView(dateTimeRow("Ora", R.drawable.ic_setting_clock, timeValue) {
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(importCalendar.get(Calendar.HOUR_OF_DAY))
                .setMinute(importCalendar.get(Calendar.MINUTE))
                .setTitleText("Ora misurazione")
                .build()
            picker.addOnPositiveButtonClickListener {
                importCalendar.set(Calendar.HOUR_OF_DAY, picker.hour)
                importCalendar.set(Calendar.MINUTE, picker.minute)
                timeValue.text = String.format(Locale.ITALIAN, "%02d:%02d", picker.hour, picker.minute)
            }
            picker.show(supportFragmentManager, "bia_import_time_picker")
        })
        dateTimeCard.addView(dateTimeBody)
        content.addView(dateTimeCard, LinearLayout.LayoutParams(-1, -2))

        content.addView(TextView(this).apply {
            text = "RISULTATI"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.text_muted))
            setPadding(dp(2), dp(16), 0, dp(6))
        })

        val resultsCard = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(getColor(R.color.surface_primary))
            strokeColor = getColor(R.color.divider)
            strokeWidth = dp(1)
        }
        val resultsBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fields.entries.forEachIndexed { index, entry ->
            val label = entry.key
            val valueAndUnit = entry.value
            val input = TextInputEditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                valueAndUnit.first?.let { setText(formatNumber(it)) }
                hint = "—"
                setSingleLine(true)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_muted))
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                background = null
                setPadding(dp(4), 0, dp(6), 0)
                minWidth = dp(74)
                setSelectAllOnFocus(true)
            }
            inputs[label] = input

            resultsBody.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))

                addView(TextView(this@BiaActivity).apply {
                    text = label
                    textSize = 13f
                    setTextColor(getColor(R.color.text_primary))
                }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                })

                val valuePill = MaterialCardView(this@BiaActivity).apply {
                    radius = dp(10).toFloat()
                    cardElevation = 0f
                    setCardBackgroundColor(getColor(R.color.surface_secondary))
                    addView(LinearLayout(this@BiaActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(8), 0, dp(8), 0)
                        addView(input, LinearLayout.LayoutParams(dp(78), dp(40)))
                        addView(TextView(this@BiaActivity).apply {
                            text = valueAndUnit.second
                            textSize = 11f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(getColor(R.color.accent_green_dark))
                        }, LinearLayout.LayoutParams(-2, dp(40)).apply {
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        })
                    })
                }
                addView(valuePill, LinearLayout.LayoutParams(-2, dp(40)))
            })

            if (index < fields.size - 1) {
                resultsBody.addView(View(this).apply {
                    setBackgroundColor(getColor(R.color.divider))
                }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(14)
                    marginEnd = dp(14)
                })
            }
        }
        resultsCard.addView(resultsBody)
        content.addView(resultsCard, LinearLayout.LayoutParams(-1, -2))

        if (preview.notes.isNotBlank() && preview.notes.lowercase(Locale.ITALIAN) != "none") {
            content.addView(TextView(this).apply {
                text = "Nota IA: ${preview.notes}"
                setTextColor(getColor(R.color.text_muted))
                textSize = 11f
                setPadding(dp(2), dp(10), dp(2), 0)
            })
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, ScrollView.LayoutParams(-1, -2))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Controlla importazione BIA")
            .setView(scroll)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Usa valori") { _, _ ->
                values[KEY_WEIGHT] = parseFloat(inputs.getValue("Peso").text?.toString())
                values[KEY_BODY_FAT] = parseFloat(inputs.getValue("Grasso corporeo").text?.toString())
                values[KEY_VISCERAL_FAT] = parseFloat(inputs.getValue("Grasso viscerale").text?.toString())
                values[KEY_MUSCLE_MASS] = parseFloat(inputs.getValue("Massa muscolare").text?.toString())
                values[KEY_SKELETAL_MUSCLE] = parseFloat(inputs.getValue("Muscolo scheletrico").text?.toString())
                values[KEY_BODY_WATER] = parseFloat(inputs.getValue("Acqua corporea").text?.toString())
                values[KEY_BMR] = parseFloat(inputs.getValue("BMR").text?.toString())
                bindMeasurementRows()

                selectedDateMillis = importCalendar.timeInMillis
                selectedHour = importCalendar.get(Calendar.HOUR_OF_DAY)
                selectedMinute = importCalendar.get(Calendar.MINUTE)
                renderDateTime()
            }
            .show()
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.history.collect { history ->
                        if (historyContainer.visibility == View.VISIBLE) renderHistory(history)
                    }
                }
                launch {
                    viewModel.saved.collect {
                        Toast.makeText(this@BiaActivity, "Misurazione BIA aggiunta allo storico", Toast.LENGTH_SHORT).show()
                        resetForm()
                    }
                }
                launch {
                    viewModel.deleted.collect {
                        Toast.makeText(this@BiaActivity, "Misurazione BIA eliminata dallo storico", Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.error.collect { message ->
                        Toast.makeText(this@BiaActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderHistory(history: List<BiaMeasurementEntity>) {
        historyList.removeAllViews()
        if (history.isEmpty()) {
            historySummary.text = "Nessuna misurazione BIA salvata per questo profilo."
            return
        }

        val latest = history.first()
        historySummary.text = buildSummary(history, latest)

        history.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                val p = (16 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (10 * resources.displayMetrics.density).toInt() }
            }

            card.addView(TextView(this).apply {
                text = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(item.measuredAtEpochMillis))
                setTextColor(getColor(R.color.text_primary))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 14f
            })
            card.addView(TextView(this).apply {
                text = buildMeasurementLine(item)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            buildConditionLine(item)?.let { conditions ->
                card.addView(TextView(this).apply {
                    text = conditions
                    setTextColor(getColor(R.color.text_muted))
                    textSize = 11f
                    setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
                })
            }
            card.addView(TextView(this).apply {
                text = "Tieni premuto per eliminare"
                setTextColor(getColor(R.color.text_muted))
                textSize = 10f
                setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            card.setOnLongClickListener {
                confirmDelete(item)
                true
            }
            historyList.addView(card)
        }
    }

    private fun buildSummary(history: List<BiaMeasurementEntity>, latest: BiaMeasurementEntity): String {
        val lines = mutableListOf("${history.size} misurazioni salvate")
        latest.weightKg?.let { current ->
            val previous = previousValue(history, latest) { it.weightKg }
            val delta = previous?.let { current - it }
            lines += "Peso attuale ${formatValue(current, "kg")}${delta?.let { "  (${formatSigned(it)} kg vs precedente disponibile)" }.orEmpty()}"
        }
        latest.bodyFatPercent?.let { current ->
            val previous = previousValue(history, latest) { it.bodyFatPercent }
            val delta = previous?.let { current - it }
            lines += "Grasso ${formatValue(current, "%")}${delta?.let { "  (${formatSigned(it)} pp vs precedente disponibile)" }.orEmpty()}"
        }
        latest.muscleMassKg?.let { current ->
            val previous = previousValue(history, latest) { it.muscleMassKg }
            val delta = previous?.let { current - it }
            lines += "Massa muscolare ${formatValue(current, "kg")}${delta?.let { "  (${formatSigned(it)} kg)" }.orEmpty()}"
        }
        val avgWeight = history.mapNotNull { it.weightKg }.takeIf { it.isNotEmpty() }?.average()
        if (avgWeight != null) lines += "Media peso storico ${String.format(Locale.ITALIAN, "%.1f kg", avgWeight)}"
        return lines.joinToString("\n")
    }

    private fun previousValue(
        history: List<BiaMeasurementEntity>,
        current: BiaMeasurementEntity,
        selector: (BiaMeasurementEntity) -> Float?,
    ): Float? {
        val index = history.indexOfFirst { it.id == current.id }
        if (index < 0) return null
        return history.drop(index + 1).firstNotNullOfOrNull(selector)
    }

    private fun buildMeasurementLine(item: BiaMeasurementEntity): String = listOfNotNull(
        item.weightKg?.let { "Peso ${formatValue(it, "kg")}" },
        item.bodyFatPercent?.let { "Grasso ${formatValue(it, "%")}" },
        item.visceralFatLevel?.let { "Viscerale ${formatValue(it, "")}" },
        item.muscleMassKg?.let { "Massa muscolare ${formatValue(it, "kg")}" },
        item.skeletalMuscleKg?.let { "Scheletrico ${formatValue(it, "kg")}" },
        item.bodyWaterPercent?.let { "Acqua ${formatValue(it, "%")}" },
        item.bmrKcal?.let { "BMR ${formatValue(it, "kcal")}" },
    ).joinToString(" · ").ifBlank { "Valori parziali" }

    private fun buildConditionLine(item: BiaMeasurementEntity): String? {
        val conditions = listOfNotNull(
            "a digiuno".takeIf { item.fasting },
            "appena sveglio".takeIf { item.justWokeUp },
            "dopo bagno".takeIf { item.afterBathroom },
            "nessun allenamento recente".takeIf { item.noRecentWorkout },
        )
        return conditions.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "Condizioni: ")
    }

    private fun confirmDelete(item: BiaMeasurementEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Elimina misurazione")
            .setMessage("Vuoi eliminare questa misurazione BIA dallo storico? L'operazione non è reversibile.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Elimina") { _, _ -> viewModel.delete(item) }
            .show()
    }

    private fun resetForm() {
        values.keys.forEach { values[it] = null }
        bindMeasurementRows()
        selectedDateMillis = System.currentTimeMillis()
        val now = Calendar.getInstance()
        selectedHour = now.get(Calendar.HOUR_OF_DAY)
        selectedMinute = now.get(Calendar.MINUTE)
        findViewById<MaterialCheckBox>(R.id.checkFasting).isChecked = false
        findViewById<MaterialCheckBox>(R.id.checkJustWoken).isChecked = false
        findViewById<MaterialCheckBox>(R.id.checkAfterShower).isChecked = false
        findViewById<MaterialCheckBox>(R.id.checkNoWorkout).isChecked = false
        renderDateTime()
    }

    private fun renderDateTime() {
        dateInput.setText(SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(Date(selectedDateMillis)))
        timeInput.setText(String.format(Locale.ITALIAN, "%02d:%02d", selectedHour, selectedMinute))
    }

    private fun composeMeasurementMillis(): Long {
        val base = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, base.get(Calendar.YEAR))
            set(Calendar.MONTH, base.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, base.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, selectedHour)
            set(Calendar.MINUTE, selectedMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    private fun parseFloat(raw: String?): Float? = raw
        ?.trim()
        ?.replace(',', '.')
        ?.takeIf { it.isNotEmpty() }
        ?.toFloatOrNull()

    private fun formatValue(value: Float, unit: String): String = if (unit.isBlank()) {
        formatNumber(value)
    } else {
        "${formatNumber(value)} $unit"
    }

    private fun formatNumber(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.ITALIAN, "%.1f", value)

    private fun formatSigned(value: Float): String = String.format(Locale.ITALIAN, "%+.1f", value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_AI_JOB_KEY = "bia_import_job_key"
        const val EXTRA_OPEN_HISTORY = "open_bia_history"
        private const val KEY_WEIGHT = "weight"
        private const val KEY_BODY_FAT = "bodyFat"
        private const val KEY_VISCERAL_FAT = "visceralFat"
        private const val KEY_MUSCLE_MASS = "muscleMass"
        private const val KEY_SKELETAL_MUSCLE = "skeletalMuscle"
        private const val KEY_BODY_WATER = "bodyWater"
        private const val KEY_BMR = "bmr"
    }
}
