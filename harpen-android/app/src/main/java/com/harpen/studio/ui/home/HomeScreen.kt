package com.harpen.studio.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harpen.studio.data.local.FolderEntity
import com.harpen.studio.data.local.NoteEntity
import com.harpen.studio.model.CanvasMode
import com.harpen.studio.model.PageTemplate
import com.harpen.studio.ui.state.HarpenViewModel
import com.harpen.studio.ui.state.HomeSection
import com.harpen.studio.ui.state.SortOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HarpenViewModel,
    onOpenWorkspace: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val activeNotes by viewModel.activeNotes.collectAsState()
    val trashedNotes by viewModel.trashedNotes.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var selectedSection by remember { mutableStateOf(HomeSection.RECENT) }
    var selectedFolderId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(SortOrder.UPDATED_DESC) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<NoteEntity?>(null) }
    var moveTarget by remember { mutableStateOf<NoteEntity?>(null) }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importPdfAsNote(uri, title = "Imported PDF") { onOpenWorkspace() }
        }
    }

    val harpenPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importHarpenArchive(uri) { onOpenWorkspace() }
        }
    }

    val filteredNotes = remember(activeNotes, trashedNotes, selectedSection, selectedFolderId, searchQuery, sortOrder) {
        val base = when (selectedSection) {
            HomeSection.TRASH -> trashedNotes
            HomeSection.FAVORITES -> activeNotes.filter { it.isFavorite }
            HomeSection.NOTEBOOKS -> activeNotes.filter { it.isNotebook }
            HomeSection.PDF_DOCUMENTS -> activeNotes.filter { it.hasPdf }
            HomeSection.IMAGES -> activeNotes.filter { it.hasImages }
            else -> activeNotes
        }.let { list ->
            if (selectedFolderId != null && selectedSection == HomeSection.ALL_NOTES) {
                list.filter { it.folderId == selectedFolderId }
            } else list
        }.let { list ->
            if (searchQuery.isBlank()) list
            else list.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.extractedText.contains(searchQuery, ignoreCase = true)
            }
        }

        when (sortOrder) {
            SortOrder.UPDATED_DESC -> base.sortedByDescending { it.updatedAt }
            SortOrder.CREATED_DESC -> base.sortedByDescending { it.createdAt }
            SortOrder.TITLE_ASC -> base.sortedBy { it.title.lowercase() }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val isCompactWidth = maxWidth < 760.dp

        Row(modifier = Modifier.fillMaxSize()) {
            // Adaptive Tablet Navigation Sidebar
            Surface(
                modifier = Modifier
                    .width(if (isCompactWidth) 76.dp else 248.dp)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 16.dp, horizontal = if (isCompactWidth) 8.dp else 14.dp)
                ) {
                    if (!isCompactWidth) {
                        Text(
                            text = "HARPEN",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Your thoughts. Your strokes. Your world.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                    } else {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Draw, contentDescription = "HARPEN", tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(HomeSection.entries) { section ->
                            val selected = selectedSection == section
                            NavigationDrawerItem(
                                selected = selected,
                                onClick = {
                                    if (section == HomeSection.SETTINGS) {
                                        onOpenSettings()
                                    } else {
                                        selectedSection = section
                                        selectedFolderId = null
                                    }
                                },
                                icon = { Icon(sectionIcon(section), contentDescription = section.title) },
                                label = {
                                    if (!isCompactWidth) {
                                        Text(section.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            )
                        }

                        if (!isCompactWidth && folders.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Folders",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                            items(folders) { folder ->
                                NavigationDrawerItem(
                                    selected = selectedFolderId == folder.id,
                                    onClick = {
                                        selectedSection = HomeSection.ALL_NOTES
                                        selectedFolderId = if (selectedFolderId == folder.id) null else folder.id
                                    },
                                    icon = { Icon(Icons.Outlined.Folder, contentDescription = folder.name) },
                                    label = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                )
                            }
                        }
                    }

                    if (!isCompactWidth) {
                        OutlinedButton(
                            onClick = { showFolderDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("New Folder")
                        }
                    }
                }
            }

            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            // Main Content Area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(20.dp)
            ) {
                // Top Action & Search Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search notes, notebooks, or extracted OCR text…") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "Search") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )

                    var sortExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { sortExpanded = true }) {
                            Icon(Icons.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                            if (!isCompactWidth) {
                                Spacer(Modifier.width(6.dp))
                                Text(sortOrder.displayName)
                            }
                        }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            SortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.displayName) },
                                    onClick = {
                                        sortOrder = order
                                        sortExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Button(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Create")
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Quick Studio Welcome Strip (when enabled & on Recent)
                if (settings.showWelcomeBanner && selectedSection == HomeSection.RECENT) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Offline Creative Studio",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Vector ink, PalmGuard protection, offline Camera OCR, and native .harpen archives — locally stored on your tablet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) {
                                    Text("Import PDF")
                                }
                                OutlinedButton(onClick = { harpenPicker.launch(arrayOf("*/*")) }) {
                                    Text("Open .harpen")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                if (selectedSection == HomeSection.TEMPLATES) {
                    TemplateGalleryGrid(
                        onPickTemplate = { template ->
                            viewModel.createNewNote(
                                title = "${template.displayName} Note",
                                template = template
                            ) { onOpenWorkspace() }
                        }
                    )
                } else if (filteredNotes.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.NoteAdd,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = if (selectedSection == HomeSection.TRASH) "Trash is empty" else "No items in ${selectedSection.title}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            if (selectedSection != HomeSection.TRASH) {
                                Button(onClick = {
                                    viewModel.createNewNote { onOpenWorkspace() }
                                }) {
                                    Text("Create Your First Note")
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredNotes, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                isTrash = selectedSection == HomeSection.TRASH,
                                onOpen = {
                                    viewModel.openNote(note.id)
                                    onOpenWorkspace()
                                },
                                onFavorite = { viewModel.toggleFavorite(note) },
                                onRename = { renameTarget = note },
                                onDuplicate = { viewModel.duplicateNote(note) },
                                onMove = { moveTarget = note },
                                onTrash = { viewModel.moveToTrash(note.id) },
                                onRestore = { viewModel.restoreFromTrash(note.id) },
                                onDeleteForever = { viewModel.deletePermanently(note) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateNoteDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { title, isNotebook, template, canvasMode ->
                showCreateDialog = false
                viewModel.createNewNote(
                    title = title,
                    isNotebook = isNotebook,
                    template = template,
                    canvasMode = canvasMode,
                    folderId = selectedFolderId
                ) { onOpenWorkspace() }
            }
        )
    }

    if (showFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showFolderDialog = false },
            title = { Text("Create Folder") },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.createFolder(folderName)
                    showFolderDialog = false
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showFolderDialog = false }) { Text("Cancel") }
            }
        )
    }

    renameTarget?.let { target ->
        var newTitle by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    label = { Text("Title") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameNote(target.id, newTitle)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    moveTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { moveTarget = null },
            title = { Text("Move to Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        viewModel.moveNoteToFolder(target.id, null)
                        moveTarget = null
                    }) { Text("Root (No Folder)") }
                    folders.forEach { folder ->
                        TextButton(onClick = {
                            viewModel.moveNoteToFolder(target.id, folder.id)
                            moveTarget = null
                        }) { Text(folder.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { moveTarget = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun NoteCard(
    note: NoteEntity,
    isTrash: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val dateLabel = remember(note.updatedAt) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(note.updatedAt))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !isTrash, onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (note.isNotebook) Color(note.coverColorArgb).copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(10.dp)
            ) {
                Icon(
                    imageVector = when {
                        note.hasPdf -> Icons.Outlined.PictureAsPdf
                        note.isNotebook -> Icons.Outlined.MenuBook
                        else -> Icons.Outlined.Description
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
                if (!isTrash) {
                    IconButton(
                        onClick = onFavorite,
                        modifier = Modifier.align(Alignment.TopEnd).size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (note.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                            contentDescription = "Favorite",
                            tint = if (note.isFavorite) Color(0xFFD97706) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${note.pageCount} pg · ${note.templateName.lowercase().replaceFirstChar { it.uppercase() }} · $dateLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (isTrash) {
                            DropdownMenuItem(text = { Text("Restore") }, onClick = { menuExpanded = false; onRestore() })
                            DropdownMenuItem(text = { Text("Delete Permanently") }, onClick = { menuExpanded = false; onDeleteForever() })
                        } else {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; onRename() })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuExpanded = false; onDuplicate() })
                            DropdownMenuItem(text = { Text("Move to Folder") }, onClick = { menuExpanded = false; onMove() })
                            DropdownMenuItem(text = { Text("Move to Trash") }, onClick = { menuExpanded = false; onTrash() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateGalleryGrid(onPickTemplate: (PageTemplate) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 180.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(PageTemplate.entries) { template ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onPickTemplate(template) }
                    .padding(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.GridOn, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(template.displayName, style = MaterialTheme.typography.titleSmall)
                    Text("Tap to start", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CreateNoteDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Boolean, PageTemplate, CanvasMode) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var isNotebook by remember { mutableStateOf(false) }
    var template by remember { mutableStateOf(PageTemplate.DOTTED) }
    var canvasMode by remember { mutableStateOf(CanvasMode.PAGED) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New HARPEN Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !isNotebook, onClick = { isNotebook = false }, label = { Text("Quick Note") })
                    FilterChip(selected = isNotebook, onClick = { isNotebook = true }, label = { Text("Notebook") })
                }
                Text("Canvas Mode", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CanvasMode.entries.take(3).forEach { mode ->
                        FilterChip(
                            selected = canvasMode == mode,
                            onClick = { canvasMode = mode },
                            label = { Text(mode.displayName.substringBefore(" ")) }
                        )
                    }
                }
                Text("Template", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(PageTemplate.DOTTED, PageTemplate.RULED, PageTemplate.GRID, PageTemplate.BLANK, PageTemplate.CORNELL).forEach { t ->
                        FilterChip(selected = template == t, onClick = { template = t }, label = { Text(t.displayName) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(title, isNotebook, template, canvasMode) }) {
                Text("Create & Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun sectionIcon(section: HomeSection): ImageVector = when (section) {
    HomeSection.RECENT -> Icons.Outlined.Schedule
    HomeSection.ALL_NOTES -> Icons.Outlined.Description
    HomeSection.NOTEBOOKS -> Icons.Outlined.MenuBook
    HomeSection.FAVORITES -> Icons.Outlined.StarOutline
    HomeSection.TEMPLATES -> Icons.Outlined.GridOn
    HomeSection.PDF_DOCUMENTS -> Icons.Outlined.PictureAsPdf
    HomeSection.IMAGES -> Icons.Outlined.Image
    HomeSection.TRASH -> Icons.Outlined.DeleteOutline
    HomeSection.SETTINGS -> Icons.Outlined.Settings
}
