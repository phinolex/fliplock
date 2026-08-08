package com.fliplock.cover

import android.app.Application
import android.content.Context
import com.fliplock.cover.data.SettingsRepository
import com.fliplock.cover.log.FlipLockLogger
import com.fliplock.cover.sensors.SensorRepository

/**
 * Conteneur de dependances minimal.
 *
 * Aucune bibliotheque d'injection : trois objets, crees une fois,
 * partages entre l'activite et le service d'accessibilite (meme processus).
 */
object AppGraph {

    @Volatile
    private var initialized = false

    lateinit var settings: SettingsRepository
        private set

    lateinit var sensors: SensorRepository
        private set

    lateinit var logger: FlipLockLogger
        private set

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        logger = FlipLockLogger()
        settings = SettingsRepository(app)
        sensors = SensorRepository(app)
        initialized = true
    }
}

class FlipLockApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
