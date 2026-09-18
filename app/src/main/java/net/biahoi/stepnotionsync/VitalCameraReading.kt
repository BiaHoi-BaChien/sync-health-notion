package net.biahoi.stepnotionsync

import java.text.Normalizer
import java.util.Locale

private val VITAL_OCR_LABELS_AND_UNITS = setOf(
    "sys", "dia", "pul", "pulse", "mmhg", "bpm", "/min",
    "最高血圧", "最低血圧", "収縮期", "拡張期", "脈拍"
)

internal data class VitalCameraReading(val systolic: Int, val diastolic: Int, val heartRate: Int)

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
        if (text.isEmpty() || text.lowercase(Locale.ROOT) in VITAL_OCR_LABELS_AND_UNITS) continue
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
