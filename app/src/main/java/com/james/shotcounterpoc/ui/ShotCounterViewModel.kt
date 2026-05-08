package com.james.shotcounterpoc.ui

import android.annotation.SuppressLint
import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ShotSeries(
    val id: String,
    val name: String,
    val count: Int,
    val createdAtMillis: Long
)

data class ShotCounterUiState(
    val currentCount: Int = 0,
    val displayedDb: Float = 80f,
    val liveDb: Float = 80f,
    val lastShotDb: Float? = null,
    val shotFlashTriggerMs: Long = 0L,
    val isListening: Boolean = false,
    val shotThresholdDb: Float = 110f,
    val rearmThresholdDb: Float = 95f,
    val minShotGapMs: Int = 300,
    val isCalibrationTestMode: Boolean = false,
    val calibrationTestShotPeaks: List<Float> = emptyList(),
    val calibrationTestIntervalsMs: List<Long> = emptyList(),
    val seriesNameInput: String = "",
    val shotSeries: List<ShotSeries> = emptyList()
) {
    val totalSavedCount: Int = shotSeries.sumOf { it.count }
}

class ShotCounterViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("shot_counter_poc", 0)

    private val meterMinDb = 80f
    private val meterMaxDb = 180f

    private val defaultShotThresholdDb = 110f
    private val defaultRearmThresholdDb = 95f
    private val defaultMinShotGapMs = 300

    private val keyShotThresholdDb = "cal_shot_threshold_db"
    private val keyRearmThresholdDb = "cal_rearm_threshold_db"
    private val keyMinShotGapMs = "cal_min_shot_gap_ms"

    private val calibration = loadCalibration()

    private val _uiState = MutableStateFlow(
        ShotCounterUiState(
            shotThresholdDb = calibration.shotThresholdDb,
            rearmThresholdDb = calibration.rearmThresholdDb,
            minShotGapMs = calibration.minShotGapMs,
            seriesNameInput = defaultSeriesName(),
            shotSeries = loadSeriesFromStorage()
        )
    )
    val uiState: StateFlow<ShotCounterUiState> = _uiState.asStateFlow()

    private var listenJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var clearLastShotJob: Job? = null

    private var shotArmed = true
    private var lastShotEpochMs = 0L

    private val testModePeakLimit = 30

    private var testModeLastShotEpochMs: Long? = null

    data class CalibrationSettings(
        val shotThresholdDb: Float,
        val rearmThresholdDb: Float,
        val minShotGapMs: Int
    )

    fun incrementManually() {
        _uiState.update { it.copy(currentCount = it.currentCount + 1) }
    }

    fun decrementManually() {
        _uiState.update { it.copy(currentCount = max(0, it.currentCount - 1)) }
    }

    fun resetCurrentCount() {
        _uiState.update { it.copy(currentCount = 0) }
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
        val name = snapshot.seriesNameInput.ifBlank { defaultSeriesName() }
        val created = ShotSeries(
            id = UUID.randomUUID().toString(),
            name = name,
            count = snapshot.currentCount,
            createdAtMillis = System.currentTimeMillis()
        )

        val updated = listOf(created) + snapshot.shotSeries
        _uiState.update {
            it.copy(
                currentCount = 0,
                seriesNameInput = defaultSeriesName(),
                shotSeries = updated
            )
        }
        persistSeries(updated)
    }

    fun saveInProgressWithDefaultName() {
        val snapshot = _uiState.value
        if (snapshot.currentCount <= 0) return

        val created = ShotSeries(
            id = UUID.randomUUID().toString(),
            name = defaultSeriesName(),
            count = snapshot.currentCount,
            createdAtMillis = System.currentTimeMillis()
        )
        val updated = listOf(created) + snapshot.shotSeries
        _uiState.update {
            it.copy(
                currentCount = 0,
                seriesNameInput = defaultSeriesName(),
                shotSeries = updated
            )
        }
        persistSeries(updated)
    }

    fun deleteSeries(id: String) {
        val updated = _uiState.value.shotSeries.filterNot { it.id == id }
        _uiState.update { it.copy(shotSeries = updated) }
        persistSeries(updated)
    }

    fun deleteAllSeries() {
        _uiState.update { it.copy(shotSeries = emptyList()) }
        persistSeries(emptyList())
    }

    fun updateShotThresholdDb(value: Float) {
        _uiState.update { state ->
            val newShot = value.coerceIn(90f, meterMaxDb)
            val newRearm = state.rearmThresholdDb.coerceIn(meterMinDb, newShot - 1f)
            state.copy(shotThresholdDb = newShot, rearmThresholdDb = newRearm)
        }
        persistCalibrationFromState()
    }

    fun updateRearmThresholdDb(value: Float) {
        _uiState.update { state ->
            val newRearm = value.coerceIn(meterMinDb, state.shotThresholdDb - 1f)
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

        val sampleRate = 44_100
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
            minBuffer * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        shotArmed = true
        audioRecord = record
        _uiState.update { it.copy(isListening = true, liveDb = meterMinDb, displayedDb = meterMinDb) }

        listenJob = viewModelScope.launch(Dispatchers.Default) {
            val buffer = ShortArray(minBuffer)
            record.startRecording()
            while (true) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val db = estimateDb(buffer, read).coerceIn(meterMinDb, meterMaxDb)
                applyDbSmoothingAndDetection(db)
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
        _uiState.update { it.copy(isListening = false, liveDb = meterMinDb, displayedDb = meterMinDb) }
    }

    override fun onCleared() {
        stopListening()
        super.onCleared()
    }

    private fun applyDbSmoothingAndDetection(db: Float) {
        val now = System.currentTimeMillis()
        val thresholds = _uiState.value

        _uiState.update { state ->
            val smoothed = if (db >= state.displayedDb) {
                db
            } else {
                max(db, state.displayedDb - 1.6f)
            }
            state.copy(liveDb = db, displayedDb = smoothed)
        }

        if (!shotArmed && db < thresholds.rearmThresholdDb) {
            shotArmed = true
        }

        if (shotArmed && db >= thresholds.shotThresholdDb && now - lastShotEpochMs >= thresholds.minShotGapMs.toLong()) {
            shotArmed = false
            lastShotEpochMs = now
            _uiState.update {
                it.copy(
                    currentCount = it.currentCount + 1,
                    lastShotDb = db,
                    shotFlashTriggerMs = now
                )
            }

            recordCalibrationTestShotIfEnabled(db, now)

            clearLastShotJob?.cancel()
            clearLastShotJob = viewModelScope.launch {
                delay(2500)
                _uiState.update { state ->
                    state.copy(lastShotDb = null)
                }
            }
        }
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

    private fun persistSeries(series: List<ShotSeries>) {
        val array = JSONArray()
        series.forEach { row ->
            array.put(
                JSONObject()
                    .put("id", row.id)
                    .put("name", row.name)
                    .put("count", row.count)
                    .put("createdAtMillis", row.createdAtMillis)
            )
        }
        prefs.edit().putString("shot_series", array.toString()).apply()
    }

    private fun loadSeriesFromStorage(): List<ShotSeries> {
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
                            count = item.optInt("count", 0),
                            createdAtMillis = item.optLong("createdAtMillis", 0L)
                        )
                    )
                }
            }.sortedByDescending { it.createdAtMillis }
        } catch (_: Exception) {
            emptyList()
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
}
