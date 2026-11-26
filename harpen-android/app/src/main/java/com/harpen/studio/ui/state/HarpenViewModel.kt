package com.harpen.studio.ui.state

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harpen.studio.HarpenApplication
import com.harpen.studio.data.local.ColorPaletteEntity
import com.harpen.studio.data.local.FolderEntity
import com.harpen.studio.data.local.NoteEntity
import com.harpen.studio.data.settings.HarpenSettings
import com.harpen.studio.data.settings.SettingsCategory
import com.harpen.studio.input.PalmGuardStatus
import com.harpen.studio.model.CanvasMode
import com.harpen.studio.model.ImageObject
import com.harpen.studio.model.Layer
import com.harpen.studio.model.LayerBlendMode
import com.harpen.studio.model.LayerType
import com.harpen.studio.model.NoteDocument
import com.harpen.studio.model.NotePage
import com.harpen.studio.model.PageOrientation
import com.harpen.studio.model.PageSizePreset
import com.harpen.studio.model.PageTemplate
import com.harpen.studio.model.PenToolType
import com.harpen.studio.model.SelectionMode
import com.harpen.studio.model.TextObject
import com.harpen.studio.model.ToolConfig
import com.harpen.studio.model.VectorStroke
import com.harpen.studio.pdf.PdfExportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

enum class HomeSection(val title: String) {
    RECENT("Recent"),
    ALL_NOTES("All Notes"),
    NOTEBOOKS("Notebooks"),
    FAVORITES("Favorites"),
    TEMPLATES("Templates"),
    PDF_DOCUMENTS("PDF Documents"),
    IMAGES("Images"),
    TRASH("Trash"),
    SETTINGS("Settings")
}

enum class SortOrder(val displayName: String) {
    UPDATED_DESC("Last Modified"),
    CREATED_DESC("Date Created"),
    TITLE_ASC("Title A–Z")
}

data class ClipboardPayload(
    val strokes: List<VectorStroke> = emptyList(),
    val texts: List<TextObject> = emptyList(),
    val images: List<ImageObject> = emptyList()
)

class HarpenViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as HarpenApplication
    private val noteDao = app.database.noteDao()
    private val folderDao = app.database.folderDao()
    private val paletteDao = app.database.paletteDao()
    val settingsRepo = app.settingsRepository
    val archiveManager = app.archiveManager
    val safManager = app.safStorageManager
    val touchDebugger = app.touchDebugger
    val ocrManager = app.ocrManager
    val pdfEngine = app.pdfEngine

    val settings: StateFlow<HarpenSettings> = settingsRepo.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, HarpenSettings())

    val activeNotes: StateFlow<List<NoteEntity>> = noteDao.observeActiveNotes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val trashedNotes: StateFlow<List<NoteEntity>> = noteDao.observeTrashedNotes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folders: StateFlow<List<FolderEntity>> = folderDao.observeFolders()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val palettes: StateFlow<List<ColorPaletteEntity>> = paletteDao.observePalettes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentDoc = MutableStateFlow<NoteDocument?>(null)
    val currentDoc: StateFlow<NoteDocument?> = _currentDoc.asStateFlow()

    private val _toolConfig = MutableStateFlow(ToolConfig())
    val toolConfig: StateFlow<ToolConfig> = _toolConfig.asStateFlow()

    private val _selectionMode = MutableStateFlow(SelectionMode.NONE)
    val selectionMode: StateFlow<SelectionMode> = _selectionMode.asStateFlow()

    private val _selectedStrokeIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedStrokeIds: StateFlow<Set<String>> = _selectedStrokeIds.asStateFlow()

    private val _selectedTextIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedTextIds: StateFlow<Set<String>> = _selectedTextIds.asStateFlow()

    private val _selectedImageIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedImageIds: StateFlow<Set<String>> = _selectedImageIds.asStateFlow()

    private val _eyedropperActive = MutableStateFlow(false)
    val eyedropperActive: StateFlow<Boolean> = _eyedropperActive.asStateFlow()

    private val _rulerVisible = MutableStateFlow(false)
    val rulerVisible: StateFlow<Boolean> = _rulerVisible.asStateFlow()

    private val _pdfBackdrop = MutableStateFlow<Bitmap?>(null)
    val pdfBackdrop: StateFlow<Bitmap?> = _pdfBackdrop.asStateFlow()

    private val _palmGuardStatus = MutableStateFlow(PalmGuardStatus())
    val palmGuardStatus: StateFlow<PalmGuardStatus> = _palmGuardStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val undoStack = ArrayDeque<NoteDocument>()
    private val redoStack = ArrayDeque<NoteDocument>()
    private var clipboard: ClipboardPayload? = null
    private var autosaveJob: Job? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    init {
        startAutosaveLoop()
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun showStatus(msg: String) {
        _statusMessage.value = msg
    }

    // --- Home & Library Operations ---

    fun createNewNote(
        title: String? = null,
        isNotebook: Boolean = false,
        template: PageTemplate = settings.value.defaultPageTemplate,
        canvasMode: CanvasMode = settings.value.defaultCanvasMode,
        folderId: String? = null,
        onOpened: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            val finalTitle = title?.takeIf { it.isNotBlank() }
                ?: "${settings.value.defaultNoteTitlePrefix} ${activeNotes.value.size + 1}"
            val page = NotePage(
                pageIndex = 0,
                template = template,
                sizePreset = settings.value.defaultPageSize,
                orientation = settings.value.defaultPageOrientation,
                backgroundColorArgb = settings.value.defaultPageBgArgb
            )
            val doc = NoteDocument(
                id = id,
                title = finalTitle,
                folderId = folderId,
                isNotebook = isNotebook,
                canvasMode = canvasMode,
                pages = listOf(page)
            )
            val file = archiveManager.saveDocument(doc).getOrThrow()
            noteDao.upsert(
                NoteEntity(
                    id = id,
                    title = finalTitle,
                    folderId = folderId,
                    isNotebook = isNotebook,
                    templateName = template.name,
                    pageCount = 1,
                    projectFilePath = file.absolutePath
                )
            )
            openNote(id)
            onOpened(id)
        }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            folderDao.upsert(FolderEntity(id = UUID.randomUUID().toString(), name = name.trim()))
        }
    }

    fun renameNote(noteId: String, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            noteDao.rename(noteId, newTitle.trim())
            if (_currentDoc.value?.id == noteId) {
                _currentDoc.value = _currentDoc.value?.copy(title = newTitle.trim())
                saveCurrentDocumentNow()
            }
        }
    }

    fun toggleFavorite(note: NoteEntity) {
        viewModelScope.launch {
            noteDao.setFavorite(note.id, !note.isFavorite)
        }
    }

    fun moveNoteToFolder(noteId: String, folderId: String?) {
        viewModelScope.launch {
            noteDao.moveToFolder(noteId, folderId)
        }
    }

    fun moveToTrash(noteId: String) {
        viewModelScope.launch {
            noteDao.setTrashed(noteId, true)
        }
    }

    fun restoreFromTrash(noteId: String) {
        viewModelScope.launch {
            noteDao.setTrashed(noteId, false)
        }
    }

    fun deletePermanently(note: NoteEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            File(note.projectFilePath).delete()
            noteDao.deletePermanently(note.id)
        }
    }

    fun duplicateNote(note: NoteEntity) {
        viewModelScope.launch {
            val loaded = archiveManager.readArchiveFromFile(File(note.projectFilePath)).getOrNull() ?: return@launch
            val copyId = UUID.randomUUID().toString()
            val copyTitle = "${loaded.title} (Copy)"
            val copyDoc = loaded.copy(id = copyId, title = copyTitle, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            val outFile = archiveManager.saveDocument(copyDoc).getOrThrow()
            noteDao.upsert(
                note.copy(
                    id = copyId,
                    title = copyTitle,
                    projectFilePath = outFile.absolutePath,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun openNote(noteId: String) {
        viewModelScope.launch {
            val entity = noteDao.getById(noteId) ?: return@launch
            val doc = archiveManager.readArchiveFromFile(File(entity.projectFilePath)).getOrElse {
                NoteDocument(id = entity.id, title = entity.title)
            }
            undoStack.clear()
            redoStack.clear()
            clearSelection()
            _currentDoc.value = doc
            _rulerVisible.value = settings.value.showRulerByDefault
            loadPdfBackdropForActivePage(doc)
        }
    }

    fun importPdfAsNote(uri: Uri, title: String = "Annotated PDF", onOpened: (String) -> Unit = {}) {
        viewModelScope.launch {
            val res = pdfEngine.importPdfPages(uri)
            res.onSuccess { (assetName, count) ->
                val pages = (0 until count.coerceAtLeast(1)).map { idx ->
                    val layers = mutableListOf(
                        Layer(id = "layer_bg_$idx", name = "PDF Base", type = LayerType.BACKGROUND, locked = settings.value.lockBackgroundLayerByDefault),
                        Layer(id = "layer_ink_$idx", name = "Ink Notes", type = LayerType.INK)
                    )
                    if (settings.value.autoCreateAnnotationLayerForPdf) {
                        layers.add(Layer(id = "layer_ann_$idx", name = "PDF Annotations", type = LayerType.ANNOTATION))
                    }
                    NotePage(
                        pageIndex = idx,
                        template = PageTemplate.BLANK,
                        pdfAssetFileName = assetName,
                        pdfSourcePageIndex = idx,
                        layers = layers
                    )
                }
                val id = UUID.randomUUID().toString()
                val doc = NoteDocument(
                    id = id,
                    title = title,
                    isNotebook = true,
                    hasPdf = true,
                    pages = pages,
                    activeLayerId = pages.first().layers.last().id
                )
                val saved = archiveManager.saveDocument(doc).getOrThrow()
                noteDao.upsert(
                    NoteEntity(
                        id = id,
                        title = title,
                        isNotebook = true,
                        hasPdf = true,
                        pageCount = pages.size,
                        projectFilePath = saved.absolutePath
                    )
                )
                openNote(id)
                onOpened(id)
            }.onFailure { err ->
                showStatus("PDF import failed: ${err.message}")
            }
        }
    }

    fun importHarpenArchive(uri: Uri, onOpened: (String) -> Unit = {}) {
        viewModelScope.launch {
            safManager.importHarpenFromUri(uri).onSuccess { doc ->
                val saved = archiveManager.saveDocument(doc).getOrThrow()
                noteDao.upsert(
                    NoteEntity(
                        id = doc.id,
                        title = doc.title,
                        isNotebook = doc.isNotebook,
                        hasPdf = doc.hasPdf,
                        hasImages = doc.hasImages,
                        pageCount = doc.pages.size,
                        projectFilePath = saved.absolutePath,
                        extractedText = doc.extractedTextCache
                    )
                )
                openNote(doc.id)
                onOpened(doc.id)
            }.onFailure { err ->
                showStatus("Failed to open .harpen file: ${err.message}")
            }
        }
    }

    // --- Canvas Document Editing + Undo/Redo ---

    private fun pushUndoSnapshot(doc: NoteDocument) {
        val cap = settings.value.maxUndoHistory.coerceIn(10, 120)
        while (undoStack.size >= cap) undoStack.removeFirst()
        undoStack.addLast(doc)
        redoStack.clear()
    }

    private fun mutateActivePage(recordUndo: Boolean = true, transform: (NotePage) -> NotePage) {
        val doc = _currentDoc.value ?: return
        if (recordUndo) pushUndoSnapshot(doc)
        val idx = doc.activePageIndex.coerceIn(0, doc.pages.lastIndex)
        val updatedPages = doc.pages.toMutableList()
        updatedPages[idx] = transform(updatedPages[idx])
        _currentDoc.value = doc.copy(pages = updatedPages, updatedAt = System.currentTimeMillis())
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        _currentDoc.value?.let { redoStack.addLast(it) }
        _currentDoc.value = prev
        loadPdfBackdropForActivePage(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        _currentDoc.value?.let { undoStack.addLast(it) }
        _currentDoc.value = next
        loadPdfBackdropForActivePage(next)
    }

    fun addStroke(stroke: VectorStroke) {
        mutateActivePage { page -> page.copy(strokes = page.strokes + stroke) }
    }

    fun eraseStrokes(strokeIds: List<String>) {
        if (strokeIds.isEmpty()) return
        val idSet = strokeIds.toSet()
        mutateActivePage { page -> page.copy(strokes = page.strokes.filterNot { it.id in idSet }) }
    }

    fun selectTool(type: PenToolType) {
        _selectionMode.value = SelectionMode.NONE
        _eyedropperActive.value = false
        _toolConfig.value = _toolConfig.value.copy(
            toolType = type,
            width = type.defaultWidth,
            opacity = type.defaultOpacity,
            smoothing = type.defaultSmoothing,
            texture = type.defaultTexture
        )
    }

    fun updateToolConfig(transform: (ToolConfig) -> ToolConfig) {
        _toolConfig.value = transform(_toolConfig.value)
    }

    fun setSelectionMode(mode: SelectionMode) {
        _selectionMode.value = mode
        _eyedropperActive.value = false
        if (mode == SelectionMode.NONE) clearSelection()
    }

    fun setSelection(strokes: Set<String>, texts: Set<String>, images: Set<String>) {
        _selectedStrokeIds.value = strokes
        _selectedTextIds.value = texts
        _selectedImageIds.value = images
    }

    fun clearSelection() {
        _selectedStrokeIds.value = emptySet()
        _selectedTextIds.value = emptySet()
        _selectedImageIds.value = emptySet()
    }

    fun moveSelectedObjects(dx: Float, dy: Float) {
        val sIds = _selectedStrokeIds.value
        val tIds = _selectedTextIds.value
        val iIds = _selectedImageIds.value
        if (sIds.isEmpty() && tIds.isEmpty() && iIds.isEmpty()) return

        mutateActivePage(recordUndo = false) { page ->
            page.copy(
                strokes = page.strokes.map { s ->
                    if (s.id in sIds && !s.locked) {
                        s.copy(transform = s.transform.copy(translateX = s.transform.translateX + dx, translateY = s.transform.translateY + dy))
                    } else s
                },
                textObjects = page.textObjects.map { t ->
                    if (t.id in tIds && !t.locked) t.copy(x = t.x + dx, y = t.y + dy) else t
                },
                imageObjects = page.imageObjects.map { img ->
                    if (img.id in iIds && !img.locked) img.copy(x = img.x + dx, y = img.y + dy) else img
                }
            )
        }
    }

    fun deleteSelectedObjects() {
        val sIds = _selectedStrokeIds.value
        val tIds = _selectedTextIds.value
        val iIds = _selectedImageIds.value
        mutateActivePage { page ->
            page.copy(
                strokes = page.strokes.filterNot { it.id in sIds && !it.locked },
                textObjects = page.textObjects.filterNot { it.id in tIds && !it.locked },
                imageObjects = page.imageObjects.filterNot { it.id in iIds && !it.locked }
            )
        }
        clearSelection()
    }

    fun duplicateSelectedObjects() {
        val sIds = _selectedStrokeIds.value
        val tIds = _selectedTextIds.value
        val iIds = _selectedImageIds.value
        mutateActivePage { page ->
            val extraStrokes = page.strokes.filter { it.id in sIds }.map {
                it.copy(id = UUID.randomUUID().toString(), transform = it.transform.copy(translateX = it.transform.translateX + 28f, translateY = it.transform.translateY + 28f))
            }
            val extraTexts = page.textObjects.filter { it.id in tIds }.map {
                it.copy(id = UUID.randomUUID().toString(), x = it.x + 28f, y = it.y + 28f)
            }
            val extraImgs = page.imageObjects.filter { it.id in iIds }.map {
                it.copy(id = UUID.randomUUID().toString(), x = it.x + 28f, y = it.y + 28f)
            }
            page.copy(
                strokes = page.strokes + extraStrokes,
                textObjects = page.textObjects + extraTexts,
                imageObjects = page.imageObjects + extraImgs
            )
        }
    }

    fun copySelectedObjects(cut: Boolean = false) {
        val doc = _currentDoc.value ?: return
        val page = doc.pages.getOrNull(doc.activePageIndex) ?: return
        clipboard = ClipboardPayload(
            strokes = page.strokes.filter { it.id in _selectedStrokeIds.value },
            texts = page.textObjects.filter { it.id in _selectedTextIds.value },
            images = page.imageObjects.filter { it.id in _selectedImageIds.value }
        )
        if (cut) deleteSelectedObjects()
    }

    fun pasteClipboard() {
        val payload = clipboard ?: return
        mutateActivePage { page ->
            page.copy(
                strokes = page.strokes + payload.strokes.map {
                    it.copy(id = UUID.randomUUID().toString(), transform = it.transform.copy(translateX = it.transform.translateX + 32f, translateY = it.transform.translateY + 32f))
                },
                textObjects = page.textObjects + payload.texts.map {
                    it.copy(id = UUID.randomUUID().toString(), x = it.x + 32f, y = it.y + 32f)
                },
                imageObjects = page.imageObjects + payload.images.map {
                    it.copy(id = UUID.randomUUID().toString(), x = it.x + 32f, y = it.y + 32f)
                }
            )
        }
    }

    fun flipSelectedObjects(horizontal: Boolean) {
        val sIds = _selectedStrokeIds.value
        val iIds = _selectedImageIds.value
        mutateActivePage { page ->
            page.copy(
                strokes = page.strokes.map { s ->
                    if (s.id in sIds) s.copy(
                        transform = if (horizontal) s.transform.copy(flipHorizontal = !s.transform.flipHorizontal)
                        else s.transform.copy(flipVertical = !s.transform.flipVertical)
                    ) else s
                },
                imageObjects = page.imageObjects.map { img ->
                    if (img.id in iIds) {
                        if (horizontal) img.copy(flipHorizontal = !img.flipHorizontal)
                        else img.copy(flipVertical = !img.flipVertical)
                    } else img
                }
            )
        }
    }

    fun rotateSelectedObjects(deltaDeg: Float) {
        val sIds = _selectedStrokeIds.value
        val tIds = _selectedTextIds.value
        val iIds = _selectedImageIds.value
        mutateActivePage { page ->
            page.copy(
                strokes = page.strokes.map { s ->
                    if (s.id in sIds) s.copy(transform = s.transform.copy(rotationDeg = (s.transform.rotationDeg + deltaDeg) % 360f)) else s
                },
                textObjects = page.textObjects.map { t ->
                    if (t.id in tIds) t.copy(rotationDeg = (t.rotationDeg + deltaDeg) % 360f) else t
                },
                imageObjects = page.imageObjects.map { img ->
                    if (img.id in iIds) img.copy(rotationDeg = (img.rotationDeg + deltaDeg) % 360f) else img
                }
            )
        }
    }

    fun recolorSelectedStrokes(colorArgb: Long) {
        val sIds = _selectedStrokeIds.value
        val tIds = _selectedTextIds.value
        mutateActivePage { page ->
            page.copy(
                strokes = page.strokes.map { if (it.id in sIds) it.copy(colorArgb = colorArgb) else it },
                textObjects = page.textObjects.map { if (it.id in tIds) it.copy(colorArgb = colorArgb) else it }
            )
        }
    }

    fun toggleLockSelectedObjects() {
        val sIds = _selectedStrokeIds.value
        val tIds = _selectedTextIds.value
        val iIds = _selectedImageIds.value
        mutateActivePage { page ->
            page.copy(
                strokes = page.strokes.map { if (it.id in sIds) it.copy(locked = !it.locked) else it },
                textObjects = page.textObjects.map { if (it.id in tIds) it.copy(locked = !it.locked) else it },
                imageObjects = page.imageObjects.map { if (it.id in iIds) it.copy(locked = !it.locked) else it }
            )
        }
    }

    // --- Text & Image Objects ---

    fun addOrUpdateTextObject(textObj: TextObject) {
        mutateActivePage { page ->
            val exists = page.textObjects.any { it.id == textObj.id }
            val next = if (exists) {
                page.textObjects.map { if (it.id == textObj.id) textObj else it }
            } else {
                page.textObjects + textObj
            }
            page.copy(textObjects = next)
        }
        // Update extracted text index for library search
        _currentDoc.value?.let { doc ->
            val allText = doc.pages.flatMap { p -> p.textObjects.map { it.text } }.joinToString("\n")
            _currentDoc.value = doc.copy(extractedTextCache = allText)
        }
    }

    fun insertBitmapAsImageObject(bitmap: Bitmap, asBackground: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = "img_${UUID.randomUUID()}.png"
            val outFile = archiveManager.getAssetFile(fileName)
            FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 95, it) }
            val doc = _currentDoc.value ?: return@launch
            val layerId = if (asBackground) doc.pages[doc.activePageIndex].layers.first().id else doc.activeLayerId
            val ratio = bitmap.height.toFloat() / bitmap.width.toFloat().coerceAtLeast(1f)
            val width = 520f
            val img = ImageObject(
                layerId = layerId,
                assetFileName = fileName,
                width = width,
                height = width * ratio,
                isBackground = asBackground
            )
            withContext(Dispatchers.Main) {
                mutateActivePage { page ->
                    page.copy(imageObjects = page.imageObjects + img)
                }
                _currentDoc.value = _currentDoc.value?.copy(hasImages = true)
            }
        }
    }

    fun importImageFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@launch
            insertBitmapAsImageObject(bmp)
        }
    }

    // --- Layers ---

    fun setActiveLayer(layerId: String) {
        _currentDoc.value = _currentDoc.value?.copy(activeLayerId = layerId)
    }

    fun addLayer(name: String, type: LayerType = LayerType.INK) {
        val layer = Layer(name = name, type = type)
        mutateActivePage { page -> page.copy(layers = page.layers + layer) }
        setActiveLayer(layer.id)
    }

    fun updateLayer(layerId: String, transform: (Layer) -> Layer) {
        mutateActivePage { page ->
            page.copy(layers = page.layers.map { if (it.id == layerId) transform(it) else it })
        }
    }

    fun deleteLayer(layerId: String) {
        mutateActivePage { page ->
            if (page.layers.size <= 1) return@mutateActivePage page
            page.copy(
                layers = page.layers.filterNot { it.id == layerId },
                strokes = page.strokes.filterNot { it.layerId == layerId },
                textObjects = page.textObjects.filterNot { it.layerId == layerId },
                imageObjects = page.imageObjects.filterNot { it.layerId == layerId }
            )
        }
    }

    fun mergeLayerDown(layerId: String) {
        mutateActivePage { page ->
            val idx = page.layers.indexOfFirst { it.id == layerId }
            if (idx <= 0) return@mutateActivePage page
            val targetLayerId = page.layers[idx - 1].id
            page.copy(
                layers = page.layers.filterNot { it.id == layerId },
                strokes = page.strokes.map { if (it.layerId == layerId) it.copy(layerId = targetLayerId) else it },
                textObjects = page.textObjects.map { if (it.layerId == layerId) it.copy(layerId = targetLayerId) else it },
                imageObjects = page.imageObjects.map { if (it.layerId == layerId) it.copy(layerId = targetLayerId) else it }
            )
        }
    }

    fun reorderLayer(layerId: String, moveUp: Boolean) {
        mutateActivePage { page ->
            val list = page.layers.toMutableList()
            val idx = list.indexOfFirst { it.id == layerId }
            if (idx < 0) return@mutateActivePage page
            val targetIdx = if (moveUp) idx + 1 else idx - 1
            if (targetIdx !in list.indices) return@mutateActivePage page
            val item = list.removeAt(idx)
            list.add(targetIdx, item)
            page.copy(layers = list)
        }
    }

    fun clearLayer(layerId: String) {
        mutateActivePage { page ->
            page.copy(
                strokes = page.strokes.filterNot { it.layerId == layerId },
                textObjects = page.textObjects.filterNot { it.layerId == layerId },
                imageObjects = page.imageObjects.filterNot { it.layerId == layerId }
            )
        }
    }

    // --- Pages & Canvas Viewport ---

    fun switchPage(index: Int) {
        val doc = _currentDoc.value ?: return
        val safeIdx = index.coerceIn(0, doc.pages.lastIndex)
        val updated = doc.copy(activePageIndex = safeIdx, activeLayerId = doc.pages[safeIdx].layers.last().id)
        _currentDoc.value = updated
        clearSelection()
        loadPdfBackdropForActivePage(updated)
    }

    fun addPage(template: PageTemplate = settings.value.defaultPageTemplate) {
        val doc = _currentDoc.value ?: return
        pushUndoSnapshot(doc)
        val newPage = NotePage(
            pageIndex = doc.pages.size,
            template = template,
            sizePreset = settings.value.defaultPageSize,
            orientation = settings.value.defaultPageOrientation,
            backgroundColorArgb = settings.value.defaultPageBgArgb
        )
        val updated = doc.copy(
            pages = doc.pages + newPage,
            activePageIndex = doc.pages.size,
            activeLayerId = newPage.layers[1].id
        )
        _currentDoc.value = updated
        loadPdfBackdropForActivePage(updated)
    }

    fun duplicateCurrentPage() {
        val doc = _currentDoc.value ?: return
        pushUndoSnapshot(doc)
        val cur = doc.pages[doc.activePageIndex]
        val copy = cur.copy(id = UUID.randomUUID().toString(), pageIndex = doc.pages.size)
        _currentDoc.value = doc.copy(pages = doc.pages + copy, activePageIndex = doc.pages.size)
    }

    fun deleteCurrentPage() {
        val doc = _currentDoc.value ?: return
        if (doc.pages.size <= 1) return
        pushUndoSnapshot(doc)
        val nextPages = doc.pages.toMutableList().apply { removeAt(doc.activePageIndex) }
            .mapIndexed { idx, p -> p.copy(pageIndex = idx) }
        val nextIdx = doc.activePageIndex.coerceIn(0, nextPages.lastIndex)
        val updated = doc.copy(pages = nextPages, activePageIndex = nextIdx)
        _currentDoc.value = updated
        loadPdfBackdropForActivePage(updated)
    }

    fun toggleCurrentPageBookmark() {
        mutateActivePage(recordUndo = false) { it.copy(bookmarked = !it.bookmarked) }
    }

    fun setCurrentPageTemplate(template: PageTemplate) {
        mutateActivePage { it.copy(template = template) }
    }

    fun setCurrentPageBackground(colorArgb: Long) {
        mutateActivePage { it.copy(backgroundColorArgb = colorArgb) }
    }

    fun updateViewport(zoom: Float, panX: Float, panY: Float, rotationDeg: Float) {
        val doc = _currentDoc.value ?: return
        _currentDoc.value = doc.copy(zoomScale = zoom, panX = panX, panY = panY, canvasRotationDeg = rotationDeg)
    }

    fun toggleRuler() {
        _rulerVisible.value = !_rulerVisible.value
    }

    fun toggleEyedropper() {
        _eyedropperActive.value = !_eyedropperActive.value
    }

    fun onPalmGuardStatus(status: PalmGuardStatus) {
        _palmGuardStatus.value = status
    }

    private fun loadPdfBackdropForActivePage(doc: NoteDocument) {
        val page = doc.pages.getOrNull(doc.activePageIndex) ?: return
        val pdfFile = page.pdfAssetFileName
        val pdfPageIdx = page.pdfSourcePageIndex
        if (pdfFile != null && pdfPageIdx != null) {
            viewModelScope.launch {
                val width = if (settings.value.performanceMode.name == "LOW_MEMORY_4GB") 1000 else 1400
                _pdfBackdrop.value = pdfEngine.renderPdfPageBitmap(pdfFile, pdfPageIdx, width)
            }
        } else {
            _pdfBackdrop.value = null
        }
    }

    // --- Persistence, Export & Autosave ---

    fun saveCurrentDocumentNow(onSaved: () -> Unit = {}) {
        val doc = _currentDoc.value ?: return
        viewModelScope.launch {
            val file = archiveManager.saveDocument(doc).getOrNull() ?: return@launch
            val existing = noteDao.getById(doc.id)
            noteDao.upsert(
                NoteEntity(
                    id = doc.id,
                    title = doc.title,
                    folderId = doc.folderId,
                    isNotebook = doc.isNotebook,
                    isFavorite = doc.isFavorite,
                    isTrashed = doc.isTrashed,
                    hasPdf = doc.hasPdf,
                    hasImages = doc.hasImages,
                    templateName = doc.pages.firstOrNull()?.template?.name ?: "DOTTED",
                    pageCount = doc.pages.size,
                    projectFilePath = file.absolutePath,
                    extractedText = doc.extractedTextCache,
                    createdAt = existing?.createdAt ?: doc.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
            )
            if (settings.value.keepCrashRecoverySnapshots) {
                archiveManager.saveRecoverySnapshot(doc, settings.value.maxRecoverySnapshots)
            }
            onSaved()
        }
    }

    fun exportCurrentPdfAndShare(allPages: Boolean, highQuality: Boolean) {
        val doc = _currentDoc.value ?: return
        viewModelScope.launch {
            val indices = if (allPages) doc.pages.indices.toList() else listOf(doc.activePageIndex)
            val exportDir = File(app.cacheDir, "exports").apply { mkdirs() }
            val outFile = File(exportDir, "${doc.title.replace(" ", "_")}.pdf")
            val opts = PdfExportOptions(
                pageIndices = indices,
                sizePreset = doc.pages[doc.activePageIndex].sizePreset,
                orientation = doc.pages[doc.activePageIndex].orientation,
                marginPt = settings.value.pdfExportMarginPt,
                includeBackground = settings.value.pdfExportIncludeBackground,
                includeImages = settings.value.pdfExportIncludeImages,
                includeAnnotations = settings.value.pdfExportIncludeAnnotations,
                highQuality = highQuality
            )
            pdfEngine.exportToPdf(doc, opts, outFile).onSuccess { file ->
                pdfEngine.shareFileViaSharesheet(file, "application/pdf", "Export HARPEN PDF")
            }.onFailure { err ->
                showStatus("PDF export error: ${err.message}")
            }
        }
    }

    fun exportCurrentPageImageAndShare(isPng: Boolean) {
        val doc = _currentDoc.value ?: return
        val page = doc.pages.getOrNull(doc.activePageIndex) ?: return
        viewModelScope.launch {
            val exportDir = File(app.cacheDir, "exports").apply { mkdirs() }
            val ext = if (isPng) "png" else "jpg"
            val outFile = File(exportDir, "${doc.title.replace(" ", "_")}_p${doc.activePageIndex + 1}.$ext")
            pdfEngine.exportPageAsImage(
                page = page,
                outFile = outFile,
                format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                resolutionScale = settings.value.imageExportResolutionScale,
                transparentBg = isPng && settings.value.pngExportTransparentBg
            ).onSuccess { file ->
                pdfEngine.shareFileViaSharesheet(file, if (isPng) "image/png" else "image/jpeg", "Share Page Image")
            }
        }
    }

    fun exportExtractedTextAndShare() {
        val doc = _currentDoc.value ?: return
        val text = doc.pages.flatMap { p -> p.textObjects.map { it.text } }.joinToString("\n\n")
        val exportDir = File(app.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(exportDir, "${doc.title.replace(" ", "_")}.txt")
        outFile.writeText(text.ifBlank { "No text objects in ${doc.title}." })
        pdfEngine.shareFileViaSharesheet(outFile, "text/plain", "Export Extracted Text")
    }

    fun updateSettings(transform: (HarpenSettings) -> HarpenSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    fun resetSettingsCategory(category: SettingsCategory) {
        viewModelScope.launch { settingsRepo.resetCategory(category) }
    }

    fun resetAllSettings() {
        viewModelScope.launch { settingsRepo.resetAll() }
    }

    private fun startAutosaveLoop() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            while (true) {
                val intervalSec = settings.value.autosaveIntervalSeconds.coerceIn(5, 300)
                delay(intervalSec * 1000L)
                if (_currentDoc.value != null) {
                    saveCurrentDocumentNow()
                }
            }
        }
    }
}
