package com.distronode.districtai.ui.settings.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.ToolConfig
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the capabilities screen draws — and the two things it must never draw: a toggle when the
 * configuration did not load, and a shorter list than the workspace actually has.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT — see PersonaFormScreenTest. Ten rows plus two save sections do not fit a
// phone-sized Robolectric display, and `assertIsDisplayed` checks visible bounds rather than
// presence.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class CapabilitiesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** ⛔ Carries `transfer_to_creator`, which this client's catalog does not know. */
    private val storedTools = listOf("search_knowledge_base", "transfer_to_creator", "leave_message")

    private val loaded = ConfigState.Ready(
        WorkspaceConfig(
            aiPersona = AiPersona(dgiEnabled = false),
            toolConfig = ToolConfig(
                allowedTools = storedTools,
                supportPhoneNumber = "+14165550123",
            ),
        ),
    )

    private fun render(
        state: CapabilitiesUiState,
        onToggleTool: (String, Boolean) -> Unit = { _, _ -> },
        onSaveTools: () -> Unit = {},
        onToggleEnrichment: (Boolean) -> Unit = {},
        onSaveEnrichment: () -> Unit = {},
        onRetry: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                CapabilitiesScreen(
                    state = state,
                    onToggleTool = onToggleTool,
                    onSaveTools = onSaveTools,
                    onToggleEnrichment = onToggleEnrichment,
                    onSaveEnrichment = onSaveEnrichment,
                    onRetry = onRetry,
                    onBack = onBack,
                )
            }
        }
    }

    // ── The load gate ────────────────────────────────────────────────────────

    @Test
    fun `a failed load offers a retry and NOT ONE switch or save control`() {
        // ⛔ THE ASSERTION THE WHOLE PACKAGE EXISTS TO MAKE, AND IT IS SHARPEST HERE:
        // `PATCH workspace/tools` replaces the allowlist wholesale, so a switchable list rendered
        // from no configuration is a deletion waiting for a tap. Walked over the catalog AND the
        // declared mutating handles, so a control added later cannot fall out of this check.
        render(
            CapabilitiesUiState(
                load = ConfigState.LoadFailed(FailureText(UiText.Literal("Could not reach the server."))),
            ),
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION)
            .assertIsDisplayed()

        CAPABILITY_CATALOG.forEach { id ->
            composeRule.onNodeWithContentDescription(capabilityToggleDescription(id))
                .assertDoesNotExist()
        }
        CAPABILITIES_MUTATING_DESCRIPTIONS.forEach { handle ->
            composeRule.onNodeWithContentDescription(handle).assertDoesNotExist()
        }
    }

    @Test
    fun `the retry fires the reload`() {
        var retries = 0
        render(
            CapabilitiesUiState(
                load = ConfigState.LoadFailed(FailureText(UiText.Literal("Offline."))),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `loading draws skeletons and no switches`() {
        render(CapabilitiesUiState())

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOADING_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(capabilityToggleDescription("leave_message"))
            .assertDoesNotExist()
    }

    // ── The loaded list ──────────────────────────────────────────────────────

    @Test
    fun `every catalog capability is drawn, on or off according to what is stored`() {
        render(CapabilitiesUiState(load = loaded))

        composeRule.onNodeWithContentDescription(capabilityToggleDescription("leave_message"))
            .assertIsOn()
        composeRule.onNodeWithContentDescription(capabilityToggleDescription("search_knowledge_base"))
            .assertIsOn()
        // Not in the stored list, so off — and still present, so it can be turned on.
        composeRule.onNodeWithContentDescription(capabilityToggleDescription("book_appointment"))
            .assertIsOff()
    }

    @Test
    fun `a stored capability this app cannot name is drawn by its raw id and explains itself`() {
        // ⛔ HIDING IT WOULD DELETE IT ON THE NEXT SAVE, because the list is replaced wholesale.
        render(CapabilitiesUiState(load = loaded))

        composeRule.onNodeWithText("transfer_to_creator").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(capabilityToggleDescription("transfer_to_creator"))
            .assertIsOn()
        composeRule.onNodeWithText(
            "Stored for this workspace. This version of the app does not have a name for it, and " +
                "leaving it on keeps it.",
        ).assertIsDisplayed()
    }

    @Test
    fun `flipping a switch reports the id and the new value`() {
        var toggled: Pair<String, Boolean>? = null
        render(CapabilitiesUiState(load = loaded), onToggleTool = { id, on -> toggled = id to on })

        composeRule.onNodeWithContentDescription(capabilityToggleDescription("leave_message"))
            .performClick()

        assertEquals("leave_message" to false, toggled)
    }

    @Test
    fun `the transfer-to-human prerequisite is named only when it is genuinely missing`() {
        // ⚠️ The ONE prerequisite this screen can observe. The calendar connection lives behind a
        // route this client does not call, so nothing is claimed about the scheduling tools — a
        // warning that is sometimes wrong trains people to ignore all of them.
        render(CapabilitiesUiState(load = loaded))

        composeRule.onNodeWithText("Needs a support team number, which cannot be set in this app.")
            .assertDoesNotExist()
    }

    @Test
    fun `a workspace with no support number is told what transfer-to-human needs`() {
        render(
            CapabilitiesUiState(
                load = ConfigState.Ready(
                    WorkspaceConfig(toolConfig = ToolConfig(allowedTools = storedTools)),
                ),
            ),
        )

        composeRule.onNodeWithText("Needs a support team number, which cannot be set in this app.")
            .assertIsDisplayed()
    }

    // ── The two saves ────────────────────────────────────────────────────────

    @Test
    fun `both save buttons are disabled until their own section is dirty`() {
        render(CapabilitiesUiState(load = loaded))

        composeRule.onNodeWithContentDescription(CAPABILITIES_SAVE_TOOLS_DESCRIPTION)
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(CAPABILITIES_SAVE_ENRICHMENT_DESCRIPTION)
            .assertIsNotEnabled()
    }

    @Test
    fun `a dirty allowlist enables its own save and NOT the enrichment one`() {
        // ⚠️ TWO ROUTES, TWO BUTTONS. One tap must never write through both.
        render(
            CapabilitiesUiState(load = loaded, toolToggles = mapOf("leave_message" to false)),
        )

        composeRule.onNodeWithContentDescription(CAPABILITIES_SAVE_TOOLS_DESCRIPTION)
            .assertIsEnabled()
        composeRule.onNodeWithContentDescription(CAPABILITIES_SAVE_ENRICHMENT_DESCRIPTION)
            .assertIsNotEnabled()
    }

    @Test
    fun `a dirty enrichment switch enables its own save and NOT the tools one`() {
        render(CapabilitiesUiState(load = loaded, enrichmentDraft = true))

        composeRule.onNodeWithContentDescription(CAPABILITIES_ENRICHMENT_TOGGLE_DESCRIPTION)
            .assertIsOn()
        composeRule.onNodeWithContentDescription(CAPABILITIES_SAVE_ENRICHMENT_DESCRIPTION)
            .assertIsEnabled()
        composeRule.onNodeWithContentDescription(CAPABILITIES_SAVE_TOOLS_DESCRIPTION)
            .assertIsNotEnabled()
    }

    @Test
    fun `saving disables every switch on the screen, including the other section's`() {
        // ⚠️ Two writes racing would leave their re-reads racing too, and the loser would
        // overwrite the winner's baseline.
        render(
            CapabilitiesUiState(
                load = loaded,
                toolToggles = mapOf("leave_message" to false),
                toolsSave = SaveState.Saving,
            ),
        )

        composeRule.onNodeWithContentDescription(capabilityToggleDescription("leave_message"))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(CAPABILITIES_ENRICHMENT_TOGGLE_DESCRIPTION)
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(CAPABILITIES_SAVE_TOOLS_DESCRIPTION)
            .assertIsNotEnabled()
    }

    @Test
    fun `each section reports its own save outcome`() {
        render(
            CapabilitiesUiState(
                load = loaded,
                toolsSave = SaveState.Failed(FailureText(UiText.Literal("Invalid payload"))),
                enrichmentSave = SaveState.Saved,
            ),
        )

        composeRule.onNodeWithText("Invalid payload").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CAPABILITIES_ENRICHMENT_NOTICE_DESCRIPTION)
            .assertIsDisplayed()
    }

    // ── Leaving with pending edits ───────────────────────────────────────────

    @Test
    fun `back with a pending toggle asks before discarding it`() {
        var backs = 0
        render(
            CapabilitiesUiState(load = loaded, toolToggles = mapOf("leave_message" to false)),
            onBack = { backs += 1 },
        )

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
            .assertIsDisplayed()
        assertEquals(0, backs)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `a pending ENRICHMENT change also trips the guard`() {
        // ⚠️ Either section counts: the guard is about leaving work behind, not about which route
        // would have written it.
        var backs = 0
        render(
            CapabilitiesUiState(load = loaded, enrichmentDraft = true),
            onBack = { backs += 1 },
        )

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
            .assertIsDisplayed()
        assertEquals(0, backs)
    }

    @Test
    fun `back with nothing pending leaves immediately`() {
        var backs = 0
        render(CapabilitiesUiState(load = loaded), onBack = { backs += 1 })

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
            .assertDoesNotExist()
        assertEquals(1, backs)
    }
}
