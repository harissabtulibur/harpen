package com.harpen.studio.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.harpen.studio.canvas.HarpenCanvasListener
import com.harpen.studio.canvas.HarpenCanvasView
import com.harpen.studio.data.settings.GestureAction
import com.harpen.studio.input.PalmGuardStatus
import com.harpen.studio.model.*
import com.harpen.studio.ui.state.HarpenViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchDebuggerScreen(
    viewModel: HarpenViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val events by viewModel.touchDebugger.events.collectAsState()
    val markers by viewModel.touchDebugger.markers.collectAsState()

    var testPage by remember { mutableStateOf(NotePage(template = PageTemplate.GRID)) }
    var testStatus by remember { mutableStateOf(PalmGuardStatus(mode = settings.palmGuardMode)) }
    var canvasRef by remember { mutableStateOf<HarpenCanvasView?>(null) }

    LaunchedEffect(Unit) {
        if (!settings.touchDebuggerEnabled) {
            viewModel.updateSettings { it.copy(touchDebuggerEnabled = true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HARPEN Touch Debugger & PalmGuard Lab") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = {
                        viewModel.updateSettings { it.copy(debugLoggingPaused = !it.debugLoggingPaused) }
                    }) {
                        Text(if (settings.debugLoggingPaused) "Resume Log" else "Pause Log")
                    }
                    TextButton(onClick = {
                        viewModel.touchDebugger.clearLogs()
                        testPage = NotePage(template = PageTemplate.GRID)
                        canvasRef?.cancelUnfinishedStrokeSafely()
                    }) {
                        Text("Clear")
                    }
                    TextButton(onClick = {
                        val csv = viewModel.touchDebugger.exportLogsAsCsv()
                        val file = File(context.cacheDir, "exports/touch_log_${System.currentTimeMillis()}.csv").apply {
                            parentFile?.mkdirs()
                            writeText(csv)
                        }
                        viewModel.pdfEngine.shareFileViaSharesheet(file, "text/csv", "Export Touch Debugger CSV")
                    }) {
                        Text("Export CSV")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // PalmGuard Mode Selector + Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PalmGuardMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.palmGuardMode == mode,
                        onClick = { viewModel.updateSettings { it.copy(palmGuardMode = mode) } },
                        label = { Text("Mode ${mode.code}") }
                    )
                }
                Spacer(Modifier.weight(1f))
                LegendDot(Color(0xFF16A34A), "Primary")
                LegendDot(Color(0xFFF97316), "Secondary")
                LegendDot(Color(0xFFDC2626), "Cancelled")
                LegendDot(Color(0xFF06B6D4), "Selected")
                LegendDot(Color(0xFF64748B), "Inactive")
            }

            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Live Native Drawing & Touch Surface
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            HarpenCanvasView(ctx).apply {
                                touchDebugger = viewModel.touchDebugger
                                canvasRef = this
                                listener = object : HarpenCanvasListener {
                                    override fun onStrokeCommitted(stroke: VectorStroke) {
                                        testPage = testPage.copy(strokes = testPage.strokes + stroke)
                                    }
                                    override fun onStrokesErased(strokeIds: List<String>) {}
                                    override fun onGestureAction(action: GestureAction) {}
                                    override fun onViewportChanged(zoom: Float, panX: Float, panY: Float, rotationDeg: Float) {}
                                    override fun onSelectionChanged(selectedStrokeIds: Set<String>, selectedTextIds: Set<String>, selectedImageIds: Set<String>) {}
                                    override fun onSelectionMoved(dx: Float, dy: Float) {}
                                    override fun onTextObjectTapped(textObject: TextObject) {}
                                    override fun onEyedropperColorPicked(colorArgb: Long) {}
                                    override fun onPalmGuardStatusChanged(status: PalmGuardStatus) {
                                        testStatus = status
                                    }
                                }
                            }
                        },
                        update = { view ->
                            view.bindState(
                                page = testPage,
                                mode = CanvasMode.DRAWING,
                                layerId = "layer_ink",
                                tool = ToolConfig(PenToolType.BALLPOINT, width = 4f, colorArgb = 0xFF1D4ED8),
                                selection = SelectionMode.NONE,
                                eyedropper = false,
                                ruler = false,
                                zoom = 1f,
                                panX = 0f,
                                panY = 0f,
                                rotationDeg = 0f,
                                harpenSettings = settings,
                                selectedStrokes = emptySet(),
                                selectedTexts = emptySet(),
                                selectedImages = emptySet(),
                                pdfBackdrop = null
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Telemetry Panel & Event Stream
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "${settings.palmGuardMode.displayName} · Accepted: ${testStatus.acceptedEventsCount} · Filtered: ${testStatus.rejectedEventsCount}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    testStatus.lastConflictReason?.let { reason ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            TextButton(onClick = { canvasRef?.cancelUnfinishedStrokeSafely() }) { Text("Reset") }
                        }
                    }

                    // Active pointer cards
                    markers.forEach { m ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { viewModel.touchDebugger.selectPointer(m.pointerId) }
                        ) {
                            Text(
                                text = "PtrID=${m.pointerId} idx=${m.pointerIndex} ${m.state} (${m.x.toInt()},${m.y.toInt()}) P=${"%.2f".format(m.pressure)} Sz=${"%.2f".format(m.size)} Maj=${m.touchMajor.toInt()}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.fillMaxWidth().padding(8.dp)
                            )
                        }
                    }

                    HorizontalDivider()
                    Text("Event Log (${events.size} / ${settings.debugLogCapacity})", style = MaterialTheme.typography.labelMedium)

                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(events.asReversed(), key = { it.id }) { ev ->
                            val bg = if (ev.filteredOut) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            Text(
                                text = "#${ev.id} ${ev.actionName}[${ev.actionIndex}] id=${ev.pointerId} cnt=${ev.pointerCount} ${ev.toolTypeName} (${ev.x.toInt()},${ev.y.toInt()}) hist=${ev.historySize} dev=${ev.deviceId} ${ev.filterReason ?: ""}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(bg, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
