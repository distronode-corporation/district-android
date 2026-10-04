package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `me.*` and `settings.*` families.
 *
 * ⛔ THIS IS THE ONE PLACE ON THE SURFACE WHERE STRICTNESS IS THE PRODUCT. Seven notification
 * booleans and four presentation settings decide what a member is emailed and how their
 * availability is drawn; a default would silently claim they had opted out of something, or place
 * their working day in the wrong hours. Every field on [SchedulingMe] but the avatar is required
 * for that reason, and a `{}` body is a decode failure rather than a member with no preferences.
 */
class SchedulingAdminSettingsContractFixtureTest {

    @Test
    fun `the caller's own user decodes all seven notification switches independently`() {
        val me = SchedulingAdminFixtures.data(
            "district-scheduling-me.json",
            SchedulingMe.serializer(),
        )

        assertEquals("sched-user-contract", me.id)
        assertEquals("America/Toronto", me.timezone)
        assertEquals("24h", me.timeFormat)
        assertEquals(1, me.weekStart)
        assertEquals("ymd", me.dateFormat)
        assertTrue(me.isAdmin)
        assertFalse(me.isOwner)
        assertEquals("admin", me.role)

        // ⛔ THE MIXED SET IS THE POINT. Three of the seven are false in the fixture, so a shared
        // default of either value would pass one assertion here and be wrong about the other.
        assertTrue(me.notifyConfirmation)
        assertTrue(me.notifyCancellation)
        assertFalse(me.notifyReschedule)
        assertTrue(me.notifyReminder)
        assertTrue(me.notifyHostBooking)
        assertFalse(me.notifyHostCancel)
        assertTrue(me.notifyHostReschedule)

        // ⚠️ THE ONE OPTIONAL FIELD, present here.
        assertEquals(
            "https://book.example.com/media/avatars/contract.png",
            me.avatarUrl,
        )
    }

    @Test
    fun `a member with no avatar decodes, and a member with no timezone does not`() {
        // ⚠️ THE ABSENT HALF OF THE OPTIONAL PAIR. No committed fixture covers it because the QA
        // member has a picture; this is the shape the fork sends for one who has not set one, and
        // it is written out rather than derived from the fixture so a change to either is visible.
        val withoutAvatar = ContractFixtures.json.decodeFromString(
            SchedulingMe.serializer(),
            """
            {"id":"sched-user-contract","email":"contract@distronode.test","name":"Contract Member",
             "timezone":"America/Toronto","time_format":"24h","week_start":1,"date_format":"ymd",
             "is_admin":true,"is_owner":false,"role":"admin","notify_confirmation":true,
             "notify_cancellation":true,"notify_reschedule":false,"notify_reminder":true,
             "notify_host_booking":true,"notify_host_cancel":false,"notify_host_reschedule":true}
            """.trimIndent(),
        )
        assertNull("an avatarless member is a member", withoutAvatar.avatarUrl)
        assertEquals("America/Toronto", withoutAvatar.timezone)

        // ⛔ AND THE REQUIRED HALF. A timezone-less body would place every booking window in the
        // wrong hours if it decoded with a default.
        assertTrue(
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingMe.serializer(),
                    """{"id":"u","email":"a@b.test","name":"A"}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `branding keeps an empty url as the spelling of unset`() {
        val branding = SchedulingAdminFixtures.data(
            "district-scheduling-branding.json",
            SchedulingBranding.serializer(),
        )

        assertEquals("Contract Agency", branding.businessName)
        assertEquals(48, branding.logoHeight)
        assertEquals(100, branding.logoOpacity)
        assertEquals(80, branding.bannerOpacity)
        assertEquals("https://www.contract.test/terms", branding.termsUrl)

        // ⛔ `""` AND NOT NULL, WHICH IS WHY THE FOUR URL FIELDS ARE NON-NULLABLE. Modelling this
        // as a nullable would model a shape the fork does not produce, and the round trip would
        // stop matching. Treat it as absent at the presentation layer, never in the type.
        assertEquals("", branding.privacyUrl)

        // ⚠️ THE NESTED COLLECTION, and the one optional field on this type.
        assertEquals(2, branding.supportedLocales?.size)
        assertEquals("fr", branding.supportedLocales?.get(1)?.code)
        assertEquals("Français", branding.supportedLocales?.get(1)?.name)
        assertEquals("en", branding.fallbackLocale)
    }

    @Test
    fun `the llm settings decode their flag and their prompt text`() {
        val llm = SchedulingAdminFixtures.data(
            "district-scheduling-llm.json",
            SchedulingLlmSettings.serializer(),
        )

        assertTrue(llm.enabled)

        // ⛔ OPERATOR-AUTHORED TEXT THAT REACHES A MODEL PROMPT, required rather than defaulted: a
        // default would put words into a prompt nobody wrote.
        assertEquals("Always list the agreed next steps first.", llm.extraInstructions)

        assertTrue(
            "an llm body with no instructions key is drift, not an empty prompt",
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingLlmSettings.serializer(),
                    """{"enabled":true}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `every settings fixture survives a round trip in both encodings`() {
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-me.json",
            SchedulingMe.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-branding.json",
            SchedulingBranding.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-llm.json",
            SchedulingLlmSettings.serializer(),
        )
    }
}
