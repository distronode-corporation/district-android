package com.distronode.districtai.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The palette and the theme that hands it out, pinned in both modes.
 *
 * WHY THE DARK MODE NEEDS ITS OWN TEST. Dark is the designed state, the one every value was
 * contrast-checked in, and it is the state no other test in this project renders: Robolectric
 * reports a light system by default, so every screen test draws the light palette. A dark theme that handed
 * Material the light scheme, or the extended tokens the wrong palette, would pass all of them.
 *
 * WHY THE HEX VALUES ARE PINNED. Each public dark token is documented as the web's
 * `[data-theme="dark"]` value from tokens.css, verbatim, and the brand is only one brand while the
 * two agree. A token nudged here without the web moving with it fails below and names the token.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DistrictPaletteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(darkTheme: Boolean): Pair<ColorScheme, DistrictColors> {
        var scheme: ColorScheme? = null
        var tokens: DistrictColors? = null
        composeRule.setContent {
            DistrictTheme(darkTheme = darkTheme) {
                scheme = MaterialTheme.colorScheme
                tokens = DistrictTheme.colors
            }
        }
        composeRule.waitForIdle()
        return checkNotNull(scheme) to checkNotNull(tokens)
    }

    @Test
    fun `every public dark token is the web's documented value`() {
        mapOf(
            "--background" to (DistrictColors.BACKGROUND to 0xFF0E1C1F),
            "--foreground" to (DistrictColors.FOREGROUND to 0xFFE9F0F1),
            "--surface" to (DistrictColors.SURFACE to 0xFF152528),
            "--card" to (DistrictColors.CARD to 0xFF16292D),
            "--muted" to (DistrictColors.MUTED to 0xFF1D3236),
            "--muted-foreground" to (DistrictColors.MUTED_FOREGROUND to 0xFF93A8AB),
            "--border" to (DistrictColors.BORDER to 0xFF24393D),
            "--input" to (DistrictColors.INPUT to 0xFF2A4146),
            "--elevated" to (DistrictColors.ELEVATED to 0xFF1F373B),
            "--district" to (DistrictColors.DISTRICT to 0xFF7D84F7),
            "--district-foreground" to (DistrictColors.DISTRICT_FOREGROUND to 0xFF0F1020),
            "--success" to (DistrictColors.SUCCESS to 0xFF3FBF7F),
            "--warning" to (DistrictColors.WARNING to 0xFFFBBF24),
            "--destructive" to (DistrictColors.DESTRUCTIVE to 0xFFF87171),
            "--info" to (DistrictColors.INFO to 0xFF60A5FA),
        ).forEach { (token, pair) ->
            val (color, argb) = pair
            assertEquals(token, Color(argb), color)
        }
    }

    @Test
    fun `the dark palette is assembled from those tokens, one for one`() {
        val dark = DistrictColors.Dark

        assertEquals(DistrictColors.BACKGROUND, dark.background)
        assertEquals(DistrictColors.FOREGROUND, dark.foreground)
        assertEquals(DistrictColors.SURFACE, dark.surface)
        assertEquals(DistrictColors.CARD, dark.card)
        assertEquals(DistrictColors.MUTED, dark.muted)
        assertEquals(DistrictColors.MUTED_FOREGROUND, dark.mutedForeground)
        assertEquals(DistrictColors.BORDER, dark.border)
        assertEquals(DistrictColors.INPUT, dark.input)
        assertEquals(DistrictColors.ELEVATED, dark.elevated)
        assertEquals(DistrictColors.DISTRICT, dark.district)
        assertEquals(DistrictColors.DISTRICT_FOREGROUND, dark.districtForeground)
        assertEquals(DistrictColors.SUCCESS, dark.success)
        assertEquals(DistrictColors.WARNING, dark.warning)
        assertEquals(DistrictColors.DESTRUCTIVE, dark.destructive)
        assertEquals(DistrictColors.INFO, dark.info)
        // Every semantic fill in dark carries the same black text.
        listOf(dark.onSuccess, dark.onWarning, dark.onDestructive, dark.onInfo).forEach {
            assertEquals(DistrictColors.ON_SEMANTIC_DARK, it)
        }
    }

    @Test
    fun `a dark theme hands Material and the District tokens the dark palette`() {
        val (scheme, tokens) = capture(darkTheme = true)

        assertEquals(DistrictColors.Dark, tokens)
        assertEquals(DistrictColors.DISTRICT, scheme.primary)
        assertEquals(DistrictColors.DISTRICT_FOREGROUND, scheme.onPrimary)
        assertEquals(DistrictColors.BACKGROUND, scheme.background)
        assertEquals(DistrictColors.FOREGROUND, scheme.onBackground)
        assertEquals(DistrictColors.SURFACE, scheme.surface)
        assertEquals(DistrictColors.CARD, scheme.surfaceContainer)
        assertEquals(DistrictColors.BORDER, scheme.outline)
        assertEquals(DistrictColors.DESTRUCTIVE, scheme.error)
        assertEquals(DistrictColors.ON_SEMANTIC_DARK, scheme.onError)
    }

    @Test
    fun `a light theme hands both the light palette, and never the dark accent`() {
        val (scheme, tokens) = capture(darkTheme = false)

        assertEquals(DistrictColors.Light, tokens)
        assertEquals(DistrictColors.Light.district, scheme.primary)
        assertEquals(DistrictColors.Light.background, scheme.background)
        // The accent is indigo in both modes but not the same indigo: the light palette carries a
        // far deeper one for AA contrast on white.
        assertNotEquals(DistrictColors.DISTRICT, scheme.primary)
    }
}
