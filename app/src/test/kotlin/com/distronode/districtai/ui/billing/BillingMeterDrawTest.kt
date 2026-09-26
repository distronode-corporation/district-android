package com.distronode.districtai.ui.billing

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictColors
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the usage meter's bar fills, read off the drawn card.
 *
 * ⚠️ NOT A PIXEL COMPARISON: a yes/no about whether the brand accent was painted anywhere in the
 * card, which is the decision the bar makes (minutes against an allowance are filled, an empty
 * month is bare track) and which survives any change to size or spacing.
 *
 * ⛔ NATIVE GRAPHICS, because the legacy mode never runs a draw pass, so the `Canvas` block would
 * be composed and measured and never asked to paint anything.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w600dp-h2000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillingMeterDrawTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val accent = DistrictColors.Light.district

    private fun colorsInMeter(usage: UsageData, included: Int): Set<Color> {
        composeRule.setContent { DistrictTheme(darkTheme = false) { UsageMeterCard(usage, included) } }
        val pixels = composeRule.onNodeWithContentDescription(BILLING_METER_DESCRIPTION)
            .captureToImage()
            .toPixelMap()
        val seen = mutableSetOf<Color>()
        for (x in 0 until pixels.width) {
            for (y in 0 until pixels.height) seen += pixels[x, y]
        }
        return seen
    }

    @Test
    fun `minutes against an allowance fill the bar in the brand accent`() {
        assertTrue(accent in colorsInMeter(UsageData(month = "2026-08", callMinutesInbound = 750.0), 1500))
    }

    @Test
    fun `zero minutes against an allowance draw bare track`() {
        assertFalse(accent in colorsInMeter(UsageData(month = "2026-08", callMinutesInbound = 0.0), 1500))
    }
}
