package com.spop.poverlay.util

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.spop.poverlay.overlay.OverlaySensorViewModel
import com.spop.poverlay.util.livechart.LiveChartAttributes
import com.yabu.livechart.model.DataPoint
import com.yabu.livechart.model.Dataset
import com.yabu.livechart.view.LiveChart
import com.yabu.livechart.view.LiveChartStyle

@Composable
fun LineChartFull(

    data: Collection<Number>,
    maxValue: Float,
    modifier: Modifier,
    pauseChart: Boolean,
    fillColor: Color = Color.LightGray,
    lineColor: Color = Color.DarkGray,
    minValue: Float = 0f,
    average: Float = 0f,


) {
    val graph = remember { data }
    var lcs :LiveChartStyle = LiveChartStyle()
    val a: LiveChartAttributes


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
                baselineColor = android.graphics.Color.LTGRAY
                pathStrokeWidth = 4f
                baselineStrokeWidth = 2f

                mainCornerRadius = 40f
                secondColor = android.graphics.Color.TRANSPARENT



            }).disableTouchOverlay()

        },
        update = { view ->
            if (!pauseChart) {
                try {
                    view.setDataset(Dataset(graph.mapIndexed { index, value ->
                        //Start values at 1f to keep line visible at all times
                        DataPoint(index.toFloat(), value.toFloat())
                    }.toMutableList())).drawYBounds()
                        // Draws a customizable base line from the first point of the dataset or manually set a data point
                        .drawBaseline()
                        .setBaselineManually(average)
                        .drawFill(withGradient = true)
                        .drawDataset()
                }
                catch (e: Exception) {}
            }
        }

    )
}
