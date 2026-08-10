package com.fliplock.cover.service

import android.accessibilityservice.AccessibilityService
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.fliplock.cover.AppGraph
import com.fliplock.cover.data.FlipLockSettings
import com.fliplock.cover.detection.CoverDetectionEngine
import com.fliplock.cover.detection.DetectionEvent
import com.fliplock.cover.detection.DetectionStrategy
import com.fliplock.cover.log.LogCategory
import com.fliplock.cover.runtime.FlipLockRuntime
import com.fliplock.cover.runtime.LockAttempt
import com.fliplock.cover.runtime.LockOrigin
import com.fliplock.cover.sensors.LightSample
import com.fliplock.cover.sensors.ProximitySample
import com.fliplock.cover.sensors.SensorRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Service d'accessibilite de FlipLock.
 *
 * ## Ce qu'il fait
 * 1. Il surveille le capteur de luminosite (et, si demande, la proximite)
 *    lorsque FlipLock est active et que l'ecran est allume.
 * 2. Lorsque le moteur confirme une fermeture de rabat, il execute
 *    `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)`.
 *
 * ## Ce qu'il NE fait PAS
 * - il ne recoit aucun evenement d'accessibilite (accessibilityEventTypes = 0) ;
 * - il ne peut pas lire le contenu des fenetres (canRetrieveWindowContent = false) ;
 * - il ne lit pas les notifications, n'enregistre pas les frappes, ne capture pas l'ecran ;
 * - il n'utilise aucun WakeLock et ne maintient jamais le processeur eveille.
 *
 * ## Architecture
 * Comme l'utilisateur a deja autorise ce service, c'est LUI qui porte la
 * surveillance des capteurs : aucun service supplementaire n'est cree.
 * Un service de premier plan optionnel existe (desactive par defaut) pour les
 * cas ou Android/One UI couperait les capteurs en arriere-plan.
 *
 * ## Batterie
 * - aucun polling : uniquement SensorEventListener ;
 * - les listeners sont retires des que l'ecran s'eteint ou que FlipLock est desactive ;
 * - le « ticker » de 50 ms ne tourne QUE pendant une candidature (quelques centaines
 *   de millisecondes), pour confirmer meme si le capteur « on-change » cesse d'emettre.
 */
class FlipLockAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var engine: CoverDetectionEngine
    private var screenMonitor: ScreenStateMonitor? = null
    private var wakeController: WakeOnOpenController? = null

    private var settings: FlipLockSettings = FlipLockSettings.DEFAULT
    private var lightRegistered = false
    private var proximityRegistered = false
    private var monitoring = false
    private var ticking = false
    private var autoLockAtMs = 0L

    private val sensors: SensorRepository get() = AppGraph.sensors

    // ---------------------------------------------------------------- capteurs

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            val lux = event.values.firstOrNull() ?: return
            FlipLockRuntime.lastLight.value = LightSample(
                lux = lux,
                elapsedMs = now,
                sensorTimestampNs = event.timestamp,
                wallClockMs = System.currentTimeMillis(),
            )
            FlipLockRuntime.lightEventCount.value += 1
            FlipLockRuntime.serviceSnapshot.value = engine.onLightReading(lux, now)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            val raw = event.values.firstOrNull() ?: return
            val near = sensors.isNear(raw)
            FlipLockRuntime.lastProximity.value = ProximitySample(
                distanceCm = raw,
                near = near,
                elapsedMs = now,
                sensorTimestampNs = event.timestamp,
                wallClockMs = System.currentTimeMillis(),
            )
            FlipLockRuntime.proximityEventCount.value += 1
            FlipLockRuntime.serviceSnapshot.value = engine.onProximityReading(near, now)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Fait progresser la confirmation meme si le capteur, de type « on-change »,
     * cesse d'emettre une fois stabilise a 0 lux. Actif uniquement pendant une
     * candidature.
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!ticking) return
            val snapshot = engine.tick(SystemClock.elapsedRealtime())
            FlipLockRuntime.serviceSnapshot.value = snapshot
            if (snapshot.candidate) {
                handler.postDelayed(this, TICK_INTERVAL_MS)
            } else {
                ticking = false
            }
        }
    }

    // ------------------------------------------------------------- cycle de vie

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppGraph.init(this)
        instance = this
        FlipLockRuntime.accessibilityConnected.value = true
        AppGraph.logger.log(LogCategory.ACCESSIBILITY, "connected=true")

        engine = CoverDetectionEngine(
            initialConfig = settings.toDetectionConfig(),
            onEvent = ::onDetectionEvent,
        )
        engine.setProximitySupported(sensors.proximitySensor != null)

        screenMonitor = ScreenStateMonitor(this) { interactive -> onScreenStateChanged(interactive) }
        wakeController = WakeOnOpenController(this, sensors, AppGraph.logger)

        scope.launch {
            AppGraph.settings.settings.collectLatest { applySettings(it) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Volontairement vide : le service est configure pour ne recevoir aucun evenement.
    }

    override fun onInterrupt() {
        // Rien a interrompre : FlipLock ne produit aucun retour d'accessibilite.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun teardown() {
        // onUnbind() puis onDestroy() peuvent tous deux etre appeles ; et si Android
        // detruit le service sans jamais l'avoir connecte, `engine` n'existe pas.
        if (::engine.isInitialized) stopMonitoring() else stopTicking()
        wakeController?.disarm()
        screenMonitor?.unregister()
        if (instance === this) instance = null
        FlipLockRuntime.accessibilityConnected.value = false
        FlipLockRuntime.monitoring.value = false
        AppGraph.logger.log(LogCategory.ACCESSIBILITY, "connected=false")
    }

    // --------------------------------------------------------------- reglages

    private fun applySettings(updated: FlipLockSettings) {
        val previous = settings
        settings = updated
        engine.updateConfig(updated.toDetectionConfig())

        if (updated.enabled) {
            startMonitoring()
            if (previous.strategy != updated.strategy) reconcileProximity()
        } else {
            stopMonitoring()
        }

        if (!updated.enabled || !updated.wakeOnOpenEnabled) {
            wakeController?.disarm()
        }

        val serviceRunning = FlipLockRuntime.persistentServiceRunning.value
        if (updated.persistentServiceEnabled && !serviceRunning) {
            FlipLockForegroundService.start(this)
        } else if (!updated.persistentServiceEnabled && serviceRunning) {
            FlipLockForegroundService.stop(this)
        }
    }

    private fun startMonitoring() {
        val monitor = screenMonitor ?: return
        monitor.register()
        val now = SystemClock.elapsedRealtime()
        val interactive = monitor.isInteractive
        FlipLockRuntime.screenInteractive.value = interactive
        engine.setEnabled(true, now)
        engine.setScreenInteractive(interactive, now)
        if (interactive) registerSensors() else unregisterSensors()
        if (!monitoring) {
            monitoring = true
            AppGraph.logger.log(
                LogCategory.SYSTEM,
                "monitoring started (strategy=${settings.strategy.name}, screen interactive=$interactive)",
            )
        }
        FlipLockRuntime.monitoring.value = true
    }

    private fun stopMonitoring() {
        stopTicking()
        unregisterSensors()
        screenMonitor?.unregister()
        engine.setEnabled(false, SystemClock.elapsedRealtime())
        if (monitoring) {
            monitoring = false
            AppGraph.logger.log(LogCategory.SYSTEM, "monitoring stopped")
        }
        FlipLockRuntime.monitoring.value = false
        FlipLockRuntime.serviceSnapshot.value = engine.currentSnapshot()
    }

    private fun registerSensors() {
        if (!lightRegistered) {
            lightRegistered = sensors.registerListener(
                lightListener,
                sensors.lightSensor,
                SensorRepository.MONITORING_DELAY_US,
            )
            AppGraph.logger.log(
                LogCategory.LIGHT,
                "light sensor listener: ${if (lightRegistered) "registered" else "REFUSED"}",
            )
        }
        reconcileProximity()
    }

    private fun reconcileProximity() {
        val wanted = settings.strategy != DetectionStrategy.LIGHT_ONLY &&
            sensors.proximitySensor != null &&
            lightRegistered
        if (wanted && !proximityRegistered) {
            proximityRegistered = sensors.registerListener(
                proximityListener,
                sensors.proximitySensor,
                SensorManager.SENSOR_DELAY_NORMAL,
            )
            AppGraph.logger.log(
                LogCategory.PROXIMITY,
                "proximity sensor listener: ${if (proximityRegistered) "registered" else "REFUSED"}",
            )
        } else if (!wanted && proximityRegistered) {
            sensors.unregisterListener(proximityListener)
            proximityRegistered = false
            AppGraph.logger.log(LogCategory.PROXIMITY, "proximity sensor listener released")
        }
    }

    private fun unregisterSensors() {
        if (lightRegistered) {
            sensors.unregisterListener(lightListener)
            lightRegistered = false
        }
        if (proximityRegistered) {
            sensors.unregisterListener(proximityListener)
            proximityRegistered = false
        }
    }

    // ----------------------------------------------------------------- ecran

    private fun onScreenStateChanged(interactive: Boolean) {
        FlipLockRuntime.screenInteractive.value = interactive
        AppGraph.logger.log(LogCategory.SCREEN, "interactive=$interactive")

        // Ecran rallume juste apres un verrouillage automatique : la coque n'etait
        // pas fermee. C'est un faux positif, et le seul signal qui permette a
        // l'application de le savoir sans que l'utilisateur ait a le diagnostiquer.
        if (interactive && autoLockAtMs > 0L) {
            val delay = SystemClock.elapsedRealtime() - autoLockAtMs
            autoLockAtMs = 0L
            if (delay in 0L..UNDONE_LOCK_WINDOW_MS) {
                val count = FlipLockRuntime.undoneLockCount.value + 1
                FlipLockRuntime.undoneLockCount.value = count
                AppGraph.logger.log(
                    LogCategory.ACTION,
                    "lock undone after $delay ms — likely FALSE POSITIVE (total: $count)",
                )
            }
        }
        engine.setScreenInteractive(interactive, SystemClock.elapsedRealtime())
        if (!settings.enabled) {
            wakeController?.disarm()
            return
        }
        if (interactive) {
            wakeController?.disarm()
            registerSensors()
        } else {
            // Economie de batterie : plus rien a surveiller ecran eteint.
            stopTicking()
            unregisterSensors()
            armWakeOnOpen()
        }
    }

    /**
     * Arme le reveil a l'ouverture, mais UNIQUEMENT si l'ecran vient de s'eteindre
     * alors que le rabat etait detecte ferme.
     *
     * Sans cette condition, une simple mise en veille par inactivite (rabat ouvert,
     * telephone pose sur un bureau eclaire) rallumerait l'ecran au moindre mouvement.
     */
    private fun armWakeOnOpen() {
        val controller = wakeController ?: return
        if (!settings.wakeOnOpenEnabled) return
        val lastLux = FlipLockRuntime.lastLight.value?.lux
        if (lastLux == null || lastLux > settings.closedLuxThreshold) {
            AppGraph.logger.log(
                LogCategory.SCREEN,
                "wake not armed: flap not detected as closed (${lastLux ?: "no reading"} lux)",
            )
            return
        }
        controller.arm(
            threshold = WakeOnOpenController.thresholdFor(settings.closedLuxThreshold),
            instantWindowMs = settings.wakeInstantWindowMs,
            closedReferenceLux = lastLux,
        )
    }

    // -------------------------------------------------------------- detection

    private fun onDetectionEvent(event: DetectionEvent) {
        val logger = AppGraph.logger
        when (event) {
            is DetectionEvent.CandidateStarted -> {
                logger.log(
                    LogCategory.ENGINE,
                    String.format(
                        Locale.US,
                        "lux=%.1f | baseline=%.1f | drop=%.1f%% | candidate=true (%s)",
                        event.lux, event.baseline, event.dropPercent, event.reason,
                    ),
                )
                startTicking()
            }

            is DetectionEvent.CandidateRejected -> logger.log(
                LogCategory.ENGINE,
                String.format(
                    Locale.US,
                    "lux=%.1f | baseline=%.1f | REJECTED: %s",
                    event.lux, event.baseline, event.reason,
                ),
            )

            is DetectionEvent.CandidateCancelled -> {
                logger.log(
                    LogCategory.ENGINE,
                    "candidate=false after ${event.elapsedMs} ms - ${event.reason}",
                )
                stopTicking()
            }

            is DetectionEvent.Confirmed -> {
                logger.log(
                    LogCategory.ENGINE,
                    String.format(
                        Locale.US,
                        "lux=%.1f | duration=%d ms | confirmed=true",
                        event.lux, event.durationMs,
                    ),
                )
                stopTicking()
            }

            DetectionEvent.LockRequested -> performLock(LockOrigin.DETECTION)
        }
    }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    private fun stopTicking() {
        if (!ticking) return
        ticking = false
        handler.removeCallbacks(tickRunnable)
    }

    // ----------------------------------------------------------- verrouillage

    private fun performLock(origin: LockOrigin): Boolean {
        val logger = AppGraph.logger
        logger.log(LogCategory.ACTION, "LOCK_SCREEN requested (origin=${origin.name})")
        val success = try {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } catch (t: Throwable) {
            logger.log(LogCategory.ACTION, "exception ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        logger.log(LogCategory.ACTION, "result=$success")
        if (success && origin == LockOrigin.DETECTION) {
            autoLockAtMs = SystemClock.elapsedRealtime()
        }

        // performGlobalAction ne renvoie qu'un booleen, sans motif. Quand il echoue,
        // on releve ce qui permet de distinguer les deux causes possibles :
        //  - serviceInfo == null : Android nous a delie, la connexion est morte
        //    (typiquement apres une reinstallation) -> desactiver/reactiver l'autorisation ;
        //  - serviceInfo present mais action refusee : restriction systeme.
        if (!success) {
            val info = runCatching { serviceInfo }.getOrNull()
            val stillEnabled = runCatching { AccessibilityStatus.isServiceEnabled(this) }.getOrDefault(false)
            logger.log(
                LogCategory.ACCESSIBILITY,
                "lock refused — serviceInfo=${if (info == null) "null (connexion morte)" else "present"}" +
                    " | enabledInSettings=$stillEnabled" +
                    " | capabilities=${info?.capabilities ?: -1}",
            )
        }
        FlipLockRuntime.lastLockAttempt.value =
            LockAttempt(System.currentTimeMillis(), success, origin)
        return success
    }

    companion object {
        private const val TICK_INTERVAL_MS = 50L

        /** En deca de ce delai, un rallumage signale que la coque n'etait pas fermee. */
        private const val UNDONE_LOCK_WINDOW_MS = 12_000L

        @Volatile
        private var instance: FlipLockAccessibilityService? = null

        /** Vrai si Android a reellement connecte le service. */
        val isConnected: Boolean get() = instance != null

        /**
         * Verrouille immediatement l'ecran.
         * Utilise par le bouton « Tester le verrouillage ».
         * Renvoie false si le service d'accessibilite n'est pas actif.
         */
        fun lockNow(origin: LockOrigin = LockOrigin.TEST_BUTTON): Boolean =
            instance?.performLock(origin) ?: false
    }
}
