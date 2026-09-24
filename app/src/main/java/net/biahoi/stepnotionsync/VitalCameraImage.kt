package net.biahoi.stepnotionsync

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.camera.core.ImageProxy
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Apply the shared CameraX viewport and rotation before using normalized guide coordinates. */
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

/** Rectify the column, then crop only to the explicitly positioned per-row guides. */
internal fun cropVitalRows(source: Bitmap, layout: VitalScanLayout): List<Bitmap> {
    require(layout.isValid())
    val points = layout.corners.map { VitalScanPoint(it.x * source.width, it.y * source.height) }
    fun distance(a: Int, b: Int) = hypot(points[a].x - points[b].x, points[a].y - points[b].y)
    val columnWidth = (distance(0, 1) + distance(3, 2)) / 2
    val columnHeight = (distance(0, 3) + distance(1, 2)) / 2
    val scale = minOf(1f, 1600f / max(columnWidth, columnHeight))
    val width = (columnWidth * scale).roundToInt()
    val height = (columnHeight * scale).roundToInt()
    require(width >= 64 && height >= 128) { "The selected display is too small." }
    val transform = Matrix()
    check(layout.toImageMatrix(source.width, source.height).invert(transform))
    transform.postScale(width.toFloat(), height.toFloat())
    val column = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val rows = mutableListOf<Bitmap>()
    try {
        val canvas = Canvas(column)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        for ((index, range) in layout.rowRanges(height).withIndex()) {
            val inset = layout.rowInsets[index]
            val left = (width * inset.left).toInt()
            val right = (width * inset.right).toInt()
            rows.add(Bitmap.createBitmap(column, left, range.first, right - left, range.count()))
        }
        return rows
    } catch (error: Exception) {
        rows.forEach { it.recycle() }
        throw error
    } finally {
        column.recycle()
    }
}

internal fun readVitalRowPixels(row: Bitmap): SevenSegmentNumberResult {
    val scale = minOf(1.0, 640.0 / max(row.width, row.height))
    val scaled = Bitmap.createScaledBitmap(row, max(1, (row.width * scale).toInt()),
        max(1, (row.height * scale).toInt()), true)
    try {
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return readSevenSegmentNumber(pixels, scaled.width, scaled.height)
    } finally {
        if (scaled !== row) scaled.recycle()
    }
}
