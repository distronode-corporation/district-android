package com.distronode.districtai.ui.settings.workspace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
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

    // ── Loading, leaving and saving ──────────────────────────────────────────

    @Test
    fun `loading draws skeletons and no controls`() {
        render(RoutingRulesUiState())

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOADING_DESCRIPTION)
            .assertIsDisplayed()
        ROUTING_MUTATING_DESCRIPTIONS.forEach { handle ->
            composeRule.onNodeWithContentDescription(handle).assertDoesNotExist()
        }
    }

    @Test
    fun `back with unsaved rules asks first, and keeping editing stays`() {
        var backs = 0
        render(ready().copy(draft = emptyList()), onBack = { backs += 1 })

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_KEEP_EDITING_DESCRIPTION)
            .performClick()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
            .assertDoesNotExist()
        assertEquals(0, backs)

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `back with nothing pending leaves at once`() {
        var backs = 0
        render(ready(), onBack = { backs += 1 })

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `a save in flight locks every rule and says it is saving`() {
        render(ready().copy(draft = ready().rules.take(1), save = SaveState.Saving))

        composeRule.onNodeWithContentDescription(routingFieldDescription(0, RoutingRuleField.VOICE))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(routingFieldDescription(0, RoutingRuleField.VALUE))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(routingRemoveDescription(0)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(ROUTING_ADD_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithText("Saving", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a stored voice outside the list is offered as its own option rather than replaced`() {
        // ⛔ THE REAL ALLOW-LIST IS PER WORKSPACE AND INVISIBLE HERE, so a voice this list does not
        // carry is not necessarily wrong, and a picker that could not re-select it would turn
        // "open the menu and close it" into an edit.
        var edited: Pair<RoutingRuleField, String>? = null
        render(
            ready(Json.parseToJsonElement("""[{"id":"r","field":"industry","voice":"Nova"}]""")),
            onEdit = { _, field, value -> edited = field to value },
        )
        val picker = routingFieldDescription(0, RoutingRuleField.VOICE)

        composeRule.onNodeWithContentDescription(picker).performClick()
        composeRule.onNodeWithContentDescription(routingOptionDescription(picker, "Nova")).performClick()

        assertEquals(RoutingRuleField.VOICE to "Nova", edited)
    }

    @Test
    fun `every control reports its own rule and field after the screen recomposes`() {
        // ⚠️ REDRAWN FROM A NEW STATE WITH THE SAME CALLBACKS, as on every edit in production. A
        // control that kept a stale index across the redraw would rewrite the wrong rule.
        val calls = mutableListOf<String>()
        val current = mutableStateOf(ready())
        composeRule.setContent {
            DistrictTheme {
                RoutingRulesScreen(
                    state = current.value,
                    onAdd = { calls += "add" },
                    // ⚠️ Folded back into the state the way the ViewModel does, so a box shows what
                    // was typed rather than snapping back to its old value.
                    onEdit = { index, field, value ->
                        calls += "edit:$index:$field:$value"
                        val rules = current.value.rules.toMutableList()
                        rules[index] = rules[index].with(field, value)
                        current.value = current.value.copy(draft = rules)
                    },
                    onRemove = { calls += "remove:$it" },
                    onSave = { calls += "save" },
                    onRetry = { calls += "retry" },
                    onBack = { calls += "back" },
                )
            }
        }

        current.value = ready().let {
            it.copy(draft = listOf(it.rules[0], it.rules[1].with(RoutingRuleField.VALUE, "health")))
        }
        composeRule.waitForIdle()

        fun pick(field: RoutingRuleField, option: String) {
            val picker = routingFieldDescription(1, field)
            composeRule.onNodeWithContentDescription(picker).performClick()
            composeRule.onNodeWithContentDescription(routingOptionDescription(picker, option))
                .performClick()
        }
        pick(RoutingRuleField.FIELD, "industry")
        pick(RoutingRuleField.OPERATOR, "equals")
        pick(RoutingRuleField.VOICE, "Kore")
        composeRule.onNodeWithContentDescription(routingFieldDescription(1, RoutingRuleField.VALUE))
            .performTextReplacement("retail")
        composeRule.onNodeWithContentDescription(
            routingFieldDescription(1, RoutingRuleField.INSTRUCTION),
        ).performTextReplacement("Be kind.")
        composeRule.onNodeWithContentDescription(routingRemoveDescription(1)).performClick()
        composeRule.onNodeWithContentDescription(ROUTING_ADD_DESCRIPTION).performClick()

        // The confirmation, carried across another redraw before it is accepted.
        composeRule.onNodeWithContentDescription(ROUTING_SAVE_DESCRIPTION).performClick()
        current.value = current.value.copy(draft = current.value.rules.take(1))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(ROUTING_CONFIRM_DESCRIPTION).performClick()

        assertEquals(
            listOf(
                "edit:1:FIELD:industry",
                "edit:1:OPERATOR:equals",
                "edit:1:VOICE:Kore",
                "edit:1:VALUE:retail",
                "edit:1:INSTRUCTION:Be kind.",
                "remove:1",
                "add",
                "save",
            ),
            calls,
        )
    }
}
