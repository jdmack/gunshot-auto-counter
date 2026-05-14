package com.james.shotcounterpoc.ui

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import com.james.shotcounterpoc.audio.CircularShortBuffer
import com.james.shotcounterpoc.audio.WavWriter
import com.james.shotcounterpoc.data.AppDatabase
import com.james.shotcounterpoc.data.ShotEventEntity
import com.james.shotcounterpoc.data.ShotSeriesEntity
import com.james.shotcounterpoc.data.ShotSeriesWithEvents
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ShotEvent(
    val id: String,
    val shotSeriesId: String?,
    val detectedAtMillis: Long,
    val confidence: Float,
    val peakDb: Float,
    val audioClipPath: String? = null
)

data class ShotSeries(
    val id: String,
    val name: String,
    val recordedRoundCount: Int,
    val createdAtMillis: Long,
    val shots: List<ShotEvent> = emptyList()
)

data class ShotCounterUiState(
    val currentCount: Int = 0,
    val displayedDb: Float = 80f,
    val liveDb: Float = 80f,
    val lastPeakDb: Float? = null,
    val lastShotDb: Float? = null,
    val shotFlashTriggerMs: Long = 0L,
    val isListening: Boolean = false,
    val shotThresholdDb: Float = 110f,
    val rearmThresholdDb: Float = 95f,
    val minShotGapMs: Int = 300,
    val isCalibrationTestMode: Boolean = false,
    val calibrationTestShotPeaks: List<Float> = emptyList(),
    val calibrationTestIntervalsMs: List<Long> = emptyList(),
    val dbHistory: List<Float> = emptyList(),
    val dbMarkerHistory: List<Int> = emptyList(),
    val displayMinDb: Float = 80f,
    val displayMaxDb: Float = 180f,
    val barDecayMsPerDb: Float = 100f,
    val fallbackRearmMarginDb: Float = 3f,
    val isCounterExpanded: Boolean = true,
    val isSoundLevelsExpanded: Boolean = true,
    val isCalibrationExpanded: Boolean = false,
    val isShotSeriesExpanded: Boolean = true,
    val seriesNameInput: String = "",
    val inProgressShotEvents: List<ShotEvent> = emptyList(),
    val shotSeries: List<ShotSeries> = emptyList(),
    val playingClipId: String? = null
) {
    val totalSavedCount: Int = shotSeries.sumOf { it.recordedRoundCount }
}

class ShotCounterViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val GRAPH_MARKER_PEAK = 1
        const val GRAPH_MARKER_SHOT = 1 shl 1
    }

    private val prefs = application.getSharedPreferences("shot_counter_poc", 0)
    private val database = AppDatabase.get(application)

    private val meterMinDb = 80f
    private val meterMaxDb = 180f
    private val sampleRate = 44_100
    // Peak drop threshold is computed dynamically as a fraction of the display range (see updatePeakTracking)
    private val peakDropConfirmFraction = 0.04f // 4% of display range drop required to confirm a peak
    private val peakSmoothingAlpha = 0.25f
    private val historyLimit = 240

    // Audio clip window: 300 ms pre-trigger + 700 ms post-trigger = 1 s total
    private val preTriggerMs = 300
    private val postTriggerMs = 700

    private val defaultShotThresholdDb = 110f
    private val defaultRearmThresholdDb = 95f
    private val defaultMinShotGapMs = 300

    private val keyShotThresholdDb = "cal_shot_threshold_db"
    private val keyRearmThresholdDb = "cal_rearm_threshold_db"
    private val keyMinShotGapMs = "cal_min_shot_gap_ms"
    private val keyDisplayMinDb = "display_min_db"
    private val keyDisplayMaxDb = "display_max_db"
    private val keyBarDecayMsPerDb = "bar_decay_ms_per_db"
    private val keyFallbackRearmMarginDb = "fallback_rearm_margin_db"
    private val defaultFallbackRearmMarginDb = 3f
    private val keyCounterExpanded = "counter_expanded"
    private val keySoundLevelsExpanded = "sound_levels_expanded"
    private val keyCalibrationExpanded = "calibration_expanded"
    private val keyShotSeriesExpanded = "shot_series_expanded"

    private val calibration = loadCalibration()
    private val displaySettings = loadDisplaySettings()
    private val sectionExpansion = loadSectionExpansion()

    private val _uiState = MutableStateFlow(
        ShotCounterUiState(
            shotThresholdDb = calibration.shotThresholdDb,
            rearmThresholdDb = calibration.rearmThresholdDb,
            minShotGapMs = calibration.minShotGapMs,
            displayMinDb = displaySettings.displayMinDb,
            displayMaxDb = displaySettings.displayMaxDb,
            barDecayMsPerDb = displaySettings.barDecayMsPerDb,
            fallbackRearmMarginDb = displaySettings.fallbackRearmMarginDb,
            isCounterExpanded = sectionExpansion.isCounterExpanded,
            isSoundLevelsExpanded = sectionExpansion.isSoundLevelsExpanded,
            isCalibrationExpanded = sectionExpansion.isCalibrationExpanded,
            isShotSeriesExpanded = sectionExpansion.isShotSeriesExpanded,
            seriesNameInput = defaultSeriesName()
        )
    )
    val uiState: StateFlow<ShotCounterUiState> = _uiState.asStateFlow()

    private var listenJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var clearLastShotJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    private var shotArmed = true
    private var lastShotEpochMs = 0L
    private var peakSmoothedDb = displaySettings.displayMinDb
    private var peakCandidateDb: Float? = null
    private var peakCandidateSampleNumber = 0L
    private var currentSampleNumber = 0L
    private var peakTrackingInitialized = false
    private var peakRearmed = true          // false after a peak is confirmed, until level drops then rises
    private var peakMinAfterConfirm = Float.MAX_VALUE  // minimum dB seen since last peak confirmation
    private var lastDbUpdateEpochMs = 0L

    private val testModePeakLimit = 30

    private var testModeLastShotEpochMs: Long? = null

    data class CalibrationSettings(
        val shotThresholdDb: Float,
        val rearmThresholdDb: Float,
        val minShotGapMs: Int
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = database.dao().getAllWithEvents()
            val series = if (entries.isEmpty()) {
                val migrated = loadSeriesFromSharedPrefs()
                if (migrated.isNotEmpty()) {
                    migrated.forEach { s ->
                        database.dao().insertSeries(s.toEntity())
                        database.dao().insertEvents(s.shots.map { it.toEntity(s.id) })
                    }
                    database.dao().getAllWithEvents().map { it.toDomain() }
                } else {
                    emptyList()
                }
            } else {
                entries.map { it.toDomain() }
            }
            _uiState.update { it.copy(shotSeries = series, seriesNameInput = defaultSeriesName()) }
        }
    }

    fun incrementManually() {
        _uiState.update { it.copy(currentCount = it.currentCount + 1) }
    }

    fun decrementManually() {
        _uiState.update { it.copy(currentCount = max(0, it.currentCount - 1)) }
    }

    data class SectionExpansionSettings(
        val isCounterExpanded: Boolean,
        val isSoundLevelsExpanded: Boolean,
        val isCalibrationExpanded: Boolean,
        val isShotSeriesExpanded: Boolean
    )

    fun discardCurrentSeries() {
        _uiState.update {
            it.copy(
                currentCount = 0,
                seriesNameInput = defaultSeriesName(),
                inProgressShotEvents = emptyList()
            )
        }
    }

    fun setCounterExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isCounterExpanded = expanded) }
        prefs.edit().putBoolean(keyCounterExpanded, expanded).apply()
    }

    fun setSoundLevelsExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isSoundLevelsExpanded = expanded) }
        prefs.edit().putBoolean(keySoundLevelsExpanded, expanded).apply()
    }

    fun setCalibrationExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isCalibrationExpanded = expanded) }
        prefs.edit().putBoolean(keyCalibrationExpanded, expanded).apply()
    }

    fun setShotSeriesExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isShotSeriesExpanded = expanded) }
        prefs.edit().putBoolean(keyShotSeriesExpanded, expanded).apply()
    }

    fun updateSeriesNameInput(value: String) {
        _uiState.update { it.copy(seriesNameInput = value) }
    }

    fun onSeriesNameFocused() {
        _uiState.update {
            if (it.seriesNameInput == defaultSeriesName()) {
                it.copy(seriesNameInput = "")
            } else {
                it
            }
        }
    }

    fun onSeriesNameFocusLost() {
        _uiState.update {
            if (it.seriesNameInput.isBlank()) {
                it.copy(seriesNameInput = defaultSeriesName())
            } else {
                it
            }
        }
    }

    fun saveSeries() {
        val snapshot = _uiState.value
        if (snapshot.currentCount <= 0) return
        val name = snapshot.seriesNameInput.ifBlank { defaultSeriesName() }
        val seriesId = UUID.randomUUID().toString()
        val shots = snapshot.inProgressShotEvents.map { it.copy(shotSeriesId = seriesId) }
        val created = ShotSeries(
            id = seriesId,
            name = name,
            recordedRoundCount = snapshot.currentCount,
            createdAtMillis = System.currentTimeMillis(),
            shots = shots
        )
        val updated = listOf(created) + snapshot.shotSeries
        _uiState.update {
            it.copy(
                currentCount = 0,
                seriesNameInput = defaultSeriesName(),
                inProgressShotEvents = emptyList(),
                shotSeries = updated
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            database.dao().insertSeries(created.toEntity())
            if (shots.isNotEmpty()) {
                database.dao().insertEvents(shots.map { it.toEntity(seriesId) })
            }
        }
    }

    fun saveInProgressWithDefaultName() {
        val snapshot = _uiState.value
        if (snapshot.currentCount <= 0) return
        val seriesId = UUID.randomUUID().toString()
        val shots = snapshot.inProgressShotEvents.map { it.copy(shotSeriesId = seriesId) }
        val created = ShotSeries(
            id = seriesId,
            name = defaultSeriesName(),
            recordedRoundCount = snapshot.currentCount,
            createdAtMillis = System.currentTimeMillis(),
            shots = shots
        )
        val updated = listOf(created) + snapshot.shotSeries
        _uiState.update {
            it.copy(
                currentCount = 0,
                seriesNameInput = defaultSeriesName(),
                inProgressShotEvents = emptyList(),
                shotSeries = updated
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            database.dao().insertSeries(created.toEntity())
            if (shots.isNotEmpty()) {
                database.dao().insertEvents(shots.map { it.toEntity(seriesId) })
            }
        }
    }

    fun deleteSeries(id: String) {
        val updated = _uiState.value.shotSeries.filterNot { it.id == id }
        _uiState.update { it.copy(shotSeries = updated) }
        viewModelScope.launch(Dispatchers.IO) {
            val clipPaths = database.dao().getClipPathsForSeries(id)
            database.dao().deleteSeries(id)
            clipPaths.forEach { File(it).delete() }
        }
    }

    fun deleteAllSeries() {
        _uiState.update { it.copy(shotSeries = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val clipPaths = database.dao().getAllClipPaths()
            database.dao().deleteAll()
            clipPaths.forEach { File(it).delete() }
        }
    }

    fun exportShotSeriesJson(): String {
        val array = JSONArray()
        _uiState.value.shotSeries.forEach { series ->
            array.put(series.toJsonObject())
        }
        return array.toString(2)
    }

    fun updateShotThresholdDb(value: Float) {
        _uiState.update { state ->
            val newShot = value.coerceIn(1f, meterMaxDb)
            val newRearm = state.rearmThresholdDb.coerceIn(0f, newShot - 1f)
            state.copy(shotThresholdDb = newShot, rearmThresholdDb = newRearm)
        }
        persistCalibrationFromState()
    }

    fun updateRearmThresholdDb(value: Float) {
        _uiState.update { state ->
            val newRearm = value.coerceIn(0f, state.shotThresholdDb - 1f)
            state.copy(rearmThresholdDb = newRearm)
        }
        persistCalibrationFromState()
    }

    fun updateMinShotGapMs(value: Int) {
        _uiState.update { state ->
            state.copy(minShotGapMs = value.coerceIn(100, 2000))
        }
        persistCalibrationFromState()
    }

    fun resetCalibrationDefaults() {
        _uiState.update {
            it.copy(
                shotThresholdDb = defaultShotThresholdDb,
                rearmThresholdDb = defaultRearmThresholdDb,
                minShotGapMs = defaultMinShotGapMs
            )
        }
        persistCalibrationFromState()
    }

    fun toggleCalibrationTestMode() {
        _uiState.update { state ->
            if (state.isCalibrationTestMode) {
                testModeLastShotEpochMs = null
                state.copy(isCalibrationTestMode = false)
            } else {
                testModeLastShotEpochMs = null
                state.copy(
                    isCalibrationTestMode = true,
                    calibrationTestShotPeaks = emptyList(),
                    calibrationTestIntervalsMs = emptyList()
                )
            }
        }
    }

    fun clearCalibrationTestSamples() {
        testModeLastShotEpochMs = null
        _uiState.update {
            it.copy(
                calibrationTestShotPeaks = emptyList(),
                calibrationTestIntervalsMs = emptyList()
            )
        }
    }

    fun applySuggestedCalibration() {
        val state = _uiState.value
        val suggestion = computeCalibrationSuggestion(
            state.calibrationTestShotPeaks,
            state.calibrationTestIntervalsMs
        ) ?: return

        _uiState.update {
            it.copy(
                shotThresholdDb = suggestion.shotThresholdDb,
                rearmThresholdDb = suggestion.rearmThresholdDb,
                minShotGapMs = suggestion.minShotGapMs
            )
        }
        persistCalibrationFromState()
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (_uiState.value.isListening) return

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 4
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        shotArmed = true
        peakSmoothedDb = _uiState.value.displayMinDb
        peakCandidateDb = null
        peakCandidateSampleNumber = 0L
        currentSampleNumber = 0L
        peakTrackingInitialized = false
        peakRearmed = true
        peakMinAfterConfirm = Float.MAX_VALUE
        lastDbUpdateEpochMs = System.currentTimeMillis()
        audioRecord = record
        _uiState.update {
            it.copy(
                isListening = true,
                liveDb = it.displayMinDb,
                displayedDb = it.displayMinDb,
                lastPeakDb = null,
                dbHistory = emptyList(),
                dbMarkerHistory = emptyList()
            )
        }

        val preTriggerSamples = preTriggerMs * sampleRate / 1000
        val postTriggerSamples = postTriggerMs * sampleRate / 1000

        listenJob = viewModelScope.launch(Dispatchers.Default) {
            val ringBuffer = CircularShortBuffer(preTriggerSamples)
            val buffer = ShortArray(minBuffer)
            var preTriggerSnapshot: ShortArray? = null
            var postTriggerBuf: ShortArray? = null
            var postTriggerRemaining = 0
            var pendingClipEvent: ShotEvent? = null

            record.startRecording()
            while (isActive) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                ringBuffer.write(buffer, read)

                if (postTriggerRemaining > 0) {
                    val toCopy = minOf(read, postTriggerRemaining)
                    val offset = postTriggerSamples - postTriggerRemaining
                    val currentPostTriggerBuf = postTriggerBuf
                    if (currentPostTriggerBuf != null) {
                        System.arraycopy(buffer, 0, currentPostTriggerBuf, offset, toCopy)
                    }
                    postTriggerRemaining -= toCopy

                    if (postTriggerRemaining <= 0) {
                        val pre = preTriggerSnapshot
                        val post = postTriggerBuf
                        val event = pendingClipEvent
                        if (pre != null && post != null && event != null) {
                            val combined = pre + post
                            launch(Dispatchers.IO) { saveClip(event, combined) }
                        }
                        preTriggerSnapshot = null
                        postTriggerBuf = null
                        pendingClipEvent = null
                    }
                }

                val db = estimateDb(buffer, read).coerceIn(meterMinDb, meterMaxDb)
                val firedEvent = applyDbSmoothingAndDetection(db)

                if (firedEvent != null && postTriggerRemaining <= 0) {
                    preTriggerSnapshot = ringBuffer.snapshot()
                    postTriggerBuf = ShortArray(postTriggerSamples)
                    postTriggerRemaining = postTriggerSamples
                    pendingClipEvent = firedEvent
                }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        listenJob = null

        audioRecord?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // No-op
            }
            it.release()
        }
        audioRecord = null
        val displayMin = _uiState.value.displayMinDb
        _uiState.update { it.copy(isListening = false, liveDb = displayMin, displayedDb = displayMin) }
    }

    override fun onCleared() {
        stopListening()
        stopClip()
        super.onCleared()
    }

    private fun saveClip(event: ShotEvent, samples: ShortArray) {
        val clipsDir = File(getApplication<Application>().filesDir, "clips")
        clipsDir.mkdirs()
        val file = File(clipsDir, "${event.id}.wav")
        try {
            WavWriter.write(file, sampleRate, samples)
        } catch (_: Exception) {
            return
        }
        val path = file.absolutePath
        viewModelScope.launch(Dispatchers.IO) {
            database.dao().updateClipPath(event.id, path)
        }
        _uiState.update { state ->
            state.copy(
                inProgressShotEvents = state.inProgressShotEvents.map {
                    if (it.id == event.id) it.copy(audioClipPath = path) else it
                },
                shotSeries = state.shotSeries.map { series ->
                    series.copy(shots = series.shots.map { shot ->
                        if (shot.id == event.id) shot.copy(audioClipPath = path) else shot
                    })
                }
            )
        }
    }

    fun playClip(event: ShotEvent) {
        val path = event.audioClipPath ?: return
        stopClip()
        val player = MediaPlayer()
        try {
            player.setDataSource(path)
            player.prepare()
            player.setOnCompletionListener {
                _uiState.update { s -> s.copy(playingClipId = null) }
                it.release()
                if (mediaPlayer == it) mediaPlayer = null
            }
            player.start()
            mediaPlayer = player
            _uiState.update { it.copy(playingClipId = event.id) }
        } catch (_: Exception) {
            player.release()
        }
    }

    fun stopClip() {
        mediaPlayer?.apply {
            try {
                stop()
            } catch (_: Exception) {
                // No-op
            }
            release()
        }
        mediaPlayer = null
        _uiState.update { it.copy(playingClipId = null) }
    }

    private fun applyDbSmoothingAndDetection(db: Float): ShotEvent? {
        val now = System.currentTimeMillis()
        val thresholds = _uiState.value
        val elapsedMs = if (lastDbUpdateEpochMs > 0L) (now - lastDbUpdateEpochMs).coerceAtLeast(0L) else 0L
        lastDbUpdateEpochMs = now

        val isHold = thresholds.barDecayMsPerDb < 0f
        val decayAmount = if (thresholds.barDecayMsPerDb > 0f) elapsedMs / thresholds.barDecayMsPerDb else 0f
        val decayed = thresholds.displayedDb - decayAmount
        val smoothed = if (isHold) {
            max(db, thresholds.displayedDb)
        } else if (thresholds.barDecayMsPerDb <= 0f) {
            db
        } else if (db >= decayed) {
            db
        } else {
            max(db, decayed)
        }
        val clampedSmoothed = smoothed.coerceIn(thresholds.displayMinDb, thresholds.displayMaxDb)
        val sampleNumber = currentSampleNumber++

        val confirmedPeak = updatePeakTracking(db, clampedSmoothed, sampleNumber)

        if (!shotArmed) {
            val droppedBelowRearm = db < thresholds.rearmThresholdDb
            val droppedBelowShotByMargin = db < (thresholds.shotThresholdDb - thresholds.fallbackRearmMarginDb)
            val gapElapsed = now - lastShotEpochMs >= thresholds.minShotGapMs.toLong()
            if (droppedBelowRearm || (gapElapsed && droppedBelowShotByMargin)) {
                shotArmed = true
            }
        }

        var shotEvent: ShotEvent? = null

        if (shotArmed && db >= thresholds.shotThresholdDb && now - lastShotEpochMs >= thresholds.minShotGapMs.toLong()) {
            shotArmed = false
            lastShotEpochMs = now
            shotEvent = ShotEvent(
                id = UUID.randomUUID().toString(),
                shotSeriesId = null,
                detectedAtMillis = now,
                confidence = calculateShotConfidence(db, thresholds.shotThresholdDb),
                peakDb = db
            )

            recordCalibrationTestShotIfEnabled(db, now)

            clearLastShotJob?.cancel()
            clearLastShotJob = viewModelScope.launch {
                delay(2500)
                _uiState.update { state ->
                    state.copy(lastShotDb = null)
                }
            }
        }

        _uiState.update { state ->
            val updatedHistory = (state.dbHistory + clampedSmoothed).takeLast(historyLimit)
            val updatedMarkers = ((state.dbMarkerHistory + if (shotEvent != null) GRAPH_MARKER_SHOT else 0).takeLast(historyLimit)).toMutableList()

            confirmedPeak?.let { peakConfirmation ->
                val samplesAgo = sampleNumber - peakConfirmation.sampleNumber
                val markerIndex = updatedMarkers.lastIndex - samplesAgo.toInt()
                if (markerIndex in updatedMarkers.indices) {
                    updatedMarkers[markerIndex] = updatedMarkers[markerIndex] or GRAPH_MARKER_PEAK
                }
            }

            state.copy(
                liveDb = db,
                displayedDb = clampedSmoothed,
                lastPeakDb = confirmedPeak?.value ?: state.lastPeakDb,
                currentCount = state.currentCount + if (shotEvent != null) 1 else 0,
                inProgressShotEvents = if (shotEvent != null) state.inProgressShotEvents + shotEvent else state.inProgressShotEvents,
                lastShotDb = if (shotEvent != null) db else state.lastShotDb,
                shotFlashTriggerMs = if (shotEvent != null) now else state.shotFlashTriggerMs,
                dbHistory = updatedHistory,
                dbMarkerHistory = updatedMarkers
            )
        }

        return shotEvent
    }

    private data class PeakConfirmation(
        val value: Float,
        val sampleNumber: Long
    )

    private fun updatePeakTracking(rawDb: Float, smoothedDb: Float, sampleNumber: Long): PeakConfirmation? {
        val displayRange = _uiState.value.displayMaxDb - _uiState.value.displayMinDb
        val peakDropConfirmDb = displayRange * peakDropConfirmFraction

        peakSmoothedDb += (smoothedDb - peakSmoothedDb) * peakSmoothingAlpha
        val peakInput = max(rawDb, peakSmoothedDb)

        if (!peakTrackingInitialized) {
            peakTrackingInitialized = true
            return null
        }

        // Re-arm: after a peak is confirmed, wait for the level to drop then rise by
        // peakDropConfirmDb before allowing a new peak candidate to start.
        if (!peakRearmed) {
            if (peakInput < peakMinAfterConfirm) peakMinAfterConfirm = peakInput
            if (peakInput - peakMinAfterConfirm >= peakDropConfirmDb) {
                peakRearmed = true
                peakMinAfterConfirm = Float.MAX_VALUE
            } else {
                return null
            }
        }

        if (peakCandidateDb == null) {
            peakCandidateDb = peakInput
            peakCandidateSampleNumber = sampleNumber
            return null
        }

        val candidate = peakCandidateDb ?: peakInput
        if (peakInput >= candidate) {
            peakCandidateDb = peakInput
            peakCandidateSampleNumber = sampleNumber
            return null
        }

        if (candidate - peakInput >= peakDropConfirmDb) {
            val confirmedPeak = PeakConfirmation(
                value = candidate,
                sampleNumber = peakCandidateSampleNumber
            )
            peakCandidateDb = null
            peakCandidateSampleNumber = 0L
            peakRearmed = false
            peakMinAfterConfirm = peakInput
            return confirmedPeak
        }

        return null
    }

    private fun recordCalibrationTestShotIfEnabled(db: Float, epochMs: Long) {
        _uiState.update { state ->
            if (!state.isCalibrationTestMode) {
                return@update state
            }

            val updatedPeaks = (listOf(db) + state.calibrationTestShotPeaks).take(testModePeakLimit)

            val updatedIntervals = testModeLastShotEpochMs?.let { lastEpoch ->
                (listOf(epochMs - lastEpoch) + state.calibrationTestIntervalsMs).take(testModePeakLimit)
            } ?: state.calibrationTestIntervalsMs

            testModeLastShotEpochMs = epochMs

            state.copy(
                calibrationTestShotPeaks = updatedPeaks,
                calibrationTestIntervalsMs = updatedIntervals
            )
        }
    }

    data class CalibrationSuggestion(
        val shotThresholdDb: Float,
        val rearmThresholdDb: Float,
        val minShotGapMs: Int,
        val confidenceLabel: String,
        val confidenceDetails: String
    )

    fun currentCalibrationSuggestion(): CalibrationSuggestion? {
        val state = _uiState.value
        return computeCalibrationSuggestion(state.calibrationTestShotPeaks, state.calibrationTestIntervalsMs)
    }

    private fun computeCalibrationSuggestion(
        peaks: List<Float>,
        intervalsMs: List<Long>
    ): CalibrationSuggestion? {
        if (peaks.size < 10) return null

        val sortedPeaks = peaks.sorted()
        val p25Peak = percentile(sortedPeaks, 0.25f)
        val suggestedShot = (p25Peak - 6f).coerceIn(90f, meterMaxDb)
        val suggestedRearm = (suggestedShot - 12f).coerceIn(meterMinDb, suggestedShot - 1f)

        val suggestedGap = if (intervalsMs.isEmpty()) {
            defaultMinShotGapMs
        } else {
            val sortedIntervals = intervalsMs.sorted()
            val p10Interval = percentile(sortedIntervals.map { it.toFloat() }, 0.10f)
            (p10Interval * 0.5f).roundToInt().coerceIn(100, 2000)
        }

        val mean = peaks.average().toFloat().coerceAtLeast(1f)
        val variance = peaks.fold(0.0) { acc, v ->
            val d = v - mean
            acc + d * d
        } / peaks.size
        val stdDev = kotlin.math.sqrt(variance).toFloat()
        val cv = stdDev / mean

        val confidenceLabel = when {
            peaks.size >= 20 && cv <= 0.08f -> "High"
            peaks.size >= 14 && cv <= 0.14f -> "Medium"
            else -> "Low"
        }

        val confidenceDetails = "samples=${peaks.size}, variability=${"%.1f".format(cv * 100f)}%"

        return CalibrationSuggestion(
            shotThresholdDb = suggestedShot,
            rearmThresholdDb = suggestedRearm,
            minShotGapMs = suggestedGap,
            confidenceLabel = confidenceLabel,
            confidenceDetails = confidenceDetails
        )
    }

    private fun percentile(sortedValues: List<Float>, percentile: Float): Float {
        if (sortedValues.isEmpty()) return 0f
        if (sortedValues.size == 1) return sortedValues[0]

        val clamped = percentile.coerceIn(0f, 1f)
        val index = clamped * (sortedValues.lastIndex)
        val lower = index.toInt()
        val upper = (lower + 1).coerceAtMost(sortedValues.lastIndex)
        val weight = index - lower

        return sortedValues[lower] * (1f - weight) + sortedValues[upper] * weight
    }

    private fun estimateDb(buffer: ShortArray, read: Int): Float {
        if (read <= 0) return meterMinDb
        var sumSquares = 0.0
        for (i in 0 until read) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / read)
        if (rms <= 0.0) return meterMinDb

        // Relative dB scale for this device mic path (not absolute SPL).
        val dbfs = 20.0 * log10(rms / 32768.0)
        return (dbfs + 105.0).toFloat()
    }

    private fun defaultSeriesName(): String {
        val datePart = LocalDate.now()
        val timePart = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        return "Shot Series $datePart $timePart"
    }

    private fun calculateShotConfidence(db: Float, thresholdDb: Float): Float {
        return ((db - thresholdDb + 3f) / 15f).coerceIn(0.05f, 1f)
    }

    private fun Long.toIsoUtcString(): String {
        return Instant.ofEpochMilli(this).toString()
    }

    private fun ShotEvent.toJsonObject(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("shot_series_id", shotSeriesId)
            .put("detected_at", detectedAtMillis.toIsoUtcString())
            .put("confidence", confidence)
            .put("peak_db", peakDb)
            .put("audio_clip_path", audioClipPath)
    }

    private fun ShotSeries.toJsonObject(): JSONObject {
        val shotsArray = JSONArray()
        shots.forEach { shot ->
            shotsArray.put(shot.toJsonObject())
        }

        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("recorded_round_count", recordedRoundCount)
            .put("created_at", createdAtMillis.toIsoUtcString())
            .put("shots", shotsArray)
    }

    private fun ShotSeries.toEntity() = ShotSeriesEntity(id, name, recordedRoundCount, createdAtMillis)

    private fun ShotEvent.toEntity(seriesId: String) =
        ShotEventEntity(id, seriesId, detectedAtMillis, confidence, peakDb, audioClipPath)

    private fun ShotSeriesWithEvents.toDomain() = ShotSeries(
        id = series.id,
        name = series.name,
        recordedRoundCount = series.recordedRoundCount,
        createdAtMillis = series.createdAtMillis,
        shots = shots.map { it.toDomain(series.id) }
    )

    private fun ShotEventEntity.toDomain(seriesId: String) = ShotEvent(
        id = id,
        shotSeriesId = seriesId,
        detectedAtMillis = detectedAtMillis,
        confidence = confidence,
        peakDb = peakDb,
        audioClipPath = audioClipPath
    )

    private fun loadSeriesFromSharedPrefs(): List<ShotSeries> {
        val raw = prefs.getString("shot_series", null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(
                        ShotSeries(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            name = item.optString("name", defaultSeriesName()),
                            recordedRoundCount = item.optInt(
                                "recorded_round_count",
                                item.optInt("recordedRoundCount", item.optInt("count", 0))
                            ),
                            createdAtMillis = item.optLong("createdAtMillis", 0L),
                            shots = item.optJSONArray("shots").toShotEvents()
                        )
                    )
                }
            }.sortedByDescending { it.createdAtMillis }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun JSONArray?.toShotEvents(): List<ShotEvent> {
        if (this == null) return emptyList()

        return buildList {
            for (i in 0 until length()) {
                val item = optJSONObject(i) ?: continue
                add(
                    ShotEvent(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        shotSeriesId = item.optString("shot_series_id").takeIf { it.isNotBlank() },
                        detectedAtMillis = item.optString("detected_at")
                            .takeIf { it.isNotBlank() }
                            ?.let {
                                runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
                            }
                            ?: item.optLong("detectedAtMillis", 0L),
                        confidence = item.optDouble("confidence", 0.0).toFloat(),
                        peakDb = item.optDouble("peak_db", item.optDouble("peakDb", 0.0)).toFloat(),
                        audioClipPath = item.optString("audio_clip_path").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private fun persistCalibrationFromState() {
        val state = _uiState.value
        prefs.edit()
            .putFloat(keyShotThresholdDb, state.shotThresholdDb)
            .putFloat(keyRearmThresholdDb, state.rearmThresholdDb)
            .putInt(keyMinShotGapMs, state.minShotGapMs)
            .apply()
    }

    private fun loadCalibration(): CalibrationSettings {
        val shot = prefs.getFloat(keyShotThresholdDb, defaultShotThresholdDb)
            .coerceIn(90f, meterMaxDb)
        val rearm = prefs.getFloat(keyRearmThresholdDb, defaultRearmThresholdDb)
            .coerceIn(meterMinDb, shot - 1f)
        val gap = prefs.getInt(keyMinShotGapMs, defaultMinShotGapMs)
            .coerceIn(100, 2000)
        return CalibrationSettings(
            shotThresholdDb = shot,
            rearmThresholdDb = rearm,
            minShotGapMs = gap
        )
    }

    private data class DisplaySettings(
        val displayMinDb: Float,
        val displayMaxDb: Float,
        val barDecayMsPerDb: Float,
        val fallbackRearmMarginDb: Float
    )

    private fun loadDisplaySettings(): DisplaySettings {
        val min = prefs.getFloat(keyDisplayMinDb, 80f).coerceIn(0f, 179f)
        val max = prefs.getFloat(keyDisplayMaxDb, 180f).coerceIn(1f, 180f)
        val adjustedMin = min.coerceAtMost(max - 5f)
        val adjustedMax = max.coerceAtLeast(adjustedMin + 5f)
        val decay = prefs.getFloat(keyBarDecayMsPerDb, 100f).let { v ->
            if (v < 0f) -1f else ((v / 25f).roundToInt() * 25f).toFloat().coerceIn(0f, 500f)
        }
        val margin = prefs.getFloat(keyFallbackRearmMarginDb, defaultFallbackRearmMarginDb).coerceIn(1f, 20f)
        return DisplaySettings(adjustedMin, adjustedMax, decay, margin)
    }

    private fun loadSectionExpansion(): SectionExpansionSettings {
        return SectionExpansionSettings(
            isCounterExpanded = prefs.getBoolean(keyCounterExpanded, true),
            isSoundLevelsExpanded = prefs.getBoolean(keySoundLevelsExpanded, true),
            isCalibrationExpanded = prefs.getBoolean(keyCalibrationExpanded, false),
            isShotSeriesExpanded = prefs.getBoolean(keyShotSeriesExpanded, true)
        )
    }

    fun updateDisplayMinDb(value: Float) {
        _uiState.update { state ->
            val newMin = value.coerceIn(0f, state.displayMaxDb - 5f)
            state.copy(displayMinDb = newMin)
        }
        persistDisplaySettingsFromState()
    }

    fun updateDisplayMaxDb(value: Float) {
        _uiState.update { state ->
            val newMax = value.coerceIn(state.displayMinDb + 5f, 180f)
            state.copy(displayMaxDb = newMax)
        }
        persistDisplaySettingsFromState()
    }

    fun updateBarDecayMsPerDb(value: Float) {
        val snapped = if (value < 0f) -1f else ((value / 25f).roundToInt() * 25f).toFloat().coerceIn(0f, 500f)
        _uiState.update { state ->
            state.copy(barDecayMsPerDb = snapped)
        }
        persistDisplaySettingsFromState()
    }

    fun updateFallbackRearmMarginDb(value: Float) {
        _uiState.update { state ->
            state.copy(fallbackRearmMarginDb = value.coerceIn(1f, 20f))
        }
        persistDisplaySettingsFromState()
    }

    private fun persistDisplaySettingsFromState() {
        val state = _uiState.value
        prefs.edit()
            .putFloat(keyDisplayMinDb, state.displayMinDb)
            .putFloat(keyDisplayMaxDb, state.displayMaxDb)
            .putFloat(keyBarDecayMsPerDb, state.barDecayMsPerDb)
            .putFloat(keyFallbackRearmMarginDb, state.fallbackRearmMarginDb)
            .apply()
    }
}
