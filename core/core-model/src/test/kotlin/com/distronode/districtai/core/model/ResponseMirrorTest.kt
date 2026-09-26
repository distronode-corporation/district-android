package com.distronode.districtai.core.model

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every response fixture that no round trip covered yet, held against both encodings.
 *
 * WHAT THIS ADDS TO THE DECODE ASSERTIONS. The contract classes beside this one prove each fixture
 * DECODES, and a few of them (analytics, usage, billing, devices, meetings, workflows, scheduling)
 * already prove it round-trips. The rest had no check that the model keeps what it read. A property
 * whose default happens to equal the value that arrived, or whose serial name drifted away from the
 * server's key, decodes cleanly and passes every assertion that only reads it. [WireMirror] catches
 * both: the verbose encoding must give back every key the server sent, and the omit-defaults
 * encoding (the production one) must write nothing the server did not.
 *
 * One test per surface, so a failure names the area it broke rather than a fixture index.
 */
class ResponseMirrorTest {

    @Test
    fun `the contact reads write back what they decoded`() {
        val list = WireMirror.assertMirrors(ContactListResponse.serializer(), "district-contacts.json")
        val detail = WireMirror.assertMirrors(ContactDetailResponse.serializer(), "district-contact-detail.json")
        WireMirror.assertMirrors(ContactMutationResponse.serializer(), "district-contact-update.json")
        WireMirror.assertMirrors(ContactMutationResponse.serializer(), "district-contact-delete.json")

        // Guards against a vacuous pass: the fixtures must carry rows and nested objects to mirror.
        assertTrue("the contact list fixture must carry contacts", list.contacts.isNotEmpty())
        assertTrue("the contact detail fixture must carry a contact", detail.contact != null)
    }

    @Test
    fun `the call reads write back what they decoded`() {
        val calls = WireMirror.assertMirrors(ListSerializer(CallSummary.serializer()), "district-calls.json")
        WireMirror.assertMirrors(CallDetailResponse.serializer(), "district-call-detail.json")
        WireMirror.assertMirrors(CallTranscriptResponse.serializer(), "district-call-transcript.json")
        WireMirror.assertMirrors(DialResponse.serializer(), "district-dial.json")

        assertTrue("the calls fixture must carry calls", calls.isNotEmpty())
    }

    @Test
    fun `the inbox reads and writes echo back what they decoded`() {
        val conversations =
            WireMirror.assertMirrors(ConversationsResponse.serializer(), "district-conversations.json")
        WireMirror.assertMirrors(SendMessageResponse.serializer(), "district-message-send.json")
        WireMirror.assertMirrors(SendMessageResponse.serializer(), "district-message-send-email.json")
        WireMirror.assertMirrors(SendMessageResponse.serializer(), "district-message-send-media.json")
        WireMirror.assertMirrors(MarkReadResponse.serializer(), "district-message-mark-read.json")
        WireMirror.assertMirrors(UnreadCountResponse.serializer(), "district-messages-unread-count.json")
        WireMirror.assertMirrors(MessageThreadResponse.serializer(), "district-message-thread.json")

        assertTrue("the conversations fixture must carry threads", conversations.conversations.isNotEmpty())
    }

    @Test
    fun `the composer's reads and writes echo back what they decoded`() {
        WireMirror.assertMirrors(MediaUploadResponse.serializer(), "district-media-upload.json")
        WireMirror.assertMirrors(DraftResponse.serializer(), "district-draft.json")
        WireMirror.assertMirrors(DraftResponse.serializer(), "district-draft-null.json")
        WireMirror.assertMirrors(DraftResponse.serializer(), "district-draft-put.json")
        WireMirror.assertMirrors(DraftListResponse.serializer(), "district-drafts-list.json")
        WireMirror.assertMirrors(DraftDeleteResponse.serializer(), "district-draft-delete.json")
        WireMirror.assertMirrors(AiDraftResponse.serializer(), "district-ai-draft.json")
    }

    @Test
    fun `the HQ assistant's answers write back what they decoded`() {
        val answer = WireMirror.assertMirrors(HqPromptResponse.serializer(), "district-hq-answer.json")
        val pending = WireMirror.assertMirrors(HqPromptResponse.serializer(), "district-hq-pending-write.json")
        WireMirror.assertMirrors(HqConfirmResponse.serializer(), "district-hq-confirm.json")

        // The two prompt fixtures are the two branches of one route, and must stay different.
        assertTrue("the answer and the pending write must differ", answer != pending)
    }

    @Test
    fun `the desk's reads and writes echo back what they decoded`() {
        WireMirror.assertMirrors(DeskSettingsResponse.serializer(), "district-desk-settings.json")
        WireMirror.assertMirrors(DeskSettingsResponse.serializer(), "district-desk-settings-patch.json")
        WireMirror.assertMirrors(DeskSettingsResponse.serializer(), "district-desk-logo.json")
        WireMirror.assertMirrors(DeskLogoRemovalResponse.serializer(), "district-desk-logo-delete.json")
        val tickets = WireMirror.assertMirrors(DeskTicketsResponse.serializer(), "district-desk-tickets.json")
        WireMirror.assertMirrors(DeskTicketCreateResponse.serializer(), "district-desk-ticket-create.json")
        val ticket = WireMirror.assertMirrors(DeskTicketResponse.serializer(), "district-desk-ticket.json")
        WireMirror.assertMirrors(DeskReplyResponse.serializer(), "district-desk-ticket-reply.json")
        WireMirror.assertMirrors(DeskTicketStatusResponse.serializer(), "district-desk-ticket-status.json")

        assertTrue("the ticket list fixture must carry tickets", tickets.tickets.isNotEmpty())
        assertTrue("the ticket read must carry a thread", ticket.ticket?.messages.orEmpty().isNotEmpty())
    }

    @Test
    fun `the support desk's reads and writes echo back what they decoded`() {
        val requests =
            WireMirror.assertMirrors(SupportRequestListResponse.serializer(), "district-support-requests.json")
        WireMirror.assertMirrors(SupportRequestCreateResponse.serializer(), "district-support-request-create.json")
        val detail =
            WireMirror.assertMirrors(SupportRequestDetailResponse.serializer(), "district-support-request.json")
        WireMirror.assertMirrors(SupportReplyResponse.serializer(), "district-support-reply.json")
        WireMirror.assertMirrors(SupportCloseResponse.serializer(), "district-support-close.json")

        assertTrue("the request list fixture must carry requests", requests.requests.isNotEmpty())
        assertTrue("the request read must carry a thread", detail.request?.messages.orEmpty().isNotEmpty())
    }

    @Test
    fun `the persona and messaging settings write back what they decoded`() {
        WireMirror.assertMirrors(PersonaOptionsResponse.serializer(), "district-persona-options.json")
        WireMirror.assertMirrors(PersonaPreviewTokenResponse.serializer(), "district-persona-preview-token.json")
        WireMirror.assertMirrors(MessagingResponse.serializer(), "district-messaging.json")
        WireMirror.assertMirrors(MessagingResponse.serializer(), "district-messaging-unmanaged.json")
        WireMirror.assertMirrors(MessagingAccountSaveResponse.serializer(), "district-messaging-upsert.json")
        WireMirror.assertMirrors(MessagingDefaultResponse.serializer(), "district-messaging-set-default.json")
        WireMirror.assertMirrors(MessagingDefaultResponse.serializer(), "district-messaging-delete.json")
        WireMirror.assertMirrors(
            MessagingChannelDefaultResponse.serializer(),
            "district-messaging-channel-default.json",
        )
        WireMirror.assertMirrors(MessagingMetaResponse.serializer(), "district-messaging-meta.json")
        WireMirror.assertMirrors(MessagingTestResponse.serializer(), "district-messaging-test.json")
        WireMirror.assertMirrors(MessagingTestResponse.serializer(), "district-messaging-test-rejected.json")
    }

    @Test
    fun `the knowledge, members and workspace reads write back what they decoded`() {
        WireMirror.assertMirrors(KnowledgeListResponse.serializer(), "district-knowledge.json")
        WireMirror.assertMirrors(KnowledgeCreateResponse.serializer(), "district-knowledge-create.json")
        WireMirror.assertMirrors(KnowledgeDeleteResponse.serializer(), "district-knowledge-delete.json")
        WireMirror.assertMirrors(KnowledgeModeResponse.serializer(), "district-knowledge-mode.json")
        WireMirror.assertMirrors(KnowledgeModeResponse.serializer(), "district-knowledge-mode-patch.json")
        WireMirror.assertMirrors(MemberListResponse.serializer(), "district-members.json")
        WireMirror.assertMirrors(MemberMutationResponse.serializer(), "district-member-add.json")
        WireMirror.assertMirrors(MemberMutationResponse.serializer(), "district-member-role-patch.json")
        WireMirror.assertMirrors(MemberMutationResponse.serializer(), "district-member-remove.json")
        WireMirror.assertMirrors(RenameResponse.serializer(), "district-rename.json")
        val workspaces =
            WireMirror.assertMirrors(WorkspaceListResponse.serializer(), "district-workspace-list.json")
        WireMirror.assertMirrors(OverviewResponse.serializer(), "district-overview.json")
        WireMirror.assertMirrors(DistrictSetupResponse.serializer(), "district-setup.json")
        WireMirror.assertMirrors(DeviceRevokeResponse.serializer(), "district-native-revoke.json")

        assertTrue("the workspace list fixture must carry workspaces", workspaces.workspaces.isNotEmpty())
    }

    @Test
    fun `a renamed key is caught even though the model round-trips against itself`() {
        // Proves the mirror is not vacuous: a tree whose key differs from the one the server sent
        // must be refused, even though each side is a perfectly valid encoding of something.
        val sent = ContractFixtures.json.parseToJsonElement("""{"success":true,"marked":2}""")
        val renamed = ContractFixtures.json.parseToJsonElement("""{"success":true,"markedCount":2}""")
        val changed = ContractFixtures.json.parseToJsonElement("""{"success":true,"marked":3}""")
        val widened = ContractFixtures.json.parseToJsonElement("""{"success":true,"marked":2.0}""")

        assertRefused { WireMirror.assertCovered("rename", sent, renamed) }
        assertRefused { WireMirror.assertCovered("value", sent, changed) }
        // The same number written with a fraction is still the same JSON number.
        WireMirror.assertCovered("number", sent, widened)
    }

    private fun assertRefused(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertEquals(AssertionError::class.java, failure?.javaClass)
    }
}
