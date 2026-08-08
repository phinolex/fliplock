package com.fliplock.cover.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le seuil recommande est ce qui pilote toute la detection : s'il est trop haut,
 * la moindre ombre est evaluee comme une fermeture potentielle et la bande morte
 * entre seuil de fermeture et seuil de relachement devient enorme.
 */
class CalibrationThresholdTest {

    private fun threshold(openMedianLux: Float, closedMax: Float) =
        CalibrationManager.recommendedThreshold(openMedianLux, closedMax)

    @Test
    fun `rabat parfaitement opaque en plein jour - seuil bas`() {
        // Cas reel releve sur un Galaxy SM-S948B : 629 lux ouvert, 0 lux ferme.
        // L'ancienne formule recommandait 47,9 lux ; toute ombre passait dessous.
        val t = threshold(openMedianLux = 629f, closedMax = 1f)
        assertTrue("seuil trop haut: $t", t <= 6f)
        assertTrue("seuil trop bas: $t", t >= 2f)
    }

    @Test
    fun `rabat opaque en interieur - seuil bas aussi`() {
        val t = threshold(openMedianLux = 140f, closedMax = 0f)
        assertTrue("seuil trop haut: $t", t <= 4f)
    }

    @Test
    fun `rabat qui laisse filtrer la lumiere - seuil au-dessus de la fuite`() {
        // Ferme = 18 lux : le seuil doit passer au-dessus, sinon rien n'est detecte.
        val t = threshold(openMedianLux = 143f, closedMax = 18f)
        assertTrue("seuil sous la fuite mesuree: $t", t > 18f)
    }

    @Test
    fun `le seuil ne depasse jamais le quart de la lumiere ambiante`() {
        // Rabat tres fuyant dans une piece sombre : mieux vaut un seuil bas et rater
        // une fermeture que verrouiller a chaque variation de lumiere.
        val t = threshold(openMedianLux = 40f, closedMax = 30f)
        assertTrue("seuil trop proche de l'ambiant: $t", t <= 10f)
    }

    @Test
    fun `bornes respectees`() {
        assertEquals(60f, threshold(openMedianLux = 100_000f, closedMax = 10_000f), 0.01f)
        assertTrue(threshold(openMedianLux = 0f, closedMax = 0f) >= 0.5f)
    }
}
