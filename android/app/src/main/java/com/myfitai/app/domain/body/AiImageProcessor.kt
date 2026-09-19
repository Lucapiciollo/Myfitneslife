package com.myfitai.app.domain.body

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.myfitai.app.ai.AiImageInput
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

object AiImageProcessor {
    fun fromUri(context: Context, uri: Uri): AiImageInput = context.contentResolver.openInputStream(uri)?.use { fromBytes(it.readBytes()) }
        ?: error("Impossibile leggere l'immagine")
    fun fromFile(file: File): AiImageInput = fromBytes(file.readBytes())

    private fun fromBytes(bytes: ByteArray): AiImageInput {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Formato immagine non supportato")
        val largest = max(bitmap.width, bitmap.height)
        val scaled = if (largest > 1600) {
            val ratio = 1600f / largest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        } else bitmap
        val output = ByteArrayOutputStream()
        return try {
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 86, output)) { "Impossibile comprimere l'immagine" }
            AiImageInput("image/jpeg", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            output.close()
        }
    }
}
