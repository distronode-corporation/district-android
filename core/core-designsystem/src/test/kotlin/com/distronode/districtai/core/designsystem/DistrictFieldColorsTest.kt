package com.distronode.districtai.core.designsystem

import androidx.compose.material3.TextFieldColors
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one text-field colour set, pinned to the tokens.
 *
 * ⚠️ [TextFieldColors.disabledTextColor] IS THE ENTRY THAT DRIFTED. Only one of the seven copies this
 * function replaced set it, so it is asserted by name rather than trusted to the container colours.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DistrictFieldColorsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the field colours come from the tokens, disabled text included`() {
        lateinit var colors: TextFieldColors
        lateinit var tokens: DistrictColors
        composeRule.setContent {
            DistrictTheme {
                colors = districtFieldColors()
                tokens = DistrictTheme.colors
            }
        }
        composeRule.waitForIdle()

        assertEquals(tokens.muted, colors.focusedContainerColor)
        assertEquals(tokens.muted, colors.unfocusedContainerColor)
        assertEquals(tokens.muted, colors.disabledContainerColor)
        assertEquals(tokens.district, colors.focusedIndicatorColor)
        assertEquals(tokens.border, colors.unfocusedIndicatorColor)
        assertEquals(tokens.foreground, colors.focusedTextColor)
        assertEquals(tokens.foreground, colors.unfocusedTextColor)
        assertEquals(tokens.mutedForeground, colors.disabledTextColor)
        assertEquals(tokens.district, colors.cursorColor)
    }
}
