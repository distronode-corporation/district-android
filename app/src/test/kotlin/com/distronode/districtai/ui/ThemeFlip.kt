package com.distronode.districtai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.distronode.districtai.core.designsystem.DistrictTheme

/**
 * Content drawn under a theme the test can switch between light and dark.
 *
 * ⚠️ WHAT A FLIP EXERCISES. `DistrictTheme` provides its colours, spacing and text styles through
 * STATIC composition locals, so changing the theme redraws every composable below it, including the
 * ones whose own inputs did not change. That is the "same input, drawn again" case a screen meets
 * whenever the theme changes under it, and the only way a test can ask a composable with unchanged
 * arguments to run again: an ordinary state change skips it.
 */
internal class ThemeFlip(private val composeRule: ComposeContentTestRule) {

    private var dark by mutableStateOf(false)

    fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent { DistrictTheme(darkTheme = dark) { content() } }
    }

    /** Switch to the other theme and wait for the redraw. */
    fun flip() {
        dark = !dark
        composeRule.waitForIdle()
    }
}
