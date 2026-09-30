package com.spop.poverlay.overlay

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.LifecycleOwner
import com.spop.poverlay.ConfigurationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionTest {
    private val stored = mutableMapOf<String, Any>("serialNumber" to "1234")
    private val preferences = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val context = mockk<Context>()

    init {
        every { context.getSharedPreferences(ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), any()) } answers {
            stored[firstArg<String>()] as String? ?: secondArg<String?>()
        }
        every { preferences.getFloat(any(), any()) } answers {
            stored[firstArg<String>()] as Float? ?: secondArg<Float>()
        }
        every { preferences.getBoolean(any(), any()) } answers {
            stored[firstArg<String>()] as Boolean? ?: secondArg<Boolean>()
        }
        every { preferences.edit() } returns editor
        every { editor.putFloat(any(), any()) } answers {
            stored[firstArg()] = secondArg<Float>()
            editor
        }
        every { editor.putString(any(), any()) } answers {
            stored[firstArg()] = secondArg<String>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            stored[firstArg()] = secondArg<Boolean>()
            editor
        }
    }

    private fun repository(): ConfigurationRepository {
        val owner = mockk<LifecycleOwner>(relaxed = true)
        return ConfigurationRepository(context, owner)
    }

    private fun dialog(offset: Float = 0f, location: OverlayLocation = OverlayLocation.Bottom) =
        OverlayDialogViewModel(Size(1920f, 1080f), MutableStateFlow(false), offset, location)
            .also { it.onOverlayLayout(IntSize(1000, 110)) }

    @Test
    fun `new installation defaults to bottom center`() {
        repository().use { config ->
            val dialog = dialog(config.overlayHorizontalOffset, config.overlayLocation)
            assertEquals(Offset.Zero, dialog.dialogOrigin.value)
            assertEquals(OverlayLocation.Bottom, dialog.dialogLocation.value)
        }
    }

    @Test
    fun `saved position survives repository and dialog recreation and subsequent dragging`() {
        repository().use { config ->
            val dialog = dialog()
            dialog.processHorizontalDrag(-250f)
            dialog.processVerticalDrag(600f)
            config.setOverlayPosition(dialog.dialogOrigin.value.x, dialog.dialogLocation.value)
        }
        verify(exactly = 1) { editor.apply() }

        repository().use { config ->
            val restored = dialog(config.overlayHorizontalOffset, config.overlayLocation)
            assertEquals(Offset(-250f, 0f), restored.dialogOrigin.value)
            assertEquals(OverlayLocation.Top, restored.dialogLocation.value)
            assertEquals(OverlayLocation.Top.gravity, restored.dialogGravity.value)

            restored.processHorizontalDrag(30f)
            assertEquals(Offset(-220f, 0f), restored.dialogOrigin.value)
            restored.processVerticalDrag(-600f)
            config.setOverlayPosition(restored.dialogOrigin.value.x, restored.dialogLocation.value)
        }
        repository().use { config ->
            assertEquals(-220f, config.overlayHorizontalOffset, 0f)
            assertEquals(OverlayLocation.Bottom, config.overlayLocation)
        }
    }

    @Test
    fun `restored position is clamped to measured overlay bounds`() {
        val dialog = dialog(800f)
        assertEquals(Offset(460f, 0f), dialog.dialogOrigin.value)
        dialog.onTimerOverlayLayout(IntSize(300, 60))
        dialog.processHorizontalDrag(50f)
        assertEquals(Offset(460f, 0f), dialog.dialogOrigin.value)
        dialog.processHorizontalDrag(-30f)
        assertEquals(Offset(430f, 0f), dialog.dialogOrigin.value)
        dialog.onOverlayLayout(IntSize(2000, 110))
        assertEquals(Offset.Zero, dialog.dialogOrigin.value)
    }

    @Test
    fun `dragging still snaps at center and can move past it`() {
        val dialog = dialog(100f)
        dialog.processHorizontalDrag(-85f)
        assertEquals(Offset.Zero, dialog.dialogOrigin.value)
        dialog.processHorizontalDrag(-50f)
        assertEquals(Offset(-35f, 0f), dialog.dialogOrigin.value)
    }

    @Test
    fun `invalid saved position falls back to defaults`() {
        stored[ConfigurationRepository.Preferences.OverlayHorizontalOffset.key] = Float.NaN
        stored[ConfigurationRepository.Preferences.OverlayLocation.key] = "unknown"
        repository().use { config ->
            assertEquals(0f, config.overlayHorizontalOffset, 0f)
            assertEquals(OverlayLocation.Bottom, config.overlayLocation)
        }
    }

    @Test fun `shifter visibility and spacing survive settings and service recreation`() {
        repository().use { config ->
            assertEquals(true, config.showShifters.value)
            assertEquals(0f, config.shifterInset.value, 0f)
            config.setShowShifters(false)
            config.setShifterInset(.75f)
        }
        repository().use { config ->
            assertEquals(false, config.showShifters.value)
            assertEquals(.75f, config.shifterInset.value, 0f)
            config.setShowShifters(true)
            config.setShifterInset(2f)
        }
        repository().use { config ->
            assertEquals(true, config.showShifters.value)
            assertEquals(1f, config.shifterInset.value, 0f)
        }
    }

    @Test fun `invalid saved shifter spacing cannot move windows off screen`() {
        stored[ConfigurationRepository.Preferences.ShifterInset.key] = Float.NaN
        repository().use { config ->
            assertEquals(0f, config.shifterInset.value, 0f)
            config.setShifterInset(-1f)
            assertEquals(0f, config.shifterInset.value, 0f)
        }
    }
}
