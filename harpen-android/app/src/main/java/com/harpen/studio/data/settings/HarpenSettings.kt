package com.harpen.studio.data.settings

import com.harpen.studio.model.CanvasMode
import com.harpen.studio.model.OcrCorrectionMode
import com.harpen.studio.model.OcrLanguageMode
import com.harpen.studio.model.PageOrientation
import com.harpen.studio.model.PageSizePreset
import com.harpen.studio.model.PageTemplate
import com.harpen.studio.model.PalmGuardMode
import com.harpen.studio.model.PenToolType
import kotlinx.serialization.Serializable

enum class SettingsCategory(val title: String, val subtitle: String) {
    GENERAL("General", "Language, defaults, startup workspace"),
    PALMGUARD_TOUCH("PalmGuard & Touch", "Input protection modes, thresholds, recovery"),
    GESTURES("Gestures", "Multi-finger taps, pinch, stylus double-tap, conflicts"),
    PEN_BRUSH("Pen & Brush", "Pressure curve, smoothing, presets, quick colors"),
    CANVAS_PAGES("Canvas & Pages", "Default template, paper size, grid snap, ruler"),
    LAYERS("Layers", "Auto-layer behavior, blend defaults, selection bounds"),
    OCR_CAMERA("OCR & Camera", "Offline recognition language, cleanup & spelling review"),
    PDF_EXPORT("PDF & Export", "Default quality, margins, transparency, format"),
    INTERFACE_TOOLBAR("Interface & Toolbar", "Collapsible bars, handedness, distraction-free"),
    STORAGE_BACKUP("Storage & Backup", "SAF folder, autosave interval, crash recovery"),
    ACCESSIBILITY("Accessibility", "Theme, contrast, UI scale, button size, haptics"),
    PERFORMANCE("Performance", "4 GB RAM optimization, bitmap cache, animations"),
    DEVELOPER_TOOLS("Developer Tools", "Touch Debugger overlay, bounded log buffer, diagnostics")
}

enum class AppThemeMode(val displayName: String) { LIGHT("Light"), DARK("Dark"), SYSTEM("System") }
enum class HandednessMode(val displayName: String) { RIGHT("Right-Handed"), LEFT("Left-Handed") }
enum class PerformanceMode(val displayName: String) { BALANCED("Balanced"), HIGH_QUALITY("High Quality"), LOW_MEMORY_4GB("Low Memory (4 GB RAM)"), BATTERY_SAVER("Battery Saver") }
enum class GestureAction(val displayName: String) {
    NONE("Disabled"),
    UNDO("Undo"),
    REDO("Redo"),
    PAN("Pan Canvas"),
    ZOOM("Pinch Zoom"),
    TOGGLE_ERASER("Toggle Eraser"),
    LASSO_SELECT("Lasso Selection"),
    TOGGLE_UI("Toggle Distraction-Free UI"),
    NEXT_PAGE("Next Page"),
    PREV_PAGE("Previous Page")
}

@Serializable
data class HarpenSettings(
    // 1. General
    val defaultNoteTitlePrefix: String = "Note",
    val openLastNoteOnLaunch: Boolean = false,
    val confirmBeforeDelete: Boolean = true,
    val showWelcomeBanner: Boolean = true,

    // 2. PalmGuard & Touch
    val palmGuardMode: PalmGuardMode = PalmGuardMode.SINGLE_POINTER,
    val palmGuardCancelOnMultiTouch: Boolean = false,
    val palmGuardContactHeuristicEnabled: Boolean = false,
    val palmGuardMaxContactSize: Float = 0.28f,
    val palmGuardMaxTouchMajorPx: Float = 95f,
    val palmGuardIgnoreSuspiciousBeforeStroke: Boolean = true,
    val palmGuardPrimaryPointerStickiness: Boolean = true,
    val palmGuardRecoveryTimeoutMs: Int = 350,
    val palmGuardShowCancelReasonBanner: Boolean = true,

    // 3. Gestures
    val gestureTwoFingerTap: GestureAction = GestureAction.UNDO,
    val gestureThreeFingerTap: GestureAction = GestureAction.REDO,
    val gestureTwoFingerDragEnabled: Boolean = true,
    val gesturePinchZoomEnabled: Boolean = true,
    val gestureStylusDoubleTap: GestureAction = GestureAction.TOGGLE_ERASER,
    val gestureLongPress: GestureAction = GestureAction.LASSO_SELECT,
    val gestureFourFingerTap: GestureAction = GestureAction.TOGGLE_UI,
    val gesturePageSwipeEnabled: Boolean = false,
    val gestureTapDurationMs: Int = 240,
    val gestureSensitivity: Float = 1.0f,
    val gestureConflictProtection: Boolean = true,

    // 4. Pen & Brush
    val defaultPenTool: PenToolType = PenToolType.BALLPOINT,
    val globalSmoothingMultiplier: Float = 1.0f,
    val useHardwarePressureWhenAvailable: Boolean = true,
    val useHardwareTiltWhenAvailable: Boolean = true,
    val autoCloseToolPopoverOnDraw: Boolean = true,
    val recentColorsCsv: String = "#0F172A,#1D4ED8,#0F766E,#DC2626,#D97706,#475569",
    val favoriteColorsCsv: String = "#0F172A,#2563EB,#0D9488,#059669,#E11D48",

    // 5. Canvas & Pages
    val defaultCanvasMode: CanvasMode = CanvasMode.PAGED,
    val defaultPageTemplate: PageTemplate = PageTemplate.DOTTED,
    val defaultPageSize: PageSizePreset = PageSizePreset.A4,
    val defaultPageOrientation: PageOrientation = PageOrientation.PORTRAIT,
    val defaultPageBgArgb: Long = 0xFFFFFFFF,
    val snapToGrid: Boolean = false,
    val gridSpacingDp: Int = 24,
    val showRulerByDefault: Boolean = false,
    val allowCanvasRotation: Boolean = true,
    val maxZoomScale: Float = 5.0f,

    // 6. Layers
    val autoCreateAnnotationLayerForPdf: Boolean = true,
    val showLayerThumbnails: Boolean = true,
    val lockBackgroundLayerByDefault: Boolean = true,

    // 7. OCR & Camera
    val ocrDefaultLanguage: OcrLanguageMode = OcrLanguageMode.MIXED,
    val ocrCorrectionMode: OcrCorrectionMode = OcrCorrectionMode.SPELLING,
    val ocrAlwaysReviewBeforeAccept: Boolean = true,
    val ocrPreserveTechnicalTerms: Boolean = true,
    val ocrImageQualityPercent: Int = 90,
    val ocrAutoSaveScanImage: Boolean = true,

    // 8. PDF & Export
    val pdfExportHighQuality: Boolean = true,
    val pdfExportIncludeBackground: Boolean = true,
    val pdfExportIncludeImages: Boolean = true,
    val pdfExportIncludeAnnotations: Boolean = true,
    val pdfExportMarginPt: Int = 18,
    val imageExportResolutionScale: Float = 2.0f,
    val pngExportTransparentBg: Boolean = false,

    // 9. Interface & Toolbar
    val sidebarCollapsedByDefault: Boolean = false,
    val toolbarCollapsedByDefault: Boolean = false,
    val toolbarCompactFloating: Boolean = true,
    val showPageNavPill: Boolean = true,
    val handedness: HandednessMode = HandednessMode.RIGHT,

    // 10. Storage & Backup
    val safBackupTreeUri: String? = null,
    val autosaveIntervalSeconds: Int = 15,
    val keepCrashRecoverySnapshots: Boolean = true,
    val maxRecoverySnapshots: Int = 5,

    // 11. Accessibility
    val themeMode: AppThemeMode = AppThemeMode.LIGHT,
    val highContrastMode: Boolean = false,
    val uiScaleFactor: Float = 1.0f,
    val largeToolbarButtons: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    val reduceAnimations: Boolean = false,

    // 12. Performance
    val performanceMode: PerformanceMode = PerformanceMode.LOW_MEMORY_4GB,
    val renderQualityScale: Float = 1.0f,
    val maxUndoHistory: Int = 40,
    val bitmapCacheMb: Int = 48,

    // 13. Developer Tools
    val touchDebuggerEnabled: Boolean = false,
    val debugShowPointerIds: Boolean = true,
    val debugShowCoordinates: Boolean = true,
    val debugShowPressure: Boolean = true,
    val debugShowContactSize: Boolean = true,
    val debugShowEventLabels: Boolean = true,
    val debugShowTrails: Boolean = true,
    val debugShowRawEvents: Boolean = true,
    val debugLogCapacity: Int = 400,
    val debugLoggingPaused: Boolean = false
)
