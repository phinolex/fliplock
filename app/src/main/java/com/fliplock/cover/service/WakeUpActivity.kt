package com.fliplock.cover.service

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Solution de repli pour allumer l'ecran, via l'API officielle
 * `setTurnScreenOn` (API 27+), si le WakeLock ecran — deprecie — venait a etre
 * neutralise par une future version d'Android.
 *
 * Cette activite est invisible : fond transparent, aucune animation, et elle se
 * termine au bout de 400 ms. Elle N'ESSAIE PAS de deverrouiller le telephone :
 * l'ecran s'allume sur l'ecran de verrouillage, l'authentification reste requise.
 */
class WakeUpActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, VISIBLE_MS)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private companion object {
        const val VISIBLE_MS = 400L
    }
}
