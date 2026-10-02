package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.KB_MODE_INTERNAL
import com.distronode.districtai.core.model.KB_MODE_LINKED
import com.distronode.districtai.core.model.KnowledgeDocument
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_TELNYX
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_TWILIO
import com.distronode.districtai.core.model.MESSAGING_SOURCE_BYOK
import com.distronode.districtai.core.model.MessagingAccount
import com.distronode.districtai.core.model.MessagingResponse
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The derived rules of the four screens that are NOT wholesale replaces: knowledge, members,
 * capabilities' enrichment switch, and messaging.
 *
 * ⚠️ EACH [busy] IS A UNION OF SEPARATE WRITES, AND EVERY MEMBER OF THE UNION IS PINNED ON ITS
 * OWN. A write left out of the union is a form that stays enabled while that write is in flight,
 * which is how a second tap reaches the server.
 */
class WorkspaceRosterUiStateTest {

    private val failure = FailureText(UiText.Literal("offline"))

    // ── Knowledge ────────────────────────────────────────────────────────────

    @Test
    fun `the document list is empty until it has been read`() {
        assertEquals(emptyList<KnowledgeDocument>(), KnowledgeUiState().documents)
        assertEquals(
            emptyList<KnowledgeDocument>(),
            KnowledgeUiState(list = KnowledgeListState.Failed(failure)).documents,
        )
    }

    @Test
    fun `every knowledge write makes the screen busy on its own`() {
        val ready = KnowledgeUiState(
            canWrite = true,
            mode = KB_MODE_INTERNAL,
            draftTitle = "Hours",
            draftContent = "Nine to five.",
        )
        assertTrue(ready.canAdd)
        assertTrue(ready.canChangeMode)

        listOf(
            ready.copy(addSave = SaveState.Saving),
            ready.copy(deleteSave = SaveState.Saving),
            ready.copy(modeSave = SaveState.Saving),
        ).forEach { busy ->
            assertTrue(busy.busy)
            assertFalse(busy.canAdd)
            assertFalse(busy.canChangeMode)
        }
    }

    @Test
    fun `the mode cannot be changed before it has been read`() {
        // ⚠️ NULL IS NOT `internal`. Offering a switch away from a mode nobody read would be an
        // edit made against an unknown value.
        assertFalse(KnowledgeUiState(canWrite = true, mode = null).canChangeMode)
    }

    @Test
    fun `only a move INTO linked is a residency change`() {
        val internal = KnowledgeUiState(mode = KB_MODE_INTERNAL)
        assertTrue(internal.isResidencyChange(KB_MODE_LINKED))
        assertFalse(internal.isResidencyChange(KB_MODE_INTERNAL))

        val linked = KnowledgeUiState(mode = KB_MODE_LINKED)
        // Already linked, so nothing new leaves the region.
        assertFalse(linked.isResidencyChange(KB_MODE_LINKED))
        assertFalse(linked.isResidencyChange(KB_MODE_INTERNAL))
    }

    // ── Members ──────────────────────────────────────────────────────────────

    @Test
    fun `the roster is empty until it has been read`() {
        assertEquals(emptyList<WorkspaceMember>(), MembersUiState().members)
        assertEquals(
            emptyList<WorkspaceMember>(),
            MembersUiState(list = MembersListState.Failed(failure)).members,
        )
        assertFalse(MembersUiState().loaded)
    }

    @Test
    fun `every membership write makes the screen busy on its own`() {
        val ready = MembersUiState(
            list = MembersListState.Ready(emptyList()),
            canManage = true,
            canRename = true,
            draftEmail = "new@example.com",
            renameDraft = "Acme",
        )
        assertTrue(ready.canAdd)
        assertTrue(ready.canRenameNow)

        listOf(
            ready.copy(addSave = SaveState.Saving),
            ready.copy(roleSave = SaveState.Saving),
            ready.copy(removeSave = SaveState.Saving),
            ready.copy(renameSave = SaveState.Saving),
        ).forEach { busy ->
            assertTrue(busy.busy)
            assertFalse(busy.canAdd)
            assertFalse(busy.canRenameNow)
        }
    }

    // ── Capabilities: the enrichment switch ──────────────────────────────────

    @Test
    fun `the enrichment switch reads off wherever nothing says on`() {
        // ⛔ OFF UNTIL SOMEONE TURNS IT ON. Unloaded, a workspace with no persona, and a persona
        // that never answered all render as off.
        assertFalse(CapabilitiesUiState().enrichmentEnabled)
        assertFalse(CapabilitiesUiState(load = ConfigState.Ready(WorkspaceConfig())).enrichmentEnabled)
        assertFalse(
            CapabilitiesUiState(
                load = ConfigState.Ready(WorkspaceConfig(aiPersona = AiPersona(dgiEnabled = null))),
            ).enrichmentEnabled,
        )
        assertTrue(
            CapabilitiesUiState(
                load = ConfigState.Ready(WorkspaceConfig(aiPersona = AiPersona(dgiEnabled = true))),
            ).enrichmentEnabled,
        )
    }

    @Test
    fun `an enrichment draft is dirty only against what is stored`() {
        val storedOn = ConfigState.Ready(WorkspaceConfig(aiPersona = AiPersona(dgiEnabled = true)))

        assertFalse(CapabilitiesUiState(load = storedOn, enrichmentDraft = true).enrichmentDirty)
        assertTrue(CapabilitiesUiState(load = storedOn, enrichmentDraft = false).enrichmentDirty)
        assertTrue(
            "a workspace with no persona stores off",
            CapabilitiesUiState(
                load = ConfigState.Ready(WorkspaceConfig()),
                enrichmentDraft = true,
            ).enrichmentDirty,
        )

        // ⚠️ A draft against an unread config compares with off, and still cannot be saved.
        val unread = CapabilitiesUiState(enrichmentDraft = true)
        assertTrue(unread.enrichmentDirty)
        assertFalse(unread.canSaveEnrichment)
    }

    @Test
    fun `either section's save in flight disables both save buttons`() {
        val storedOff = ConfigState.Ready(WorkspaceConfig(aiPersona = AiPersona(dgiEnabled = false)))
        val both = CapabilitiesUiState(
            load = storedOff,
            enrichmentDraft = true,
            toolToggles = mapOf("send_sms" to false),
        )
        assertTrue(both.canSaveTools)
        assertTrue(both.canSaveEnrichment)

        listOf(
            both.copy(toolsSave = SaveState.Saving),
            both.copy(enrichmentSave = SaveState.Saving),
        ).forEach { busy ->
            assertFalse(busy.canSaveTools)
            assertFalse(busy.canSaveEnrichment)
        }
    }

    // ── Messaging ────────────────────────────────────────────────────────────

    @Test
    fun `every messaging write makes the form busy on its own`() {
        val ready = MessagingUiState(
            load = MessagingLoadState.Ready(MessagingResponse(success = true)),
            canEdit = true,
            creatorCellDraft = "+14165550170",
        )
        assertTrue(ready.canEditNow)

        listOf(
            ready.copy(accountSave = SaveState.Saving),
            ready.copy(defaultSave = SaveState.Saving),
            ready.copy(channelSave = SaveState.Saving),
            ready.copy(deleteSave = SaveState.Saving),
            ready.copy(metaSave = SaveState.Saving),
            ready.copy(test = MessagingTestState.Running),
        ).forEach { busy ->
            assertTrue(busy.busy)
            assertFalse(busy.canEditNow)
            assertFalse(busy.canSaveCreatorCell)
        }
    }

    @Test
    fun `an account the read carried without a provider seeds the form as Twilio`() {
        // ⚠️ THE ROUTE'S OWN DEFAULTS. A row stored before the provider column existed is a Twilio
        // BYOK account, and the form must open on what the server will treat it as.
        val draft = MessagingDraft.of(MessagingAccount(id = "acct-legacy"))

        assertEquals(MESSAGING_PROVIDER_TWILIO, draft.provider)
        assertEquals(MESSAGING_SOURCE_BYOK, draft.credentialSource)
        assertEquals("", draft.label)
        assertEquals(MESSAGING_PROVIDER_TWILIO, draft.originalProvider)
        assertFalse("an unchanged provider keeps the stored secrets", draft.secretsRequired)
    }

    @Test
    fun `switching an existing account's provider requires fresh secrets`() {
        // ⛔ THE ROUTE REUSES STORED SECRETS ONLY WHEN THE PROVIDER IS UNCHANGED.
        val draft = MessagingDraft.of(
            MessagingAccount(id = "acct-1", provider = MESSAGING_PROVIDER_TWILIO, label = "Main"),
        )

        assertTrue("blank boxes keep the stored secrets", draft.canSave)

        val switched = draft.copy(provider = MESSAGING_PROVIDER_TELNYX)
        assertTrue(switched.secretsRequired)
        assertFalse("blank boxes would now store no credential at all", switched.canSave)
    }
}
