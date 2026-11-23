package com.myfitai.app.data.profile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.myfitai.app.domain.profile.AnamnesisDocument
import java.io.File
import java.io.IOException
import java.util.UUID

/** Keeps selected medical documents in app-private storage; no URI or bytes leave the device. */
class ProfileAnamnesisDocumentStore(context: Context) {
    private val appContext = context.applicationContext

    fun copyFrom(profileId: Long, uri: Uri): AnamnesisDocument {
        require(profileId > 0) { "Profilo non valido" }
        val mimeType = normalizedMimeType(appContext.contentResolver.getType(uri))
            ?: throw IllegalArgumentException("Formato non supportato. Seleziona un PDF, PNG o JPEG.")
        val displayName = displayName(uri)
        val id = UUID.randomUUID().toString()
        val directory = profileDirectory(profileId).apply { mkdirs() }
        val destination = File(directory, id)
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Documento non leggibile")
            input.use { source ->
                destination.outputStream().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_DOCUMENT_BYTES) { "Ogni documento deve essere inferiore a 15 MB" }
                        target.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
        return AnamnesisDocument(id, displayName, mimeType)
    }

    fun deleteExcept(profileId: Long, retainedIds: Set<String>) {
        if (profileId <= 0) return
        profileDirectory(profileId).listFiles()?.forEach { file ->
            if (file.name !in retainedIds) file.delete()
        }
    }

    fun deleteProfile(profileId: Long) {
        if (profileId > 0) profileDirectory(profileId).deleteRecursively()
    }

    private fun profileDirectory(profileId: Long) = File(File(appContext.filesDir, DIRECTORY_NAME), profileId.toString())

    private fun displayName(uri: Uri): String {
        val queried = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        val raw = queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Documento"
        return raw.replace('\\', '/').substringAfterLast('/').take(255).ifBlank { "Documento" }
    }

    private fun normalizedMimeType(mimeType: String?): String? = when (mimeType?.lowercase()) {
        "application/pdf" -> "application/pdf"
        "image/jpeg", "image/jpg" -> "image/jpeg"
        "image/png" -> "image/png"
        else -> null
    }

    private companion object {
        const val DIRECTORY_NAME = "profile_anamnesis_documents"
        const val MAX_DOCUMENT_BYTES = 15L * 1024L * 1024L
    }
}