package com.fliplock.cover.diagnostic

import com.fliplock.cover.data.FlipLockSettings
import com.fliplock.cover.detection.EngineSnapshot
import com.fliplock.cover.sensors.LightSample
import com.fliplock.cover.sensors.ProximitySample
import com.fliplock.cover.sensors.SensorDescriptor
import com.fliplock.cover.sensors.SensorProbeResult
import com.fliplock.cover.sensors.SensorTypeNames
import java.util.Locale

/**
 * Construit le rapport texte copiable dans le presse-papiers.
 *
 * Le rapport ne contient QUE des informations techniques :
 * modele, version d'Android, fiches capteurs, valeurs mesurees et reglages.
 * Aucun identifiant, aucun numero de serie, aucune donnee personnelle.
 */
object DiagnosticReportBuilder {

    fun build(
        device: DeviceInfo,
        probes: SensorProbeState,
        allSensors: List<SensorDescriptor>,
        allProbes: List<SensorProbeResult>,
        settings: FlipLockSettings,
        snapshot: EngineSnapshot?,
        lastLight: LightSample?,
        lastProximity: ProximitySample?,
        accessibilityEnabled: Boolean,
        accessibilityConnected: Boolean,
        monitoring: Boolean,
        persistentService: Boolean,
        wakeUpCapability: String,
        logTail: String,
    ): String = buildString {
        appendLine("=== FLIPLOCK — RAPPORT DE DIAGNOSTIC ===")
        appendLine()

        appendLine("DEVICE")
        appendLine("  manufacturer=${device.manufacturer}")
        appendLine("  model=${device.model}")
        appendLine("  device=${device.device}")
        appendLine()

        appendLine("ANDROID")
        appendLine("  release=${device.androidRelease}")
        appendLine("  API ${device.sdkInt}")
        appendLine()

        appendLine("LIGHT SENSOR")
        appendProbe(probes.light)
        lastLight?.let {
            appendLine("  currentLux=${fmt(it.lux)}")
            appendLine("  sensorTimestampNs=${it.sensorTimestampNs}")
        }
        appendLine()

        appendLine("PROXIMITY")
        appendProbe(probes.proximity)
        lastProximity?.let {
            appendLine("  currentValue=${fmt(it.distanceCm)}")
            appendLine("  near=${it.near}")
        }
        appendLine()

        appendLine("WAKE-UP")
        appendLine("  $wakeUpCapability")
        appendLine()

        appendLine("AVAILABLE SENSORS (${allSensors.size})")
        allSensors.forEachIndexed { index, descriptor ->
            val probe = allProbes.firstOrNull {
                it.descriptor?.name == descriptor.name && it.descriptor.type == descriptor.type
            }
            val relevant = if (SensorTypeNames.isPotentiallyRelevant(descriptor)) " <-- pertinent" else ""
            appendLine("  ${index + 1}. ${descriptor.name}$relevant")
            appendLine("      type=${descriptor.type} (${descriptor.typeLabel}) stringType=${descriptor.stringType}")
            appendLine("      vendor=${descriptor.vendor} version=${descriptor.version}")
            appendLine(
                "      range=${fmt(descriptor.maximumRange)} resolution=${fmt(descriptor.resolution)} power=${fmt(descriptor.power)} mA"
            )
            appendLine("      reportingMode=${descriptor.reportingModeLabel} wakeUp=${descriptor.isWakeUpSensor}")
            if (probe != null) {
                appendLine(
                    "      probe: registration=${probe.registrationAccepted} " +
                        "events=${probe.eventsReceived} count=${probe.eventCount} " +
                        "last=${probe.lastValue?.let { fmt(it) } ?: "-"}"
                )
                if (descriptor.reportingMode == android.hardware.Sensor.REPORTING_MODE_ONE_SHOT) {
                    appendLine("      note: capteur one-shot — necessite requestTriggerSensor(), non sondable ici")
                } else if (descriptor.reportingMode == android.hardware.Sensor.REPORTING_MODE_SPECIAL_TRIGGER) {
                    appendLine("      note: capteur declencheur — n'emet que lorsque l'evenement physique se produit")
                }
            }
        }
        appendLine()

        appendLine("FLIPLOCK")
        appendLine("  enabled=${settings.enabled}")
        appendLine("  strategy=${settings.strategy.name}")
        appendLine("  closedLuxThreshold=${fmt(settings.closedLuxThreshold)}")
        appendLine("  minimumDropPercent=${fmt(settings.minimumDropPercent)}")
        appendLine("  minimumAbsoluteDropLux=${fmt(settings.minimumAbsoluteDropLux)}")
        appendLine("  confirmationDurationMs=${settings.confirmationDurationMs}")
        appendLine("  cooldownMs=${settings.cooldownMs}")
        appendLine("  minBaselineLux=${fmt(settings.minBaselineLux)}")
        appendLine("  persistentService=$persistentService")
        appendLine("  wakeOnOpen=${settings.wakeOnOpenEnabled}")
        appendLine("  wakeInstantWindowMs=${settings.wakeInstantWindowMs}")
        appendLine("  monitoring=$monitoring")
        appendLine("  accessibilityEnabled=$accessibilityEnabled")
        appendLine("  accessibilityConnected=$accessibilityConnected")
        appendLine()

        appendLine("CALIBRATION")
        appendLine("  done=${settings.calibrationDone}")
        appendLine("  openLux=${fmt(settings.calibrationOpenLux)}")
        appendLine("  closedLux=${fmt(settings.calibrationClosedLux)}")
        appendLine("  separationPercent=${fmt(settings.calibrationSeparationPercent)}")
        appendLine("  proximityUsable=${settings.calibrationProximityUsable}")
        appendLine()

        appendLine("ENGINE")
        if (snapshot == null) {
            appendLine("  (aucune mesure)")
        } else {
            appendLine("  state=${snapshot.state}")
            appendLine("  lux=${snapshot.lux?.let { fmt(it) } ?: "-"}")
            appendLine("  baseline=${fmt(snapshot.baselineLux)} (${snapshot.baselineSampleCount} echantillons)")
            appendLine("  drop=${fmt(snapshot.dropPercent)} % / ${fmt(snapshot.absoluteDropLux)} lux")
            appendLine("  candidate=${snapshot.candidate}")
            appendLine("  cooldownRemainingMs=${snapshot.cooldownRemainingMs}")
            appendLine("  proximityNear=${snapshot.proximityNear}")
            appendLine("  reason=${snapshot.reason}")
        }
        appendLine()

        appendLine("JOURNAL (dernieres lignes)")
        if (logTail.isBlank()) {
            appendLine("  (vide)")
        } else {
            logTail.lineSequence().forEach { appendLine("  $it") }
        }
    }

    private fun StringBuilder.appendProbe(probe: SensorProbeResult) {
        appendLine("  available=${probe.declared}")
        val descriptor = probe.descriptor
        if (descriptor == null) {
            appendLine("  (capteur absent de cet appareil)")
            return
        }
        appendLine("  name=${descriptor.name}")
        appendLine("  vendor=${descriptor.vendor}")
        appendLine("  type=${descriptor.type} stringType=${descriptor.stringType}")
        appendLine("  version=${descriptor.version}")
        appendLine("  maximumRange=${fmt(descriptor.maximumRange)}")
        appendLine("  resolution=${fmt(descriptor.resolution)}")
        appendLine("  power=${fmt(descriptor.power)} mA")
        appendLine("  reportingMode=${descriptor.reportingModeLabel}")
        appendLine("  wakeUp=${descriptor.isWakeUpSensor}")
        appendLine("  events=${probe.eventsReceived} (count=${probe.eventCount} en ${probe.probeDurationMs} ms)")
        appendLine("  verdict=${probe.verdict}")
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.3f", value)
}
