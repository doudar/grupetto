package com.spop.poverlay

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.AlertDialog
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

internal const val COFFEE_URL = "https://buymeacoffee.com/anthonydouv"

internal fun openCoffeeLink(context: Context): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, COFFEE_URL.toUri()))
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
internal fun CoffeeLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showFallback by remember { mutableStateOf(false) }
    IconButton(
        onClick = { showFallback = !openCoffeeLink(context) },
        modifier = modifier.size(48.dp),
    ) {
        Image(
            painterResource(R.drawable.buy_me_a_coffee),
            contentDescription = stringResource(R.string.coffee_link_label),
            modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
        )
    }
    if (showFallback) {
        AlertDialog(
            onDismissRequest = { showFallback = false },
            title = { Text(stringResource(R.string.coffee_browser_unavailable)) },
            text = { SelectionContainer { Text(COFFEE_URL) } },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(COFFEE_URL))
                    showFallback = false
                }) { Text(stringResource(R.string.coffee_copy_link)) }
            },
            dismissButton = {
                TextButton(onClick = { showFallback = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
