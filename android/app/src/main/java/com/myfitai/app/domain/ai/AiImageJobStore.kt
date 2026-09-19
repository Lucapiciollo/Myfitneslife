package com.myfitai.app.domain.ai

import android.content.Context
import com.myfitai.app.ai.AiImageInput
import java.io.File
import java.util.UUID

class AiImageJobStore(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "ai-job-images").apply { mkdirs() }
    fun write(image: AiImageInput): String {
        val file = File(directory, "${UUID.randomUUID()}.payload")
        file.writeText("${image.mimeType}\n${image.base64Data}", Charsets.UTF_8)
        return file.absolutePath
    }
    fun read(path: String): AiImageInput {
        val file = File(path).canonicalFile
        require(file.parentFile?.canonicalFile == directory.canonicalFile) { "Invalid AI image path" }
        val lines = file.readLines(Charsets.UTF_8)
        require(lines.size >= 2 && lines[0].isNotBlank() && lines[1].isNotBlank()) { "Invalid AI image payload" }
        return AiImageInput(lines[0], lines.drop(1).joinToString(""))
    }
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).canonicalFile.takeIf { it.parentFile?.canonicalFile == directory.canonicalFile }?.delete() }
    }
}
