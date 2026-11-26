package com.harpen.studio.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.harpen.studio.data.settings.GestureAction
import com.harpen.studio.data.settings.HarpenSettings
import com.harpen.studio.input.PalmGuardDecision
import com.harpen.studio.input.PalmGuardEngine
import com.harpen.studio.input.PalmGuardStatus
import com.harpen.studio.input.PointerSample
import com.harpen.studio.input.TouchDebuggerController
import com.harpen.studio.model.CanvasMode
import com.harpen.studio.model.ImageObject
import com.harpen.studio.model.LayerBlendMode
import com.harpen.studio.model.NotePage
import com.harpen.studio.model.ObjectTransform
import com.harpen.studio.model.PageOrientation
import com.harpen.studio.model.PageTemplate
import com.harpen.studio.model.PenToolType
import com.harpen.studio.model.PointerVisualState
import com.harpen.studio.model.SelectionMode
import com.harpen.studio.model.StrokePoint
import com.harpen.studio.model.TextAlignment
import com.harpen.studio.model.TextObject
import com.harpen.studio.model.ToolConfig
import com.harpen.studio.model.VectorStroke
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

interface HarpenCanvasListener {
    fun onStrokeCommitted(stroke: VectorStroke)
    fun onStrokesErased(strokeIds: List<String>)
    fun onGestureAction(action: GestureAction)
    fun onViewportChanged(zoom: Float, panX: Float, panY: Float, rotationDeg: Float)
    fun onSelectionChanged(selectedStrokeIds: Set<String>, selectedTextIds: Set<String>, selectedImageIds: Set<String>)
    fun onSelectionMoved(dx: Float, dy: Float)
    fun onTextObjectTapped(textObject: TextObject)
    fun onEyedropperColorPicked(colorArgb: Long)
    fun onPalmGuardStatusChanged(status: PalmGuardStatus)
}

/**
 * Custom native Android View canvas for HARPEN.
 * - Real-time low-latency MotionEvent stroke capture with historical batching
 * - PalmGuard input protection (Modes A-E)
 * - Built-in Touch Debugger overlay directly on the drawing surface
 * - Multi-touch gesture recognition (2/3/4 finger taps, pinch zoom, 2-finger pan)
 * - Ruler, snap-to-grid, templates, layer compositing, lasso/rect selection
 */
class HarpenCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: HarpenCanvasListener? = null
    var touchDebugger: TouchDebuggerController? = null
    val palmGuard = PalmGuardEngine()

    private var currentPage: NotePage = NotePage()
    private var canvasMode: CanvasMode = CanvasMode.PAGED
    private var activeLayerId: String = "layer_ink"
    private var toolConfig: ToolConfig = ToolConfig()
    private var selectionMode: SelectionMode = SelectionMode.NONE
    private var eyedropperActive: Boolean = false
    private var rulerVisible: Boolean = false
    private var rulerY: Float = 420f
    private var rulerAngleDeg: Float = 0f
    private var settings: HarpenSettings = HarpenSettings()
    private var assetsDir: File = File(context.filesDir, "assets")
    private var pdfBackdropBitmap: Bitmap? = null

    private var zoomScale: Float = 1f
    private var panX: Float = 60f
    private var panY: Float = 60f
    private var canvasRotationDeg: Float = 0f

    private val strokeRenderer = StrokeRenderer()
    private val bitmapCache = LinkedHashMap<String, Bitmap>(16, 0.75f, true)

    private val activeStrokePoints = mutableListOf<StrokePoint>()
    private var activeStrokePointerId: Int? = null
    private val lassoPoints = mutableListOf<Pair<Float, Float>>()
    private var rectSelectStart: Pair<Float, Float>? = null
    private var rectSelectCurrent: Pair<Float, Float>? = null

    private var selectedStrokeIds = emptySet<String>()
    private var selectedTextIds = emptySet<String>()
    private var selectedImageIds = emptySet<String>()
    private var draggingSelection = false
    private var lastDragCanvasPoint: Pair<Float, Float>? = null

    // Multi-touch gesture tracking
    private var maxPointersInGesture = 0
    private var gestureDownTimeMs = 0L
    private var gestureMovedBeyondSlop = false
    private var isPanningOrZooming = false
    private var lastPinchDist = 0f
    private var lastPinchMidX = 0f
    private var lastPinchMidY = 0f
    private var lastPinchAngle = 0f
    private var lastTapDownPos = mutableMapOf<Int, Pair<Float, Float>>()

    // Pre-allocated paints & matrices
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val templatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.2f }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A2563EB
        style = Paint.Style.FILL
    }
    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC0F172A.toInt()
        style = Paint.Style.FILL
    }
    private val rulerTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        textSize = 22f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val layerPaint = Paint()
    private val debugMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 24f
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val debugTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val workMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val scratchPoint = FloatArray(2)
    private val scratchRect = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun bindState(
        page: NotePage,
        mode: CanvasMode,
        layerId: String,
        tool: ToolConfig,
        selection: SelectionMode,
        eyedropper: Boolean,
        ruler: Boolean,
        zoom: Float,
        panX: Float,
        panY: Float,
        rotationDeg: Float,
        harpenSettings: HarpenSettings,
        selectedStrokes: Set<String>,
        selectedTexts: Set<String>,
        selectedImages: Set<String>,
        pdfBackdrop: Bitmap?
    ) {
        this.currentPage = page
        this.canvasMode = mode
        this.activeLayerId = layerId
        this.toolConfig = tool
        this.selectionMode = selection
        this.eyedropperActive = eyedropper
        this.rulerVisible = ruler
        this.zoomScale = zoom
        this.panX = panX
        this.panY = panY
        this.canvasRotationDeg = rotationDeg
        this.settings = harpenSettings
        this.selectedStrokeIds = selectedStrokes
        this.selectedTextIds = selectedTexts
        this.selectedImageIds = selectedImages
        this.pdfBackdropBitmap = pdfBackdrop
        invalidate()
    }

    /**
     * Safely cancels any unfinished stroke on rotation/configuration interruption
     * without leaving partial strokes or locking PalmGuard.
     */
    fun cancelUnfinishedStrokeSafely() {
        activeStrokePoints.clear()
        activeStrokePointerId = null
        lassoPoints.clear()
        rectSelectStart = null
        rectSelectCurrent = null
        isPanningOrZooming = false
        draggingSelection = false
        palmGuard.manualReset()
        listener?.onPalmGuardStatusChanged(palmGuard.getStatus(settings.palmGuardMode))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Outer workspace backdrop
        canvas.drawColor(if (settings.themeMode.name == "DARK") 0xFF0F172A.toInt() else 0xFFE2E8F0.toInt())

        canvas.save()
        buildViewportMatrix(workMatrix)
        canvas.concat(workMatrix)

        val pageW = getPageWidthPx()
        val pageH = getPageHeightPx()

        if (canvasMode == CanvasMode.INFINITE) {
            paperPaint.color = currentPage.backgroundColorArgb.toInt()
            canvas.drawRect(-4000f, -4000f, 8000f, 8000f, paperPaint)
            drawPageTemplate(canvas, -1000f, -1000f, 3000f, 3000f)
        } else {
            // Subtle page shadow + crisp paper sheet
            paperPaint.color = 0x220F172A
            canvas.drawRoundRect(6f, 8f, pageW + 6f, pageH + 8f, 8f, 8f, paperPaint)
            paperPaint.color = currentPage.backgroundColorArgb.toInt()
            canvas.drawRect(0f, 0f, pageW, pageH, paperPaint)

            pdfBackdropBitmap?.let { bmp ->
                canvas.drawBitmap(bmp, null, RectF(0f, 0f, pageW, pageH), imagePaint)
            }
            drawPageTemplate(canvas, 0f, 0f, pageW, pageH)
        }

        // Composite layers in order
        for (layer in currentPage.layers) {
            if (!layer.visible) continue
            layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
            val useLayerSave = layer.opacity < 0.99f || layer.blendMode != LayerBlendMode.NORMAL
            val saveCount = if (useLayerSave) {
                canvas.saveLayer(null, layerPaint)
            } else -1

            // Draw images in this layer
            currentPage.imageObjects
                .filter { it.layerId == layer.id }
                .sortedBy { it.zOrder }
                .forEach { img -> drawImageObject(canvas, img) }

            // Draw strokes in this layer
            currentPage.strokes
                .filter { it.layerId == layer.id }
                .forEach { stroke ->
                    strokeRenderer.drawStroke(
                        canvas = canvas,
                        stroke = stroke,
                        layerOpacity = 1f,
                        useHardwarePressure = settings.useHardwarePressureWhenAvailable
                    )
                }

            // Draw in-progress live stroke on active layer
            if (layer.id == activeLayerId && activeStrokePoints.isNotEmpty()) {
                val previewStroke = VectorStroke(
                    id = "_preview",
                    layerId = activeLayerId,
                    toolType = toolConfig.toolType,
                    colorArgb = toolConfig.colorArgb,
                    width = toolConfig.width,
                    opacity = toolConfig.opacity,
                    smoothing = toolConfig.smoothing * settings.globalSmoothingMultiplier,
                    texture = toolConfig.texture,
                    pressureResponse = toolConfig.pressureResponse,
                    tiltResponse = toolConfig.tiltResponse,
                    points = activeStrokePoints
                )
                strokeRenderer.drawStroke(
                    canvas = canvas,
                    stroke = previewStroke,
                    layerOpacity = 1f,
                    useHardwarePressure = settings.useHardwarePressureWhenAvailable
                )
            }

            // Draw text objects in this layer
            currentPage.textObjects
                .filter { it.layerId == layer.id }
                .forEach { textObj -> drawTextObject(canvas, textObj) }

            if (saveCount != -1) canvas.restoreToCount(saveCount)
        }

        // Selection highlights & active marquee/lasso
        drawSelectionOverlays(canvas)

        if (rulerVisible) {
            drawRuler(canvas, pageW)
        }

        canvas.restore()

        // Touch Debugger live overlay rendered in screen coordinates directly over canvas
        if (settings.touchDebuggerEnabled) {
            drawTouchDebuggerOverlay(canvas)
        }
    }

    private fun drawPageTemplate(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val spacing = settings.gridSpacingDp.coerceIn(12, 72) * resources.displayMetrics.density
        when (currentPage.template) {
            PageTemplate.BLANK, PageTemplate.CUSTOM -> Unit
            PageTemplate.GRID, PageTemplate.GRAPH -> {
                templatePaint.color = if (currentPage.template == PageTemplate.GRAPH) 0x332563EB else 0x2264748B
                var x = left
                while (x <= right) {
                    canvas.drawLine(x, top, x, bottom, templatePaint)
                    x += spacing
                }
                var y = top
                while (y <= bottom) {
                    canvas.drawLine(left, y, right, y, templatePaint)
                    y += spacing
                }
            }
            PageTemplate.DOTTED -> {
                templatePaint.color = 0x5564748B
                var y = top + spacing
                while (y < bottom) {
                    var x = left + spacing
                    while (x < right) {
                        canvas.drawCircle(x, y, 2.2f, templatePaint)
                        x += spacing
                    }
                    y += spacing
                }
            }
            PageTemplate.RULED -> {
                templatePaint.color = 0x3364748B
                var y = top + spacing * 2
                while (y < bottom) {
                    canvas.drawLine(left, y, right, y, templatePaint)
                    y += spacing * 1.4f
                }
                templatePaint.color = 0x55EF4444
                canvas.drawLine(left + spacing * 2.5f, top, left + spacing * 2.5f, bottom, templatePaint)
            }
            PageTemplate.CORNELL -> {
                templatePaint.color = 0x443B82F6
                val headerY = top + (bottom - top) * 0.12f
                val cueX = left + (right - left) * 0.28f
                val summaryY = bottom - (bottom - top) * 0.16f
                canvas.drawLine(left, headerY, right, headerY, templatePaint)
                canvas.drawLine(cueX, headerY, cueX, summaryY, templatePaint)
                canvas.drawLine(left, summaryY, right, summaryY, templatePaint)
                templatePaint.color = 0x2264748B
                var y = headerY + spacing * 1.3f
                while (y < summaryY) {
                    canvas.drawLine(cueX + 12f, y, right - 12f, y, templatePaint)
                    y += spacing * 1.3f
                }
            }
            PageTemplate.PLANNER -> {
                templatePaint.color = 0x330F766E
                val midX = (left + right) * 0.5f
                canvas.drawLine(midX, top + 40f, midX, bottom - 40f, templatePaint)
                var y = top + 90f
                while (y < bottom - 40f) {
                    canvas.drawLine(left + 32f, y, right - 32f, y, templatePaint)
                    y += spacing * 1.8f
                }
            }
            PageTemplate.MUSIC_SHEET -> {
                templatePaint.color = 0x55334155
                var staffTop = top + 70f
                while (staffTop < bottom - 90f) {
                    for (line in 0 until 5) {
                        val y = staffTop + line * 14f
                        canvas.drawLine(left + 40f, y, right - 40f, y, templatePaint)
                    }
                    staffTop += 130f
                }
            }
        }
    }

    private fun drawImageObject(canvas: Canvas, img: ImageObject) {
        val file = File(assetsDir, img.assetFileName)
        val bmp = bitmapCache[img.assetFileName] ?: if (file.exists()) {
            val opts = BitmapFactory.Options().apply {
                if (settings.performanceMode.name == "LOW_MEMORY_4GB") {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.also { loaded ->
                if (bitmapCache.size > 12) {
                    val eldest = bitmapCache.keys.firstOrNull()
                    if (eldest != null) bitmapCache.remove(eldest)
                }
                bitmapCache[img.assetFileName] = loaded
            }
        } else null

        canvas.save()
        val cx = img.x + img.width * 0.5f
        val cy = img.y + img.height * 0.5f
        canvas.rotate(img.rotationDeg, cx, cy)
        canvas.scale(if (img.flipHorizontal) -1f else 1f, if (img.flipVertical) -1f else 1f, cx, cy)

        imagePaint.alpha = (img.opacity.coerceIn(0f, 1f) * 255).toInt()
        val dst = RectF(img.x, img.y, img.x + img.width, img.y + img.height)

        if (bmp != null) {
            val src = Rect(
                (bmp.width * img.cropLeft).toInt(),
                (bmp.height * img.cropTop).toInt(),
                (bmp.width * img.cropRight).toInt(),
                (bmp.height * img.cropBottom).toInt()
            )
            canvas.drawBitmap(bmp, src, dst, imagePaint)
        } else {
            paperPaint.color = 0xFFE2E8F0.toInt()
            canvas.drawRect(dst, paperPaint)
        }
        canvas.restore()
    }

    private fun drawTextObject(canvas: Canvas, textObj: TextObject) {
        canvas.save()
        canvas.rotate(textObj.rotationDeg, textObj.x + textObj.width * 0.5f, textObj.y + textObj.height * 0.5f)
        textObj.backgroundColorArgb?.let { bg ->
            paperPaint.color = bg.toInt()
            canvas.drawRoundRect(
                textObj.x - 8f, textObj.y - 8f,
                textObj.x + textObj.width + 8f, textObj.y + textObj.height + 8f,
                8f, 8f, paperPaint
            )
        }
        textPaint.color = textObj.colorArgb.toInt()
        textPaint.textSize = textObj.fontSizeSp * resources.displayMetrics.density
        textPaint.isFakeBoldText = textObj.bold
        textPaint.isUnderlineText = textObj.underline
        textPaint.textSkewX = if (textObj.italic) -0.2f else 0f
        textPaint.textAlign = when (textObj.alignment) {
            TextAlignment.LEFT -> Paint.Align.LEFT
            TextAlignment.CENTER -> Paint.Align.CENTER
            TextAlignment.RIGHT -> Paint.Align.RIGHT
        }

        val lines = textObj.text.split("\n")
        val lineHeight = textPaint.textSize * textObj.lineSpacing
        var y = textObj.y + textPaint.textSize
        lines.forEachIndexed { idx, line ->
            val formatted = when {
                textObj.bulletList -> "• $line"
                textObj.numberedList -> "${idx + 1}. $line"
                else -> line
            }
            val drawX = when (textObj.alignment) {
                TextAlignment.LEFT -> textObj.x
                TextAlignment.CENTER -> textObj.x + textObj.width * 0.5f
                TextAlignment.RIGHT -> textObj.x + textObj.width
            }
            textObj.highlightColorArgb?.let { hl ->
                paperPaint.color = hl.toInt()
                canvas.drawRect(textObj.x, y - textPaint.textSize, textObj.x + textObj.width, y + 6f, paperPaint)
            }
            canvas.drawText(formatted, drawX, y, textPaint)
            y += lineHeight
        }
        canvas.restore()
    }

    private fun drawSelectionOverlays(canvas: Canvas) {
        currentPage.strokes.filter { it.id in selectedStrokeIds }.forEach { s ->
            StrokeRenderer.computeBounds(s.points, scratchRect)
            scratchRect.offset(s.transform.translateX, s.transform.translateY)
            scratchRect.inset(-10f, -10f)
            canvas.drawRect(scratchRect, selectionFillPaint)
            canvas.drawRect(scratchRect, selectionPaint)
        }
        currentPage.textObjects.filter { it.id in selectedTextIds }.forEach { t ->
            scratchRect.set(t.x - 8f, t.y - 8f, t.x + t.width + 8f, t.y + t.height + 8f)
            canvas.drawRect(scratchRect, selectionPaint)
        }
        currentPage.imageObjects.filter { it.id in selectedImageIds }.forEach { img ->
            scratchRect.set(img.x - 6f, img.y - 6f, img.x + img.width + 6f, img.y + img.height + 6f)
            canvas.drawRect(scratchRect, selectionPaint)
        }

        if (lassoPoints.size >= 2) {
            val path = Path().apply {
                moveTo(lassoPoints[0].first, lassoPoints[0].second)
                for (i in 1 until lassoPoints.size) {
                    lineTo(lassoPoints[i].first, lassoPoints[i].second)
                }
            }
            canvas.drawPath(path, selectionPaint)
        }

        val rStart = rectSelectStart
        val rEnd = rectSelectCurrent
        if (rStart != null && rEnd != null) {
            scratchRect.set(
                min(rStart.first, rEnd.first),
                min(rStart.second, rEnd.second),
                max(rStart.first, rEnd.first),
                max(rStart.second, rEnd.second)
            )
            canvas.drawRect(scratchRect, selectionFillPaint)
            canvas.drawRect(scratchRect, selectionPaint)
        }
    }

    private fun drawRuler(canvas: Canvas, pageWidth: Float) {
        canvas.save()
        canvas.rotate(rulerAngleDeg, pageWidth * 0.5f, rulerY)
        canvas.drawRoundRect(-200f, rulerY - 36f, pageWidth + 200f, rulerY + 36f, 8f, 8f, rulerPaint)
        var x = 0f
        while (x <= pageWidth) {
            val major = (x.toInt() % 100) == 0
            canvas.drawLine(x, rulerY - 36f, x, rulerY - (if (major) 14f else 24f), rulerTickPaint)
            if (major) {
                canvas.drawText("${x.toInt()}", x + 4f, rulerY + 14f, rulerTickPaint)
            }
            x += 25f
        }
        canvas.restore()
    }

    private fun drawTouchDebuggerOverlay(canvas: Canvas) {
        val markers = touchDebugger?.markers?.value.orEmpty()
        for (m in markers) {
            val color = when (m.state) {
                PointerVisualState.PRIMARY -> 0xFF16A34A.toInt()     // Green
                PointerVisualState.SECONDARY -> 0xFFF97316.toInt()   // Orange
                PointerVisualState.CANCELLED -> 0xFFDC2626.toInt()   // Red
                PointerVisualState.SELECTED -> 0xFF06B6D4.toInt()    // Cyan
                PointerVisualState.INACTIVE -> 0xFF64748B.toInt()    // Gray
            }

            if (settings.debugShowTrails && m.trail.size >= 2) {
                debugTrailPaint.color = (color and 0x00FFFFFF) or 0x99000000.toInt()
                val trailPath = Path().apply {
                    moveTo(m.trail[0].first, m.trail[0].second)
                    for (i in 1 until m.trail.size) {
                        lineTo(m.trail[i].first, m.trail[i].second)
                    }
                }
                canvas.drawPath(trailPath, debugTrailPaint)
            }

            // Contact size ellipse if available
            if (settings.debugShowContactSize && (m.touchMajor > 1f || m.size > 0.01f)) {
                val radius = max(24f, if (m.touchMajor > 1f) m.touchMajor * 0.5f else m.size * 180f)
                debugMarkerPaint.style = Paint.Style.STROKE
                debugMarkerPaint.strokeWidth = 3f
                debugMarkerPaint.color = color
                canvas.drawCircle(m.x, m.y, radius, debugMarkerPaint)
            }

            debugMarkerPaint.style = Paint.Style.FILL
            debugMarkerPaint.color = color
            canvas.drawCircle(m.x, m.y, 16f, debugMarkerPaint)

            val labelParts = buildList {
                if (settings.debugShowPointerIds) add("ID:${m.pointerId}")
                if (settings.debugShowCoordinates) add("(${m.x.toInt()},${m.y.toInt()})")
                if (settings.debugShowPressure) add("P:${"%.2f".format(m.pressure)}")
                if (settings.debugShowEventLabels) add(m.state.name)
            }
            if (labelParts.isNotEmpty()) {
                val label = labelParts.joinToString(" ")
                val tw = debugTextPaint.measureText(label)
                paperPaint.color = 0xCC0F172A.toInt()
                canvas.drawRoundRect(m.x + 20f, m.y - 34f, m.x + 34f + tw, m.y - 4f, 6f, 6f, paperPaint)
                canvas.drawText(label, m.x + 27f, m.y - 12f, debugTextPaint)
            }
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
            event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
        ) {
            touchDebugger?.recordMotionEvent(
                event = event,
                settings = settings,
                primaryPointerId = activeStrokePointerId,
                cancelledPointers = palmGuard.getCancelledPointers(),
                filteredOut = false,
                filterReason = null
            )
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val now = System.currentTimeMillis()

        // Track multi-touch tap gestures
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                maxPointersInGesture = 1
                gestureDownTimeMs = now
                gestureMovedBeyondSlop = false
                isPanningOrZooming = false
                lastTapDownPos.clear()
                lastTapDownPos[event.getPointerId(0)] = event.getX(0) to event.getY(0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointersInGesture = max(maxPointersInGesture, event.pointerCount)
                val pid = event.getPointerId(actionIndex)
                lastTapDownPos[pid] = event.getX(actionIndex) to event.getY(actionIndex)
                if (event.pointerCount == 2) {
                    lastPinchDist = spacing(event)
                    lastPinchMidX = (event.getX(0) + event.getX(1)) * 0.5f
                    lastPinchMidY = (event.getY(0) + event.getY(1)) * 0.5f
                    lastPinchAngle = angle(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val start = lastTapDownPos[pid]
                    if (start != null && hypot(event.getX(i) - start.first, event.getY(i) - start.second) > 22f * settings.gestureSensitivity) {
                        gestureMovedBeyondSlop = true
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                val elapsed = now - gestureDownTimeMs
                if (!gestureMovedBeyondSlop && elapsed <= settings.gestureTapDurationMs && !isPanningOrZooming) {
                    when (maxPointersInGesture) {
                        2 -> if (settings.gestureTwoFingerTap != GestureAction.NONE) {
                            listener?.onGestureAction(settings.gestureTwoFingerTap)
                        }
                        3 -> if (settings.gestureThreeFingerTap != GestureAction.NONE) {
                            listener?.onGestureAction(settings.gestureThreeFingerTap)
                        }
                        4 -> if (settings.gestureFourFingerTap != GestureAction.NONE) {
                            listener?.onGestureAction(settings.gestureFourFingerTap)
                        }
                    }
                }
            }
        }

        // Eyedropper mode
        if (eyedropperActive && actionMasked == MotionEvent.ACTION_DOWN) {
            val (cx, cy) = screenToCanvas(event.getX(0), event.getY(0))
            val picked = pickColorNear(cx, cy)
            listener?.onEyedropperColorPicked(picked)
            return true
        }

        // Multi-touch Pan / Pinch Zoom (when 2 pointers are active)
        if (event.pointerCount == 2 && (settings.gestureTwoFingerDragEnabled || settings.gesturePinchZoomEnabled)) {
            if (!settings.gestureConflictProtection || activeStrokePointerId == null || isPanningOrZooming) {
                handleTwoFingerTransform(event)
            }
        }

        val samples = (0 until event.pointerCount).map { idx ->
            PointerSample(
                pointerId = event.getPointerId(idx),
                pointerIndex = idx,
                x = event.getX(idx),
                y = event.getY(idx),
                pressure = event.getPressure(idx),
                size = event.getSize(idx),
                touchMajor = event.getTouchMajor(idx),
                touchMinor = event.getTouchMinor(idx),
                toolType = event.getToolType(idx)
            )
        }

        val decision = palmGuard.evaluate(actionMasked, actionIndex, samples, settings, now)
        listener?.onPalmGuardStatusChanged(palmGuard.getStatus(settings.palmGuardMode, now))

        val filteredOut = decision is PalmGuardDecision.Ignore || decision is PalmGuardDecision.CancelStrokeAndRecover
        val filterReason = when (decision) {
            is PalmGuardDecision.Ignore -> decision.reason
            is PalmGuardDecision.CancelStrokeAndRecover -> decision.reason
            is PalmGuardDecision.PassToGestureEngine -> decision.reason
            is PalmGuardDecision.Accept -> decision.note
        }

        touchDebugger?.recordMotionEvent(
            event = event,
            settings = settings,
            primaryPointerId = activeStrokePointerId,
            cancelledPointers = palmGuard.getCancelledPointers(),
            filteredOut = filteredOut,
            filterReason = filterReason
        )

        when (decision) {
            is PalmGuardDecision.CancelStrokeAndRecover -> {
                activeStrokePoints.clear()
                activeStrokePointerId = null
                invalidate()
                return true
            }
            is PalmGuardDecision.PassToGestureEngine -> {
                if (settings.gestureConflictProtection && activeStrokePointerId != null && !isPanningOrZooming) {
                    // Protect active stroke from accidental 2-finger hijack unless Mode C cancelled
                    return true
                }
                isPanningOrZooming = true
                return true
            }
            is PalmGuardDecision.Ignore -> {
                if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    finishActiveInputIfMatches(event, actionMasked, actionIndex)
                }
                return true
            }
            is PalmGuardDecision.Accept -> {
                if (isPanningOrZooming && event.pointerCount > 1) return true
                processAcceptedDrawingInput(event, actionMasked, decision.primaryPointerId)
                return true
            }
        }
    }

    private fun handleTwoFingerTransform(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) return
        val newDist = spacing(event)
        val midX = (event.getX(0) + event.getX(1)) * 0.5f
        val midY = (event.getY(0) + event.getY(1)) * 0.5f
        val newAngle = angle(event)

        if (lastPinchDist > 10f) {
            isPanningOrZooming = true
            // Cancel accidental dot from first finger before second landed
            if (activeStrokePoints.size <= 3) {
                activeStrokePoints.clear()
                activeStrokePointerId = null
            }

            if (settings.gestureTwoFingerDragEnabled) {
                panX += (midX - lastPinchMidX)
                panY += (midY - lastPinchMidY)
            }
            if (settings.gesturePinchZoomEnabled) {
                val scaleDelta = (newDist / lastPinchDist).coerceIn(0.8f, 1.25f)
                val nextZoom = (zoomScale * scaleDelta).coerceIn(0.25f, settings.maxZoomScale)
                val ratio = nextZoom / zoomScale
                panX = midX - (midX - panX) * ratio
                panY = midY - (midY - panY) * ratio
                zoomScale = nextZoom
            }
            if (settings.allowCanvasRotation) {
                var dAngle = newAngle - lastPinchAngle
                if (dAngle > 180f) dAngle -= 360f
                if (dAngle < -180f) dAngle += 360f
                if (kotlin.math.abs(dAngle) > 1.2f) {
                    canvasRotationDeg = (canvasRotationDeg + dAngle) % 360f
                }
            }
            listener?.onViewportChanged(zoomScale, panX, panY, canvasRotationDeg)
            invalidate()
        }
        lastPinchDist = newDist
        lastPinchMidX = midX
        lastPinchMidY = midY
        lastPinchAngle = newAngle
    }

    private fun processAcceptedDrawingInput(
        event: MotionEvent,
        actionMasked: Int,
        primaryPointerId: Int
    ) {
        val ptrIdx = event.findPointerIndex(primaryPointerId)
        if (ptrIdx < 0) return

        val (cx, cy) = screenToCanvas(event.getX(ptrIdx), event.getY(ptrIdx))
        val snapped = applyGridAndRulerSnap(cx, cy)

        // Selection Mode handling
        if (selectionMode != SelectionMode.NONE) {
            handleSelectionTouch(actionMasked, snapped.first, snapped.second)
            return
        }

        // Check active layer lock
        val activeLayer = currentPage.layers.firstOrNull { it.id == activeLayerId }
        if (activeLayer?.locked == true) return

        // Stroke / Area / Lasso Eraser modes
        if (toolConfig.toolType == PenToolType.ERASER_STROKE || toolConfig.toolType == PenToolType.ERASER_AREA) {
            handleVectorEraser(actionMasked, snapped.first, snapped.second)
            return
        }
        if (toolConfig.toolType == PenToolType.ERASER_LASSO) {
            handleLassoEraser(actionMasked, snapped.first, snapped.second)
            return
        }

        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeStrokePointerId = primaryPointerId
                activeStrokePoints.clear()
                activeStrokePoints.add(
                    StrokePoint(
                        x = snapped.first,
                        y = snapped.second,
                        timestamp = event.eventTime,
                        pressure = event.getPressure(ptrIdx),
                        tiltX = event.getAxisValue(MotionEvent.AXIS_TILT, ptrIdx),
                        orientation = event.getOrientation(ptrIdx),
                        toolType = event.getToolType(ptrIdx)
                    )
                )
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeStrokePointerId != primaryPointerId) return
                // Consume batched historical points for smooth high-rate stylus input
                val historyCount = event.historySize
                for (h in 0 until historyCount) {
                    val hx = event.getHistoricalX(ptrIdx, h)
                    val hy = event.getHistoricalY(ptrIdx, h)
                    val (chx, chy) = screenToCanvas(hx, hy)
                    val s = applyGridAndRulerSnap(chx, chy)
                    activeStrokePoints.add(
                        StrokePoint(
                            x = s.first,
                            y = s.second,
                            timestamp = event.getHistoricalEventTime(h),
                            pressure = event.getHistoricalPressure(ptrIdx, h),
                            toolType = event.getToolType(ptrIdx)
                        )
                    )
                }
                activeStrokePoints.add(
                    StrokePoint(
                        x = snapped.first,
                        y = snapped.second,
                        timestamp = event.eventTime,
                        pressure = event.getPressure(ptrIdx),
                        tiltX = event.getAxisValue(MotionEvent.AXIS_TILT, ptrIdx),
                        orientation = event.getOrientation(ptrIdx),
                        toolType = event.getToolType(ptrIdx)
                    )
                )
                invalidate()
            }
        }
    }

    private fun finishActiveInputIfMatches(event: MotionEvent, actionMasked: Int, actionIndex: Int) {
        if (selectionMode != SelectionMode.NONE) {
            handleSelectionTouch(MotionEvent.ACTION_UP, 0f, 0f)
        }
        if (toolConfig.toolType == PenToolType.ERASER_LASSO && actionMasked == MotionEvent.ACTION_UP) {
            handleLassoEraser(MotionEvent.ACTION_UP, 0f, 0f)
        }

        val liftedId = event.getPointerId(actionIndex)
        if (actionMasked == MotionEvent.ACTION_UP || liftedId == activeStrokePointerId) {
            if (activeStrokePoints.isNotEmpty()) {
                val committed = VectorStroke(
                    layerId = activeLayerId,
                    toolType = toolConfig.toolType,
                    colorArgb = toolConfig.colorArgb,
                    width = toolConfig.width,
                    opacity = toolConfig.opacity,
                    smoothing = toolConfig.smoothing * settings.globalSmoothingMultiplier,
                    texture = toolConfig.texture,
                    pressureResponse = toolConfig.pressureResponse,
                    tiltResponse = toolConfig.tiltResponse,
                    points = activeStrokePoints.toList()
                )
                activeStrokePoints.clear()
                activeStrokePointerId = null
                listener?.onStrokeCommitted(committed)
            }
            activeStrokePointerId = null
            invalidate()
        }
    }

    private fun handleVectorEraser(actionMasked: Int, cx: Float, cy: Float) {
        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_MOVE) {
            val radius = toolConfig.width
            val hitIds = currentPage.strokes
                .filter { it.layerId == activeLayerId && !it.locked }
                .filter { StrokeRenderer.hitTestStroke(it, cx, cy, radius) }
                .map { it.id }
            if (hitIds.isNotEmpty()) {
                listener?.onStrokesErased(hitIds)
            }
        }
    }

    private fun handleLassoEraser(actionMasked: Int, cx: Float, cy: Float) {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lassoPoints.clear()
                lassoPoints.add(cx to cy)
            }
            MotionEvent.ACTION_MOVE -> {
                lassoPoints.add(cx to cy)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (lassoPoints.size >= 3) {
                    val toErase = currentPage.strokes
                        .filter { it.layerId == activeLayerId && !it.locked }
                        .filter { stroke ->
                            stroke.points.any { pt ->
                                pointInPolygon(pt.x + stroke.transform.translateX, pt.y + stroke.transform.translateY, lassoPoints)
                            }
                        }
                        .map { it.id }
                    if (toErase.isNotEmpty()) listener?.onStrokesErased(toErase)
                }
                lassoPoints.clear()
                invalidate()
            }
        }
    }

    private fun handleSelectionTouch(actionMasked: Int, cx: Float, cy: Float) {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isPointInsideCurrentSelection(cx, cy)) {
                    draggingSelection = true
                    lastDragCanvasPoint = cx to cy
                    return
                }
                draggingSelection = false
                when (selectionMode) {
                    SelectionMode.LASSO -> {
                        lassoPoints.clear()
                        lassoPoints.add(cx to cy)
                    }
                    SelectionMode.RECTANGLE -> {
                        rectSelectStart = cx to cy
                        rectSelectCurrent = cx to cy
                    }
                    SelectionMode.SINGLE_OBJECT -> {
                        val hitText = currentPage.textObjects.lastOrNull {
                            cx in it.x..(it.x + it.width) && cy in it.y..(it.y + it.height)
                        }
                        if (hitText != null) {
                            listener?.onSelectionChanged(emptySet(), setOf(hitText.id), emptySet())
                            listener?.onTextObjectTapped(hitText)
                            return
                        }
                        val hitImg = currentPage.imageObjects.lastOrNull {
                            cx in it.x..(it.x + it.width) && cy in it.y..(it.y + it.height)
                        }
                        if (hitImg != null) {
                            listener?.onSelectionChanged(emptySet(), emptySet(), setOf(hitImg.id))
                            return
                        }
                        val hitStroke = currentPage.strokes.lastOrNull {
                            StrokeRenderer.hitTestStroke(it, cx, cy, 24f)
                        }
                        listener?.onSelectionChanged(
                            hitStroke?.let { setOf(it.id) } ?: emptySet(),
                            emptySet(),
                            emptySet()
                        )
                    }
                    SelectionMode.NONE -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingSelection) {
                    val prev = lastDragCanvasPoint
                    if (prev != null) {
                        val dx = cx - prev.first
                        val dy = cy - prev.second
                        lastDragCanvasPoint = cx to cy
                        listener?.onSelectionMoved(dx, dy)
                    }
                    return
                }
                if (selectionMode == SelectionMode.LASSO) {
                    lassoPoints.add(cx to cy)
                    invalidate()
                } else if (selectionMode == SelectionMode.RECTANGLE) {
                    rectSelectCurrent = cx to cy
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (draggingSelection) {
                    draggingSelection = false
                    lastDragCanvasPoint = null
                    return
                }
                if (selectionMode == SelectionMode.LASSO && lassoPoints.size >= 3) {
                    val sIds = currentPage.strokes.filter { s ->
                        s.points.any { pt -> pointInPolygon(pt.x + s.transform.translateX, pt.y + s.transform.translateY, lassoPoints) }
                    }.map { it.id }.toSet()
                    val tIds = currentPage.textObjects.filter { t ->
                        pointInPolygon(t.x + t.width * 0.5f, t.y + t.height * 0.5f, lassoPoints)
                    }.map { it.id }.toSet()
                    val iIds = currentPage.imageObjects.filter { img ->
                        pointInPolygon(img.x + img.width * 0.5f, img.y + img.height * 0.5f, lassoPoints)
                    }.map { it.id }.toSet()
                    listener?.onSelectionChanged(sIds, tIds, iIds)
                    lassoPoints.clear()
                    invalidate()
                } else if (selectionMode == SelectionMode.RECTANGLE && rectSelectStart != null && rectSelectCurrent != null) {
                    val r = RectF(
                        min(rectSelectStart!!.first, rectSelectCurrent!!.first),
                        min(rectSelectStart!!.second, rectSelectCurrent!!.second),
                        max(rectSelectStart!!.first, rectSelectCurrent!!.first),
                        max(rectSelectStart!!.second, rectSelectCurrent!!.second)
                    )
                    val sIds = currentPage.strokes.filter { s ->
                        s.points.any { pt -> r.contains(pt.x + s.transform.translateX, pt.y + s.transform.translateY) }
                    }.map { it.id }.toSet()
                    val tIds = currentPage.textObjects.filter { t ->
                        RectF.intersects(r, RectF(t.x, t.y, t.x + t.width, t.y + t.height))
                    }.map { it.id }.toSet()
                    val iIds = currentPage.imageObjects.filter { img ->
                        RectF.intersects(r, RectF(img.x, img.y, img.x + img.width, img.y + img.height))
                    }.map { it.id }.toSet()
                    listener?.onSelectionChanged(sIds, tIds, iIds)
                    rectSelectStart = null
                    rectSelectCurrent = null
                    invalidate()
                }
            }
        }
    }

    private fun isPointInsideCurrentSelection(cx: Float, cy: Float): Boolean {
        if (selectedStrokeIds.isEmpty() && selectedTextIds.isEmpty() && selectedImageIds.isEmpty()) return false
        currentPage.strokes.filter { it.id in selectedStrokeIds }.forEach { s ->
            StrokeRenderer.computeBounds(s.points, scratchRect)
            scratchRect.offset(s.transform.translateX, s.transform.translateY)
            scratchRect.inset(-16f, -16f)
            if (scratchRect.contains(cx, cy)) return true
        }
        currentPage.textObjects.filter { it.id in selectedTextIds }.forEach { t ->
            if (cx in t.x..(t.x + t.width) && cy in t.y..(t.y + t.height)) return true
        }
        currentPage.imageObjects.filter { it.id in selectedImageIds }.forEach { img ->
            if (cx in img.x..(img.x + img.width) && cy in img.y..(img.y + img.height)) return true
        }
        return false
    }

    private fun pickColorNear(cx: Float, cy: Float): Long {
        val hit = currentPage.strokes.lastOrNull { StrokeRenderer.hitTestStroke(it, cx, cy, 28f) }
        return hit?.colorArgb ?: currentPage.backgroundColorArgb
    }

    private fun applyGridAndRulerSnap(x: Float, y: Float): Pair<Float, Float> {
        var nx = x
        var ny = y
        if (rulerVisible && kotlin.math.abs(ny - rulerY) < 28f) {
            ny = rulerY - 36f
        } else if (settings.snapToGrid) {
            val spacing = settings.gridSpacingDp.coerceIn(12, 72) * resources.displayMetrics.density
            nx = (nx / spacing).roundToInt() * spacing
            ny = (ny / spacing).roundToInt() * spacing
        }
        return nx to ny
    }

    private fun buildViewportMatrix(out: Matrix) {
        out.reset()
        out.postScale(zoomScale, zoomScale)
        if (canvasRotationDeg != 0f) {
            out.postRotate(canvasRotationDeg, getPageWidthPx() * 0.5f * zoomScale, getPageHeightPx() * 0.5f * zoomScale)
        }
        out.postTranslate(panX, panY)
    }

    private fun screenToCanvas(sx: Float, sy: Float): Pair<Float, Float> {
        buildViewportMatrix(workMatrix)
        workMatrix.invert(inverseMatrix)
        scratchPoint[0] = sx
        scratchPoint[1] = sy
        inverseMatrix.mapPoints(scratchPoint)
        return scratchPoint[0] to scratchPoint[1]
    }

    private fun getPageWidthPx(): Float {
        val base = if (currentPage.sizePreset.name == "CUSTOM") currentPage.customWidthPt else currentPage.sizePreset.widthPt
        val hBase = if (currentPage.sizePreset.name == "CUSTOM") currentPage.customHeightPt else currentPage.sizePreset.heightPt
        val w = if (currentPage.orientation == PageOrientation.PORTRAIT) base else hBase
        return w * 2f
    }

    private fun getPageHeightPx(): Float {
        val base = if (currentPage.sizePreset.name == "CUSTOM") currentPage.customWidthPt else currentPage.sizePreset.widthPt
        val hBase = if (currentPage.sizePreset.name == "CUSTOM") currentPage.customHeightPt else currentPage.sizePreset.heightPt
        val h = if (currentPage.orientation == PageOrientation.PORTRAIT) hBase else base
        return if (canvasMode == CanvasMode.LONG_VERTICAL) h * 4f else h * 2f
    }

    private fun spacing(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return hypot(dx, dy)
    }

    private fun angle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun pointInPolygon(x: Float, y: Float, poly: List<Pair<Float, Float>>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].first
            val yi = poly[i].second
            val xj = poly[j].first
            val yj = poly[j].second
            val intersect = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { it != 0f } ?: 0.0001f) + xi)
            if (intersect) inside = !inside
            j = i
        }
        return inside
    }
}
