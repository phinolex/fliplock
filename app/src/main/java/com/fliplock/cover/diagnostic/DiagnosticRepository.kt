package com.fliplock.cover.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import com.fliplock.cover.sensors.SensorProbeResult
import com.fliplock.cover.sensors.SensorRepository

/** Informations techniques de l'appareil (aucune donnee personnelle). */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
) {
    companion object {
        fun current(): DeviceInfo = DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}

/** Etat des sondages de capteurs. */
data class SensorProbeState(
    val light: SensorProbeResult = SensorProbeResult.missing(),
    val proximity: SensorProbeResult = SensorProbeResult.missing(),
    val all: List<SensorProbeResult> = emptyList(),
    val probedAtWallClockMs: Long = 0L,
)

/**
 * Regroupe les informations de diagnostic et gere les sondages reels de capteurs.
 * Tout reste local : rien n'est envoye, rien n'est ecrit sur disque.
 */
class DiagnosticRepository(private val sensors: SensorRepository) {

    val deviceInfo: DeviceInfo = DeviceInfo.current()

    suspend fun probeCoreSensors(durationMs: Long = SensorRepository.DEFAULT_PROBE_MS): SensorProbeState {
        val light = sensors.probe(sensors.lightSensor, durationMs)
        val proximity = sensors.probe(sensors.proximitySensor, durationMs)
        return SensorProbeState(
            light = light,
            proximity = proximity,
            probedAtWallClockMs = System.currentTimeMillis(),
        )
    }

    suspend fun probeAllSensors(durationMs: Long = 3000L): List<SensorProbeResult> =
        sensors.probeAll(durationMs)

    /** Le reveil automatique n'est possible proprement qu'avec un capteur « wake-up ». */
    fun wakeUpCapability(): String {
        val light = sensors.wakeUpLightSensor
        val proximity = sensors.wakeUpProximitySensor
        return when {
            light != null -> light.name
            proximity != null -> "(none, but wake-up proximity: ${proximity.name})"
            else -> "none"
        }
    }

    fun hasWakeUpSensor(): Boolean =
        sensors.wakeUpLightSensor != null || sensors.wakeUpProximitySensor != null

    fun copyToClipboard(context: Context, label: String, text: String): Boolean {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        return runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        }.getOrDefault(false)
    }
}
