package com.fliplock.cover.detection

import java.util.Locale

/**
 * Strategies de detection disponibles.
 *
 * - [LIGHT_ONLY]           : capteur de luminosite uniquement.
 * - [LIGHT_PLUS_PROXIMITY] : la proximite doit CONFIRMER la chute de lumiere.
 * - [AUTO]                 : la lumiere suffit quand elle est concluante ; la proximite
 *                            sert de secours quand la piece est trop sombre pour trancher.
 */
enum class DetectionStrategy {
    AUTO,
    LIGHT_ONLY,
    LIGHT_PLUS_PROXIMITY,
}

/**
 * Etat de la coque expose a l'interface.
 *
 * Le moteur ne produit JAMAIS de texte destine a l'utilisateur : il renvoie un
 * etat, et c'est la couche Compose qui choisit la chaine traduite. C'est ce qui
 * permet a [CoverDetectionEngine] de rester 100 % Kotlin/JVM, sans ressources
 * Android, donc testable.
 */
enum class CoverState {
    OPEN,
    CLOSING,
    COOLDOWN,
    SCREEN_OFF,
    MONITORING_OFF,
}

/** Etat interne du moteur, expose pour le diagnostic. */
enum class EngineState {
    /** FlipLock desactive : aucun capteur analyse. */
    DISABLED,

    /** Ecran deja eteint : rien a verrouiller. */
    SCREEN_OFF,

    /** Verrouillage recent : on ignore volontairement les mesures. */
    COOLDOWN,

    /** En veille active : on suit la luminosite ambiante. */
    IDLE,

    /** Chute detectee, confirmation en cours. */
    CANDIDATE,
}

/**
 * Parametres du detecteur. Tous exposes dans « Reglages avances ».
 *
 * Les valeurs par defaut correspondent au cahier des charges :
 * seuil 2 lux, chute 85 %, chute absolue 5 lux, confirmation 300 ms, cooldown 1500 ms.
 */
data class DetectionConfig(
    /** Au-dessus de ce niveau, on considere que le rabat n'est pas ferme. */
    val closedLuxThreshold: Float = 2.0f,

    /** Chute relative minimale par rapport a la baseline, en pourcentage. */
    val minimumDropPercent: Float = 85.0f,

    /** Chute absolue minimale, en lux : protege des pieces deja sombres. */
    val minimumAbsoluteDropLux: Float = 5.0f,

    /** Duree pendant laquelle l'obscurite doit persister avant de verrouiller. */
    val confirmationDurationMs: Long = 300L,

    /** Delai d'inhibition apres un verrouillage. */
    val cooldownMs: Long = 1500L,

    /** Strategie choisie par l'utilisateur. */
    val strategy: DetectionStrategy = DetectionStrategy.AUTO,

    /** Fenetre glissante servant a calculer la baseline (mediane). */
    val baselineWindowMs: Long = 3000L,

    /**
     * Duree maximale autorisee entre la derniere mesure « claire » et la mesure sombre.
     * C'est le critere de VITESSE : une extinction progressive de la piece ne le satisfait pas.
     *
     * ATTENTION : c'est un PLANCHER, pas une valeur absolue. Le capteur de lumiere
     * est « on-change » et Android ralentit fortement sa cadence quand une autre
     * application est au premier plan. Avec une mesure toutes les 1,5 s, aucune
     * fermeture ne peut tenir dans 900 ms. Le moteur elargit donc cette fenetre en
     * fonction de la cadence reellement observee — voir CoverDetectionEngine.
     */
    val fallWindowMs: Long = 900L,

    /**
     * Multiplicateur applique a la cadence observee du capteur pour obtenir la
     * fenetre de chute effective. Une fermeture reelle passe de clair a sombre en
     * une a deux mesures ; une piece qui s'assombrit sejourne bien plus longtemps
     * dans la zone intermediaire.
     */
    val fallWindowSampleFactor: Float = 2.5f,

    /** Plafond absolu de la fenetre de chute, quelle que soit la lenteur du capteur. */
    val maxFallWindowMs: Long = 2600L,

    /**
     * Critere de PLATEAU : la derniere mesure claire doit valoir au moins cette
     * fraction de la baseline.
     *
     * C'est ce qui distingue une fermeture d'un assombrissement progressif, et le
     * critere tient meme quand le capteur est ralenti. Une fermeture ressemble a un
     * plateau suivi d'une falaise (140, 140, 140, puis 0). Un assombrissement est une
     * pente : les mesures claires elles-memes decroissent (140, 120, 90, 60, 30),
     * et la derniere passe donc largement sous la mediane.
     */
    val baselinePlateauRatio: Float = 0.5f,

    /**
     * En dessous de cette baseline, la lumiere seule ne permet physiquement pas
     * de distinguer « rabat ferme » de « piece sombre ».
     */
    val minBaselineLux: Float = 8.0f,

    /**
     * Duree pendant laquelle un passage FAR -> NEAR de la proximite reste
     * considere comme « lie a la fermeture en cours ».
     */
    val proximityConfirmWindowMs: Long = 2500L,
) {
    /**
     * Seuil de relachement (hysteresis). Tant que la mesure reste en dessous,
     * la candidature se poursuit ; au-dessus, c'est un artefact (main, reflet...).
     */
    val releaseLuxThreshold: Float
        get() = closedLuxThreshold * 2.5f + 1.0f

    companion object {
        val DEFAULT = DetectionConfig()
    }
}

/** Evenements emis par le moteur, utilises pour le journal et pour declencher l'action. */
sealed interface DetectionEvent {
    data class CandidateStarted(
        val lux: Float,
        val baseline: Float,
        val dropPercent: Float,
        val reason: String,
    ) : DetectionEvent

    data class CandidateCancelled(
        val reason: String,
        val elapsedMs: Long,
    ) : DetectionEvent

    /**
     * L'obscurite a bien ete atteinte mais la candidature a ete refusee.
     *
     * Emis une seule fois par episode sombre : sans cela, un echec de detection
     * ne laisse AUCUNE trace dans le journal et devient impossible a diagnostiquer.
     */
    data class CandidateRejected(
        val lux: Float,
        val baseline: Float,
        val reason: String,
    ) : DetectionEvent

    data class Confirmed(
        val durationMs: Long,
        val lux: Float,
        val baseline: Float,
    ) : DetectionEvent

    /** Le seul evenement qui doit provoquer un verrouillage reel. */
    data object LockRequested : DetectionEvent
}

/** Photographie de l'etat du moteur, affichee dans l'interface et le diagnostic. */
data class EngineSnapshot(
    val state: EngineState,
    val lux: Float?,
    val baselineLux: Float,
    val baselineSampleCount: Int,
    val absoluteDropLux: Float,
    val dropPercent: Float,
    val candidate: Boolean,
    val candidateElapsedMs: Long,
    val confirmedAtMs: Long,
    val cooldownRemainingMs: Long,
    val proximityNear: Boolean?,
    val proximitySupported: Boolean,
    val reason: String,
) {
    val coverState: CoverState
        get() = when {
            candidate -> CoverState.CLOSING
            state == EngineState.COOLDOWN -> CoverState.COOLDOWN
            state == EngineState.SCREEN_OFF -> CoverState.SCREEN_OFF
            state == EngineState.DISABLED -> CoverState.MONITORING_OFF
            else -> CoverState.OPEN
        }

    fun dropPercentText(): String = String.format(Locale.US, "%.1f %%", dropPercent)

    companion object {
        val EMPTY = EngineSnapshot(
            state = EngineState.DISABLED,
            lux = null,
            baselineLux = 0f,
            baselineSampleCount = 0,
            absoluteDropLux = 0f,
            dropPercent = 0f,
            candidate = false,
            candidateElapsedMs = 0L,
            confirmedAtMs = 0L,
            cooldownRemainingMs = 0L,
            proximityNear = null,
            proximitySupported = false,
            reason = "waiting for a reading",
        )
    }
}
