package com.james.shotcounterpoc.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.toArgb
import com.james.shotcounterpoc.BuildConfig
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val appVersionLabel = "v${BuildConfig.VERSION_NAME}"

    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var selectedSeriesId by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showQuickSettingsDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var seriesNameFieldBounds by remember { mutableStateOf<Rect?>(null) }
    val isDarkTheme = isSystemInDarkTheme()
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val flashAlpha = remember { Animatable(0f) }

    val navigationBarColor = if (isDarkTheme) Color(0xFF0F2740) else Color(0xFFD6E8FF)

    SideEffect {
        val activity = context as? Activity ?: return@SideEffect
        @Suppress("DEPRECATION")
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

    val hasPendingSeries = uiState.currentCount > 0 || uiState.inProgressShotEvents.isNotEmpty()

    BackHandler {
        if (hasPendingSeries) {
            showExitDialog = true
        } else {
            (context as? Activity)?.finish()
        }
    }

    val exportSeriesCount = uiState.shotSeries.size
    val exportClipCount = uiState.shotSeries
        .flatMap { it.shots }
        .mapNotNull { it.audioClipPath }
        .distinct()
        .count()

    fun resolveClipUris(): ArrayList<Uri> {
        return ArrayList(
            uiState.shotSeries
                .flatMap { it.shots }
                .mapNotNull { it.audioClipPath }
                .distinct()
                .mapNotNull { path ->
                    runCatching {
                        val clipFile = File(path)
                        if (!clipFile.exists()) return@runCatching null
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            clipFile
                        )
                    }.getOrNull()
                }
        )
    }

    fun launchShare(includeJson: Boolean, includeClips: Boolean) {
        val clipUris = if (includeClips) resolveClipUris() else arrayListOf()
        if (includeClips && clipUris.isEmpty()) {
            Toast.makeText(context, "No clips available to export", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(
            if (clipUris.isEmpty()) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
        ).apply {
            type = when {
                clipUris.isEmpty() -> "text/plain"
                includeJson -> "*/*"
                else -> "audio/wav"
            }
            putExtra(Intent.EXTRA_SUBJECT, "Shot Counter export ${BuildConfig.VERSION_NAME}")
            if (includeJson) {
                putExtra(Intent.EXTRA_TEXT, viewModel.exportShotSeriesJson())
            }
            if (clipUris.isNotEmpty()) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, clipUris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val firstUri = clipUris.first()
                clipData = ClipData.newUri(context.contentResolver, "shot_clip", firstUri).apply {
                    clipUris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
        }

        context.startActivity(Intent.createChooser(shareIntent, "Export shot series"))
        showExportDialog = false
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
                                Text(
                                    text = appVersionLabel,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                                IconButton(
                                    onClick = {
                                        if (uiState.shotSeries.isEmpty()) {
                                            Toast.makeText(context, "No shot series to export", Toast.LENGTH_SHORT).show()
                                        } else {
                                            showExportDialog = true
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Export shot series",
                                        tint = Color.White
                                    )
                                }
                                IconButton(onClick = { showQuickSettingsDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Quick settings",
                                        tint = Color.White
                                    )
                                }
                                IconButton(onClick = { showHelpDialog = true }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Help,
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
                            .fillMaxWidth()
                            .height(84.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isListening) Color(0xFFB71C1C) else Color(0xFF1B5E20),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            if (isListening) "STOP LISTENING" else "LISTEN",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }

                    ExpandableSection(
                        title = "Counter",
                        expanded = uiState.isCounterExpanded,
                        onExpandedChange = viewModel::setCounterExpanded
                    ) {
                        CounterSection(
                            count = uiState.currentCount,
                            seriesName = uiState.seriesNameInput,
                            hasPendingSeries = hasPendingSeries,
                            isDarkTheme = isDarkTheme,
                            onMinus = viewModel::decrementManually,
                            onPlus = viewModel::incrementManually,
                            onSeriesNameChange = viewModel::updateSeriesNameInput,
                            onSeriesNameFocused = viewModel::onSeriesNameFocused,
                            onSeriesNameFocusLost = viewModel::onSeriesNameFocusLost,
                            onSaveSeries = viewModel::saveSeries,
                            onDiscardSeries = viewModel::discardCurrentSeries,
                            onSeriesNameBoundsChange = { seriesNameFieldBounds = it }
                        )
                    }

                    ExpandableSection(
                        title = "Sound Levels",
                        expanded = uiState.isSoundLevelsExpanded,
                        onExpandedChange = viewModel::setSoundLevelsExpanded
                    ) {
                        DecibelSection(
                            isListening = uiState.isListening,
                            displayedDb = uiState.displayedDb,
                            liveDb = uiState.liveDb,
                            lastPeakDb = uiState.lastPeakDb,
                            lastShotDb = uiState.lastShotDb,
                            dbHistory = uiState.dbHistory,
                            dbMarkerHistory = uiState.dbMarkerHistory,
                            shotThresholdDb = uiState.shotThresholdDb,
                            rearmThresholdDb = uiState.rearmThresholdDb,
                            displayMinDb = uiState.displayMinDb,
                            displayMaxDb = uiState.displayMaxDb
                        )
                    }

                    CalibrationSection(
                        shotThresholdDb = uiState.shotThresholdDb,
                        rearmThresholdDb = uiState.rearmThresholdDb,
                        minShotGapMs = uiState.minShotGapMs,
                        expanded = uiState.isCalibrationExpanded,
                        onExpandedChange = viewModel::setCalibrationExpanded,
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

                    ShotSeriesSection(
                        rows = uiState.shotSeries,
                        expanded = uiState.isShotSeriesExpanded,
                        onExpandedChange = viewModel::setShotSeriesExpanded,
                        onSelectOne = { selectedSeriesId = it },
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
        if (showQuickSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showQuickSettingsDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text("Quick Settings") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SettingsSectionHeader("Display")
                        LabeledSliderField(
                            label = "Minimum dB",
                            valueText = "${uiState.displayMinDb.toInt()} dB",
                            value = uiState.displayMinDb,
                            valueRange = 0f..179f,
                            onValueChange = viewModel::updateDisplayMinDb
                        )
                        LabeledSliderField(
                            label = "Maximum dB",
                            valueText = "${uiState.displayMaxDb.toInt()} dB",
                            value = uiState.displayMaxDb,
                            valueRange = 1f..180f,
                            onValueChange = viewModel::updateDisplayMaxDb
                        )

                        SettingsSectionHeader("Behavior")
                        val decayValueText = if (uiState.barDecayMsPerDb <= 0f) "Hold" else "${uiState.barDecayMsPerDb.toInt()} ms/dB"
                        LabeledSliderField(
                            label = "Bar decay rate",
                            valueText = decayValueText,
                            value = uiState.barDecayMsPerDb,
                            valueRange = 0f..3000f,
                            steps = 29,
                            onValueChange = viewModel::updateBarDecayMsPerDb
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showQuickSettingsDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Done")
                    }
                }
            )
        }

        if (showExitDialog) {
            AlertDialog(
                onDismissRequest = { showExitDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text("Exit App") },
                text = {
                    Text(
                        if (uiState.currentCount > 0 || uiState.inProgressShotEvents.isNotEmpty()) {
                            "What should happen to the current shot series before exiting?"
                        } else {
                            "There is no unsaved shot series. You can still save the default series, discard it, or cancel exit."
                        }
                    )
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                    ) {
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.saveSeries()
                                showExitDialog = false
                                (context as? Activity)?.finish()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Save")
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.discardCurrentSeries()
                                showExitDialog = false
                                (context as? Activity)?.finish()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF546E7A),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Discard")
                        }
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showExitDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text("Export") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Series: $exportSeriesCount  |  Clips: $exportClipCount",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                        Button(
                            onClick = { launchShare(includeJson = true, includeClips = true) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            )
                        ) {
                            Text("JSON + Clips ($exportClipCount)")
                        }
                        Button(
                            onClick = { launchShare(includeJson = true, includeClips = false) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = Color.White
                            )
                        ) {
                            Text("JSON Only ($exportSeriesCount series)")
                        }
                        Button(
                            onClick = { launchShare(includeJson = false, includeClips = true) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1565C0),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Clips Only ($exportClipCount)")
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    Button(
                        onClick = { showExportDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showHelpDialog) {
            AlertDialog(
                onDismissRequest = { showHelpDialog = false },
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text("How to use") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME}",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
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

        selectedSeriesId?.let { id ->
            val row = uiState.shotSeries.firstOrNull { it.id == id }
            if (row != null) {
                AlertDialog(
                    onDismissRequest = { selectedSeriesId = null },
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    textContentColor = MaterialTheme.colorScheme.onSurface,
                    title = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Shot Events",
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = row.name,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    },
                    text = {
                        ShotEventsTable(
                            shots = row.shots,
                            playingClipId = uiState.playingClipId,
                            onPlayClip = { viewModel.playClip(it) },
                            onStopClip = { viewModel.stopClip() }
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { selectedSeriesId = null },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Close")
                        }
                    }
                )
            }
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
private fun ShotSeriesSection(
    rows: List<ShotSeries>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectOne: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteOne: (String) -> Unit
) {
    ExpandableSection(
        title = "Shot Series",
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
            ShotSeriesTable(
                rows = rows,
                onSelectOne = onSelectOne,
                onDeleteAll = onDeleteAll,
                onDeleteOne = onDeleteOne
            )
    }
}

@Composable
private fun CalibrationSection(
    shotThresholdDb: Float,
    rearmThresholdDb: Float,
    minShotGapMs: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
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
    ExpandableSection(
        title = "Calibration",
        expanded = expanded,
        onExpandedChange = onExpandedChange
    ) {
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
                valueRange = 1f..180f
            )

            Text("Rearm Threshold: ${"%.1f".format(rearmThresholdDb)} dB")
            Slider(
                value = rearmThresholdDb,
                onValueChange = onRearmThresholdChange,
                valueRange = 0f..179f
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
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF9E9E9E).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(if (expanded) RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp) else RoundedCornerShape(8.dp))
                .background(Color(0xFF0B4F8A))
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Color(0xFFF7FBFF)
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = Color(0xFFF7FBFF),
                textAlign = TextAlign.Center
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = Color.Transparent
            )
        }

        if (expanded) {
            content()
        }
    }
}

@Composable
private fun CounterSection(
    count: Int,
    seriesName: String,
    hasPendingSeries: Boolean,
    isDarkTheme: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    onSeriesNameChange: (String) -> Unit,
    onSeriesNameFocused: () -> Unit,
    onSeriesNameFocusLost: () -> Unit,
    onSaveSeries: () -> Unit,
    onDiscardSeries: () -> Unit,
    onSeriesNameBoundsChange: (Rect) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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

        Text(text = "Series Name", fontWeight = FontWeight.SemiBold)
        TextField(
            value = seriesName,
            onValueChange = onSeriesNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { onSeriesNameBoundsChange(it.boundsInRoot()) }
                .onFocusChanged {
                    if (it.isFocused) {
                        onSeriesNameFocused()
                    } else {
                        onSeriesNameFocusLost()
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
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onSaveSeries,
                enabled = hasPendingSeries,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                )
            ) {
                Text("Save", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onDiscardSeries,
                enabled = hasPendingSeries,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF546E7A),
                    contentColor = Color.White
                )
            ) {
                Text("Discard", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
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
    lastPeakDb: Float?,
    lastShotDb: Float?,
    dbHistory: List<Float>,
    dbMarkerHistory: List<Int>,
    shotThresholdDb: Float,
    rearmThresholdDb: Float,
    displayMinDb: Float,
    displayMaxDb: Float
) {
    val normalized = ((displayedDb - displayMinDb) / (displayMaxDb - displayMinDb).coerceAtLeast(1f)).coerceIn(0f, 1f)
    val currentDbLabel = if (isListening && liveDb > displayMinDb) {
        "Current dB: ${"%.1f".format(liveDb)}"
    } else {
        "Current dB: -"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledMetricColumn(modifier = Modifier.weight(1f), title = "Current dB", value = currentDbLabel.removePrefix("Current dB: "))
            LabeledMetricColumn(modifier = Modifier.weight(1f), title = "Last Peak dB", value = lastPeakDb?.let { "${"%.1f".format(it)}" } ?: "-")
            LabeledMetricColumn(modifier = Modifier.weight(1f), title = "Last shot dB", value = lastShotDb?.let { "${"%.1f".format(it)}" } ?: "-")
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

        DbTrendGraph(
            samples = dbHistory,
            markers = dbMarkerHistory,
            shotThresholdDb = shotThresholdDb,
            rearmThresholdDb = rearmThresholdDb,
            minDb = displayMinDb,
            maxDb = displayMaxDb
        )
    }
}

@Composable
private fun ShotSeriesTable(
    rows: List<ShotSeries>,
    onSelectOne: (String) -> Unit,
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
                        .clickable { onSelectOne(row.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(row.name, modifier = Modifier.weight(1f))
                    Text(row.recordedRoundCount.toString(), modifier = Modifier.padding(end = 8.dp))
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

@Composable
private fun ShotEventsTable(
    shots: List<ShotEvent>,
    playingClipId: String? = null,
    onPlayClip: (ShotEvent) -> Unit = {},
    onStopClip: () -> Unit = {}
) {
    if (shots.isEmpty()) {
        Text("No detected shot events were stored for this series.")
        return
    }

    val headerColor = if (isSystemInDarkTheme()) Color(0xFF17314E) else Color(0xFFDDEBFF)
    val rowAltColor = if (isSystemInDarkTheme()) Color(0xFF10253B) else Color(0xFFF5F9FF)
    val rowColor = if (isSystemInDarkTheme()) Color(0xFF152C45) else Color(0xFFEAF3FF)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerColor)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            TableCell(text = "detected_at", weight = 1.45f, bold = true)
            TableCell(text = "confidence", weight = 0.95f, bold = true)
            TableCell(text = "peak_db", weight = 0.7f, bold = true)
            Spacer(modifier = Modifier.width(32.dp))
        }

        shots.forEachIndexed { index, shot ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (index % 2 == 0) rowColor else rowAltColor)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                TableCell(text = formatDetectedAt(shot.detectedAtMillis), weight = 1.45f)
                TableCell(text = "%.2f".format(shot.confidence), weight = 0.95f)
                TableCell(text = "%.1f".format(shot.peakDb), weight = 0.7f)
                Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                    when {
                        playingClipId == shot.id -> IconButton(
                            onClick = onStopClip,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Stop,
                                contentDescription = "Stop",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        shot.audioClipPath != null -> IconButton(
                            onClick = { onPlayClip(shot) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        else -> Spacer(modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DbTrendGraph(
    samples: List<Float>,
    markers: List<Int>,
    shotThresholdDb: Float,
    rearmThresholdDb: Float,
    minDb: Float,
    maxDb: Float
) {
    val graphHeight = 120.dp
    val background = if (isSystemInDarkTheme()) Color(0xFF081622) else Color(0xFFF6FAFF)
    val gridColor = if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.10f) else Color(0xFF0B4F8A).copy(alpha = 0.12f)
    val lineColor = Color(0xFF42A5F5)
    val accentColor = Color(0xFF1565C0)
    val peakMarkerColor = Color(0xFFF9A825)
    val shotMarkerColor = Color(0xFFD32F2F)
    val shotThresholdColor = Color(0xFFC62828)
    val rearmThresholdColor = Color(0xFFEF6C00)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(graphHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(background)
                .border(1.dp, Color(0xFF90A4AE).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(42.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${maxDb.toInt()}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    text = "${((minDb + maxDb) / 2f).toInt()}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                Text(
                    text = "${minDb.toInt()}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                if (samples.size < 2) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "dB history",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                } else {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val width = size.width
                        val height = size.height
                        val range = (maxDb - minDb).coerceAtLeast(1f)

                        fun yFor(value: Float): Float {
                            val normalized = ((value - minDb) / range).coerceIn(0f, 1f)
                            return height - (normalized * height)
                        }

                        listOf(minDb, (minDb + maxDb) / 2f, maxDb).forEach { level ->
                            val y = yFor(level)
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(width, y),
                                strokeWidth = 1f
                            )
                        }

                        listOf(
                            shotThresholdDb to shotThresholdColor,
                            rearmThresholdDb to rearmThresholdColor
                        ).forEach { (level, color) ->
                            if (level in minDb..maxDb) {
                                val y = yFor(level)
                                drawLine(
                                    color = color.copy(alpha = 0.9f),
                                    start = Offset(0f, y),
                                    end = Offset(width, y),
                                    strokeWidth = 2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                                )
                            }
                        }

                        val points = samples.mapIndexed { index, sample ->
                            val x = if (samples.size == 1) 0f else (index.toFloat() / (samples.size - 1).coerceAtLeast(1)) * width
                            Offset(x, yFor(sample))
                        }
                        val markerSlice = if (markers.size >= points.size) {
                            markers.takeLast(points.size)
                        } else {
                            List(points.size - markers.size) { 0 } + markers
                        }

                        if (points.size >= 2) {
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { point -> lineTo(point.x, point.y) }
                            }
                            drawPath(
                                path = path,
                                color = lineColor,
                                style = Stroke(width = 3f, cap = StrokeCap.Round)
                            )
                            points.takeLast(12).forEach { point ->
                                drawCircle(color = accentColor, radius = 2.5f, center = point)
                            }
                            points.forEachIndexed { index, point ->
                                val marker = markerSlice.getOrElse(index) { 0 }
                                if ((marker and ShotCounterViewModel.GRAPH_MARKER_SHOT) != 0) {
                                    drawLine(
                                        color = shotMarkerColor,
                                        start = Offset(point.x, (point.y - 10f).coerceAtLeast(0f)),
                                        end = Offset(point.x, (point.y + 10f).coerceAtMost(height)),
                                        strokeWidth = 3f,
                                        cap = StrokeCap.Round
                                    )
                                }
                                if ((marker and ShotCounterViewModel.GRAPH_MARKER_PEAK) != 0) {
                                    drawCircle(
                                        color = peakMarkerColor,
                                        radius = 5f,
                                        center = point
                                    )
                                    drawCircle(
                                        color = Color.White,
                                        radius = 2f,
                                        center = point
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Older", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Text("Now", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendItem(color = peakMarkerColor, label = "Peak")
            LegendItem(color = shotMarkerColor, label = "Shot")
            LegendItem(color = shotThresholdColor, label = "Shot threshold")
            LegendItem(color = rearmThresholdColor, label = "Rearm threshold")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color)
        )
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun LabeledMetricColumn(
    modifier: Modifier = Modifier,
    title: String,
    value: String
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(text = value, fontSize = 15.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
        )
    }
}

@Composable
private fun LabeledSliderField(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(valueText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    bold: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier
            .weight(weight)
            .padding(end = 8.dp),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = 11.sp
    )
}

private fun formatDetectedAt(epochMillis: Long): String {
    if (epochMillis <= 0L) return "-"
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}

@Preview(showBackground = true)
@Composable
fun ShotCounterPocAppPreview() {
    ShotCounterPocApp()
}
