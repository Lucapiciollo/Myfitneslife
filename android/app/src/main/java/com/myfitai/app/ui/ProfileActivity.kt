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
import com.myfitai.app.R
import com.myfitai.app.data.AppDataContainer
import com.myfitai.app.data.local.entity.UserProfileEntity
import com.myfitai.app.security.AiCredentialProvider
import com.myfitai.app.security.SecureAiCredentialStore
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
        val openEditor = { go(ProfileEditActivity::class.java) }
        findViewById<SettingRowView>(R.id.rowPersonalData).setOnClickListener { openEditor() }
        findViewById<SettingRowView>(R.id.rowGoals).setOnClickListener { openEditor() }
        findViewById<SettingRowView>(R.id.rowFoodPreferences).setOnClickListener { openEditor() }
        findViewById<SettingRowView>(R.id.rowDaySchedule).setOnClickListener { openEditor() }
        findViewById<SettingRowView>(R.id.rowWorkouts).setOnClickListener { go(WorkoutsActivity::class.java) }
        findViewById<SettingRowView>(R.id.rowNotifications).setOnClickListener { go(NotificationsActivity::class.java) }
        findViewById<SettingRowView>(R.id.rowExport).setOnClickListener { go(ExportActivity::class.java) }
        findViewById<SettingRowView>(R.id.rowSettings).setOnClickListener { go(SettingsActivity::class.java) }

        val openAiRow = findViewById<SettingRowView>(R.id.rowOpenAiKey)
        openAiRow.setOnClickListener { go(SettingsActivity::class.java) }
        val hasKey = SecureAiCredentialStore(this).exists(AiCredentialProvider.OPENAI)
        openAiRow.setTrailingBadge(
            getString(if (hasKey) R.string.profile_openai_configured else R.string.profile_openai_not_configured),
            if (hasKey) R.color.accent_green else R.color.text_muted,
        )
    }

    private fun bindProfileActions() {
        findViewById<TextView>(R.id.profileName).setOnClickListener { showProfileMenu(it) }
        findViewById<TextView>(R.id.profileStats).setOnClickListener { go(ProfileEditActivity::class.java) }
        findViewById<TextView>(R.id.profileGoal).setOnClickListener { go(ProfileEditActivity::class.java) }
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

            startActivity(Intent(this@ProfileActivity, ProfileEditActivity::class.java).apply {
                putExtra(ProfileEditActivity.EXTRA_BOOTSTRAP, true)
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
                startActivity(Intent(this, ProfileEditActivity::class.java).putExtra(ProfileEditActivity.EXTRA_CREATE, true))
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
        val options = if (profile.photoPath.isNullOrBlank()) arrayOf("Scatta foto", "Scegli dalla galleria", "Annulla")
        else arrayOf("Scatta foto", "Scegli dalla galleria", "Rimuovi foto", "Annulla")
        MaterialAlertDialogBuilder(this)
            .setTitle("Foto profilo")
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
