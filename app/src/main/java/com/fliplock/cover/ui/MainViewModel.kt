package com.fliplock.cover.ui

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fliplock.cover.AppGraph
import com.fliplock.cover.R
import com.fliplock.cover.calibration.CalibrationManager
import com.fliplock.cover.calibration.CalibrationResult
import com.fliplock.cover.calibration.DarkTestResult
import com.fliplock.cover.calibration.LuxStats
import com.fliplock.cover.data.FlipLockSettings
import com.fliplock.cover.detection.CoverDetectionEngine
import com.fliplock.cover.detection.DetectionEvent
import com.fliplock.cover.detection.DetectionStrategy
import com.fliplock.cover.detection.EngineSnapshot
import com.fliplock.cover.diagnostic.DiagnosticReportBuilder
import com.fliplock.cover.diagnostic.DiagnosticRepository
import com.fliplock.cover.diagnostic.SensorProbeState
import com.fliplock.cover.log.LogCategory
import com.fliplock.cover.runtime.FlipLockRuntime
import com.fliplock.cover.sensors.LightSample
import com.fliplock.cover.sensors.ProximitySample
import com.fliplock.cover.sensors.SensorProbeResult
import com.fliplock.cover.service.AccessibilityStatus
import com.fliplock.cover.service.FlipLockAccessibilityService
import com.fliplock.cover.service.WakeOnOpenController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Une ligne d'historique des mesures (section « Historique » du diagnostic). */
data class LuxHistoryEntry(
    val wallClockMs: Long,
    val lux: Float,
    val dropPercent: Float,
    val candidate: Boolean,
)

enum class CalibrationStep { INTRO, MEASURING_OPEN, COUNTDOWN, MEASURING_CLOSED, RESULT }

data class CalibrationUiState(
    val step: CalibrationStep = CalibrationStep.INTRO,
    val running: Boolean = false,
    val progress: Float = 0f,
    val countdown: Int = 3,
    val liveLux: Float? = null,
    val openStats: LuxStats? = null,
    val result: CalibrationResult? = null,
    val applied: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = AppGraph.settings
    private val sensorRepo = AppGraph.sensors
    private val logger = AppGraph.logger
    private val diagnostics = DiagnosticRepository(sensorRepo)
    private val calibrationManager = CalibrationManager(sensorRepo, logger)

    val settings: StateFlow<FlipLockSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, FlipLockSettings.DEFAULT)

    val logs = logger.entries

    // --- etat live -----------------------------------------------------------

    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    private val _lastLight = MutableStateFlow<LightSample?>(null)
    val lastLight: StateFlow<LightSample?> = _lastLight.asStateFlow()

    private val _lastProximity = MutableStateFlow<ProximitySample?>(null)
    val lastProximity: StateFlow<ProximitySample?> = _lastProximity.asStateFlow()

    private val _snapshot = MutableStateFlow(EngineSnapshot.EMPTY)
    val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    private val _history = MutableStateFlow<List<LuxHistoryEntry>>(emptyList())
    val history: StateFlow<List<LuxHistoryEntry>> = _history.asStateFlow()

    private val _detectionTrace = MutableStateFlow(DetectionTrace())
    val detectionTrace: StateFlow<DetectionTrace> = _detectionTrace.asStateFlow()

    private val _probeState = MutableStateFlow(SensorProbeState())
    val probeState: StateFlow<SensorProbeState> = _probeState.asStateFlow()

    private val _allProbes = MutableStateFlow<List<SensorProbeResult>>(emptyList())
    val allProbes: StateFlow<List<SensorProbeResult>> = _allProbes.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private val _calibration = MutableStateFlow(CalibrationUiState())
    val calibration: StateFlow<CalibrationUiState> = _calibration.asStateFlow()

    private val _darkTest = MutableStateFlow<DarkTestResult?>(null)
    val darkTest: StateFlow<DarkTestResult?> = _darkTest.asStateFlow()

    /** Message transitoire affiche en Snackbar, identifie par sa ressource. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    val deviceInfo = diagnostics.deviceInfo
    val sensorDescriptors = sensorRepo.allDescriptors()
    val wakeUpCapability = diagnostics.wakeUpCapability()
    val hasWakeUpSensor = diagnostics.hasWakeUpSensor()

    /** Declencheurs wake-up utilisables pour le reveil a l'ouverture. */
    val wakeTriggers: String = WakeOnOpenController.describeTriggers(sensorRepo)
    val wakeOnOpenSupported: Boolean =
        WakeOnOpenController.availableTriggers(sensorRepo).isNotEmpty()

    fun wakeLuxThreshold(): Float =
        WakeOnOpenController.thresholdFor(settings.value.closedLuxThreshold)

    /**
     * Moteur « apercu » : identique a celui du service mais il ne verrouille JAMAIS.
     * Il permet de voir en direct, dans le diagnostic, ce que FlipLock deciderait.
     */
    private val previewEngine = CoverDetectionEngine(
        initialConfig = FlipLockSettings.DEFAULT.toDetectionConfig(),
        onEvent = ::onPreviewEvent,
    )

    private var backgroundedAtEventCount = -1L
    private var liveJob: Job? = null
    private var previewTickJob: Job? = null
    private var calibrationJob: Job? = null

    init {
        previewEngine.setEnabled(true, SystemClock.elapsedRealtime())
        previewEngine.setScreenInteractive(true, SystemClock.elapsedRealtime())
        previewEngine.setProximitySupported(sensorRepo.proximitySensor != null)
        viewModelScope.launch {
            settings.collect { previewEngine.updateConfig(it.toDetectionConfig()) }
        }
        viewModelScope.launch { probeCoreSensors() }
        logger.log(
            LogCategory.SYSTEM,
            "FlipLock started - ${deviceInfo.manufacturer} ${deviceInfo.model}, API ${deviceInfo.sdkInt}",
        )
    }

    // --- cycle de vie de l'ecran --------------------------------------------

    fun onForeground() {
        refreshAccessibility()
        // Temoin d'arriere-plan : combien d'evenements le SERVICE a-t-il recus
        // pendant que l'application n'etait pas a l'ecran ? C'est la mesure qui
        // distingue « Android a coupe les capteurs » de « le seuil n'etait pas atteint ».
        if (backgroundedAtEventCount >= 0L) {
            val delta = FlipLockRuntime.lightEventCount.value - backgroundedAtEventCount
            logger.log(
                LogCategory.SYSTEM,
                "app foregrounded — light events received by the service while in background: $delta",
            )
            backgroundedAtEventCount = -1L
        }
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            launch {
                sensorRepo.lightFlow().collect { sample -> onLiveLight(sample) }
            }
            launch {
                sensorRepo.proximityFlow().collect { sample -> onLiveProximity(sample) }
            }
        }
    }

    fun onBackground() {
        backgroundedAtEventCount = FlipLockRuntime.lightEventCount.value
        logger.log(
            LogCategory.SYSTEM,
            "app backgrounded — service light events so far: $backgroundedAtEventCount",
        )
        liveJob?.cancel()
        liveJob = null
        previewTickJob?.cancel()
        previewTickJob = null
    }

    private fun onLiveLight(sample: LightSample) {
        _lastLight.value = sample
        FlipLockRuntime.lastLight.value = sample
        val snap = previewEngine.onLightReading(sample.lux, sample.elapsedMs)
        _snapshot.value = snap
        appendHistory(sample, snap)
        ensurePreviewTicking(snap)
    }

    private fun onLiveProximity(sample: ProximitySample) {
        _lastProximity.value = sample
        FlipLockRuntime.lastProximity.value = sample
        _snapshot.value = previewEngine.onProximityReading(sample.near, sample.elapsedMs)
    }

    /**
     * Le capteur de lumiere est « on-change » : une fois stabilise a 0 lux il
     * n'emet plus rien. Ce ticker, actif UNIQUEMENT pendant une candidature,
     * permet a la confirmation d'aboutir.
     */
    private fun ensurePreviewTicking(snapshot: EngineSnapshot) {
        if (!snapshot.candidate) return
        if (previewTickJob?.isActive == true) return
        previewTickJob = viewModelScope.launch {
            while (isActive) {
                delay(50L)
                val snap = previewEngine.tick(SystemClock.elapsedRealtime())
                _snapshot.value = snap
                if (!snap.candidate) break
            }
        }
    }

    private fun appendHistory(sample: LightSample, snapshot: EngineSnapshot) {
        val entry = LuxHistoryEntry(
            wallClockMs = sample.wallClockMs,
            lux = sample.lux,
            dropPercent = snapshot.dropPercent,
            candidate = snapshot.candidate,
        )
        _history.value = (_history.value + entry).takeLast(MAX_HISTORY)
    }

    private fun onPreviewEvent(event: DetectionEvent) {
        val now = System.currentTimeMillis()
        when (event) {
            is DetectionEvent.CandidateStarted ->
                _detectionTrace.value = _detectionTrace.value.copy(
                    lastCandidateAtMs = now,
                    lastCancelReason = null,
                )

            is DetectionEvent.CandidateCancelled ->
                _detectionTrace.value = _detectionTrace.value.copy(lastCancelReason = event.reason)

            // Affiche dans le diagnostic POURQUOI une obscurite n'a pas ete retenue.
            is DetectionEvent.CandidateRejected ->
                _detectionTrace.value = _detectionTrace.value.copy(lastCancelReason = event.reason)

            is DetectionEvent.Confirmed ->
                _detectionTrace.value = _detectionTrace.value.copy(lastConfirmedAtMs = now)

            DetectionEvent.LockRequested -> {
                _detectionTrace.value = _detectionTrace.value.copy(lastLockRequestAtMs = now)
                logger.log(
                    LogCategory.ENGINE,
                    "[PREVIEW] LOCK REQUESTED - diagnostics simulation, no actual lock performed",
                )
            }
        }
    }

    // --- accessibilite -------------------------------------------------------

    fun refreshAccessibility() {
        _accessibilityEnabled.value = AccessibilityStatus.isServiceEnabled(getApplication())
    }

    fun openAccessibilitySettings(context: Context) {
        AccessibilityStatus.openAccessibilitySettings(context)
    }

    fun openAppDetails(context: Context) {
        AccessibilityStatus.openAppDetails(context)
    }

    /** Bouton « Tester le verrouillage » : separe le probleme A (detection) du B (permission). */
    fun testLock() {
        if (!FlipLockAccessibilityService.isConnected) {
            logger.log(LogCategory.ACTION, "test refused: accessibility service not connected")
            _message.value = R.string.msg_accessibility_off
            return
        }
        val success = FlipLockAccessibilityService.lockNow()
        if (!success) {
            _message.value = R.string.msg_lock_refused
        }
    }

    // --- reglages ------------------------------------------------------------

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(enabled = enabled) }
            logger.log(LogCategory.SYSTEM, "FlipLock ${if (enabled) "enabled" else "disabled"}")
        }
    }

    fun updateSettings(transform: (FlipLockSettings) -> FlipLockSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    fun setStrategy(strategy: DetectionStrategy) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(strategy = strategy) }
            logger.log(LogCategory.SYSTEM, "strategy = ${strategy.name}")
        }
    }

    /**
     * Durcit les seuils d'un cran. Reponse directe a « l'ecran se verrouille tout
     * seul » : plutot qu'expliquer quels curseurs bouger, on le fait.
     */
    fun reduceSensitivity() {
        viewModelScope.launch {
            settingsRepo.update { current ->
                current.copy(
                    minimumDropPercent = (current.minimumDropPercent + 10f).coerceAtMost(92f),
                    minimumAbsoluteDropLux = (current.minimumAbsoluteDropLux + 10f).coerceAtMost(60f),
                    confirmationDurationMs = (current.confirmationDurationMs + 200L).coerceAtMost(1500L),
                )
            }
            FlipLockRuntime.undoneLockCount.value = 0
            val s = settingsRepo.current()
            logger.log(
                LogCategory.SYSTEM,
                "sensitivity reduced: drop=${s.minimumDropPercent}% absolute=${s.minimumAbsoluteDropLux} " +
                    "confirm=${s.confirmationDurationMs}ms",
            )
            _message.value = R.string.msg_sensitivity_reduced
        }
    }

    fun resetDefaults() {
        viewModelScope.launch {
            settingsRepo.update { FlipLockSettings.resetTuning(it) }
            logger.log(LogCategory.SYSTEM, "settings restored to defaults")
            _message.value = R.string.msg_defaults_restored
        }
    }

    fun setPersistentService(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(persistentServiceEnabled = enabled) }
        }
    }

    fun setWakeOnOpen(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(wakeOnOpenEnabled = enabled) }
            logger.log(
                LogCategory.SYSTEM,
                "wake on open ${if (enabled) "enabled" else "disabled"}",
            )
        }
    }

    // --- diagnostic ----------------------------------------------------------

    fun probeCoreSensors() {
        viewModelScope.launch {
            _probing.value = true
            _probeState.value = diagnostics.probeCoreSensors()
            _probing.value = false
            val state = _probeState.value
            logger.log(
                LogCategory.LIGHT,
                "probe: declared=${state.light.declared} events=${state.light.eventsReceived} (${state.light.eventCount})",
            )
            logger.log(
                LogCategory.PROXIMITY,
                "probe: declared=${state.proximity.declared} events=${state.proximity.eventsReceived} (${state.proximity.eventCount})",
            )
        }
    }

    fun probeAllSensors() {
        viewModelScope.launch {
            _probing.value = true
            _allProbes.value = diagnostics.probeAllSensors()
            _probing.value = false
            val active = _allProbes.value.count { it.eventsReceived }
            logger.log(
                LogCategory.SYSTEM,
                "full probe: ${active}/${_allProbes.value.size} sensors emitted events",
            )
        }
    }

    fun clearLogs() {
        logger.clear()
        _history.value = emptyList()
        _detectionTrace.value = DetectionTrace()
    }

    fun copyDiagnostic(context: Context) {
        val report = DiagnosticReportBuilder.build(
            device = deviceInfo,
            probes = _probeState.value,
            allSensors = sensorDescriptors,
            allProbes = _allProbes.value,
            settings = settings.value,
            snapshot = FlipLockRuntime.serviceSnapshot.value ?: _snapshot.value,
            lastLight = _lastLight.value,
            lastProximity = _lastProximity.value,
            accessibilityEnabled = _accessibilityEnabled.value,
            accessibilityConnected = FlipLockAccessibilityService.isConnected,
            monitoring = FlipLockRuntime.monitoring.value,
            persistentService = FlipLockRuntime.persistentServiceRunning.value,
            wakeUpCapability = wakeUpCapability,
            logTail = logger.renderTail(),
        )
        val ok = diagnostics.copyToClipboard(context, "FlipLock diagnostic", report)
        _message.value = if (ok) R.string.msg_diagnostic_copied else R.string.msg_copy_failed
    }

    // --- calibration ---------------------------------------------------------

    fun startCalibration() {
        if (calibrationJob?.isActive == true) return
        calibrationJob = viewModelScope.launch {
            _calibration.value = CalibrationUiState(
                step = CalibrationStep.MEASURING_OPEN,
                running = true,
            )
            val open = calibrationManager.measure(CalibrationManager.MEASURE_WINDOW_MS) { fraction, lux ->
                _calibration.value = _calibration.value.copy(progress = fraction, liveLux = lux)
            }
            _calibration.value = _calibration.value.copy(
                step = CalibrationStep.COUNTDOWN,
                openStats = open.stats,
                progress = 0f,
            )
            for (n in 3 downTo 1) {
                _calibration.value = _calibration.value.copy(countdown = n)
                delay(1000L)
            }
            _calibration.value = _calibration.value.copy(
                step = CalibrationStep.MEASURING_CLOSED,
                progress = 0f,
            )
            val closed = calibrationManager.measure(CalibrationManager.MEASURE_WINDOW_MS) { fraction, lux ->
                _calibration.value = _calibration.value.copy(progress = fraction, liveLux = lux)
            }
            val result = calibrationManager.buildResult(open, closed)
            _calibration.value = _calibration.value.copy(
                step = CalibrationStep.RESULT,
                running = false,
                result = result,
            )
        }
    }

    fun resetCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        _calibration.value = CalibrationUiState()
    }

    fun applyCalibration(result: CalibrationResult) {
        viewModelScope.launch {
            settingsRepo.update { current ->
                current.copy(
                    closedLuxThreshold = result.recommendedClosedThreshold,
                    minimumDropPercent = result.recommendedDropPercent,
                    minimumAbsoluteDropLux = result.recommendedAbsoluteDropLux,
                    calibrationDone = true,
                    calibrationOpenLux = result.open.median,
                    calibrationClosedLux = result.closed.median,
                    calibrationSeparationPercent = result.separationPercent,
                    calibrationProximityUsable = result.proximityNearWhenClosed,
                )
            }
            _calibration.value = _calibration.value.copy(applied = true)
            logger.log(LogCategory.CALIBRATION, "recommended settings applied")
            _message.value = R.string.msg_calibration_applied
        }
    }

    fun runDarkTest() {
        viewModelScope.launch {
            _darkTest.value = null
            _probing.value = true
            val ambient = calibrationManager.measure(1800L)
            _darkTest.value = calibrationManager.buildDarkTest(
                ambient = ambient,
                closedThreshold = settings.value.closedLuxThreshold,
                // Verite terrain issue de la calibration, pas « le capteur a emis ».
                proximityReactsToFlap = settings.value.calibrationProximityUsable,
            )
            _probing.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        onBackground()
        calibrationJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_HISTORY = 60
    }
}

/** Suivi des dernieres decisions du moteur d'apercu, pour la section historique. */
data class DetectionTrace(
    val lastCandidateAtMs: Long = 0L,
    val lastConfirmedAtMs: Long = 0L,
    val lastLockRequestAtMs: Long = 0L,
    val lastCancelReason: String? = null,
)
