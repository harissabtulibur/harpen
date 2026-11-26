package com.harpen.studio.ui.ocr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.harpen.studio.model.OcrCorrectionMode
import com.harpen.studio.model.OcrLanguageMode
import com.harpen.studio.model.TextObject
import com.harpen.studio.ocr.IndonesianEnglishCorrector
import com.harpen.studio.ocr.OcrCorrectionResult
import com.harpen.studio.ocr.TextCorrectionChange
import com.harpen.studio.ui.state.HarpenViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScanScreen(
    viewModel: HarpenViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settings by viewModel.settings.collectAsState()
    val doc by viewModel.currentDoc.collectAsState()
    val scope = rememberCoroutineScope()

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPerm = granted
    }

    var useFrontCamera by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var imageCaptureRef by remember { mutableStateOf<ImageCapture?>(null) }

    val scannedPages = remember { mutableStateListOf<Bitmap>() }
    var activeScanIndex by remember { mutableIntStateOf(0) }
    var rotationDeg by remember { mutableFloatStateOf(0f) }
    var enhanceContrast by remember { mutableStateOf(true) }
    var grayscale by remember { mutableStateOf(true) }

    var languageMode by remember { mutableStateOf(settings.ocrDefaultLanguage) }
    var correctionMode by remember { mutableStateOf(settings.ocrCorrectionMode) }
    var rawOcrText by remember { mutableStateOf("") }
    var correctionResult by remember { mutableStateOf<OcrCorrectionResult?>(null) }
    var reviewChanges by remember { mutableStateOf<List<TextCorrectionChange>>(emptyList()) }
    var editedFinalText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { bmp ->
                    scannedPages.add(bmp)
                    activeScanIndex = scannedPages.lastIndex
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPerm) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    fun runOcrPipeline() {
        val currentBmp = scannedPages.getOrNull(activeScanIndex) ?: return
        scope.launch {
            isProcessing = true
            val enhanced = viewModel.ocrManager.enhanceDocumentBitmap(
                source = currentBmp,
                rotationDeg = rotationDeg,
                contrastEnhance = enhanceContrast,
                grayscale = grayscale
            )
            viewModel.ocrManager.recognizeBitmapOffline(enhanced).onSuccess { extracted ->
                rawOcrText = extracted
                val corr = IndonesianEnglishCorrector.process(
                    rawText = extracted,
                    languageMode = languageMode,
                    correctionMode = correctionMode,
                    preserveTechnicalTerms = settings.ocrPreserveTechnicalTerms
                )
                correctionResult = corr
                reviewChanges = corr.changes
                editedFinalText = corr.correctedText
            }.onFailure { err ->
                viewModel.showStatus("Offline OCR error: ${err.message}")
            }
            isProcessing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera Scan & Offline OCR") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { galleryPicker.launch(arrayOf("image/*")) }) {
                        Text("Import Photo")
                    }
                }
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left Pane: CameraX Preview or Scanned Document Adjuster
            Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (scannedPages.isEmpty()) {
                    if (hasCameraPerm) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    PreviewView(ctx).apply {
                                        scaleType = PreviewView.ScaleType.FIT_CENTER
                                    }
                                },
                                update = { previewView ->
                                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                                    cameraProviderFuture.addListener({
                                        val provider = cameraProviderFuture.get()
                                        val preview = Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }
                                        val capture = ImageCapture.Builder()
                                            .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                                            .build()
                                        imageCaptureRef = capture
                                        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                                        runCatching {
                                            provider.unbindAll()
                                            provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                                        }
                                    }, ContextCompat.getMainExecutor(context))
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { useFrontCamera = !useFrontCamera }) {
                                Icon(Icons.Outlined.Cameraswitch, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Flip Camera")
                            }
                            OutlinedButton(onClick = { flashEnabled = !flashEnabled }) {
                                Icon(if (flashEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (flashEnabled) "Flash On" else "Flash Off")
                            }
                            Spacer(Modifier.weight(1f))
                            Button(onClick = {
                                val capture = imageCaptureRef ?: return@Button
                                capture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val bmp = imageProxyToBitmap(image)
                                            image.close()
                                            if (bmp != null) {
                                                scannedPages.add(bmp)
                                                activeScanIndex = scannedPages.lastIndex
                                            }
                                        }
                                        override fun onError(exception: ImageCaptureException) {
                                            viewModel.showStatus("Capture failed: ${exception.message}")
                                        }
                                    }
                                )
                            }) {
                                Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Capture Scan")
                            }
                        }
                    } else {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Camera permission is needed to scan documents.")
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                    Text("Grant Camera Permission")
                                }
                            }
                        }
                    }
                } else {
                    val activeBmp = scannedPages.getOrNull(activeScanIndex)
                    if (activeBmp != null) {
                        val previewBmp = remember(activeBmp, rotationDeg, enhanceContrast, grayscale) {
                            viewModel.ocrManager.enhanceDocumentBitmap(activeBmp, rotationDeg, enhanceContrast, grayscale)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = previewBmp.asImageBitmap(),
                                contentDescription = "Document scan preview",
                                modifier = Modifier.fillMaxSize().padding(8.dp)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { scannedPages.clear(); rawOcrText = ""; editedFinalText = "" }) {
                            Text("Retake")
                        }
                        OutlinedButton(onClick = { rotationDeg = (rotationDeg + 90f) % 360f }) {
                            Text("Rotate 90°")
                        }
                        FilterChip(selected = enhanceContrast, onClick = { enhanceContrast = !enhanceContrast }, label = { Text("Enhance") })
                        FilterChip(selected = grayscale, onClick = { grayscale = !grayscale }, label = { Text("B&W") })
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { runOcrPipeline() }, enabled = !isProcessing) {
                            Text(if (isProcessing) "Recognizing…" else "Recognize Offline")
                        }
                    }
                }
            }

            // Right Pane: OCR Language, Correction Review & Insertion Actions
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("OCR Language & Correction Mode", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OcrLanguageMode.entries.forEach { lang ->
                        FilterChip(
                            selected = languageMode == lang,
                            onClick = { languageMode = lang },
                            label = { Text(lang.displayName) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OcrCorrectionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = correctionMode == mode,
                            onClick = { correctionMode = mode },
                            label = { Text(mode.displayName) }
                        )
                    }
                }

                correctionResult?.let { res ->
                    Text(
                        "Detected: ${res.detectedLanguage} · ${res.note}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Review corrections before accepting
                if (reviewChanges.isNotEmpty() && settings.ocrAlwaysReviewBeforeAccept) {
                    Text("Review Suggested Corrections (${reviewChanges.size})", style = MaterialTheme.typography.labelLarge)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            itemsIndexed(reviewChanges) { idx, change ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = change.accepted,
                                        onCheckedChange = { checked ->
                                            val updated = reviewChanges.toMutableList()
                                            updated[idx] = change.copy(accepted = checked)
                                            reviewChanges = updated
                                            editedFinalText = IndonesianEnglishCorrector.applySelectedChanges(rawOcrText, updated)
                                        }
                                    )
                                    Text("${change.originalToken} → ${change.suggestedToken} (${change.reason})", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = editedFinalText,
                    onValueChange = { editedFinalText = it },
                    label = { Text("Extracted & Editable Text") },
                    minLines = 8,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("HARPEN OCR", editedFinalText))
                        viewModel.showStatus("Copied extracted text")
                    }, enabled = editedFinalText.isNotBlank()) {
                        Text("Copy")
                    }

                    Button(onClick = {
                        val layerId = doc?.activeLayerId ?: "layer_ink"
                        if (editedFinalText.isNotBlank()) {
                            viewModel.addOrUpdateTextObject(TextObject(layerId = layerId, text = editedFinalText))
                        }
                        if (settings.ocrAutoSaveScanImage) {
                            scannedPages.getOrNull(activeScanIndex)?.let { bmp ->
                                viewModel.insertBitmapAsImageObject(bmp, asBackground = false)
                            }
                        }
                        viewModel.showStatus("Inserted OCR text & scan into note")
                        onClose()
                    }, enabled = editedFinalText.isNotBlank() || scannedPages.isNotEmpty()) {
                        Text("Insert into Note")
                    }

                    OutlinedButton(onClick = {
                        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
                        val txtFile = File(outDir, "ocr_scan_${System.currentTimeMillis()}.txt")
                        txtFile.writeText(editedFinalText)
                        viewModel.pdfEngine.shareFileViaSharesheet(txtFile, "text/plain", "Save Extracted OCR Text")
                    }, enabled = editedFinalText.isNotBlank()) {
                        Text("Save .TXT")
                    }
                }
            }
        }
    }
}

private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
    val buffer = image.planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
