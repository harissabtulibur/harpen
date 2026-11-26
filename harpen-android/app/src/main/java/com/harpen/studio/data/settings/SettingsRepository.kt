package com.harpen.studio.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.harpenSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "harpen_settings_prefs")

class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsKey = stringPreferencesKey("harpen_settings_json")

    val settingsFlow: Flow<HarpenSettings> = context.harpenSettingsStore.data.map { prefs ->
        val raw = prefs[settingsKey]
        if (raw.isNullOrBlank()) {
            HarpenSettings()
        } else {
            runCatching { json.decodeFromString<HarpenSettings>(raw) }.getOrDefault(HarpenSettings())
        }
    }

    suspend fun update(transform: (HarpenSettings) -> HarpenSettings) {
        context.harpenSettingsStore.edit { prefs ->
            val current = prefs[settingsKey]?.let {
                runCatching { json.decodeFromString<HarpenSettings>(it) }.getOrNull()
            } ?: HarpenSettings()
            val updated = transform(current)
            prefs[settingsKey] = json.encodeToString(updated)
        }
    }

    suspend fun resetAll() {
        context.harpenSettingsStore.edit { prefs ->
            prefs[settingsKey] = json.encodeToString(HarpenSettings())
        }
    }

    suspend fun resetCategory(category: SettingsCategory) {
        val defaults = HarpenSettings()
        update { cur ->
            when (category) {
                SettingsCategory.GENERAL -> cur.copy(
                    defaultNoteTitlePrefix = defaults.defaultNoteTitlePrefix,
                    openLastNoteOnLaunch = defaults.openLastNoteOnLaunch,
                    confirmBeforeDelete = defaults.confirmBeforeDelete,
                    showWelcomeBanner = defaults.showWelcomeBanner
                )
                SettingsCategory.PALMGUARD_TOUCH -> cur.copy(
                    palmGuardMode = defaults.palmGuardMode,
                    palmGuardCancelOnMultiTouch = defaults.palmGuardCancelOnMultiTouch,
                    palmGuardContactHeuristicEnabled = defaults.palmGuardContactHeuristicEnabled,
                    palmGuardMaxContactSize = defaults.palmGuardMaxContactSize,
                    palmGuardMaxTouchMajorPx = defaults.palmGuardMaxTouchMajorPx,
                    palmGuardIgnoreSuspiciousBeforeStroke = defaults.palmGuardIgnoreSuspiciousBeforeStroke,
                    palmGuardPrimaryPointerStickiness = defaults.palmGuardPrimaryPointerStickiness,
                    palmGuardRecoveryTimeoutMs = defaults.palmGuardRecoveryTimeoutMs,
                    palmGuardShowCancelReasonBanner = defaults.palmGuardShowCancelReasonBanner
                )
                SettingsCategory.GESTURES -> cur.copy(
                    gestureTwoFingerTap = defaults.gestureTwoFingerTap,
                    gestureThreeFingerTap = defaults.gestureThreeFingerTap,
                    gestureTwoFingerDragEnabled = defaults.gestureTwoFingerDragEnabled,
                    gesturePinchZoomEnabled = defaults.gesturePinchZoomEnabled,
                    gestureStylusDoubleTap = defaults.gestureStylusDoubleTap,
                    gestureLongPress = defaults.gestureLongPress,
                    gestureFourFingerTap = defaults.gestureFourFingerTap,
                    gesturePageSwipeEnabled = defaults.gesturePageSwipeEnabled,
                    gestureTapDurationMs = defaults.gestureTapDurationMs,
                    gestureSensitivity = defaults.gestureSensitivity,
                    gestureConflictProtection = defaults.gestureConflictProtection
                )
                SettingsCategory.PEN_BRUSH -> cur.copy(
                    defaultPenTool = defaults.defaultPenTool,
                    globalSmoothingMultiplier = defaults.globalSmoothingMultiplier,
                    useHardwarePressureWhenAvailable = defaults.useHardwarePressureWhenAvailable,
                    useHardwareTiltWhenAvailable = defaults.useHardwareTiltWhenAvailable,
                    autoCloseToolPopoverOnDraw = defaults.autoCloseToolPopoverOnDraw
                )
                SettingsCategory.CANVAS_PAGES -> cur.copy(
                    defaultCanvasMode = defaults.defaultCanvasMode,
                    defaultPageTemplate = defaults.defaultPageTemplate,
                    defaultPageSize = defaults.defaultPageSize,
                    defaultPageOrientation = defaults.defaultPageOrientation,
                    defaultPageBgArgb = defaults.defaultPageBgArgb,
                    snapToGrid = defaults.snapToGrid,
                    gridSpacingDp = defaults.gridSpacingDp,
                    showRulerByDefault = defaults.showRulerByDefault,
                    allowCanvasRotation = defaults.allowCanvasRotation,
                    maxZoomScale = defaults.maxZoomScale
                )
                SettingsCategory.LAYERS -> cur.copy(
                    autoCreateAnnotationLayerForPdf = defaults.autoCreateAnnotationLayerForPdf,
                    showLayerThumbnails = defaults.showLayerThumbnails,
                    lockBackgroundLayerByDefault = defaults.lockBackgroundLayerByDefault
                )
                SettingsCategory.OCR_CAMERA -> cur.copy(
                    ocrDefaultLanguage = defaults.ocrDefaultLanguage,
                    ocrCorrectionMode = defaults.ocrCorrectionMode,
                    ocrAlwaysReviewBeforeAccept = defaults.ocrAlwaysReviewBeforeAccept,
                    ocrPreserveTechnicalTerms = defaults.ocrPreserveTechnicalTerms,
                    ocrImageQualityPercent = defaults.ocrImageQualityPercent,
                    ocrAutoSaveScanImage = defaults.ocrAutoSaveScanImage
                )
                SettingsCategory.PDF_EXPORT -> cur.copy(
                    pdfExportHighQuality = defaults.pdfExportHighQuality,
                    pdfExportIncludeBackground = defaults.pdfExportIncludeBackground,
                    pdfExportIncludeImages = defaults.pdfExportIncludeImages,
                    pdfExportIncludeAnnotations = defaults.pdfExportIncludeAnnotations,
                    pdfExportMarginPt = defaults.pdfExportMarginPt,
                    imageExportResolutionScale = defaults.imageExportResolutionScale,
                    pngExportTransparentBg = defaults.pngExportTransparentBg
                )
                SettingsCategory.INTERFACE_TOOLBAR -> cur.copy(
                    sidebarCollapsedByDefault = defaults.sidebarCollapsedByDefault,
                    toolbarCollapsedByDefault = defaults.toolbarCollapsedByDefault,
                    toolbarCompactFloating = defaults.toolbarCompactFloating,
                    showPageNavPill = defaults.showPageNavPill,
                    handedness = defaults.handedness
                )
                SettingsCategory.STORAGE_BACKUP -> cur.copy(
                    safBackupTreeUri = defaults.safBackupTreeUri,
                    autosaveIntervalSeconds = defaults.autosaveIntervalSeconds,
                    keepCrashRecoverySnapshots = defaults.keepCrashRecoverySnapshots,
                    maxRecoverySnapshots = defaults.maxRecoverySnapshots
                )
                SettingsCategory.ACCESSIBILITY -> cur.copy(
                    themeMode = defaults.themeMode,
                    highContrastMode = defaults.highContrastMode,
                    uiScaleFactor = defaults.uiScaleFactor,
                    largeToolbarButtons = defaults.largeToolbarButtons,
                    hapticFeedbackEnabled = defaults.hapticFeedbackEnabled,
                    reduceAnimations = defaults.reduceAnimations
                )
                SettingsCategory.PERFORMANCE -> cur.copy(
                    performanceMode = defaults.performanceMode,
                    renderQualityScale = defaults.renderQualityScale,
                    maxUndoHistory = defaults.maxUndoHistory,
                    bitmapCacheMb = defaults.bitmapCacheMb
                )
                SettingsCategory.DEVELOPER_TOOLS -> cur.copy(
                    touchDebuggerEnabled = defaults.touchDebuggerEnabled,
                    debugShowPointerIds = defaults.debugShowPointerIds,
                    debugShowCoordinates = defaults.debugShowCoordinates,
                    debugShowPressure = defaults.debugShowPressure,
                    debugShowContactSize = defaults.debugShowContactSize,
                    debugShowEventLabels = defaults.debugShowEventLabels,
                    debugShowTrails = defaults.debugShowTrails,
                    debugShowRawEvents = defaults.debugShowRawEvents,
                    debugLogCapacity = defaults.debugLogCapacity,
                    debugLoggingPaused = defaults.debugLoggingPaused
                )
            }
        }
    }
}
