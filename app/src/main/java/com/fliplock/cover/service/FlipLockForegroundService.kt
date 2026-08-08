package com.fliplock.cover.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fliplock.cover.AppGraph
import com.fliplock.cover.MainActivity
import com.fliplock.cover.R
import com.fliplock.cover.log.LogCategory
import com.fliplock.cover.runtime.FlipLockRuntime

/**
 * Service de premier plan OPTIONNEL, desactive par defaut.
 *
 * Il NE LIT AUCUN CAPTEUR : c'est le service d'accessibilite qui surveille la
 * luminosite. Son unique role est de maintenir le processus dans un etat
 * « foreground » si Android/One UI venait a couper la diffusion des evenements
 * de capteurs en arriere-plan (restriction introduite avec Android 9).
 *
 * A activer uniquement si le TEST C (fermeture hors de FlipLock) echoue.
 */
class FlipLockForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.fgs_notification_title))
            .setContentText(getString(R.string.fgs_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            FlipLockRuntime.persistentServiceRunning.value = true
            AppGraph.logger.log(LogCategory.SYSTEM, "persistent service started")
            START_STICKY
        } catch (t: Throwable) {
            AppGraph.logger.log(
                LogCategory.SYSTEM,
                "persistent service refused by Android (${t.javaClass.simpleName})",
            )
            FlipLockRuntime.persistentServiceRunning.value = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        FlipLockRuntime.persistentServiceRunning.value = false
        AppGraph.logger.log(LogCategory.SYSTEM, "persistent service stopped")
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fgs_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.fgs_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "fliplock_monitoring"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FlipLockForegroundService::class.java),
            )
            true
        } catch (t: Throwable) {
            AppGraph.logger.log(
                LogCategory.SYSTEM,
                "cannot start persistent service (${t.javaClass.simpleName})",
            )
            false
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, FlipLockForegroundService::class.java))
            }
        }
    }
}
