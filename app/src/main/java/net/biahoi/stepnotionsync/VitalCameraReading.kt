package net.biahoi.stepnotionsync

import java.text.Normalizer
import java.util.Locale

private val VITAL_OCR_LABELS_AND_UNITS = setOf(
    "sys", "dia", "pul", "pulse", "mmhg", "bpm", "/min",
    "最高血圧", "最低血圧", "収縮期", "拡張期", "脈拍"
)

internal data class VitalCameraReading(val systolic: Int, val diastolic: Int, val heartRate: Int)

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
