package com.distronode.districtai.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire shape of the DTOs no committed fixture carries, written out by hand.
 *
 * WHY HAND-WRITTEN HERE AND NOWHERE ELSE. Every other DTO is held against real server output. These
 * are the request bodies the app builds itself, and the few responses the website's generator has
 * no fixture for (search hits, the block list). For a request body the production encoding IS the
 * contract: it is exactly what the route receives, so each case pins it key for key, both fully
 * populated and with every optional property left at its default.
 *
 * WHAT "LEFT AT ITS DEFAULT" PROVES. The production encoder omits defaults, so an empty optional
 * property must not appear at all. The persona and messaging writes merge field-wise and keep the
 * stored value for an ABSENT key, while an explicit `null` lands in the merged object (see
 * [PersonaPatchRequest] and [MessagingProviderConfig]), so an omitted key and a null one are
 * different instructions to the server.
 */
class HandBuiltWireTest {

    @Test
    fun `a search hit writes every field it reads, and an empty one writes nothing`() {
        val hit = MessageSearchHit(
            messageId = "msg-1",
            key = "+14165550142",
            threadKey = "contact:c1",
            counterpart = "+14165550142",
            kind = "sms",
            contactId = "c1",
            contactName = "Ada Lovelace",
            contactEmail = "ada@example.com",
            body = "See you at noon",
            subject = "Lunch",
            direction = "inbound",
            type = "sms",
            createdAt = "2026-09-20T12:00:00.000Z",
        )

        WireMirror.assertWire(
            MessageSearchHit.serializer(),
            hit,
            """{"messageId":"msg-1","key":"+14165550142","threadKey":"contact:c1",""" +
                """"counterpart":"+14165550142","kind":"sms","contactId":"c1","contactName":"Ada Lovelace",""" +
                """"contactEmail":"ada@example.com","body":"See you at noon","subject":"Lunch",""" +
                """"direction":"inbound","type":"sms","createdAt":"2026-09-20T12:00:00.000Z"}""",
        )
        WireMirror.assertWire(MessageSearchHit.serializer(), MessageSearchHit(), "{}")
        WireMirror.assertWire(
            MessageSearchResponse.serializer(),
            MessageSearchResponse(success = true, results = listOf(MessageSearchHit(messageId = "msg-2")), limit = 20),
            """{"success":true,"results":[{"messageId":"msg-2"}],"limit":20}""",
        )
        WireMirror.assertWire(MessageSearchResponse.serializer(), MessageSearchResponse(), "{}")

        // A hit with no contact name is titled by its counterpart, and a blank name counts as none.
        assertEquals("Ada Lovelace", hit.displayName)
        assertEquals("+14165550142", hit.copy(contactName = " ").displayName)
    }

    @Test
    fun `the block request and its answers carry exactly what the route names`() {
        WireMirror.assertWire(
            ContactBlockRequest.serializer(),
            ContactBlockRequest(workspaceId = "ws-1", contactId = "c1", phoneNumber = "+14165550142", blocked = true),
            """{"workspaceId":"ws-1","contactId":"c1","phoneNumber":"+14165550142","blocked":true}""",
        )
        // An unblock by number alone sends no contact key at all, rather than a null one.
        WireMirror.assertWire(
            ContactBlockRequest.serializer(),
            ContactBlockRequest(workspaceId = "ws-1", phoneNumber = "+14165550142", blocked = false),
            """{"workspaceId":"ws-1","phoneNumber":"+14165550142","blocked":false}""",
        )
        WireMirror.assertWire(
            ContactBlockResponse.serializer(),
            ContactBlockResponse(
                success = true,
                contactId = "c1",
                name = "Ada",
                phoneNumber = "+14165550142",
                blockedAt = "2026-09-20T12:00:00.000Z",
            ),
            """{"success":true,"contactId":"c1","name":"Ada","phoneNumber":"+14165550142",""" +
                """"blockedAt":"2026-09-20T12:00:00.000Z"}""",
        )
        WireMirror.assertWire(ContactBlockResponse.serializer(), ContactBlockResponse(), "{}")
        WireMirror.assertWire(
            BlockedContactsResponse.serializer(),
            BlockedContactsResponse(success = true, blocked = listOf(BlockedContact(contactId = "c1", name = "Ada"))),
            """{"success":true,"blocked":[{"contactId":"c1","name":"Ada"}]}""",
        )
        WireMirror.assertWire(BlockedContactsResponse.serializer(), BlockedContactsResponse(), "{}")
        WireMirror.assertRequiredKeys(
            BlockedContact.serializer(),
            BlockedContact(contactId = "c1", name = "Ada"),
            """{"contactId":"c1","name":"Ada"}""",
        )
    }

    @Test
    fun `the HQ prompt carries its history turns, and an empty history sends no key`() {
        WireMirror.assertWire(
            HqPromptRequest.serializer(),
            HqPromptRequest(
                workspaceId = "ws-1",
                prompt = "How many calls today?",
                history = listOf(HqTurn(role = "user", text = "Hi"), HqTurn(role = "assistant", text = "Hello")),
            ),
            """{"workspaceId":"ws-1","prompt":"How many calls today?",""" +
                """"history":[{"role":"user","text":"Hi"},{"role":"assistant","text":"Hello"}]}""",
        )
        WireMirror.assertWire(
            HqPromptRequest.serializer(),
            HqPromptRequest(workspaceId = "ws-1", prompt = "Hi"),
            """{"workspaceId":"ws-1","prompt":"Hi"}""",
        )
        WireMirror.assertRequiredKeys(
            HqTurn.serializer(),
            HqTurn(role = "user", text = "Hi"),
            """{"role":"user","text":"Hi"}""",
        )
    }

    @Test
    fun `an HQ confirmation echoes the tool and its arguments, and argument-free tools send none`() {
        val args = JsonObject(mapOf("contactId" to JsonPrimitive("c1"), "name" to JsonPrimitive("Ada")))

        WireMirror.assertWire(
            HqConfirmRequest.serializer(),
            HqConfirmRequest(workspaceId = "ws-1", confirm = HqConfirmAction(tool = "update_contact", args = args)),
            """{"workspaceId":"ws-1","confirm":{"tool":"update_contact","args":{"contactId":"c1","name":"Ada"}}}""",
        )
        WireMirror.assertRequiredKeys(
            HqConfirmAction.serializer(),
            HqConfirmAction(tool = "sync_calendar"),
            """{"tool":"sync_calendar"}""",
        )
    }

    @Test
    fun `a persona preview form sends only the fields the operator set`() {
        val form = PersonaPreviewForm(
            name = "Ada",
            greeting = "Hello",
            personality = "Warm",
            voice = "aura-2-thalia-en",
            language = "en",
            modelId = "gemini",
            responseLength = "short",
            temperature = 0.4,
            voiceStyle = "calm",
            preemptiveTts = true,
        )

        WireMirror.assertWire(
            PersonaPreviewTokenRequest.serializer(),
            PersonaPreviewTokenRequest(workspaceId = "ws-1", formData = form),
            """{"workspaceId":"ws-1","formData":{"name":"Ada","greeting":"Hello","personality":"Warm",""" +
                """"voice":"aura-2-thalia-en","language":"en","modelId":"gemini","responseLength":"short",""" +
                """"temperature":0.4,"voiceStyle":"calm","preemptiveTts":true}}""",
        )
        WireMirror.assertWire(PersonaPreviewForm.serializer(), PersonaPreviewForm(), "{}")
        WireMirror.assertWire(
            PersonaPreviewForm.serializer(),
            PersonaPreviewForm(voice = "aura-2-thalia-en"),
            """{"voice":"aura-2-thalia-en"}""",
        )
    }

    @Test
    fun `a provider config sends only the credentials that were typed`() {
        val sinch = MessagingProviderConfig(
            provider = MESSAGING_PROVIDER_SINCH,
            phoneNumbers = listOf("+14165550142"),
            accountSid = "AC1",
            authToken = "tok",
            projectId = "p1",
            keyId = "k1",
            keySecret = "s1",
            applicationKey = "ak",
            applicationSecret = "as",
            apiKey = "key",
        )

        WireMirror.assertWire(
            MessagingProviderConfig.serializer(),
            sinch,
            """{"provider":"sinch","phoneNumbers":["+14165550142"],"accountSid":"AC1","authToken":"tok",""" +
                """"projectId":"p1","keyId":"k1","keySecret":"s1","applicationKey":"ak",""" +
                """"applicationSecret":"as","apiKey":"key"}""",
        )
        // An untyped credential is omitted, never sent as null: the route keeps the stored secret for
        // an absent key, while an explicit null would be merged into the stored config.
        WireMirror.assertWire(MessagingProviderConfig.serializer(), MessagingProviderConfig(), "{}")
        WireMirror.assertWire(
            MessagingTestRequest.serializer(),
            MessagingTestRequest(
                workspaceId = "ws-1",
                providerConfig = MessagingProviderConfig(provider = MESSAGING_PROVIDER_TELNYX, apiKey = "key"),
            ),
            """{"workspaceId":"ws-1","providerConfig":{"provider":"telnyx","apiKey":"key"}}""",
        )
    }

    @Test
    fun `phone intelligence with nothing resolved writes nothing, and a region needs its code and name`() {
        WireMirror.assertWire(PhoneIntel.serializer(), PhoneIntel(), "{}")
        WireMirror.assertWire(
            PhoneIntel.serializer(),
            PhoneIntel(country = "CA", region = PhoneRegion(code = "ON", name = "Ontario", city = "Toronto")),
            """{"country":"CA","region":{"code":"ON","name":"Ontario","city":"Toronto"}}""",
        )
        WireMirror.assertRequiredKeys(
            PhoneRegion.serializer(),
            PhoneRegion(code = "ON", name = "Ontario"),
            """{"code":"ON","name":"Ontario"}""",
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the override-create serializer refuses any format but JSON in both directions`() {
        // The serializer picks an arm by reading the raw element, which only JSON exposes, so a
        // non-JSON codec must be refused by name rather than silently taking the first arm.
        val plainEncoder = object : AbstractEncoder() {
            override val serializersModule: SerializersModule = EmptySerializersModule()
        }
        val plainDecoder = object : AbstractDecoder() {
            override val serializersModule: SerializersModule = EmptySerializersModule()
            override fun decodeElementIndex(descriptor: SerialDescriptor): Int = CompositeDecoder.DECODE_DONE
        }
        val group = SchedulingOverrideCreated.Range(
            SchedulingOverrideGroup(
                groupId = "g-1",
                reason = "Holiday",
                start = "2026-12-24",
                end = "2026-12-26",
                days = 3,
            ),
        )

        val write = runCatching { SchedulingOverrideCreatedSerializer.serialize(plainEncoder, group) }
        val read = runCatching { SchedulingOverrideCreatedSerializer.deserialize(plainDecoder) }

        assertTrue(write.exceptionOrNull() is SerializationException)
        assertTrue(write.exceptionOrNull()?.message.orEmpty().contains("JSON"))
        assertTrue(read.exceptionOrNull() is SerializationException)
        assertTrue(read.exceptionOrNull()?.message.orEmpty().contains("JSON"))
    }
}
