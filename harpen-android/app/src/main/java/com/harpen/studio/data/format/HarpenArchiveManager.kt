package com.harpen.studio.data.format

import android.content.Context
import com.harpen.studio.model.NoteDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class HarpenManifest(
    val formatName: String = "HARPEN_PROJECT",
    val formatVersion: Int = 1,
    val minReaderVersion: Int = 1,
    val appVersion: String = "1.0.0",
    val encrypted: Boolean = false,
    val documentId: String,
    val title: String,
    val pageCount: Int,
    val savedAt: Long = System.currentTimeMillis()
)

class HarpenArchiveManager(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    val projectsDir: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    val assetsDir: File
        get() = File(context.filesDir, "assets").apply { mkdirs() }

    val recoveryDir: File
        get() = File(context.filesDir, "recovery").apply { mkdirs() }

    fun getProjectFile(noteId: String): File = File(projectsDir, "$noteId.harpen")

    fun getAssetFile(assetFileName: String): File = File(assetsDir, assetFileName)

    suspend fun saveDocument(
        document: NoteDocument,
        targetFile: File = getProjectFile(document.id),
        optionalPassword: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = File(targetFile.parentFile ?: projectsDir, "${targetFile.name}.tmp")
            tempFile.outputStream().use { out ->
                writeArchiveToStream(document, out, optionalPassword)
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            targetFile
        }
    }

    suspend fun saveRecoverySnapshot(document: NoteDocument, maxSnapshots: Int = 5) = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(recoveryDir, "${document.id}_${System.currentTimeMillis()}.harpen")
            file.outputStream().use { writeArchiveToStream(document, it, null) }
            val existing = recoveryDir.listFiles { f -> f.name.startsWith(document.id) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
            existing.drop(maxSnapshots).forEach { it.delete() }
        }
    }

    suspend fun writeArchiveToStream(
        document: NoteDocument,
        outputStream: OutputStream,
        optionalPassword: String? = null
    ) = withContext(Dispatchers.IO) {
        val docBytes = json.encodeToString(document).toByteArray(Charsets.UTF_8)
        val isEncrypted = !optionalPassword.isNullOrEmpty()
        val payloadBytes = if (isEncrypted) {
            encryptBytes(docBytes, optionalPassword!!)
        } else {
            docBytes
        }

        val manifest = HarpenManifest(
            encrypted = isEncrypted,
            documentId = document.id,
            title = document.title,
            pageCount = document.pages.size
        )
        val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)

        ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestBytes)
            zip.closeEntry()

            val docEntryName = if (isEncrypted) "document.json.enc" else "document.json"
            zip.putNextEntry(ZipEntry(docEntryName))
            zip.write(payloadBytes)
            zip.closeEntry()

            val referencedAssets = buildSet {
                document.pages.forEach { page ->
                    page.pdfAssetFileName?.let { add(it) }
                    page.imageObjects.forEach { add(it.assetFileName) }
                }
            }

            referencedAssets.forEach { assetName ->
                val assetFile = getAssetFile(assetName)
                if (assetFile.exists()) {
                    zip.putNextEntry(ZipEntry("assets/$assetName"))
                    assetFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    suspend fun readArchiveFromFile(
        file: File,
        optionalPassword: String? = null
    ): Result<NoteDocument> = withContext(Dispatchers.IO) {
        runCatching {
            file.inputStream().use { readArchiveFromStream(it, optionalPassword).getOrThrow() }
        }
    }

    suspend fun readArchiveFromStream(
        inputStream: InputStream,
        optionalPassword: String? = null
    ): Result<NoteDocument> = withContext(Dispatchers.IO) {
        runCatching {
            var manifest: HarpenManifest? = null
            var rawDocBytes: ByteArray? = null
            var encryptedDocBytes: ByteArray? = null

            ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == "manifest.json" -> {
                            manifest = json.decodeFromString<HarpenManifest>(
                                zip.readBytes().toString(Charsets.UTF_8)
                            )
                        }
                        name == "document.json" -> {
                            rawDocBytes = zip.readBytes()
                        }
                        name == "document.json.enc" -> {
                            encryptedDocBytes = zip.readBytes()
                        }
                        name.startsWith("assets/") && !entry.isDirectory -> {
                            val assetName = name.removePrefix("assets/").replace("..", "")
                            val outFile = getAssetFile(assetName)
                            outFile.parentFile?.mkdirs()
                            outFile.writeBytes(zip.readBytes())
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val parsedManifest = manifest ?: error("Invalid .harpen file: missing manifest.json")
            if (parsedManifest.minReaderVersion > 1) {
                error("This .harpen file requires a newer version of HARPEN (v${parsedManifest.minReaderVersion}).")
            }

            val finalDocBytes = when {
                rawDocBytes != null -> rawDocBytes!!
                encryptedDocBytes != null -> {
                    val password = optionalPassword ?: error("Password required for encrypted .harpen archive")
                    decryptBytes(encryptedDocBytes!!, password)
                }
                else -> error("Invalid .harpen archive: missing document payload")
            }

            json.decodeFromString<NoteDocument>(finalDocBytes.toString(Charsets.UTF_8))
        }
    }

    private fun encryptBytes(plain: ByteArray, password: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16).apply { random.nextBytes(this) }
        val iv = ByteArray(12).apply { random.nextBytes(this) }
        val key = deriveAesKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plain)
        return ByteArrayOutputStream().apply {
            write(salt)
            write(iv)
            write(cipherText)
        }.toByteArray()
    }

    private fun decryptBytes(blob: ByteArray, password: String): ByteArray {
        require(blob.size > 28) { "Corrupted encrypted payload" }
        val input = ByteArrayInputStream(blob)
        val salt = ByteArray(16).also { input.read(it) }
        val iv = ByteArray(12).also { input.read(it) }
        val cipherText = input.readBytes()
        val key = deriveAesKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(cipherText)
    }

    private fun deriveAesKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 65_536, 256)
        val secret = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return SecretKeySpec(secret.encoded, "AES")
    }
}
