package com.myfitai.app.domain.food

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.myfitai.app.ai.AiImageInput
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/**
 * Prepares a food or nutrition-label photo for the AI provider without persisting it.
 * L'immagine viene ridimensionata/compressa in memoria e trasformata in base64.
 */
object CheatPhotoProcessor {
    // Vision-token usage grows with image resolution; retain readable detail at a smaller payload.
    private const val MAX_SIDE = 1024
    private const val JPEG_QUALITY = 70

    fun fromUri(context: Context, uri: Uri): AiImageInput {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Impossibile leggere la foto")
        return fromBytes(bytes)
    }

    fun fromFile(file: File): AiImageInput = fromBytes(file.readBytes())

    private fun fromBytes(bytes: ByteArray): AiImageInput {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Formato immagine non supportato")
        val scaled = scaleDown(bitmap)
        val output = ByteArrayOutputStream()
        try {
            check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Impossibile preparare la foto"
            }
            return AiImageInput(
                mimeType = "image/jpeg",
                base64Data = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
            )
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            output.close()
        }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val largest = max(source.width, source.height)
        if (largest <= MAX_SIDE) return source
        val ratio = MAX_SIDE.toFloat() / largest.toFloat()
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
