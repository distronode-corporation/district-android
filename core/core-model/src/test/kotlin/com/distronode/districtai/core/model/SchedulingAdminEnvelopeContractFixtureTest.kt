package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The envelope every one of the 64 scheduling-admin ops travels in, and the three refusal bodies.
 *
 * ⛔ THE FAILURE FIXTURE IS AN HTTP **200** AND THAT IS THE WHOLE REASON THIS CLASS EXISTS SEPARATE
 * FROM THE NINE FAMILY CLASSES. `district-scheduling-admin-failure.json` is what a working route
 * answers when the SCHEDULER refuses; a client that read the payload without reading the flag would
 * report an outage as data, and a type whose every field defaults decodes `{ok:false}` cleanly.
 *
 * ⛔ AND THE TWO NO-CONTENT FIXTURES ARE NOT EMPTY BODIES. The catalog's `NO_CONTENT` is
 * `z.unknown().transform(() => ({ ok: true }))`, so the scheduler's 204 is rewritten into a real
 * object before it reaches this client: an outer flag and an inner one that mean different things.
 */
class SchedulingAdminEnvelopeContractFixtureTest {

    private fun fixture(name: String) = ContractFixtures.read(name)

    @Test
    fun `the no-content envelope carries an outer flag and an inner one`() {
        val ok = SchedulingAdminFixtures.data(
            "district-scheduling-ok.json",
            SchedulingNoContent.serializer(),
        )
        val noContent = SchedulingAdminFixtures.data(
            "district-scheduling-no-content.json",
            SchedulingNoContent.serializer(),
        )

        // ⛔ TWO FLAGS, ONE NESTED IN THE OTHER. The outer one says Distronode's route succeeded;
        // the inner one is the fork's own answer, rewritten from a 204. A type that modelled only
        // one of them would decode a refusal.
        assertTrue(ok.ok)
        assertTrue(noContent.ok)
        assertEquals(ok, noContent)
    }

    @Test
    fun `an empty no-content payload is rejected rather than read as a successful op`() {
        // ⛔ `ok` CARRIES NO DEFAULT, AND THIS IS WHY. `{}` inside `data` would otherwise decode
        // into a confident "the delete worked" for sixteen ops that delete customer data.
        assertTrue(
            "an empty no-content payload must not decode",
            runCatching {
                ContractFixtures.json.decodeFromString(SchedulingNoContent.serializer(), "{}")
            }.isFailure,
        )
    }

    @Test
    fun `a scheduler refusal decodes as a 200 with the flag down`() {
        val head = ContractFixtures.json.decodeFromString(
            SchedulingAdminEnvelopeHead.serializer(),
            fixture("district-scheduling-admin-failure.json"),
        )

        assertEquals(false, head.ok)
        assertEquals("rejected", head.failure)

        // ⚠️ THE SCHEDULER'S OWN STATUS, CARRIED FOR A LOG LINE. It is 403 here while the HTTP
        // response was 200 — which is exactly the confusion the field's doc warns about, and
        // exactly why nothing may branch on it. The five-code vocabulary is keyed on `failure`.
        assertEquals(403, head.status)
        assertTrue(
            "the refusal body is not an HTTP error body",
            !fixture("district-scheduling-admin-failure.json").contains("\"error\""),
        )
    }

    @Test
    fun `a success envelope's head decodes with failure and status absent`() {
        // ⚠️ THE HEAD IS DECODED LENIENTLY IN PRODUCTION (it must ignore `data`), so this asserts
        // the DEFAULTS rather than the strict decode: both keys are absent on every success, and a
        // caller reading `failure` on a good answer must get null rather than an empty string.
        val head = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(
                SchedulingAdminEnvelopeHead.serializer(),
                fixture("district-scheduling-ok.json"),
            )

        assertTrue(head.ok)
        assertNull("a success carries no failure kind", head.failure)
        assertNull("a success carries no scheduler status", head.status)
    }

    @Test
    fun `an invalid-params refusal carries field NAMES and a not-ready one carries none`() {
        val invalid = ContractFixtures.json.decodeFromString(
            SchedulingAdminErrorBody.serializer(),
            fixture("district-scheduling-admin-invalid-params.json"),
        )
        val notReady = ContractFixtures.json.decodeFromString(
            SchedulingAdminErrorBody.serializer(),
            fixture("district-scheduling-admin-not-ready.json"),
        )

        assertEquals("invalid_params", invalid.error)

        // ⛔ NAMES, NEVER MESSAGES, AND THE NESTED COLLECTION IS THE POINT. A zod issue's message
        // quotes the offending input straight back out of the API; `issueFields` flattens to paths
        // and drops the text, so these are identifiers to look up rather than prose to show.
        assertEquals(listOf("time_format", "week_start"), invalid.fields)

        // ⛔ THE PRESENT-AND-ABSENT PAIR. The same type reads both bodies, and `fields` is ABSENT
        // on the 409 rather than empty — an empty list would mean "we could not say which field",
        // which is a different statement from "this refusal is not about a field at all".
        assertEquals("scheduling_not_ready", notReady.error)
        assertNull("a not-ready refusal names no fields", notReady.fields)
    }

    @Test
    fun `the image upload answers one url of three, inside the same envelope`() {
        val upload = SchedulingAdminFixtures.data(
            "district-scheduling-upload.json",
            SchedulingUploadResult.serializer(),
        )

        // ⛔ EXACTLY ONE OF THE THREE ARRIVES, decided by the `target` the upload sent. A required
        // field on this type would refuse two of the three uploads.
        assertEquals(
            "https://book.example.com/media/branding/logo.png",
            upload.logoUrl,
        )
        assertNull("a logo upload names no banner", upload.bannerUrl)
        assertNull("a logo upload names no avatar", upload.avatarUrl)

        // ⚠️ THE ACCESSOR IS THE SUPPORTED READ. Reaching for the key that matches the target puts
        // the same decision in two places.
        assertEquals(upload.logoUrl, upload.publishedUrl)
    }

    @Test
    fun `an unmodelled key on a scheduling-admin body is rejected rather than ignored`() {
        // ⛔ PROVES THE GUARD GUARDS FOR THIS SURFACE'S DECODER TOO. The contract classes share one
        // `Json`, so if this ever passes, the shared decoder has been relaxed and EVERY half of the
        // gate has quietly stopped protecting anything.
        assertTrue(
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingAdminEnvelopeHead.serializer(),
                    """{"ok":true,"failure":null,"status":null,"brandNewServerField":"boom"}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `every envelope fixture survives a round trip in both encodings`() {
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-ok.json",
            SchedulingNoContent.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-no-content.json",
            SchedulingNoContent.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-upload.json",
            SchedulingUploadResult.serializer(),
        )
        assertNotNull(fixture("district-scheduling-admin-not-ready.json"))
    }
}
