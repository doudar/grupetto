package com.spop.poverlay.overlay.composables


import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.GlobalVariables

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.TextStyle

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.Arrangement


@Composable

fun StatCardFit(
    name: String,
    value: String,
    unit: String,
    avg: String,
    max: String,
    id: Int,
    offsetx: Int = 0,
    offsety: Int = 0,
    modifier: Modifier
) {
    val context = LocalContext.current
    val dbHelper = remember { DBHelper(context) }
    val globalVariables = remember { GlobalVariables(context) }
    val density = LocalDensity.current

    var offsetX by remember { mutableStateOf(offsetx.toFloat()) }
    var offsetY by remember { mutableStateOf(offsety.toFloat()) }

    var width: Int = 125
    var height :Int = 100

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
                .width(125.dp).background(Color.Yellow )
                .background(Color.Black, shape = RoundedCornerShape(16.dp))

        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,

                ) {

                Row(modifier = Modifier.background(Color.Black) .width(125.dp).height(125.dp)) {
                    Column(

                        modifier = Modifier.width(85.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = name,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            style = TextStyle(
                                lineHeight = 16.sp  // Adjust this value to your desired line height

                            )

                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = value,
                            color = Color.White,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(
                                lineHeight = 16.sp  // Adjust this value to your desired line height

                            )

                        )
                        Text(
                            text = unit,
                            fontSize = 14.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Light,
                            style = TextStyle(
                                lineHeight = 16.sp  // Adjust this value to your desired line height

                            )

                        )
                    }
                    Column(
                        modifier = Modifier.width(40.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "avg:\n $avg ", fontSize = 14.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Light,
                            style = TextStyle(


                            )

                        )
                        Text(
                            text = "max:\n$max", fontSize = 14.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Light,
                            style = TextStyle(


                            )
                        )

                    }
                }
            }
        }
    }
}


