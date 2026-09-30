package com.spop.poverlay.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** No drag handler: position belongs to the paired WindowManager layouts. */
@Composable
internal fun FixedShiftButton(up: Boolean, watts: Boolean, onClick: () -> Unit) {
    Button(onClick, Modifier.size(ShifterWidthDp.dp, ShifterHeightDp.dp).semantics {
        val target = if (watts) "ERG target watts" else "resistance"
        contentDescription = if (up) "Shift up: increase $target" else "Shift down: decrease $target"
    }, contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, Color(0xFF91FFE0)),
        elevation = ButtonDefaults.elevation(0.dp, 0.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF008761))) {
        Text(if (up) "+" else "−", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}
