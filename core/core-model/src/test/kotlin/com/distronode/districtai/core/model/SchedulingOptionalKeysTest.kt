package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scheduling-admin answers whose rows [SchedulingRequiredKeysTest] does not build: the page,
 * the one-shot answers of a write, and the envelope's own halves.
 *
 * Same method as there. Each answer is built from its required properties only, its production
 * encoding is pinned exactly (so an absent optional key is never written as a null), and each
 * required key is removed in turn to prove the decoder refuses the body rather than inventing a
 * default. The answers whose every key is optional are pinned as `{}`, which is the other half of
 * the same claim: an empty body is a real answer for them, not a failure.
 */
class SchedulingOptionalKeysTest {

    @Test
    fun `a booking page requires only its rows, and an attendee may name nobody`() {
        WireMirror.assertRequiredKeys(
            SchedulingBookingPage.serializer(),
            SchedulingBookingPage(items = emptyList()),
            """{"items":[]}""",
        )
        // ⚠️ A phone-booked attendee has a name and no address, and a calendar invite can have the
        // reverse, so an attendee with neither still decodes.
        WireMirror.assertWire(SchedulingBookingAttendee.serializer(), SchedulingBookingAttendee(), "{}")
        WireMirror.assertWire(
            SchedulingBookingAttendee.serializer(),
            SchedulingBookingAttendee(name = "Dana Booker"),
            """{"name":"Dana Booker"}""",
        )
    }

    @Test
    fun `an archive and a new api key require their keys`() {
        WireMirror.assertRequiredKeys(
            SchedulingUserArchived.serializer(),
            SchedulingUserArchived(ok = true),
            """{"ok":true}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingApiKeyCreated.serializer(),
            SchedulingApiKeyCreated(id = "k-1", name = "CI", key = "sk_live_once"),
            """{"id":"k-1","name":"CI","key":"sk_live_once"}""",
        )
    }

    @Test
    fun `the calendar status, an override group and the slots require their keys`() {
        WireMirror.assertRequiredKeys(
            SchedulingCalendarStatus.serializer(),
            SchedulingCalendarStatus(connected = false, configured = true, connections = emptyList()),
            """{"connected":false,"configured":true,"connections":[]}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingAvailabilityOverride.serializer(),
            SchedulingAvailabilityOverride(id = "o-1", date = "2026-12-25", isAvailable = false, reason = "Holiday"),
            """{"id":"o-1","date":"2026-12-25","is_available":false,"reason":"Holiday"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingSlots.serializer(),
            SchedulingSlots(slots = emptyList()),
            """{"slots":[]}""",
        )
    }

    @Test
    fun `the envelope head requires its flag, and an error body may say nothing`() {
        WireMirror.assertRequiredKeys(
            SchedulingAdminEnvelopeHead.serializer(),
            SchedulingAdminEnvelopeHead(ok = false),
            """{"ok":false}""",
        )
        // ⚠️ A 500 can answer with no usable body at all; the STATUS decides the outcome.
        WireMirror.assertWire(SchedulingAdminErrorBody.serializer(), SchedulingAdminErrorBody(), "{}")
    }

    @Test
    fun `an upload names the one key its target published, and the accessor reads whichever it is`() {
        // ⛔ EXACTLY ONE OF THE THREE ARRIVES. The accessor is the only place that decides which.
        val banner = "https://x.test/banner.png"
        val avatar = "https://x.test/avatar.png"
        assertEquals(banner, SchedulingUploadResult(bannerUrl = banner).publishedUrl)
        assertEquals(avatar, SchedulingUploadResult(avatarUrl = avatar).publishedUrl)
        assertNull("an answer that names no key published nothing", SchedulingUploadResult().publishedUrl)
        WireMirror.assertWire(
            SchedulingUploadResult.serializer(),
            SchedulingUploadResult(avatarUrl = avatar),
            """{"avatar_url":"$avatar"}""",
        )
    }
}
