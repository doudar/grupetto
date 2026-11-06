package com.spop.poverlay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HeartRateZoneSettings(stored: List<Int?>, onChange: (List<Int?>) -> Unit) {
    Text("Zone boundaries (bpm)", fontWeight = FontWeight.SemiBold)
    Text("Leave a boundary blank to use the default.", fontSize = 13.sp)
    val values = remember(stored) {
        mutableStateListOf(*stored.map { it?.toString() ?: "" }.toTypedArray())
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        val columns = if (maxWidth >= 480.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.indices.toList().chunked(columns).forEach { indices ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    indices.forEach { index ->
                        OutlinedTextField(values[index], { input ->
                            values[index] = input.filter(Char::isDigit).take(3)
                            onChange(values.map { it.toIntOrNull() })
                        // Use words: the bundled font does not contain the right-arrow glyph.
                        }, label = { Text("${index + 1} to ${index + 2}") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            }
        }
    }
}
