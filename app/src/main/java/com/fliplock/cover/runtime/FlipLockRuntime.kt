package com.fliplock.cover.runtime

import com.fliplock.cover.detection.EngineSnapshot
import com.fliplock.cover.sensors.LightSample
import com.fliplock.cover.sensors.ProximitySample
import kotlinx.coroutines.flow.MutableStateFlow

/** Ce qui a demande le verrouillage. */
enum class LockOrigin { DETECTION, TEST_BUTTON }

/** Resultat d'une tentative de verrouillage. */
data class LockAttempt(
    val wallClockMs: Long,
    val success: Boolean,
    val origin: LockOrigin,
)

/**
 * Etat partage en memoire entre le service d'accessibilite et l'interface.
 * L'activite et le service tournent dans le meme processus : un simple objet
 * suffit, aucun IPC ni broadcast n'est necessaire.
 */
object FlipLockRuntime {
    val accessibilityConnected = MutableStateFlow(false)
    val monitoring = MutableStateFlow(false)
    val screenInteractive = MutableStateFlow(true)
    val persistentServiceRunning = MutableStateFlow(false)

    val lastLight = MutableStateFlow<LightSample?>(null)
    val lastProximity = MutableStateFlow<ProximitySample?>(null)
    val serviceSnapshot = MutableStateFlow<EngineSnapshot?>(null)

    val lightEventCount = MutableStateFlow(0L)
    val proximityEventCount = MutableStateFlow(0L)

    val lastLockAttempt = MutableStateFlow<LockAttempt?>(null)

    /** Reveil a l'ouverture : declencheurs wake-up actuellement armes. */
    val wakeOnOpenArmed = MutableStateFlow(false)

    /** Resultat de la derniere tentative d'allumage de l'ecran. */
    val lastWakeSucceeded = MutableStateFlow<Boolean?>(null)
}
