package com.fliplock.cover.calibration

import android.hardware.SensorManager
import com.fliplock.cover.log.FlipLockLogger
import com.fliplock.cover.log.LogCategory
import com.fliplock.cover.sensors.SensorRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.Locale

/** Statistiques d'une serie de mesures de luminosite. */
data class LuxStats(
    val count: Int,
    val mean: Float,
    val median: Float,
    val min: Float,
    val max: Float,
) {
    fun summary(): String = String.format(
        Locale.US,
        "mean %.1f | median %.1f | min %.1f | max %.1f (%d readings)",
        mean, median, min, max, count,
    )

    companion object {
        val EMPTY = LuxStats(0, 0f, 0f, 0f, 0f)

        fun from(values: List<Float>): LuxStats {
            if (values.isEmpty()) return EMPTY
            val sorted = values.sorted()
            val mid = sorted.size / 2
            val median = if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) / 2f
            }
            return LuxStats(
                count = sorted.size,
                mean = sorted.sum() / sorted.size,
                median = median,
                min = sorted.first(),
                max = sorted.last(),
            )
        }
    }
}

enum class CalibrationQuality {
    EXCELLENT,
    GOOD,
    WEAK,
    POOR,
}

/**
 * Conseil issu de la calibration.
 *
 * Comme pour le moteur de detection, cette couche ne produit aucun texte :
 * elle renvoie un cas, et l'interface choisit la chaine traduite.
 */
enum class CalibrationAdvice {
    LIGHT_ENOUGH,
    RETRY_BRIGHTER_WITH_PROXIMITY,
    RETRY_BRIGHTER,
    POOR_WITH_PROXIMITY,
    POOR,
}

/** Resultat complet d'une calibration, avec les valeurs recommandees. */
data class CalibrationResult(
    val open: LuxStats,
    val closed: LuxStats,
    val separationPercent: Float,
    val quality: CalibrationQuality,
    val recommendedClosedThreshold: Float,
    val recommendedDropPercent: Float,
    val recommendedAbsoluteDropLux: Float,
    val proximityAvailable: Boolean,
    val proximityNearWhenClosed: Boolean,
    val advice: CalibrationAdvice,
)

/** Resultat du test « Tester dans l'obscurite ». */
data class DarkTestResult(
    val ambient: LuxStats,
    val closedThreshold: Float,
    val requiredAmbientLux: Float,
    val reliable: Boolean,
    val proximityAvailable: Boolean,
    val proximityProducesEvents: Boolean,
    /**
     * La proximite a-t-elle REELLEMENT reagi au rabat lors de la calibration ?
     *
     * A ne pas confondre avec [proximityProducesEvents], qui est presque toujours
     * vrai : un capteur « on-change » renvoie sa valeur courante des l'abonnement.
     * Recommander le mode hybride sur cette base condamne la detection, puisque
     * ce mode EXIGE un NEAR qui n'arrivera jamais.
     */
    val proximityReactsToFlap: Boolean,
)

/** Mesure brute : luminosite + etats de proximite observes pendant la fenetre. */
data class Measurement(
    val luxValues: List<Float>,
    val proximityNearValues: List<Boolean>,
) {
    val stats: LuxStats get() = LuxStats.from(luxValues)
    val sawNear: Boolean get() = proximityNearValues.any { it }
    val proximityProducedEvents: Boolean get() = proximityNearValues.isNotEmpty()
}

/**
 * Assistant de calibration.
 *
 * Mesure la luminosite coque ouverte puis coque fermee, puis calcule
 * automatiquement des seuils adaptes a CETTE coque et a CET appareil.
 * Rien n'est code en dur : tout est derive des mesures reelles.
 */
class CalibrationManager(
    private val sensors: SensorRepository,
    private val logger: FlipLockLogger,
) {

    /** Ecoute les capteurs pendant [durationMs] et renvoie les valeurs collectees. */
    suspend fun measure(
        durationMs: Long,
        onProgress: (fraction: Float, lastLux: Float?) -> Unit = { _, _ -> },
    ): Measurement = coroutineScope {
        val luxValues = Collections.synchronizedList(ArrayList<Float>())
        val proximityValues = Collections.synchronizedList(ArrayList<Boolean>())

        val lightJob = launch {
            sensors.lightFlow(SensorManager.SENSOR_DELAY_GAME).collect { sample ->
                luxValues.add(sample.lux)
            }
        }
        val proximityJob = launch {
            sensors.proximityFlow().collect { sample ->
                proximityValues.add(sample.near)
            }
        }

        var elapsed = 0L
        while (elapsed < durationMs) {
            delay(PROGRESS_STEP_MS)
            elapsed += PROGRESS_STEP_MS
            val last = synchronized(luxValues) { luxValues.lastOrNull() }
            onProgress((elapsed.toFloat() / durationMs).coerceIn(0f, 1f), last)
        }

        lightJob.cancel()
        proximityJob.cancel()

        val result = Measurement(
            luxValues = synchronized(luxValues) { luxValues.toList() },
            proximityNearValues = synchronized(proximityValues) { proximityValues.toList() },
        )
        logger.log(
            LogCategory.CALIBRATION,
            "window ${durationMs} ms: ${result.stats.summary()}",
        )
        result
    }

    /**
     * Calcule les reglages recommandes.
     *
     * Le seuil vise est place au-dessus du bruit maximal observe coque fermee
     * et tres en dessous de la lumiere observee coque ouverte.
     */
    fun buildResult(open: Measurement, closed: Measurement): CalibrationResult {
        val openStats = open.stats
        val closedStats = closed.stats

        val separation = if (openStats.median > 0f) {
            (((openStats.median - closedStats.median) / openStats.median) * 100f).coerceIn(0f, 100f)
        } else {
            0f
        }

        val quality = when {
            openStats.count == 0 || closedStats.count == 0 -> CalibrationQuality.POOR
            separation >= 95f && openStats.median >= 20f -> CalibrationQuality.EXCELLENT
            separation >= 85f -> CalibrationQuality.GOOD
            separation >= 60f -> CalibrationQuality.WEAK
            else -> CalibrationQuality.POOR
        }

        val threshold = recommendedThreshold(openStats.median, closedStats.max)

        // Marge PROPORTIONNELLE, pas fixe. Une separation mesuree de 87 % donnait
        // un seuil a 79 %, et une fermeture reelle a 77,6 % etait refusee de justesse :
        // la calibration est un echantillon unique, les fermetures reelles varient autour.
        val recommendedDrop = (separation * 0.85f).coerceIn(55f, 92f)
        val recommendedAbsolute =
            ((openStats.median - closedStats.median) * 0.25f).coerceIn(3f, 30f)

        val advice = when (quality) {
            CalibrationQuality.EXCELLENT, CalibrationQuality.GOOD ->
                CalibrationAdvice.LIGHT_ENOUGH

            CalibrationQuality.WEAK -> if (closed.proximityProducedEvents) {
                CalibrationAdvice.RETRY_BRIGHTER_WITH_PROXIMITY
            } else {
                CalibrationAdvice.RETRY_BRIGHTER
            }

            CalibrationQuality.POOR -> if (closed.sawNear) {
                CalibrationAdvice.POOR_WITH_PROXIMITY
            } else {
                CalibrationAdvice.POOR
            }
        }

        logger.log(
            LogCategory.CALIBRATION,
            String.format(
                Locale.US,
                "open=%.1f lux | closed=%.1f lux | separation=%.1f%% | recommended threshold=%.2f lux",
                openStats.median, closedStats.median, separation, threshold,
            ),
        )

        return CalibrationResult(
            open = openStats,
            closed = closedStats,
            separationPercent = separation,
            quality = quality,
            recommendedClosedThreshold = threshold,
            recommendedDropPercent = recommendedDrop,
            recommendedAbsoluteDropLux = recommendedAbsolute,
            proximityAvailable = sensors.proximitySensor != null,
            proximityNearWhenClosed = closed.sawNear,
            advice = advice,
        )
    }

    /**
     * Test « piece sombre » : la lumiere ambiante actuelle permet-elle encore
     * de distinguer rabat ouvert et rabat ferme ?
     */
    fun buildDarkTest(
        ambient: Measurement,
        closedThreshold: Float,
        proximityReactsToFlap: Boolean,
    ): DarkTestResult {
        val stats = ambient.stats
        // Il faut une marge nette entre l'ambiance et le seuil de fermeture.
        val requiredAmbient = maxOf(closedThreshold * 4f, 8f)
        val reliable = stats.median >= requiredAmbient
        val proximityAvailable = sensors.proximitySensor != null
        val proximityWorks = ambient.proximityProducedEvents

        logger.log(
            LogCategory.CALIBRATION,
            String.format(
                Locale.US,
                "dark test: ambient=%.1f lux | reliable=%b | proximity emits=%b | reacts to flap=%b",
                stats.median, reliable, proximityWorks, proximityReactsToFlap,
            ),
        )

        return DarkTestResult(
            ambient = stats,
            closedThreshold = closedThreshold,
            requiredAmbientLux = requiredAmbient,
            reliable = reliable,
            proximityAvailable = proximityAvailable,
            proximityProducesEvents = proximityWorks,
            proximityReactsToFlap = proximityReactsToFlap,
        )
    }

    companion object {
        const val MEASURE_WINDOW_MS = 2000L
        private const val PROGRESS_STEP_MS = 100L

        /**
         * Seuil de fermeture recommande, en lux.
         *
         * Le seuil doit COLLER a ce qui est mesure rabat ferme, et surtout pas se
         * placer a mi-chemin de la lumiere ambiante. L'ancienne formule faisait la
         * moyenne du plancher et du plafond : avec un rabat parfaitement opaque
         * (ferme = 0 lux) dans une piece a 629 lux, elle recommandait 48 lux — un
         * seuil que la moindre ombre franchit, et qui creuse une bande morte enorme
         * entre le seuil de fermeture et le seuil de relachement.
         *
         * On part donc du bruit mesure rabat ferme, borne par une fraction de la
         * lumiere rabat ouvert pour le cas d'un rabat qui laisse passer la lumiere.
         *
         * Fonction pure : testable sans appareil.
         */
        fun recommendedThreshold(openMedianLux: Float, closedMaxLux: Float): Float {
            val noiseFloor = maxOf(closedMaxLux * 2f + 1f, 2f)
            val safetyCeiling = maxOf(openMedianLux * 0.25f, 3f)
            return minOf(noiseFloor, safetyCeiling).coerceIn(0.5f, 60f)
        }
    }
}
