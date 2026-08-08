package com.fliplock.cover.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fliplock.cover.R
import com.fliplock.cover.calibration.CalibrationAdvice
import com.fliplock.cover.calibration.CalibrationQuality
import com.fliplock.cover.detection.CoverState
import com.fliplock.cover.detection.DetectionStrategy
import com.fliplock.cover.runtime.LockOrigin
import java.util.Locale

@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(containerColor, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
fun BigValue(value: String, caption: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = caption,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontSize = 52.sp,
            lineHeight = 60.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
fun MonoText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

@Composable
fun ThinDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

fun formatLux(value: Float?): String =
    if (value == null) "—" else String.format(Locale.getDefault(), "%.1f", value)

fun formatPercent(value: Float): String =
    String.format(Locale.getDefault(), "%.1f %%", value)

// --------------------------------------------------------------------------
// Traduction des enums métier.
//
// Les couches detection / calibration ne produisent aucun texte : elles
// renvoient un état, et c'est ici qu'il devient une chaîne localisée.
// --------------------------------------------------------------------------

@Composable
fun DetectionStrategy.label(): String = stringResource(
    when (this) {
        DetectionStrategy.AUTO -> R.string.strategy_auto
        DetectionStrategy.LIGHT_ONLY -> R.string.strategy_light_only
        DetectionStrategy.LIGHT_PLUS_PROXIMITY -> R.string.strategy_light_proximity
    }
)

@Composable
fun CoverState.label(): String = stringResource(
    when (this) {
        CoverState.OPEN -> R.string.cover_open
        CoverState.CLOSING -> R.string.cover_closing
        CoverState.COOLDOWN -> R.string.cover_cooldown
        CoverState.SCREEN_OFF -> R.string.cover_screen_off
        CoverState.MONITORING_OFF -> R.string.cover_monitoring_off
    }
)

@Composable
fun CalibrationQuality.label(): String = stringResource(
    when (this) {
        CalibrationQuality.EXCELLENT -> R.string.calib_quality_excellent
        CalibrationQuality.GOOD -> R.string.calib_quality_good
        CalibrationQuality.WEAK -> R.string.calib_quality_weak
        CalibrationQuality.POOR -> R.string.calib_quality_poor
    }
)

@Composable
fun CalibrationAdvice.text(): String = stringResource(
    when (this) {
        CalibrationAdvice.LIGHT_ENOUGH -> R.string.calib_advice_light_enough
        CalibrationAdvice.RETRY_BRIGHTER_WITH_PROXIMITY -> R.string.calib_advice_retry_brighter_prox
        CalibrationAdvice.RETRY_BRIGHTER -> R.string.calib_advice_retry_brighter
        CalibrationAdvice.POOR_WITH_PROXIMITY -> R.string.calib_advice_poor_prox
        CalibrationAdvice.POOR -> R.string.calib_advice_poor
    }
)

@Composable
fun LockOrigin.label(): String = stringResource(
    when (this) {
        LockOrigin.DETECTION -> R.string.origin_detection
        LockOrigin.TEST_BUTTON -> R.string.origin_test_button
    }
)

@Composable
fun yesNo(value: Boolean): String =
    stringResource(if (value) R.string.value_yes else R.string.value_no)

@Composable
fun lux(value: Float?): String = stringResource(R.string.unit_lux, formatLux(value))
