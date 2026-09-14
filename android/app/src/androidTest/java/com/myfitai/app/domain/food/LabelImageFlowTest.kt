package com.myfitai.app.domain.food

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.myfitai.app.ai.AiImageInput
import java.io.File
import java.io.FileOutputStream
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabelImageFlowTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        files.forEach { it.delete() }
    }

    @Test
    fun validLabel_isConvertedToInMemoryJpegPayload() {
        val source = tempImage(2400, 1200)
        val payload = LabelImageProcessor.fromFile(source)
        LabelImageTempStore(context).delete(source)

        assertEqualsJpeg(payload)
        assertTrue(payload.base64Data.isNotBlank())
        assertFalse(source.exists())
    }

    @Test
    fun unreadableLabel_isRejectedWithoutPayload() {
        val source = File.createTempFile("invalid-label-", ".jpg", context.cacheDir)
        files += source
        source.writeText("not an image")

        val result = runCatching { LabelImageProcessor.fromFile(source) }

        assertTrue(result.isFailure)
    }

    @Test
    fun tempStore_deletesOnlyOwnedCameraFiles() {
        val store = LabelImageTempStore(context)
        val owned = store.create()
        val external = File.createTempFile("external-label-", ".jpg", context.cacheDir)
        files += external
        assertTrue(owned.exists())
        assertTrue(external.exists())

        store.delete(owned)
        store.delete(external)

        assertFalse(owned.exists())
        assertTrue(external.exists())
    }

    private fun tempImage(width: Int, height: Int): File {
        val file = LabelImageTempStore(context).create()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) }
        bitmap.recycle()
        return file
    }

    private fun assertEqualsJpeg(payload: AiImageInput) {
        assertNotNull(payload)
        assertTrue(payload.mimeType == "image/jpeg")
        assertTrue(payload.base64Data.length > 100)
        // Android's Base64 decoder confirms the payload is binary image data, not a path.
        assertTrue(android.util.Base64.decode(payload.base64Data, android.util.Base64.NO_WRAP).size > 100)
    }
}
