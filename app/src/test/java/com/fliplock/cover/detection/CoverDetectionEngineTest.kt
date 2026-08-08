package com.fliplock.cover.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du moteur de detection avec des valeurs artificielles.
 * Couvre les six scenarios du cahier des charges (section 36).
 */
class CoverDetectionEngineTest {

    private class Recorder {
        var candidates = 0
        var confirmations = 0
        var locks = 0
        val cancellations = mutableListOf<String>()
        val rejections = mutableListOf<String>()

        fun handle(event: DetectionEvent) {
            when (event) {
                is DetectionEvent.CandidateStarted -> candidates++
                is DetectionEvent.CandidateCancelled -> cancellations += event.reason
                is DetectionEvent.CandidateRejected -> rejections += event.reason
                is DetectionEvent.Confirmed -> confirmations++
                DetectionEvent.LockRequested -> locks++
            }
        }
    }

    private fun newEngine(
        config: DetectionConfig = DetectionConfig.DEFAULT,
    ): Pair<CoverDetectionEngine, Recorder> {
        val recorder = Recorder()
        val engine = CoverDetectionEngine(config, recorder::handle)
        engine.setEnabled(true, 0L)
        engine.setScreenInteractive(true, 0L)
        return engine to recorder
    }

    /** Alimente le moteur avec [count] mesures a [lux], espacees de [stepMs]. */
    private fun feed(
        engine: CoverDetectionEngine,
        lux: Float,
        from: Long,
        count: Int,
        stepMs: Long = 100L,
    ): Long {
        var time = from
        repeat(count) {
            engine.onLightReading(lux, time)
            time += stepMs
        }
        return time
    }

    // --- CAS 1 : baseline 100 -> 1 -> 0 => fermeture -------------------------

    @Test
    fun `cas 1 - chute brutale depuis une baseline claire declenche le verrouillage`() {
        val (engine, recorder) = newEngine()
        feed(engine, 100f, from = 0L, count = 10)

        engine.onLightReading(1f, 1000L)
        engine.onLightReading(0f, 1100L)
        engine.tick(1450L)

        assertEquals(1, recorder.candidates)
        assertEquals(1, recorder.confirmations)
        assertEquals(1, recorder.locks)
    }

    // --- CAS 2 : 100 80 60 40 20 => pas de fermeture brutale -----------------

    @Test
    fun `cas 2 - baisse progressive de la lumiere ne verrouille pas`() {
        val (engine, recorder) = newEngine()
        var time = 0L
        listOf(100f, 80f, 60f, 40f, 20f).forEach { lux ->
            engine.onLightReading(lux, time)
            time += 500L
        }
        engine.tick(time + 1000L)

        assertEquals(0, recorder.candidates)
        assertEquals(0, recorder.locks)
    }

    @Test
    fun `cas 2 bis - extinction lente jusqu'a l'obscurite ne verrouille pas`() {
        val (engine, recorder) = newEngine()
        var time = 0L
        listOf(100f, 70f, 50f, 30f, 10f, 4f, 1f, 0f).forEach { lux ->
            engine.onLightReading(lux, time)
            time += 800L
        }
        engine.tick(time + 1000L)

        assertEquals(0, recorder.locks)
    }

    // --- CAS 3 : piece deja sombre ------------------------------------------

    @Test
    fun `cas 3 - piece deja sombre ne verrouille pas sans autre signal`() {
        val (engine, recorder) = newEngine()
        engine.onLightReading(1f, 0L)
        engine.onLightReading(1f, 200L)
        engine.onLightReading(0f, 400L)
        engine.onLightReading(1f, 600L)
        engine.tick(1500L)

        assertEquals(0, recorder.candidates)
        assertEquals(0, recorder.locks)
    }

    @Test
    fun `cas 3 bis - piece sombre plus proximite NEAR verrouille en mode hybride`() {
        val (engine, recorder) = newEngine(
            DetectionConfig.DEFAULT.copy(strategy = DetectionStrategy.LIGHT_PLUS_PROXIMITY)
        )
        engine.onProximityReading(near = false, nowMs = 0L)
        engine.onLightReading(1f, 100L)
        engine.onProximityReading(near = true, nowMs = 300L)
        engine.onLightReading(0f, 400L)
        engine.tick(800L)

        assertEquals(1, recorder.locks)
    }

    // --- CAS 4 : artefact de 50 ms ------------------------------------------

    @Test
    fun `cas 4 - obscurite tres breve est traitee comme un artefact`() {
        val (engine, recorder) = newEngine()
        feed(engine, 100f, from = 0L, count = 10)

        engine.onLightReading(0f, 1000L)
        engine.onLightReading(100f, 1050L)
        engine.tick(1500L)

        assertEquals(1, recorder.candidates)
        assertEquals(0, recorder.confirmations)
        assertEquals(0, recorder.locks)
        assertTrue(recorder.cancellations.isNotEmpty())
    }

    // --- CAS 5 : obscurite > 300 ms -----------------------------------------

    @Test
    fun `cas 5 - obscurite maintenue au dela de la duree de confirmation verrouille`() {
        val (engine, recorder) = newEngine()
        feed(engine, 120f, from = 0L, count = 10)

        engine.onLightReading(0f, 1000L)
        engine.tick(1100L)
        engine.tick(1200L)
        assertEquals(0, recorder.locks)

        engine.tick(1310L)
        assertEquals(1, recorder.locks)
    }

    // --- CAS 6 : cooldown ---------------------------------------------------

    @Test
    fun `cas 6 - un second verrouillage est ignore pendant le cooldown`() {
        val (engine, recorder) = newEngine()
        feed(engine, 100f, from = 0L, count = 10)
        engine.onLightReading(0f, 1000L)
        engine.tick(1400L)
        assertEquals(1, recorder.locks)

        // Nouvelle fermeture immediate : cooldown de 1500 ms actif.
        feed(engine, 100f, from = 1500L, count = 5)
        engine.onLightReading(0f, 2000L)
        engine.tick(2400L)
        assertEquals(1, recorder.locks)

        // Apres expiration du cooldown, la detection reprend.
        feed(engine, 100f, from = 3000L, count = 5)
        engine.onLightReading(0f, 3500L)
        engine.tick(3900L)
        assertEquals(2, recorder.locks)
    }

    // --- protections supplementaires ----------------------------------------

    @Test
    fun `ecran deja eteint - aucun verrouillage`() {
        val (engine, recorder) = newEngine()
        feed(engine, 100f, from = 0L, count = 10)
        engine.setScreenInteractive(false, 950L)

        engine.onLightReading(0f, 1000L)
        engine.tick(1500L)

        assertEquals(0, recorder.locks)
    }

    @Test
    fun `fliplock desactive - aucun verrouillage`() {
        val (engine, recorder) = newEngine()
        feed(engine, 100f, from = 0L, count = 10)
        engine.setEnabled(false, 950L)

        engine.onLightReading(0f, 1000L)
        engine.tick(1500L)

        assertEquals(0, recorder.locks)
    }

    @Test
    fun `main passee devant le capteur - annulation avant confirmation`() {
        val (engine, recorder) = newEngine()
        feed(engine, 300f, from = 0L, count = 10)

        engine.onLightReading(0.5f, 1000L)
        engine.tick(1100L)
        engine.onLightReading(280f, 1180L)
        engine.tick(1600L)

        assertEquals(0, recorder.locks)
    }

    @Test
    fun `mode lumiere uniquement - la proximite n'influence pas la decision`() {
        val (engine, recorder) = newEngine(
            DetectionConfig.DEFAULT.copy(strategy = DetectionStrategy.LIGHT_ONLY)
        )
        engine.onProximityReading(near = true, nowMs = 0L)
        engine.onLightReading(1f, 100L)
        engine.onLightReading(0f, 300L)
        engine.tick(900L)

        assertEquals(0, recorder.locks)
    }

    // --- cadence lente du capteur (application en arriere-plan) --------------

    @Test
    fun `capteur ralenti par Android - une fermeture reste detectee`() {
        // Android reduit la cadence du capteur quand une autre application est au
        // premier plan : ici une mesure toutes les 800 ms. Avec une fenetre de chute
        // fixe a 900 ms, la fermeture serait rejetee alors qu'elle est instantanee.
        val (engine, recorder) = newEngine()
        feed(engine, 140f, from = 0L, count = 6, stepMs = 800L)

        engine.onLightReading(0f, 5000L) // mesure suivante : 800 ms apres la derniere claire
        engine.tick(5400L)

        assertEquals(1, recorder.locks)
    }

    @Test
    fun `capteur ralenti - une extinction progressive reste refusee`() {
        // Meme cadence lente, mais la piece s'assombrit par paliers : la lumiere
        // sejourne dans la zone intermediaire, ce qui eloigne la derniere mesure
        // claire. La fenetre elargie ne doit PAS laisser passer ce cas.
        val (engine, recorder) = newEngine()
        var time = 0L
        listOf(140f, 120f, 90f, 60f, 30f, 12f, 5f, 3f, 1f, 0f).forEach { lux ->
            engine.onLightReading(lux, time)
            time += 800L
        }
        engine.tick(time + 1000L)

        assertEquals(0, recorder.locks)
    }

    @Test
    fun `seuil eleve - le rabat qui traverse les valeurs intermediaires est detecte`() {
        // Seuil a 27 lux, donc relachement a 68,5 : le rabat qui descend passe par
        // 55 lux, une valeur qui ne compte ni comme sombre ni comme claire. La
        // vitesse se mesure depuis le plateau (200 lux), pas depuis la derniere
        // mesure au-dessus du relachement.
        val config = DetectionConfig.DEFAULT.copy(
            closedLuxThreshold = 27f,
            minimumDropPercent = 79f,
            minimumAbsoluteDropLux = 30f,
        )
        val (engine, recorder) = newEngine(config)
        feed(engine, 200f, from = 0L, count = 6, stepMs = 160L)
        // Descente du rabat : passage par la zone intermediaire, puis obscurite.
        engine.onLightReading(55f, 960L)
        engine.onLightReading(26f, 1120L)
        engine.tick(1500L)

        assertEquals(1, recorder.locks)
    }

    @Test
    fun `un refus est signale une seule fois par episode sombre`() {
        val (engine, recorder) = newEngine()
        // Piece deja sombre : aucune baseline exploitable, donc refus.
        engine.onLightReading(1f, 0L)
        engine.onLightReading(0f, 200L)
        engine.onLightReading(0f, 400L)
        assertEquals(1, recorder.rejections.size)

        // La lumiere revient puis repart : nouvel episode, nouveau signalement.
        engine.onLightReading(150f, 600L)
        engine.onLightReading(0f, 800L)
        assertEquals(2, recorder.rejections.size)
    }

    @Test
    fun `le snapshot expose la baseline et la chute mesurees`() {
        val (engine, _) = newEngine()
        feed(engine, 200f, from = 0L, count = 6)
        val snapshot = engine.onLightReading(0f, 600L)

        assertEquals(200f, snapshot.baselineLux, 0.01f)
        assertEquals(100f, snapshot.dropPercent, 0.01f)
        assertTrue(snapshot.candidate)
    }
}
