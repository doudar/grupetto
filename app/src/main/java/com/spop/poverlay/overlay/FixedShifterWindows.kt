package com.spop.poverlay.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.spop.poverlay.util.disableAnimations
import kotlin.math.roundToInt

/** Two button-sized windows leave the rest of the screen available to the underlying app. */
internal class FixedShifterWindows(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val savedStateOwner: SavedStateRegistryOwner,
    private val preview: Boolean = false,
    private val onShift: (Int) -> Unit
) : AutoCloseable {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val windows = mutableListOf<Pair<ComposeView, WindowManager.LayoutParams>>()
    private var watts by mutableStateOf(false)
    private var inset = 0f
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) hide()
        if (event == Lifecycle.Event.ON_DESTROY) close()
    }

    init { owner.lifecycle.addObserver(observer) }

    fun update(shown: Boolean, position: Float, shiftWatts: Boolean) {
        inset = normalizedShifterInset(position)
        watts = shiftWatts
        if (!shown || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context))) {
            hide()
            return
        }
        if (windows.isEmpty()) {
            for (up in listOf(false, true)) {
                val params = WindowManager.LayoutParams(
                    1, 1,
                    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        (if (preview) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or if (up) Gravity.RIGHT else Gravity.LEFT
                    title = "Grupetto ${if (preview) "preview " else ""}shift ${if (up) "up" else "down"}"
                    disableAnimations()
                }
                place(params)
                val view = ComposeView(context).apply {
                    setViewTreeLifecycleOwner(owner)
                    setViewTreeSavedStateRegistryOwner(savedStateOwner)
                    setContent { FixedShiftButton(up, watts) { onShift(if (up) 1 else -1) } }
                }
                manager.addView(view, params)
                windows.add(view to params)
            }
        } else refresh()
    }

    private fun place(params: WindowManager.LayoutParams) {
        val metrics = context.resources.displayMetrics
        params.width = (ShifterWidthDp * metrics.density).roundToInt()
        params.height = (ShifterHeightDp * metrics.density).roundToInt()
        params.x = shifterEdgeInset(metrics.widthPixels, metrics.density, inset)
        params.y = (ShifterBottomMarginDp * metrics.density).roundToInt()
    }

    fun refresh() {
        windows.forEach { (view, params) ->
            val before = listOf(params.x, params.y, params.width, params.height)
            place(params)
            if (before != listOf(params.x, params.y, params.width, params.height)) manager.updateViewLayout(view, params)
        }
    }

    private fun hide() {
        windows.forEach { (view, _) ->
            manager.removeViewImmediate(view)
            view.disposeComposition()
        }
        windows.clear()
    }

    override fun close() {
        hide()
        owner.lifecycle.removeObserver(observer)
    }
}
