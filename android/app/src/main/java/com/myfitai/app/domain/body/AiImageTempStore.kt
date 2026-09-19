package com.myfitai.app.domain.body

import android.content.Context
import java.io.File

class AiImageTempStore(context: Context) {
    private val dir = File(context.applicationContext.cacheDir, "ai-imports").apply { mkdirs() }
    fun create(): File = File.createTempFile("bia-", ".jpg", dir)
    fun delete(file: File?) { if (file?.parentFile?.canonicalFile == dir.canonicalFile) file.delete() }
}
