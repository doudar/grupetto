package com.spop.poverlay

import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun SerialNameUpgradeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check your Grupetto connection") },
        text = {
            Text("The Grupetto name may have changed after this upgrade. " +
                "If your training app or SmartSpin2k cannot find it, scan for the new name and pair again.")
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}