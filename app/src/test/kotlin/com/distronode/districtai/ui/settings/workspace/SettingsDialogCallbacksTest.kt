package com.distronode.districtai.ui.settings.workspace

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.CallHandlingResponse
import com.distronode.districtai.core.model.KB_MODE_INTERNAL
import com.distronode.districtai.core.model.KB_MODE_LINKED
import com.distronode.districtai.core.model.KnowledgeDocument
import com.distronode.districtai.core.model.MessagingAccount
import com.distronode.districtai.core.model.MessagingResponse
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every confirmation dialog in the settings screens, answered after the screen was handed NEW
 * callbacks while the dialog was open.
 *
 * ⛔ THE DIALOG MUST ACT THROUGH THE CALLBACK THE SCREEN WAS LAST GIVEN. The nav host rebuilds its
 * lambdas whenever it recomposes, and a dialog that held on to the instance it was opened with
 * would confirm a deletion, a replacement or a departure through a stale one. Each test opens a
 * dialog, bumps [generation] (which rebuilds every callback), and asserts the confirmation carries
 * the new generation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h4000dp")
class SettingsDialogCallbacksTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val generation = mutableIntStateOf(1)

    private val calls = mutableListOf<String>()

    private fun rebuildCallbacks() {
        generation.intValue += 1
        composeRule.waitForIdle()
    }

    private fun click(description: String) {
        composeRule.onNodeWithContentDescription(description).performClick()
    }

    private fun leaveThroughTheDiscardDialog() {
        click(TOP_BAR_BACK_DESCRIPTION)
        rebuildCallbacks()
        click(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
    }

    @Test
    fun `call handling discards through the latest back`() {
        val state = CallHandlingUiState(
            canMutate = true,
            load = CallHandlingLoad.Ready(CallHandlingResponse(success = true)),
            availability = AvailabilityLoad.Ready(AvailabilityResponse(success = true)),
            modeDraft = CallHandling.APP_FIRST,
        )
        composeRule.setContent {
            val g = generation.intValue
            DistrictTheme {
                CallHandlingScreen(
                    state = state,
                    onSelectMode = {},
                    onSelectRingSeconds = {},
                    onSave = {},
                    onSetAvailability = {},
                    onRetry = {},
                    onBack = { calls += "back:$g" },
                )
            }
        }

        leaveThroughTheDiscardDialog()

        assertEquals(listOf("back:2"), calls)
    }

    @Test
    fun `the directory replaces and discards through the latest callbacks`() {
        val loaded = DirectoryEditorUiState(
            load = ConfigState.Ready(
                WorkspaceConfig(
                    callDirectory = Json.parseToJsonElement("""[{"name":"Ops","phoneNumber":"+1"}]"""),
                ),
            ),
        )
        val state = loaded.copy(draft = emptyList())
        composeRule.setContent {
            val g = generation.intValue
            DistrictTheme {
                DirectoryEditorScreen(
                    state = state,
                    onEditNew = { _, _ -> },
                    onAdd = {},
                    onEdit = { _, _, _ -> },
                    onRemove = {},
                    onSave = { calls += "save:$g" },
                    onRetry = {},
                    onBack = { calls += "back:$g" },
                )
            }
        }

        click(DIRECTORY_SAVE_DESCRIPTION)
        rebuildCallbacks()
        click(DIRECTORY_CONFIRM_DESCRIPTION)
        leaveThroughTheDiscardDialog()

        assertEquals(listOf("save:2", "back:3"), calls)
    }

    @Test
    fun `the routing rules replace and discard through the latest callbacks`() {
        val state = RoutingRulesUiState(
            load = ConfigState.Ready(
                WorkspaceConfig(routingRules = Json.parseToJsonElement("""[{"id":"r"}]""")),
            ),
            draft = emptyList(),
        )
        composeRule.setContent {
            val g = generation.intValue
            DistrictTheme {
                RoutingRulesScreen(
                    state = state,
                    onAdd = {},
                    onEdit = { _, _, _ -> },
                    onRemove = {},
                    onSave = { calls += "save:$g" },
                    onRetry = {},
                    onBack = { calls += "back:$g" },
                )
            }
        }

        click(ROUTING_SAVE_DESCRIPTION)
        rebuildCallbacks()
        click(ROUTING_CONFIRM_DESCRIPTION)
        leaveThroughTheDiscardDialog()

        assertEquals(listOf("save:2", "back:3"), calls)
    }

    @Test
    fun `the persona form discards through the latest back`() {
        val state = PersonaFormUiState(
            load = ConfigState.Ready(WorkspaceConfig(aiPersona = AiPersona(name = "Ada"))),
            edits = mapOf(PersonaField.NAME to "Bea"),
        )
        composeRule.setContent {
            val g = generation.intValue
            DistrictTheme {
                PersonaFormScreen(
                    state = state,
                    onEdit = { _, _ -> },
                    onSave = {},
                    onRetry = {},
                    onBack = { calls += "back:$g" },
                    onSelectLanguage = {},
                    onSelectResponseLength = {},
                    onPreview = {},
                )
            }
        }

        leaveThroughTheDiscardDialog()

        assertEquals(listOf("back:2"), calls)
    }

    @Test
    fun `the capabilities screen discards through the latest back`() {
        val state = CapabilitiesUiState(
            load = ConfigState.Ready(WorkspaceConfig()),
            // A scheduling tool is off by default, so switching it on is a pending change.
            toolToggles = mapOf("list_event_types" to true),
        )
        composeRule.setContent {
            val g = generation.intValue
            DistrictTheme {
                CapabilitiesScreen(
                    state = state,
                    onToggleTool = { _, _ -> },
                    onSaveTools = {},
                    onToggleEnrichment = {},
                    onSaveEnrichment = {},
                    onRetry = {},
                    onBack = { calls += "back:$g" },
                )
            }
        }

        leaveThroughTheDiscardDialog()

        assertEquals(listOf("back:2"), calls)
    }

    @Test
    fun `messaging deletes and re-points a channel through the latest callbacks`() {
        val state = MessagingUiState(
            load = MessagingLoadState.Ready(
                MessagingResponse(
                    success = true,
                    accounts = listOf(MessagingAccount(id = "acct-1", provider = "twilio", label = "Main")),
                ),
            ),
            canEdit = true,
        )
        composeRule.setContent {
            val g = generation.intValue
            DistrictTheme {
                MessagingScreen(
                    state = state,
                    onStartEditing = {},
                    onEditDraft = {},
                    onSaveAccount = {},
                    onTestCredentials = {},
                    onSetDefault = {},
                    onSetChannelDefault = { channel, id -> calls += "channel:$channel:$id:$g" },
                    onDelete = { calls += "delete:$it:$g" },
                    onEditCreatorCell = {},
                    onSaveCreatorCell = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        click(messagingRemoveDescription("acct-1"))
        rebuildCallbacks()
        click(MESSAGING_DELETE_CONFIRM_DESCRIPTION)
        click(messagingChannelSetDescription("sms"))
        rebuildCallbacks()
        click(MESSAGING_CHANNEL_CONFIRM_DESCRIPTION)

        assertEquals(listOf("delete:acct-1:2", "channel:sms:acct-1:3"), calls)
    }

    @Test
    fun `members adds, removes and changes a role through the latest callbacks`() {
        val state = MembersUiState(
            list = MembersListState.Ready(listOf(WorkspaceMember("a@example.com", "client"))),
            canManage = true,
            canRename = true,
            draftEmail = "new@example.com",
        )
        composeRule.setContent {
            val g = generation.intValue
            DistrictTheme {
                MembersScreen(
                    state = state,
                    onEditEmail = {},
                    onEditRole = {},
                    onAdd = { calls += "add:$g" },
                    onChangeRole = { email, role -> calls += "role:$email:$role:$g" },
                    onRemove = { calls += "remove:$it:$g" },
                    onEditName = {},
                    onRename = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        click(MEMBERS_ADD_OPEN_DESCRIPTION)
        rebuildCallbacks()
        click(MEMBERS_ADD_CONFIRM_DESCRIPTION)
        click(memberRemoveDescription("a@example.com"))
        rebuildCallbacks()
        click(MEMBERS_REMOVE_CONFIRM_DESCRIPTION)
        click(memberRoleDescription("a@example.com"))
        rebuildCallbacks()
        click(memberRoleOptionDescription(WorkspaceRole.VIEWER))
        click(MEMBERS_ROLE_CONFIRM_DESCRIPTION)

        assertEquals(
            listOf("add:2", "remove:a@example.com:3", "role:a@example.com:VIEWER:4"),
            calls,
        )
    }

    @Test
    fun `knowledge deletes and switches mode through the latest callbacks`() {
        val state = KnowledgeUiState(
            list = KnowledgeListState.Ready(listOf(KnowledgeDocument(id = "doc-1", title = "Hours"))),
            canWrite = true,
            mode = KB_MODE_INTERNAL,
        )
        composeRule.setContent {
            val g = generation.intValue
            DistrictTheme {
                KnowledgeScreen(
                    state = state,
                    onEditTitle = {},
                    onEditContent = {},
                    onAdd = {},
                    onDelete = { calls += "delete:$it:$g" },
                    onSelectMode = { calls += "mode:$it:$g" },
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        click(knowledgeDeleteDescription("doc-1"))
        rebuildCallbacks()
        click(KNOWLEDGE_DELETE_CONFIRM_DESCRIPTION)
        click(knowledgeModeDescription(KB_MODE_LINKED))
        rebuildCallbacks()
        click(KNOWLEDGE_LINKED_CONFIRM_DESCRIPTION)

        assertEquals(listOf("delete:doc-1:2", "mode:$KB_MODE_LINKED:3"), calls)
    }
}
