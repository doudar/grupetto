package com.spop.poverlay.overlay.composables

// import androidx.compose.material.Text
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.spop.poverlay.R
import com.spop.poverlay.overlay.MetricType
import com.spop.poverlay.overlay.PowerChartFullWidth
import com.spop.poverlay.overlay.PowerChartShrunkWidth
import com.spop.poverlay.overlay.StatCard
import com.spop.poverlay.overlay.StatCardWidth
import com.spop.poverlay.ui.theme.MetricCadenceColor
import com.spop.poverlay.ui.theme.MetricCalorieColor
import com.spop.poverlay.ui.theme.MetricHeartRateColor
import com.spop.poverlay.ui.theme.MetricInclineColor
import com.spop.poverlay.ui.theme.MetricPowerColor
import com.spop.poverlay.ui.theme.MetricResistanceColor
import com.spop.poverlay.ui.theme.MetricSpeedColor
import com.spop.poverlay.util.LineChart

@Composable
fun OverlayMainContent(
        modifier: Modifier,
        rowAlignment: Alignment.Vertical,
        isTread: Boolean,
        power: String,
        rpm: String,
        currentGraph: List<Float>,
        selectedMetric: MetricType,
        resistance: String,
        speed: String,
        speedLabel: String,
        heartRate: String,
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
        totalEnergy: String,
        totalDistance: String,
        distanceUnit: String,
        avgCadence: String,
        avgResistance: String,
        maxHeartRate: String,
        avgHeartRate: String,
        showHeartRateCard: Boolean,
        incline: String,
        showInclineCard: Boolean,
        showPowerCard: Boolean,
        showCadenceCard: Boolean,
        showResistanceCard: Boolean,
        onMetricSelected: (MetricType) -> Unit,
        onSpeedUnitClicked: () -> Unit,
        onChartClicked: () -> Unit
) {
    var shrinkChart by remember { mutableStateOf(false) }

    val chartColor =
            when (selectedMetric) {
                MetricType.POWER -> MetricPowerColor
                MetricType.CADENCE -> MetricCadenceColor
                MetricType.RESISTANCE -> MetricResistanceColor
                MetricType.SPEED -> MetricSpeedColor
                MetricType.HEART_RATE -> MetricHeartRateColor
                MetricType.INCLINE -> MetricInclineColor
            }

    // Define minimum thresholds to prevent chart from getting too compressed at low values
    // Use session max if higher than threshold, otherwise use threshold
    val chartMaxValue =
            when (selectedMetric) {
                MetricType.POWER -> maxOf(250f, maxPowerValue)
                MetricType.CADENCE -> maxOf(160f, maxCadenceValue)
                MetricType.RESISTANCE -> maxOf(100f, maxResistanceValue)
                MetricType.SPEED -> maxOf(40f, maxSpeedValue)
                MetricType.HEART_RATE -> 220f
                MetricType.INCLINE -> 15f
            }

    val statCardModifier = Modifier.requiredWidth(StatCardWidth)

    // The live chart plots the currently-selected metric. Long-press shrinks it.
    // Shared between the bike and tread layouts so the tap-to-select behavior and
    // shrink state are identical in both.
    val chart = @Composable {
        val chartWidth =
                if (shrinkChart) {
                    PowerChartShrunkWidth
                } else {
                    PowerChartFullWidth
                }
        val chartPadding =
                if (shrinkChart) {
                    15.dp
                } else {
                    8.dp
                }

        Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                    onTap = { onChartClicked() },
                                    onLongPress = { shrinkChart = !shrinkChart }
                            )
                        }
        ) {
            LineChart(
                    data = currentGraph,
                    maxValue = chartMaxValue,
                    pauseChart = pauseChart,
                    modifier =
                            Modifier.requiredWidth(chartWidth)
                                    .requiredHeight(90.dp)
                                    .padding(horizontal = chartPadding),
                    fillColor = chartColor.copy(alpha = 0.6f),
                    lineColor = chartColor,
            )
        }
    }

    val powerCard = @Composable {
        StatCard(
                name = "Power",
                value = power,
                unit = "watts",
                modifier = statCardModifier,
                iconDrawable = R.drawable.ic_power,
                maxValue = maxPower,
                totalValue = totalEnergy,
                totalUnit = "kJ",
                color = MetricPowerColor,
                onClick = { onMetricSelected(MetricType.POWER) }
        )
    }

    val cadenceCard = @Composable {
        StatCard(
                name = "Cadence",
                value = rpm,
                unit = "rpm",
                modifier = statCardModifier,
                iconDrawable = R.drawable.ic_cadence,
                maxValue = maxCadence,
                totalValue = avgCadence,
                totalUnit = "avg",
                color = MetricCadenceColor,
                onClick = { onMetricSelected(MetricType.CADENCE) }
        )
    }

    val resistanceCard = @Composable {
        StatCard(
                name = "Resistance",
                value = resistance,
                unit = "%",
                modifier = statCardModifier,
                iconDrawable = R.drawable.ic_resistance,
                maxValue = maxResistance,
                totalValue = avgResistance,
                totalUnit = "avg",
                color = MetricResistanceColor,
                onClick = { onMetricSelected(MetricType.RESISTANCE) }
        )
    }

    val speedCard = @Composable {
        StatCard(
                name = "Speed",
                value = speed,
                unit = speedLabel,
                modifier = statCardModifier,
                iconDrawable = R.drawable.ic_speed,
                maxValue = maxSpeed,
                totalValue = totalDistance,
                totalUnit = distanceUnit,
                color = MetricSpeedColor,
                onClick = { onMetricSelected(MetricType.SPEED) },
                onUnitClick = onSpeedUnitClicked
        )
    }

    val inclineCard = @Composable {
        // Placeholder icon: reuses ic_speed until a dedicated incline/slope
        // drawable is added (follow-up).
        StatCard(
                name = "Incline",
                value = incline,
                unit = "%",
                modifier = statCardModifier,
                iconDrawable = R.drawable.ic_speed,
                maxValue = "",
                color = MetricInclineColor,
                onClick = { onMetricSelected(MetricType.INCLINE) }
        )
    }

    val heartRateCard = @Composable {
        StatCard(
                name = "Heart Rate",
                value = heartRate,
                unit = "bpm",
                modifier = statCardModifier,
                iconDrawable = R.drawable.ic_hrm,
                maxValue = maxHeartRate,
                totalValue = avgHeartRate,
                totalUnit = "avg",
                color = MetricHeartRateColor,
                onClick = { onMetricSelected(MetricType.HEART_RATE) }
        )
    }

    val caloriesCard = @Composable {
        StatCard(
                "Calories",
                calories,
                color = MetricCalorieColor,
                unit = "kcal",
                maxValue = "",
                modifier = statCardModifier,
                iconDrawable = R.drawable.ic_calories
        )
    }

    Row(
            modifier = modifier,
            verticalAlignment = rowAlignment,
            horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (isTread) {
            // Mirror the Peloton Tread's physical controls: incline on the left,
            // the live graph in the middle, speed on the right. Calories (and HR
            // when a monitor is connected) trail the arrangement so they don't
            // break the incline-left / speed-right mirroring.
            if (showInclineCard) {
                inclineCard()
            }
            chart()
            speedCard()
            if (showHeartRateCard) {
                heartRateCard()
            }
            caloriesCard()
        } else {
            // Bike layout: unchanged.
            if (showPowerCard) {
                powerCard()
            }
            if (showCadenceCard) {
                cadenceCard()
            }
            chart()
            if (showResistanceCard) {
                resistanceCard()
            }
            speedCard()
            if (showInclineCard) {
                inclineCard()
            }
            if (showHeartRateCard) {
                heartRateCard()
            }
            caloriesCard()
        }
    }
}
