package com.spop.poverlay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun ShifterSettings(shown: Boolean, inset: Float, onShown: (Boolean) -> Unit, onInset: (Float) -> Unit,
    onPreview: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val pressing by interaction.collectIsPressedAsState()
    LaunchedEffect(dragging, pressing) { onPreview(dragging || pressing) }
    DisposableEffect(Unit) { onDispose { onPreview(false) } }
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(shown, role = Role.Checkbox, onValueChange = onShown),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(shown, null)
            Spacer(Modifier.width(12.dp))
            Text("Show shifters", fontSize = 16.sp)
        }
        Text("Fixed at the bottom left and right in overlay mode. Hidden in settings except while positioning.", fontSize = 13.sp,
            lineHeight = 18.sp, color = Color(0xFF9EAEC0))
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Move both inward", fontSize = 15.sp)
            Text(String.format(Locale.US, "%.1f%% of screen width", inset * 12.5f), fontSize = 14.sp, color = Color(0xFFB7F7DF))
        }
        Slider(inset, onInset, Modifier.fillMaxWidth().semantics { contentDescription = "Shifter spacing" },
            valueRange = 0f..1f, interactionSource = interaction)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("At edges", fontSize = 12.sp)
            Text("1/8 screen inward", fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text("Both buttons move the same distance. ERG shifts change target watts; Sim and Manual shifts change resistance.",
            fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF9EAEC0))
    }
}

@Composable
internal fun ShifterPositionPreview(shown: Boolean, inset: Float) {
    val activity = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
    val windows = remember(activity) {
        com.spop.poverlay.overlay.FixedShifterWindows(activity, activity, activity, preview = true, onShift = {})
    }
    DisposableEffect(windows) { onDispose { windows.close() } }
    SideEffect { windows.update(shown, inset, false) }
}
