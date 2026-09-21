package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Developer tab: API keys, connected OAuth apps, webhooks and their delivery log.
 *
 * ⛔ TWO OF THESE SIX FIXTURES CARRY A SECRET AND FOUR MUST NOT. `apiKeys.create` answers a `key`
 * and `webhooks.create` answers a `secret`, each exactly once and never again; the list shapes are
 * declared WITHOUT those keys, and a test below asserts the absence rather than trusting it. If
 * one ever appears in a listing the strict decoder rejects it, which is the correct direction.
 */
class SchedulingAdminDeveloperContractFixtureTest {

    @Test
    fun `the api key listing carries metadata and never the key itself`() {
        val keys = SchedulingAdminFixtures.data(
            "district-scheduling-api-keys.json",
            SchedulingItems.serializer(SchedulingApiKey.serializer()),
        ).items

        assertEquals(2, keys.size)
        assertEquals("Zapier", keys[0].name)
        assertEquals("2026-09-01T11:00:00Z", keys[0].createdAt)

        // ⛔ THE PRESENT-AND-ABSENT PAIR, and the null is explicit: a key that has never been used
        // sends `"last_used_at": null` rather than omitting it.
        assertEquals("2026-09-10T08:15:00Z", keys[0].lastUsedAt)
        assertNull("an unused key has no last-used stamp", keys[1].lastUsedAt)

        assertFalse(
            "no listing may serve the secret",
            ContractFixtures.read("district-scheduling-api-keys.json").contains("\"key\""),
        )
    }

    @Test
    fun `the create response is the one and only time the secret crosses the wire`() {
        val created = SchedulingAdminFixtures.data(
            "district-scheduling-api-key-created.json",
            SchedulingApiKeyCreated.serializer(),
        )

        // ⛔ NEVER STORED, CACHED OR LOGGED. `apiKeys.list` will never send it again, so a screen
        // either shows it now or it is gone — and anything that wrote it to a diagnostic has
        // published a live credential for the tenancy's whole scheduler API.
        assertEquals("cno_contract_new_api_key", created.key)
        assertEquals("key-contract-1", created.id)
        assertEquals("Zapier", created.name)

        // ⚠️ THE FORK'S OWN SENTENCE SAYING EXACTLY THAT, carried so the warning cannot drift
        // between the clients that render it.
        assertEquals("Store this now; it is not shown again.", created.note)
    }

    @Test
    fun `oauth connections decode an expiring grant and a non-expiring one`() {
        val connections = SchedulingAdminFixtures.data(
            "district-scheduling-oauth-connections.json",
            SchedulingItems.serializer(SchedulingOAuthConnection.serializer()),
        ).items

        assertEquals(2, connections.size)
        assertEquals("Raycast", connections[0].clientName)
        assertEquals("2026-10-03T11:00:00Z", connections[0].expiresAt)
        assertEquals("2026-09-10T19:00:00Z", connections[0].lastUsedAt)

        // ⛔ EXPLICITLY NULL IS A STRONGER STATEMENT THAN "WE DO NOT KNOW WHEN": this grant does
        // not expire. A screen that sorted by it must put those somewhere deliberate rather than
        // treating null as the epoch.
        assertEquals("Internal script", connections[1].clientName)
        assertNull(connections[1].expiresAt)
        assertNull(connections[1].lastUsedAt)
    }

    @Test
    fun `webhooks decode their event list, their field filter and an explicit null filter`() {
        val webhooks = SchedulingAdminFixtures.data(
            "district-scheduling-webhooks.json",
            SchedulingItems.serializer(SchedulingWebhook.serializer()),
        ).items

        assertEquals(2, webhooks.size)

        // ⚠️ TWO NESTED COLLECTIONS ON ONE ROW, and `events` is a list of STRINGS rather than the
        // enum: an already-installed build must still be able to read a webhook subscribed to an
        // event added after it shipped.
        assertEquals(listOf("booking.created", "booking.cancelled"), webhooks[0].events)
        assertEquals(listOf("attendee_name", "attendee_email"), webhooks[0].fields)
        assertEquals(true, webhooks[0].isActive)

        // ⛔ `fields: null` IS "SEND EVERYTHING", NOT EMPTY. An empty list would be a webhook whose
        // payload carries no attendee detail at all, which is a different subscription.
        assertEquals(listOf("recording.completed"), webhooks[1].events)
        assertNull("a null field filter means the whole payload", webhooks[1].fields)
        assertEquals(false, webhooks[1].isActive)
    }

    @Test
    fun `every event the request enum offers appears in the fixtures' vocabulary`() {
        // ⚠️ THE ENUM IS THE REQUEST SIDE AND THE STRINGS ARE THE RESPONSE SIDE, so this checks
        // that the wire spellings agree rather than that the sets are equal — the fixtures use
        // three of the seven, and the other four are still values a screen may offer.
        val wire = SchedulingWebhookEvent.entries.map { it.wire }
        assertTrue(wire.contains("booking.created"))
        assertTrue(wire.contains("recording.completed"))
        assertTrue(wire.contains("notes.ready"))
        assertEquals(7, wire.size)
        assertEquals(wire.size, wire.toSet().size)

        // ⛔ THE WIRE SPELLING IS DOTTED AND THE ENTRY NAME IS NOT, so a decode has to go through
        // the @SerialName rather than the entry name. Pinned because `valueOf`-style parsing would
        // pass every test that only reads `wire`.
        assertEquals(
            SchedulingWebhookEvent.TRANSCRIPT_READY,
            ContractFixtures.json.decodeFromString(
                SchedulingWebhookEvent.serializer(),
                "\"transcript.ready\"",
            ),
        )
    }

    @Test
    fun `a freshly created webhook carries a secret the listing shape cannot hold`() {
        val created = SchedulingAdminFixtures.data(
            "district-scheduling-webhook-created.json",
            SchedulingWebhookCreated.serializer(),
        )

        assertEquals("whsec_contract_shown_once", created.secret)
        assertEquals(listOf("booking.created", "booking.cancelled"), created.events)

        // ⛔ A SEPARATE TYPE FOR ONE KEY, AND THAT KEY IS A SECRET. Folding it into the list type
        // as an optional field would put a credential-shaped key on the shape every listing
        // decodes, which is how a client ends up logging one by accident — so the list type must
        // REFUSE this body.
        assertTrue(
            "the listing type must not decode a create response",
            runCatching {
                SchedulingAdminFixtures.data(
                    "district-scheduling-webhook-created.json",
                    SchedulingWebhook.serializer(),
                )
            }.isFailure,
        )
    }

    @Test
    fun `a delivery that was never answered omits its response status`() {
        val deliveries = SchedulingAdminFixtures.data(
            "district-scheduling-webhook-deliveries.json",
            SchedulingItems.serializer(SchedulingWebhookDelivery.serializer()),
        ).items

        assertEquals(2, deliveries.size)
        assertEquals("delivered", deliveries[0].status)
        assertEquals(1, deliveries[0].attemptCount)
        assertEquals(200, deliveries[0].responseStatus)
        assertEquals("bk-contract-1", deliveries[0].bookingId)

        // ⛔ ABSENT BECAUSE THE REQUEST NEVER GOT AN ANSWER — DNS, TLS, timeout — which is exactly
        // the case an operator is debugging. A `0` default would report "the endpoint answered 0"
        // for "the endpoint was never reached".
        assertEquals("failed", deliveries[1].status)
        assertEquals(5, deliveries[1].attemptCount)
        assertNull("an unreachable endpoint answered nothing", deliveries[1].responseStatus)

        // ⚠️ AND `booking_id` IS ABSENT FOR AN EVENT THAT IS NOT ABOUT A BOOKING.
        assertEquals("recording.completed", deliveries[1].event)
        assertNull(deliveries[1].bookingId)
    }

    @Test
    fun `every developer fixture survives a round trip in both encodings`() {
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-api-keys.json",
            SchedulingItems.serializer(SchedulingApiKey.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-api-key-created.json",
            SchedulingApiKeyCreated.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-oauth-connections.json",
            SchedulingItems.serializer(SchedulingOAuthConnection.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-webhooks.json",
            SchedulingItems.serializer(SchedulingWebhook.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-webhook-created.json",
            SchedulingWebhookCreated.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-webhook-deliveries.json",
            SchedulingItems.serializer(SchedulingWebhookDelivery.serializer()),
        )
    }
}
