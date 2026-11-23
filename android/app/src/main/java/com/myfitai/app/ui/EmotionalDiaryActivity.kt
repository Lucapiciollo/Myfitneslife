package com.myfitai.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textfield.TextInputEditText
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.MoodCheckInEntity
import com.myfitai.app.data.profile.ActiveProfileStore
import com.myfitai.app.domain.diary.EmotionalDiaryRules
import com.myfitai.app.domain.diary.MoodLevel
import com.myfitai.app.navigation.BottomNavBinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class EmotionalDiaryActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private val activeProfileStore: ActiveProfileStore by lazy { data.activeProfileStore }
    private var activeProfileId: Long? = null
    private var weekStartEpochDay = 0L
    private var selectedDateEpochDay = 0L
    private var weekMoods: Map<Long, MoodCheckInEntity> = emptyMap()
    private var weeklyNote: String? = null
    private var loadJob: Job? = null
    private var renderingMood = false

    private val dayFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN)
    private val shortDayFormatter = DateTimeFormatter.ofPattern("EEEE d", Locale.ITALIAN)
    private val weekFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ITALIAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_emotional_diary)
        bindBottom(BottomNavBinder.Tab.MORE)

        activeProfileId = activeProfileStore.currentIdOrNull()
        val today = data.emotionalDiaryService.todayEpochDay
        weekStartEpochDay = savedInstanceState?.getLong(STATE_WEEK_START)
            ?: EmotionalDiaryRules.weekStartEpochDay(today)
        selectedDateEpochDay = savedInstanceState?.getLong(STATE_SELECTED_DATE) ?: today
        if (selectedDateEpochDay !in weekStartEpochDay..weekStartEpochDay + 6) {
            selectedDateEpochDay = today
        }

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.prevWeekButton).setOnClickListener { changeWeek(-1) }
        findViewById<ImageButton>(R.id.nextWeekButton).setOnClickListener { changeWeek(1) }
        findViewById<View>(R.id.deleteMoodButton).setOnClickListener { deleteMood() }
        findViewById<View>(R.id.saveWeeklyNoteButton).setOnClickListener { saveWeeklyNote() }
        findViewById<View>(R.id.deleteWeeklyNoteButton).setOnClickListener { deleteWeeklyNote() }
        bindMoodOptions()
        renderWeekHeader()
    }

    override fun onResume() {
        super.onResume()
        activeProfileStore.refreshFromPersistence()
        val profileId = activeProfileStore.currentIdOrNull()
        if (profileId != activeProfileId) {
            activeProfileId = profileId
            val today = data.emotionalDiaryService.todayEpochDay
            weekStartEpochDay = EmotionalDiaryRules.weekStartEpochDay(today)
            selectedDateEpochDay = today
        }
        refreshWeek()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_WEEK_START, weekStartEpochDay)
        outState.putLong(STATE_SELECTED_DATE, selectedDateEpochDay)
        super.onSaveInstanceState(outState)
    }

    private fun bindMoodOptions() {
        MoodLevel.entries.forEach { mood ->
            findViewById<MaterialRadioButton>(moodButtonId(mood)).text = "${mood.emoji}  ${mood.label}"
        }
        findViewById<RadioGroup>(R.id.moodOptions).setOnCheckedChangeListener { _, checkedId ->
            if (renderingMood || checkedId == -1) return@setOnCheckedChangeListener
            val mood = MoodLevel.entries.firstOrNull { moodButtonId(it) == checkedId } ?: return@setOnCheckedChangeListener
            val profileId = activeProfileId ?: return@setOnCheckedChangeListener
            lifecycleScope.launch {
                runCatching {
                    data.emotionalDiaryService.saveMood(profileId, selectedDateEpochDay, mood)
                }.onSuccess {
                    refreshWeek()
                }.onFailure {
                    Toast.makeText(this@EmotionalDiaryActivity, "Registrazione non riuscita", Toast.LENGTH_LONG).show()
                    refreshWeek()
                }
            }
        }
    }

    private fun changeWeek(offset: Long) {
        val today = data.emotionalDiaryService.todayEpochDay
        val currentWeekStart = EmotionalDiaryRules.weekStartEpochDay(today)
        val candidate = weekStartEpochDay + offset * 7
        if (candidate > currentWeekStart) return
        weekStartEpochDay = candidate
        selectedDateEpochDay = if (candidate == currentWeekStart) today else candidate
        renderWeekHeader()
        refreshWeek()
    }

    private fun refreshWeek() {
        val profileId = activeProfileId
        renderWeekHeader()
        if (profileId == null) {
            weekMoods = emptyMap()
            weeklyNote = null
            renderWeekDays()
            renderSelectedDay()
            renderWeeklyNote()
            findViewById<TextView>(R.id.moodStatus).setText(R.string.diary_no_active_profile)
            return
        }
        val requestedWeek = weekStartEpochDay
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val moods = data.emotionalDiaryService.moodsForWeek(profileId, requestedWeek).first()
            val note = data.emotionalDiaryService.weeklyNote(profileId, requestedWeek)?.note
            if (activeProfileId != profileId || weekStartEpochDay != requestedWeek) return@launch
            weekMoods = moods.associateBy { it.dateEpochDay }
            weeklyNote = note
            renderWeekDays()
            renderSelectedDay()
            renderWeeklyNote()
        }
    }

    private fun renderWeekHeader() {
        val start = LocalDate.ofEpochDay(weekStartEpochDay)
        val end = start.plusDays(6)
        findViewById<TextView>(R.id.weekLabel).text =
            "${start.format(weekFormatter)} - ${end.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALIAN))}"
        val todayWeekStart = EmotionalDiaryRules.weekStartEpochDay(data.emotionalDiaryService.todayEpochDay)
        findViewById<View>(R.id.nextWeekButton).isEnabled = weekStartEpochDay < todayWeekStart
    }

    private fun renderWeekDays() {
        val container = findViewById<LinearLayout>(R.id.weekDays)
        container.removeAllViews()
        val today = data.emotionalDiaryService.todayEpochDay
        for (offset in 0L..6L) {
            val dateEpochDay = weekStartEpochDay + offset
            val date = LocalDate.ofEpochDay(dateEpochDay)
            val entry = weekMoods[dateEpochDay]
            val display = if (activeProfileId == null) {
                com.myfitai.app.domain.diary.MoodDisplay(mood = null, automatic = false)
            } else {
                EmotionalDiaryRules.displayFor(dateEpochDay, today, entry?.mood)
            }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                isClickable = dateEpochDay <= today
                isFocusable = dateEpochDay <= today
                minimumHeight = resources.getDimensionPixelSize(R.dimen.control_min_height)
                val inset = resources.getDimensionPixelSize(R.dimen.space_8)
                setPadding(inset, 0, inset, 0)
                isSelected = dateEpochDay == selectedDateEpochDay
                contentDescription = buildDayDescription(date, display)
                setOnClickListener {
                    if (dateEpochDay <= today) {
                        selectedDateEpochDay = dateEpochDay
                        renderWeekDays()
                        renderSelectedDay()
                    }
                }
            }
            val dateLabel = TextView(this).apply {
                text = date.format(shortDayFormatter).replaceFirstChar { it.titlecase(Locale.ITALIAN) }
                setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
                setTextColor(getColor(if (dateEpochDay == selectedDateEpochDay) R.color.accent_green else R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val moodLabel = TextView(this).apply {
                text = display.mood?.let { "${it.emoji} ${it.label}${if (display.automatic) " · automatico" else ""}" }
                    ?: getString(R.string.diary_no_entry)
                setTextAppearance(R.style.Text_MyFitAI_SettingsDescription)
                gravity = Gravity.END
                maxLines = 1
            }
            row.addView(dateLabel)
            row.addView(moodLabel)
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            if (offset < 6) {
                container.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.divider_thickness),
                ))
            }
        }
    }

    private fun buildDayDescription(date: LocalDate, display: com.myfitai.app.domain.diary.MoodDisplay): String {
        val day = date.format(dayFormatter).replaceFirstChar { it.titlecase(Locale.ITALIAN) }
        val mood = display.mood?.let { "${it.label}${if (display.automatic) ", automatico non confermato" else ""}" }
            ?: getString(R.string.diary_no_entry)
        return "$day, $mood"
    }

    private fun renderSelectedDay() {
        val date = LocalDate.ofEpochDay(selectedDateEpochDay)
        val entry = weekMoods[selectedDateEpochDay]
        val display = EmotionalDiaryRules.displayFor(selectedDateEpochDay, data.emotionalDiaryService.todayEpochDay, entry?.mood)
        findViewById<TextView>(R.id.selectedDayLabel).text =
            date.format(dayFormatter).replaceFirstChar { it.titlecase(Locale.ITALIAN) }
        findViewById<TextView>(R.id.moodStatus).text = when {
            entry != null && display.mood != null -> "Registrato: ${display.mood.emoji} ${display.mood.label}"
            display.automatic -> "Molto male · valore automatico, non confermato"
            else -> getString(R.string.diary_no_entry)
        }
        val options = findViewById<RadioGroup>(R.id.moodOptions)
        renderingMood = true
        options.clearCheck()
        display.mood?.takeIf { !display.automatic }?.let { options.check(moodButtonId(it)) }
        val dayIsAvailable = selectedDateEpochDay <= data.emotionalDiaryService.todayEpochDay && activeProfileId != null
        for (index in 0 until options.childCount) options.getChildAt(index).isEnabled = dayIsAvailable
        findViewById<View>(R.id.deleteMoodButton).visibility = if (entry != null) View.VISIBLE else View.GONE
        renderingMood = false
    }

    private fun renderWeeklyNote() {
        val input = findViewById<TextInputEditText>(R.id.weeklyNoteInput)
        val text = weeklyNote.orEmpty()
        if (input.text?.toString() != text) {
            input.setText(text)
            input.setSelection(input.text?.length ?: 0)
        }
        val enabled = activeProfileId != null
        input.isEnabled = enabled
        findViewById<View>(R.id.saveWeeklyNoteButton).isEnabled = enabled
        findViewById<View>(R.id.deleteWeeklyNoteButton).visibility = if (weeklyNote.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun deleteMood() {
        val profileId = activeProfileId ?: return
        lifecycleScope.launch {
            runCatching { data.emotionalDiaryService.deleteMood(profileId, selectedDateEpochDay) }
                .onSuccess { refreshWeek() }
                .onFailure { Toast.makeText(this@EmotionalDiaryActivity, "Eliminazione non riuscita", Toast.LENGTH_LONG).show() }
        }
    }

    private fun saveWeeklyNote() {
        val profileId = activeProfileId ?: return
        val input = findViewById<TextInputEditText>(R.id.weeklyNoteInput)
        val value = input.text?.toString().orEmpty()
        lifecycleScope.launch {
            runCatching { data.emotionalDiaryService.saveWeeklyNote(profileId, weekStartEpochDay, value) }
                .onSuccess {
                    Toast.makeText(
                        this@EmotionalDiaryActivity,
                        if (value.isBlank()) R.string.diary_note_deleted else R.string.diary_note_saved,
                        Toast.LENGTH_SHORT,
                    ).show()
                    refreshWeek()
                }
                .onFailure { Toast.makeText(this@EmotionalDiaryActivity, "Nota non salvata", Toast.LENGTH_LONG).show() }
        }
    }

    private fun deleteWeeklyNote() {
        val profileId = activeProfileId ?: return
        lifecycleScope.launch {
            runCatching { data.emotionalDiaryService.deleteWeeklyNote(profileId, weekStartEpochDay) }
                .onSuccess {
                    Toast.makeText(this@EmotionalDiaryActivity, R.string.diary_note_deleted, Toast.LENGTH_SHORT).show()
                    refreshWeek()
                }
                .onFailure { Toast.makeText(this@EmotionalDiaryActivity, "Nota non eliminata", Toast.LENGTH_LONG).show() }
        }
    }

    private fun moodButtonId(mood: MoodLevel): Int = when (mood) {
        MoodLevel.VERY_BAD -> R.id.moodVeryBad
        MoodLevel.BAD -> R.id.moodBad
        MoodLevel.NEUTRAL -> R.id.moodNeutral
        MoodLevel.GOOD -> R.id.moodGood
        MoodLevel.VERY_GOOD -> R.id.moodVeryGood
    }

    private companion object {
        const val STATE_WEEK_START = "emotional_diary_week_start"
        const val STATE_SELECTED_DATE = "emotional_diary_selected_date"
    }
}