package com.distronode.districtai.ui.settings.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The workspace settings hub: eight rows for a mutating role, TWO for a viewer, and a note about
 * what is deliberately absent in each case.
 *
 * ⛔ THE VIEWER CASES ARE THE POINT OF THIS FILE. The hub is open to viewers (for read access to
 * knowledge, messaging and call handling) and the ROWS are gated. The four config-backed sections
 * must stay hidden (`workspace/config` excludes `viewer` from its READ, so those rows would lead to
 * a 403), and the assertion for that is an ABSENCE, which is only as trustworthy as the handles
 * it names. Every handle here is a `const` from the screen, so a rename fails to compile rather
 * than making an absence test pass for the wrong reason.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class WorkspaceSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        canMutate: Boolean = true,
        onOpenPersona: () -> Unit = {},
        onOpenCapabilities: () -> Unit = {},
        onOpenNumbers: () -> Unit = {},
        onOpenSection: (String) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                WorkspaceSettingsScreen(
                    canMutate = canMutate,
                    onOpenPersona = onOpenPersona,
                    onOpenCapabilities = onOpenCapabilities,
                    onOpenNumbers = onOpenNumbers,
                    onOpenSection = onOpenSection,
                    onBack = onBack,
                )
            }
        }
    }

    @Test
    fun `every section is listed for a mutating role`() {
        render()

        listOf(
            WORKSPACE_SETTINGS_PERSONA_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_VOICE_STUDIO_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_DIRECTORY_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_ROUTING_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_MESSAGING_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_MEMBERS_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_NUMBERS_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_CALLS_ROW_DESCRIPTION,
        ).forEach { handle ->
            composeRule.onNodeWithContentDescription(handle).assertIsDisplayed()
        }
    }

    @Test
    fun `the receptionist's rows sit under a District Studio heading in the web's order`() {
        // ⛔ THE WEB'S ORDER AND THE WEB'S LABELS: Persona, Voice, Call handling, Skills, Knowledge
        // (Integrations shares the Skills screen and Video has no native screen, so neither has a
        // row). The heading is a product name and is never translated; Eyebrow uppercases it.
        render()

        composeRule.onNodeWithText("District Studio".uppercase()).assertIsDisplayed()
        listOf("Persona", "Voice", "Call handling", "Skills", "Knowledge").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }

        val studioOrder = listOf(
            WORKSPACE_SETTINGS_PERSONA_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_VOICE_STUDIO_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_CALLS_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION,
            // The workspace's own settings follow the Studio, never interleave with it.
            WORKSPACE_SETTINGS_DIRECTORY_ROW_DESCRIPTION,
        )
        val headingTop = composeRule.onNodeWithText("District Studio".uppercase())
            .fetchSemanticsNode().boundsInRoot.top
        val tops = studioOrder.map {
            composeRule.onNodeWithContentDescription(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertTrue("the heading sits above the first Studio row", headingTop < tops.first())
        assertEquals(tops.sorted(), tops)
    }

    @Test
    fun `a viewer still sees the District Studio heading over the rows they may open`() {
        render(canMutate = false)

        composeRule.onNodeWithText("District Studio".uppercase()).assertIsDisplayed()
        val callsTop = composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_CALLS_ROW_DESCRIPTION)
            .fetchSemanticsNode().boundsInRoot.top
        val knowledgeTop = composeRule
            .onNodeWithContentDescription(WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION)
            .fetchSemanticsNode().boundsInRoot.top
        val messagingTop = composeRule
            .onNodeWithContentDescription(WORKSPACE_SETTINGS_MESSAGING_ROW_DESCRIPTION)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(callsTop < knowledgeTop)
        assertTrue("messaging is outside the Studio, below it", knowledgeTop < messagingTop)
    }

    @Test
    fun `the four section rows navigate by their own section segment`() {
        // ⛔ THE SEGMENT IS WHAT DECIDES THE DESTINATION, and a wrong one is a silent no-op rather
        // than an exception — Navigation simply does not match, so the tap does nothing. Asserted
        // by VALUE here, and `RoutesTest` asserts the same literals build a matching route.
        var section: String? = null
        render(onOpenSection = { section = it })

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DIRECTORY_ROW_DESCRIPTION)
            .performClick()
        assertEquals("directory", section)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_ROUTING_ROW_DESCRIPTION)
            .performClick()
        assertEquals("routing", section)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION)
            .performClick()
        assertEquals("knowledge", section)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_MESSAGING_ROW_DESCRIPTION)
            .performClick()
        assertEquals("messaging", section)

        // ⚠️ THE ONE SECTION WHOSE OWN READ IS WIDER THAN THIS ROW'S GATE — the member LIST admits
        // `viewer` while the row is still shown to mutating roles only. That is a deliberate
        // stopping point rather than a rule: the members screen needs its own affordance audit
        // before it widens.
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_MEMBERS_ROW_DESCRIPTION)
            .performClick()
        assertEquals("members", section)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_VOICE_STUDIO_ROW_DESCRIPTION)
            .performClick()
        assertEquals("voice-studio", section)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_CALLS_ROW_DESCRIPTION)
            .performClick()
        assertEquals("calls", section)
    }

    @Test
    fun `a viewer gets the three rows whose reads admit them, and no others`() {
        // ⛔ THE FOUR HIDDEN ROWS ALL HYDRATE FROM `workspace/config`, WHOSE GET EXCLUDES `viewer`
        // BY DESIGN — the payload carries staff transfer numbers and the operator's own prompt, and
        // that design stands. A viewer offered one of them would land on a 403 they were invited to.
        //
        // ⚠️ CALL HANDLING IS THE THIRD, ADDED WITH THE `calls` SECTION. Both of its reads admit a
        // viewer (the mode explains a call list they can already see, and the availability read
        // answers them `false` with `reason: "role"` rather than refusing), and the SCREEN withholds
        // the controls the two PATCHes refuse.
        render(canMutate = false)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_MESSAGING_ROW_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_CALLS_ROW_DESCRIPTION)
            .assertIsDisplayed()

        listOf(
            WORKSPACE_SETTINGS_PERSONA_ROW_DESCRIPTION,
            // ⛔ The Studio read and the persona PATCH both exclude `viewer`.
            WORKSPACE_SETTINGS_VOICE_STUDIO_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_DIRECTORY_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_ROUTING_ROW_DESCRIPTION,
            // ⚠️ These two would SERVE a viewer and are hidden anyway — see the ⚠️ above.
            WORKSPACE_SETTINGS_MEMBERS_ROW_DESCRIPTION,
            WORKSPACE_SETTINGS_NUMBERS_ROW_DESCRIPTION,
        ).forEach { handle ->
            composeRule.onNodeWithContentDescription(handle).assertDoesNotExist()
        }
    }

    @Test
    fun `a viewer's two rows still navigate by their own section segment`() {
        var section: String? = null
        render(canMutate = false, onOpenSection = { section = it })

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION)
            .performClick()
        assertEquals("knowledge", section)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_MESSAGING_ROW_DESCRIPTION)
            .performClick()
        assertEquals("messaging", section)
    }

    @Test
    fun `each row opens its own section`() {
        var opened: String? = null
        render(
            onOpenPersona = { opened = "persona" },
            onOpenCapabilities = { opened = "capabilities" },
            onOpenNumbers = { opened = "numbers" },
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_PERSONA_ROW_DESCRIPTION)
            .performClick()
        assertEquals("persona", opened)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION)
            .performClick()
        assertEquals("capabilities", opened)

        // ⚠️ Points at the EXISTING marketplace screen rather than a second numbers view; a
        // duplicate under settings would be a second place for its read-only boundary to drift.
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_NUMBERS_ROW_DESCRIPTION)
            .performClick()
        assertEquals("numbers", opened)
    }

    @Test
    fun `the hub says which settings are not here`() {
        // ⛔ A SETTINGS SCREEN THAT STOPS WITHOUT SAYING WHY READS AS A HALF-BUILT ONE. What is
        // absent is campaign settings and the avatar form.
        // ⛔ THE NOTE NAMES NO DESTINATION (no "web dashboard"), because App Store Guideline 3.1.1
        // forbids it. The note still has to exist and still has to say what is missing, which is
        // what this matches on.
        render()

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_MORE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("not available in this app", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a viewer gets a different note, because 'the rest is elsewhere' would be false for them`() {
        // ⛔ TWO ROWS OUT OF EIGHT WITH NO EXPLANATION READS AS A BROKEN SCREEN. And telling a
        // viewer the rest is edited elsewhere would be false twice: the two sections they CAN see
        // are editable here for anyone else, and a viewer cannot edit anything anywhere either.
        render(canMutate = false)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_MORE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("read-only access", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("not available in this app", substring = true).assertDoesNotExist()
    }
}
