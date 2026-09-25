package net.biahoi.stepnotionsync

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

internal data class VitalImageRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top

    fun intersects(element: VitalOcrElement) =
        element.right > left && element.left < right && element.bottom > top && element.top < bottom
}

internal fun isVitalNumericEvidence(element: VitalOcrElement): Boolean {
    val text = Normalizer.normalize(element.text, Normalizer.Form.NFKC).trim()
    return text.any(Char::isDigit) || text.matches(Regex("[Il|O]+"))
}

/** Locate the three prominent numeric rows without asking the user to position guides. */
internal fun automaticVitalRows(elements: List<VitalOcrElement>, width: Int, height: Int): List<VitalImageRegion>? {
    if (width < 32 || height < 32 || elements.size > 200) return null
    if (elements.any { it.left < 0 || it.top < 0 || it.right > width || it.bottom > height ||
            it.left >= it.right || it.top >= it.bottom }) return null
    val numeric = elements.filter(::isVitalNumericEvidence)
    val tallest = numeric.maxOfOrNull { it.bottom - it.top } ?: return null
    if (tallest < 16) return null
    // Dates and memory indicators are smaller than the measurement digits. Retain
    // partial strokes and the smaller pulse row; they are not interpreted as numbers here.
    val prominent = numeric.filter { it.bottom - it.top >= tallest * 0.35 }.sortedBy { it.top }
    val groups = mutableListOf<MutableList<VitalOcrElement>>()
    for (element in prominent) {
        val group = groups.lastOrNull()
        if (group != null && element.top < group.maxOf { it.bottom }) group.add(element)
        else groups.add(mutableListOf(element))
    }
    if (groups.size != 3) return null
    if (groups.any { group ->
            group.sortedBy { it.left }.zipWithNext().any { (a, b) ->
                b.left < a.right || b.left - a.right > max(a.bottom - a.top, b.bottom - b.top)
            }
        }) return null
    val rows = groups.map { group ->
        VitalImageRegion(group.minOf { it.left }, group.minOf { it.top }, group.maxOf { it.right }, group.maxOf { it.bottom })
    }
    if (rows.maxOf { it.left } >= rows.minOf { it.right }) return null
    if (rows.any { it.width > it.height * 3 }) return null
    if (rows.zipWithNext().any { (a, b) -> a.bottom >= b.top }) return null
    // Keep horizontal context, including a leading 1 that OCR may have missed.
    // All three rows share the leftmost detected digit; never crop to just one OCR token.
    val columnLeft = rows.minOf { it.left }
    return rows.mapIndexed { index, row ->
        val verticalMargin = max(4, row.height / 10)
        val topLimit = if (index == 0) 0 else (rows[index - 1].bottom + row.top) / 2
        val bottomLimit = if (index == rows.lastIndex) height else (row.bottom + rows[index + 1].top) / 2
        VitalImageRegion(
            max(0, min(columnLeft, row.left - row.height * 2)),
            max(topLimit, row.top - verticalMargin),
            min(width, row.right + row.height),
            min(bottomLimit, row.bottom + verticalMargin)
        )
    }.takeIf { regions -> regions.all { it.width >= 32 && it.height >= 32 } }
}

/** Retain even partially overlapping text so a sign or a split digit cannot disappear. */
internal fun elementsInVitalRegion(elements: List<VitalOcrElement>, region: VitalImageRegion): List<VitalOcrElement> =
    elements.filter(region::intersects).map {
        it.copy(left = it.left - region.left, top = it.top - region.top,
            right = it.right - region.left, bottom = it.bottom - region.top)
    }

/** With no complete pixel layout, all three measurement labels must identify the OCR rows. */
internal fun labelledAutomaticVitalRows(
    elements: List<VitalOcrElement>, width: Int, height: Int, content: VitalImageRegion
): List<VitalImageRegion>? {
    val rows = automaticVitalRows(elements, width, height) ?: return null
    for ((index, labels) in VITAL_MEASUREMENT_LABELS.withIndex()) {
        val label = elements.singleOrNull {
            Normalizer.normalize(it.text, Normalizer.Form.NFKC).trim().trimEnd('.').lowercase(Locale.ROOT) in labels
        } ?: return null
        val center = (label.top + label.bottom) / 2
        if (center !in rows[index].top until rows[index].bottom) return null
    }
    if (elements.filter(::isVitalNumericEvidence).any { element -> rows.count { it.intersects(element) } != 1 }) return null
    return rows.map { it.copy(left = max(it.left, content.left), right = min(it.right, content.right)) }
        .takeIf { clipped -> clipped.all { it.width >= 32 } }
}
