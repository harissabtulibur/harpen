package com.harpen.studio.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class PenToolType(
    val displayName: String,
    val isEraser: Boolean = false,
    val defaultWidth: Float = 4f,
    val defaultOpacity: Float = 1f,
    val defaultSmoothing: Float = 0.45f,
    val defaultTexture: Float = 0f
) {
    BALLPOINT("Ballpoint Pen", false, 3.5f, 1f, 0.45f, 0f),
    FOUNTAIN("Fountain Pen", false, 5.5f, 1f, 0.55f, 0f),
    FINELINER("Fineliner", false, 2.2f, 1f, 0.35f, 0f),
    BRUSH("Brush Pen", false, 12f, 1f, 0.6f, 0.1f),
    PENCIL_HB("Pencil HB", false, 3.0f, 0.85f, 0.3f, 0.45f),
    PENCIL_MECHANICAL("Mechanical Pencil", false, 1.6f, 0.9f, 0.25f, 0.2f),
    PENCIL_SOFT("Soft Pencil 4B", false, 7.0f, 0.78f, 0.35f, 0.7f),
    MARKER("Marker", false, 16f, 0.88f, 0.4f, 0.05f),
    HIGHLIGHTER("Highlighter", false, 26f, 0.35f, 0.5f, 0f),
    CRAYON("Crayon", false, 14f, 0.85f, 0.25f, 0.85f),
    WATERCOLOR("Watercolor", false, 24f, 0.42f, 0.65f, 0.6f),
    ERASER_PIXEL("Pixel Eraser", true, 18f, 1f, 0.2f, 0f),
    ERASER_STROKE("Stroke Eraser", true, 16f, 1f, 0f, 0f),
    ERASER_AREA("Area Eraser", true, 20f, 1f, 0f, 0f),
    ERASER_LASSO("Lasso Eraser", true, 16f, 1f, 0f, 0f)
}

@Serializable
data class ToolConfig(
    val toolType: PenToolType = PenToolType.BALLPOINT,
    val width: Float = toolType.defaultWidth,
    val opacity: Float = toolType.defaultOpacity,
    val colorArgb: Long = 0xFF0F172A,
    val smoothing: Float = toolType.defaultSmoothing,
    val texture: Float = toolType.defaultTexture,
    val pressureResponse: Float = 0.65f,
    val tiltResponse: Float = 0.35f,
    val presetName: String = "Default"
)

@Serializable
data class StrokePoint(
    val x: Float,
    val y: Float,
    val timestamp: Long,
    val pressure: Float? = null,
    val tiltX: Float? = null,
    val tiltY: Float? = null,
    val orientation: Float? = null,
    val toolType: Int = 0
)

@Serializable
data class ObjectTransform(
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false
)

@Serializable
data class VectorStroke(
    val id: String = UUID.randomUUID().toString(),
    val layerId: String,
    val toolType: PenToolType,
    val colorArgb: Long,
    val width: Float,
    val opacity: Float,
    val smoothing: Float,
    val texture: Float = 0f,
    val pressureResponse: Float = 0.65f,
    val tiltResponse: Float = 0.35f,
    val points: List<StrokePoint>,
    val transform: ObjectTransform = ObjectTransform(),
    val groupId: String? = null,
    val locked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class TextAlignment { LEFT, CENTER, RIGHT }

@Serializable
data class TextObject(
    val id: String = UUID.randomUUID().toString(),
    val layerId: String,
    val text: String,
    val fontFamily: String = "SansSerif",
    val fontSizeSp: Float = 16f,
    val colorArgb: Long = 0xFF0F172A,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val alignment: TextAlignment = TextAlignment.LEFT,
    val bulletList: Boolean = false,
    val numberedList: Boolean = false,
    val backgroundColorArgb: Long? = null,
    val highlightColorArgb: Long? = null,
    val lineSpacing: Float = 1.25f,
    val x: Float = 120f,
    val y: Float = 120f,
    val width: Float = 420f,
    val height: Float = 160f,
    val rotationDeg: Float = 0f,
    val groupId: String? = null,
    val locked: Boolean = false
)

@Serializable
data class ImageObject(
    val id: String = UUID.randomUUID().toString(),
    val layerId: String,
    val assetFileName: String,
    val sourceUri: String? = null,
    val x: Float = 80f,
    val y: Float = 80f,
    val width: Float = 480f,
    val height: Float = 360f,
    val rotationDeg: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val opacity: Float = 1f,
    val isBackground: Boolean = false,
    val zOrder: Int = 0,
    val groupId: String? = null,
    val locked: Boolean = false
)

@Serializable
enum class LayerType { INK, IMAGE, TEXT, BACKGROUND, ANNOTATION }

@Serializable
enum class LayerBlendMode { NORMAL, MULTIPLY, SCREEN }

@Serializable
data class Layer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: LayerType = LayerType.INK,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Float = 1f,
    val blendMode: LayerBlendMode = LayerBlendMode.NORMAL,
    val groupId: String? = null
)

@Serializable
enum class PageTemplate(val displayName: String) {
    BLANK("Blank"),
    GRID("Grid"),
    DOTTED("Dotted"),
    RULED("Ruled"),
    CORNELL("Cornell"),
    PLANNER("Planner"),
    GRAPH("Graph"),
    MUSIC_SHEET("Music Sheet"),
    CUSTOM("Custom")
}

@Serializable
enum class CanvasMode(val displayName: String) {
    PAGED("Paged Notebook"),
    INFINITE("Infinite Canvas"),
    LONG_VERTICAL("Long Vertical"),
    DRAWING("Drawing Canvas")
}

@Serializable
enum class PageSizePreset(val displayName: String, val widthPt: Float, val heightPt: Float) {
    A4("A4", 595f, 842f),
    A5("A5", 420f, 595f),
    LETTER("Letter", 612f, 792f),
    CUSTOM("Custom", 800f, 1100f)
}

@Serializable
enum class PageOrientation { PORTRAIT, LANDSCAPE }

@Serializable
data class NotePage(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int = 0,
    val template: PageTemplate = PageTemplate.DOTTED,
    val sizePreset: PageSizePreset = PageSizePreset.A4,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val customWidthPt: Float = 800f,
    val customHeightPt: Float = 1100f,
    val backgroundColorArgb: Long = 0xFFFFFFFF,
    val bookmarked: Boolean = false,
    val pdfAssetFileName: String? = null,
    val pdfSourcePageIndex: Int? = null,
    val layers: List<Layer> = listOf(
        Layer(id = "layer_bg", name = "Background", type = LayerType.BACKGROUND),
        Layer(id = "layer_ink", name = "Ink Layer 1", type = LayerType.INK),
        Layer(id = "layer_ann", name = "Annotations", type = LayerType.ANNOTATION)
    ),
    val strokes: List<VectorStroke> = emptyList(),
    val textObjects: List<TextObject> = emptyList(),
    val imageObjects: List<ImageObject> = emptyList()
)

@Serializable
data class NoteDocument(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Untitled Note",
    val folderId: String? = null,
    val isNotebook: Boolean = false,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    val hasPdf: Boolean = false,
    val hasImages: Boolean = false,
    val canvasMode: CanvasMode = CanvasMode.PAGED,
    val coverColorArgb: Long = 0xFF1E3A8A,
    val tags: List<String> = emptyList(),
    val pages: List<NotePage> = listOf(NotePage()),
    val activePageIndex: Int = 0,
    val activeLayerId: String = "layer_ink",
    val zoomScale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val canvasRotationDeg: Float = 0f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val extractedTextCache: String = ""
)

@Serializable
enum class SelectionMode { NONE, LASSO, RECTANGLE, SINGLE_OBJECT }

enum class PalmGuardMode(val code: String, val displayName: String, val summary: String) {
    NATIVE_RAW("A", "Mode A — Native / Raw", "Accepts Android MotionEvent input without app-level filtering."),
    SINGLE_POINTER("B", "Mode B — Single Pointer Protection", "Locks active stroke to one pointer ID; secondary touches cannot join or connect."),
    MULTI_TOUCH_CANCEL("C", "Mode C — Multi-Touch Conflict Cancel", "Cancels & removes contaminated stroke on multi-touch conflict, then recovers safely."),
    CONTACT_HEURISTIC("D", "Mode D — Contact Heuristic", "Uses reported contact size/touchMajor thresholds when available; never claims certainty."),
    EXPERIMENTAL_HYBRID("E", "Mode E — Experimental Hybrid", "Combines configurable filters independently and logs raw vs filtered streams.")
}

enum class PointerVisualState { PRIMARY, SECONDARY, CANCELLED, SELECTED, INACTIVE }

data class TouchMarker(
    val pointerId: Int,
    val pointerIndex: Int,
    val x: Float,
    val y: Float,
    val rawX: Float,
    val rawY: Float,
    val pressure: Float,
    val size: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val orientation: Float,
    val toolType: Int,
    val state: PointerVisualState,
    val trail: List<Pair<Float, Float>> = emptyList()
)

data class TouchDebugEvent(
    val id: Long,
    val timestampMs: Long,
    val actionName: String,
    val actionIndex: Int,
    val pointerId: Int,
    val pointerIndex: Int,
    val pointerCount: Int,
    val isPrimary: Boolean,
    val toolTypeName: String,
    val x: Float,
    val y: Float,
    val rawX: Float,
    val rawY: Float,
    val pressure: Float,
    val size: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val orientation: Float,
    val deviceId: Int,
    val source: Int,
    val flags: Int,
    val historySize: Int,
    val filteredOut: Boolean,
    val filterReason: String? = null
)

enum class OcrLanguageMode(val displayName: String) {
    AUTO("Auto (ID + EN)"),
    INDONESIAN("Bahasa Indonesia"),
    ENGLISH("English"),
    MIXED("Indonesian + English")
}

enum class OcrCorrectionMode(val displayName: String, val description: String) {
    NONE("None", "Keep raw offline OCR output unchanged."),
    BASIC_CLEANUP("Basic Cleanup", "Fix broken line wraps, extra spaces, and common OCR glyph confusions."),
    SPELLING("Spelling Correction", "Offline Indonesian + English dictionary check while preserving numbers, formulas, and technical terms."),
    GRAMMAR("Grammar Rules (Heuristic)", "Offline rule-based capitalization & phrase checks (full neural grammar requires external model)."),
    PUNCTUATION("Punctuation Cleanup", "Normalize spacing around periods, commas, colons, and quotes.")
}
