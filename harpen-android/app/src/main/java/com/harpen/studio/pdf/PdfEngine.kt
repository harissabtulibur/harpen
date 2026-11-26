package com.harpen.studio.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.harpen.studio.canvas.StrokeRenderer
import com.harpen.studio.data.format.HarpenArchiveManager
import com.harpen.studio.model.LayerType
import com.harpen.studio.model.NoteDocument
import com.harpen.studio.model.NotePage
import com.harpen.studio.model.PageOrientation
import com.harpen.studio.model.PageSizePreset
import com.harpen.studio.model.TextAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class PdfExportOptions(
    val pageIndices: List<Int>,
    val sizePreset: PageSizePreset = PageSizePreset.A4,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val marginPt: Int = 18,
    val includeBackground: Boolean = true,
    val includeImages: Boolean = true,
    val includeAnnotations: Boolean = true,
    val highQuality: Boolean = true
)

class PdfEngine(
    private val context: Context,
    private val archiveManager: HarpenArchiveManager
) {
    private val strokeRenderer = StrokeRenderer()

    suspend fun importPdfPages(uri: Uri): Result<Pair<String, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val assetName = "pdf_${UUID.randomUUID()}.pdf"
            val outFile = archiveManager.getAssetFile(assetName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { input.copyTo(it) }
            } ?: error("Unable to open PDF URI")

            val pfd = ParcelFileDescriptor.open(outFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val pageCount = pfd.use {
                PdfRenderer(it).use { renderer -> renderer.pageCount }
            }
            assetName to pageCount
        }
    }

    suspend fun renderPdfPageBitmap(
        pdfAssetFileName: String,
        pageIndex: Int,
        targetWidthPx: Int = 1240
    ): Bitmap? = withContext(Dispatchers.IO) {
        val file = archiveManager.getAssetFile(pdfAssetFileName)
        if (!file.exists()) return@withContext null
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return@use null
                    renderer.openPage(pageIndex).use { page ->
                        val ratio = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                        val w = targetWidthPx.coerceIn(400, 2000)
                        val h = (w * ratio).toInt().coerceAtLeast(400)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }.getOrNull()
    }

    suspend fun exportToPdf(
        document: NoteDocument,
        options: PdfExportOptions,
        outFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val pdfDoc = PdfDocument()
            val scaleFactor = if (options.highQuality) 2f else 1f

            options.pageIndices.forEachIndexed { exportIdx, pageIdx ->
                val page = document.pages.getOrNull(pageIdx) ?: return@forEachIndexed
                val rawW = if (options.sizePreset == PageSizePreset.CUSTOM) page.customWidthPt else options.sizePreset.widthPt
                val rawH = if (options.sizePreset == PageSizePreset.CUSTOM) page.customHeightPt else options.sizePreset.heightPt
                val pageW = (if (options.orientation == PageOrientation.PORTRAIT) rawW else rawH) * scaleFactor
                val pageH = (if (options.orientation == PageOrientation.PORTRAIT) rawH else rawW) * scaleFactor

                val pageInfo = PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), exportIdx + 1).create()
                val pdfPage = pdfDoc.startPage(pageInfo)
                renderNotePageToCanvas(
                    canvas = pdfPage.canvas,
                    page = page,
                    widthPx = pageW,
                    heightPx = pageH,
                    marginPx = options.marginPt * scaleFactor,
                    includeBackground = options.includeBackground,
                    includeImages = options.includeImages,
                    includeAnnotations = options.includeAnnotations
                )
                pdfDoc.finishPage(pdfPage)
            }

            FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
            pdfDoc.close()
            outFile
        }
    }

    suspend fun exportPageAsImage(
        page: NotePage,
        outFile: File,
        format: Bitmap.CompressFormat,
        resolutionScale: Float = 2f,
        transparentBg: Boolean = false
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val w = (page.sizePreset.widthPt * resolutionScale).toInt().coerceIn(595, 2400)
            val h = (page.sizePreset.heightPt * resolutionScale).toInt().coerceIn(842, 3400)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            if (!transparentBg) {
                canvas.drawColor(page.backgroundColorArgb.toInt())
            }
            renderNotePageToCanvas(
                canvas = canvas,
                page = page,
                widthPx = w.toFloat(),
                heightPx = h.toFloat(),
                marginPx = 0f,
                includeBackground = !transparentBg,
                includeImages = true,
                includeAnnotations = true
            )
            FileOutputStream(outFile).use { bmp.compress(format, 95, it) }
            bmp.recycle()
            outFile
        }
    }

    private fun renderNotePageToCanvas(
        canvas: Canvas,
        page: NotePage,
        widthPx: Float,
        heightPx: Float,
        marginPx: Float,
        includeBackground: Boolean,
        includeImages: Boolean,
        includeAnnotations: Boolean
    ) {
        if (includeBackground) {
            canvas.drawColor(page.backgroundColorArgb.toInt())
            page.pdfAssetFileName?.let { asset ->
                val file = archiveManager.getAssetFile(asset)
                if (file.exists()) {
                    runCatching {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                            PdfRenderer(pfd).use { renderer ->
                                val idx = page.pdfSourcePageIndex ?: 0
                                if (idx in 0 until renderer.pageCount) {
                                    renderer.openPage(idx).use { pdfPage ->
                                        val bmp = Bitmap.createBitmap(widthPx.toInt(), heightPx.toInt(), Bitmap.Config.ARGB_8888)
                                        bmp.eraseColor(Color.WHITE)
                                        pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                        canvas.drawBitmap(bmp, marginPx, marginPx, null)
                                        bmp.recycle()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val scaleX = (widthPx - marginPx * 2f) / (page.sizePreset.widthPt * 2f)
        val scaleY = (heightPx - marginPx * 2f) / (page.sizePreset.heightPt * 2f)
        canvas.save()
        canvas.translate(marginPx, marginPx)
        canvas.scale(scaleX, scaleY)

        val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (layer in page.layers) {
            if (!layer.visible) continue
            if (!includeAnnotations && layer.type == LayerType.ANNOTATION) continue

            if (includeImages) {
                page.imageObjects.filter { it.layerId == layer.id }.forEach { img ->
                    val f = archiveManager.getAssetFile(img.assetFileName)
                    if (f.exists()) {
                        BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                            imgPaint.alpha = (img.opacity * layer.opacity * 255).toInt()
                            canvas.drawBitmap(bmp, null, RectF(img.x, img.y, img.x + img.width, img.y + img.height), imgPaint)
                            bmp.recycle()
                        }
                    }
                }
            }

            page.strokes.filter { it.layerId == layer.id }.forEach { stroke ->
                strokeRenderer.drawStroke(canvas, stroke, layer.opacity, useHardwarePressure = true)
            }

            page.textObjects.filter { it.layerId == layer.id }.forEach { t ->
                txtPaint.color = t.colorArgb.toInt()
                txtPaint.textSize = t.fontSizeSp * 2f
                txtPaint.isFakeBoldText = t.bold
                txtPaint.textAlign = when (t.alignment) {
                    TextAlignment.LEFT -> Paint.Align.LEFT
                    TextAlignment.CENTER -> Paint.Align.CENTER
                    TextAlignment.RIGHT -> Paint.Align.RIGHT
                }
                var y = t.y + txtPaint.textSize
                t.text.split("\n").forEach { line ->
                    canvas.drawText(line, t.x, y, txtPaint)
                    y += txtPaint.textSize * t.lineSpacing
                }
            }
        }
        canvas.restore()
    }

    fun shareFileViaSharesheet(file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
