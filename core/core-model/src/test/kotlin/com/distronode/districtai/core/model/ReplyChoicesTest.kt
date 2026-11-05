package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ConversationSummary.replyChoices]: the server's reply options, read as this build can send them. */
class ReplyChoicesTest {

    private val whatsAppOnly = ConversationSummary(
        threadKey = "addr:14165550177",
        counterpart = "+14165550177",
        canSms = true,
        replyOptions = listOf(ReplyOption("whatsapp", "+14165550177")),
    )

    @Test
    fun `the server's options win, in its order, and canSms plays no part`() {
        assertEquals(listOf(ReplyTarget("+14165550177", CHANNEL_WHATSAPP)), whatsAppOnly.replyChoices)

        val mixed = whatsAppOnly.copy(
            replyOptions = listOf(
                ReplyOption("email", "ada@example.com"),
                ReplyOption("sms", "+14165550177"),
                ReplyOption("messenger", "fb:5550101"),
                ReplyOption("instagram", "ig:5550102"),
            ),
        )
        assertEquals(
            listOf(CHANNEL_EMAIL, CHANNEL_SMS, CHANNEL_MESSENGER, CHANNEL_INSTAGRAM),
            mixed.replyChoices.map { it.channel },
        )
    }

    @Test
    fun `an option with nothing in it is no choice`() {
        assertEquals(ReplyOption("", ""), ReplyOption())
        assertTrue(listOf(ReplyOption()).toReplyChoices().isEmpty())
    }

    @Test
    fun `an unknown channel and a blank address are skipped`() {
        val thread = whatsAppOnly.copy(
            replyOptions = listOf(
                ReplyOption("rcs", "+14165550177"),
                ReplyOption("sms", " "),
                ReplyOption("email", "ada@example.com"),
            ),
        )
        assertEquals(listOf(ReplyTarget("ada@example.com", CHANNEL_EMAIL)), thread.replyChoices)
    }

    @Test
    fun `an empty list is no reply box, even when canSms says otherwise`() {
        assertTrue(whatsAppOnly.copy(replyOptions = emptyList()).replyChoices.isEmpty())
    }

    @Test
    fun `no options at all (a server that predates the flag) falls back to replyTarget`() {
        val old = whatsAppOnly.copy(replyOptions = null)
        assertEquals(listOf(ReplyTarget("+14165550177", CHANNEL_SMS)), old.replyChoices)
        assertTrue(old.copy(canSms = false).replyChoices.isEmpty())
    }

    @Test
    fun `the recorded reply-options fixture reads as one channel per Meta thread`() {
        val response = ContractFixtures.json.decodeFromString(
            ConversationsResponse.serializer(),
            ContractFixtures.read("district-conversations-reply-options.json"),
        )
        val byKey = response.conversations.associate { it.threadKey to it.replyChoices.map(ReplyTarget::channel) }
        assertEquals(listOf(CHANNEL_WHATSAPP), byKey["addr:14165550177"])
        assertEquals(listOf(CHANNEL_MESSENGER), byKey["addr:fb:5550101"])
        assertEquals(listOf(CHANNEL_INSTAGRAM), byKey["addr:ig:5550102"])
        assertEquals(listOf(CHANNEL_EMAIL, CHANNEL_SMS, CHANNEL_WHATSAPP), byKey["contact:contact_contract_1"])
    }
}
