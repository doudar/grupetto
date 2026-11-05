package com.spop.poverlay.calibration

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Finite transitions respect Android's animation-duration scale. No perpetual shimmer, clock,
 * state mutation or interpolated calibration values: only pixels crossfade between cell buckets. */
@Composable
internal fun CalibrationStreamBucket(capture: CalibrationUiState, recording: Boolean) {
    val progress = capture.progress
    val label = when {
        !recording || !capture.enabled -> "Paused"
        capture.resting -> "Rest — resume when ready"
        capture.frame == null -> "Waiting for bike + pedals"
        progress.phase == CapturePhase.Settling -> "Settling — ${progress.settleRemaining}s"
        else -> progress.phase.cue
    }
    val seconds = progress.bucketSeconds.coerceIn(0, CalibrationCellSeconds)
    val cell = progress.cell
    val description = (cell?.let { "Cell ${it / 16 + 1}, ${45 + it % 16 * 5} rpm" } ?: "No active cell") +
        ": $seconds of 100 accepted seconds. $label"
    Column(Modifier.fillMaxWidth().semantics {
        contentDescription = description
        progressBarRangeInfo = ProgressBarRangeInfo(seconds / 100f, 0f..1f)
    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = captureProgressColor(progress))
            Text("$seconds / 100 s", fontSize = 13.sp, color = Color(0xFFB6C7D5))
        }
        // Cell identity controls crossfade; growth within that bucket uses a gentle finite fill.
        Crossfade(targetState = cell, animationSpec = tween(450), label = "Collection cell transition") { displayedCell ->
            val target = if (displayedCell == cell) seconds / 100f else 0f
            val fill by animateFloatAsState(targetValue = target, animationSpec = tween(650), label = "Accepted seconds")
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(Color(0xFF263949), cornerRadius = radius)
                val width = size.width * fill.coerceIn(0f, 1f)
                if (width > 0) {
                    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF318D9D), Color(0xFF6EE7B7))),
                        size = Size(width, size.height), cornerRadius = radius)
                    // A restrained light edge gives each incoming second a tangible finish.
                    drawLine(Color(0xFFD0F8E9).copy(alpha = .5f), Offset(width, 2.dp.toPx()),
                        Offset(width, size.height - 2.dp.toPx()), 1.dp.toPx())
                }
            }
        }
    }
}
