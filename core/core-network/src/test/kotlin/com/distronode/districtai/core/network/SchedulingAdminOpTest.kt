package com.distronode.districtai.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 75 op names, their role bars and their write flags, spelled a SECOND time.
 *
 * ⛔ THE STRINGS ARE EMBEDDED HERE RATHER THAN DERIVED FROM [SchedulingAdminOp.entries], AND THAT
 * IS THE ONLY THING THAT MAKES THIS A TEST. The op crosses the wire as a string and a key renamed
 * on the server is a **400 `unknown_op`**, not a compile error — so a test that re-read the enum
 * would assert that the code equals itself and would pass through any rename. Copied from
 * the server's op catalog (`admin-ops.ts`) and from the iOS client's `SchedulingAdminOpTests`, which
 * does the same thing for the same reason.
 *
 * ⛔ AND THE TWO SPLITS ARE ASSERTED AS COUNTS, WHICH NEITHER DERIVES FROM THE OTHER. 35 `viewer` /
 * 40 `client`, and 29 reads / 46 writes: the `me.*` and `calendar.*` namespaces are viewer-level
 * even where they WRITE, so the two classifications disagree on exactly six ops.
 */
class SchedulingAdminOpTest {

    @Test
    fun `the catalog holds exactly seventy-five ops with unique wire names`() {
        assertEquals(EXPECTED.size, SchedulingAdminOp.entries.size)
        assertEquals(75, SchedulingAdminOp.entries.size)

        val wire = SchedulingAdminOp.entries.map { it.wire }
        assertEquals("no op name may be spelled twice", wire.size, wire.toSet().size)
    }

    @Test
    fun `every op's wire name matches the server's key verbatim`() {
        val actual = SchedulingAdminOp.entries.associate { it.name to it.wire }
        val expected = EXPECTED.associate { it.name to it.wire }

        // ⚠️ COMPARED AS MAPS so a failure names the op rather than an index, and both directions
        // are checked: a withdrawn op fails here as loudly as a renamed one.
        assertEquals(expected, actual)
    }

    @Test
    fun `every op's role bar matches the catalog, and the split is 35 viewer to 40 client`() {
        val actual = SchedulingAdminOp.entries.associate { it.name to it.minRole }
        val expected = EXPECTED.associate { it.name to it.minRole }
        assertEquals(expected, actual)

        assertEquals(
            35,
            SchedulingAdminOp.entries.count { it.minRole == SchedulingAdminRole.VIEWER },
        )
        assertEquals(
            40,
            SchedulingAdminOp.entries.count { it.minRole == SchedulingAdminRole.CLIENT },
        )
    }

    @Test
    fun `every op's write flag matches the catalog, and the split is 29 reads to 46 writes`() {
        val actual = SchedulingAdminOp.entries.associate { it.name to it.isWrite }
        val expected = EXPECTED.associate { it.name to it.isWrite }
        assertEquals(expected, actual)

        assertEquals(29, SchedulingAdminOp.entries.count { !it.isWrite })
        assertEquals(46, SchedulingAdminOp.entries.count { it.isWrite })
    }

    @Test
    fun `the role bar and the write flag disagree on exactly the six self-service writes`() {
        // ⛔ THE WHOLE REASON `isWrite` IS NOT `minRole == CLIENT`. These six are viewer-level
        // WRITES billed to a separate 30-per-member-per-hour bucket, precisely so one viewer
        // changing their avatar in a loop cannot lock every administrator out of the workspace's
        // 120 for an hour. Deriving one property from the other puts them back in the shared pool.
        val disagreeing = SchedulingAdminOp.entries
            .filter { it.isWrite && it.minRole == SchedulingAdminRole.VIEWER }
            .map { it.wire }
            .toSet()

        assertEquals(
            setOf(
                "me.patch",
                "me.avatar.delete",
                "calendar.caldav.connect",
                "calendar.connections.calendars.put",
                "calendar.connections.destination",
                "calendar.connections.delete",
            ),
            disagreeing,
        )

        // ⚠️ AND THE CONVERSE IS EMPTY: there is no `client`-level READ, so every disagreement is
        // in the direction above.
        assertTrue(
            SchedulingAdminOp.entries.none { !it.isWrite && it.minRole == SchedulingAdminRole.CLIENT },
        )
    }

    @Test
    fun `no op names a scheduler route the catalog deliberately withholds`() {
        // ⛔ THE ALLOWLIST'S EXCLUSIONS, ASSERTED AS ABSENCES. `/v1/settings/{google,email,zoom,
        // livekit,stripe,tracking}` hold INSTANCE credentials shared by every tenancy,
        // `/v1/platform/*` can create and delete any tenancy at all, and the role/ownership
        // transfers would let a member re-arrange who owns the tenancy behind District's back.
        val wire = SchedulingAdminOp.entries.map { it.wire }.toSet()
        listOf(
            "settings.google.get",
            "settings.email.get",
            "settings.zoom.get",
            "settings.livekit.get",
            "settings.stripe.get",
            "settings.tracking.get",
            "platform.tenants.list",
            "platform.tenants.delete",
            "users.role.patch",
            "users.transferOwnership",
        ).forEach { withheld ->
            assertTrue("$withheld must never be catalogued here", withheld !in wire)
        }
    }

    @Test
    fun `the three upload targets carry the roles the route enforces`() {
        // ⚠️ AN AVATAR IS THE CALLER'S OWN PICTURE AND IS VIEWER-LEVEL; the logo and banner are the
        // WORKSPACE's public branding and are not. Spelling the target as a String would let a
        // screen offer a control the server refuses and would put an avatar on the branding budget.
        assertEquals(3, SchedulingAdminUploadTarget.entries.size)
        assertEquals(SchedulingAdminRole.CLIENT, SchedulingAdminUploadTarget.LOGO.minRole)
        assertEquals(SchedulingAdminRole.CLIENT, SchedulingAdminUploadTarget.BANNER.minRole)
        assertEquals(SchedulingAdminRole.VIEWER, SchedulingAdminUploadTarget.AVATAR.minRole)
        assertEquals("logo", SchedulingAdminUploadTarget.LOGO.wire)
        assertEquals("avatar", SchedulingAdminUploadTarget.AVATAR.wire)
    }

    @Test
    fun `agency is absent from the role vocabulary, because it is never a minimum`() {
        // ⚠️ IT CLEARS BOTH BARS, so it is never the MINIMUM for anything; the server's
        // `roleClears` says the same in the other direction. A third entry here would be a value
        // no op could hold.
        assertEquals(
            listOf("viewer", "client"),
            SchedulingAdminRole.entries.map { it.wire },
        )
    }

    /** One row of the catalog, re-typed. */
    private data class Row(
        val name: String,
        val wire: String,
        val minRole: SchedulingAdminRole,
        val isWrite: Boolean,
    )

    private companion object {
        private fun viewerRead(name: String, wire: String) =
            Row(name, wire, SchedulingAdminRole.VIEWER, isWrite = false)

        private fun viewerWrite(name: String, wire: String) =
            Row(name, wire, SchedulingAdminRole.VIEWER, isWrite = true)

        private fun clientWrite(name: String, wire: String) =
            Row(name, wire, SchedulingAdminRole.CLIENT, isWrite = true)

        val EXPECTED: List<Row> = listOf(
            viewerRead("ME_GET", "me.get"),
            viewerWrite("ME_PATCH", "me.patch"),
            viewerWrite("ME_AVATAR_DELETE", "me.avatar.delete"),

            viewerRead("EVENT_TYPES_LIST", "eventTypes.list"),
            viewerRead("EVENT_TYPES_GET", "eventTypes.get"),
            clientWrite("EVENT_TYPES_CREATE", "eventTypes.create"),
            clientWrite("EVENT_TYPES_PATCH", "eventTypes.patch"),
            clientWrite("EVENT_TYPES_DELETE", "eventTypes.delete"),
            viewerRead("EVENT_TYPES_HOSTS_GET", "eventTypes.hosts.get"),
            clientWrite("EVENT_TYPES_HOSTS_PUT", "eventTypes.hosts.put"),
            clientWrite("EVENT_TYPES_TEST_EMAIL", "eventTypes.testEmail"),
            viewerRead("EVENT_TYPES_QUESTIONS_LIST", "eventTypes.questions.list"),
            clientWrite("EVENT_TYPES_QUESTIONS_CREATE", "eventTypes.questions.create"),
            clientWrite("EVENT_TYPES_QUESTIONS_PATCH", "eventTypes.questions.patch"),
            clientWrite("EVENT_TYPES_QUESTIONS_DELETE", "eventTypes.questions.delete"),
            viewerRead("EVENT_TYPES_SLOTS", "eventTypes.slots"),

            viewerRead("AVAILABILITY_RULES_LIST", "availability.rules.list"),
            clientWrite("AVAILABILITY_RULES_CREATE", "availability.rules.create"),
            clientWrite("AVAILABILITY_RULES_PATCH", "availability.rules.patch"),
            clientWrite("AVAILABILITY_RULES_DELETE", "availability.rules.delete"),
            viewerRead("AVAILABILITY_OVERRIDES_LIST", "availability.overrides.list"),
            clientWrite("AVAILABILITY_OVERRIDES_CREATE", "availability.overrides.create"),
            clientWrite("AVAILABILITY_OVERRIDES_PATCH", "availability.overrides.patch"),
            clientWrite("AVAILABILITY_OVERRIDES_DELETE", "availability.overrides.delete"),
            clientWrite("AVAILABILITY_OVERRIDES_DELETE_GROUP", "availability.overrides.deleteGroup"),

            viewerRead("BOOKINGS_LIST", "bookings.list"),
            viewerRead("BOOKINGS_ANSWERS", "bookings.answers"),
            clientWrite("BOOKINGS_CANCEL", "bookings.cancel"),
            clientWrite("BOOKINGS_RESCHEDULE", "bookings.reschedule"),
            clientWrite("BOOKINGS_REASSIGN", "bookings.reassign"),
            viewerRead("BOOKINGS_NOTES", "bookings.notes"),
            clientWrite("BOOKINGS_NOTES_REGENERATE", "bookings.notes.regenerate"),
            viewerRead("BOOKINGS_TRANSCRIPT", "bookings.transcript"),

            viewerRead("CALENDAR_STATUS", "calendar.status"),
            viewerWrite("CALENDAR_CALDAV_CONNECT", "calendar.caldav.connect"),
            viewerRead("CALENDAR_CONNECTIONS_CALENDARS_GET", "calendar.connections.calendars.get"),
            viewerWrite("CALENDAR_CONNECTIONS_CALENDARS_PUT", "calendar.connections.calendars.put"),
            viewerWrite("CALENDAR_CONNECTIONS_DESTINATION", "calendar.connections.destination"),
            viewerWrite("CALENDAR_CONNECTIONS_DELETE", "calendar.connections.delete"),
            viewerRead("ZOOM_STATUS", "zoom.status"),

            viewerRead("USERS_LIST", "users.list"),
            clientWrite("USERS_ARCHIVE", "users.archive"),
            viewerRead("USERS_UPCOMING_BOOKINGS", "users.upcomingBookings"),

            viewerRead("TEAMS_LIST", "teams.list"),
            viewerRead("TEAMS_GET", "teams.get"),
            clientWrite("TEAMS_CREATE", "teams.create"),
            clientWrite("TEAMS_PATCH", "teams.patch"),
            clientWrite("TEAMS_DELETE", "teams.delete"),
            clientWrite("TEAMS_MEMBERS_ADD", "teams.members.add"),
            clientWrite("TEAMS_MEMBERS_PATCH", "teams.members.patch"),
            clientWrite("TEAMS_MEMBERS_REMOVE", "teams.members.remove"),

            viewerRead("RECORDINGS_LIST", "recordings.list"),
            clientWrite("RECORDINGS_DELETE", "recordings.delete"),
            clientWrite("RECORDINGS_DELETE_ALL", "recordings.deleteAll"),
            viewerRead("RECORDINGS_CONSENT", "recordings.consent"),

            viewerRead("SETTINGS_BRANDING_GET", "settings.branding.get"),
            clientWrite("SETTINGS_BRANDING_PATCH", "settings.branding.patch"),
            clientWrite("SETTINGS_BRANDING_LOGO_DELETE", "settings.branding.logo.delete"),
            clientWrite("SETTINGS_BRANDING_BANNER_DELETE", "settings.branding.banner.delete"),
            viewerRead("SETTINGS_STORAGE_GET", "settings.storage.get"),
            clientWrite("SETTINGS_STORAGE_PATCH", "settings.storage.patch"),
            viewerRead("SETTINGS_NOTETAKER_GET", "settings.notetaker.get"),
            clientWrite("SETTINGS_NOTETAKER_PATCH", "settings.notetaker.patch"),
            viewerRead("SETTINGS_LLM_GET", "settings.llm.get"),
            clientWrite("SETTINGS_LLM_PATCH", "settings.llm.patch"),

            viewerRead("API_KEYS_LIST", "apiKeys.list"),
            clientWrite("API_KEYS_CREATE", "apiKeys.create"),
            clientWrite("API_KEYS_DELETE", "apiKeys.delete"),
            viewerRead("OAUTH_CONNECTIONS_LIST", "oauth.connections.list"),
            clientWrite("OAUTH_CONNECTIONS_DELETE", "oauth.connections.delete"),
            viewerRead("WEBHOOKS_LIST", "webhooks.list"),
            clientWrite("WEBHOOKS_CREATE", "webhooks.create"),
            clientWrite("WEBHOOKS_PATCH", "webhooks.patch"),
            clientWrite("WEBHOOKS_DELETE", "webhooks.delete"),
            viewerRead("WEBHOOKS_DELIVERIES", "webhooks.deliveries"),
        )
    }
}
