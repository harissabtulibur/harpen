package com.harpen.studio.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.harpen.studio.data.format.HarpenArchiveManager
import com.harpen.studio.model.NoteDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StorageStats(
    val projectsBytes: Long,
    val assetsBytes: Long,
    val recoveryBytes: Long,
    val cacheBytes: Long
) {
    val totalBytes: Long get() = projectsBytes + assetsBytes + recoveryBytes + cacheBytes
}

class SafStorageManager(
    private val context: Context,
    private val archiveManager: HarpenArchiveManager
) {

    fun persistTreePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }
    }

    suspend fun backupDocumentToFolder(
        document: NoteDocument,
        treeUri: Uri
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Selected storage folder is unavailable")
            if (!tree.canWrite()) {
                error("Selected folder (SD/USB/External) is read-only or permission expired.")
            }
            val safeTitle = document.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val fileName = "${safeTitle}_${document.id.take(6)}.harpen"
            tree.findFile(fileName)?.delete()
            val targetDoc = tree.createFile("application/vnd.harpen.project", fileName)
                ?: error("Unable to create backup file in target storage")
            context.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                archiveManager.writeArchiveToStream(document, out)
            } ?: error("Failed to open output stream for SAF URI")
            targetDoc.name ?: fileName
        }
    }

    suspend fun exportDocumentToUri(
        document: NoteDocument,
        uri: Uri,
        password: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                archiveManager.writeArchiveToStream(document, out, password)
            } ?: error("Cannot write to selected file URI")
            Unit
        }
    }

    suspend fun importHarpenFromUri(uri: Uri, password: String? = null): Result<NoteDocument> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    archiveManager.readArchiveFromStream(input, password).getOrThrow()
                } ?: error("Cannot read selected .harpen file")
            }
        }

    suspend fun computeStorageStats(): StorageStats = withContext(Dispatchers.IO) {
        StorageStats(
            projectsBytes = dirSize(archiveManager.projectsDir),
            assetsBytes = dirSize(archiveManager.assetsDir),
            recoveryBytes = dirSize(archiveManager.recoveryDir),
            cacheBytes = dirSize(context.cacheDir)
        )
    }

    suspend fun clearCache(): Long = withContext(Dispatchers.IO) {
        val before = dirSize(context.cacheDir)
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        before
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
