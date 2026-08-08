package com.fliplock.cover.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEventListener
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Acces unique et centralise aux capteurs.
 *
 * Aucun polling : on s'appuie exclusivement sur SensorEventListener / SensorManager.
 * Aucune boucle while(true), aucune lecture periodique.
 */
class SensorRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val sensorManager: SensorManager? =
        appContext.getSystemService(SensorManager::class.java)

    val lightSensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    val proximitySensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    /** Capteur de luminosite « wake-up » : seul moyen propre de reveiller l'ecran. */
    val wakeUpLightSensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT, true)
    val wakeUpProximitySensor: Sensor? get() = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY, true)

    fun allSensors(): List<Sensor> = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()

    fun allDescriptors(): List<SensorDescriptor> = allSensors().map { SensorDescriptor.from(it) }

    /**
     * Recherche par `stringType`.
     *
     * Les constantes Sensor.TYPE_TILT_DETECTOR / TYPE_PICK_UP_GESTURE sont masquees
     * dans le SDK public : on passe donc par l'identifiant textuel, qui est stable
     * et verifiable dans l'ecran de diagnostic.
     */
    fun findByStringType(stringType: String, requireWakeUp: Boolean = false): Sensor? =
        allSensors().firstOrNull {
            it.stringType == stringType && (!requireWakeUp || it.isWakeUpSensor)
        }

    /**
     * Arme un capteur declencheur (REPORTING_MODE_ONE_SHOT).
     * Ces capteurs se desarment automatiquement apres chaque declenchement
     * et refusent `registerListener`.
     */
    fun requestTrigger(listener: TriggerEventListener, sensor: Sensor?): Boolean {
        val manager = sensorManager ?: return false
        val target = sensor ?: return false
        return try {
            manager.requestTriggerSensor(listener, target)
        } catch (t: Throwable) {
            false
        }
    }

    fun cancelTrigger(listener: TriggerEventListener, sensor: Sensor?) {
        val target = sensor ?: return
        runCatching { sensorManager?.cancelTriggerSensor(listener, target) }
    }

    fun registerListener(listener: SensorEventListener, sensor: Sensor?, delayUs: Int): Boolean {
        val manager = sensorManager ?: return false
        val target = sensor ?: return false
        return try {
            manager.registerListener(listener, target, delayUs)
        } catch (t: Throwable) {
            false
        }
    }

    fun unregisterListener(listener: SensorEventListener) {
        try {
            sensorManager?.unregisterListener(listener)
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** Convertit une valeur brute de proximite en booleen NEAR/FAR. */
    fun isNear(value: Float, sensor: Sensor? = proximitySensor): Boolean {
        val maxRange = sensor?.maximumRange ?: DEFAULT_NEAR_CM
        val threshold = minOf(maxRange, DEFAULT_NEAR_CM)
        return value < threshold
    }

    fun lightFlow(delayUs: Int = SensorManager.SENSOR_DELAY_UI): Flow<LightSample> = callbackFlow {
        val sensor = lightSensor
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(
                    LightSample(
                        lux = event.values.firstOrNull() ?: 0f,
                        elapsedMs = SystemClock.elapsedRealtime(),
                        sensorTimestampNs = event.timestamp,
                        wallClockMs = System.currentTimeMillis(),
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        registerListener(listener, sensor, delayUs)
        awaitClose { unregisterListener(listener) }
    }

    fun proximityFlow(delayUs: Int = SensorManager.SENSOR_DELAY_NORMAL): Flow<ProximitySample> = callbackFlow {
        val sensor = proximitySensor
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val raw = event.values.firstOrNull() ?: 0f
                trySend(
                    ProximitySample(
                        distanceCm = raw,
                        near = isNear(raw, sensor),
                        elapsedMs = SystemClock.elapsedRealtime(),
                        sensorTimestampNs = event.timestamp,
                        wallClockMs = System.currentTimeMillis(),
                    )
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        registerListener(listener, sensor, delayUs)
        awaitClose { unregisterListener(listener) }
    }

    /**
     * SONDAGE REEL d'un capteur : on ne se contente jamais de `sensor != null`.
     * On enregistre reellement un listener pendant [durationMs] et on compte
     * les evenements effectivement recus.
     */
    suspend fun probe(sensor: Sensor?, durationMs: Long = DEFAULT_PROBE_MS): SensorProbeResult {
        if (sensor == null) return SensorProbeResult.missing()
        val descriptor = SensorDescriptor.from(sensor)
        val values = ArrayList<Float>()
        val lock = Any()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                synchronized(lock) { values.add(event.values.firstOrNull() ?: 0f) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val accepted = registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        try {
            delay(durationMs)
        } finally {
            unregisterListener(listener)
        }
        val collected = synchronized(lock) { values.toList() }
        return SensorProbeResult(
            descriptor = descriptor,
            declared = true,
            registrationAccepted = accepted,
            eventsReceived = collected.isNotEmpty(),
            eventCount = collected.size,
            firstValue = collected.firstOrNull(),
            lastValue = collected.lastOrNull(),
            probeDurationMs = durationMs,
        )
    }

    /**
     * Sonde TOUS les capteurs annonces par le telephone, simultanement.
     * Sert a reperer un capteur constructeur (Samsung) exploitable si
     * TYPE_LIGHT ne reagissait pas comme attendu.
     */
    suspend fun probeAll(durationMs: Long = DEFAULT_PROBE_MS): List<SensorProbeResult> {
        val sensors = allSensors()
        if (sensors.isEmpty()) return emptyList()
        val lock = Any()
        val counts = HashMap<String, Int>()
        val firsts = HashMap<String, Float>()
        val lasts = HashMap<String, Float>()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val key = keyOf(event.sensor ?: return)
                val value = event.values.firstOrNull() ?: 0f
                synchronized(lock) {
                    counts[key] = (counts[key] ?: 0) + 1
                    if (!firsts.containsKey(key)) firsts[key] = value
                    lasts[key] = value
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val accepted = HashMap<String, Boolean>()
        sensors.forEach { sensor ->
            accepted[keyOf(sensor)] = registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        try {
            delay(durationMs)
        } finally {
            unregisterListener(listener)
        }
        return sensors.map { sensor ->
            val key = keyOf(sensor)
            val count = synchronized(lock) { counts[key] ?: 0 }
            SensorProbeResult(
                descriptor = SensorDescriptor.from(sensor),
                declared = true,
                registrationAccepted = accepted[key] == true,
                eventsReceived = count > 0,
                eventCount = count,
                firstValue = synchronized(lock) { firsts[key] },
                lastValue = synchronized(lock) { lasts[key] },
                probeDurationMs = durationMs,
            )
        }
    }

    private fun keyOf(sensor: Sensor): String = "${sensor.type}|${sensor.name}"

    companion object {
        const val DEFAULT_PROBE_MS = 1800L
        private const val DEFAULT_NEAR_CM = 5.0f

        /**
         * Cadence utilisee par le service : compromis entre reactivite
         * (< 500 ms entre fermeture et verrouillage) et consommation.
         * Le capteur de lumiere est « on-change » : il n'emet que sur variation.
         */
        const val MONITORING_DELAY_US = SensorManager.SENSOR_DELAY_GAME
    }
}
