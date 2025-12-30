package com.spop.poverlay.overlay.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.GlobalVariables
import androidx.compose.ui.text.style.TextAlign


@Composable
fun StatCardMovable(
    name: String,
    value: String,
    unit: String,
    id: Int,
    offsetx: Int = 0,
    offsety: Int = 0,
    modifier: Modifier,
    averages: Boolean = false,
    avg: String = "",
    max: String = "",
    batteryPCT: String = ""

) {
    var height: Int=110
    var width: Int=125

    if (averages == true)
    {
        width = 160
    }

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

    Box(modifier = Modifier
        .offset(offsetX.dp, offsetY.dp)
        .pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = {
                    val userId = globalVariables.UserIDGet()
                    dbHelper.updateStatCardPosition(userId, id, offsetX.toInt(), offsetY.toInt())
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
                .background(Color.Black, shape = RoundedCornerShape(16.dp))

        ) {
            if(averages == false) {
                var ffontSize:Int = 48
                if (name == "Duration"||name == "Speed")ffontSize = 32


                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,

                    ) {
                    Text(
                        text = name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal
                    )
                    Text(
                        text = value,
                        color = Color.White,
                        fontSize = ffontSize.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = unit,
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Light
                    )
                }
            }
            else
            {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,

                    ) {
                    var ffontSize:Int = 48
                    if (name == "Duration"||name == "Speed")ffontSize = 32
                    Row(modifier = Modifier .width(125.dp).height(125.dp)) {
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
                                fontSize = ffontSize.sp,
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
                            verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "avg:\n $avg ", fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Light,
                                textAlign = TextAlign.Center,
                                style = TextStyle(


                                )

                            )
                            Text(
                                text = "max:\n$max", fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Light,
                                textAlign = TextAlign.Center,
                                style = TextStyle(


                                )


                            )
                            Text(
                                text = "$batteryPCT%", fontSize = 14.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Light,
                                textAlign = TextAlign.Center,
                                style = TextStyle(


                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
