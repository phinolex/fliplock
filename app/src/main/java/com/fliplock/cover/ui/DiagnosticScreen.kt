package com.fliplock.cover.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fliplock.cover.R
import com.fliplock.cover.sensors.SensorDescriptor
import com.fliplock.cover.sensors.SensorProbeResult
import com.fliplock.cover.sensors.SensorTypeNames
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HISTORY_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

@Composable
fun DiagnosticScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lastLight by viewModel.lastLight.collectAsStateWithLifecycle()
    val lastProximity by viewModel.lastProximity.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val trace by viewModel.detectionTrace.collectAsStateWithLifecycle()
    val probes by viewModel.probeState.collectAsStateWithLifecycle()
    val allProbes by viewModel.allProbes.collectAsStateWithLifecycle()
    val probing by viewModel.probing.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(title = null) {
                BigValue(
                    value = lux(lastLight?.lux),
                    caption = stringResource(R.string.diag_current_light),
                    color = if (snapshot.candidate) StatusColors.ok else MaterialTheme.colorScheme.onSurface,
                )
                if (snapshot.candidate) {
                    Text(
                        text = stringResource(R.string.diag_possible_close),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = StatusColors.ok,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ThinDivider()
                InfoRow(stringResource(R.string.diag_baseline), lux(snapshot.baselineLux))
                InfoRow(
                    stringResource(R.string.diag_baseline_samples),
                    snapshot.baselineSampleCount.toString(),
                )
                InfoRow(
                    stringResource(R.string.diag_drop),
                    "${formatPercent(snapshot.dropPercent)} / ${lux(snapshot.absoluteDropLux)}",
                )
                InfoRow(stringResource(R.string.diag_engine_state), snapshot.state.name)
                InfoRow(stringResource(R.string.diag_decision), snapshot.reason)
                lastLight?.let {
                    InfoRow(
                        stringResource(R.string.diag_last_reading),
                        HISTORY_TIME.format(Instant.ofEpochMilli(it.wallClockMs)),
                    )
                    InfoRow(
                        stringResource(R.string.diag_sensor_timestamp),
                        "${it.sensorTimestampNs} ns",
                    )
                }
                Text(
                    text = stringResource(R.string.diag_preview_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.diag_history)) {
                if (history.isEmpty()) {
                    Text(
                        stringResource(R.string.diag_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val candidateTag = stringResource(R.string.diag_history_candidate)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(8.dp),
                    ) {
                        history.takeLast(14).reversed().forEach { entry ->
                            MonoText(
                                "${HISTORY_TIME.format(Instant.ofEpochMilli(entry.wallClockMs))} — " +
                                    "${String.format(Locale.US, "%.1f", entry.lux)} lux" +
                                    if (entry.candidate) "  $candidateTag" else ""
                            )
                        }
                    }
                }
                ThinDivider()
                MonoText("DROP = ${formatPercent(snapshot.dropPercent)}")
                MonoText("CANDIDATE = ${yesNo(snapshot.candidate)}")
                MonoText("CONFIRMED = ${yesNo(trace.lastConfirmedAtMs > 0L)}")
                MonoText("LOCK REQUESTED = ${yesNo(trace.lastLockRequestAtMs > 0L)}")
                trace.lastCancelReason?.let {
                    MonoText("${stringResource(R.string.diag_last_rejection)} = $it")
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.diag_probe_section)) {
                Text(
                    text = stringResource(R.string.diag_probe_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
                ProbeBlock("TYPE_LIGHT", probes.light)
                ThinDivider()
                ProbeBlock("TYPE_PROXIMITY", probes.proximity)
                lastProximity?.let {
                    InfoRow(
                        stringResource(R.string.diag_proximity_value),
                        "${formatLux(it.distanceCm)} (${
                            stringResource(
                                if (it.near) R.string.proximity_near else R.string.proximity_far
                            )
                        })",
                    )
                }
                Button(
                    onClick = { viewModel.probeCoreSensors() },
                    enabled = !probing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (probing) R.string.diag_probe_running else R.string.diag_probe_restart
                        )
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.probeAllSensors() },
                    enabled = !probing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.diag_probe_all))
                }
            }
        }

        item {
            SectionCard(
                title = stringResource(R.string.diag_sensor_list, viewModel.sensorDescriptors.size)
            ) {
                Text(
                    text = stringResource(R.string.diag_sensor_list_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        items(viewModel.sensorDescriptors) { descriptor ->
            SensorCard(
                descriptor,
                allProbes.firstOrNull {
                    it.descriptor?.name == descriptor.name && it.descriptor.type == descriptor.type
                },
            )
        }

        item {
            SectionCard(title = stringResource(R.string.diag_log, logs.size)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(8.dp),
                ) {
                    if (logs.isEmpty()) {
                        MonoText(stringResource(R.string.diag_log_empty))
                    } else {
                        logs.takeLast(40).reversed().forEach { MonoText(it.format()) }
                    }
                }
                Text(
                    text = stringResource(R.string.diag_log_local),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { viewModel.copyDiagnostic(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_copy_diagnostic))
                }
                OutlinedButton(
                    onClick = { viewModel.clearLogs() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_clear_logs))
                }
            }
        }
    }
}

@Composable
private fun ProbeBlock(title: String, probe: SensorProbeResult) {
    Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    InfoRow(stringResource(R.string.diag_probe_declared), yesNo(probe.declared))
    InfoRow(stringResource(R.string.diag_probe_accepted), yesNo(probe.registrationAccepted))
    InfoRow(stringResource(R.string.diag_probe_events), yesNo(probe.eventsReceived), emphasize = true)
    InfoRow(stringResource(R.string.diag_probe_count), probe.eventCount.toString())
    InfoRow(stringResource(R.string.diag_probe_verdict), probe.verdict)
    probe.descriptor?.let { descriptor ->
        InfoRow(stringResource(R.string.label_name), descriptor.name)
        InfoRow(stringResource(R.string.label_vendor), descriptor.vendor)
        InfoRow(
            stringResource(R.string.label_max_range),
            String.format(Locale.US, "%.2f", descriptor.maximumRange),
        )
        InfoRow(
            stringResource(R.string.label_resolution),
            String.format(Locale.US, "%.4f", descriptor.resolution),
        )
        InfoRow(
            stringResource(R.string.label_power),
            String.format(Locale.US, "%.3f mA", descriptor.power),
        )
    }
}

@Composable
private fun SensorCard(descriptor: SensorDescriptor, probe: SensorProbeResult?) {
    val relevant = SensorTypeNames.isPotentiallyRelevant(descriptor)
    SectionCard(title = null) {
        Text(
            text = descriptor.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (relevant) StatusColors.ok else MaterialTheme.colorScheme.onSurface,
        )
        if (relevant) {
            Text(
                text = stringResource(R.string.diag_sensor_relevant),
                style = MaterialTheme.typography.labelSmall,
                color = StatusColors.ok,
            )
        }
        MonoText(
            "type=${descriptor.type} (${descriptor.stringType})\n" +
                "vendor=${descriptor.vendor} version=${descriptor.version}\n" +
                "range=${String.format(Locale.US, "%.3f", descriptor.maximumRange)} " +
                "resolution=${String.format(Locale.US, "%.4f", descriptor.resolution)} " +
                "power=${String.format(Locale.US, "%.3f", descriptor.power)} mA\n" +
                "mode=${descriptor.reportingMode} wakeUp=${descriptor.isWakeUpSensor}" +
                if (probe != null) {
                    "\nprobe: registration=${probe.registrationAccepted} " +
                        "events=${probe.eventsReceived} count=${probe.eventCount} " +
                        "last=${probe.lastValue?.let { String.format(Locale.US, "%.3f", it) } ?: "-"}"
                } else {
                    ""
                }
        )
    }
}
