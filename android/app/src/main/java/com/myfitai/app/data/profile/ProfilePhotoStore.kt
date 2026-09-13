package com.myfitai.app.data.profile

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Gestisce le foto profilo nello storage privato dell'app.
 * Room conserva solo il path; nessun BLOB immagine viene scritto nel database.
 */
class ProfilePhotoStore(private val context: Context) {
    private val appContext = context.applicationContext
    private val profileDir: File
        get() = File(appContext.filesDir, "profile_photos").apply { mkdirs() }

    fun createCameraTemp(profileId: Long): Pair<File, Uri> {
        val file = File(appContext.cacheDir, "profile_camera_${profileId}_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        return file to uri
    }

    fun importFromUri(profileId: Long, source: Uri): String {
        val target = targetFile(profileId)
        appContext.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Unable to open selected image" }
            FileOutputStream(target, false).use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    fun importFromFile(profileId: Long, source: File): String {
        val target = targetFile(profileId)
        source.inputStream().use { input ->
            FileOutputStream(target, false).use { output -> input.copyTo(output) }
        }
        source.delete()
        return target.absolutePath
    }

    fun delete(photoPath: String?) {
        if (photoPath.isNullOrBlank()) return
        val file = File(photoPath)
        if (file.parentFile?.canonicalFile == profileDir.canonicalFile) file.delete()
    }

    private fun targetFile(profileId: Long): File = File(profileDir, "profile_$profileId.jpg")
}
