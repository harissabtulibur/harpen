package com.harpen.studio.ui.workspace

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.harpen.studio.canvas.HarpenCanvasListener
import com.harpen.studio.canvas.HarpenCanvasView
import com.harpen.studio.data.settings.GestureAction
import com.harpen.studio.data.settings.HandednessMode
import com.harpen.studio.input.PalmGuardStatus
import com.harpen.studio.model.*
import com.harpen.studio.ui.state.HarpenViewModel
import kotlinx.coroutines.launch

@Composable
fun WorkspaceScreen(
    viewModel: HarpenViewModel,
    onBackHome: () -> Unit,
    onOpenCameraOcr: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTouchDebugger: () -> Unit
) {
    val doc by viewModel.currentDoc.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val toolConfig by viewModel.toolConfig.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedStrokes by viewModel.selectedStrokeIds.collectAsState()
    val selectedTexts by viewModel.selectedTextIds.collectAsState()
    val selectedImages by viewModel.selectedImageIds.collectAsState()
    val eyedropperActive by viewModel.eyedropperActive.collectAsState()
    val rulerVisible by viewModel.rulerVisible.collectAsState()
    val pdfBackdrop by viewModel.pdfBackdrop.collectAsState()
    val palmGuardStatus by viewModel.palmGuardStatus.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    var sidebarVisible by remember(settings.sidebarCollapsedByDefault) { mutableStateOf(!settings.sidebarCollapsedByDefault) }
    var toolbarVisible by remember(settings.toolbarCollapsedByDefault) { mutableStateOf(!settings.toolbarCollapsedByDefault) }
    var distractionFree by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showToolPopover by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var editingTextObj by remember { mutableStateOf<TextObject?>(null) }
    var showNewTextDialog by remember { mutableStateOf(false) }

    val activeDoc = doc
    if (activeDoc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val activePage = activeDoc.pages.getOrElse(activeDoc.activePageIndex) { activeDoc.pages.first() }
    var canvasViewRef by remember { mutableStateOf<HarpenCanvasView?>(null) }

    // Safely cancel unfinished strokes when screen orientation changes
    LaunchedEffect(config.orientation) {
        canvasViewRef?.cancelUnfinishedStrokeSafely()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importImageFromUri(uri)
    }

    val saveHarpenAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.harpen.project")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                viewModel.safManager.exportDocumentToUri(activeDoc, uri).onSuccess {
                    viewModel.showStatus("Saved .harpen project")
                }.onFailure {
                    viewModel.showStatus("Save As failed: ${it.message}")
                }
            }
        }
    }

    val latestToolConfig by rememberUpdatedState(toolConfig)
    val latestSettings by rememberUpdatedState(settings)
    val latestDoc by rememberUpdatedState(activeDoc)

    val canvasListener = remember {
        object : HarpenCanvasListener {
            override fun onStrokeCommitted(stroke: VectorStroke) {
                viewModel.addStroke(stroke)
            }
            override fun onStrokesErased(strokeIds: List<String>) {
                viewModel.eraseStrokes(strokeIds)
            }
            override fun onGestureAction(action: GestureAction) {
                when (action) {
                    GestureAction.UNDO -> viewModel.undo()
                    GestureAction.REDO -> viewModel.redo()
                    GestureAction.TOGGLE_ERASER -> {
                        val next = if (latestToolConfig.toolType.isEraser) latestSettings.defaultPenTool else PenToolType.ERASER_STROKE
                        viewModel.selectTool(next)
                    }
                    GestureAction.LASSO_SELECT -> viewModel.setSelectionMode(SelectionMode.LASSO)
                    GestureAction.TOGGLE_UI -> distractionFree = !distractionFree
                    GestureAction.NEXT_PAGE -> viewModel.switchPage(latestDoc.activePageIndex + 1)
                    GestureAction.PREV_PAGE -> viewModel.switchPage(latestDoc.activePageIndex - 1)
                    else -> Unit
                }
            }
            override fun onViewportChanged(zoom: Float, panX: Float, panY: Float, rotationDeg: Float) {
                viewModel.updateViewport(zoom, panX, panY, rotationDeg)
            }
            override fun onSelectionChanged(selectedStrokeIds: Set<String>, selectedTextIds: Set<String>, selectedImageIds: Set<String>) {
                viewModel.setSelection(selectedStrokeIds, selectedTextIds, selectedImageIds)
            }
            override fun onSelectionMoved(dx: Float, dy: Float) {
                viewModel.moveSelectedObjects(dx, dy)
            }
            override fun onTextObjectTapped(textObject: TextObject) {
                editingTextObj = textObject
            }
            override fun onEyedropperColorPicked(colorArgb: Long) {
                viewModel.updateToolConfig { it.copy(colorArgb = colorArgb) }
                viewModel.toggleEyedropper()
            }
            override fun onPalmGuardStatusChanged(status: PalmGuardStatus) {
                viewModel.onPalmGuardStatus(status)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Collapsible Left Sidebar (Notebook Pages & Quick File Actions)
            if (!distractionFree && sidebarVisible && settings.handedness == HandednessMode.RIGHT) {
                PageSidebarPanel(
                    doc = activeDoc,
                    isLandscape = isLandscape,
                    onBackHome = {
                        viewModel.saveCurrentDocumentNow()
                        onBackHome()
                    },
                    onSelectPage = { viewModel.switchPage(it) },
                    onAddPage = { viewModel.addPage() },
                    onDuplicatePage = { viewModel.duplicateCurrentPage() },
                    onDeletePage = { viewModel.deleteCurrentPage() },
                    onToggleBookmark = { viewModel.toggleCurrentPageBookmark() },
                    onCollapse = { sidebarVisible = false }
                )
            }

            // Primary Canvas Surface
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                AndroidView(
                    factory = { ctx ->
                        HarpenCanvasView(ctx).apply {
                            touchDebugger = viewModel.touchDebugger
                            listener = canvasListener
                            canvasViewRef = this
                        }
                    },
                    update = { view ->
                        view.listener = canvasListener
                        view.touchDebugger = viewModel.touchDebugger
                        view.bindState(
                            page = activePage,
                            mode = activeDoc.canvasMode,
                            layerId = activeDoc.activeLayerId,
                            tool = toolConfig,
                            selection = selectionMode,
                            eyedropper = eyedropperActive,
                            ruler = rulerVisible,
                            zoom = activeDoc.zoomScale,
                            panX = activeDoc.panX,
                            panY = activeDoc.panY,
                            rotationDeg = activeDoc.canvasRotationDeg,
                            harpenSettings = settings,
                            selectedStrokes = selectedStrokes,
                            selectedTexts = selectedTexts,
                            selectedImages = selectedImages,
                            pdfBackdrop = pdfBackdrop
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Floating Menu restore pill when sidebar is hidden
                if (!distractionFree && !sidebarVisible) {
                    SmallFloatingActionButton(
                        onClick = { sidebarVisible = true },
                        modifier = Modifier
                            .align(if (settings.handedness == HandednessMode.RIGHT) Alignment.TopStart else Alignment.TopEnd)
                            .padding(14.dp)
                    ) {
                        Icon(Icons.Outlined.Menu, contentDescription = "Show Sidebar")
                    }
                }

                // Floating Tools restore pill when toolbar is hidden
                if (!distractionFree && !toolbarVisible) {
                    SmallFloatingActionButton(
                        onClick = { toolbarVisible = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp)
                    ) {
                        Icon(Icons.Outlined.Build, contentDescription = "Show Tools")
                    }
                }

                // Compact Floating Toolbar
                if (!distractionFree && toolbarVisible) {
                    FloatingWorkspaceToolbar(
                        modifier = Modifier
                            .align(if (isLandscape) Alignment.TopCenter else Alignment.BottomCenter)
                            .padding(12.dp),
                        doc = activeDoc,
                        toolConfig = toolConfig,
                        selectionMode = selectionMode,
                        canUndo = viewModel.canUndo,
                        canRedo = viewModel.canRedo,
                        rulerVisible = rulerVisible,
                        onUndo = { viewModel.undo() },
                        onRedo = { viewModel.redo() },
                        onSelectTool = { viewModel.selectTool(it) },
                        onOpenToolConfig = { showToolPopover = !showToolPopover },
                        onSelectSelectionMode = { viewModel.setSelectionMode(it) },
                        onInsertText = { showNewTextDialog = true },
                        onImportImage = { imagePicker.launch(arrayOf("image/*")) },
                        onOpenCameraOcr = onOpenCameraOcr,
                        onConvertHandwriting = {
                            scope.launch {
                                val res = viewModel.ocrManager.recognizeHandwritingOffline(activePage.strokes)
                                res.onSuccess { text ->
                                    if (text.isNotBlank()) {
                                        viewModel.addOrUpdateTextObject(TextObject(layerId = activeDoc.activeLayerId, text = text))
                                        viewModel.showStatus("Converted handwriting to editable text")
                                    }
                                }.onFailure { err ->
                                    viewModel.showStatus(err.message ?: "Handwriting recognition unavailable offline")
                                }
                            }
                        },
                        onToggleRuler = { viewModel.toggleRuler() },
                        onToggleLayers = { showLayers = !showLayers },
                        onOpenTemplates = { showTemplateDialog = true },
                        onOpenExport = { showExportDialog = true },
                        onToggleDistractionFree = { distractionFree = true },
                        onOpenSettings = onOpenSettings,
                        onCollapseToolbar = { toolbarVisible = false }
                    )
                }

                // Selection Action Bar when objects are selected
                if (!distractionFree && (selectedStrokes.isNotEmpty() || selectedTexts.isNotEmpty() || selectedImages.isNotEmpty())) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = if (!isLandscape && toolbarVisible) 84.dp else 18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { viewModel.copySelectedObjects(false) }) { Text("Copy") }
                            TextButton(onClick = { viewModel.copySelectedObjects(true) }) { Text("Cut") }
                            TextButton(onClick = { viewModel.pasteClipboard() }) { Text("Paste") }
                            TextButton(onClick = { viewModel.duplicateSelectedObjects() }) { Text("Duplicate") }
                            TextButton(onClick = { viewModel.rotateSelectedObjects(15f) }) { Text("Rotate 15°") }
                            TextButton(onClick = { viewModel.flipSelectedObjects(true) }) { Text("Flip H") }
                            TextButton(onClick = { viewModel.flipSelectedObjects(false) }) { Text("Flip V") }
                            TextButton(onClick = { viewModel.recolorSelectedStrokes(toolConfig.colorArgb) }) { Text("Recolor") }
                            TextButton(onClick = { viewModel.toggleLockSelectedObjects() }) { Text("Lock") }
                            TextButton(onClick = { viewModel.deleteSelectedObjects() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }

                // Tool Config & Color Wheel Popover
                if (!distractionFree && showToolPopover) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 76.dp, end = 16.dp)
                    ) {
                        ToolSettingsPopover(
                            toolConfig = toolConfig,
                            recentColorsCsv = settings.recentColorsCsv,
                            favoriteColorsCsv = settings.favoriteColorsCsv,
                            onUpdateTool = { viewModel.updateToolConfig(it) },
                            onPickEyedropper = {
                                viewModel.toggleEyedropper()
                                showToolPopover = false
                            },
                            onAddFavoriteColor = { argb ->
                                val hex = String.format("#%08X", argb.toInt())
                                viewModel.updateSettings { cur ->
                                    cur.copy(favoriteColorsCsv = (listOf(hex) + cur.favoriteColorsCsv.split(",")).distinct().take(8).joinToString(","))
                                }
                            },
                            onDismiss = { showToolPopover = false }
                        )
                    }
                }

                // PalmGuard conflict / recovery banner (never permanently locks)
                if (!distractionFree && settings.palmGuardShowCancelReasonBanner &&
                    (palmGuardStatus.isInConflictRecovery || palmGuardStatus.lastConflictReason != null)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(14.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = palmGuardStatus.lastConflictReason ?: "PalmGuard recovered",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.widthIn(max = 280.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { canvasViewRef?.cancelUnfinishedStrokeSafely() }) {
                                Text("Reset Input")
                            }
                        }
                    }
                }

                // Distraction-Free Reveal Control (quiet corner trigger)
                if (distractionFree) {
                    FilledTonalIconButton(
                        onClick = { distractionFree = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    ) {
                        Icon(Icons.Outlined.FullscreenExit, contentDescription = "Exit Distraction-Free Mode")
                    }
                }

                // Touch Debugger Quick Inspector button when enabled
                if (!distractionFree && settings.touchDebuggerEnabled) {
                    FilledTonalButton(
                        onClick = onOpenTouchDebugger,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(14.dp)
                    ) {
                        Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Touch Debugger")
                    }
                }
            }

            // Collapsible Layer Panel
            if (!distractionFree && showLayers) {
                LayerPanelSheet(
                    layers = activePage.layers,
                    activeLayerId = activeDoc.activeLayerId,
                    onSelectLayer = { viewModel.setActiveLayer(it) },
                    onAddLayer = { name, type -> viewModel.addLayer(name, type) },
                    onUpdateLayer = { id, fn -> viewModel.updateLayer(id, fn) },
                    onDeleteLayer = { viewModel.deleteLayer(it) },
                    onMergeDown = { viewModel.mergeLayerDown(it) },
                    onReorder = { id, up -> viewModel.reorderLayer(id, up) },
                    onClearLayer = { viewModel.clearLayer(it) },
                    onClose = { showLayers = false }
                )
            }

            // Left-handed sidebar placement option
            if (!distractionFree && sidebarVisible && settings.handedness == HandednessMode.LEFT) {
                PageSidebarPanel(
                    doc = activeDoc,
                    isLandscape = isLandscape,
                    onBackHome = {
                        viewModel.saveCurrentDocumentNow()
                        onBackHome()
                    },
                    onSelectPage = { viewModel.switchPage(it) },
                    onAddPage = { viewModel.addPage() },
                    onDuplicatePage = { viewModel.duplicateCurrentPage() },
                    onDeletePage = { viewModel.deleteCurrentPage() },
                    onToggleBookmark = { viewModel.toggleCurrentPageBookmark() },
                    onCollapse = { sidebarVisible = false }
                )
            }
        }

        // Transient feedback Snackbar
        statusMessage?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearStatusMessage() }) { Text("OK") }
                }
            ) { Text(msg) }
        }
    }

    if (showNewTextDialog || editingTextObj != null) {
        TextObjectEditorDialog(
            initial = editingTextObj,
            layerId = activeDoc.activeLayerId,
            onDismiss = {
                showNewTextDialog = false
                editingTextObj = null
            },
            onSave = { obj ->
                viewModel.addOrUpdateTextObject(obj)
                showNewTextDialog = false
                editingTextObj = null
            }
        )
    }

    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("Page Template & Background") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageTemplate.entries.forEach { tmpl ->
                        TextButton(onClick = {
                            viewModel.setCurrentPageTemplate(tmpl)
                            showTemplateDialog = false
                        }) { Text(tmpl.displayName) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTemplateDialog = false }) { Text("Close") } }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Save & Export") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.saveCurrentDocumentNow { viewModel.showStatus("Saved locally") }
                        showExportDialog = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save Project (.harpen)") }
                    OutlinedButton(onClick = {
                        saveHarpenAsLauncher.launch("${activeDoc.title.replace(" ", "_")}.harpen")
                        showExportDialog = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("Save As (.harpen via SAF)") }
                    OutlinedButton(onClick = {
                        viewModel.exportCurrentPdfAndShare(allPages = true, highQuality = settings.pdfExportHighQuality)
                        showExportDialog = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("Export All Pages as PDF") }
                    OutlinedButton(onClick = {
                        viewModel.exportCurrentPdfAndShare(allPages = false, highQuality = settings.pdfExportHighQuality)
                        showExportDialog = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("Export Current Page as PDF") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = {
                            viewModel.exportCurrentPageImageAndShare(isPng = true)
                            showExportDialog = false
                        }, modifier = Modifier.weight(1f)) { Text("PNG") }
                        OutlinedButton(onClick = {
                            viewModel.exportCurrentPageImageAndShare(isPng = false)
                            showExportDialog = false
                        }, modifier = Modifier.weight(1f)) { Text("JPG") }
                        OutlinedButton(onClick = {
                            viewModel.exportExtractedTextAndShare()
                            showExportDialog = false
                        }, modifier = Modifier.weight(1f)) { Text("TXT") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun PageSidebarPanel(
    doc: NoteDocument,
    isLandscape: Boolean,
    onBackHome: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onAddPage: () -> Unit,
    onDuplicatePage: () -> Unit,
    onDeletePage: () -> Unit,
    onToggleBookmark: () -> Unit,
    onCollapse: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(if (isLandscape) 210.dp else 156.dp)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBackHome) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Back to Library")
                }
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Hide Sidebar")
                }
            }
            Text(doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text("Page ${doc.activePageIndex + 1} / ${doc.pages.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onAddPage) { Icon(Icons.Outlined.NoteAdd, contentDescription = "Add Page") }
                IconButton(onClick = onDuplicatePage) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Duplicate Page") }
                IconButton(onClick = onToggleBookmark) { Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Bookmark") }
                IconButton(onClick = onDeletePage) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete Page") }
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(doc.pages) { idx, page ->
                    val selected = idx == doc.activePageIndex
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectPage(idx) }
                            .padding(8.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.SpaceBetween) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Page ${idx + 1}", style = MaterialTheme.typography.labelMedium)
                                if (page.bookmarked) Icon(Icons.Outlined.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Text(
                                "${page.template.displayName} · ${page.strokes.size} strokes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingWorkspaceToolbar(
    modifier: Modifier,
    doc: NoteDocument,
    toolConfig: ToolConfig,
    selectionMode: SelectionMode,
    canUndo: Boolean,
    canRedo: Boolean,
    rulerVisible: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSelectTool: (PenToolType) -> Unit,
    onOpenToolConfig: () -> Unit,
    onSelectSelectionMode: (SelectionMode) -> Unit,
    onInsertText: () -> Unit,
    onImportImage: () -> Unit,
    onOpenCameraOcr: () -> Unit,
    onConvertHandwriting: () -> Unit,
    onToggleRuler: () -> Unit,
    onToggleLayers: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenExport: () -> Unit,
    onToggleDistractionFree: () -> Unit,
    onOpenSettings: () -> Unit,
    onCollapseToolbar: () -> Unit
) {
    var showAllBrushes by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.Outlined.Undo, contentDescription = "Undo") }
            IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.Outlined.Redo, contentDescription = "Redo") }
            VerticalDivider(modifier = Modifier.height(26.dp).padding(horizontal = 4.dp))

            // Quick primary pens + brush selector
            listOf(
                PenToolType.BALLPOINT to Icons.Outlined.Edit,
                PenToolType.FOUNTAIN to Icons.Outlined.Create,
                PenToolType.PENCIL_HB to Icons.Outlined.DriveFileRenameOutline,
                PenToolType.HIGHLIGHTER to Icons.Outlined.FormatColorText,
                PenToolType.ERASER_STROKE to Icons.Outlined.CleaningServices
            ).forEach { (type, icon) ->
                val active = selectionMode == SelectionMode.NONE && toolConfig.toolType == type
                FilledIconToggleButton(checked = active, onCheckedChange = { onSelectTool(type) }) {
                    Icon(icon, contentDescription = type.displayName)
                }
            }

            Box {
                TextButton(onClick = { showAllBrushes = true }) {
                    Text(toolConfig.toolType.displayName.substringBefore(" "))
                }
                DropdownMenu(expanded = showAllBrushes, onDismissRequest = { showAllBrushes = false }) {
                    PenToolType.entries.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.displayName) },
                            onClick = {
                                onSelectTool(t)
                                showAllBrushes = false
                            }
                        )
                    }
                }
            }

            IconButton(onClick = onOpenToolConfig) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color(toolConfig.colorArgb))
                        .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(11.dp))
                )
            }

            VerticalDivider(modifier = Modifier.height(26.dp).padding(horizontal = 4.dp))

            FilledIconToggleButton(
                checked = selectionMode == SelectionMode.LASSO,
                onCheckedChange = {
                    onSelectSelectionMode(if (selectionMode == SelectionMode.LASSO) SelectionMode.NONE else SelectionMode.LASSO)
                }
            ) { Icon(Icons.Outlined.Gesture, contentDescription = "Lasso Selection") }

            FilledIconToggleButton(
                checked = selectionMode == SelectionMode.RECTANGLE,
                onCheckedChange = {
                    onSelectSelectionMode(if (selectionMode == SelectionMode.RECTANGLE) SelectionMode.NONE else SelectionMode.RECTANGLE)
                }
            ) { Icon(Icons.Outlined.SelectAll, contentDescription = "Rectangle Selection") }

            IconButton(onClick = onInsertText) { Icon(Icons.Outlined.TextFields, contentDescription = "Insert Text") }
            IconButton(onClick = onImportImage) { Icon(Icons.Outlined.Image, contentDescription = "Import Image") }
            IconButton(onClick = onOpenCameraOcr) { Icon(Icons.Outlined.PhotoCamera, contentDescription = "Camera Scan & OCR") }
            IconButton(onClick = onConvertHandwriting) { Icon(Icons.Outlined.Translate, contentDescription = "Handwriting to Text") }
            FilledIconToggleButton(checked = rulerVisible, onCheckedChange = { onToggleRuler() }) {
                Icon(Icons.Outlined.Straighten, contentDescription = "Ruler")
            }
            IconButton(onClick = onToggleLayers) { Icon(Icons.Outlined.Layers, contentDescription = "Layers") }
            IconButton(onClick = onOpenTemplates) { Icon(Icons.Outlined.GridOn, contentDescription = "Templates") }
            IconButton(onClick = onOpenExport) { Icon(Icons.Outlined.IosShare, contentDescription = "Save & Export") }
            IconButton(onClick = onToggleDistractionFree) { Icon(Icons.Outlined.Fullscreen, contentDescription = "Distraction-Free Mode") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
            IconButton(onClick = onCollapseToolbar) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Hide Toolbar") }
        }
    }
}
