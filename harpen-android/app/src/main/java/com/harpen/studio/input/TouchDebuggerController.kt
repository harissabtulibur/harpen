package com.harpen.studio.input

import android.view.MotionEvent
import com.harpen.studio.data.settings.HarpenSettings
import com.harpen.studio.model.PointerVisualState
import com.harpen.studio.model.TouchDebugEvent
import com.harpen.studio.model.TouchMarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class TouchDebuggerController {

    private val sequence = AtomicLong(1L)
    private val logBuffer = ArrayDeque<TouchDebugEvent>()
    private val activeMarkers = LinkedHashMap<Int, TouchMarker>()
    private var selectedPointerId: Int? = null

    private val _events = MutableStateFlow<List<TouchDebugEvent>>(emptyList())
    val events: StateFlow<List<TouchDebugEvent>> = _events.asStateFlow()

    private val _markers = MutableStateFlow<List<TouchMarker>>(emptyList())
    val markers: StateFlow<List<TouchMarker>> = _markers.asStateFlow()

    fun selectPointer(pointerId: Int?) {
        selectedPointerId = pointerId
        publishMarkers()
    }

    fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.clear()
            _events.value = emptyList()
        }
    }

    fun clearMarkers() {
        synchronized(activeMarkers) {
            activeMarkers.clear()
            _markers.value = emptyList()
        }
    }

    fun recordMotionEvent(
        event: MotionEvent,
        settings: HarpenSettings,
        primaryPointerId: Int?,
        cancelledPointers: Set<Int>,
        filteredOut: Boolean,
        filterReason: String?
    ) {
        if (!settings.touchDebuggerEnabled) return

        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val actionName = actionToString(actionMasked)
        val isHover = actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
            actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
            actionMasked == MotionEvent.ACTION_HOVER_EXIT

        val pointerCount = event.pointerCount
        val now = event.eventTime

        for (i in 0 until pointerCount) {
            val pointerId = event.getPointerId(i)
            val x = event.getX(i)
            val y = event.getY(i)
            val rawX = if (i == 0) event.rawX else x
            val rawY = if (i == 0) event.rawY else y
            val pressure = event.getPressure(i)
            val size = event.getSize(i)
            val touchMajor = event.getTouchMajor(i)
            val touchMinor = event.getTouchMinor(i)
            val orientation = event.getOrientation(i)
            val toolType = event.getToolType(i)
            val isPrimary = pointerId == primaryPointerId

            val visualState = when {
                pointerId == selectedPointerId -> PointerVisualState.SELECTED
                cancelledPointers.contains(pointerId) || actionMasked == MotionEvent.ACTION_CANCEL -> PointerVisualState.CANCELLED
                actionMasked == MotionEvent.ACTION_UP || (actionMasked == MotionEvent.ACTION_POINTER_UP && i == actionIndex) || isHover -> PointerVisualState.INACTIVE
                isPrimary -> PointerVisualState.PRIMARY
                else -> PointerVisualState.SECONDARY
            }

            synchronized(activeMarkers) {
                val existingTrail = activeMarkers[pointerId]?.trail.orEmpty()
                val nextTrail = if (settings.debugShowTrails && actionMasked == MotionEvent.ACTION_MOVE) {
                    (existingTrail + (x to y)).takeLast(28)
                } else if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                    listOf(x to y)
                } else {
                    existingTrail
                }

                activeMarkers[pointerId] = TouchMarker(
                    pointerId = pointerId,
                    pointerIndex = i,
                    x = x,
                    y = y,
                    rawX = rawX,
                    rawY = rawY,
                    pressure = pressure,
                    size = size,
                    touchMajor = touchMajor,
                    touchMinor = touchMinor,
                    orientation = orientation,
                    toolType = toolType,
                    state = visualState,
                    trail = nextTrail
                )

                if (actionMasked == MotionEvent.ACTION_UP ||
                    actionMasked == MotionEvent.ACTION_CANCEL ||
                    actionMasked == MotionEvent.ACTION_HOVER_EXIT
                ) {
                    // Keep brief inactive markers clear on next DOWN; mark inactive now
                    activeMarkers[pointerId] = activeMarkers[pointerId]!!.copy(state = visualState)
                }
            }

            val shouldLogPointer = (i == actionIndex) ||
                actionMasked == MotionEvent.ACTION_MOVE ||
                actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                actionMasked == MotionEvent.ACTION_CANCEL

            if (!settings.debugLoggingPaused && shouldLogPointer) {
                val debugEvent = TouchDebugEvent(
                    id = sequence.getAndIncrement(),
                    timestampMs = now,
                    actionName = actionName,
                    actionIndex = actionIndex,
                    pointerId = pointerId,
                    pointerIndex = i,
                    pointerCount = pointerCount,
                    isPrimary = isPrimary,
                    toolTypeName = toolTypeToString(toolType),
                    x = x,
                    y = y,
                    rawX = rawX,
                    rawY = rawY,
                    pressure = pressure,
                    size = size,
                    touchMajor = touchMajor,
                    touchMinor = touchMinor,
                    orientation = orientation,
                    deviceId = event.deviceId,
                    source = event.source,
                    flags = event.flags,
                    historySize = event.historySize,
                    filteredOut = filteredOut,
                    filterReason = filterReason
                )

                synchronized(logBuffer) {
                    val cap = settings.debugLogCapacity.coerceIn(50, 2000)
                    while (logBuffer.size >= cap) {
                        logBuffer.removeFirst()
                    }
                    logBuffer.addLast(debugEvent)
                }
            }
        }

        if (actionMasked == MotionEvent.ACTION_DOWN && pointerCount == 1) {
            // Prune stale inactive markers from previous gesture
            synchronized(activeMarkers) {
                val activeId = event.getPointerId(0)
                activeMarkers.keys.toList().forEach { id ->
                    if (id != activeId) activeMarkers.remove(id)
                }
            }
        }

        publishMarkers()
        if (!settings.debugLoggingPaused) {
            synchronized(logBuffer) {
                _events.value = logBuffer.toList()
            }
        }
    }

    private fun publishMarkers() {
        synchronized(activeMarkers) {
            _markers.value = activeMarkers.values.toList()
        }
    }

    fun exportLogsAsCsv(): String {
        val snapshot = synchronized(logBuffer) { logBuffer.toList() }
        val sb = StringBuilder()
        sb.appendLine("id,timestampMs,action,actionIndex,pointerId,pointerIndex,pointerCount,isPrimary,toolType,x,y,rawX,rawY,pressure,size,touchMajor,touchMinor,orientation,deviceId,source,flags,historySize,filteredOut,filterReason")
        snapshot.forEach { e ->
            sb.appendLine("${e.id},${e.timestampMs},${e.actionName},${e.actionIndex},${e.pointerId},${e.pointerIndex},${e.pointerCount},${e.isPrimary},${e.toolTypeName},${e.x},${e.y},${e.rawX},${e.rawY},${e.pressure},${e.size},${e.touchMajor},${e.touchMinor},${e.orientation},${e.deviceId},${e.source},${e.flags},${e.historySize},${e.filteredOut},\"${e.filterReason.orEmpty()}\"")
        }
        return sb.toString()
    }

    companion object {
        fun actionToString(actionMasked: Int): String = when (actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
            MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
            MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
            else -> "ACTION_$actionMasked"
        }

        fun toolTypeToString(toolType: Int): String = when (toolType) {
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER/CAPACITIVE"
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_ERASER -> "STYLUS_ERASER"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            else -> "UNKNOWN($toolType)"
        }
    }
}
