package com.james.shotcounterpoc.ui

import android.Manifest
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb

private val AppBlueLightScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    secondary = Color(0xFF1976D2),
    onSecondary = Color.White,
    tertiary = Color(0xFF1E88E5),
    onTertiary = Color.White,
    surface = Color(0xFFEAF2FF),
    onSurface = Color(0xFF0D1B2A)
)

private val AppBlueDarkScheme = darkColorScheme(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF0D47A1),
    secondary = Color(0xFF90CAF9),
    onSecondary = Color(0xFF0D47A1),
    tertiary = Color(0xFF42A5F5),
    onTertiary = Color(0xFF0D47A1),
    surface = Color(0xFF0D1F33),
    onSurface = Color(0xFFDCECFF)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotCounterPocApp(viewModel: ShotCounterViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    val calibrationSuggestion = viewModel.currentCalibrationSuggestion()
    val context = LocalContext.current

    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var seriesNameFieldBounds by remember { mutableStateOf<Rect?>(null) }
    val isDarkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val flashAlpha = remember { Animatable(0f) }

    val navigationBarColor = if (isDarkTheme) Color(0xFF0F2740) else Color(0xFFD6E8FF)

    SideEffect {
        val activity = context as? Activity ?: return@SideEffect
        activity.window.navigationBarColor = navigationBarColor.toArgb()
        WindowInsetsControllerCompat(activity.window, view).isAppearanceLightNavigationBars = !isDarkTheme
    }

    LaunchedEffect(uiState.shotFlashTriggerMs) {
        if (uiState.shotFlashTriggerMs > 0L) {
            flashAlpha.snapTo(0.35f)
            flashAlpha.animateTo(0f, animationSpec = tween(durationMillis = 280))
        }
    }

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startListening()
        } else {
            Toast.makeText(context, "Microphone permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler {
        viewModel.saveInProgressWithDefaultName()
        (context as? Activity)?.finish()
    }

    MaterialTheme(colorScheme = if (isDarkTheme) AppBlueDarkScheme else AppBlueLightScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(seriesNameFieldBounds) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                val tapUp = event.changes.firstOrNull { it.changedToUpIgnoreConsumed() } ?: continue
                                val bounds = seriesNameFieldBounds ?: continue
                                if (!bounds.contains(tapUp.position)) {
                                    focusManager.clearFocus(force = true)
                                }
                            }
                        }
                    }
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Shot Counter POC", color = Color.White) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color(0xFF0B3D6B)
                            ),
                            actions = {
                                IconButton(onClick = { showHelpDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Help,
                                        contentDescription = "Help",
                                        tint = Color.White
                                    )
                                }
                            }
                        )
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                    CounterSection(
                        count = uiState.currentCount,
                        onMinus = viewModel::decrementManually,
                        onPlus = viewModel::incrementManually
                    )

                    DecibelSection(
                        isListening = uiState.isListening,
                        displayedDb = uiState.displayedDb,
                        liveDb = uiState.liveDb,
                        lastShotDb = uiState.lastShotDb
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val isListening = uiState.isListening
                        Button(
                            onClick = {
                                if (isListening) {
                                    viewModel.stopListening()
                                } else {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (granted) {
                                        viewModel.startListening()
                                    } else {
                                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(90.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isListening) Color(0xFFB71C1C) else Color(0xFF1B5E20),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                if (isListening) "STOP" else "Listen",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }

                        Button(
                            onClick = viewModel::resetCurrentCount,
                            modifier = Modifier
                                .weight(1f)
                                .height(90.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF546E7A),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Reset", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }

                    CalibrationSection(
                        shotThresholdDb = uiState.shotThresholdDb,
                        rearmThresholdDb = uiState.rearmThresholdDb,
                        minShotGapMs = uiState.minShotGapMs,
                        isCalibrationTestMode = uiState.isCalibrationTestMode,
                        calibrationTestShotPeaks = uiState.calibrationTestShotPeaks,
                        calibrationSuggestion = calibrationSuggestion,
                        onShotThresholdChange = viewModel::updateShotThresholdDb,
                        onRearmThresholdChange = viewModel::updateRearmThresholdDb,
                        onMinGapChange = viewModel::updateMinShotGapMs,
                        onResetDefaults = viewModel::resetCalibrationDefaults,
                        onToggleTestMode = viewModel::toggleCalibrationTestMode,
                        onClearTestSamples = viewModel::clearCalibrationTestSamples,
                        onApplySuggested = viewModel::applySuggestedCalibration
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "Series Name", fontWeight = FontWeight.SemiBold)
                        TextField(
                            value = uiState.seriesNameInput,
                            onValueChange = viewModel::updateSeriesNameInput,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .onGloballyPositioned { seriesNameFieldBounds = it.boundsInRoot() }
                                .onFocusChanged {
                                    if (it.isFocused) {
                                        viewModel.onSeriesNameFocused()
                                    } else {
                                        viewModel.onSeriesNameFocusLost()
                                    }
                                },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = if (isDarkTheme) Color(0xFF102841) else Color(0xFFF1F7FF),
                                unfocusedContainerColor = if (isDarkTheme) Color(0xFF0D2338) else Color(0xFFE8F2FF),
                                focusedIndicatorColor = Color(0xFF1565C0),
                                unfocusedIndicatorColor = Color(0xFF90A4AE),
                                cursorColor = Color(0xFF1565C0)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(onClick = viewModel::saveSeries) {
                                Text("Save Series", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    ShotSeriesTable(
                        rows = uiState.shotSeries,
                        onDeleteAll = { showDeleteAllDialog = true },
                        onDeleteOne = { pendingDeleteId = it }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF90CAF9), RoundedCornerShape(10.dp))
                            .background(Color(0xFF0D47A1).copy(alpha = if (isDarkTheme) 0.35f else 0.12f), RoundedCornerShape(10.dp))
                            .padding(vertical = 12.dp, horizontal = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Total Count", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(24.dp))
                        Text(uiState.totalSavedCount.toString(), fontWeight = FontWeight.ExtraBold, fontSize = 26.sp)
                    }
                }
                }

                val flashColor = if ((uiState.lastShotDb ?: 0f) >= uiState.shotThresholdDb + 8f) {
                    Color(0x66D32F2F)
                } else {
                    Color(0x66F57C00)
                }
                if (flashAlpha.value > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(flashColor.copy(alpha = flashAlpha.value))
                    )
                }
            }
        }
    }

    MaterialTheme(colorScheme = if (isDarkTheme) AppBlueDarkScheme else AppBlueLightScheme) {
        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text("How to use") },
                text = {
                    Text(
                        "1. Press Listen to start mic monitoring.\n" +
                        "2. Shots above the threshold are counted automatically. Use +/- to correct.\n" +
                        "3. Press Save Series when a string of fire is done — it saves the count and resets to 0.\n\n" +
                        "Calibration:\n" +
                        "• Shot Threshold — how loud a sound must be to count as a shot.\n" +
                        "• Rearm Threshold — how quiet it must get before the next shot can register (prevents echo double-counting).\n" +
                        "• Min Shot Gap — minimum time (ms) between two shots.\n" +
                        "• Use Test Mode to fire real shots and let the app suggest ideal thresholds."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { showHelpDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Got it")
                    }
                }
            )
        }

        if (showDeleteAllDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAllDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text("Delete all series") },
                text = {
                    Text("Are you sure you want to delete ALL Shot Series? This action cannot be undone.")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteAllSeries()
                            showDeleteAllDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C), contentColor = Color.White)
                    ) {
                        Text("Delete", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showDeleteAllDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Cancel", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }

        pendingDeleteId?.let { id ->
            val row = uiState.shotSeries.firstOrNull { it.id == id }
            if (row != null) {
                AlertDialog(
                    onDismissRequest = { pendingDeleteId = null },
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    textContentColor = MaterialTheme.colorScheme.onSurface,
                    title = { Text("Delete series") },
                    text = {
                        Text("Are you sure you want to delete the Shot Series \"${row.name}\"? This action cannot be undone.")
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.deleteSeries(id)
                                pendingDeleteId = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C), contentColor = Color.White)
                        ) {
                            Text("Delete", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    },
                    dismissButton = {
                        Button(
                            onClick = { pendingDeleteId = null },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Cancel", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CalibrationSection(
    shotThresholdDb: Float,
    rearmThresholdDb: Float,
    minShotGapMs: Int,
    isCalibrationTestMode: Boolean,
    calibrationTestShotPeaks: List<Float>,
    calibrationSuggestion: ShotCounterViewModel.CalibrationSuggestion?,
    onShotThresholdChange: (Float) -> Unit,
    onRearmThresholdChange: (Float) -> Unit,
    onMinGapChange: (Int) -> Unit,
    onResetDefaults: () -> Unit,
    onToggleTestMode: () -> Unit,
    onClearTestSamples: () -> Unit,
    onApplySuggested: () -> Unit
) {
    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF9E9E9E).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
    ) {
        // Header bar — tap to expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(if (expanded) RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp) else RoundedCornerShape(8.dp))
                .background(Color(0xFF0B4F8A))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Color(0xFFF7FBFF)
            )
            Text(
                "Calibration",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFFF7FBFF),
                textAlign = TextAlign.Center
            )
            // Invisible icon to balance the layout so title stays centered
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = Color.Transparent
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onResetDefaults) {
                        Text("Reset Cal", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = onToggleTestMode) {
                        Text(if (isCalibrationTestMode) "Stop Test" else "Test Mode", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

        Text("Shot Threshold: ${"%.1f".format(shotThresholdDb)} dB")
        Slider(
            value = shotThresholdDb,
            onValueChange = onShotThresholdChange,
            valueRange = 90f..180f
        )

        Text("Rearm Threshold: ${"%.1f".format(rearmThresholdDb)} dB")
        Slider(
            value = rearmThresholdDb,
            onValueChange = onRearmThresholdChange,
            valueRange = 80f..179f
        )

        Text("Min Shot Gap: ${minShotGapMs} ms")
        Slider(
            value = minShotGapMs.toFloat(),
            onValueChange = { onMinGapChange(it.toInt()) },
            valueRange = 100f..2000f
        )

        if (isCalibrationTestMode) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("Test Mode Active", fontWeight = FontWeight.SemiBold, color = Color(0xFF1B5E20))
            Text("Detected test shots: ${calibrationTestShotPeaks.size} (target 10-20)")

            val peaksText = calibrationTestShotPeaks.take(10)
                .joinToString(separator = ", ") { "%.1f".format(it) }
            Text(
                text = if (peaksText.isBlank()) "Recent peaks: -" else "Recent peaks: $peaksText",
                fontSize = 12.sp
            )

            calibrationSuggestion?.let { suggestion ->
                Spacer(modifier = Modifier.height(4.dp))
                val confidenceColor = when (suggestion.confidenceLabel) {
                    "High" -> Color(0xFF1B5E20)
                    "Medium" -> Color(0xFFF9A825)
                    else -> Color(0xFFB71C1C)
                }
                Text(
                    text = "Suggestion Confidence: ${suggestion.confidenceLabel}",
                    color = confidenceColor,
                    fontWeight = FontWeight.Bold
                )
                Text(suggestion.confidenceDetails, fontSize = 12.sp)
                Text("Suggested Shot Threshold: ${"%.1f".format(suggestion.shotThresholdDb)} dB")
                Text("Suggested Rearm Threshold: ${"%.1f".format(suggestion.rearmThresholdDb)} dB")
                Text("Suggested Min Gap: ${suggestion.minShotGapMs} ms")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApplySuggested) {
                        Text("Apply Suggested", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(onClick = onClearTestSamples) {
                        Text("Clear Samples", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } ?: run {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Collect at least 10 test shots to get suggestions.")
                Button(onClick = onClearTestSamples) {
                    Text("Clear Samples", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
            } // end expanded Column
        } // end if (expanded)
    } // end outer Column
}

@Composable
private fun CounterSection(
    count: Int,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SquareActionButton(symbol = "-", onClick = onMinus)
        Spacer(modifier = Modifier.width(48.dp))
        Text(
            text = count.toString(),
            fontSize = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(120.dp)
        )
        Spacer(modifier = Modifier.width(48.dp))
        SquareActionButton(symbol = "+", onClick = onPlus)
    }
}

@Composable
private fun SquareActionButton(symbol: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(60.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    ) {
        Text(symbol, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DecibelSection(
    isListening: Boolean,
    displayedDb: Float,
    liveDb: Float,
    lastShotDb: Float?
) {
    val normalized = ((displayedDb - 80f) / 100f).coerceIn(0f, 1f)
    val currentDbLabel = if (isListening && liveDb > 80f) {
        "Current dB: ${"%.1f".format(liveDb)}"
    } else {
        "Current dB: -"
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(currentDbLabel)
            Text(text = lastShotDb?.let { "Last shot dB: ${"%.1f".format(it)}" } ?: "Last shot dB: -")
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                .background(Color(0xFFE0E0E0))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(normalized)
                    .height(24.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xFF2E7D32), Color(0xFFF9A825), Color(0xFFC62828))
                        )
                    )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("80 dB")
            Text("180 dB")
        }
    }
}

@Composable
private fun ShotSeriesTable(
    rows: List<ShotSeries>,
    onDeleteAll: () -> Unit,
    onDeleteOne: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF9E9E9E).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0B4F8A))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Shot Series Name",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF7FBFF),
                fontSize = 14.sp
            )
            Text(
                "Count",
                modifier = Modifier.padding(end = 4.dp),
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF7FBFF),
                fontSize = 14.sp
            )
            Button(
                onClick = onDeleteAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB71C1C),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(0.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .size(42.dp)
                    .padding(bottom = 4.dp)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete all series")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            rows.forEachIndexed { index, row ->
                val rowBackground = if (index % 2 == 0) {
                    if (isSystemInDarkTheme()) Color(0xFF1D2E45) else Color(0xFFE9F2FF)
                } else {
                    if (isSystemInDarkTheme()) Color(0xFF132236) else Color(0xFFD7E8FF)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBackground)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(row.name, modifier = Modifier.weight(1f))
                    Text(row.count.toString(), modifier = Modifier.padding(end = 8.dp))
                    IconButton(onClick = { onDeleteOne(row.id) }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete ${row.name}",
                            tint = Color(0xFFB71C1C)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ShotCounterPocAppPreview() {
    ShotCounterPocApp()
}
