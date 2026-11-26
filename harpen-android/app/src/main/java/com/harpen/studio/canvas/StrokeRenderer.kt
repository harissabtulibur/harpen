package com.harpen.studio.canvas

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.harpen.studio.model.PenToolType
import com.harpen.studio.model.StrokePoint
import com.harpen.studio.model.VectorStroke
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Hardware-accelerated stroke renderer with pre-allocated Paint/Path objects
 * so drawing paths does not allocate per-frame on 4 GB RAM tablets.
 */
class StrokeRenderer {

    private val reusablePath = Path()
    private val reusableMatrix = Matrix()
    private val reusableBounds = RectF()

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val eraserXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val multiplyXfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)

    fun drawStroke(
        canvas: Canvas,
        stroke: VectorStroke,
        layerOpacity: Float = 1f,
        useHardwarePressure: Boolean = true
    ) {
        val points = stroke.points
        if (points.isEmpty()) return

        canvas.save()
        applyTransform(canvas, stroke)

        val alphaInt = ((stroke.opacity * layerOpacity).coerceIn(0f, 1f) * 255).toInt()
        val baseColor = (stroke.colorArgb.toInt() and 0x00FFFFFF) or (alphaInt shl 24)

        configurePaintForTool(stroke.toolType, baseColor, stroke.width, stroke.texture)

        val hasRealPressure = useHardwarePressure && detectRealPressureVariation(points)

        if (points.size == 1) {
            val p = points[0]
            fillPaint.color = baseColor
            fillPaint.xfermode = strokePaint.xfermode
            val radius = max(1f, stroke.width * 0.5f)
            canvas.drawCircle(p.x, p.y, radius, fillPaint)
            canvas.restore()
            return
        }

        if (hasRealPressure && supportsVariableWidth(stroke.toolType)) {
            drawVariableWidthSegments(canvas, points, stroke.width, stroke.pressureResponse)
        } else if (stroke.toolType == PenToolType.PENCIL_SOFT || stroke.toolType == PenToolType.CRAYON || stroke.toolType == PenToolType.PENCIL_HB) {
            drawTexturedStroke(canvas, points, stroke)
        } else {
            buildSmoothPath(points, stroke.smoothing, reusablePath)
            canvas.drawPath(reusablePath, strokePaint)
        }

        canvas.restore()
    }

    private fun applyTransform(canvas: Canvas, stroke: VectorStroke) {
        val t = stroke.transform
        if (t.translateX == 0f && t.translateY == 0f &&
            t.scaleX == 1f && t.scaleY == 1f &&
            t.rotationDeg == 0f && !t.flipHorizontal && !t.flipVertical
        ) return

        computeBounds(stroke.points, reusableBounds)
        val cx = reusableBounds.centerX()
        val cy = reusableBounds.centerY()

        reusableMatrix.reset()
        reusableMatrix.postTranslate(-cx, -cy)
        reusableMatrix.postScale(
            if (t.flipHorizontal) -t.scaleX else t.scaleX,
            if (t.flipVertical) -t.scaleY else t.scaleY
        )
        reusableMatrix.postRotate(t.rotationDeg)
        reusableMatrix.postTranslate(cx + t.translateX, cy + t.translateY)
        canvas.concat(reusableMatrix)
    }

    private fun configurePaintForTool(
        tool: PenToolType,
        argb: Int,
        width: Float,
        texture: Float
    ) {
        strokePaint.reset()
        strokePaint.isAntiAlias = true
        strokePaint.style = Paint.Style.STROKE
        strokePaint.color = argb
        strokePaint.strokeWidth = width
        strokePaint.strokeCap = when (tool) {
            PenToolType.HIGHLIGHTER, PenToolType.MARKER -> Paint.Cap.SQUARE
            else -> Paint.Cap.ROUND
        }
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.maskFilter = when (tool) {
            PenToolType.WATERCOLOR -> BlurMaskFilter(max(2f, width * 0.28f), BlurMaskFilter.Blur.NORMAL)
            PenToolType.PENCIL_SOFT -> if (texture > 0.1f) BlurMaskFilter(1.4f, BlurMaskFilter.Blur.NORMAL) else null
            else -> null
        }
        strokePaint.xfermode = when (tool) {
            PenToolType.ERASER_PIXEL -> eraserXfermode
            PenToolType.HIGHLIGHTER -> multiplyXfermode
            else -> null
        }
    }

    private fun buildSmoothPath(points: List<StrokePoint>, smoothing: Float, outPath: Path) {
        outPath.reset()
        val first = points[0]
        outPath.moveTo(first.x, first.y)

        if (points.size == 2 || smoothing <= 0.05f) {
            for (i in 1 until points.size) {
                outPath.lineTo(points[i].x, points[i].y)
            }
            return
        }

        for (i in 1 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val midX = (p0.x + p1.x) * 0.5f
            val midY = (p0.y + p1.y) * 0.5f
            outPath.quadTo(p0.x, p0.y, midX, midY)
        }
        val last = points.last()
        outPath.lineTo(last.x, last.y)
    }

    private fun drawVariableWidthSegments(
        canvas: Canvas,
        points: List<StrokePoint>,
        baseWidth: Float,
        pressureResponse: Float
    ) {
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val p = curr.pressure ?: 1f
            val factor = 1f + (p - 0.5f) * 1.4f * pressureResponse
            strokePaint.strokeWidth = max(0.8f, baseWidth * factor.coerceIn(0.25f, 2.2f))
            canvas.drawLine(prev.x, prev.y, curr.x, curr.y, strokePaint)
        }
    }

    private fun drawTexturedStroke(
        canvas: Canvas,
        points: List<StrokePoint>,
        stroke: VectorStroke
    ) {
        buildSmoothPath(points, stroke.smoothing, reusablePath)
        canvas.drawPath(reusablePath, strokePaint)

        // Subtle grain stipple proportional to texture setting
        if (stroke.texture > 0.15f) {
            val originalAlpha = strokePaint.alpha
            strokePaint.alpha = (originalAlpha * 0.35f).toInt()
            val step = max(1, (4 - stroke.texture * 2).toInt())
            fillPaint.color = strokePaint.color
            fillPaint.alpha = (originalAlpha * 0.45f).toInt()
            for (i in points.indices step step) {
                val p = points[i]
                val jitter = ((p.x.toInt() xor p.y.toInt()) % 5) * 0.35f * stroke.texture
                canvas.drawCircle(p.x + jitter, p.y - jitter, max(0.8f, stroke.width * 0.18f), fillPaint)
            }
            strokePaint.alpha = originalAlpha
        }
    }

    private fun supportsVariableWidth(tool: PenToolType): Boolean = when (tool) {
        PenToolType.FOUNTAIN, PenToolType.BRUSH, PenToolType.PENCIL_SOFT, PenToolType.WATERCOLOR -> true
        else -> false
    }

    /**
     * Never falsely simulate pressure on universal capacitive styluses:
     * only treat pressure as real if it varies or lies strictly within (0, 1).
     */
    private fun detectRealPressureVariation(points: List<StrokePoint>): Boolean {
        var first: Float? = null
        for (pt in points) {
            val p = pt.pressure ?: continue
            if (p > 0.01f && p < 0.99f) return true
            if (first == null) first = p else if (kotlin.math.abs(p - first) > 0.03f) return true
        }
        return false
    }

    companion object {
        fun computeBounds(points: List<StrokePoint>, outRect: RectF) {
            if (points.isEmpty()) {
                outRect.setEmpty()
                return
            }
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (p in points) {
                minX = min(minX, p.x)
                minY = min(minY, p.y)
                maxX = max(maxX, p.x)
                maxY = max(maxY, p.y)
            }
            outRect.set(minX, minY, maxX, maxY)
        }

        fun hitTestStroke(stroke: VectorStroke, x: Float, y: Float, tolerance: Float = 24f): Boolean {
            val bounds = RectF()
            computeBounds(stroke.points, bounds)
            val tx = stroke.transform.translateX
            val ty = stroke.transform.translateY
            bounds.offset(tx, ty)
            bounds.inset(-tolerance, -tolerance)
            if (!bounds.contains(x, y)) return false
            val r = max(tolerance, stroke.width * 0.8f)
            return stroke.points.any { pt ->
                hypot((pt.x + tx) - x, (pt.y + ty) - y) <= r
            }
        }
    }
}
