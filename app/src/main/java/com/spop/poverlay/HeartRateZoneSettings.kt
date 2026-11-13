package com.spop.poverlay

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SelectedBorder = Color(0xFF7DD3FC)
private val IdleBorder = Color(0x66FFFFFF)

/**
 * Four heart-rate zone boundaries, entered on a small keypad drawn inside the dialog. The system
 * keyboard is never opened, so it cannot cover the boxes (it takes half the screen on the bike).
 */
@Composable
internal fun HeartRateZoneSettings(
    stored: List<Int?>,
    onChange: (List<Int?>) -> Unit,
) {
    val values = remember(stored) {
        mutableStateListOf(*stored.map { it?.toString() ?: "" }.toTypedArray())
    }
    var selectedIndex by remember { mutableStateOf(-1) }
    var valuesOnSelect by remember { mutableStateOf(stored) }

    // Fills in the other boundaries from the box at [index], once editing it is finished.
    fun commit(index: Int) {
        if (selectedIndex != index) return
        selectedIndex = -1
        val filled = fillZoneBoundaries(valuesOnSelect, values.map { it.toIntOrNull() }, index)
        filled.forEachIndexed { i, v -> values[i] = v?.toString() ?: "" }
        onChange(filled)
    }

    fun select(index: Int) {
        if (selectedIndex == index) return
        if (selectedIndex >= 0) commit(selectedIndex)
        selectedIndex = index
        valuesOnSelect = values.map { it.toIntOrNull() }
    }

    fun typeDigit(digit: Char) {
        val i = selectedIndex
        if (i < 0) return
        // Digits are added at the cursor, which sits after the last digit; three digits at most.
        if (values[i].length >= 3) return
        values[i] = values[i] + digit
        onChange(values.map { it.toIntOrNull() })
    }

    fun backspace() {
        val i = selectedIndex
        if (i < 0) return
        // One digit at a time.
        values[i] = values[i].dropLast(1)
        onChange(values.map { it.toIntOrNull() })
    }

    // The dialog's own Done button can close this screen while a box is still being edited.
    val latestCommit by rememberUpdatedState({ i: Int -> commit(i) })
    DisposableEffect(Unit) {
        onDispose {
            if (selectedIndex >= 0) latestCommit(selectedIndex)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val columns = if (maxWidth >= 600.dp) 5 else 3
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.indices.toList().chunked(columns).forEach { indices ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    indices.forEach { index ->
                        val selected = index == selectedIndex
                        Column(
                            Modifier.weight(1f)
                                .border(if (selected) 3.dp else 1.dp, if (selected) SelectedBorder else IdleBorder, RoundedCornerShape(8.dp))
                                .clickable { select(index) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            // Each box is the top of that zone: 60, 70, 80, 90 and 100% of max heart rate.
                            Text("Zone ${index + 1}", fontSize = 13.sp, color = if (selected) SelectedBorder else Color.Unspecified)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (values[index].isNotEmpty() || !selected) {
                                    Text(values[index].ifEmpty { "-" }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                }
                                if (selected) BlinkingCursor()
                            }
                        }
                    }
                }
            }
        }
    }
    if (selectedIndex >= 0) {
        // One compact row so the whole zone screen stays on screen without scrolling.
        val rows = listOf(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "Del", "Done"))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { key ->
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .testTag("zoneKey_$key")
                                .border(1.dp, IdleBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    when (key) {
                                        "Del" -> backspace()
                                        "Done" -> commit(selectedIndex)
                                        else -> typeDigit(key[0])
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(key, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

/** A text cursor that blinks once a second, shown after the digits of the box being edited. */
@Composable
private fun BlinkingCursor() {
    val blink = rememberInfiniteTransition()
    val alpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1f at 499
                0f at 500
                0f at 999
            },
            repeatMode = RepeatMode.Restart,
        ),
    )
    Box(Modifier.padding(start = 2.dp).width(2.dp).height(26.dp).alpha(alpha).background(SelectedBorder))
}
