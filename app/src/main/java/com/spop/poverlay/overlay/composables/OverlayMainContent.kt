package com.spop.poverlay.overlay.composables

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
//import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.R
import com.spop.poverlay.overlay.MetricType
import com.spop.poverlay.overlay.PowerChartFullWidth
import com.spop.poverlay.overlay.PowerChartShrunkWidth
import com.spop.poverlay.overlay.StatCard
import com.spop.poverlay.overlay.StatCardWidth
import com.spop.poverlay.ui.theme.MetricCadenceColor
import com.spop.poverlay.ui.theme.MetricCalorieColor
import com.spop.poverlay.ui.theme.MetricPowerColor
import com.spop.poverlay.ui.theme.MetricResistanceColor
import com.spop.poverlay.ui.theme.MetricSpeedColor
import com.spop.poverlay.util.LineChart


@Composable
fun OverlayMainContent(
    modifier: Modifier,
    rowAlignment: Alignment.Vertical,
    power: String,
    rpm: String,
    heartRate: String,
    heartAvg: String,
    heartPeak: String,
    heartRateZones: List<Int?>?,
    showCalories: Boolean,
    showHeartAvailable: Boolean,
    onToggleCalories: () -> Unit,
    currentGraph: List<Float>,
    selectedMetric: MetricType,
    resistance: String,
    speed: String,
    speedLabel: String,
    calories: String,
    pauseChart: Boolean,
    maxPower: String,
    maxCadence: String,
    maxResistance: String,
    maxSpeed: String,
    maxPowerValue: Float,
    maxCadenceValue: Float,
    maxResistanceValue: Float,
    maxSpeedValue: Float,
    maxHeartValue: Float,
    totalEnergy: String,
    totalDistance: String,
    distanceUnit: String,
    avgCadence: String,
    avgResistance: String,
    onMetricSelected: (MetricType) -> Unit,
    onSpeedUnitClicked: () -> Unit,
    onChartClicked: () -> Unit
) {
    var shrinkChart by remember { mutableStateOf(false) }

    val chartColor = when (selectedMetric) {
        MetricType.POWER -> MetricPowerColor
        MetricType.CADENCE -> MetricCadenceColor
        MetricType.RESISTANCE -> MetricResistanceColor
        MetricType.SPEED -> MetricSpeedColor
        MetricType.HEART -> Color.Red
    }

    val chartLabel = when (selectedMetric) {
        MetricType.POWER -> "Power"
        MetricType.CADENCE -> "Cadence"
        MetricType.RESISTANCE -> "Resistance"
        MetricType.SPEED -> "Speed"
        MetricType.HEART -> "Heart"
    }

    // Define minimum thresholds to prevent chart from getting too compressed at low values
    // Use session max if higher than threshold, otherwise use threshold
    val heartZoneThresholds = if (selectedMetric == MetricType.HEART) {
        heartRateZones?.map { it?.toFloat() }
    } else {
        null
    }
    val heartZoneDefined = heartZoneThresholds?.filterNotNull()?.sorted()
    val heartZoneMin = heartZoneDefined?.firstOrNull()?.let { maxOf(0f, it - 10f) }
    val heartZoneMax = heartZoneDefined?.lastOrNull()?.let { it + 10f }

    val chartMaxValue = when (selectedMetric) {
        MetricType.POWER -> maxOf(250f, maxPowerValue)
        MetricType.CADENCE -> maxOf(160f, maxCadenceValue)
        MetricType.RESISTANCE -> maxOf(100f, maxResistanceValue)
        MetricType.SPEED -> maxOf(40f, maxSpeedValue)
        MetricType.HEART -> heartZoneMax ?: maxOf(200f, maxHeartValue)
    }
    val chartMinValue = when (selectedMetric) {
        MetricType.HEART -> heartZoneMin ?: 0f
        else -> 0f
    }

    Row(
        modifier = modifier,
        verticalAlignment = rowAlignment,
        horizontalArrangement = Arrangement.Start,
    ) {
        val statCardFullModifier = Modifier.requiredWidth(StatCardWidth)
        val statCardCollapsedModifier = Modifier.requiredWidth(48.dp)

        StatCard(
            name = "Power",
            value = power,
            unit = "watts",
            modifier = statCardFullModifier,
            iconDrawable = R.drawable.ic_power,
            maxValue = maxPower,
            totalValue = totalEnergy,
            totalUnit = "kJ",
            color = MetricPowerColor,
            onClick = { onMetricSelected(MetricType.POWER) }
        )

        StatCard(
            name = "Cadence",
            value = rpm,
            unit = "rpm",
            modifier = statCardFullModifier,
            iconDrawable = R.drawable.ic_cadence,
            maxValue = maxCadence,
            totalValue = avgCadence,
            totalUnit = "avg",
            color = MetricCadenceColor,
            onClick = { onMetricSelected(MetricType.CADENCE) }
        )

        // heart stat will be shown just left of Calories when available

        val chartWidth = if (shrinkChart) {
            PowerChartShrunkWidth
        } else {
            PowerChartFullWidth
        }
        val chartPadding = if (shrinkChart) {
            15.dp
        } else {
            8.dp
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onChartClicked() },
                        onLongPress = { shrinkChart = !shrinkChart }
                    )
                }
        ) {
           /* Text(
                text = chartLabel,
                color = chartColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )*/
            LineChart(
                data = currentGraph,
                maxValue = chartMaxValue,
                minValue = chartMinValue,
                pauseChart = pauseChart,
                modifier = Modifier
                    .requiredWidth(chartWidth)
                    .requiredHeight(90.dp)
                    .padding(horizontal = chartPadding),
                fillColor = chartColor.copy(alpha = 0.6f),
                lineColor = chartColor,
                zoneThresholds = heartZoneThresholds,
            )
        }

        StatCard(
            name = "Resistance",
            value = resistance,
            unit = "%",
            modifier = statCardFullModifier,
            iconDrawable = R.drawable.ic_resistance,
            maxValue = maxResistance,
            totalValue = avgResistance,
            totalUnit = "avg",
            color = MetricResistanceColor,
            onClick = { onMetricSelected(MetricType.RESISTANCE) }
        )

        StatCard(
            name = "Speed",
            value = speed,
            unit = speedLabel,
            modifier = statCardFullModifier,
            iconDrawable = R.drawable.ic_speed,
            maxValue = maxSpeed,
            totalValue = totalDistance,
            totalUnit = distanceUnit,
            color = MetricSpeedColor,
            onClick = { onMetricSelected(MetricType.SPEED) },
            onUnitClick = onSpeedUnitClicked
        )
        // Calories/Heart area: heart (if any) then calories so calories stays far right
        if (showHeartAvailable) {
            StatCard(
                name = "Heart",
                value = heartRate,
                unit = "bpm",
                modifier = statCardFullModifier,
                iconDrawable = R.drawable.ic_heart,
                maxValue = heartPeak,
                totalValue = heartAvg,
                totalUnit = "avg",
                color = Color.Red,
                onClick = { onMetricSelected(MetricType.HEART) }
            )
        }
        if (showCalories) {
            StatCard(
                name = "Calories",
                value = calories,
                unit = "kcal",
                modifier = statCardFullModifier,
                iconDrawable = R.drawable.ic_calories,
                maxValue = null,
                totalValue = null,
                totalUnit = null,
                color = MetricCalorieColor,
                onClick = onToggleCalories
            )
        } else {
            // collapsed calories: show only icon clickable (fills slot)
            Box(
                contentAlignment = Alignment.Center,
                modifier = statCardCollapsedModifier.clickable { onToggleCalories() }
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_calories),
                    contentDescription = "Calories",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}


