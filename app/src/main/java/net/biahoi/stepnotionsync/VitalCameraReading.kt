package net.biahoi.stepnotionsync

import java.text.Normalizer
import java.util.Locale

internal val VITAL_MEASUREMENT_LABELS = listOf(
    setOf("sys", "最高血圧", "収縮期"), setOf("dia", "最低血圧", "拡張期"), setOf("pul", "pulse", "脈拍")
)

private val VITAL_OCR_LABELS_AND_UNITS = setOf(
    "sys", "dia", "pul", "pulse", "mmhg", "bpm", "/min",
    "最高血圧", "最低血圧", "収縮期", "拡張期", "脈拍"
)

internal data class VitalCameraReading(val systolic: Int, val diastolic: Int, val heartRate: Int)

internal fun vitalReadingFromNumbers(values: List<Int>): VitalCameraReading? {
    if (values.size != 3 || values.any { it !in 1..999 }) return null
    val (systolic, diastolic, heartRate) = values
    if (systolic <= diastolic || heartRate > 300) return null
    return VitalCameraReading(systolic, diastolic, heartRate)
}

internal fun selectVitalCameraNumber(ocr: Int?, segments: SevenSegmentNumberResult): Int? = when (segments) {
    is SevenSegmentNumberResult.Recognized -> segments.value.takeIf { ocr == null || it == ocr }
    SevenSegmentNumberResult.NotDetected -> ocr
    SevenSegmentNumberResult.Uncertain -> null
}

internal fun selectFramedVitalCameraNumber(
    elements: List<VitalOcrElement>, width: Int, height: Int, segments: SevenSegmentNumberResult
): Int? {
    val margin = maxOf(2, minOf(width, height) / 100)
    if (elements.any { it.left < margin || it.top < margin || it.right > width - margin || it.bottom > height - margin }) return null
    if (!allowsSevenSegmentFallback(elements)) return null
    return selectVitalCameraNumber(parseVitalCameraNumber(elements), segments)
}

/** Cropping cannot override a conflicting full-image reading or discard its uncertain glyphs. */
internal fun selectAutomaticVitalCameraNumber(
    original: List<VitalOcrElement>, refined: List<VitalOcrElement>, width: Int, height: Int,
    segments: SevenSegmentNumberResult
): Int? {
    val first = selectFramedVitalCameraNumber(original, width, height, segments) ?: return null
    val second = selectFramedVitalCameraNumber(refined, width, height, segments) ?: return null
    return first.takeIf { it == second }
}

/** Missing OCR rows are allowed only when independently located pixels supply them. */
internal fun selectWholeImageVitalReading(elements: List<VitalOcrElement>, display: SevenSegmentDisplay): VitalCameraReading? {
    val recognized = display.result as? SevenSegmentVitalResult.Recognized ?: return null
    if (display.rows.size != 3 || parseVitalCameraReading(display.rows) != recognized.reading) return null
    val matched = List(3) { mutableListOf<VitalOcrElement>() }
    for (element in elements) {
        if (element.left < 0 || element.top < 0 || element.right <= element.left || element.bottom <= element.top) return null
        val text = Normalizer.normalize(element.text, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
        val field = VITAL_MEASUREMENT_LABELS.indexOfFirst { text.trimEnd('.') in it }
        if (field >= 0) {
            val row = display.rows[field]
            val margin = (row.bottom - row.top) / 10
            if ((element.top + element.bottom) / 2 !in row.top - margin until row.bottom + margin) return null
        }
        if (text.isEmpty() || text.trimEnd('.') in VITAL_OCR_LABELS_AND_UNITS) continue
        val rows = display.rows.indices.filter { index ->
            val row = display.rows[index]
            val overlap = minOf(row.bottom, element.bottom) - maxOf(row.top, element.top)
            val margin = (row.bottom - row.top) / 4
            overlap > 0 && overlap >= minOf(row.bottom - row.top, element.bottom - element.top) / 2 &&
                element.right > row.left - margin && element.left < row.right + margin
        }
        // A number outside all detected rows is additional evidence, not background.
        if (rows.isEmpty() && !isVitalNumericEvidence(element)) continue
        if (rows.size != 1) return null
        matched[rows.single()].add(element)
    }
    for ((index, row) in matched.withIndex()) {
        if (!allowsSevenSegmentFallback(row)) return null
        // Multiple tokens must not hide a conflicting complete OCR number.
        if (row.mapNotNull { parseVitalCameraNumber(listOf(it)) }.any { it.toString() != display.rows[index].text }) return null
    }
    return recognized.reading
}

/** One complete number per guide; split digits and unknown text remain invalid. */
internal fun parseVitalCameraNumber(elements: List<VitalOcrElement>): Int? {
    val numbers = elements.filterNot {
        val text = Normalizer.normalize(it.text, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
        text.isEmpty() || text.trimEnd('.') in VITAL_OCR_LABELS_AND_UNITS
    }
    val element = numbers.singleOrNull() ?: return null
    if (element.left >= element.right || element.top >= element.bottom) return null
    val text = Normalizer.normalize(element.text, Normalizer.Form.NFKC).trim()
    return text.takeIf { it.matches(Regex("[1-9][0-9]{0,2}")) }?.toInt()
}

/** Pixel uncertainty and conflicting complete readings require a retry, even when OCR succeeds. */
internal fun selectVitalCameraReading(ocr: VitalCameraReading?, segments: SevenSegmentVitalResult): VitalCameraReading? =
    when (segments) {
        is SevenSegmentVitalResult.Recognized -> segments.reading.takeIf { ocr == null || it == ocr }
        SevenSegmentVitalResult.NotDetected -> ocr
        SevenSegmentVitalResult.Uncertain -> null
    }

/** A pixel fallback must not turn a recognized decimal, sign, date or time into an integer. */
internal fun allowsSevenSegmentFallback(elements: List<VitalOcrElement>): Boolean = elements.all { element ->
    val text = Normalizer.normalize(element.text, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
    text.trimEnd('.') in VITAL_OCR_LABELS_AND_UNITS || text.none { it in ".,:;/+-−" }
}

internal data class VitalOcrElement(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

/** Reads only a single column, ordered SYS / DIA / pulse as shown in the camera instructions. */
internal fun parseVitalCameraReading(elements: List<VitalOcrElement>): VitalCameraReading? {
    val numbers = mutableListOf<Pair<VitalOcrElement, Int>>()
    for (element in elements) {
        val text = Normalizer.normalize(element.text, Normalizer.Form.NFKC).trim()
        if (text.isEmpty() || text.lowercase(Locale.ROOT).trimEnd('.') in VITAL_OCR_LABELS_AND_UNITS) continue
        // Unknown text may be a split digit (e.g. I + 80); ignore only known labels/units.
        // Never repair uncertain glyphs, join split digits, or discard dates/decimal values.
        if (!text.matches(Regex("[1-9][0-9]{0,2}"))) return null
        if (element.left >= element.right || element.top >= element.bottom) return null
        numbers.add(element to text.toInt())
    }
    if (numbers.size != 3) return null
    val ordered = numbers.sortedBy { it.first.top }
    if (ordered.zipWithNext().any { (upper, lower) -> upper.first.bottom > lower.first.top }) return null
    if (ordered.maxOf { it.first.left } >= ordered.minOf { it.first.right }) return null
    val (systolic, diastolic, heartRate) = ordered.map { it.second }
    if (systolic <= diastolic || heartRate > 300) return null
    return VitalCameraReading(systolic, diastolic, heartRate)
}
