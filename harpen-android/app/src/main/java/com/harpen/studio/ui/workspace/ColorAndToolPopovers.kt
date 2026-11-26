package com.harpen.studio.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.harpen.studio.model.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

@Composable
fun ToolSettingsPopover(
    toolConfig: ToolConfig,
    recentColorsCsv: String,
    favoriteColorsCsv: String,
    onUpdateTool: ((ToolConfig) -> ToolConfig) -> Unit,
    onPickEyedropper: () -> Unit,
    onAddFavoriteColor: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var hue by remember { mutableFloatStateOf(215f) }
    var sat by remember { mutableFloatStateOf(0.8f) }
    var value by remember { mutableFloatStateOf(0.8f) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .width(340.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(toolConfig.toolType.displayName, style = MaterialTheme.typography.titleMedium)
                Row {
                    IconButton(onClick = onPickEyedropper) {
                        Icon(Icons.Outlined.Colorize, contentDescription = "Eyedropper")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }
            }

            // Width, Opacity, Smoothing, Texture, Pressure, Tilt
            SliderRow("Width", "${toolConfig.width.toInt()} px", toolConfig.width, 1f..48f) { w ->
                onUpdateTool { it.copy(width = w) }
            }
            SliderRow("Opacity", "${(toolConfig.opacity * 100).toInt()}%", toolConfig.opacity, 0.05f..1f) { o ->
                onUpdateTool { it.copy(opacity = o) }
            }
            SliderRow("Smoothing", "${(toolConfig.smoothing * 100).toInt()}%", toolConfig.smoothing, 0f..1f) { s ->
                onUpdateTool { it.copy(smoothing = s) }
            }
            if (!toolConfig.toolType.isEraser) {
                SliderRow("Texture", "${(toolConfig.texture * 100).toInt()}%", toolConfig.texture, 0f..1f) { t ->
                    onUpdateTool { it.copy(texture = t) }
                }
                SliderRow("Pressure Response", "${(toolConfig.pressureResponse * 100).toInt()}%", toolConfig.pressureResponse, 0f..1f) { p ->
                    onUpdateTool { it.copy(pressureResponse = p) }
                }
                SliderRow("Tilt Response", "${(toolConfig.tiltResponse * 100).toInt()}%", toolConfig.tiltResponse, 0f..1f) { tl ->
                    onUpdateTool { it.copy(tiltResponse = tl) }
                }

                // Interactive Color Wheel
                Text("Color Wheel", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        modifier = Modifier
                            .size(116.dp)
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    val dx = offset.x - cx
                                    val dy = offset.y - cy
                                    val r = min(cx, cy)
                                    val dist = hypot(dx, dy).coerceAtMost(r)
                                    val angle = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
                                    hue = angle
                                    sat = (dist / r).coerceIn(0f, 1f)
                                    val argb = Color.hsv(hue, sat, value).toArgb().toLong() and 0xFFFFFFFFL
                                    onUpdateTool { it.copy(colorArgb = argb) }
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    val cx = size.width / 2f
                                    val cy = size.height / 2f
                                    val dx = change.position.x - cx
                                    val dy = change.position.y - cy
                                    val r = min(cx, cy)
                                    val dist = hypot(dx, dy).coerceAtMost(r)
                                    val angle = ((atan2(dy, dx) * 180f / PI.toFloat()) + 360f) % 360f
                                    hue = angle
                                    sat = (dist / r).coerceIn(0f, 1f)
                                    val argb = Color.hsv(hue, sat, value).toArgb().toLong() and 0xFFFFFFFFL
                                    onUpdateTool { it.copy(colorArgb = argb) }
                                }
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val sweep = Brush.sweepGradient(
                                listOf(
                                    Color.Red, Color.Yellow, Color.Green,
                                    Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                                )
                            )
                            drawCircle(brush = sweep)
                            drawCircle(
                                brush = Brush.radialGradient(
                                    listOf(Color.White, Color.Transparent)
                                )
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(toolConfig.colorArgb))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        )
                        Slider(
                            value = value,
                            onValueChange = { v ->
                                value = v
                                val argb = Color.hsv(hue, sat, value).toArgb().toLong() and 0xFFFFFFFFL
                                onUpdateTool { it.copy(colorArgb = argb) }
                            },
                            valueRange = 0.08f..1f
                        )
                        TextButton(onClick = { onAddFavoriteColor(toolConfig.colorArgb) }) {
                            Text("Save to Favorites")
                        }
                    }
                }

                // Favorite & Recent Swatches
                SwatchRow("Favorites", favoriteColorsCsv) { argb -> onUpdateTool { it.copy(colorArgb = argb) } }
                SwatchRow("Recent", recentColorsCsv) { argb -> onUpdateTool { it.copy(colorArgb = argb) } }
            }
        }
    }
}

@Composable
private fun SwatchRow(label: String, csv: String, onSelect: (Long) -> Unit) {
    val colors = remember(csv) {
        csv.split(",").mapNotNull { hex ->
            runCatching { android.graphics.Color.parseColor(hex.trim()).toLong() and 0xFFFFFFFFL }.getOrNull()
        }
    }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colors.take(7).forEach { argb ->
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onSelect(argb) }
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(valueText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun LayerPanelSheet(
    layers: List<Layer>,
    activeLayerId: String,
    onSelectLayer: (String) -> Unit,
    onAddLayer: (String, LayerType) -> Unit,
    onUpdateLayer: (String, (Layer) -> Layer) -> Unit,
    onDeleteLayer: (String) -> Unit,
    onMergeDown: (String) -> Unit,
    onReorder: (String, Boolean) -> Unit,
    onClearLayer: (String) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Layers", style = MaterialTheme.typography.titleMedium)
                Row {
                    IconButton(onClick = { onAddLayer("Ink Layer ${layers.size + 1}", LayerType.INK) }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add Layer")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close Layers")
                    }
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(layers.reversed(), key = { it.id }) { layer ->
                    val isSelected = layer.id == activeLayerId
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectLayer(layer.id) }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(layer.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onUpdateLayer(layer.id) { it.copy(visible = !it.visible) } }, modifier = Modifier.size(28.dp)) {
                                    Icon(if (layer.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, contentDescription = "Toggle visibility", modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onUpdateLayer(layer.id) { it.copy(locked = !it.locked) } }, modifier = Modifier.size(28.dp)) {
                                    Icon(if (layer.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen, contentDescription = "Toggle lock", modifier = Modifier.size(18.dp))
                                }
                            }
                            Slider(
                                value = layer.opacity,
                                onValueChange = { op -> onUpdateLayer(layer.id) { it.copy(opacity = op) } },
                                valueRange = 0f..1f
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onReorder(layer.id, true) }) { Text("Up") }
                                TextButton(onClick = { onReorder(layer.id, false) }) { Text("Down") }
                                TextButton(onClick = { onMergeDown(layer.id) }) { Text("Merge") }
                                TextButton(onClick = { onClearLayer(layer.id) }) { Text("Clear") }
                                if (layers.size > 1) {
                                    TextButton(onClick = { onDeleteLayer(layer.id) }) { Text("Del") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TextObjectEditorDialog(
    initial: TextObject?,
    layerId: String,
    onDismiss: () -> Unit,
    onSave: (TextObject) -> Unit
) {
    var content by remember { mutableStateOf(initial?.text ?: "") }
    var fontSize by remember { mutableFloatStateOf(initial?.fontSizeSp ?: 18f) }
    var bold by remember { mutableStateOf(initial?.bold ?: false) }
    var italic by remember { mutableStateOf(initial?.italic ?: false) }
    var underline by remember { mutableStateOf(initial?.underline ?: false) }
    var alignment by remember { mutableStateOf(initial?.alignment ?: TextAlignment.LEFT) }
    var bullets by remember { mutableStateOf(initial?.bulletList ?: false) }
    var numbered by remember { mutableStateOf(initial?.numberedList ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Insert Text Box" else "Edit Text Box") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Text content") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = bold, onClick = { bold = !bold }, label = { Text("B") })
                    FilterChip(selected = italic, onClick = { italic = !italic }, label = { Text("I") })
                    FilterChip(selected = underline, onClick = { underline = !underline }, label = { Text("U") })
                    FilterChip(selected = bullets, onClick = { bullets = !bullets; if (bullets) numbered = false }, label = { Text("Bullets") })
                    FilterChip(selected = numbered, onClick = { numbered = !numbered; if (numbered) bullets = false }, label = { Text("1.2.") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextAlignment.entries.forEach { al ->
                        FilterChip(selected = alignment == al, onClick = { alignment = al }, label = { Text(al.name) })
                    }
                }
                SliderRow("Font Size", "${fontSize.toInt()} sp", fontSize, 10f..48f) { fontSize = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                val base = initial ?: TextObject(layerId = layerId, text = content)
                onSave(
                    base.copy(
                        text = content,
                        fontSizeSp = fontSize,
                        bold = bold,
                        italic = italic,
                        underline = underline,
                        alignment = alignment,
                        bulletList = bullets,
                        numberedList = numbered
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
