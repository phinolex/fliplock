package com.fliplock.cover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fliplock.cover.R
import com.fliplock.cover.detection.DetectionStrategy
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun AdvancedSettingsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var threshold by remember(settings.closedLuxThreshold) {
        mutableFloatStateOf(settings.closedLuxThreshold)
    }
    var dropPercent by remember(settings.minimumDropPercent) {
        mutableFloatStateOf(settings.minimumDropPercent)
    }
    var absoluteDrop by remember(settings.minimumAbsoluteDropLux) {
        mutableFloatStateOf(settings.minimumAbsoluteDropLux)
    }
    var confirmMs by remember(settings.confirmationDurationMs) {
        mutableFloatStateOf(settings.confirmationDurationMs.toFloat())
    }
    var cooldownMs by remember(settings.cooldownMs) {
        mutableFloatStateOf(settings.cooldownMs.toFloat())
    }
    var minBaseline by remember(settings.minBaselineLux) {
        mutableFloatStateOf(settings.minBaselineLux)
    }
    var instantWindowSec by remember(settings.wakeInstantWindowMs) {
        mutableFloatStateOf(settings.wakeInstantWindowMs / 1000f)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = stringResource(R.string.adv_detection)) {
                LabeledSlider(
                    label = stringResource(R.string.adv_close_threshold),
                    valueText = lux(threshold),
                    value = threshold,
                    range = 0.5f..30f,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = {
                        viewModel.updateSettings { it.copy(closedLuxThreshold = threshold) }
                    },
                )
                Text(
                    text = stringResource(R.string.adv_close_threshold_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LabeledSlider(
                    label = stringResource(R.string.adv_min_drop),
                    valueText = formatPercent(dropPercent),
                    value = dropPercent,
                    range = 15f..99f,
                    onValueChange = { dropPercent = it },
                    onValueChangeFinished = {
                        viewModel.updateSettings { it.copy(minimumDropPercent = dropPercent) }
                    },
                )

                LabeledSlider(
                    label = stringResource(R.string.adv_min_absolute_drop),
                    valueText = lux(absoluteDrop),
                    value = absoluteDrop,
                    range = 2f..60f,
                    onValueChange = { absoluteDrop = it },
                    onValueChangeFinished = {
                        viewModel.updateSettings { it.copy(minimumAbsoluteDropLux = absoluteDrop) }
                    },
                )

                LabeledSlider(
                    label = stringResource(R.string.adv_confirmation),
                    valueText = "${confirmMs.roundToLong()} ms",
                    value = confirmMs,
                    range = 100f..1500f,
                    onValueChange = { confirmMs = it },
                    onValueChangeFinished = {
                        viewModel.updateSettings {
                            it.copy(confirmationDurationMs = confirmMs.roundToLong())
                        }
                    },
                )

                LabeledSlider(
                    label = stringResource(R.string.adv_cooldown),
                    valueText = "${cooldownMs.roundToLong()} ms",
                    value = cooldownMs,
                    range = 500f..10000f,
                    onValueChange = { cooldownMs = it },
                    onValueChangeFinished = {
                        viewModel.updateSettings { it.copy(cooldownMs = cooldownMs.roundToLong()) }
                    },
                )

                LabeledSlider(
                    label = stringResource(R.string.adv_min_baseline),
                    valueText = lux(minBaseline),
                    value = minBaseline,
                    range = 2f..60f,
                    onValueChange = { minBaseline = it },
                    onValueChangeFinished = {
                        viewModel.updateSettings { it.copy(minBaselineLux = minBaseline) }
                    },
                )
                Text(
                    text = stringResource(R.string.adv_min_baseline_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.adv_mode)) {
                DetectionStrategy.entries.forEach { strategy ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = settings.strategy == strategy,
                                onClick = { viewModel.setStrategy(strategy) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.strategy == strategy,
                            onClick = { viewModel.setStrategy(strategy) },
                        )
                        Text(text = strategy.label(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    text = stringResource(R.string.adv_mode_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.adv_wake_title)) {
                if (!viewModel.wakeOnOpenSupported) {
                    Text(
                        text = stringResource(R.string.adv_wake_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusColors.off,
                    )
                    Text(
                        text = stringResource(R.string.adv_wake_unavailable_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // L'interrupteur vit sur l'accueil : ici on ne regle que le detail.
                    Text(
                        text = stringResource(R.string.adv_toggle_on_home),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InfoRow(
                        stringResource(R.string.adv_wake_threshold),
                        lux(viewModel.wakeLuxThreshold()),
                    )
                    ThinDivider()
                    LabeledSlider(
                        label = stringResource(R.string.adv_wake_instant_window),
                        valueText = if (instantWindowSec < 1f) {
                            stringResource(R.string.adv_wake_instant_disabled)
                        } else {
                            stringResource(
                                R.string.adv_wake_instant_seconds,
                                instantWindowSec.roundToInt(),
                            )
                        },
                        value = instantWindowSec,
                        range = 0f..300f,
                        onValueChange = { instantWindowSec = it },
                        onValueChangeFinished = {
                            viewModel.updateSettings {
                                it.copy(wakeInstantWindowMs = instantWindowSec.roundToLong() * 1000L)
                            }
                        },
                    )
                    Text(
                        text = stringResource(R.string.adv_wake_instant_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ThinDivider()
                    Text(
                        text = stringResource(R.string.adv_wake_triggers_help, viewModel.wakeTriggers),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ThinDivider()
                    Text(
                        text = stringResource(R.string.adv_wake_limits),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.adv_reset)) {
                OutlinedButton(
                    onClick = { viewModel.resetDefaults() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.adv_reset_button))
                }
                Text(
                    text = stringResource(R.string.adv_reset_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
