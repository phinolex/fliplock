package com.fliplock.cover.detection

import java.util.Locale

/**
 * Detecteur de FERMETURE DE RABAT.
 *
 * Classe volontairement 100 % Kotlin/JVM (aucun import Android) afin d'etre
 * testable avec des valeurs artificielles — voir CoverDetectionEngineTest.
 *
 * ## Principe
 *
 * On ne verrouille jamais sur un simple `lux < X`. On cherche un EVENEMENT :
 * une chute rapide, profonde et durable par rapport a la lumiere ambiante
 * observee juste avant.
 *
 * Une « baseline » (mediane glissante sur [DetectionConfig.baselineWindowMs])
 * est maintenue uniquement a partir des mesures CLAIRES, c'est-a-dire au-dessus
 * du seuil de relachement. Elle represente la lumiere « coque ouverte ».
 *
 * Une candidature demarre lorsque TOUTES ces conditions sont vraies :
 *  1. FlipLock est active ;
 *  2. l'ecran est interactif ;
 *  3. aucun cooldown en cours ;
 *  4. la mesure courante est <= closedLuxThreshold ;
 *  5. VITESSE : la derniere mesure claire date de moins de fallWindowMs ;
 *  6. la baseline est exploitable (>= minBaselineLux, au moins 2 echantillons) ;
 *  7. chute absolue >= minimumAbsoluteDropLux ;
 *  8. chute relative >= minimumDropPercent.
 *
 * Elle est CONFIRMEE si la condition tient sans interruption pendant
 * confirmationDurationMs. Toute remontee au-dessus du seuil de relachement
 * annule immediatement (main passee devant, ombre, artefact du capteur).
 *
 * Quand la piece est deja sombre (baseline inexploitable), la lumiere seule ne
 * peut pas trancher : le moteur refuse de verrouiller, sauf si la proximite
 * vient de passer a NEAR et que la strategie l'autorise.
 *
 * ## Temps
 *
 * Toutes les dates sont fournies par l'appelant en millisecondes monotones
 * (SystemClock.elapsedRealtime en production). Le moteur ne lit jamais l'horloge.
 * [tick] permet de faire progresser la confirmation meme si le capteur — qui est
 * de type « on-change » — cesse d'emettre une fois arrive a 0 lux.
 */
class CoverDetectionEngine(
    initialConfig: DetectionConfig = DetectionConfig.DEFAULT,
    private val onEvent: (DetectionEvent) -> Unit = {},
) {

    private data class LuxSample(val lux: Float, val timeMs: Long)

    var config: DetectionConfig = initialConfig
        private set

    private val brightSamples = ArrayDeque<LuxSample>()

    private var enabled = false
    private var screenInteractive = true

    private var lastLux: Float? = null
    private var lastLuxTimeMs = 0L
    private var lastBrightTimeMs = NEVER
    private var lastBrightLux = 0f

    /** Derniere fois que la lumiere etait encore au niveau du plateau. */
    private var lastPlateauTimeMs = NEVER

    private var proximitySupported = false
    private var proximityNear: Boolean? = null
    private var proximityNearSinceMs = NEVER

    private var state = EngineState.DISABLED
    private var candidateStartMs = 0L
    private var frozenBaseline = 0f
    private var frozenDropPercent = 0f
    private var frozenAbsoluteDrop = 0f
    private var candidateMinLux = 0f
    private var candidateMaxLux = 0f
    private var lastLockTimeMs = NEVER
    private var lastConfirmedAtMs = 0L

    private var snapshot = EngineSnapshot.EMPTY
    private var rejectionReported = false

    // ---------------------------------------------------------------- entrees

    fun updateConfig(newConfig: DetectionConfig) {
        config = newConfig
    }

    fun setEnabled(value: Boolean, nowMs: Long) {
        if (enabled == value) return
        enabled = value
        if (!value) {
            clearHistory()
            state = EngineState.DISABLED
        }
        evaluate(nowMs)
    }

    fun isEnabled(): Boolean = enabled

    fun setScreenInteractive(value: Boolean, nowMs: Long) {
        if (screenInteractive == value) return
        screenInteractive = value
        if (!value) clearHistory()
        evaluate(nowMs)
    }

    fun setProximitySupported(value: Boolean) {
        proximitySupported = value
    }

    /** Nouvelle mesure du capteur de proximite. */
    fun onProximityReading(near: Boolean, nowMs: Long): EngineSnapshot {
        proximitySupported = true
        if (proximityNear != near) {
            proximityNear = near
            proximityNearSinceMs = nowMs
        }
        return evaluate(nowMs)
    }

    /** Nouvelle mesure du capteur de luminosite, en lux. */
    fun onLightReading(lux: Float, nowMs: Long): EngineSnapshot {
        lastLux = lux
        lastLuxTimeMs = nowMs

        // La baseline ne se nourrit QUE de mesures claires, et jamais pendant
        // une candidature (sinon la chute se « lisserait » elle-meme).
        if (state != EngineState.CANDIDATE && lux > config.releaseLuxThreshold) {
            brightSamples.addLast(LuxSample(lux, nowMs))
            lastBrightTimeMs = nowMs
            lastBrightLux = lux
            trimBrightSamples(nowMs)
            // La lumiere est revenue : le prochain episode sombre pourra etre signale.
            rejectionReported = false
        }
        // Instant ou la lumiere etait encore AU NIVEAU DU PLATEAU. C'est de la que
        // se mesure la vitesse de chute : un rabat traverse les valeurs
        // intermediaires en une fraction de seconde, une piece qui s'assombrit y
        // sejourne plusieurs secondes.
        val baseline = baselineLux()
        if (baseline > 0f && lux >= baseline * config.baselinePlateauRatio) {
            lastPlateauTimeMs = nowMs
        }
        return evaluate(nowMs)
    }

    /** Fait progresser la machine a etats sans nouvelle mesure. */
    fun tick(nowMs: Long): EngineSnapshot = evaluate(nowMs)

    fun currentSnapshot(): EngineSnapshot = snapshot

    fun reset() {
        clearHistory()
        state = if (enabled) EngineState.IDLE else EngineState.DISABLED
        lastLockTimeMs = NEVER
        lastLux = null
    }

    // ------------------------------------------------------------- evaluation

    private fun evaluate(nowMs: Long): EngineSnapshot {
        if (!enabled) {
            cancelCandidate("FlipLock disabled", nowMs)
            state = EngineState.DISABLED
            return publish(nowMs, "FlipLock disabled")
        }
        if (!screenInteractive) {
            cancelCandidate("screen already off", nowMs)
            state = EngineState.SCREEN_OFF
            return publish(nowMs, "screen not interactive - nothing to lock")
        }
        val cooldownRemaining = cooldownRemaining(nowMs)
        if (cooldownRemaining > 0L) {
            cancelCandidate("cooldown in progress", nowMs)
            state = EngineState.COOLDOWN
            return publish(nowMs, "cooldown ${cooldownRemaining} ms")
        }
        if (state != EngineState.CANDIDATE) state = EngineState.IDLE

        val lux = lastLux ?: return publish(nowMs, "no light reading received yet")

        return if (state == EngineState.CANDIDATE) {
            evaluateCandidate(lux, nowMs)
        } else {
            evaluateIdle(lux, nowMs)
        }
    }

    private fun evaluateIdle(lux: Float, nowMs: Long): EngineSnapshot {
        val cfg = config
        if (lux > cfg.closedLuxThreshold) {
            return publish(nowMs, "case open (${fmt(lux)} lux)")
        }

        val baseline = baselineLux()
        val absoluteDrop = (baseline - lux).coerceAtLeast(0f)
        val dropPercent = if (baseline > 0f) (absoluteDrop / baseline) * 100f else 0f
        val fallDelay = lastLuxTimeMs - lastPlateauTimeMs
        val fallWindow = effectiveFallWindowMs()
        val fastFall = fallDelay in 0L..fallWindow
        val baselineUsable = brightSamples.size >= 2 && baseline >= cfg.minBaselineLux

        val lightStrong = fastFall &&
            baselineUsable &&
            absoluteDrop >= cfg.minimumAbsoluteDropLux &&
            dropPercent >= cfg.minimumDropPercent

        // Piece deja sombre : la lumiere seule ne peut pas trancher.
        val ambientTooDark = !lightStrong && !baselineUsable
        val proximityJustClosed = proximityNear == true &&
            (nowMs - proximityNearSinceMs) in 0L..cfg.proximityConfirmWindowMs

        val accept: Boolean
        val reason: String
        when (cfg.strategy) {
            DetectionStrategy.LIGHT_ONLY -> {
                accept = lightStrong
                reason = if (accept) {
                    "fast drop ${fmt(baseline)} -> ${fmt(lux)} lux"
                } else {
                    rejectionReason(lightStrong, fastFall, baselineUsable, baseline, absoluteDrop, dropPercent)
                }
            }

            DetectionStrategy.LIGHT_PLUS_PROXIMITY -> {
                val lightPart = lightStrong || ambientTooDark
                accept = lightPart && proximityJustClosed
                reason = when {
                    accept -> "drop + proximity NEAR"
                    !lightPart -> rejectionReason(lightStrong, fastFall, baselineUsable, baseline, absoluteDrop, dropPercent)
                    else -> "proximity not NEAR (required in this mode)"
                }
            }

            DetectionStrategy.AUTO -> {
                val rescue = ambientTooDark && proximitySupported && proximityJustClosed
                accept = lightStrong || rescue
                reason = when {
                    lightStrong -> "fast drop ${fmt(baseline)} -> ${fmt(lux)} lux"
                    rescue -> "dark room, close confirmed by proximity"
                    ambientTooDark -> "room already dark: light inconclusive, no usable proximity"
                    else -> rejectionReason(lightStrong, fastFall, baselineUsable, baseline, absoluteDrop, dropPercent)
                }
            }
        }

        if (!accept) {
            // Un echec de detection doit laisser une trace, sinon il est indiagnosticable.
            // Une seule ligne par episode sombre : le rearmement se fait quand la
            // lumiere repasse au-dessus du seuil de relachement.
            if (!rejectionReported) {
                rejectionReported = true
                onEvent(DetectionEvent.CandidateRejected(lux, baseline, reason))
            }
            return publish(nowMs, reason)
        }

        state = EngineState.CANDIDATE
        candidateStartMs = minOf(lastLuxTimeMs, nowMs)
        candidateMinLux = lux
        candidateMaxLux = lux
        frozenBaseline = baseline
        frozenDropPercent = dropPercent
        frozenAbsoluteDrop = absoluteDrop
        onEvent(DetectionEvent.CandidateStarted(lux, baseline, dropPercent, reason))
        return publish(nowMs, "candidate: $reason")
    }

    private fun evaluateCandidate(lux: Float, nowMs: Long): EngineSnapshot {
        val cfg = config
        candidateMinLux = minOf(candidateMinLux, lux)
        candidateMaxLux = maxOf(candidateMaxLux, lux)
        if (lux > cfg.releaseLuxThreshold) {
            cancelCandidate("light came back (${fmt(lux)} lux) - artefact", nowMs)
            return publish(nowMs, "candidate cancelled: light came back")
        }
        if (cfg.strategy == DetectionStrategy.LIGHT_PLUS_PROXIMITY && proximityNear == false) {
            cancelCandidate("proximity switched to FAR", nowMs)
            return publish(nowMs, "candidate cancelled: proximity FAR")
        }
        val elapsed = nowMs - candidateStartMs
        val required = requiredConfirmationMs()
        if (elapsed >= required) {
            // Un rabat ferme donne une droite ; un ecran qui joue une video, non.
            val spread = candidateMaxLux - candidateMinLux
            val allowed = maxOf(cfg.maxCandidateSpreadLux, cfg.closedLuxThreshold * 0.3f)
            if (spread > allowed) {
                cancelCandidate(
                    "light unstable during confirmation (${fmt(spread)} lux spread > ${fmt(allowed)}) " +
                        "- screen content, not a closed flap",
                    nowMs,
                )
                return publish(nowMs, "candidate cancelled: light too unstable")
            }
            confirm(lux, elapsed, nowMs)
            return publish(nowMs, "close confirmed after $elapsed ms")
        }
        return publish(nowMs, "confirming ($elapsed/$required ms)")
    }

    private fun confirm(lux: Float, elapsedMs: Long, nowMs: Long) {
        state = EngineState.COOLDOWN
        lastLockTimeMs = nowMs
        lastConfirmedAtMs = nowMs
        onEvent(DetectionEvent.Confirmed(elapsedMs, lux, frozenBaseline))
        onEvent(DetectionEvent.LockRequested)
        // La baseline d'avant fermeture n'a plus de sens.
        clearHistory()
    }

    private fun cancelCandidate(reason: String, nowMs: Long) {
        if (state != EngineState.CANDIDATE) return
        val elapsed = nowMs - candidateStartMs
        state = EngineState.IDLE
        onEvent(DetectionEvent.CandidateCancelled(reason, elapsed))
    }

    // ---------------------------------------------------------------- helpers

    private fun rejectionReason(
        lightStrong: Boolean,
        fastFall: Boolean,
        baselineUsable: Boolean,
        baseline: Float,
        absoluteDrop: Float,
        dropPercent: Float,
    ): String {
        if (lightStrong) return "conditions met"
        if (!baselineUsable) {
            return "baseline unusable (${fmt(baseline)} lux, ${brightSamples.size} sample(s))"
        }
        if (!fastFall) {
            val delay = lastLuxTimeMs - lastPlateauTimeMs
            val window = effectiveFallWindowMs()
            val cadence = medianBrightIntervalMs()
            return "drop too gradual: light left the ${fmt(baseline)} lux plateau ${delay} ms ago > ${window} ms window" +
                (cadence?.let { " (sensor cadence ~${it} ms)" } ?: "")
        }
        if (absoluteDrop < config.minimumAbsoluteDropLux) {
            return "absolute drop ${fmt(absoluteDrop)} lux < ${fmt(config.minimumAbsoluteDropLux)} lux"
        }
        if (dropPercent < config.minimumDropPercent) {
            return "relative drop ${fmt(dropPercent)} % < ${fmt(config.minimumDropPercent)} %"
        }
        return "conditions not met"
    }

    private fun trimBrightSamples(nowMs: Long) {
        while (brightSamples.isNotEmpty() &&
            nowMs - brightSamples.first().timeMs > config.baselineWindowMs
        ) {
            brightSamples.removeFirst()
        }
        while (brightSamples.size > MAX_BRIGHT_SAMPLES) {
            brightSamples.removeFirst()
        }
    }

    /**
     * Fenetre de chute effective, adaptee a la cadence REELLE du capteur.
     *
     * Le capteur de luminosite est « on-change », et Android reduit fortement sa
     * frequence quand une autre application est au premier plan : on peut passer
     * de 20 mesures/s a une mesure toutes les 1,5 s. Avec une fenetre fixe de
     * 900 ms, plus AUCUNE fermeture ne serait detectable dans ces conditions —
     * c'est le bug « je ferme le rabat depuis une autre appli et rien ne se passe ».
     *
     * On exige donc que la chute tienne en quelques MESURES plutot qu'en un nombre
     * fixe de millisecondes. La protection anti-faux-positif reste entiere : une
     * piece qui s'assombrit progressivement sejourne plusieurs mesures dans la zone
     * intermediaire (entre le seuil de fermeture et le seuil de relachement), ce qui
     * eloigne d'autant la derniere mesure claire.
     */
    private fun effectiveFallWindowMs(): Long {
        val cadence = medianBrightIntervalMs() ?: return config.fallWindowMs
        val scaled = (cadence * config.fallWindowSampleFactor).toLong()
        return scaled.coerceIn(config.fallWindowMs, config.maxFallWindowMs)
    }

    /** Ecart median entre deux mesures claires consecutives, ou null si trop peu de donnees. */
    private fun medianBrightIntervalMs(): Long? {
        if (brightSamples.size < 3) return null
        val samples = brightSamples.toList()
        val gaps = ArrayList<Long>(samples.size - 1)
        for (i in 1 until samples.size) {
            val gap = samples[i].timeMs - samples[i - 1].timeMs
            if (gap > 0L) gaps.add(gap)
        }
        if (gaps.isEmpty()) return null
        gaps.sort()
        val mid = gaps.size / 2
        return if (gaps.size % 2 == 1) gaps[mid] else (gaps[mid - 1] + gaps[mid]) / 2
    }

    /**
     * Duree de confirmation exigee pour la candidature en cours.
     *
     * Chute franche -> la duree configuree. Contraste faible (piece sombre, rabat
     * qui laisse filtrer) -> on exige bien plus longtemps, parce que la seule chose
     * qui distingue encore une fermeture d'une ombre est sa PERSISTANCE.
     */
    private fun requiredConfirmationMs(): Long {
        val cfg = config
        // La proximite NEAR est une certitude physique : elle vaut un contraste franc.
        val strong = proximityNear == true ||
            (frozenDropPercent >= cfg.strongDropPercent &&
                frozenAbsoluteDrop >= cfg.strongAbsoluteDropLux)
        if (strong) return cfg.confirmationDurationMs
        val stretched = (cfg.confirmationDurationMs * cfg.weakContrastConfirmFactor).toLong()
        return stretched.coerceIn(cfg.confirmationDurationMs, cfg.maxConfirmationMs)
    }

    private fun baselineLux(): Float {
        if (brightSamples.isEmpty()) return 0f
        val sorted = brightSamples.map { it.lux }.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    private fun clearHistory() {
        brightSamples.clear()
        lastBrightTimeMs = NEVER
        lastBrightLux = 0f
        lastPlateauTimeMs = NEVER
    }

    private fun cooldownRemaining(nowMs: Long): Long {
        if (lastLockTimeMs == NEVER) return 0L
        return (lastLockTimeMs + config.cooldownMs - nowMs).coerceAtLeast(0L)
    }

    private fun publish(nowMs: Long, reason: String): EngineSnapshot {
        val lux = lastLux
        val baseline = if (state == EngineState.CANDIDATE) frozenBaseline else baselineLux()
        val absoluteDrop = if (lux != null) (baseline - lux).coerceAtLeast(0f) else 0f
        val dropPercent = if (baseline > 0f) (absoluteDrop / baseline) * 100f else 0f
        snapshot = EngineSnapshot(
            state = state,
            lux = lux,
            baselineLux = baseline,
            baselineSampleCount = brightSamples.size,
            absoluteDropLux = absoluteDrop,
            dropPercent = dropPercent,
            candidate = state == EngineState.CANDIDATE,
            candidateElapsedMs = if (state == EngineState.CANDIDATE) nowMs - candidateStartMs else 0L,
            confirmedAtMs = lastConfirmedAtMs,
            cooldownRemainingMs = cooldownRemaining(nowMs),
            proximityNear = proximityNear,
            proximitySupported = proximitySupported,
            reason = reason,
        )
        return snapshot
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.1f", value)

    companion object {
        private const val NEVER = -1_000_000_000L
        private const val MAX_BRIGHT_SAMPLES = 120
    }
}
