package com.myfitai.app.domain.food

import android.content.Context
import java.io.File

/** Owns temporary camera-photo files; the AI payload remains in memory. */
class CheatPhotoTempStore(context: Context) {
    private val cacheDir = File(context.applicationContext.cacheDir, "cheat-photos").apply { mkdirs() }

    fun create(): File = File.createTempFile("photo-", ".jpg", cacheDir)

    fun delete(file: File?) {
        if (file == null) return
        if (file.parentFile?.canonicalFile == cacheDir.canonicalFile) file.delete()
    }
}
