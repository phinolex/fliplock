package com.fliplock.cover.service

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.fliplock.cover.log.FlipLockLogger
import com.fliplock.cover.log.LogCategory
import com.fliplock.cover.runtime.FlipLockRuntime
import com.fliplock.cover.sensors.SensorRepository
import java.util.Locale

/**
 * Reveil de l'ecran a l'OUVERTURE du rabat.
 *
 * ## Le probleme physique
 *
 * Ecran eteint, le SoC se met en veille. Le capteur `TYPE_LIGHT` de cet appareil
 * est declare `Non-wakeup` : ses evenements sont mis en tampon par le sensor hub
 * et ne reveillent PAS le processeur. Ecouter la lumiere en continu exigerait un
 * WakeLock permanent — exclu (autonomie).
 *
 * ## La solution
 *
 * On s'appuie sur les capteurs declencheurs **wake-up** du telephone
 * (Tilt Detector, Wake Up Motion, Pick Up Gesture, Significant Motion), qui sont
 * cables en materiel et consomment ~0,001 mA :
 *
 * 1. ecran eteint ET rabat detecte ferme  -> on arme les declencheurs ;
 * 2. le telephone bouge (vous le prenez pour ouvrir le rabat)
 *    -> le declencheur reveille le processeur ;
 * 3. FlipLock prend un WakeLock PARTIEL de 1,5 s maximum et ecoute la lumiere ;
 * 4. la luminosite depasse le seuil -> le rabat est ouvert -> on allume l'ecran ;
 * 5. sinon (toujours sombre) -> on relache tout et on se rendort.
 *
 * Aucun WakeLock permanent, aucune boucle, aucun polling.
 *
 * ## Limite assumee
 *
 * Dans une piece totalement noire, ouvrir le rabat ne change pas la luminosite :
 * l'ecran ne s'allumera pas. C'est la meme limite physique que pour la detection
 * de fermeture, et c'est preferable a un reveil intempestif dans une poche.
 */
class WakeOnOpenController(
    private val context: Context,
    private val sensors: SensorRepository,
    private val logger: FlipLockLogger,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)

    private var armed = false
    private var checking = false
    private var luxThreshold = DEFAULT_LUX_THRESHOLD

    private var continuousTriggers: List<Sensor> = emptyList()
    private var oneShotTriggers: List<Sensor> = emptyList()
    private var checkWakeLock: PowerManager.WakeLock? = null

    private var instantWatchActive = false
    private var instantWakeLock: PowerManager.WakeLock? = null

    /** Capteurs « declencheur special » : ils s'ecoutent avec registerListener. */
    private val continuousTriggerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            onTriggerFired(event.sensor?.stringType ?: "mouvement")
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Capteurs « one-shot » : ils se desarment seuls, il faut les re-armer. */
    private val oneShotTriggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            onTriggerFired(event?.sensor?.stringType ?: "one-shot")
        }
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val lux = event.values.firstOrNull() ?: return
            if (lux >= luxThreshold) {
                finishCheck(
                    opened = true,
                    detail = String.format(Locale.US, "%.1f lux >= %.1f", lux, luxThreshold),
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val checkTimeout = Runnable {
        finishCheck(opened = false, detail = "light stayed low")
    }

    /**
     * Ecoute directe de la lumiere pendant la « fenetre instantanee ».
     * Necessite un WakeLock partiel : le capteur est Non-wakeup.
     */
    private val instantLightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val lux = event.values.firstOrNull() ?: return
            if (lux < luxThreshold) return
            logger.log(
                LogCategory.SCREEN,
                String.format(
                    Locale.US,
                    "wake: flap opened during instant watch (%.1f lux >= %.1f)",
                    lux, luxThreshold,
                ),
            )
            stopInstantWatch("flap opened")
            wakeScreen()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val instantWatchTimeout = Runnable { stopInstantWatch("window elapsed") }

    /**
     * Arme la surveillance. A n'appeler QUE lorsque l'ecran vient de s'eteindre
     * alors que le rabat etait detecte ferme.
     *
     * Deux phases complementaires :
     *  - [instantWindowMs] : ecoute directe de la lumiere, reagit meme telephone
     *    en main (les declencheurs de mouvement, eux, exigent un retour au repos) ;
     *  - au-dela : declencheurs wake-up materiels seuls, cout nul.
     */
    fun arm(threshold: Float, instantWindowMs: Long, closedReferenceLux: Float) {
        if (armed) return
        // Seuil adaptatif : en plein soleil, la fuite de lumiere sous le rabat peut
        // depasser le seuil nominal. On se cale toujours nettement au-dessus de la
        // luminosite reellement mesuree au moment du verrouillage.
        luxThreshold = maxOf(threshold, closedReferenceLux * 3f + 10f)
        val triggers = availableTriggers()
        continuousTriggers = triggers.filter { it.reportingMode != Sensor.REPORTING_MODE_ONE_SHOT }
        oneShotTriggers = triggers.filter { it.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT }

        var count = 0
        continuousTriggers.forEach { sensor ->
            if (sensors.registerListener(
                    continuousTriggerListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                )
            ) {
                count++
            }
        }
        count += armOneShotTriggers()

        val instantStarted = startInstantWatch(instantWindowMs)
        armed = count > 0 || instantStarted
        FlipLockRuntime.wakeOnOpenArmed.value = armed
        logger.log(
            LogCategory.SCREEN,
            String.format(
                Locale.US,
                "wake armed: %d trigger(s) + instant watch %s, threshold %.1f lux",
                count,
                if (instantStarted) "${instantWindowMs} ms" else "off",
                luxThreshold,
            ),
        )
    }

    fun disarm() {
        handler.removeCallbacks(checkTimeout)
        stopInstantWatch("desarmement")
        if (checking) {
            sensors.unregisterListener(lightListener)
            checking = false
        }
        releaseCheckWakeLock()
        if (!armed) {
            FlipLockRuntime.wakeOnOpenArmed.value = false
            return
        }
        sensors.unregisterListener(continuousTriggerListener)
        oneShotTriggers.forEach { sensors.cancelTrigger(oneShotTriggerListener, it) }
        armed = false
        FlipLockRuntime.wakeOnOpenArmed.value = false
        logger.log(LogCategory.SCREEN, "wake disarmed")
    }

    // ------------------------------------------------------------------ interne

    /**
     * Phase 1 : le capteur de lumiere est Non-wakeup, il faut donc empecher le
     * SoC de se suspendre pour recevoir ses evenements en temps reel.
     * Le WakeLock est PARTIEL (l'ecran reste eteint) et plafonne par un timeout
     * systeme : il ne peut pas rester bloque.
     */
    private fun startInstantWatch(windowMs: Long): Boolean {
        if (windowMs <= 0L) return false
        if (instantWatchActive) return true
        val registered = sensors.registerListener(
            instantLightListener,
            sensors.lightSensor,
            SensorManager.SENSOR_DELAY_UI,
        )
        if (!registered) {
            logger.log(LogCategory.SCREEN, "wake: instant watch impossible (sensor refused)")
            return false
        }
        instantWatchActive = true
        acquireInstantWakeLock(windowMs + WAKELOCK_MARGIN_MS)
        handler.postDelayed(instantWatchTimeout, windowMs)
        return true
    }

    private fun stopInstantWatch(reason: String) {
        if (!instantWatchActive) return
        instantWatchActive = false
        handler.removeCallbacks(instantWatchTimeout)
        sensors.unregisterListener(instantLightListener)
        releaseInstantWakeLock()
        logger.log(LogCategory.SCREEN, "wake: instant watch ended ($reason)")
    }

    private fun acquireInstantWakeLock(timeoutMs: Long) {
        if (instantWakeLock?.isHeld == true) return
        val manager = powerManager ?: return
        val wakeLock = runCatching {
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, INSTANT_TAG)
        }.getOrNull() ?: return
        runCatching { wakeLock.acquire(timeoutMs) }
        instantWakeLock = wakeLock
    }

    private fun releaseInstantWakeLock() {
        val wakeLock = instantWakeLock ?: return
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        instantWakeLock = null
    }

    private fun availableTriggers(): List<Sensor> = availableTriggers(sensors)

    private fun armOneShotTriggers(): Int {
        var count = 0
        oneShotTriggers.forEach { sensor ->
            if (sensors.requestTrigger(oneShotTriggerListener, sensor)) count++
        }
        return count
    }

    private fun onTriggerFired(source: String) {
        if (!armed || checking) return
        checking = true
        acquireCheckWakeLock()
        val registered = sensors.registerListener(
            lightListener,
            sensors.lightSensor,
            SensorManager.SENSOR_DELAY_GAME,
        )
        if (!registered) {
            finishCheck(opened = false, detail = "light sensor unavailable")
            return
        }
        logger.log(LogCategory.SCREEN, "wake: trigger \"$source\" - checking the light")
        handler.postDelayed(checkTimeout, CHECK_WINDOW_MS)
    }

    private fun finishCheck(opened: Boolean, detail: String) {
        if (!checking) return
        checking = false
        handler.removeCallbacks(checkTimeout)
        sensors.unregisterListener(lightListener)

        if (opened) {
            logger.log(LogCategory.SCREEN, "wake: flap open ($detail)")
            wakeScreen()
        } else {
            logger.log(LogCategory.SCREEN, "wake: flap still closed ($detail)")
        }

        releaseCheckWakeLock()
        // Les capteurs one-shot se sont desarmes en se declenchant : on les re-arme.
        if (armed) armOneShotTriggers()
    }

    private fun wakeScreen() {
        val manager = powerManager
        if (manager == null) {
            logger.log(LogCategory.ACTION, "wake: PowerManager unavailable")
            return
        }
        try {
            @Suppress("DEPRECATION")
            val wakeLock = manager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                SCREEN_WAKE_TAG,
            )
            // acquire(timeout) se relache tout seul : jamais de WakeLock persistant.
            wakeLock.acquire(SCREEN_ON_MS)
            logger.log(LogCategory.ACTION, "wake: screen wake lock acquired (${SCREEN_ON_MS} ms)")
        } catch (t: Throwable) {
            logger.log(LogCategory.ACTION, "wake: wake lock refused (${t.javaClass.simpleName})")
        }

        // Verification + solution de repli officielle si le WakeLock ecran est neutralise.
        handler.postDelayed({
            val interactive = manager.isInteractive
            logger.log(LogCategory.ACTION, "wake: screen interactive=$interactive")
            FlipLockRuntime.lastWakeSucceeded.value = interactive
            if (!interactive) startWakeActivity()
        }, WAKE_VERIFY_DELAY_MS)
    }

    private fun startWakeActivity() {
        val intent = Intent(context, WakeUpActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_NO_HISTORY,
        )
        val started = runCatching { context.startActivity(intent) }.isSuccess
        logger.log(LogCategory.ACTION, "wake: WakeUpActivity fallback launched=$started")
    }

    private fun acquireCheckWakeLock() {
        if (checkWakeLock?.isHeld == true) return
        val manager = powerManager ?: return
        val wakeLock = runCatching {
            manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CHECK_TAG)
        }.getOrNull() ?: return
        runCatching { wakeLock.acquire(CHECK_WAKELOCK_MS) }
        checkWakeLock = wakeLock
    }

    private fun releaseCheckWakeLock() {
        val wakeLock = checkWakeLock ?: return
        runCatching { if (wakeLock.isHeld) wakeLock.release() }
        checkWakeLock = null
    }

    companion object {
        /** Seuil de luminosite par defaut si aucune calibration n'est disponible. */
        const val DEFAULT_LUX_THRESHOLD = 30f

        /** Fenetre d'ecoute de la lumiere apres un declenchement. */
        private const val CHECK_WINDOW_MS = 1500L

        /** Plafond du WakeLock partiel : il ne peut PAS depasser cette duree. */
        private const val CHECK_WAKELOCK_MS = 2500L

        private const val SCREEN_ON_MS = 3000L
        private const val WAKE_VERIFY_DELAY_MS = 500L
        private const val WAKELOCK_MARGIN_MS = 1000L
        private const val SCREEN_WAKE_TAG = "FlipLock:wakeOnOpen"
        private const val CHECK_TAG = "FlipLock:lightCheck"
        private const val INSTANT_TAG = "FlipLock:instantWatch"

        /** Duree par defaut de la veille instantanee apres un verrouillage. */
        const val DEFAULT_INSTANT_WINDOW_MS = 60_000L
        const val MAX_INSTANT_WINDOW_MS = 300_000L

        /**
         * Declencheurs recherches, par ordre de pertinence.
         * Identifiants releves dans le diagnostic reel du Galaxy S26 Ultra —
         * aucun n'est code a l'aveugle, et chacun est optionnel.
         */
        private val TRIGGER_STRING_TYPES = listOf(
            "android.sensor.tilt_detector",
            "com.samsung.sensor.wake_up_motion",
            "android.sensor.pick_up_gesture",
            "android.sensor.significant_motion",
        )

        /** Seuil de reveil derive du seuil de fermeture calibre. */
        fun thresholdFor(closedLuxThreshold: Float): Float =
            maxOf(closedLuxThreshold * 1.5f, 15f)

        /** Declencheurs wake-up reellement presents sur cet appareil. */
        fun availableTriggers(sensors: SensorRepository): List<Sensor> = TRIGGER_STRING_TYPES
            .mapNotNull { sensors.findByStringType(it, requireWakeUp = true) }

        fun describeTriggers(sensors: SensorRepository): String {
            val triggers = availableTriggers(sensors)
            if (triggers.isEmpty()) return "Réveil automatique non disponible sur cet appareil."
            return triggers.joinToString("\n") { "• ${it.name} (${it.vendor})" }
        }
    }
}
