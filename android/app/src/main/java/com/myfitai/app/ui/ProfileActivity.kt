package com.myfitai.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.myfitai.app.notifications.NotificationPreferences
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.ui.widgets.SettingRowView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.Period

class ProfileActivity : BaseShellActivity() {
    private val data by lazy { AppDataContainer.get(this) }
    private var profiles: List<UserProfileEntity> = emptyList()
    private var currentProfile: UserProfileEntity? = null
    private var pendingCameraFile: File? = null

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importGalleryPhoto(uri)
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null) importCameraPhoto(file) else file?.delete()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        bindBack()
        bindSettingsNavigation()
        bindProfileActions()
        ensureProfileSelection()
        observeProfiles()
    }

    private fun bindSettingsNavigation() {
        val openEditor = { openReprofileWizard() }
        findViewById<SettingRowView>(R.id.rowPersonalData).setOnClickListener { openEditor() }
        findViewById<SettingRowView>(R.id.rowGoals).setOnClickListener { openEditor() }
        findViewById<SettingRowView>(R.id.rowFoodPreferences).setOnClickListener { openEditor() }
        findViewById<SettingRowView>(R.id.rowDaySchedule).setOnClickListener { openEditor() }
        findViewById<SettingRowView>(R.id.rowWorkouts).setOnClickListener { go(WorkoutsActivity::class.java) }
        updateWorkoutsRowVisibility()
        findViewById<SettingRowView>(R.id.rowNotifications).setOnClickListener { showNotificationSettings() }
        findViewById<SettingRowView>(R.id.rowExport).setOnClickListener { go(ExportActivity::class.java) }
    }

    private fun showNotificationSettings() {
        val content = layoutInflater.inflate(R.layout.dialog_notification_settings, null)
        val prefs = NotificationPreferences(this)
        val mealSwitch = content.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.mealRemindersSwitch)
        val reviewSwitch = content.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.weeklyReviewSwitch)
        val aiSwitch = content.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.aiBackgroundUpdatesSwitch)
        val leadInput = content.findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.mealLeadInput)
        val leadMinutes = resources.getIntArray(R.array.notification_lead_minutes)
        val leadLabels = resources.getStringArray(R.array.notification_lead_labels)
        val selectedLead = leadMinutes.indexOf(prefs.mealLeadMinutes).coerceAtLeast(0)

        mealSwitch.isChecked = prefs.mealRemindersEnabled
        reviewSwitch.isChecked = prefs.weeklyReviewEnabled
        aiSwitch.isChecked = prefs.aiBackgroundUpdatesEnabled
        leadInput.setAdapter(android.widget.ArrayAdapter(this, R.layout.item_dropdown_myfitai, leadLabels.toList()))
        leadInput.setText(leadLabels[selectedLead.coerceAtMost(leadLabels.lastIndex)], false)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notifications_dialog_title)
            .setView(content)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                prefs.mealRemindersEnabled = mealSwitch.isChecked
                prefs.weeklyReviewEnabled = reviewSwitch.isChecked
                prefs.aiBackgroundUpdatesEnabled = aiSwitch.isChecked
                prefs.mealLeadMinutes = leadMinutes.getOrElse(leadLabels.indexOf(leadInput.text.toString())) { prefs.mealLeadMinutes }
                lifecycleScope.launch {
                    runCatching { data.notificationScheduler.refresh() }
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateWorkoutsRowVisibility()
    }

    private fun updateWorkoutsRowVisibility() {
        val profileId = data.activeProfileStore.currentIdOrNull() ?: return
        findViewById<SettingRowView>(R.id.rowWorkouts).visibility =
            if (data.workoutPreferences.isEnabled(profileId)) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun bindProfileActions() {
        findViewById<TextView>(R.id.profileName).setOnClickListener { showProfileMenu(it) }
        findViewById<TextView>(R.id.profileStats).setOnClickListener { openReprofileWizard() }
        findViewById<TextView>(R.id.profileGoal).setOnClickListener { openReprofileWizard() }
        findViewById<ShapeableImageView>(R.id.profileAvatar).setOnClickListener { showPhotoMenu() }
    }

    private fun ensureProfileSelection() {
        lifecycleScope.launch {
            val storedId = data.activeProfileStore.currentIdOrNull()
            val stored = storedId?.let { data.userProfileRepository.get(it) }
            if (stored != null) return@launch

            val defaultId = data.activeProfileStore.defaultIdOrNull()
            val default = defaultId?.let { data.userProfileRepository.get(it) }
            val first = default ?: data.userProfileRepository.getFirst()
            if (first != null) {
                data.activeProfileStore.selectProfile(first.id, makeDefault = true)
                return@launch
            }

            startActivity(Intent(this@ProfileActivity, OnboardingWizardActivity::class.java).apply {
                putExtra(OnboardingWizardActivity.EXTRA_BOOTSTRAP, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }
    }

    private fun observeProfiles() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                data.userProfileRepository.profiles.collect { values ->
                    profiles = values
                    val activeId = data.activeProfileStore.currentIdOrNull()
                    currentProfile = values.firstOrNull { it.id == activeId } ?: values.firstOrNull()
                    currentProfile?.let {
                        if (activeId != it.id) data.activeProfileStore.selectProfile(it.id, makeDefault = true)
                        renderProfile(it)
                    }
                }
            }
        }
    }

    private fun openReprofileWizard() {
        currentProfile?.id?.let { id ->
            startActivity(Intent(this, OnboardingWizardActivity::class.java).putExtra(OnboardingWizardActivity.EXTRA_PROFILE_ID, id))
        }
    }

    private fun renderProfile(profile: UserProfileEntity) {
        findViewById<TextView>(R.id.profileName).text = "${profile.name}  ▾"
        findViewById<TextView>(R.id.profileStats).text = buildStats(profile)
        findViewById<TextView>(R.id.profileGoal).text = profile.goal?.let { "Obiettivo: $it" } ?: "Obiettivo non impostato"

        val avatar = findViewById<ShapeableImageView>(R.id.profileAvatar)
        val file = profile.photoPath?.let(::File)
        if (file?.exists() == true) avatar.setImageURI(Uri.fromFile(file)) else avatar.setImageResource(R.drawable.ic_profile_unknown)
    }

    private fun buildStats(profile: UserProfileEntity): String {
        val parts = mutableListOf<String>()
        profile.birthDateEpochDay?.let {
            val birth = LocalDate.ofEpochDay(it)
            parts += "${Period.between(birth, LocalDate.now()).years} anni"
        }
        profile.biologicalSex?.let { parts += it }
        profile.heightCm?.let { parts += "${formatNumber(it)} cm" }
        profile.currentWeightKg?.let { parts += "${formatNumber(it)} kg" }
        return parts.ifEmpty { listOf("Completa i dati del profilo") }.joinToString("  |  ")
    }

    private fun showProfileMenu(anchor: android.view.View) {
        val popup = PopupMenu(this, anchor)
        profiles.forEachIndexed { index, profile ->
            popup.menu.add(0, index + 1, index, if (profile.id == currentProfile?.id) "✓ ${profile.name}" else profile.name)
        }
        val addId = profiles.size + 1000
        popup.menu.add(0, addId, profiles.size + 1, "+ Aggiungi profilo")
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == addId) {
                startActivity(Intent(this, OnboardingWizardActivity::class.java).putExtra(OnboardingWizardActivity.EXTRA_NEW_PROFILE, true))
            } else {
                profiles.getOrNull(item.itemId - 1)?.let { profile ->
                    data.activeProfileStore.selectProfile(profile.id, makeDefault = true)
                    startActivity(Intent(this, TabHostActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    })
                    finish()
                }
            }
            true
        }
        popup.show()
    }

    private fun showPhotoMenu() {
        val profile = currentProfile ?: return
        val options = if (profile.photoPath.isNullOrBlank()) arrayOf("Scatta foto", "Scegli dalla galleria")
        else arrayOf("Scatta foto", "Scegli dalla galleria", "Rimuovi foto")
        MaterialAlertDialogBuilder(this)
            .setTitle("Foto profilo")
            .setNegativeButton("Annulla", null)
            .setItems(options) { dialog, which ->
                when (options[which]) {
                    "Scatta foto" -> launchCamera(profile.id)
                    "Scegli dalla galleria" -> galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    "Rimuovi foto" -> removePhoto(profile)
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun launchCamera(profileId: Long) {
        val (file, uri) = data.profilePhotoStore.createCameraTemp(profileId)
        pendingCameraFile = file
        cameraLauncher.launch(uri)
    }

    private fun importGalleryPhoto(uri: Uri) {
        val profile = currentProfile ?: return
        lifecycleScope.launch {
            val oldPath = profile.photoPath
            val newPath = withContext(Dispatchers.IO) { data.profilePhotoStore.importFromUri(profile.id, uri) }
            updatePhoto(profile, oldPath, newPath)
        }
    }

    private fun importCameraPhoto(file: File) {
        val profile = currentProfile ?: return
        lifecycleScope.launch {
            val oldPath = profile.photoPath
            val newPath = withContext(Dispatchers.IO) { data.profilePhotoStore.importFromFile(profile.id, file) }
            updatePhoto(profile, oldPath, newPath)
        }
    }

    private suspend fun updatePhoto(profile: UserProfileEntity, oldPath: String?, newPath: String) {
        data.userProfileRepository.update(profile.copy(photoPath = newPath, updatedAtEpochMillis = System.currentTimeMillis()))
        if (!oldPath.isNullOrBlank() && oldPath != newPath) withContext(Dispatchers.IO) { data.profilePhotoStore.delete(oldPath) }
    }

    private fun removePhoto(profile: UserProfileEntity) {
        lifecycleScope.launch {
            val oldPath = profile.photoPath
            data.userProfileRepository.update(profile.copy(photoPath = null, updatedAtEpochMillis = System.currentTimeMillis()))
            withContext(Dispatchers.IO) { data.profilePhotoStore.delete(oldPath) }
        }
    }

    private fun formatNumber(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(java.util.Locale.ITALIAN, "%.1f", value)
}
