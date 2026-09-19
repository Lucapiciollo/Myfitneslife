package com.myfitai.app.domain.food

import android.content.Context
import java.io.File

/** Owns camera-label temp files; the payload itself remains an in-memory AiImageInput. */
class LabelImageTempStore(context: Context) {
    private val cacheDir = File(context.applicationContext.cacheDir, "cheat-labels").apply { mkdirs() }

    fun create(): File = File.createTempFile("label-", ".jpg", cacheDir)

    fun delete(file: File?) {
        if (file == null) return
        if (file.parentFile?.canonicalFile == cacheDir.canonicalFile) file.delete()
    }
}
