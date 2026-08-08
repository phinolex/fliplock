package com.fliplock.cover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fliplock.cover.R
import com.fliplock.cover.calibration.CalibrationQuality
import com.fliplock.cover.detection.DetectionStrategy

@Composable
fun CalibrationScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.calibration.collectAsStateWithLifecycle()
    val darkTest by viewModel.darkTest.collectAsStateWithLifecycle()
    val probing by viewModel.probing.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = stringResource(R.string.calib_title)) {
                when (state.step) {
                    CalibrationStep.INTRO -> {
                        Text(
                            text = stringResource(R.string.calib_intro_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.calib_intro_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = { viewModel.startCalibration() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.calib_start))
                        }
                    }

                    CalibrationStep.MEASURING_OPEN -> {
                        Text(
                            text = stringResource(R.string.calib_measuring_open),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        BigValue(lux(state.liveLux), stringResource(R.string.calib_measured_light))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    CalibrationStep.COUNTDOWN -> {
                        Text(
                            text = stringResource(R.string.calib_close_now),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        BigValue(
                            state.countdown.toString(),
                            stringResource(R.string.calib_countdown),
                        )
                        state.openStats?.let {
                            InfoRow(stringResource(R.string.calib_open_summary), it.summary())
                        }
                    }

                    CalibrationStep.MEASURING_CLOSED -> {
                        Text(
                            text = stringResource(R.string.calib_measuring_closed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        BigValue(lux(state.liveLux), stringResource(R.string.calib_measured_light))
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    CalibrationStep.RESULT -> {
                        val result = state.result
                        if (result == null) {
                            Text(stringResource(R.string.calib_no_result))
                        } else {
                            val color = when (result.quality) {
                                CalibrationQuality.EXCELLENT, CalibrationQuality.GOOD -> StatusColors.ok
                                CalibrationQuality.WEAK -> StatusColors.warn
                                CalibrationQuality.POOR -> StatusColors.alert
                            }
                            Text(
                                text = result.quality.label(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = color,
                            )
                            InfoRow(stringResource(R.string.calib_open_median), lux(result.open.median))
                            InfoRow(stringResource(R.string.calib_closed_median), lux(result.closed.median))
                            InfoRow(stringResource(R.string.calib_closed_max), lux(result.closed.max))
                            InfoRow(
                                stringResource(R.string.calib_difference),
                                formatPercent(result.separationPercent),
                                emphasize = true,
                            )
                            ThinDivider()
                            Text(
                                text = stringResource(R.string.calib_computed),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            InfoRow(
                                stringResource(R.string.adv_close_threshold),
                                lux(result.recommendedClosedThreshold),
                            )
                            InfoRow(
                                stringResource(R.string.adv_min_drop),
                                formatPercent(result.recommendedDropPercent),
                            )
                            InfoRow(
                                stringResource(R.string.adv_min_absolute_drop),
                                lux(result.recommendedAbsoluteDropLux),
                            )
                            ThinDivider()
                            InfoRow(
                                stringResource(R.string.calib_proximity_during),
                                stringResource(
                                    when {
                                        !result.proximityAvailable -> R.string.calib_proximity_absent
                                        result.proximityNearWhenClosed -> R.string.calib_proximity_near_seen
                                        else -> R.string.calib_proximity_no_near
                                    }
                                ),
                            )
                            Text(
                                text = result.advice.text(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(
                                onClick = { viewModel.applyCalibration(result) },
                                enabled = !state.applied,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(
                                        if (state.applied) R.string.calib_applied else R.string.calib_apply
                                    )
                                )
                            }
                            if (result.proximityNearWhenClosed &&
                                settings.strategy != DetectionStrategy.LIGHT_PLUS_PROXIMITY
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.setStrategy(DetectionStrategy.LIGHT_PLUS_PROXIMITY) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.calib_enable_hybrid))
                                }
                            }
                            OutlinedButton(
                                onClick = { viewModel.resetCalibration() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.calib_restart))
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.dark_test_title)) {
                Text(
                    text = stringResource(R.string.dark_test_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { viewModel.runDarkTest() },
                    enabled = !probing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (probing) R.string.dark_test_running else R.string.dark_test_run
                        )
                    )
                }
                darkTest?.let { result ->
                    ThinDivider()
                    InfoRow(stringResource(R.string.dark_test_ambient), lux(result.ambient.median))
                    InfoRow(stringResource(R.string.dark_test_threshold), lux(result.closedThreshold))
                    val message = if (result.reliable) {
                        stringResource(R.string.dark_test_reliable, formatLux(result.ambient.median))
                    } else {
                        stringResource(
                            R.string.dark_test_unreliable,
                            formatLux(result.ambient.median),
                            formatLux(result.requiredAmbientLux),
                        ) + "\n" + stringResource(
                            when {
                                // Seule une reaction AVEREE au rabat justifie le mode hybride.
                                result.proximityReactsToFlap -> R.string.dark_test_prox_works
                                result.proximityProducesEvents -> R.string.dark_test_prox_no_flap
                                result.proximityAvailable -> R.string.dark_test_prox_silent
                                else -> R.string.dark_test_prox_absent
                            }
                        )
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (result.reliable) StatusColors.ok else StatusColors.warn,
                    )
                    if (!result.reliable && result.proximityReactsToFlap &&
                        settings.strategy != DetectionStrategy.LIGHT_PLUS_PROXIMITY
                    ) {
                        Button(
                            onClick = { viewModel.setStrategy(DetectionStrategy.LIGHT_PLUS_PROXIMITY) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.dark_test_switch_hybrid))
                        }
                    }
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.calib_saved)) {
                if (!settings.calibrationDone) {
                    Text(
                        stringResource(R.string.calib_none),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    InfoRow(stringResource(R.string.calib_open), lux(settings.calibrationOpenLux))
                    InfoRow(stringResource(R.string.calib_closed), lux(settings.calibrationClosedLux))
                    InfoRow(
                        stringResource(R.string.calib_separation),
                        formatPercent(settings.calibrationSeparationPercent),
                    )
                    InfoRow(
                        stringResource(R.string.calib_active_threshold),
                        lux(settings.closedLuxThreshold),
                    )
                }
            }
        }
    }
}
