package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.DirectoryField
import com.distronode.districtai.core.model.RoutingRule
import com.distronode.districtai.core.model.RoutingRuleField
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two wholesale-replace editors' derived rules: the transfer directory and the routing rules.
 *
 * ⛔ BOTH SAVES REPLACE A STORED ARRAY WITH EXACTLY WHAT THEY SEND, so the states that matter are
 * the ones BEFORE a successful read: nothing may be editable, nothing may be savable, and an
 * unloaded screen must not read as an empty list that a save would then write back.
 */
class WorkspaceEditorUiStateTest {

    private val failed = ConfigState.LoadFailed(FailureText(UiText.Literal("offline")))

    private val directory = ConfigState.Ready(
        WorkspaceConfig(
            callDirectory = Json.parseToJsonElement(
                """[{"name":"Ops desk","phoneNumber":"+14165550177"}]""",
            ),
        ),
    )

    private val rules = ConfigState.Ready(
        WorkspaceConfig(
            routingRules = Json.parseToJsonElement(
                """[{"id":"rule-1","field":"industry","operator":"contains","value":"tech"}]""",
            ),
        ),
    )

    // ── The transfer directory ───────────────────────────────────────────────

    @Test
    fun `before a read lands the directory has no baseline, no rows and no controls`() {
        listOf(ConfigState.Loading, failed).forEach { load ->
            val state = DirectoryEditorUiState(load = load)

            assertNull(state.baseline)
            assertEquals(emptyList<Any>(), state.entries)
            assertFalse(state.editable)
            assertFalse("a failed read is not an unmodellable value", state.unmodellable)
            assertFalse(state.canAdd)
            assertFalse(state.canSave)
        }
    }

    @Test
    fun `a filled pending row cannot be added while a save is in flight`() {
        val state = DirectoryEditorUiState(
            load = directory,
            newName = "Front desk",
            newPhoneNumber = "+14165550100",
        )
        assertTrue(state.canAdd)

        assertFalse(state.copy(save = SaveState.Saving).canAdd)
    }

    @Test
    fun `a text box reads its row's field, and an index past the list reads empty`() {
        val state = DirectoryEditorUiState(load = directory)

        assertEquals("Ops desk", state.fieldValue(0, DirectoryField.NAME))
        assertEquals("+14165550177", state.fieldValue(0, DirectoryField.PHONE_NUMBER))
        assertEquals("", state.fieldValue(1, DirectoryField.NAME))
        assertEquals("", state.fieldValue(-1, DirectoryField.NAME))
    }

    // ── The routing rules ────────────────────────────────────────────────────

    @Test
    fun `before a read lands there are no rules and no controls`() {
        listOf(ConfigState.Loading, failed).forEach { load ->
            val state = RoutingRulesUiState(load = load)

            assertNull(state.baseline)
            assertEquals(emptyList<RoutingRule>(), state.rules)
            assertFalse(state.editable)
            assertFalse(state.unmodellable)
            assertFalse(state.canSave)
        }
    }

    @Test
    fun `an edited rule set is savable, except while a save is in flight`() {
        val loaded = RoutingRulesUiState(load = rules)
        val edited = loaded.copy(
            draft = loaded.rules.map { it.with(RoutingRuleField.VALUE, "healthcare") },
        )
        assertTrue(edited.dirty)
        assertTrue(edited.canSave)

        assertFalse(edited.copy(save = SaveState.Saving).canSave)
        assertFalse("an untouched set is not savable", loaded.canSave)
    }
}
