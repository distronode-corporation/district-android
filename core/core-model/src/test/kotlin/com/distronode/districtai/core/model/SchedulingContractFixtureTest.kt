package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The booking pages' half of the contract gate.
 *
 * ⛔ A SEPARATE CLASS FROM `ContractFixtureTest` FOR THE REASON `WorkflowContractFixtureTest` IS
 * ONE: that class reached detekt's `LargeClass` ceiling as endpoints accumulated, and the healthy
 * answer is another class rather than a raised threshold. The strict decoder and the fixture loader
 * are shared through [ContractFixtures] precisely so the split cannot make one of them lenient —
 * that would silently retire the gate for whichever endpoints lived here.
 *
 * ⛔ THE FIVE FIXTURES WERE COMMITTED BEFORE ANY ANDROID CODE CONSUMED THEM, so until this class
 * existed the Android half of the two-sided gate was absent for this surface: the website's
 * `android-contracts.test.ts` pinned the bytes it emits, and nothing checked that a Kotlin DTO
 * could read them. That is the shape of gap this file closes, not merely "more coverage".
 *
 * ⛔ WHY THIS SURFACE IS WORTH PINNING. Three of the four status presentations are distinguished by
 * fields that are NULL in the other three — `bookingUrl` on `ready`, `lastError` on `error`,
 * `tenant` itself on legacy — so a DTO that defaulted one of them would not fail to load. It would
 * load and say the wrong thing: a booking link for a page that is not there, "setup succeeded" for
 * a workspace whose provision failed, or an offer to provision a tenancy that already exists.
 */
class SchedulingContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    private fun status(name: String): SchedulingStatusResponse =
        json.decodeFromString(SchedulingStatusResponse.serializer(), fixture(name))

    @Test
    fun `a ready tenancy decodes with its booking link and its credential flag`() {
        val response = status("district-scheduling-status-ready.json")

        assertEquals(true, response.eligible)
        assertEquals(true, response.canManage)
        val tenant = requireNotNull(response.tenant) { "a ready fixture must carry a tenancy row" }

        assertEquals(SchedulingTenantStatus.READY, tenant.tenantStatus)
        assertEquals("book.example.com", tenant.publicHost)
        assertEquals("us", tenant.region)

        // ⛔ THE KEY THIS CLIENT MUST NEVER REBUILD. The route derives it server-side and sends it
        // only for a ready tenancy, so a client assembling `https://<publicHost>/book/...` itself
        // would publish a booking link for a page that is not serving yet. Pinned by VALUE, not by
        // non-nullness, because a rebuilt one would also be non-null.
        assertEquals(
            "https://book.example.com/book/phone-consultation",
            tenant.bookingUrl,
        )

        // ⛔ A PRESENCE BOOLEAN AND NOTHING ELSE. `apiKeyEnc` and `webhookSecretEnc` are KMS-wrapped
        // columns the route deliberately does not select; if a ciphertext ever appears in this
        // fixture, the strict decoder rejects the unknown key and this is where it surfaces.
        assertTrue(tenant.hasCredentials)
        assertFalse(
            "no ciphertext may reach this client",
            fixture("district-scheduling-status-ready.json").contains("apiKeyEnc"),
        )

        assertNotNull("a ready tenancy has been observed provisioned", tenant.lastReadyAt)
        assertNull("a ready tenancy carries no error", tenant.lastError)
    }

    @Test
    fun `a provisioning tenancy has no link, no credential and no last-ready stamp`() {
        val response = status("district-scheduling-status-provisioning.json")
        val tenant = response.tenant!!

        assertEquals(SchedulingTenantStatus.PROVISIONING, tenant.tenantStatus)

        // ⛔ ALL THREE NULLS ARE THE CONTRACT, AND EACH ONE IS A DIFFERENT WRONG SCREEN IF IT
        // DEFAULTED. A non-null `bookingUrl` publishes a dead link; a `hasCredentials` stuck true
        // claims the tenancy can be talked to; a `lastReadyAt` invents a moment it was live.
        assertNull("a provisioning tenancy has no booking link", tenant.bookingUrl)
        assertFalse("the credential is written after the platform call", tenant.hasCredentials)
        assertNull("it has never been ready", tenant.lastReadyAt)
        assertNull(tenant.lastError)
    }

    @Test
    fun `a failed provision carries its reason and still knows its host`() {
        val response = status("district-scheduling-status-error.json")
        val tenant = response.tenant!!

        assertEquals(SchedulingTenantStatus.ERROR, tenant.tenantStatus)

        // ⛔ THE ONE FIELD THIS STATE EXISTS TO CARRY. It is operator-facing and shown to any member
        // of the workspace on purpose — somebody who cannot see why provisioning failed has to open
        // a ticket to learn it — and it is classified, truncated text rather than a raw remote
        // body, because a raw body from the scheduler can contain the once-only API key.
        assertEquals("cloudflare refused the dns record (HTTP 403)", tenant.lastError)

        // ⛔ AND `bookingUrl` IS STILL NULL WHILE `publicHost` IS NOT. The host is allocated once
        // and survives a failure; the link is derived only for `ready`. A client that treated a
        // known host as a working page would advertise a booking URL that 404s.
        assertNull(tenant.bookingUrl)
        assertEquals("book.example.com", tenant.publicHost)

        // ⛔ AND IT IS ALSO THE ONLY FIXTURE WHERE `canManage` IS FALSE, which is what stops the
        // Enable button being pinned to a constant by a fixture set generated as an owner. This is
        // the pair that proves the button is drawn from the SERVER's answer: the presentation
        // offers Enable for a failed provision, and this row must still not get one.
        assertEquals(true, response.eligible)
        assertEquals(false, response.canManage)
    }

    @Test
    fun `the legacy state is a null tenant on an eligible workspace, not an error`() {
        val response = status("district-scheduling-status-legacy.json")

        // ⛔ THE ORDINARY CONDITION OF EVERY WORKSPACE BEFORE ANYBODY PRESSES ENABLE, and the one
        // most easily rendered as a fault. `tenant == null` is a STATE; the screen's job is to
        // offer a button, not to report that something is broken.
        assertNull(response.tenant)
        assertEquals(true, response.eligible)
        assertEquals(true, response.canManage)

        // ⚠️ AND IT IS NOT THE SAME QUESTION AS ELIGIBILITY. Both answers are sent precisely
        // because they are independent — a workspace can be admitted and unprovisioned, or
        // provisioned and later removed from the allowlist.
        assertFalse(
            "the legacy body carries no tenancy object at all",
            fixture("district-scheduling-status-legacy.json").contains("publicHost"),
        )
    }

    @Test
    fun `the enable 202 decodes its flag, its status and its host`() {
        val response = json.decodeFromString(
            SchedulingEnableResponse.serializer(),
            fixture("district-scheduling-enable.json"),
        )

        assertEquals(true, response.ok)
        assertEquals("ready", response.status)
        assertEquals(SchedulingTenantStatus.READY, response.tenantStatus)
        assertEquals("book.example.com", response.publicHost)
        assertNull("a successful provision records no error", response.error)

        // ⛔ THE FLAG IS SPELLED `ok`, NOT `success`, AND NOTHING MAY RUN THIS THROUGH THE SHARED
        // ENVELOPE GUARD. If a `success` key ever appears here the strict decoder rejects it, which
        // is the correct direction: it would mean the route changed shape.
        assertFalse(fixture("district-scheduling-enable.json").contains("\"success\""))
    }

    @Test
    fun `an unmodelled tenancy status decodes and is reported as unknown rather than as an error`() {
        // ⛔ THE FORWARD-COMPATIBILITY PROPERTY, AND IT IS A REAL RISK RATHER THAN A HYPOTHETICAL.
        // The schema's own comment says `status` is "a plain String with a CHECK constraint in SQL
        // rather than a Prisma enum, so adding a state never needs a migration on five databases" —
        // i.e. a fifth state is cheap server-side. A `@Serializable enum` here (which is what the
        // iOS client does) would THROW on it, and every already-installed Android build would show
        // "update the app" instead of the booking page it can see perfectly well.
        //
        // ⛔ AND NULL IS NOT A GUESS. The screen renders an unmodelled state as its own sentence;
        // a lenient fallback onto ERROR would report a failure that did not happen.
        val decoded = json.decodeFromString(
            SchedulingStatusResponse.serializer(),
            """{"eligible":true,"canManage":true,"tenant":{"status":"retiring",""" +
                """"publicHost":"acme-book.distronode.com","region":"us","lastReadyAt":null,""" +
                """"lastError":null,"hasCredentials":true,"bookingUrl":null}}""",
        )

        assertEquals("retiring", decoded.tenant!!.status)
        assertNull("an unmodelled state is unknown, never ERROR", decoded.tenant!!.tenantStatus)
    }

    @Test
    fun `the enable route's wider vocabulary does not become a tenancy state`() {
        // ⚠️ `status` HERE FORWARDS `ProvisionResult.status`, whose TypeScript type is
        // `SchedulingTenantStatus | "skipped"` — one provisioner type serves provisioning AND
        // deprovisioning. The enable path cannot reach `"skipped"` today; when it can, the answer
        // is "not a tenancy state, go re-read the status route", not a decode failure on a body
        // that is otherwise perfectly readable.
        val decoded = json.decodeFromString(
            SchedulingEnableResponse.serializer(),
            """{"ok":false,"status":"skipped","publicHost":null,"error":"nothing to do"}""",
        )

        assertEquals("skipped", decoded.status)
        assertNull(decoded.tenantStatus)
        assertEquals("nothing to do", decoded.error)
    }

    @Test
    fun `an empty body is rejected rather than read as an unentitled workspace`() {
        // ⛔ THE ANDROID ANALOGUE OF THE ENVELOPE GUARD, AND THE REASON THESE DTOs BREAK THIS
        // PACKAGE'S "default every field" HABIT. Every other response type here defaults
        // everything, which is safe only because `rejectedEnvelope` checks a `success` flag by
        // hand. These two routes send no flag, so required fields are the ONLY thing that can
        // reject a structurally wrong 200 — and with defaults, `{}` would decode into
        // `eligible=false, canManage=false, tenant=null`, a confident claim that this customer is
        // not entitled to a product they may well be paying for.
        assertTrue(
            "an empty status body must not decode",
            runCatching {
                json.decodeFromString(SchedulingStatusResponse.serializer(), "{}")
            }.isFailure,
        )
        assertTrue(
            "an empty enable body must not decode",
            runCatching {
                json.decodeFromString(SchedulingEnableResponse.serializer(), "{}")
            }.isFailure,
        )
    }

    @Test
    fun `scheduling fixtures survive a round trip in both encodings`() {
        // ⛔ THE NULLS ARE WHAT THIS CATCHES. Three of the five fixtures write `lastReadyAt`,
        // `lastError` or `bookingUrl` as an explicit null, and the terse encoding writes them as
        // ABSENT — the same shape the server produces elsewhere. A default that swallowed one (an
        // empty string for the reason, say) would decode and re-encode to something different,
        // which no decode assertion above can see.
        WireMirror.assertFixtureRoundTrips(
            SchedulingStatusResponse.serializer(),
            "district-scheduling-status-ready.json",
        )
        WireMirror.assertFixtureRoundTrips(
            SchedulingStatusResponse.serializer(),
            "district-scheduling-status-provisioning.json",
        )
        WireMirror.assertFixtureRoundTrips(
            SchedulingStatusResponse.serializer(),
            "district-scheduling-status-error.json",
        )
        WireMirror.assertFixtureRoundTrips(
            SchedulingStatusResponse.serializer(),
            "district-scheduling-status-legacy.json",
        )
        WireMirror.assertFixtureRoundTrips(SchedulingEnableResponse.serializer(), "district-scheduling-enable.json")
    }

    @Test
    fun `an unmodelled field on a scheduling response is rejected rather than ignored`() {
        // ⛔ PROVES THE GUARD GUARDS FOR THIS FILE'S DECODER TOO. The contract classes share one
        // `Json` instance, so if this ever passes, the shared decoder has been relaxed and EVERY
        // half of the gate has quietly stopped protecting anything. Duplicated deliberately,
        // exactly as `WorkflowContractFixtureTest` duplicates it.
        val withExtraField = """{"eligible":true,"canManage":true,"tenant":null,""" +
            """"brandNewServerField":"boom"}"""

        val failure = runCatching {
            json.decodeFromString(SchedulingStatusResponse.serializer(), withExtraField)
        }
        assertTrue(
            "Decoding an unknown key MUST fail. It succeeded, which means ignoreUnknownKeys is " +
                "no longer false and contract drift can now ship silently.",
            failure.isFailure,
        )
    }

    @Test
    fun `every tenancy status this client models parses from its wire spelling`() {
        // ⚠️ THE PARSER IS WHAT THE WHOLE SCREEN BRANCHES ON, so a rename that broke one arm would
        // otherwise show up only as a card silently falling through to "unrecognised".
        assertEquals(
            SchedulingTenantStatus.entries.toSet(),
            listOf("provisioning", "ready", "error", "disabled")
                .mapNotNull(SchedulingTenantStatus::fromWire)
                .toSet(),
        )
        assertNull(SchedulingTenantStatus.fromWire(null))
        assertNull(SchedulingTenantStatus.fromWire(""))
        // ⚠️ Case-folded, because it can only ever widen what resolves — see the enum's companion.
        assertEquals(SchedulingTenantStatus.READY, SchedulingTenantStatus.fromWire("READY"))
    }
}
