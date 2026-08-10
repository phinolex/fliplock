package com.fliplock.cover.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fliplock.cover.R
import com.fliplock.cover.detection.CoverState
import com.fliplock.cover.detection.DetectionStrategy
import com.fliplock.cover.runtime.FlipLockRuntime

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // La notification du service persistant exige POST_NOTIFICATIONS depuis Android 13.
    // Elle n'est demandee QUE si l'utilisateur active cette fonction facultative.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.setPersistentService(true) }

    fun enablePersistentService() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setPersistentService(true)
        }
    }

    val accessibilityEnabled by viewModel.accessibilityEnabled.collectAsStateWithLifecycle()
    val accessibilityConnected by FlipLockRuntime.accessibilityConnected.collectAsStateWithLifecycle()
    val monitoring by FlipLockRuntime.monitoring.collectAsStateWithLifecycle()
    val lastLight by viewModel.lastLight.collectAsStateWithLifecycle()
    val lastProximity by viewModel.lastProximity.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val lastLock by FlipLockRuntime.lastLockAttempt.collectAsStateWithLifecycle()
    val undoneLocks by FlipLockRuntime.undoneLockCount.collectAsStateWithLifecycle()

    val active = settings.enabled && accessibilityConnected

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.home_status),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(
                                if (active) R.string.home_status_active else R.string.home_status_inactive
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { viewModel.setEnabled(it) },
                    )
                }
                if (settings.enabled && !accessibilityConnected) {
                    Text(
                        text = stringResource(R.string.home_permission_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.alert,
                    )
                }
                // Mode hybride actif alors que la proximite ne reagit pas au rabat :
                // TOUTES les fermetures seront rejetees. Le dire, plutot que d'echouer
                // en silence.
                if (settings.strategy == DetectionStrategy.LIGHT_PLUS_PROXIMITY &&
                    settings.calibrationDone && !settings.calibrationProximityUsable
                ) {
                    ThinDivider()
                    Text(
                        text = stringResource(R.string.home_hybrid_broken),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.alert,
                    )
                    Button(
                        onClick = { viewModel.setStrategy(DetectionStrategy.AUTO) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.home_switch_auto))
                    }
                }
                // Faux positifs CONSTATES : l'ecran a ete rallume juste apres un
                // verrouillage automatique. Plus fiable qu'un seuil theorique.
                if (undoneLocks >= 2) {
                    ThinDivider()
                    Text(
                        text = stringResource(R.string.home_false_positives, undoneLocks),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.alert,
                    )
                    Button(
                        onClick = { viewModel.reduceSensitivity() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.home_reduce_sensitivity))
                    }
                } else if (settings.enabled &&
                    (settings.minimumDropPercent < 50f || settings.minimumAbsoluteDropLux < 10f)
                ) {
                    ThinDivider()
                    Text(
                        text = stringResource(R.string.home_sensitive_settings),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.warn,
                    )
                }
                InfoRow(
                    label = stringResource(R.string.home_monitoring),
                    value = stringResource(
                        if (monitoring) R.string.home_monitoring_running else R.string.home_monitoring_stopped
                    ),
                )
            }
        }

        // Ce sont des FONCTIONS, pas du reglage fin : elles decident de ce que
        // l'application fait. Leur place est ici, pas au fond des reglages avances
        // a cote des curseurs de lux.
        item {
            SectionCard(title = stringResource(R.string.home_features)) {
                if (viewModel.wakeOnOpenSupported) {
                    SwitchRow(
                        label = stringResource(R.string.home_wake_label),
                        description = stringResource(R.string.home_wake_desc),
                        checked = settings.wakeOnOpenEnabled,
                        onCheckedChange = { viewModel.setWakeOnOpen(it) },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.adv_wake_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusColors.off,
                    )
                }
                ThinDivider()
                SwitchRow(
                    label = stringResource(R.string.adv_persistent_service),
                    description = stringResource(R.string.home_persistent_desc),
                    checked = settings.persistentServiceEnabled,
                    onCheckedChange = { checked ->
                        if (checked) enablePersistentService() else viewModel.setPersistentService(false)
                    },
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.section_sensor)) {
                InfoRow(
                    label = stringResource(R.string.label_light),
                    value = lux(lastLight?.lux),
                    emphasize = true,
                )
                InfoRow(
                    label = stringResource(R.string.label_baseline),
                    value = lux(snapshot.baselineLux),
                )
                lastProximity?.let { proximity ->
                    InfoRow(
                        label = stringResource(R.string.label_proximity),
                        value = stringResource(
                            if (proximity.near) R.string.proximity_near else R.string.proximity_far
                        ),
                    )
                }
                ThinDivider()
                Text(
                    text = stringResource(R.string.label_cover_state),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = snapshot.coverState.label(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (snapshot.coverState == CoverState.CLOSING) {
                        StatusColors.ok
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = snapshot.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.section_lock_permission)) {
                StatusPill(
                    text = stringResource(
                        when {
                            accessibilityConnected -> R.string.permission_granted
                            accessibilityEnabled -> R.string.permission_pending
                            else -> R.string.permission_denied
                        }
                    ),
                    color = if (accessibilityConnected) StatusColors.ok else StatusColors.alert,
                    containerColor = if (accessibilityConnected) {
                        StatusColors.okContainer
                    } else {
                        StatusColors.alertContainer
                    },
                )
                Text(
                    text = stringResource(R.string.permission_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!accessibilityConnected) {
                    Button(
                        onClick = { viewModel.openAccessibilitySettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_enable_permission))
                    }
                    OutlinedButton(
                        onClick = { viewModel.openAppDetails(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_open_app_details))
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.section_actions)) {
                Button(
                    onClick = { onNavigate(Screen.CALIBRATION) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_calibrate))
                }
                Button(
                    onClick = { viewModel.testLock() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_test_lock))
                }
                lastLock?.let { attempt ->
                    InfoRow(
                        label = stringResource(R.string.label_last_lock),
                        value = stringResource(
                            if (attempt.success) R.string.lock_succeeded else R.string.lock_failed,
                            attempt.origin.label(),
                        ),
                    )
                }
                OutlinedButton(
                    onClick = { onNavigate(Screen.DIAGNOSTIC) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.screen_diagnostic))
                }
                OutlinedButton(
                    onClick = { onNavigate(Screen.ADVANCED) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.screen_advanced))
                }
                OutlinedButton(
                    onClick = { onNavigate(Screen.TECH_INFO) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.screen_tech_info))
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.section_how_it_works)) {
                Text(
                    text = stringResource(
                        R.string.how_it_works_body,
                        settings.confirmationDurationMs.toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
