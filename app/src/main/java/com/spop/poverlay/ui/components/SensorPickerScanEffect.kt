package com.spop.poverlay.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** A composed but backgrounded activity is not an open pairing picker. */
@Composable
internal fun SensorPickerScanEffect(enabled: Boolean, onVisibleChanged: (Boolean) -> Unit) {
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onVisibleChanged)
    DisposableEffect(owner, enabled) {
        val observer = LifecycleEventObserver { _, _ ->
            callback(enabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        owner.lifecycle.addObserver(observer)
        callback(enabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            owner.lifecycle.removeObserver(observer)
            callback(false)
        }
    }
}
