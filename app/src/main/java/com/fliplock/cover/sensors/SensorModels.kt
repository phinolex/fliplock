package com.fliplock.cover.sensors

import android.hardware.Sensor
import java.util.Locale

/** Mesure de luminosite ambiante. */
data class LightSample(
    val lux: Float,
    /** Horloge monotone (SystemClock.elapsedRealtime). */
    val elapsedMs: Long,
    /** Horodatage brut fourni par le capteur, en nanosecondes. */
    val sensorTimestampNs: Long,
    /** Horloge murale, pour l'affichage. */
    val wallClockMs: Long,
)

/** Mesure du capteur de proximite. */
data class ProximitySample(
    val distanceCm: Float,
    val near: Boolean,
    val elapsedMs: Long,
    val sensorTimestampNs: Long,
    val wallClockMs: Long,
)

/** Fiche technique d'un capteur, telle qu'annoncee par Android. */
data class SensorDescriptor(
    val name: String,
    val vendor: String,
    val type: Int,
    val stringType: String,
    val version: Int,
    val maximumRange: Float,
    val resolution: Float,
    val power: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val reportingMode: Int,
    val isWakeUpSensor: Boolean,
) {
    val typeLabel: String get() = SensorTypeNames.describe(type, stringType)

    val reportingModeLabel: String
        get() = when (reportingMode) {
            Sensor.REPORTING_MODE_CONTINUOUS -> "continuous"
            Sensor.REPORTING_MODE_ON_CHANGE -> "on-change"
            Sensor.REPORTING_MODE_ONE_SHOT -> "one-shot"
            Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "special-trigger"
            else -> "unknown ($reportingMode)"
        }

    fun toReportLines(indent: String = ""): List<String> = listOf(
        "${indent}name=$name",
        "${indent}vendor=$vendor",
        "${indent}type=$type ($typeLabel)",
        "${indent}stringType=$stringType",
        "${indent}version=$version",
        "${indent}maximumRange=${format(maximumRange)}",
        "${indent}resolution=${format(resolution)}",
        "${indent}power=${format(power)} mA",
        "${indent}minDelay=$minDelayUs us / maxDelay=$maxDelayUs us",
        "${indent}reportingMode=$reportingModeLabel",
        "${indent}wakeUp=$isWakeUpSensor",
    )

    private fun format(value: Float) = String.format(Locale.US, "%.4f", value)

    companion object {
        fun from(sensor: Sensor): SensorDescriptor = SensorDescriptor(
            name = sensor.name,
            vendor = sensor.vendor,
            type = sensor.type,
            stringType = sensor.stringType ?: "",
            version = sensor.version,
            maximumRange = sensor.maximumRange,
            resolution = sensor.resolution,
            power = sensor.power,
            minDelayUs = sensor.minDelay,
            maxDelayUs = sensor.maxDelay,
            reportingMode = sensor.reportingMode,
            isWakeUpSensor = sensor.isWakeUpSensor,
        )
    }
}

/**
 * Resultat d'un SONDAGE reel : on distingue explicitement
 * « capteur annonce par Android » (declared) et
 * « capteur produisant reellement des evenements » (eventsReceived).
 */
data class SensorProbeResult(
    val descriptor: SensorDescriptor?,
    val declared: Boolean,
    val registrationAccepted: Boolean,
    val eventsReceived: Boolean,
    val eventCount: Int,
    val firstValue: Float?,
    val lastValue: Float?,
    val probeDurationMs: Long,
) {
    val verdict: String
        get() = when {
            !declared -> "absent"
            !registrationAccepted -> "advertised but cannot be listened to"
            !eventsReceived -> "advertised but NO events"
            else -> "working ($eventCount event(s))"
        }

    companion object {
        fun missing(): SensorProbeResult = SensorProbeResult(
            descriptor = null,
            declared = false,
            registrationAccepted = false,
            eventsReceived = false,
            eventCount = 0,
            firstValue = null,
            lastValue = null,
            probeDurationMs = 0L,
        )
    }
}

/** Traduction lisible des constantes Sensor.TYPE_*. */
object SensorTypeNames {
    private val NAMES: Map<Int, String> = mapOf(
        Sensor.TYPE_ACCELEROMETER to "accelerometer",
        Sensor.TYPE_MAGNETIC_FIELD to "magnetic field",
        Sensor.TYPE_GYROSCOPE to "gyroscope",
        Sensor.TYPE_LIGHT to "LIGHT",
        Sensor.TYPE_PRESSURE to "pressure",
        Sensor.TYPE_PROXIMITY to "PROXIMITY",
        Sensor.TYPE_GRAVITY to "gravity",
        Sensor.TYPE_LINEAR_ACCELERATION to "linear acceleration",
        Sensor.TYPE_ROTATION_VECTOR to "rotation vector",
        Sensor.TYPE_RELATIVE_HUMIDITY to "humidity",
        Sensor.TYPE_AMBIENT_TEMPERATURE to "ambient temperature",
        Sensor.TYPE_GAME_ROTATION_VECTOR to "game rotation vector",
        Sensor.TYPE_SIGNIFICANT_MOTION to "significant motion",
        Sensor.TYPE_STEP_DETECTOR to "step detector",
        Sensor.TYPE_STEP_COUNTER to "step counter",
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR to "geomagnetic rotation vector",
        Sensor.TYPE_HEART_RATE to "heart rate",
        Sensor.TYPE_POSE_6DOF to "6DOF pose",
        Sensor.TYPE_STATIONARY_DETECT to "stationary detect",
        Sensor.TYPE_MOTION_DETECT to "motion detect",
        Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT to "low latency off-body detect",
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED to "uncalibrated accelerometer",
        Sensor.TYPE_HINGE_ANGLE to "hinge angle",
        Sensor.TYPE_DEVICE_PRIVATE_BASE to "vendor sensor",
    )

    fun describe(type: Int, stringType: String): String {
        NAMES[type]?.let { return it }
        if (type >= Sensor.TYPE_DEVICE_PRIVATE_BASE) return "vendor sensor"
        return stringType.ifBlank { "type $type" }
    }

    /**
     * Mots-cles utiles pour reperer un capteur constructeur susceptible de
     * detecter une coque (cf. section 33 du cahier des charges).
     */
    private val INTERESTING = listOf(
        "light", "lux", "als", "front", "prox", "cover", "flip", "lid",
        "grip", "context", "hall", "pocket", "screen",
    )

    fun isPotentiallyRelevant(descriptor: SensorDescriptor): Boolean {
        if (descriptor.type == Sensor.TYPE_LIGHT || descriptor.type == Sensor.TYPE_PROXIMITY) return true
        val haystack = (descriptor.name + " " + descriptor.stringType).lowercase(Locale.US)
        return INTERESTING.any { haystack.contains(it) }
    }
}
