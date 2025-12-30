package com.spop.poverlay.util


import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

import com.spop.poverlay.util.livechart.LiveChartAttributes
import com.yabu.livechart.model.DataPoint
import com.yabu.livechart.model.Dataset
import com.yabu.livechart.view.LiveChart
import com.yabu.livechart.view.LiveChartStyle

import androidx.compose.foundation.gestures.detectDragGestures

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.unit.dp

import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.GlobalVariables

@Composable
fun LineChartMovable(

    data: Collection<Number>,
    maxValue: Float,
    modifier: Modifier,
    pauseChart: Boolean,
    fillColor: Color = Color.LightGray,
    lineColor: Color = Color.DarkGray,
    minValue: Float = 0f,
    average: Float = 0f,
    offsetx: Int = 0,
    offsety: Int = 0,
    id: Int,

    ) {
    var height: Int = 110
    var width: Int = 750



    val context = LocalContext.current
    val dbHelper = remember { DBHelper(context) }
    val globalVariables = remember { GlobalVariables(context) }
    val density = LocalDensity.current

    var offsetX by remember { mutableStateOf(offsetx.toFloat()) }
    var offsetY by remember { mutableStateOf(offsety.toFloat()) }


    LaunchedEffect(id) {
        val userId = globalVariables.UserIDGet()
        val savedPos = dbHelper.getStatCardPosition(userId, id)
        if (savedPos != null) {
            offsetX = savedPos.first.toFloat()
            offsetY = savedPos.second.toFloat()
        } else {
            offsetX = offsetx.toFloat()
            offsetY = offsety.toFloat()
        }
    }

    Box(
        modifier = Modifier
            .offset(offsetX.dp, offsetY.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val userId = globalVariables.UserIDGet()
                        dbHelper.updateStatCardPosition(
                            userId,
                            id,
                            offsetX.toInt(),
                            offsetY.toInt()
                        )
                    }
                ) { change, dragAmount ->
                    change.consume()
                    offsetX += with(density) { dragAmount.x.toDp().value }
                    offsetY += with(density) { dragAmount.y.toDp().value }
                }
            }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .width(width.dp)


        )
        {


            LineChart(

                data = data,
                maxValue = maxValue,
                modifier = modifier,
                pauseChart = pauseChart,
                fillColor = fillColor,
                lineColor = lineColor,
                minValue = minValue,
                average = average,
            )


        }


    }
}

