package com.fliplock.cover.data

import com.fliplock.cover.detection.DetectionConfig
import com.fliplock.cover.detection.DetectionStrategy

/**
 * Reglages persistants de FlipLock.
 * Stockes localement via DataStore Preferences — jamais synchronises.
 */
data class FlipLockSettings(
    val enabled: Boolean = false,
    val closedLuxThreshold: Float = DetectionConfig.DEFAULT.closedLuxThreshold,
    val minimumDropPercent: Float = DetectionConfig.DEFAULT.minimumDropPercent,
    val minimumAbsoluteDropLux: Float = DetectionConfig.DEFAULT.minimumAbsoluteDropLux,
    val confirmationDurationMs: Long = DetectionConfig.DEFAULT.confirmationDurationMs,
    val cooldownMs: Long = DetectionConfig.DEFAULT.cooldownMs,
    val minBaselineLux: Float = DetectionConfig.DEFAULT.minBaselineLux,
    val strategy: DetectionStrategy = DetectionStrategy.AUTO,

    /** Service de premier plan optionnel (voir « Reglages avances »). */
    val persistentServiceEnabled: Boolean = false,

    /** Reveil de l'ecran a l'ouverture du rabat (optionnel, desactive par defaut). */
    val wakeOnOpenEnabled: Boolean = false,

    /**
     * Duree, apres un verrouillage, pendant laquelle FlipLock ecoute directement
     * la lumiere (WakeLock partiel borne). 0 = uniquement les declencheurs de mouvement.
     */
    val wakeInstantWindowMs: Long = 60_000L,

    val calibrationDone: Boolean = false,
    val calibrationOpenLux: Float = 0f,
    val calibrationClosedLux: Float = 0f,
    val calibrationSeparationPercent: Float = 0f,
    val calibrationProximityUsable: Boolean = false,
) {
    fun toDetectionConfig(): DetectionConfig = DetectionConfig(
        closedLuxThreshold = closedLuxThreshold,
        minimumDropPercent = minimumDropPercent,
        minimumAbsoluteDropLux = minimumAbsoluteDropLux,
        confirmationDurationMs = confirmationDurationMs,
        cooldownMs = cooldownMs,
        strategy = strategy,
        minBaselineLux = minBaselineLux,
    )

    companion object {
        val DEFAULT = FlipLockSettings()

        /** Valeurs d'usine des parametres de detection (le reste est conserve). */
        fun resetTuning(current: FlipLockSettings): FlipLockSettings = current.copy(
            closedLuxThreshold = DEFAULT.closedLuxThreshold,
            minimumDropPercent = DEFAULT.minimumDropPercent,
            minimumAbsoluteDropLux = DEFAULT.minimumAbsoluteDropLux,
            confirmationDurationMs = DEFAULT.confirmationDurationMs,
            cooldownMs = DEFAULT.cooldownMs,
            minBaselineLux = DEFAULT.minBaselineLux,
            strategy = DEFAULT.strategy,
        )
    }
}
