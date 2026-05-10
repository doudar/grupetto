package com.spop.poverlay.util

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.spop.poverlay.overlay.OverlaySensorViewModel
import com.yabu.livechart.model.DataPoint
import com.yabu.livechart.model.Dataset
import com.yabu.livechart.view.LiveChartStyle
import com.spop.poverlay.util.livechart.LiveChart
import com.spop.poverlay.util.livechart.ZoneBand

@Composable
fun LineChart(
    data: Collection<Number>,
    maxValue: Float,
    modifier: Modifier,
    pauseChart: Boolean,
    fillColor: Color = Color.LightGray,
    lineColor: Color = Color.DarkGray,
    zoneBands: List<ZoneBand>? = null,
) {
    // Exclude lineColor/fillColor from key when zone bands are set — coloring is per-segment
    val keyColors = if (zoneBands != null) Unit else Pair(lineColor, fillColor)
    key(keyColors, maxValue, data, zoneBands) {
        AndroidView(
            modifier = modifier,
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
            }).disableTouchOverlay().also { chart ->
                zoneBands?.let { chart.setZoneBands(it) }
            }

        },
        update = { view ->
            if (!pauseChart) {
                view.setDataset(Dataset(data.mapIndexed { index, value ->
                    //Start values at 1f to keep line visible at all times
                    DataPoint(index.toFloat(), value.toFloat().coerceIn(1f, maxValue))
                }.toMutableList()))
                    .setSecondDataset(
                        //There's no way to set explicit bounds with this graphing library
                        //This hidden dataset forces the graph to cover the given bounds
                        Dataset(
                            mutableListOf(
                                DataPoint(0f, 0f),
                                DataPoint(
                                    OverlaySensorViewModel.GraphMaxDataPoints.toFloat(),
                                    maxValue
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
