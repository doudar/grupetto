package com.spop.poverlay.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Leave space around the dialog and for its header, including the system navigation bar. */
@Composable
internal fun calibrationContentHeight() =
    (LocalConfiguration.current.screenHeightDp.dp * .82f - 88.dp).coerceIn(200.dp, 440.dp)

data class CalibrationLiveMetrics(val bikeWatts: Double? = null, val pedalWatts: Double? = null,
    val rpm: Double? = null, val resistance: Double? = null, val correctedWatts: Double? = null)

@Composable
internal fun CalibrationMetrics(metrics: CalibrationLiveMetrics, showCorrected: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        RideMetric("BIKE", metrics.bikeWatts, "W", Color(0xFF60A5FA))
        if (showCorrected) RideMetric("CORRECTED", metrics.correctedWatts, "W", Color(0xFFFBBF24))
        RideMetric("PEDALS", metrics.pedalWatts, "W", Color(0xFF6EE7B7))
        RideMetric("RPM", metrics.rpm, "", Color(0xFF6EE7B7))
        RideMetric("RESISTANCE", metrics.resistance, "", Color(0xFF60A5FA))
    }
}

@Composable
private fun RideMetric(label: String, value: Double?, unit: String, color: Color) {
    Column {
        Text(label, color = Color(0xFF9EAEC0), fontSize = 10.sp, lineHeight = 14.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value?.takeIf { it.isFinite() }?.roundToInt()?.toString() ?: "-",
                color = color, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) Text(unit, Modifier.padding(start = 2.dp, bottom = 4.dp),
                color = Color(0xFF9EAEC0), fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

internal fun captureProgressColor(progress: CaptureProgress): Color = when {
    progress.phase == CapturePhase.Saved -> Color(0xFF34D399)
    progress.phase == CapturePhase.Settling -> Color(0xFFFB923C)
    progress.phase == CapturePhase.Holding && progress.remaining <= 2 -> Color(0xFFFDE047)
    progress.phase == CapturePhase.Holding -> Color(0xFFFBBF24)
    else -> Color(0xFF9EAEC0)
}

/** Show the source failure verbatim, including its recovery instruction, in either view. */
@Composable
internal fun CaptureErrorNotice(capture: CalibrationUiState) {
    val error = capture.error ?: return
    Text(error, modifier = Modifier.fillMaxWidth()
        .background(Color(0xFF412E18), RoundedCornerShape(8.dp))
        .semantics { liveRegion = LiveRegionMode.Polite }
        .padding(12.dp), color = Color(0xFFFBBF24), fontSize = 16.sp,
        lineHeight = 22.sp, fontWeight = FontWeight.Bold)
}

@Composable
internal fun CaptureHoldFeedback(capture: CalibrationUiState, recording: Boolean) {
    if (capture.error != null) return // The prominent notice carries the actual reason.
    val collecting = recording && capture.enabled && !capture.resting && capture.frame != null &&
        capture.pedalWatts != null && capture.progress.phase in listOf(CapturePhase.Settling, CapturePhase.Holding, CapturePhase.Saved)
    val progress = capture.progress
    val color = if (collecting) captureProgressColor(progress) else Color(0xFF9EAEC0)
    val label = when {
        !recording || !capture.enabled -> "Paused"
        capture.resting -> "Rest · resume in ride view"
        capture.frame == null || capture.pedalWatts == null -> "Waiting for bike + pedals"
        progress.phase == CapturePhase.Saved -> "Saved"
        progress.phase == CapturePhase.Settling -> "Settle · ${progress.settleRemaining}s"
        progress.phase == CapturePhase.Holding -> "Hold · ${progress.remaining}s"
        else -> progress.phase.cue
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, fontSize = 16.sp, lineHeight = 22.sp, color = color, fontWeight = FontWeight.Bold)
        Canvas(Modifier.weight(1f).height(10.dp).semantics { contentDescription = label }) {
            val step = size.width / progress.totalSeconds.coerceAtLeast(1)
            repeat(progress.totalSeconds.coerceAtLeast(1)) { i ->
                drawRect(if (collecting && i < progress.seconds) color else Color(0xFF304255),
                    Offset(i * step, 0f), Size((step - 3.dp.toPx()).coerceAtLeast(1f), size.height))
            }
        }
    }
}
