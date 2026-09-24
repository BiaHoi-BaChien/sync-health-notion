package net.biahoi.stepnotionsync

import kotlin.math.max
import kotlin.math.min

internal sealed interface SevenSegmentVitalResult {
    data class Recognized(val reading: VitalCameraReading) : SevenSegmentVitalResult
    data object NotDetected : SevenSegmentVitalResult
    data object Uncertain : SevenSegmentVitalResult
}

internal sealed interface SevenSegmentNumberResult {
    data class Recognized(val value: Int) : SevenSegmentNumberResult
    data object NotDetected : SevenSegmentNumberResult
    data object Uncertain : SevenSegmentNumberResult
}

private sealed interface SegmentReadResult {
    data class Recognized(val values: List<Int>) : SegmentReadResult
    data object NotDetected : SegmentReadResult
    data object Uncertain : SegmentReadResult
}

/** On-device fallback for a framed column of three dark seven-segment numbers. */
internal fun readSevenSegmentVitals(pixels: IntArray, width: Int, height: Int): SevenSegmentVitalResult {
    return when (val result = readSevenSegmentNumbers(pixels, width, height, 3)) {
        is SegmentReadResult.Recognized -> vitalReadingFromNumbers(result.values)?.let { SevenSegmentVitalResult.Recognized(it) }
            ?: SevenSegmentVitalResult.Uncertain
        SegmentReadResult.NotDetected -> SevenSegmentVitalResult.NotDetected
        SegmentReadResult.Uncertain -> SevenSegmentVitalResult.Uncertain
    }
}

/** The entire row is retained: never crop away an unrecognized leading stroke. */
internal fun readSevenSegmentNumber(pixels: IntArray, width: Int, height: Int): SevenSegmentNumberResult =
    when (val result = readSevenSegmentNumbers(pixels, width, height, 1)) {
        is SegmentReadResult.Recognized -> SevenSegmentNumberResult.Recognized(result.values.single())
        SegmentReadResult.NotDetected -> SevenSegmentNumberResult.NotDetected
        SegmentReadResult.Uncertain -> SevenSegmentNumberResult.Uncertain
    }

private fun readSevenSegmentNumbers(pixels: IntArray, width: Int, height: Int, expectedRows: Int): SegmentReadResult {
    if (width !in 32..960 || height !in 32..960 || pixels.size != width * height) return SegmentReadResult.NotDetected
    val gray = IntArray(pixels.size) { index ->
        val color = pixels[index]
        (77 * (color shr 16 and 255) + 150 * (color shr 8 and 255) + 29 * (color and 255)) shr 8
    }
    val histogram = IntArray(256)
    gray.forEach { histogram[it]++ }
    fun percentile(fraction: Double): Int {
        var count = 0
        return histogram.indices.first { count += histogram[it]; count >= gray.size * fraction }
    }
    // A single 1 or 7 occupies much less of a row than three values do of a column.
    val dark = percentile(if (expectedRows == 1) 0.01 else 0.05)
    val background = percentile(0.70)
    if (background - dark < 30) return SegmentReadResult.NotDetected
    val localGray = if (expectedRows == 1) normalizeRowLighting(gray, width, height) else gray
    val stride = width + 1
    val integral = IntArray(stride * (height + 1))
    for (y in 0 until height) {
        var sum = 0
        for (x in 0 until width) {
            sum += localGray[y * width + x]
            integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + sum
        }
    }
    val contrast = IntArray(gray.size)
    val radius = max(8, if (expectedRows == 1) max(width, height) / 3 else height / 12)
    for (y in 0 until height) for (x in 0 until width) {
        val x0 = max(0, x - radius)
        val x1 = min(width, x + radius + 1)
        val y0 = max(0, y - radius)
        val y1 = min(height, y + radius + 1)
        val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] - integral[y1 * stride + x0] + integral[y0 * stride + x0]
        contrast[y * width + x] = sum / ((x1 - x0) * (y1 - y0)) - localGray[y * width + x]
    }
    val thresholds = listOf(0.14, 0.17, 0.20, 0.23, 0.26)
        .map { max(6, ((background - dark) * it).toInt()) }.distinct()
    // The local mean can hide a faint stroke next to darker digits. Keep independent
    // stroke evidence from the row background before that mean is subtracted.
    val strokeEvidence = if (expectedRows == 1) rowStrokeEvidence(localGray, width, height, thresholds.first()) else emptyList()
    val result = readSegmentContrasts(contrast, width, height, thresholds, closeRows = false, expectedRows, strokeEvidence)
    // Never override a complete reading or uncertainty from the original detector.
    if (result != SegmentReadResult.NotDetected) return result
    if (expectedRows == 1) return readSegmentContrasts(horizontalSegmentContrast(gray, width, height),
        width, height, thresholds, closeRows = false, expectedRows, strokeEvidence)
    return readSegmentContrasts(horizontalSegmentContrast(gray, width, height), width, height,
        (thresholds.first()..thresholds.last()).toList(), closeRows = true, expectedRows)
}

/** A horizontal background estimate excludes bright casing above/below the LCD. */
private fun horizontalSegmentContrast(gray: IntArray, width: Int, height: Int): IntArray {
    val contrast = IntArray(gray.size)
    val radius = max(8, width / 6)
    for (y in 0 until height) {
        val histogram = IntArray(256)
        var left = 0
        var right = 0
        for (x in 0 until width) {
            val x0 = max(0, x - radius)
            val x1 = min(width, x + radius + 1)
            while (left < x0) histogram[gray[y * width + left++]]--
            while (right < x1) histogram[gray[y * width + right++]]++
            var count = 0
            // The mean is pulled down by adjacent dark segments, hiding a faint stroke.
            val background = histogram.indices.first { count += histogram[it]; count >= (x1 - x0) * 0.75 }
            contrast[y * width + x] = background - gray[y * width + x]
        }
    }
    return contrast
}

private fun readSegmentContrasts(
    contrast: IntArray, width: Int, height: Int, thresholds: List<Int>, closeRows: Boolean, expectedRows: Int,
    strokeEvidence: List<SegmentBox> = emptyList()
): SegmentReadResult {
    val readings = mutableListOf<List<Int>>()
    for (threshold in thresholds) {
        val mask = BooleanArray(contrast.size) { contrast[it] >= threshold }
        // Keep the ink: erasing a frame's bounds can erase a digit attached to it as well.
        val edgeComponents = segmentComponents(mask, width, height).filter { it.left == 0 || it.right == width }
        // A guide boundary through a digit must not leave a plausible shortened number.
        if (expectedRows == 1 && touchesNumberBoundary(mask, width, height)) return SegmentReadResult.Uncertain
        val boxes = projectedDigitBoxes(mask, width, height, closeRows, edgeComponents, expectedRows) ?: return SegmentReadResult.Uncertain
        when (val result = readSegmentRows(mask, width, boxes, expectedRows)) {
            is SegmentReadResult.Recognized -> {
                if (hasUnresolvedRowStroke(strokeEvidence, boxes)) return SegmentReadResult.Uncertain
                readings.add(result.values)
            }
            SegmentReadResult.Uncertain -> return result
            SegmentReadResult.NotDetected -> Unit
        }
    }
    // Multiple contrast levels must agree; never choose between conflicting complete readings.
    return when {
        readings.distinct().size > 1 -> SegmentReadResult.Uncertain
        readings.size >= 2 -> SegmentReadResult.Recognized(readings.first())
        readings.isNotEmpty() -> SegmentReadResult.Uncertain
        else -> SegmentReadResult.NotDetected
    }
}

/** Dense vertical strokes can be a faint leading/trailing digit, even when not decoded. */
private fun rowStrokeEvidence(normalized: IntArray, width: Int, height: Int, threshold: Int): List<SegmentBox> {
    val mask = BooleanArray(normalized.size) { -normalized[it] >= threshold }
    return segmentComponents(mask, width, height).filter { box ->
        if (box.height < 4 || box.height < box.width * 1.5) return@filter false
        var ink = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (mask[y * width + x]) ink++
        ink >= box.width * box.height * 0.45
    }
}

private fun hasUnresolvedRowStroke(evidence: List<SegmentBox>, digits: List<SegmentBox>): Boolean {
    if (evidence.isEmpty() || digits.isEmpty()) return false
    val top = digits.minOf { it.top }
    val bottom = digits.maxOf { it.bottom }
    val height = bottom - top
    val left = digits.minOf { it.left }
    val right = digits.maxOf { it.right }
    val margin = max(2, height / 20)
    return evidence.any { stroke ->
        stroke.height >= max(4, height / 5) &&
            stroke.top >= top - margin && stroke.bottom <= bottom + margin &&
            stroke.right >= left - height * 0.95 && stroke.left <= right + height * 0.95 &&
            digits.none { stroke.right > it.left - margin && stroke.left < it.right + margin }
    }
}

/** Remove horizontal lighting bands before the local mean, without erasing border pixels. */
private fun normalizeRowLighting(gray: IntArray, width: Int, height: Int): IntArray {
    val normalized = IntArray(gray.size)
    for (y in 0 until height) {
        val histogram = IntArray(256)
        for (x in 0 until width) histogram[gray[y * width + x]]++
        var count = 0
        val background = histogram.indices.first { count += histogram[it]; count >= width * 0.75 }
        for (x in 0 until width) normalized[y * width + x] = gray[y * width + x] - background
    }
    return normalized
}

private fun touchesNumberBoundary(mask: BooleanArray, width: Int, height: Int): Boolean {
    val margin = max(2, min(width, height) / 100)
    // Check a small inner margin too, so resampling does not hide a clipped edge.
    return segmentComponents(mask, width, height).any {
        (it.left < margin || it.top < margin || it.right > width - margin || it.bottom > height - margin) &&
            max(it.width, it.height) >= max(4, height / 12)
    }
}

/** Null means conflicting digit evidence was found; it must not be dropped as noise. */
private fun projectedDigitBoxes(
    mask: BooleanArray, width: Int, height: Int, closeRows: Boolean, edgeComponents: List<SegmentBox>, expectedRows: Int
): List<SegmentBox>? {
    // The legacy spacing rules describe a whole column. Preserve that physical scale
    // for a single row so the gap between a 1/7's upper and lower strokes is joined.
    val layoutHeight = height * if (expectedRows == 1) 4 else 1
    fun spans(values: IntArray, minimum: Int, gap: Int): List<IntRange> {
        val active = values.indices.filter { values[it] >= minimum }
        if (active.isEmpty()) return emptyList()
        val result = mutableListOf<IntRange>()
        var start = active.first()
        var previous = start
        for (position in active.drop(1)) {
            if (position - previous > gap + 1) { result.add(start..previous); start = position }
            previous = position
        }
        result.add(start..previous)
        return result
    }
    val horizontal = IntArray(height) { y -> (0 until width).count { x -> mask[y * width + x] } }
    val minimum = if (closeRows) max(8, min((width * 0.075).toInt(), (horizontal.max() * 0.25).toInt()))
        else max(8, (horizontal.max() * 0.16).toInt())
    val rows = spans(horizontal, minimum, max(2, layoutHeight / (if (closeRows) 120 else 42)))
        .filter { it.last - it.first >= layoutHeight / (if (closeRows) 30 else 12) }.toMutableList()
    val tallest = rows.maxOfOrNull { it.count() } ?: return emptyList()
    while (closeRows || rows.size > expectedRows) {
        val joins = (0 until rows.lastIndex).filter { index ->
            rows[index + 1].first - rows[index].last < tallest * (if (closeRows) 0.25 else 0.15) &&
                rows[index + 1].last - rows[index].first < tallest * (if (closeRows) 1.30 else 1.10)
        }
        if (closeRows && joins.isEmpty()) break
        val ambiguousJoin = if (closeRows) joins.zipWithNext().any { (a, b) -> b == a + 1 } else joins.size != 1
        if (ambiguousJoin) {
            if (rows.size <= expectedRows) return emptyList()
            break
        }
        val index = joins.first()
        rows[index] = rows[index].first..rows[index + 1].last
        rows.removeAt(index + 1)
    }
    // Join a small pulse's separated upper/lower strokes before removing short noise rows.
    if (closeRows) rows.removeAll { it.count() < layoutHeight / 12 }
    if (rows.size > expectedRows) {
        // Casing shadows can resemble an extra row. Require complete digit evidence
        // before blocking OCR, without applying partial-digit guards to that noise.
        val boxes = rows.flatMap { row ->
            val vertical = IntArray(width) { x -> row.count { y -> mask[y * width + x] } }
            spans(vertical, max(3, (row.count() * 0.06).toInt()), 3).map { column ->
                SegmentBox(column.first, row.first, column.last + 1, row.last + 1)
            }
        }
        return if (readSegmentRows(mask, width, boxes, expectedRows) == SegmentReadResult.Uncertain) null else emptyList()
    }
    if (rows.size != expectedRows) return emptyList()
    return rows.flatMap { row ->
        val vertical = IntArray(width) { x -> row.count { y -> mask[y * width + x] } }
        val columns = spans(vertical, max(3, (row.count() * 0.06).toInt()), 3)
        val minimumWidth = max(3, row.count() / 12)
        // A stroke attached to a frame widens the edge abruptly and persists vertically.
        // Detect it before a wide column can be discarded as a continuous frame line.
        for (edge in listOf(0, width - 1)) {
            val direction = if (edge == 0) 1 else -1
            var previous = 0
            for (y in row) {
                var extent = 0
                while (extent < width && mask[y * width + edge + extent * direction]) extent++
                if (previous > 0 && extent - previous >= 3) {
                    val x = edge + previous * direction
                    var end = y
                    while (end <= row.last && mask[end * width + x]) end++
                    if (end - y >= minimumWidth && end - y < row.count() * 0.65) return null
                }
                previous = extent
            }
        }
        // Check connected edge strokes before projection can discard them as narrow noise.
        for (component in edgeComponents) {
            if (component.width >= minimumWidth || component.height < max(3, row.count() / 20) ||
                component.height < component.width * 2 ||
                component.bottom <= row.first || component.top > row.last) continue
            if (columns.any { it.count() >= minimumWidth && component.left <= it.last && component.right > it.first }) continue
            for (x in component.left until component.right) {
                var run = 0
                for (y in max(row.first, component.top) until min(row.last + 1, component.bottom)) {
                    run = if (mask[y * width + x]) run + 1 else 0
                    if (run >= max(3, row.count() / 20)) return null
                }
            }
        }
        columns.mapNotNull { column ->
            if (column.count() < minimumWidth) return@mapNotNull null
            val inkRows = row.filter { y -> column.any { x -> mask[y * width + x] } }
            if (inkRows.isEmpty()) return@mapNotNull null
            val inkHeight = inkRows.last() - inkRows.first() + 1
            if (inkHeight < row.count() * 0.65) {
                val ink = inkRows.sumOf { y -> column.count { x -> mask[y * width + x] } }
                // A dense partial stroke may be a cut-off digit; sparse scratches are still noise.
                if (ink >= column.count() * inkHeight * 0.45) return null
                return@mapNotNull null
            }
            SegmentBox(column.first, row.first, column.last + 1, row.last + 1)
        }.filter { box ->
            val center = (box.left + box.right) / 2
            val middle = (box.top + box.height * 0.45).toInt() until (box.top + box.height * 0.55).toInt()
            !(box.width < box.height * 0.20 && middle.count { mask[it * width + center] } > middle.count() * 0.7)
        }
    }
}

private data class SegmentBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerY get() = (top + bottom) / 2
}

private fun readSegmentRows(mask: BooleanArray, width: Int, boxes: List<SegmentBox>, expectedRows: Int): SegmentReadResult {
    if (boxes.size < expectedRows) return SegmentReadResult.NotDetected
    val rows = mutableListOf<MutableList<SegmentBox>>()
    for (box in boxes.sortedBy { it.centerY }) {
        val row = rows.lastOrNull()
        if (row != null && box.centerY - row.first().centerY < min(box.height, row.first().height) / 2) row.add(box)
        else rows.add(mutableListOf(box))
    }
    if (rows.size < expectedRows) return SegmentReadResult.NotDetected
    val elements = rows.map { row ->
        val ordered = row.sortedBy { it.left }
        if (row.maxOf { it.height } > row.minOf { it.height } * 1.35) return SegmentReadResult.NotDetected
        if (ordered.zipWithNext().any { (a, b) -> b.left < a.right || b.left - a.right > max(a.height, b.height) * 0.95 }) return SegmentReadResult.NotDetected
        val digits = ordered.map { readSegmentDigit(mask, width, it) ?: return SegmentReadResult.NotDetected }.joinToString("")
        VitalOcrElement(digits, row.minOf { it.left }, row.minOf { it.top }, row.maxOf { it.right }, row.maxOf { it.bottom })
    }
    // Fully decoded but invalid rows/values contradict OCR; they are not a missed detection.
    if (expectedRows == 3) return parseVitalCameraReading(elements)?.let {
        SegmentReadResult.Recognized(listOf(it.systolic, it.diastolic, it.heartRate))
    } ?: SegmentReadResult.Uncertain
    return parseVitalCameraNumber(elements)?.let { SegmentReadResult.Recognized(listOf(it)) }
        ?: SegmentReadResult.Uncertain
}

private fun readSegmentDigit(mask: BooleanArray, width: Int, box: SegmentBox): Int? {
    fun fill(left: Double, top: Double, right: Double, bottom: Double): Double {
        val x0 = box.left + (box.width * left).toInt()
        val x1 = box.left + (box.width * right).toInt()
        val y0 = box.top + (box.height * top).toInt()
        val y1 = box.top + (box.height * bottom).toInt()
        if (x1 <= x0 || y1 <= y0) return 0.0
        var count = 0
        for (y in y0 until y1) for (x in x0 until x1) if (mask[y * width + x]) count++
        return count.toDouble() / ((x1 - x0) * (y1 - y0))
    }
    if (box.width < box.height * 0.38) {
        val upper = max(fill(0.0, 0.15, 0.6, 0.38), fill(0.4, 0.15, 1.0, 0.38))
        val lower = max(fill(0.0, 0.62, 0.6, 0.85), fill(0.4, 0.62, 1.0, 0.85))
        return if (upper > 0.45 && lower > 0.45 && fill(0.05, 0.48, 0.95, 0.56) < 0.70) 1 else null
    }
    if (box.width > box.height * 0.9) return null
    val segments = listOf(
        fill(0.32, 0.02, 0.70, 0.14), // top
        max(fill(0.68, 0.18, 0.88, 0.40), fill(0.80, 0.18, 1.0, 0.40)), // upper right
        max(fill(0.60, 0.60, 0.80, 0.84), fill(0.72, 0.60, 0.92, 0.84)), // lower right
        fill(0.20, 0.87, 0.50, 0.99), // bottom, clear of the slanted lower-right stroke
        max(fill(0.0, 0.60, 0.20, 0.84), fill(0.12, 0.60, 0.32, 0.84)), // lower left
        max(fill(0.02, 0.18, 0.22, 0.40), fill(0.14, 0.18, 0.34, 0.40)), // upper left
        fill(0.30, 0.43, 0.68, 0.57)  // middle
    )
    if (segments.any { it > 0.20 && it < 0.45 }) return null
    val bits = segments.foldIndexed(0) { index, value, fill -> if (fill >= 0.45) value or (1 shl index) else value }
    if (bits == 0b0100111) return 7 // Some LCDs also light the upper-left segment for 7.
    return listOf(0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110,
        0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111).indexOf(bits).takeIf { it >= 0 }
}

/** Connected dark-pixel bounds, used to retain evidence of clipped edge strokes. */
private fun segmentComponents(mask: BooleanArray, width: Int, height: Int): List<SegmentBox> {
    val joined = mask.copyOf()
    val queue = IntArray(mask.size)
    val boxes = mutableListOf<SegmentBox>()
    for (start in joined.indices) {
        if (!joined[start]) continue
        var head = 0
        var tail = 1
        queue[0] = start
        joined[start] = false
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        fun visit(index: Int) {
            if (joined[index]) { joined[index] = false; queue[tail++] = index }
        }
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            left = min(left, x)
            top = min(top, y)
            right = max(right, x + 1)
            bottom = max(bottom, y + 1)
            if (x > 0) visit(index - 1)
            if (x + 1 < width) visit(index + 1)
            if (y > 0) visit(index - width)
            if (y + 1 < height) visit(index + width)
        }
        if (right > left && bottom > top) boxes.add(SegmentBox(left, top, right, bottom))
    }
    return boxes
}
