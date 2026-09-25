package net.biahoi.stepnotionsync

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import kotlin.math.max

/** Keep the captured image aligned with the preview, including display rotation. */
internal fun capturedVitalBitmap(image: ImageProxy): Bitmap {
    val source = image.toBitmap()
    try {
        val crop = image.cropRect
        val rotation = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(source, crop.left, crop.top, crop.width(), crop.height(), rotation, true)
            .also { if (it !== source) source.recycle() }
    } catch (error: Exception) {
        source.recycle()
        throw error
    }
}

/** Bound CPU/memory while retaining all pixels in the selected image. */
internal fun vitalRecognitionBitmap(source: Bitmap, maximumSize: Int): Bitmap {
    val scale = minOf(1.0, maximumSize.toDouble() / max(source.width, source.height))
    return Bitmap.createScaledBitmap(source, max(1, (source.width * scale).toInt()),
        max(1, (source.height * scale).toInt()), true)
}

internal fun readVitalRowPixels(row: Bitmap): SevenSegmentNumberResult {
    val scaled = vitalRecognitionBitmap(row, 640)
    try {
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return readSevenSegmentNumber(pixels, scaled.width, scaled.height)
    } finally {
        if (scaled !== row) scaled.recycle()
    }
}

internal fun analyzeVitalImagePixels(source: Bitmap, elements: List<VitalOcrElement>): AutomaticVitalPixels {
    val scaled = vitalRecognitionBitmap(source, 960)
    try {
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val scaleX = scaled.width.toDouble() / source.width
        val scaleY = scaled.height.toDouble() / source.height
        val analysis = readAutomaticVitalPixels(pixels, scaled.width, scaled.height, elements.map {
            it.copy(left = (it.left * scaleX).toInt(), right = (it.right * scaleX).toInt(),
                top = (it.top * scaleY).toInt(), bottom = (it.bottom * scaleY).toInt())
        })
        val display = analysis.display.copy(rows = analysis.display.rows.map {
            it.copy(left = (it.left / scaleX).toInt(), right = (it.right / scaleX).toInt(),
                top = (it.top / scaleY).toInt(), bottom = (it.bottom / scaleY).toInt())
        })
        val content = analysis.content
        return AutomaticVitalPixels(display, VitalImageRegion((content.left / scaleX).toInt(), 0,
            (content.right / scaleX).toInt().coerceAtMost(source.width), source.height))
    } finally {
        if (scaled !== source) scaled.recycle()
    }
}
