package com.distronode.districtai.core.model

import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every row type, read on its own from the exact bytes a response nests it in.
 *
 * WHAT THIS ADDS. A row type's public `serializer()` returns the same generated `$serializer` its
 * parent's generated code calls, so reading a row on its own exercises no separate code path. What
 * these checks add is MIRRORING for rows in fixtures [ResponseMirrorTest] does not cover: each row
 * here must decode strictly from the exact subtree a response nests it in, and both of its
 * encodings must mirror that subtree, key for key. Where [ResponseMirrorTest] already mirrors the
 * parent fixture, the row line repeats that subtree and adds only a failure that names the row.
 *
 * ONE LINE PER ROW TYPE, grouped by surface, and every path is asserted to lead to an object.
 */
class RowMirrorTest {

    private fun <T> row(serializer: KSerializer<T>, fixture: String, path: String): T =
        WireMirror.assertRowMirrors(serializer, fixture, path)

    @Test
    fun `contact, call and phone rows read on their own`() {
        row(ContactCompany.serializer(), "district-contacts.json", "contacts.0.company")
        row(CallAnalysis.serializer(), "district-calls.json", "0.analysis")
        row(CallFollowUp.serializer(), "district-calls.json", "0.followUp")
        val intel = row(PhoneIntel.serializer(), "district-contact-detail.json", "phoneIntel")
        val region = row(PhoneRegion.serializer(), "district-contact-detail.json", "phoneIntel.region")
        row(OverviewMetrics.serializer(), "district-overview.json", "metrics")

        // The standalone read of the nested region is the same value the parent read carries.
        assertEquals(intel.region, region)
    }

    @Test
    fun `inbox and composer rows read on their own`() {
        row(ConversationSummary.serializer(), "district-conversations.json", "conversations.0")
        row(ConversationLastMessage.serializer(), "district-conversations.json", "conversations.0.lastMessage")
        row(SentMessage.serializer(), "district-message-send.json", "message")
        row(TimelinePageInfo.serializer(), "district-timeline-page.json", "pageInfo")
        row(MessageThreadMessage.serializer(), "district-message-thread.json", "message")
        row(MessageThreadTarget.serializer(), "district-message-thread.json", "thread")
        row(MessageDraft.serializer(), "district-drafts-list.json", "drafts.0")
        row(UploadedMedia.serializer(), "district-media-upload.json", "media")
        row(HqPendingWrite.serializer(), "district-hq-pending-write.json", "pendingWrite")
    }

    @Test
    fun `desk and support rows read on their own`() {
        val summary = row(DeskTicketSummary.serializer(), "district-desk-tickets.json", "tickets.0")
        val detail = row(DeskTicketDetail.serializer(), "district-desk-ticket.json", "ticket")
        row(DeskMessage.serializer(), "district-desk-ticket.json", "ticket.messages.0")
        row(SupportRequestSummary.serializer(), "district-support-requests.json", "requests.0")
        row(SupportRequestDetail.serializer(), "district-support-request.json", "request")
        row(SupportMessage.serializer(), "district-support-request.json", "request.messages.0")

        // Both desk rows carry the SAME source vocabulary, and a ticket filed from a call says so.
        assertEquals(summary.source == DESK_SOURCE_VOICE_CALL, summary.fromCall)
        assertEquals(detail.source == DESK_SOURCE_VOICE_CALL, detail.fromCall)
    }

    @Test
    fun `analytics, billing and usage rows read on their own`() {
        row(AnalyticsMetrics.serializer(), "district-analytics.json", "metrics")
        row(CallVolumeDelta.serializer(), "district-analytics.json", "callVolumeDelta")
        row(EngagementPoint.serializer(), "district-analytics.json", "engagementTrends.0")
        row(FunnelStage.serializer(), "district-analytics.json", "funnelData.0")
        row(SentimentSlice.serializer(), "district-analytics.json", "sentimentDistribution.0")
        row(BillingSubscription.serializer(), "district-billing.json", "subscriptions.0")
        row(BillingDiscount.serializer(), "district-billing.json", "subscriptions.0.discount")
        row(BillingInvoice.serializer(), "district-billing.json", "invoices.0")
        row(WorkspaceBilling.serializer(), "district-workspace-billing.json", "billing")
        row(UsageData.serializer(), "district-usage.json", "usage")
        row(AvailableNumber.serializer(), "district-numbers-search.json", "numbers.0")
        row(ListedNumber.serializer(), "district-provider-numbers.json", "numbers.0")
    }

    @Test
    fun `workspace, device, knowledge and member rows read on their own`() {
        row(WorkspaceEntry.serializer(), "district-workspace-list.json", "workspaces.0")
        row(WorkspaceConfig.serializer(), "district-workspace-config.json", "config")
        row(AiPersona.serializer(), "district-workspace-config.json", "config.aiPersona")
        row(NativeDevice.serializer(), "district-devices.json", "devices.0")
        row(KnowledgeDocument.serializer(), "district-knowledge.json", "documents.0")
        row(WorkspaceMember.serializer(), "district-members.json", "members.0")
        row(SetupProgress.serializer(), "district-setup.json", "setupProgress")
        row(SetupSteps.serializer(), "district-setup.json", "setupProgress.steps")
        row(E2eeInfo.serializer(), "district-room-token.json", "e2ee")
        row(GuestInvite.serializer(), "district-room-token.json", "guestInvite")
    }

    @Test
    fun `messaging, persona and workflow rows read on their own`() {
        row(MessagingAccount.serializer(), "district-messaging.json", "accounts.0")
        row(ManagedAccount.serializer(), "district-messaging.json", "managedAccount")
        row(MessagingTestDetails.serializer(), "district-messaging-test.json", "details")
        row(PersonaEngineOption.serializer(), "district-persona-options.json", "engines.0")
        row(PersonaLabelledValue.serializer(), "district-persona-options.json", "voiceStyles.0")
        row(PersonaLanguageCatalog.serializer(), "district-persona-options.json", "languages")
        row(PersonaVoiceCatalog.serializer(), "district-persona-options.json", "voices.0")
        row(PersonaVoiceGroup.serializer(), "district-persona-options.json", "voices.0.groups.0")
        row(PersonaDefaults.serializer(), "district-persona-options.json", "defaults")
        row(WorkflowListItem.serializer(), "district-workflows.json", "workflows.0")
        row(WorkflowLatestRun.serializer(), "district-workflows.json", "workflows.0.latestRun")
        row(WorkflowRun.serializer(), "district-workflow-runs.json", "runs.0")
        row(WorkflowActionResult.serializer(), "district-workflow-runs.json", "runs.0.actionResults.0")
        row(CampaignStatus.serializer(), "district-campaign-status.json", "campaign")
    }

    @Test
    fun `scheduling rows read on their own`() {
        row(SchedulingTenant.serializer(), "district-scheduling-status-ready.json", "tenant")
        row(SchedulingBookingAttendee.serializer(), "district-scheduling-bookings.json", "data.items.0.attendees.0")
        row(SchedulingBookingCounts.serializer(), "district-scheduling-bookings.json", "data.counts")
        row(SchedulingSlot.serializer(), "district-scheduling-slots.json", "data.slots.0")
        row(SchedulingSlotHost.serializer(), "district-scheduling-slots.json", "data.hosts.sched-user-contract")
        row(SchedulingTeamMember.serializer(), "district-scheduling-teams.json", "data.items.0.members.0")
        row(SchedulingUserTeam.serializer(), "district-scheduling-users.json", "data.0.teams.0")
        row(SchedulingLocaleOption.serializer(), "district-scheduling-branding.json", "data.supported_locales.0")
        row(
            SchedulingCalendarConnection.serializer(),
            "district-scheduling-calendar-status.json",
            "data.connections.0",
        )
    }

    @Test
    fun `a path that leads nowhere fails instead of passing over nothing`() {
        val missing = runCatching { row(ContactCompany.serializer(), "district-contacts.json", "contacts.99.company") }
        val notAnObject = runCatching { row(ContactCompany.serializer(), "district-contacts.json", "contacts.0.name") }

        assertEquals(AssertionError::class.java, missing.exceptionOrNull()?.javaClass)
        assertEquals(AssertionError::class.java, notAnObject.exceptionOrNull()?.javaClass)
    }
}
