package com.spop.poverlay.calibration

import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.R
import com.spop.poverlay.ui.theme.LatoFontFamily
import com.spop.poverlay.ui.theme.Typography
import org.junit.Assert.*
import org.junit.Test

class CalibrationTypographyTest {
    @Test fun headingsButtonsFieldsAndCaptionsUseTheBundledFont() {
        listOf(Typography.h1, Typography.h2, Typography.h3, Typography.h4, Typography.h5, Typography.h6,
            Typography.subtitle1, Typography.subtitle2, Typography.body1, Typography.body2,
            Typography.button, Typography.caption, Typography.overline).forEach {
            assertEquals(LatoFontFamily, it.fontFamily)
        }
    }

    @Test fun bundledRegularAndBoldFontsRenderEditorLabelsAndSignedPercentages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(R.font.lato_regular, R.font.lato_bold).forEach { resource ->
            val paint = Paint().apply { typeface = ResourcesCompat.getFont(context, resource) }
            // Editor labels use printable ASCII; selection and manual markers are drawn shapes.
            ('!'..'~').forEach { character -> assertTrue("Missing glyph: $character", paint.hasGlyph(character.toString())) }
        }
    }
}
