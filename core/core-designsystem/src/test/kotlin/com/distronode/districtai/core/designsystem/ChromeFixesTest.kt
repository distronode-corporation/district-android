package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 3.0.1 visual QA fixes to the chrome and the primitives, pinned: the icon top bar and its 48dp
 * targets, the transparent disabled Ghost, the visible off switch, the single-choice radio row, and the
 * list row and avatar at 200% font.
 *
 * ⚠️ 200% IS A DENSITY WITH `fontScale = 2`, provided around the content, which is what the platform's
 * font-size setting hands Compose. Geometry is read from semantics bounds, so these hold the layout
 * decisions rather than pixels.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ChromeFixesTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `back is an arrow that says Back, and an action is an icon that says what it does`() {
        var backs = 0
        var settings = 0
        composeRule.setContent {
            DistrictTheme(labels = DistrictLabels(back = "Retour")) {
                DistrictTopBar(title = "Comptoir clients", onBack = { backs += 1 }) {
                    DistrictTopBarAction(
                        icon = DistrictIcons.Settings,
                        label = "Paramètres du comptoir",
                        onClick = { settings += 1 },
                        modifier = Modifier.testTag("settings"),
                    )
                    DistrictTopBarAction(
                        icon = DistrictIcons.Refresh,
                        label = "Actualiser",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.testTag("refresh"),
                    )
                }
            }
        }

        // ⛔ No word "Retour" drawn: it is the arrow's content description.
        composeRule.onNodeWithText("Retour").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Retour").assertIsDisplayed()
        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).assertTouchHeightIsEqualTo(48.dp)
        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription("Paramètres du comptoir").performClick()
        composeRule.onNodeWithTag("settings").assertTouchWidthIsEqualTo(48.dp)
        composeRule.onNodeWithTag("refresh").assertIsNotEnabled()
        assertEquals(1, backs)
        assertEquals(1, settings)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `at 200 percent the title stays on one line and the bar keeps its height`() {
        composeRule.setContent {
            AtFontScale(2f) {
                DistrictTopBar(
                    title = "Boîte de réception des messages du comptoir clients",
                    modifier = Modifier.testTag("bar"),
                    onBack = {},
                ) {
                    DistrictTopBarAction(icon = DistrictIcons.Edit, label = "Nouveau message", onClick = {})
                }
            }
        }

        val title = composeRule.onNodeWithText("Boîte de réception", substring = true)
        val layouts = mutableListOf<TextLayoutResult>()
        title.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult]
            .action?.invoke(layouts)
        assertEquals(1, layouts.single().lineCount)
        // One line of title in a bar that cannot grow past it: the A-S6 bar ran down the whole screen.
        assertTrue(composeRule.onNodeWithTag("bar").getUnclippedBoundsInRoot().height < 120.dp)
    }

    @Test
    fun `a small button draws 32dp but takes a 48dp touch`() {
        composeRule.setContent {
            DistrictTheme {
                DistrictButton(text = "Open", onClick = {}, size = ButtonSize.Sm, modifier = Modifier.testTag("b"))
            }
        }

        composeRule.onNodeWithTag("b").assertTouchHeightIsEqualTo(48.dp)
    }

    @Test
    fun `a disabled transparent button stays transparent, and any other fill halves`() {
        // ⛔ visual QA AC2: Transparent.copy(alpha = 0.5f) is a 50% black block.
        assertEquals(Color.Transparent, disabledContainer(Color.Transparent))
        assertEquals(Color.Red.copy(alpha = 0.5f), disabledContainer(Color.Red))
    }

    @Test
    fun `an off switch takes the muted ink for its thumb and outline, on the card`() {
        lateinit var colors: SwitchColors
        lateinit var tokens: DistrictColors
        composeRule.setContent {
            DistrictTheme(darkTheme = true) {
                colors = districtSwitchColors()
                tokens = DistrictTheme.colors
                Switch(checked = false, onCheckedChange = {}, colors = colors)
            }
        }
        composeRule.waitForIdle()

        assertEquals(tokens.mutedForeground, colors.uncheckedThumbColor)
        assertEquals(tokens.mutedForeground, colors.uncheckedBorderColor)
        assertEquals(tokens.card, colors.uncheckedTrackColor)
    }

    @Test
    fun `a radio row is one target with the radio role, selected by its label`() {
        var chosen by mutableStateOf("a")
        composeRule.setContent {
            DistrictTheme {
                Column {
                    DistrictRadioRow("A question", chosen == "a", { chosen = "a" }, Modifier.testTag("a"))
                    DistrictRadioRow("A suggestion", chosen == "b", { chosen = "b" }, Modifier.testTag("b"))
                    DistrictRadioRow("Off", false, {}, Modifier.testTag("off"), enabled = false)
                }
            }
        }

        composeRule.onNodeWithTag("a").assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule.onNodeWithText("A suggestion").performClick()
        composeRule.onNodeWithTag("b").assertIsSelected().assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag("a").assertIsNotSelected()
        composeRule.onNodeWithTag("off").assertIsNotEnabled()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a list row keeps its trailing beside the text at 100 percent and under it at 200`() {
        var scale by mutableStateOf(1f)
        composeRule.setContent {
            AtFontScale(scale) {
                DistrictListRow(
                    title = "Sophie Bouchard",
                    subtitle = "+1 514 555 0123",
                    leading = { Avatar("Sophie Bouchard", modifier = Modifier.testTag("avatar")) },
                    trailing = { Text("Pas encore configuré", modifier = Modifier.testTag("trailing")) },
                )
            }
        }

        val title = composeRule.onNodeWithText("Sophie Bouchard").getUnclippedBoundsInRoot()
        val beside = composeRule.onNodeWithTag("trailing").getUnclippedBoundsInRoot()
        assertTrue("beside at 100%", beside.left >= title.right)
        composeRule.onNodeWithTag("avatar").assertWidthIsEqualTo(36.dp)

        scale = 2f
        composeRule.waitForIdle()
        val subtitle = composeRule.onNodeWithText("+1 514 555 0123").getUnclippedBoundsInRoot()
        val under = composeRule.onNodeWithTag("trailing").getUnclippedBoundsInRoot()
        assertTrue("under at 200%", under.top >= subtitle.bottom)
        // ⚠️ visual QA AA9: the circle grows around initials it would otherwise clip.
        assertTrue(composeRule.onNodeWithTag("avatar").getUnclippedBoundsInRoot().width > 36.dp)
    }

    @Test
    fun `an eyebrow too wide for its column at 200 percent still renders`() {
        composeRule.setContent {
            AtFontScale(2f) {
                Box(Modifier.width(80.dp)) { Eyebrow("Hebdomadaire") }
            }
        }

        composeRule.onNodeWithText("HEBDOMADAIRE").assertIsDisplayed()
    }

    /**
     * ⚠️ A LABEL HELD TO TWO LINES STEPS DOWN UNTIL IT FITS THEM, not only until no word splits: a
     * navigation label's third line is otherwise cut off.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a label held to fewer lines steps down until it fits them`() {
        var laid: TextLayoutResult? = null
        composeRule.setContent {
            Box(Modifier.width(60.dp)) {
                Text(
                    text = "Vue d'ensemble du compte",
                    style = TextStyle(fontSize = 14.sp),
                    maxLines = 1,
                    autoSize = WholeWordAutoSize(max = 14.sp, floor = 6.sp),
                    onTextLayout = { laid = it },
                )
            }
        }
        composeRule.waitForIdle()

        val layout = checkNotNull(laid)
        assertTrue(layout.layoutInput.style.fontSize < 14.sp)
        assertFalse(layout.doesNotFit() && layout.layoutInput.style.fontSize > 6.sp)
    }

    @Test
    fun `every chrome icon builds at 24dp, and back mirrors in right-to-left`() {
        val icons = listOf(
            DistrictIcons.Back, DistrictIcons.Home, DistrictIcons.Inbox, DistrictIcons.Call,
            DistrictIcons.Person, DistrictIcons.AccountCircle, DistrictIcons.Settings, DistrictIcons.Edit,
            DistrictIcons.Refresh, DistrictIcons.Flag, DistrictIcons.Block, DistrictIcons.MoreVert,
        )
        composeRule.setContent {
            DistrictTheme { Column { icons.forEach { Icon(it, contentDescription = it.name) } } }
        }

        icons.forEach {
            assertEquals(24.dp, it.defaultWidth)
            composeRule.onNodeWithContentDescription(it.name).assertIsDisplayed()
        }
        assertEquals(listOf(true), icons.filter { it.autoMirror }.map { it.name == "Back" })
    }

    @Composable
    private fun AtFontScale(scale: Float, content: @Composable () -> Unit) {
        DistrictTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
        }
    }
}
