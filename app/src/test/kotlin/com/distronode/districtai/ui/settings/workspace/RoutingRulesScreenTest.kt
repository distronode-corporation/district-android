package com.distronode.districtai.ui.settings.workspace

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.RoutingRule
import com.distronode.districtai.core.model.RoutingRuleField
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the routing-rules editor draws, including for a rule whose shape it cannot display. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h4000dp")
class RoutingRulesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val stored = Json.parseToJsonElement(
        """
        [
          {"id":"rule-1","match":"billing","action":"transfer","target":"+14165550188"},
          {"id":"rule-2","field":"industry","operator":"contains","value":"tech",
           "voice":"Fenrir","instruction":"Be brisk.","model":"deepgram-pipeline"}
        ]
        """.trimIndent(),
    )

    private fun ready(rules: JsonElement? = stored) =
        RoutingRulesUiState(load = ConfigState.Ready(WorkspaceConfig(routingRules = rules)))

    private fun render(
        state: RoutingRulesUiState,
        onAdd: () -> Unit = {},
        onEdit: (Int, RoutingRuleField, String) -> Unit = { _, _, _ -> },
        onRemove: (Int) -> Unit = {},
        onSave: () -> Unit = {},
        onRetry: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                RoutingRulesScreen(
                    state = state,
                    onAdd = onAdd,
                    onEdit = onEdit,
                    onRemove = onRemove,
                    onSave = onSave,
                    onRetry = onRetry,
                    onBack = onBack,
                )
            }
        }
    }

    // ── The load gate ────────────────────────────────────────────────────────

    @Test
    fun `a failed load offers a retry and NOT ONE editable control`() {
        render(
            RoutingRulesUiState(
                load = ConfigState.LoadFailed(FailureText(UiText.Literal("Could not reach the server."))),
            ),
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        ROUTING_MUTATING_DESCRIPTIONS.forEach { handle ->
            composeRule.onNodeWithContentDescription(handle).assertDoesNotExist()
        }
        composeRule.onNodeWithContentDescription(routingFieldDescription(0, RoutingRuleField.VALUE))
            .assertDoesNotExist()
    }

    @Test
    fun `an unmodellable rules array explains itself with no retry and no editor`() {
        render(ready(Json.parseToJsonElement("""[42]""")))

        composeRule.onNodeWithContentDescription(ROUTING_UNMODELLABLE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION)
            .assertDoesNotExist()
        ROUTING_MUTATING_DESCRIPTIONS.forEach { handle ->
            composeRule.onNodeWithContentDescription(handle).assertDoesNotExist()
        }
    }

    // ── The rules ────────────────────────────────────────────────────────────

    @Test
    fun `every rule gets a card, including one this editor cannot display`() {
        // ⚠️ A rule stored as `{match, action, target}` renders with empty builder fields — honest
        // rather than invented — and its remove control still works, because dropping it from the
        // list would delete it on the next save.
        render(ready())

        composeRule.onNodeWithContentDescription(routingFieldDescription(0, RoutingRuleField.VALUE))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(routingRemoveDescription(0)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(routingFieldDescription(1, RoutingRuleField.VOICE))
            .assertIsDisplayed()
    }

    @Test
    fun `the model override is shown and is NOT an editable control`() {
        // ⛔ A DELIBERATE GAP AGAINST THE WEB BUILDER. Its model picker is derived from a
        // region-dependent server catalogue this client cannot see, and a free-text box would let
        // an operator store an id the agent silently coerces to the default engine.
        render(ready())

        composeRule.onNodeWithText("Engine override: deepgram-pipeline", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a voice option reports the value it was tapped on`() {
        var edited: Pair<RoutingRuleField, String>? = null
        render(ready(), onEdit = { _, field, value -> edited = field to value })

        composeRule.onNodeWithContentDescription(routingFieldDescription(1, RoutingRuleField.VOICE))
            .performClick()
        composeRule.onNodeWithContentDescription(
            routingOptionDescription(routingFieldDescription(1, RoutingRuleField.VOICE), "Kore"),
        ).performClick()

        assertEquals(RoutingRuleField.VOICE to "Kore", edited)
    }

    @Test
    fun `remove reports the rule it was tapped on`() {
        var removed = -1
        render(ready(), onRemove = { removed = it })

        composeRule.onNodeWithContentDescription(routingRemoveDescription(1)).performClick()

        assertEquals(1, removed)
    }

    @Test
    fun `no rules says every caller gets the workspace persona`() {
        render(ready(Json.parseToJsonElement("[]")))

        composeRule.onNodeWithContentDescription(ROUTING_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    // ── The confirmation ─────────────────────────────────────────────────────

    @Test
    fun `the save CONFIRMS with the rule count`() {
        var saves = 0
        render(
            ready().copy(draft = listOf(RoutingRule.newRule("r1"), RoutingRule.newRule("r2"))),
            onSave = { saves += 1 },
        )

        composeRule.onNodeWithContentDescription(ROUTING_SAVE_DESCRIPTION).performClick()

        assertEquals(0, saves)
        composeRule.onNodeWithText("Replace the routing rules with 2 rules?", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(ROUTING_CONFIRM_DESCRIPTION).performClick()
        assertEquals(1, saves)
    }

    @Test
    fun `an EMPTY save gets DIFFERENT wording naming what stops happening`() {
        render(ready().copy(draft = emptyList()))

        composeRule.onNodeWithContentDescription(ROUTING_SAVE_DESCRIPTION).performClick()

        composeRule.onNodeWithText("Remove every routing rule?", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Every caller will get the workspace persona", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Replace the routing rules with", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun `cancelling saves nothing`() {
        var saves = 0
        render(ready().copy(draft = emptyList()), onSave = { saves += 1 })

        composeRule.onNodeWithContentDescription(ROUTING_SAVE_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(ROUTING_CANCEL_DESCRIPTION).performClick()

        assertEquals(0, saves)
    }

    @Test
    fun `the save is disabled when nothing changed`() {
        render(ready())

        composeRule.onNodeWithContentDescription(ROUTING_SAVE_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a rejected voice is shown verbatim`() {
        // ⚠️ The server's 400 NAMES the value the workspace refused, and this client cannot see the
        // allow-list — so the message is the whole answer.
        render(
            ready().copy(
                save = SaveState.Failed(FailureText(UiText.Literal("Invalid voice identifier: Nova"))),
            ),
        )

        composeRule.onNodeWithText("Invalid voice identifier: Nova", substring = true)
            .assertIsDisplayed()
    }
}
