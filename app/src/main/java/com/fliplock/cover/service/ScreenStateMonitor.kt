package com.fliplock.cover.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Suit l'etat interactif de l'ecran.
 *
 * On n'utilise QUE les diffusions systeme ACTION_SCREEN_ON / ACTION_SCREEN_OFF
 * (aucun sondage) et PowerManager.isInteractive pour l'etat initial.
 */
class ScreenStateMonitor(
    private val context: Context,
    private val onChanged: (Boolean) -> Unit,
) {

    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onChanged(true)
                Intent.ACTION_SCREEN_OFF -> onChanged(false)
            }
        }
    }

    val isInteractive: Boolean
        get() = powerManager?.isInteractive ?: true

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }
}
