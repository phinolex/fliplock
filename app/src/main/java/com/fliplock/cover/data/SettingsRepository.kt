package com.fliplock.cover.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fliplock.cover.detection.DetectionStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.flipLockDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fliplock_settings"
)

/**
 * Persistance locale des reglages (DataStore Preferences).
 * Aucun compte, aucun cloud, aucune synchronisation.
 */
class SettingsRepository(context: Context) {

    private val store = context.applicationContext.flipLockDataStore

    val settings: Flow<FlipLockSettings> = store.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { it.toSettings() }

    suspend fun current(): FlipLockSettings = settings.first()

    suspend fun update(transform: (FlipLockSettings) -> FlipLockSettings) {
        store.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[KEY_ENABLED] = updated.enabled
            prefs[KEY_CLOSED_THRESHOLD] = updated.closedLuxThreshold
            prefs[KEY_DROP_PERCENT] = updated.minimumDropPercent
            prefs[KEY_ABSOLUTE_DROP] = updated.minimumAbsoluteDropLux
            prefs[KEY_CONFIRM_MS] = updated.confirmationDurationMs
            prefs[KEY_COOLDOWN_MS] = updated.cooldownMs
            prefs[KEY_MIN_BASELINE] = updated.minBaselineLux
            prefs[KEY_STRATEGY] = updated.strategy.name
            prefs[KEY_PERSISTENT_SERVICE] = updated.persistentServiceEnabled
            prefs[KEY_WAKE_ON_OPEN] = updated.wakeOnOpenEnabled
            prefs[KEY_WAKE_INSTANT_WINDOW] = updated.wakeInstantWindowMs
            prefs[KEY_CALIB_DONE] = updated.calibrationDone
            prefs[KEY_CALIB_OPEN] = updated.calibrationOpenLux
            prefs[KEY_CALIB_CLOSED] = updated.calibrationClosedLux
            prefs[KEY_CALIB_SEPARATION] = updated.calibrationSeparationPercent
            prefs[KEY_CALIB_PROXIMITY] = updated.calibrationProximityUsable
        }
    }

    private fun Preferences.toSettings(): FlipLockSettings {
        val defaults = FlipLockSettings.DEFAULT
        val strategyName = this[KEY_STRATEGY]
        val strategy = DetectionStrategy.entries.firstOrNull { it.name == strategyName }
            ?: defaults.strategy
        return FlipLockSettings(
            enabled = this[KEY_ENABLED] ?: defaults.enabled,
            closedLuxThreshold = this[KEY_CLOSED_THRESHOLD] ?: defaults.closedLuxThreshold,
            minimumDropPercent = this[KEY_DROP_PERCENT] ?: defaults.minimumDropPercent,
            minimumAbsoluteDropLux = this[KEY_ABSOLUTE_DROP] ?: defaults.minimumAbsoluteDropLux,
            confirmationDurationMs = this[KEY_CONFIRM_MS] ?: defaults.confirmationDurationMs,
            cooldownMs = this[KEY_COOLDOWN_MS] ?: defaults.cooldownMs,
            minBaselineLux = this[KEY_MIN_BASELINE] ?: defaults.minBaselineLux,
            strategy = strategy,
            persistentServiceEnabled = this[KEY_PERSISTENT_SERVICE] ?: defaults.persistentServiceEnabled,
            wakeOnOpenEnabled = this[KEY_WAKE_ON_OPEN] ?: defaults.wakeOnOpenEnabled,
            wakeInstantWindowMs = this[KEY_WAKE_INSTANT_WINDOW] ?: defaults.wakeInstantWindowMs,
            calibrationDone = this[KEY_CALIB_DONE] ?: defaults.calibrationDone,
            calibrationOpenLux = this[KEY_CALIB_OPEN] ?: defaults.calibrationOpenLux,
            calibrationClosedLux = this[KEY_CALIB_CLOSED] ?: defaults.calibrationClosedLux,
            calibrationSeparationPercent = this[KEY_CALIB_SEPARATION] ?: defaults.calibrationSeparationPercent,
            calibrationProximityUsable = this[KEY_CALIB_PROXIMITY] ?: defaults.calibrationProximityUsable,
        )
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_CLOSED_THRESHOLD = floatPreferencesKey("closed_lux_threshold")
        val KEY_DROP_PERCENT = floatPreferencesKey("minimum_drop_percent")
        val KEY_ABSOLUTE_DROP = floatPreferencesKey("minimum_absolute_drop_lux")
        val KEY_CONFIRM_MS = longPreferencesKey("confirmation_duration_ms")
        val KEY_COOLDOWN_MS = longPreferencesKey("cooldown_ms")
        val KEY_MIN_BASELINE = floatPreferencesKey("min_baseline_lux")
        val KEY_STRATEGY = stringPreferencesKey("strategy")
        val KEY_PERSISTENT_SERVICE = booleanPreferencesKey("persistent_service")
        val KEY_WAKE_ON_OPEN = booleanPreferencesKey("wake_on_open")
        val KEY_WAKE_INSTANT_WINDOW = longPreferencesKey("wake_instant_window_ms")
        val KEY_CALIB_DONE = booleanPreferencesKey("calibration_done")
        val KEY_CALIB_OPEN = floatPreferencesKey("calibration_open_lux")
        val KEY_CALIB_CLOSED = floatPreferencesKey("calibration_closed_lux")
        val KEY_CALIB_SEPARATION = floatPreferencesKey("calibration_separation")
        val KEY_CALIB_PROXIMITY = booleanPreferencesKey("calibration_proximity")
    }
}
