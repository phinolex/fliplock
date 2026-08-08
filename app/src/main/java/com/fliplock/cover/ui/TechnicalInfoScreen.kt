package com.fliplock.cover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fliplock.cover.R
import com.fliplock.cover.runtime.FlipLockRuntime
import com.fliplock.cover.sensors.SensorDescriptor
import com.fliplock.cover.service.FlipLockAccessibilityService

@Composable
fun TechnicalInfoScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val probes by viewModel.probeState.collectAsStateWithLifecycle()
    val lightEvents by FlipLockRuntime.lightEventCount.collectAsStateWithLifecycle()
    val proximityEvents by FlipLockRuntime.proximityEventCount.collectAsStateWithLifecycle()
    val monitoring by FlipLockRuntime.monitoring.collectAsStateWithLifecycle()
    val persistent by FlipLockRuntime.persistentServiceRunning.collectAsStateWithLifecycle()
    val screenInteractive by FlipLockRuntime.screenInteractive.collectAsStateWithLifecycle()
    val wakeArmed by FlipLockRuntime.wakeOnOpenArmed.collectAsStateWithLifecycle()
    val lastWake by FlipLockRuntime.lastWakeSucceeded.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    val device = viewModel.deviceInfo

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = stringResource(R.string.tech_phone)) {
                InfoRow("Build.MANUFACTURER", device.manufacturer)
                InfoRow("Build.MODEL", device.model)
                InfoRow("Build.DEVICE", device.device)
            }
        }

        item {
            SectionCard(title = stringResource(R.string.tech_android)) {
                InfoRow("Build.VERSION.RELEASE", device.androidRelease)
                InfoRow("Build.VERSION.SDK_INT", device.sdkInt.toString())
            }
        }

        item {
            SectionCard(title = stringResource(R.string.tech_light_sensor)) {
                DescriptorBlock(probes.light.descriptor)
                InfoRow(stringResource(R.string.tech_events_probe), probes.light.eventCount.toString())
                InfoRow(stringResource(R.string.tech_events_service), lightEvents.toString())
            }
        }

        item {
            SectionCard(title = stringResource(R.string.tech_proximity_sensor)) {
                DescriptorBlock(probes.proximity.descriptor)
                InfoRow(
                    stringResource(R.string.tech_events_probe),
                    probes.proximity.eventCount.toString(),
                )
                InfoRow(stringResource(R.string.tech_events_service), proximityEvents.toString())
            }
        }

        item {
            SectionCard(title = stringResource(R.string.tech_wake_title)) {
                Text(
                    text = if (viewModel.wakeOnOpenSupported) {
                        stringResource(R.string.tech_wake_available, viewModel.wakeTriggers)
                    } else {
                        stringResource(R.string.adv_wake_unavailable)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (viewModel.wakeOnOpenSupported) StatusColors.ok else StatusColors.off,
                )
                InfoRow(stringResource(R.string.tech_wake_light), viewModel.wakeUpCapability)
                InfoRow(stringResource(R.string.tech_wake_enabled), yesNo(settings.wakeOnOpenEnabled))
                InfoRow(stringResource(R.string.tech_wake_armed), yesNo(wakeArmed))
                InfoRow(
                    stringResource(R.string.tech_wake_last),
                    when (lastWake) {
                        null -> stringResource(R.string.value_none)
                        true -> stringResource(R.string.tech_wake_ok)
                        false -> stringResource(R.string.tech_wake_ko)
                    },
                )
                Text(
                    text = stringResource(R.string.tech_wake_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.tech_internal_state)) {
                InfoRow(
                    stringResource(R.string.tech_accessibility_connected),
                    yesNo(FlipLockAccessibilityService.isConnected),
                )
                InfoRow(stringResource(R.string.tech_monitoring_active), yesNo(monitoring))
                InfoRow(stringResource(R.string.tech_screen_interactive), yesNo(screenInteractive))
                InfoRow(stringResource(R.string.tech_persistent_service), yesNo(persistent))
                InfoRow(stringResource(R.string.tech_strategy), settings.strategy.label())
            }
        }

        item {
            SectionCard(title = stringResource(R.string.tech_privacy)) {
                Text(
                    text = stringResource(R.string.tech_privacy_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { viewModel.copyDiagnostic(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_copy_diagnostic))
                }
            }
        }
    }
}

@Composable
private fun DescriptorBlock(descriptor: SensorDescriptor?) {
    if (descriptor == null) {
        Text(
            stringResource(R.string.tech_sensor_absent),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    InfoRow(stringResource(R.string.label_name), descriptor.name)
    InfoRow(stringResource(R.string.label_vendor), descriptor.vendor)
    InfoRow(stringResource(R.string.label_version), descriptor.version.toString())
    InfoRow(stringResource(R.string.label_type), "${descriptor.type} (${descriptor.stringType})")
    InfoRow(stringResource(R.string.label_max_range), descriptor.maximumRange.toString())
    InfoRow(stringResource(R.string.label_resolution), descriptor.resolution.toString())
    InfoRow(stringResource(R.string.label_power), "${descriptor.power} mA")
    InfoRow(stringResource(R.string.label_reporting_mode), descriptor.reportingMode.toString())
    InfoRow(stringResource(R.string.label_wake_up), yesNo(descriptor.isWakeUpSensor))
}
