package net.biahoi.stepnotionsync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

internal class VitalScanFrameView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var selectedHandle = -1
    var layout: VitalScanLayout? = null
        private set

    init {
        contentDescription = "上から最高血圧、最低血圧、脈拍。四隅で傾き、区切り線で高さ、各行の左右の点で幅を調整できます。数字の周囲に余白を残してください。"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0 && h > 0 && layout == null) layout = VitalScanLayout.centered(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val guide = layout ?: return
        val transform = guide.toImageMatrix(width, height)
        val corners = guide.corners.flatMap { listOf(it.x * width, it.y * height) }.toFloatArray()
        val outline = Path().apply {
            moveTo(corners[0], corners[1])
            for (i in 1..3) lineTo(corners[i * 2], corners[i * 2 + 1])
            close()
        }
        val cuts = listOf(0f) + guide.divisions + 1f
        val rowPaths = guide.rowInsets.mapIndexed { index, inset ->
            val row = floatArrayOf(inset.left, cuts[index], inset.right, cuts[index],
                inset.right, cuts[index + 1], inset.left, cuts[index + 1])
            transform.mapPoints(row)
            Path().apply {
                moveTo(row[0], row[1])
                for (i in 1..3) lineTo(row[i * 2], row[i * 2 + 1])
                close()
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = 0x88000000.toInt()
        canvas.drawPath(Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            rowPaths.forEach { addPath(it) }
        }, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        paint.color = Color.parseColor("#44D7B6")
        canvas.drawPath(outline, paint)
        for (division in guide.divisions) {
            val line = floatArrayOf(0f, division, 1f, division)
            transform.mapPoints(line)
            canvas.drawLine(line[0], line[1], line[2], line[3], paint)
        }
        rowPaths.forEach { canvas.drawPath(it, paint) }
        if (isEnabled) {
            paint.style = Paint.Style.FILL
            for (point in handles(guide)) canvas.drawCircle(point.x, point.y, 6 * density, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        paint.setShadowLayer(2 * density, 0f, 0f, Color.BLACK)
        val tops = listOf(0f) + guide.divisions
        listOf("最高", "最低", "脈拍").forEachIndexed { index, label ->
            val point = floatArrayOf(0f, tops[index])
            transform.mapPoints(point)
            val labelWidth = paint.measureText(label)
            canvas.drawText(label, (point[0] - labelWidth - 8 * density).coerceAtLeast(2 * density),
                point[1] + paint.textSize + 6 * density, paint)
        }
        paint.clearShadowLayer()
    }

    private fun handles(guide: VitalScanLayout): List<VitalScanPoint> {
        val handles = guide.corners.map { VitalScanPoint(it.x * width, it.y * height) }.toMutableList()
        for (division in guide.divisions) {
            val point = floatArrayOf(0.5f, division)
            guide.toImageMatrix(width, height).mapPoints(point)
            handles.add(VitalScanPoint(point[0], point[1]))
        }
        val cuts = listOf(0f) + guide.divisions + 1f
        guide.rowInsets.forEachIndexed { index, inset ->
            for (x in listOf(inset.left, inset.right)) {
                val point = floatArrayOf(x, (cuts[index] + cuts[index + 1]) / 2)
                guide.toImageMatrix(width, height).mapPoints(point)
                handles.add(VitalScanPoint(point[0], point[1]))
            }
        }
        return handles
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val guide = layout ?: return false
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectedHandle = handles(guide).withIndex().minByOrNull {
                    hypot(it.value.x - event.x, it.value.y - event.y)
                }?.takeIf { hypot(it.value.x - event.x, it.value.y - event.y) <= 28 * density }?.index ?: -1
                if (selectedHandle < 0) return false
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectedHandle < 0) return false
                val changed = if (selectedHandle < 4) {
                    guide.copy(corners = guide.corners.toMutableList().apply {
                        this[selectedHandle] = VitalScanPoint((event.x / width).coerceIn(0.02f, 0.98f),
                            (event.y / height).coerceIn(0.02f, 0.98f))
                    })
                } else {
                    val inverse = Matrix()
                    if (!guide.toImageMatrix(width, height).invert(inverse)) return true
                    val point = floatArrayOf(event.x, event.y)
                    inverse.mapPoints(point)
                    if (selectedHandle < 6) {
                        guide.copy(divisions = guide.divisions.toMutableList().apply { this[selectedHandle - 4] = point[1] })
                    } else {
                        val row = (selectedHandle - 6) / 2
                        val inset = guide.rowInsets[row]
                        val moved = if (selectedHandle % 2 == 0) inset.copy(left = point[0].coerceIn(0f, 1f))
                            else inset.copy(right = point[0].coerceIn(0f, 1f))
                        guide.copy(rowInsets = guide.rowInsets.toMutableList().apply { this[row] = moved })
                    }
                }
                if (changed.isValid()) { layout = changed; invalidate() }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = selectedHandle >= 0
                selectedHandle = -1
                parent.requestDisallowInterceptTouchEvent(false)
                if (handled && event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return handled
            }
        }
        return false
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Same perspective transform for the visible dividers and the captured pixels. */
internal fun VitalScanLayout.toImageMatrix(width: Int, height: Int): Matrix = Matrix().apply {
    require(isValid())
    check(setPolyToPoly(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), 0,
        corners.flatMap { listOf(it.x * width, it.y * height) }.toFloatArray(), 0, 4))
}
