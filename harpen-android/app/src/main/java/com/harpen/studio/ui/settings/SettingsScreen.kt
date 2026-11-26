package com.harpen.studio.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.harpen.studio.data.settings.*
import com.harpen.studio.data.storage.StorageStats
import com.harpen.studio.model.*
import com.harpen.studio.ui.state.HarpenViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: HarpenViewModel,
    onBack: () -> Unit,
    onOpenTouchDebugger: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val currentDoc by viewModel.currentDoc.collectAsState()
    val scope = rememberCoroutineScope()

    var selectedCategory by remember { mutableStateOf(SettingsCategory.PALMGUARD_TOUCH) }
    var searchQuery by remember { mutableStateOf("") }
    var storageStats by remember { mutableStateOf<StorageStats?>(null) }

    val safFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            viewModel.safManager.persistTreePermission(uri)
            viewModel.updateSettings { it.copy(safBackupTreeUri = uri.toString()) }
            viewModel.showStatus("Backup folder configured")
        }
    }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory == SettingsCategory.STORAGE_BACKUP) {
            storageStats = viewModel.safManager.computeStorageStats()
        }
    }

    val visibleCategories = remember(searchQuery) {
        if (searchQuery.isBlank()) SettingsCategory.entries
        else SettingsCategory.entries.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.subtitle.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HARPEN Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { viewModel.resetSettingsCategory(selectedCategory) }) {
                        Text("Reset Category")
                    }
                    TextButton(onClick = { viewModel.resetAllSettings() }) {
                        Text("Reset All")
                    }
                }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Left Categories + Search
            Column(
                modifier = Modifier
                    .width(290.dp)
                    .fillMaxHeight()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search settings…") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(visibleCategories) { cat ->
                        NavigationDrawerItem(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = {
                                Column {
                                    Text(cat.title, style = MaterialTheme.typography.titleSmall)
                                    Text(cat.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        )
                    }
                }
            }

            VerticalDivider()

            // Right Category Configuration Pane
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(selectedCategory.title, style = MaterialTheme.typography.headlineSmall)
                Text(selectedCategory.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()

                when (selectedCategory) {
                    SettingsCategory.GENERAL -> {
                        SwitchSetting("Open last note on launch", "Restore workspace automatically when starting HARPEN", settings.openLastNoteOnLaunch) { v ->
                            viewModel.updateSettings { it.copy(openLastNoteOnLaunch = v) }
                        }
                        SwitchSetting("Confirm before deleting", "Ask before moving notes or pages to Trash", settings.confirmBeforeDelete) { v ->
                            viewModel.updateSettings { it.copy(confirmBeforeDelete = v) }
                        }
                        SwitchSetting("Show welcome studio strip", "Display quick import & offline banner on Home", settings.showWelcomeBanner) { v ->
                            viewModel.updateSettings { it.copy(showWelcomeBanner = v) }
                        }
                    }

                    SettingsCategory.PALMGUARD_TOUCH -> {
                        Text("PalmGuard Input Mode", style = MaterialTheme.typography.titleSmall)
                        PalmGuardMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.updateSettings { it.copy(palmGuardMode = mode) }
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = settings.palmGuardMode == mode, onClick = {
                                    viewModel.updateSettings { it.copy(palmGuardMode = mode) }
                                })
                                Column(modifier = Modifier.padding(start = 8.dp)) {
                                    Text(mode.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(mode.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        SwitchSetting("Cancel active stroke on multi-touch conflict", "Removes contaminated stroke completely (Mode C/E)", settings.palmGuardCancelOnMultiTouch) { v ->
                            viewModel.updateSettings { it.copy(palmGuardCancelOnMultiTouch = v) }
                        }
                        SwitchSetting("Enable contact-size heuristic", "Filters oversized palm-like contacts when hardware reports size/major", settings.palmGuardContactHeuristicEnabled) { v ->
                            viewModel.updateSettings { it.copy(palmGuardContactHeuristicEnabled = v) }
                        }
                        SliderSetting("Max contact size threshold", "${"%.2f".format(settings.palmGuardMaxContactSize)}", settings.palmGuardMaxContactSize, 0.08f..0.8f) { v ->
                            viewModel.updateSettings { it.copy(palmGuardMaxContactSize = v) }
                        }
                        SliderSetting("Max touchMajor threshold (px)", "${settings.palmGuardMaxTouchMajorPx.toInt()} px", settings.palmGuardMaxTouchMajorPx, 30f..240f) { v ->
                            viewModel.updateSettings { it.copy(palmGuardMaxTouchMajorPx = v) }
                        }
                        SliderSetting("Conflict recovery timeout", "${settings.palmGuardRecoveryTimeoutMs} ms", settings.palmGuardRecoveryTimeoutMs.toFloat(), 100f..1200f) { v ->
                            viewModel.updateSettings { it.copy(palmGuardRecoveryTimeoutMs = v.toInt()) }
                        }
                        SwitchSetting("Show cancellation reason banner", "Displays why a stroke was cancelled plus manual reset", settings.palmGuardShowCancelReasonBanner) { v ->
                            viewModel.updateSettings { it.copy(palmGuardShowCancelReasonBanner = v) }
                        }
                    }

                    SettingsCategory.GESTURES -> {
                        DropdownSetting("Two-finger tap", settings.gestureTwoFingerTap, GestureAction.entries) { a ->
                            viewModel.updateSettings { it.copy(gestureTwoFingerTap = a) }
                        }
                        DropdownSetting("Three-finger tap", settings.gestureThreeFingerTap, GestureAction.entries) { a ->
                            viewModel.updateSettings { it.copy(gestureThreeFingerTap = a) }
                        }
                        DropdownSetting("Four-finger tap", settings.gestureFourFingerTap, GestureAction.entries) { a ->
                            viewModel.updateSettings { it.copy(gestureFourFingerTap = a) }
                        }
                        DropdownSetting("Stylus double-tap (when detectable)", settings.gestureStylusDoubleTap, GestureAction.entries) { a ->
                            viewModel.updateSettings { it.copy(gestureStylusDoubleTap = a) }
                        }
                        SwitchSetting("Two-finger drag pan", "Pan canvas using two fingers", settings.gestureTwoFingerDragEnabled) { v ->
                            viewModel.updateSettings { it.copy(gestureTwoFingerDragEnabled = v) }
                        }
                        SwitchSetting("Pinch to zoom", "Zoom canvas with two-finger pinch", settings.gesturePinchZoomEnabled) { v ->
                            viewModel.updateSettings { it.copy(gesturePinchZoomEnabled = v) }
                        }
                        SwitchSetting("Gesture conflict protection", "Prevents gestures from hijacking an active pen stroke", settings.gestureConflictProtection) { v ->
                            viewModel.updateSettings { it.copy(gestureConflictProtection = v) }
                        }
                        SliderSetting("Tap duration window", "${settings.gestureTapDurationMs} ms", settings.gestureTapDurationMs.toFloat(), 120f..500f) { v ->
                            viewModel.updateSettings { it.copy(gestureTapDurationMs = v.toInt()) }
                        }
                        SliderSetting("Gesture sensitivity", "${"%.2f".format(settings.gestureSensitivity)}x", settings.gestureSensitivity, 0.5f..2.0f) { v ->
                            viewModel.updateSettings { it.copy(gestureSensitivity = v) }
                        }
                    }

                    SettingsCategory.PEN_BRUSH -> {
                        DropdownSetting("Default pen tool", settings.defaultPenTool, PenToolType.entries) { t ->
                            viewModel.updateSettings { it.copy(defaultPenTool = t) }
                        }
                        SliderSetting("Global smoothing multiplier", "${"%.2f".format(settings.globalSmoothingMultiplier)}x", settings.globalSmoothingMultiplier, 0.2f..1.8f) { v ->
                            viewModel.updateSettings { it.copy(globalSmoothingMultiplier = v) }
                        }
                        SwitchSetting("Use hardware pressure when available", "Modulates fountain/brush width only when real pressure exists", settings.useHardwarePressureWhenAvailable) { v ->
                            viewModel.updateSettings { it.copy(useHardwarePressureWhenAvailable = v) }
                        }
                        SwitchSetting("Use hardware tilt when available", "Applies stylus tilt axis when reported by digitizer", settings.useHardwareTiltWhenAvailable) { v ->
                            viewModel.updateSettings { it.copy(useHardwareTiltWhenAvailable = v) }
                        }
                    }

                    SettingsCategory.CANVAS_PAGES -> {
                        DropdownSetting("Default canvas mode", settings.defaultCanvasMode, CanvasMode.entries) { m ->
                            viewModel.updateSettings { it.copy(defaultCanvasMode = m) }
                        }
                        DropdownSetting("Default page template", settings.defaultPageTemplate, PageTemplate.entries) { t ->
                            viewModel.updateSettings { it.copy(defaultPageTemplate = t) }
                        }
                        DropdownSetting("Default paper size", settings.defaultPageSize, PageSizePreset.entries) { s ->
                            viewModel.updateSettings { it.copy(defaultPageSize = s) }
                        }
                        SwitchSetting("Snap strokes to grid", "Align stroke points to template grid spacing", settings.snapToGrid) { v ->
                            viewModel.updateSettings { it.copy(snapToGrid = v) }
                        }
                        SliderSetting("Grid spacing (dp)", "${settings.gridSpacingDp} dp", settings.gridSpacingDp.toFloat(), 12f..64f) { v ->
                            viewModel.updateSettings { it.copy(gridSpacingDp = v.toInt()) }
                        }
                        SwitchSetting("Allow two-finger canvas rotation", "Rotate canvas smoothly during pinch gesture", settings.allowCanvasRotation) { v ->
                            viewModel.updateSettings { it.copy(allowCanvasRotation = v) }
                        }
                    }

                    SettingsCategory.LAYERS -> {
                        SwitchSetting("Auto-create annotation layer on PDF import", "Keeps PDF base separate from ink annotations", settings.autoCreateAnnotationLayerForPdf) { v ->
                            viewModel.updateSettings { it.copy(autoCreateAnnotationLayerForPdf = v) }
                        }
                        SwitchSetting("Lock background layer by default", "Prevents accidental erasing of imported backgrounds", settings.lockBackgroundLayerByDefault) { v ->
                            viewModel.updateSettings { it.copy(lockBackgroundLayerByDefault = v) }
                        }
                    }

                    SettingsCategory.OCR_CAMERA -> {
                        DropdownSetting("Default OCR language", settings.ocrDefaultLanguage, OcrLanguageMode.entries) { l ->
                            viewModel.updateSettings { it.copy(ocrDefaultLanguage = l) }
                        }
                        DropdownSetting("OCR correction mode", settings.ocrCorrectionMode, OcrCorrectionMode.entries) { m ->
                            viewModel.updateSettings { it.copy(ocrCorrectionMode = m) }
                        }
                        SwitchSetting("Always review corrections before accepting", "Shows diff checkboxes before applying spelling edits", settings.ocrAlwaysReviewBeforeAccept) { v ->
                            viewModel.updateSettings { it.copy(ocrAlwaysReviewBeforeAccept = v) }
                        }
                        SwitchSetting("Protect numbers, dates & technical terms", "Prevents auto-changing formulas, acronyms, or codes", settings.ocrPreserveTechnicalTerms) { v ->
                            viewModel.updateSettings { it.copy(ocrPreserveTechnicalTerms = v) }
                        }
                        SwitchSetting("Auto-save camera scan image onto note", "Attaches scan bitmap along with extracted editable text", settings.ocrAutoSaveScanImage) { v ->
                            viewModel.updateSettings { it.copy(ocrAutoSaveScanImage = v) }
                        }
                    }

                    SettingsCategory.PDF_EXPORT -> {
                        SwitchSetting("High quality PDF export", "2x vector & backdrop resolution", settings.pdfExportHighQuality) { v ->
                            viewModel.updateSettings { it.copy(pdfExportHighQuality = v) }
                        }
                        SwitchSetting("Include page background", "Render paper color/PDF base in exports", settings.pdfExportIncludeBackground) { v ->
                            viewModel.updateSettings { it.copy(pdfExportIncludeBackground = v) }
                        }
                        SwitchSetting("Include annotation layers", "Export handwritten ink and highlights over PDFs", settings.pdfExportIncludeAnnotations) { v ->
                            viewModel.updateSettings { it.copy(pdfExportIncludeAnnotations = v) }
                        }
                        SliderSetting("PDF margin (pt)", "${settings.pdfExportMarginPt} pt", settings.pdfExportMarginPt.toFloat(), 0f..72f) { v ->
                            viewModel.updateSettings { it.copy(pdfExportMarginPt = v.toInt()) }
                        }
                        SwitchSetting("Transparent PNG background", "Export ink layers without paper fill", settings.pngExportTransparentBg) { v ->
                            viewModel.updateSettings { it.copy(pngExportTransparentBg = v) }
                        }
                    }

                    SettingsCategory.INTERFACE_TOOLBAR -> {
                        SwitchSetting("Collapse sidebar by default", "Maximize writing space when opening a note", settings.sidebarCollapsedByDefault) { v ->
                            viewModel.updateSettings { it.copy(sidebarCollapsedByDefault = v) }
                        }
                        SwitchSetting("Collapse toolbar by default", "Start with floating Tools trigger only", settings.toolbarCollapsedByDefault) { v ->
                            viewModel.updateSettings { it.copy(toolbarCollapsedByDefault = v) }
                        }
                        DropdownSetting("Handedness layout", settings.handedness, HandednessMode.entries) { h ->
                            viewModel.updateSettings { it.copy(handedness = h) }
                        }
                    }

                    SettingsCategory.STORAGE_BACKUP -> {
                        Text(
                            "SAF Backup Folder: ${settings.safBackupTreeUri ?: "Not configured (Internal app storage only)"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { safFolderPicker.launch(null) }) {
                                Text("Choose SAF Folder (SD / USB / Internal)")
                            }
                            OutlinedButton(
                                enabled = settings.safBackupTreeUri != null && currentDoc != null,
                                onClick = {
                                    val tree = Uri.parse(settings.safBackupTreeUri!!)
                                    val docToSave = currentDoc ?: return@OutlinedButton
                                    scope.launch {
                                        viewModel.safManager.backupDocumentToFolder(docToSave, tree)
                                            .onSuccess { name -> viewModel.showStatus("Backed up $name") }
                                            .onFailure { err -> viewModel.showStatus("Backup error: ${err.message}") }
                                    }
                                }
                            ) {
                                Text("Backup Active Note Now")
                            }
                        }
                        SliderSetting("Autosave interval", "${settings.autosaveIntervalSeconds}s", settings.autosaveIntervalSeconds.toFloat(), 5f..120f) { v ->
                            viewModel.updateSettings { it.copy(autosaveIntervalSeconds = v.toInt()) }
                        }
                        storageStats?.let { st ->
                            Text(
                                "Projects: ${st.projectsBytes / 1024} KB · Assets: ${st.assetsBytes / 1024} KB · Recovery: ${st.recoveryBytes / 1024} KB · Cache: ${st.cacheBytes / 1024} KB",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                viewModel.safManager.clearCache()
                                storageStats = viewModel.safManager.computeStorageStats()
                                viewModel.showStatus("Temporary cache cleared")
                            }
                        }) { Text("Clear Cache") }
                    }

                    SettingsCategory.ACCESSIBILITY -> {
                        DropdownSetting("App theme", settings.themeMode, AppThemeMode.entries) { t ->
                            viewModel.updateSettings { it.copy(themeMode = t) }
                        }
                        SwitchSetting("High contrast mode", "Increases border and ink UI contrast", settings.highContrastMode) { v ->
                            viewModel.updateSettings { it.copy(highContrastMode = v) }
                        }
                        SwitchSetting("Large toolbar buttons", "Easier touch targets for accessibility", settings.largeToolbarButtons) { v ->
                            viewModel.updateSettings { it.copy(largeToolbarButtons = v) }
                        }
                        SwitchSetting("Haptic feedback", "Subtle tactile confirmation on actions", settings.hapticFeedbackEnabled) { v ->
                            viewModel.updateSettings { it.copy(hapticFeedbackEnabled = v) }
                        }
                        SwitchSetting("Reduce animations", "Disables decorative UI transitions", settings.reduceAnimations) { v ->
                            viewModel.updateSettings { it.copy(reduceAnimations = v) }
                        }
                    }

                    SettingsCategory.PERFORMANCE -> {
                        DropdownSetting("Performance profile", settings.performanceMode, PerformanceMode.entries) { m ->
                            viewModel.updateSettings { it.copy(performanceMode = m) }
                        }
                        SliderSetting("Undo stack limit", "${settings.maxUndoHistory} steps", settings.maxUndoHistory.toFloat(), 10f..100f) { v ->
                            viewModel.updateSettings { it.copy(maxUndoHistory = v.toInt()) }
                        }
                        SliderSetting("Bitmap memory cache", "${settings.bitmapCacheMb} MB", settings.bitmapCacheMb.toFloat(), 16f..128f) { v ->
                            viewModel.updateSettings { it.copy(bitmapCacheMb = v.toInt()) }
                        }
                    }

                    SettingsCategory.DEVELOPER_TOOLS -> {
                        Button(onClick = onOpenTouchDebugger) {
                            Icon(Icons.Outlined.BugReport, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open Touch Debugger & Test Surface")
                        }
                        SwitchSetting("Enable Touch Debugger overlay on canvas", "Shows live pointer markers & records MotionEvents", settings.touchDebuggerEnabled) { v ->
                            viewModel.updateSettings { it.copy(touchDebuggerEnabled = v) }
                        }
                        SwitchSetting("Show pointer IDs", "Display Android pointerId near live marker", settings.debugShowPointerIds) { v ->
                            viewModel.updateSettings { it.copy(debugShowPointerIds = v) }
                        }
                        SwitchSetting("Show coordinates", "Display live X/Y coordinates", settings.debugShowCoordinates) { v ->
                            viewModel.updateSettings { it.copy(debugShowCoordinates = v) }
                        }
                        SwitchSetting("Show pressure", "Display reported MotionEvent pressure", settings.debugShowPressure) { v ->
                            viewModel.updateSettings { it.copy(debugShowPressure = v) }
                        }
                        SwitchSetting("Show contact size", "Render touchMajor/size circle overlay", settings.debugShowContactSize) { v ->
                            viewModel.updateSettings { it.copy(debugShowContactSize = v) }
                        }
                        SwitchSetting("Show trails", "Render pointer movement trails", settings.debugShowTrails) { v ->
                            viewModel.updateSettings { it.copy(debugShowTrails = v) }
                        }
                        SliderSetting("Bounded log capacity", "${settings.debugLogCapacity} events", settings.debugLogCapacity.toFloat(), 100f..1500f) { v ->
                            viewModel.updateSettings { it.copy(debugLogCapacity = v.toInt()) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchSetting(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun <T : Enum<T>> DropdownSetting(
    title: String,
    selected: T,
    options: List<T>,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected.name.replace('_', ' '))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt.name.replace('_', ' ')) },
                        onClick = {
                            onSelect(opt)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
