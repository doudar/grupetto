package com.spop.poverlay.util

import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.spop.poverlay.overlay.OverlaySensorViewModel
import com.yabu.livechart.model.DataPoint
import com.yabu.livechart.model.Dataset
import com.yabu.livechart.view.LiveChart
import com.yabu.livechart.view.LiveChartStyle

@Composable
fun LineChart(
    data: Collection<Number>,
    maxValue: Float,
    minValue: Float = 0f,
    modifier: Modifier,
    pauseChart: Boolean,
    fillColor: Color = Color.LightGray,
    lineColor: Color = Color.DarkGray,
    zoneThresholds: List<Float?>? = null,
    zoneColors: List<Color>? = null,
) {
    // Use key to force recreation when colors, maxValue, or data source change
    key(lineColor, fillColor, maxValue, minValue, data, zoneThresholds) {
        Box(modifier = modifier) {
            val thresholds = zoneThresholds
            val rangeMax = maxValue - minValue
            if (rangeMax > 0f && thresholds != null && thresholds.isNotEmpty()) {
                val boundsWithZone = mutableListOf<Pair<Float, Int>>()
                thresholds.getOrNull(0)?.let { if (it > 0f) boundsWithZone.add(it to 1) }
                thresholds.getOrNull(1)?.let { if (it > 0f) boundsWithZone.add(it to 2) }
                thresholds.getOrNull(2)?.let { if (it > 0f) boundsWithZone.add(it to 3) }
                thresholds.getOrNull(3)?.let { if (it > 0f) boundsWithZone.add(it to 4) }

                val ordered = boundsWithZone
                    .distinctBy { it.first }
                    .sortedBy { it.first }
                    .filter { it.first in minValue..maxValue }

                if (ordered.isNotEmpty()) {
                    val colors = zoneColors ?: listOf(
                        Color(0xFF4DA3FF),
                        Color(0xFF3DDC84),
                        Color(0xFFFFEB3B),
                        Color(0xFFFF6D00),
                        Color(0xFFFF5C5C)
                    )
                    val bounds = listOf(minValue to 0) + ordered + listOf(maxValue to 5)
                    val zoneCount = bounds.size - 1
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        for (i in 0 until zoneCount) {
                            val lower = bounds[i].first.coerceIn(minValue, maxValue)
                            val upper = bounds[i + 1].first.coerceIn(minValue, maxValue)
                            val lowerZone = bounds[i].second + 1
                            val upperZone = bounds[i + 1].second
                            val yTop = size.height * (1f - ((upper - minValue) / rangeMax))
                            val yBottom = size.height * (1f - ((lower - minValue) / rangeMax))
                            val color = when {
                                lowerZone == upperZone -> colors[lowerZone - 1]
                                upperZone <= 2 -> colors[1]
                                lowerZone >= 4 -> colors[3]
                                lowerZone <= 3 && upperZone >= 3 -> colors[2]
                                else -> colors[2]
                            }
                            drawRect(
                                color = color.copy(alpha = 0.18f),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, yTop),
                                size = androidx.compose.ui.geometry.Size(size.width, yBottom - yTop)
                            )
                        }
                        // Draw thin separators between zones
                        val lineColor = Color.White.copy(alpha = 0.35f)
                        ordered.forEach { threshold ->
                            val y = size.height * (1f - ((threshold.first - minValue) / rangeMax))
                            drawLine(
                                color = lineColor,
                                start = androidx.compose.ui.geometry.Offset(0f, y),
                                end = androidx.compose.ui.geometry.Offset(size.width, y),
                                strokeWidth = 1f
                            )
                        }
                    }
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    LiveChart(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        clipChildren = false
                    }.setLiveChartStyle(LiveChartStyle().apply {
                        textColor = android.graphics.Color.BLUE
                        textHeight = 30f
                        mainColor = lineColor.toArgb()
                        mainFillColor = fillColor.toArgb()
                        baselineColor = android.graphics.Color.BLUE
                        pathStrokeWidth = 4f
                        baselineStrokeWidth = 6f
                        mainCornerRadius = 40f
                        secondColor = android.graphics.Color.TRANSPARENT
                    }).disableTouchOverlay()

                },
                update = { view ->
                    if (!pauseChart) {
                        view.setDataset(Dataset(data.mapIndexed { index, value ->
                            val clamped = value.toFloat().coerceIn(minValue, maxValue)
                            val normalized = (clamped - minValue).coerceIn(1f, rangeMax)
                            DataPoint(index.toFloat(), normalized)
                        }.toMutableList()))
                            .setSecondDataset(
                                //There's no way to set explicit bounds with this graphing library
                                //This hidden dataset forces the graph to cover the given bounds
                                Dataset(
                                    mutableListOf(
                                        DataPoint(0f, 0f),
                                        DataPoint(
                                            OverlaySensorViewModel.GraphMaxDataPoints.toFloat(),
                                            rangeMax
                                        )
                                    )
                                )
                            )
                            .drawFill(withGradient = true)
                            .drawDataset()
                    }
                }
            )
        }
    }
}
