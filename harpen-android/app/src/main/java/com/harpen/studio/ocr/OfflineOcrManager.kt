package com.harpen.studio.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.harpen.studio.model.VectorStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfflineOcrManager {

    private val latinRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeBitmapOffline(bitmap: Bitmap): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { cont ->
                latinRecognizer.process(image)
                    .addOnSuccessListener { result ->
                        cont.resume(result.text)
                    }
                    .addOnFailureListener { err ->
                        cont.resumeWithException(err)
                    }
            }
        }
    }

    fun enhanceDocumentBitmap(
        source: Bitmap,
        rotationDeg: Float = 0f,
        contrastEnhance: Boolean = true,
        grayscale: Boolean = true
    ): Bitmap {
        val rotated = if (rotationDeg % 360f != 0f) {
            val m = Matrix().apply { postRotate(rotationDeg) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        } else {
            source
        }

        if (!contrastEnhance && !grayscale) return rotated

        val output = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cm = ColorMatrix()
        if (grayscale) {
            cm.setSaturation(0f)
        }
        if (contrastEnhance) {
            val contrast = 1.32f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            cm.postConcat(contrastMatrix)
        }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(rotated, 0f, 0f, paint)
        return output
    }

    /**
     * Offline handwriting-to-text using ML Kit Digital Ink when local language model is downloaded.
     * Never silently flattens handwriting into an image; returns Result failure with clear offline status if model isn't cached yet.
     */
    suspend fun recognizeHandwritingOffline(
        strokes: List<VectorStroke>,
        languageTag: String = "en-US"
    ): Result<String> = withContext(Dispatchers.Default) {
        runCatching {
            if (strokes.isEmpty()) error("No handwriting strokes selected or present on layer.")
            val modelId = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
                ?: error("Unsupported handwriting recognition language tag: $languageTag")
            val model = DigitalInkRecognitionModel.builder(modelId).build()
            val remoteManager = com.google.mlkit.common.model.RemoteModelManager.getInstance()

            val isDownloaded = suspendCancellableCoroutine { cont ->
                remoteManager.isModelDownloaded(model)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }

            if (!isDownloaded) {
                error("Offline handwriting model ($languageTag) is not downloaded yet. Connect once to cache the offline pack or use Camera OCR.")
            }

            val inkBuilder = Ink.builder()
            for (stroke in strokes) {
                val sb = Ink.Stroke.builder()
                for (pt in stroke.points) {
                    sb.addPoint(Ink.Point.create(pt.x, pt.y, pt.timestamp))
                }
                inkBuilder.addStroke(sb.build())
            }

            val recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
            suspendCancellableCoroutine { cont ->
                recognizer.recognize(inkBuilder.build())
                    .addOnSuccessListener { result ->
                        val topText = result.candidates.firstOrNull()?.text.orEmpty()
                        cont.resume(topText)
                    }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        }
    }

    suspend fun downloadHandwritingModelIfOnline(languageTag: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
                ?: error("Unsupported language tag $languageTag")
            val model = DigitalInkRecognitionModel.builder(id).build()
            val manager = com.google.mlkit.common.model.RemoteModelManager.getInstance()
            suspendCancellableCoroutine { cont ->
                manager.download(model, DownloadConditions.Builder().build())
                    .addOnSuccessListener { cont.resume(Unit) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        }
    }
}
