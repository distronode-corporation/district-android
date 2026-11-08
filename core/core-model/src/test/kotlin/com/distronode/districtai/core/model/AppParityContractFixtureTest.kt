package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app parity fixtures (Inbox reply options and timeline meta, thread assignment, the WhatsApp
 * window and templates, the Messenger and Instagram reply window), decoded minimally and held to
 * [WireMirror] so each model keeps every key it reads.
 *
 * A class of its own for the reason [ConversationAssignmentContractFixtureTest] gives:
 * `ContractFixtureTest` is at detekt's LargeClass ceiling. The viewer refusal
 * (`district-conversation-assign-viewer.json`) is the shared error envelope's, so it is decoded in
 * core-network's `ApiErrorEnvelopeContractTest`.
 */
class AppParityContractFixtureTest {

    @Test
    fun `every thread carries its reply options in order`() {
        val response = WireMirror.assertMirrors(
            ConversationsResponse.serializer(),
            "district-conversations-reply-options.json",
        )

        assertTrue("the fixture must carry threads", response.conversations.isNotEmpty())
        response.conversations.forEach {
            assertTrue("${it.threadKey} must offer at least one reply channel", it.replyOptions.orEmpty().isNotEmpty())
        }
        val messenger = response.conversations.single { it.threadKey == "addr:fb:5550101" }
        assertEquals(listOf(ReplyOption(channel = "messenger", to = "fb:5550101")), messenger.replyOptions)
    }

    @Test
    fun `the plain conversation list leaves reply options and the assignee unset`() {
        val response = WireMirror.assertMirrors(ConversationsResponse.serializer(), "district-conversations.json")

        response.conversations.forEach {
            assertNull("absent, which is not the same answer as []", it.replyOptions)
            assertNull(it.assigneeEmail)
            assertEquals(false, it.assignedToMe)
        }
    }

    @Test
    fun `the assignee keys decode on the opt-in list`() {
        val response = WireMirror.assertMirrors(
            ConversationsResponse.serializer(),
            "district-conversations-assignee.json",
        )

        val mine = response.conversations.single { it.assignedToMe }
        assertEquals("contract@distronode.test", mine.assigneeEmail)
        val nobody = response.conversations.single { !it.assignedToMe }
        assertNull(nobody.assigneeEmail)
    }

    @Test
    fun `the timeline meta fixture decodes with the existing event`() {
        val response = WireMirror.assertMirrors(TimelineResponse.serializer(), "district-timeline-meta.json")

        assertTrue(response.timeline.single { it.id == "call_contract_missed" }.isMissedCall)
        assertEquals("Re: Thursday appointment", response.timeline.single { it.id == "msg_timeline_email" }.subject)
    }

    @Test
    fun `call handling decodes its read and the patch's echo`() {
        val read = WireMirror.assertMirrors(CallHandlingResponse.serializer(), "district-call-handling.json")
        val patch = WireMirror.assertMirrors(CallHandlingResponse.serializer(), "district-call-handling-patch.json")

        assertEquals(CallHandling.AI_THEN_APP, read.callHandling)
        assertTrue(CallHandling.isKnown(patch.callHandling))
        assertEquals(CallHandling.clampRing(read.appRingSeconds), read.appRingSeconds)
        assertEquals(true, patch.success)
    }

    @Test
    fun `assign returns the row and unassign returns null`() {
        val assigned = WireMirror.assertMirrors(
            ConversationAssignResponse.serializer(),
            "district-conversation-assign.json",
        )
        val unassigned = WireMirror.assertMirrors(
            ConversationAssignResponse.serializer(),
            "district-conversation-unassign.json",
        )

        assertEquals("teammate@contract.test", assigned.assignment?.assigneeEmail)
        assertEquals(true, unassigned.success)
        assertNull(unassigned.assignment)
    }

    @Test
    fun `the WhatsApp window decodes open and closed`() {
        val open = WireMirror.assertMirrors(WhatsAppWindowResponse.serializer(), "district-whatsapp-window.json")
        val closed = WireMirror.assertMirrors(
            WhatsAppWindowResponse.serializer(),
            "district-whatsapp-window-closed.json",
        )

        assertEquals(WhatsAppWindow(open = true, expiresAt = "2026-08-16T14:25:00.000Z"), open.window)
        assertEquals("+16475550100", open.receivedOn)
        assertEquals(WhatsAppWindow(open = false, expiresAt = null), closed.window)
        assertNull(closed.receivedOn)
    }

    @Test
    fun `the Messenger and Instagram reply window decodes open, human agent and closed`() {
        val open = WireMirror.assertMirrors(MetaWindowResponse.serializer(), "district-meta-window.json")
        val humanAgent = WireMirror.assertMirrors(
            MetaWindowResponse.serializer(),
            "district-meta-window-human-agent.json",
        )
        val closed = WireMirror.assertMirrors(MetaWindowResponse.serializer(), "district-meta-window-closed.json")

        assertEquals(MetaWindow(state = META_WINDOW_OPEN, until = "2026-08-16T14:25:00.000Z"), open.window)
        assertEquals(MetaWindow(state = META_WINDOW_HUMAN_AGENT, until = "2026-08-22T14:25:00.000Z"), humanAgent.window)
        assertEquals(MetaWindow(state = META_WINDOW_CLOSED, until = null), closed.window)
        listOf(open, humanAgent, closed).forEach { assertTrue(it.success) }
    }

    @Test
    fun `the WhatsApp templates decode, positional and named`() {
        val response = WireMirror.assertMirrors(
            WhatsAppTemplatesResponse.serializer(),
            "district-whatsapp-templates.json",
        )

        assertEquals(2, response.templates.single { it.id == "wa_tpl_reminder" }.variableCount)
        assertNull(response.templates.single { it.id == "wa_tpl_named" }.variableCount)
    }

    @Test
    fun `a template send answers with the ordinary sent message`() {
        val response = WireMirror.assertMirrors(
            SendMessageResponse.serializer(),
            "district-message-send-whatsapp-template.json",
        )

        assertEquals("whatsapp", response.message?.type)
        assertEquals("wa_sender_contract", response.message?.accountId)
    }

    @Test
    fun `the new DTOs compare by value, field by field`() {
        val option = ReplyOption("sms", "+15550100")
        assertEquals(option, option.copy())
        assertTrue(option != option.copy(channel = "whatsapp"))
        assertTrue(option != option.copy(to = "+15550101"))

        val assign = ConversationAssignResponse(success = true, assignment = null)
        assertEquals(assign, assign.copy())
        assertTrue(assign != assign.copy(success = false))
        assertTrue(assign != assign.copy(assignment = ConversationAssignment()))

        val window = WhatsAppWindowResponse()
        assertEquals(window, window.copy())
        listOf(
            window.copy(success = true),
            window.copy(window = WhatsAppWindow(open = true)),
            window.copy(senderId = "s"),
            window.copy(receivedOn = "+15550100"),
        ).forEach { assertTrue("each field must participate in equality", window != it) }

        val template = WhatsAppTemplate()
        assertEquals(template, template.copy())
        listOf(
            template.copy(id = "t"),
            template.copy(senderId = "s"),
            template.copy(name = "n"),
            template.copy(language = "fr_CA"),
            template.copy(category = "UTILITY"),
            template.copy(status = "approved"),
            template.copy(body = "b"),
            template.copy(variableCount = 1),
        ).forEach { assertTrue("each field must participate in equality", template != it) }
        assertTrue(WhatsAppTemplatesResponse() != WhatsAppTemplatesResponse(success = true))
    }
}
