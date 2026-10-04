package com.distronode.districtai.ui

import com.distronode.districtai.core.model.WorkspaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The route builders and the thread-key parser.
 *
 * ⛔ WHY THIS EXISTS. Every route argument in this app is a URL path segment, and a value that
 * changes where a route points is the failure mode: an unencoded `/` addresses a different
 * destination, an unencoded `+` in a phone number decodes back as a SPACE, and a `@` in an email
 * address is legal in a path but not in an authority. None of that was covered — the builders were
 * reachable only by rendering the whole navigation graph, so the encoding was asserted by nothing.
 *
 * ⚠️ Robolectric, because `Uri.encode` is `android.net.Uri` and returns null off-device. It is a
 * pure function under Robolectric, so these are fast unit tests rather than UI tests.
 */
@RunWith(RobolectricTestRunner::class)
class RoutesTest {

    @Test
    fun `templates and builders agree on segment count`() {
        // ⛔ A BUILDER THAT PRODUCES A DIFFERENT NUMBER OF SEGMENTS THAN ITS TEMPLATE NEVER MATCHES,
        // and Navigation's failure for that is a silent no-op rather than an exception — the tap
        // simply does nothing. Comparing shapes here is what makes a template edit visible.
        fun segments(route: String) = route.split("/").size

        assertEquals(segments(Routes.CALL_LOG), segments(Routes.callLog("ws1")))
        assertEquals(segments(Routes.CALL_DETAIL), segments(Routes.callDetail("ws1", "call1")))
        assertEquals(segments(Routes.INBOX), segments(Routes.inbox("ws1", WorkspaceRole.CLIENT)))
        assertEquals(
            segments(Routes.THREAD),
            segments(Routes.thread("ws1", WorkspaceRole.CLIENT, "contact:c1")),
        )
        assertEquals(segments(Routes.CONTACTS), segments(Routes.contacts("ws1", WorkspaceRole.CLIENT)))
        assertEquals(
            segments(Routes.CONTACT_DETAIL),
            segments(Routes.contactDetail("ws1", "contact1", WorkspaceRole.CLIENT)),
        )
        assertEquals(segments(Routes.ANALYTICS), segments(Routes.analytics("ws1")))
        assertEquals(
            segments(Routes.MARKETPLACE),
            segments(Routes.marketplace("ws1", WorkspaceRole.CLIENT)),
        )
        assertEquals(segments(Routes.ROOMS), segments(Routes.rooms("ws1", WorkspaceRole.CLIENT)))
        assertEquals(
            segments(Routes.ACTIVE_ROOM),
            segments(Routes.activeRoom("ws1", WorkspaceRole.CLIENT, "meet_ws1_standup")),
        )
        assertEquals(segments(Routes.DIALER), segments(Routes.dialer("ws1", WorkspaceRole.CLIENT)))
        assertEquals(
            segments(Routes.WORKFLOWS),
            segments(Routes.workflows("ws1", WorkspaceRole.CLIENT)),
        )
        assertEquals(segments(Routes.SCHEDULING), segments(Routes.scheduling("ws1")))
    }

    @Test
    fun `the scheduling route carries NO role, and that is the server's call rather than an omission`() {
        // ⛔ A FOURTH SHAPE ON THIS LIST, AND THE ONLY ONE WHERE A ROLE IS ABSENT DESPITE SOMETHING
        // BEING GATED. Analytics also carries none, but only because nothing behind it is gated at
        // all. Here the Enable button IS owner/admin-only and allowlist-gated on top — and `GET
        // /api/district/scheduling/status` sends `canManage` with every read, so the answer arrives
        // WITH the data. A `{role}` segment would be a second, weaker copy of it, and it would fail
        // the wrong way: `WorkspaceRole.fromWire` fails closed, so a corrupted segment would hide
        // the button from an owner the server would have admitted, on the one screen where that
        // button is the whole point.
        assertTrue(Routes.SCHEDULING.contains("{workspaceId}"))
        assertFalse("the server answers this, not the route", Routes.SCHEDULING.contains("{role}"))
        assertEquals("workspace/ws1/scheduling", Routes.scheduling("ws1"))
    }

    @Test
    fun `a workspace id containing a slash cannot escape the scheduling segment`() {
        // ⚠️ The same traversal guard every builder here carries. It matters as much on a route
        // with one argument as on one with two: an unencoded `/` would address a different
        // destination, and this one's screen mints a single-use credential.
        val route = Routes.scheduling("ws/../admin")

        assertFalse(route.contains("/../"))
        assertTrue(route.contains("ws%2F..%2Fadmin"))
        assertEquals(segmentsOf(Routes.SCHEDULING), segmentsOf(route))
    }

    @Test
    fun `the workflows route carries a role that is a PARTIAL gate`() {
        // ⛔ A THIRD KIND OF ROLE ARGUMENT, AND IT IS WORTH PINNING BECAUSE THE OTHER TWO ARE
        // ALREADY PINNED ABOVE. Analytics carries no role because nothing behind it is gated; the
        // dialler carries one that hides the destination entirely. This one carries a role while
        // the DESTINATION stays open to every role: the workflow list, the run history and the SDR
        // campaign status all admit `viewer` server-side, and only the toggle PATCH excludes them.
        // So the role decides whether ONE CONTROL works, and a template that dropped it would
        // leave a restored screen guessing — where the safe guess (offer nothing) silently strips
        // an operator's switch after a process kill.
        assertTrue(Routes.WORKFLOWS.contains("{workspaceId}"))
        assertTrue("the switch is role-gated", Routes.WORKFLOWS.contains("{role}"))
        assertEquals(
            "workspace/ws1/workflows/viewer",
            Routes.workflows("ws1", WorkspaceRole.VIEWER),
        )
    }

    @Test
    fun `a null role reaches the workflows screen as a value fromWire rejects`() {
        // ⚠️ FAILS CLOSED to a screen that reads but cannot toggle — which is exactly the viewer
        // experience, and therefore the safe direction for a role that could not be established.
        val route = Routes.workflows("ws1", null)

        assertTrue(route.endsWith("/none"))
        assertNull(WorkspaceRole.fromWire(route.substringAfterLast('/')))
    }

    @Test
    fun `a workspace id containing a slash cannot escape the workflows segment`() {
        // ⚠️ The same traversal guard every builder here carries: an unencoded `/` would address a
        // different destination entirely.
        val route = Routes.workflows("ws/../admin", WorkspaceRole.AGENCY)

        assertFalse(route.contains("/../"))
        assertTrue(route.contains("ws%2F..%2Fadmin"))
        assertEquals(segmentsOf(Routes.WORKFLOWS), segmentsOf(route))
    }

    @Test
    fun `the dialler route carries a role that is a real gate, and fails closed`() {
        // ⛔ `POST /api/district/calls/dial` EXCLUDES `viewer`, so unlike the rooms lobby this
        // role decides whether the destination can do the one thing it exists for. A null role
        // becomes the literal "none", which `WorkspaceRole.fromWire` does not recognise — so a
        // corrupted or renamed value fails CLOSED to no dialling rather than defaulting permissive.
        assertEquals(
            "workspace/ws1/dialer/viewer",
            Routes.dialer("ws1", WorkspaceRole.VIEWER),
        )
        assertEquals("workspace/ws1/dialer/none", Routes.dialer("ws1", null))
        assertNull(WorkspaceRole.fromWire("none"))
    }

    @Test
    fun `there is no in-call route, and that absence is the safety property`() {
        // ⛔ A CALL DESTINATION WOULD BE RESTORED FROM THE BACK STACK AFTER PROCESS DEATH and its
        // start effect would run again — placing a SECOND billable call to the same person with no
        // user action, to replace one that died with the process. The live call is state on the
        // dialler destination instead, so a killed process comes back to an idle keypad. This
        // asserts the absence so a future "tidy up: give the call its own route" has to argue with
        // this comment first.
        val routes = Routes::class.java.declaredFields
            .filter { it.type == String::class.java }
            .mapNotNull { field ->
                field.isAccessible = true
                field.get(Routes) as? String
            }
        assertTrue(routes.isNotEmpty())
        assertTrue(routes.none { it.contains("/call/") || it.endsWith("/in-call") })
    }

    @Test
    fun `a workspace id containing a slash cannot escape the dialler segment`() {
        // ⚠️ Same encoding guarantee every builder in this object carries: a value must not be
        // able to change WHERE a route points.
        assertEquals(
            "workspace/ws%2F..%2Fadmin/dialer/agency",
            Routes.dialer("ws/../admin", WorkspaceRole.AGENCY),
        )
    }

    @Test
    fun `the live-room route carries the WHOLE room name as one segment`() {
        // ⛔ THE ROOM NAME IS WHAT A MEDIA TOKEN IS MINTED AGAINST, so a destination restored after
        // process death must hold the exact string rather than a recipe for rebuilding it —
        // rebuilding is precisely where a `video_` name (a BILLABLE Tavus avatar session, one
        // character away) could be produced by mistake.
        val route = Routes.activeRoom("ws1", WorkspaceRole.AGENCY, "meet_ws1_weekly-review")

        assertEquals("workspace/ws1/rooms/agency/meet_ws1_weekly-review", route)
        // ⚠️ Underscores survive: `Uri.encode` leaves them alone, and the server's regex splits on
        // them. A room name that arrived percent-encoded would not match any room.
        assertTrue(route.endsWith("meet_ws1_weekly-review"))
    }

    @Test
    fun `a room name containing a slash cannot address another destination`() {
        // ⛔ THE ENCODING THIS WHOLE FILE EXISTS FOR, on the one route whose argument is a value a
        // token is minted against. `MeetRoomName` strips slashes before one is built, but this
        // route also carries a name that came back from the SERVER (a rejoin reads
        // `Meeting.roomName`), and that string has never been through the client's normaliser.
        val route = Routes.activeRoom("ws1", WorkspaceRole.AGENCY, "meet_ws1_a/../../admin")

        assertFalse("a slash must not survive as a separator", route.contains("a/../"))
        assertEquals(
            "workspace/ws1/rooms/agency/" + android.net.Uri.encode("meet_ws1_a/../../admin"),
            route,
        )
    }

    @Test
    fun `a null role on either rooms route becomes the literal none`() {
        // ⚠️ Which `WorkspaceRole.fromWire` rejects, so a corrupted role publishes nothing rather
        // than defaulting to a permissive one — the same direction every sibling here fails.
        assertTrue(Routes.rooms("ws1", null).endsWith("/none"))
        assertTrue(Routes.activeRoom("ws1", null, "meet_ws1_standup").contains("/none/"))
    }

    @Test
    fun `the marketplace route carries a role even though nothing on it is gated`() {
        // ⚠️ THE OPPOSITE CALL FROM ANALYTICS ONE TEST BELOW, AND BOTH ARE DELIBERATE. Both of
        // the marketplace's routes admit viewers, so there is nothing there to GATE — but there
        // is something to WORD: the read-only caption tells an agency or client member to make
        // the change on the web dashboard, and tells a viewer to ask someone who can. Sending a
        // viewer to a dashboard that will also refuse them is worse than saying nothing, so the
        // role has to survive process death with the destination.
        assertTrue(Routes.MARKETPLACE.contains("{workspaceId}"))
        assertTrue("the caption is role-dependent", Routes.MARKETPLACE.contains("{role}"))
    }

    @Test
    fun `a null role reaches the marketplace as a value fromWire rejects`() {
        // ⚠️ Fails CLOSED: "none" is not a role, so the destination renders the viewer wording
        // rather than assuming the permissive caption.
        val route = Routes.marketplace("ws1", null)

        assertTrue(route.endsWith("/none"))
        assertNull(WorkspaceRole.fromWire("none"))
    }

    @Test
    fun `the analytics route carries no role segment`() {
        // ⚠️ THE ONLY WORKSPACE-SCOPED DRILL-DOWN WITHOUT ONE, and deliberately. Contacts, the
        // Inbox and HQ each encode a role because each has a control the server refuses to a
        // viewer; analytics has none — both routes behind it admit agency, client and viewer
        // alike. A role in the URL that decides nothing is worse than its absence, because the
        // next reader has to prove it is unused.
        assertTrue(Routes.ANALYTICS.contains("{workspaceId}"))
        assertFalse("analytics is read-only for every role", Routes.ANALYTICS.contains("{role}"))
    }

    @Test
    fun `an id that would change where the analytics route points is encoded`() {
        // ⛔ A SLASH IN AN ID ADDRESSES A DIFFERENT DESTINATION. Ids are server-generated cuids,
        // but the builder must not be the thing that assumes so.
        val route = Routes.analytics("ws/../admin")

        assertFalse("a slash must not survive into the path", route.removePrefix("workspace/").contains("/admin"))
        assertTrue(route.endsWith("/analytics"))
    }

    @Test
    fun `the thread route nests under its inbox`() {
        // The thread template is the inbox template plus one segment, so a thread's parent is
        // derivable rather than duplicated. If they diverge, a back press from a thread lands
        // somewhere that is not the list it came from.
        assertTrue(Routes.THREAD.startsWith(Routes.INBOX))
        val inbox = Routes.inbox("ws1", WorkspaceRole.AGENCY)
        assertTrue(Routes.thread("ws1", WorkspaceRole.AGENCY, "contact:c1").startsWith(inbox))
    }

    @Test
    fun `a null role becomes a literal that fails closed`() {
        // ⛔ "none" MUST NOT PARSE BACK TO A ROLE. The route has to carry something for the segment
        // to exist at all, and whatever it carries is read back through fromWire — which fails closed
        // to null for anything unrecognised. If "none" ever became a recognised role, a workspace
        // whose role could not be resolved would silently be granted that role's privileges.
        val route = Routes.inbox("ws1", null)
        assertTrue("the placeholder must be in the path", route.endsWith("/none"))
        assertNull("the placeholder must not resolve to a role", WorkspaceRole.fromWire("none"))
    }

    @Test
    fun `a null role fails closed on the contacts, HQ and billing routes too`() {
        // ⚠️ The same placeholder as the inbox, on the three builders whose null case nothing
        // else exercises. Each is a destination whose role decides a control.
        listOf(
            Routes.contacts("ws1", null),
            Routes.contactDetail("ws1", "c1", null).substringBeforeLast("/"),
            Routes.hq("ws1", null),
            Routes.billing("ws1", null),
        ).forEach { route -> assertTrue(route, route.endsWith("/none")) }
    }

    @Test
    fun `a blank reply target or title is left out of a thread route rather than sent empty`() {
        // ⛔ A BLANK `replyTo` MUST NOT BECOME A RECIPIENT. The destination drops blanks too, but the
        // builder never writing one is what keeps a blank off the back stack in the first place.
        val route = Routes.thread(
            "ws1",
            WorkspaceRole.CLIENT,
            "contact:c1",
            replyTo = " ",
            replyChannel = "",
            title = "  ",
        )
        assertFalse("no query string at all: $route", route.contains("?"))
        assertEquals(Routes.thread("ws1", WorkspaceRole.CLIENT, "contact:c1"), route)
    }

    @Test
    fun `every role round-trips through the path`() {
        for (role in WorkspaceRole.entries) {
            val segment = Routes.inbox("ws1", role).substringAfterLast("/")
            assertEquals(
                "the role segment must read back as the same role",
                role,
                WorkspaceRole.fromWire(segment),
            )
        }
    }

    @Test
    fun `ids that could change where a route points are encoded`() {
        // ⛔ THE SLASH IS THE DANGEROUS ONE. An id containing '/' would add a segment and address a
        // different destination entirely — for CONTACT_DETAIL, a contact id of "a/b" would make the
        // route look like a five-segment path that matches no template, so the screen never opens.
        // Server ids are cuids and contain none of this, but "the server would never send that" is
        // not a property this client can enforce.
        val hostile = "a/b?c=d#e f"
        val route = Routes.contactDetail("ws1", hostile, WorkspaceRole.CLIENT)

        assertTrue("a raw slash must not survive into the path", !route.contains("a/b"))
        assertTrue("the query delimiter must be encoded", !route.contains("?"))
        assertTrue("the fragment delimiter must be encoded", !route.contains("#"))
        assertEquals(
            "the encoded id must still be exactly one segment",
            Routes.CONTACT_DETAIL.split("/").size,
            route.split("/").size,
        )
    }

    @Test
    fun `a contact thread key parses to a contact id and no address`() {
        val selector = parseThreadKey("contact:cme6yv8g30000abcd1234efgh")

        assertEquals("cme6yv8g30000abcd1234efgh", selector.contactId)
        assertNull("a contact-keyed thread has no address selector", selector.address)
    }

    @Test
    fun `an address thread key parses to an address and no contact id`() {
        // ⚠️ Bare digits, which is the NORMALISED form the server emits — not the '+' form the
        // message row stores. The timeline endpoint accepts either because phoneVariants regenerates
        // both, so passing it straight through is correct and reformatting it here would not be.
        val selector = parseThreadKey("addr:14165550168")

        assertNull("an address-keyed thread has no contact id", selector.contactId)
        assertEquals("14165550168", selector.address)
    }

    @Test
    fun `an email address thread key survives the prefix split`() {
        // ⛔ THE '@' AND THE '.' ARE THE POINT. An email address is a legal path segment but only
        // once encoded, and `removePrefix` operates on the DECODED value — so this asserts the two
        // halves compose: Routes.thread encodes, Navigation decodes, and the parser sees the address
        // back exactly as the server sent it.
        val selector = parseThreadKey("addr:ada@contract.test")

        assertNull(selector.contactId)
        assertEquals("ada@contract.test", selector.address)
    }

    @Test
    fun `a threadKey survives encoding into a route and back`() {
        // ⛔ THE ROUND TRIP, WHICH IS THE ONLY THING THAT PROVES THE TWO SIDES AGREE. The builder
        // encodes and the parser reads a decoded argument, so testing either alone proves nothing
        // about a value that has to pass through both. `+` is the specific trap: percent-decoding
        // leaves it alone, but `Uri.decode` (and any form-decoder) turns a RAW '+' into a space, so
        // an unencoded phone number comes back as "1416 5550168".
        for (threadKey in listOf(
            "contact:cme6yv8g30000abcd1234efgh",
            "addr:14165550168",
            "addr:+14165550168",
            "addr:ada@contract.test",
            "addr:Paul O'Brien <paul@example.test>",
        )) {
            val route = Routes.thread("ws-1", WorkspaceRole.CLIENT, threadKey)
            val encoded = route.substringAfterLast("/")
            assertEquals(
                "the encoded threadKey must decode back byte-identically: $threadKey",
                threadKey,
                android.net.Uri.decode(encoded),
            )
            val selector = parseThreadKey(android.net.Uri.decode(encoded))
            assertTrue(
                "exactly one selector must resolve for $threadKey",
                (selector.contactId == null) != (selector.address == null),
            )
        }
    }

    @Test
    fun `an unrecognised or empty thread key resolves to neither selector`() {
        // ⛔ DEGRADES, DOES NOT THROW. An empty argument is reachable after process death, and a
        // future server-side prefix is reachable without any client change. Both must produce a
        // thread that fails to load and offers a retry — not a crash on a screen the user merely
        // could not open. The ThreadViewModel treats two nulls as "nothing to ask for".
        for (malformed in listOf("", "cme6yv8g30000abcd1234efgh", "group:g1", "contact", "addr")) {
            val selector = parseThreadKey(malformed)
            assertNull("no contact id for '$malformed'", selector.contactId)
            assertNull("no address for '$malformed'", selector.address)
        }
    }

    @Test
    fun `an empty selector body is still recognised as its kind`() {
        // ⚠️ `contact:` with nothing after it yields an EMPTY id, not null — the prefix was present,
        // so the kind is known even though the value is useless. Asserted because the alternative
        // reading (treat empty as unrecognised) would send the ADDRESS selector for a contact-keyed
        // thread, and the server would answer a different customer's history or none at all.
        assertEquals("", parseThreadKey("contact:").contactId)
        assertNull(parseThreadKey("contact:").address)
        assertEquals("", parseThreadKey("addr:").address)
        assertNull(parseThreadKey("addr:").contactId)
    }

    // ── Workspace settings ───────────────────────────────────────────────────

    @Test
    fun `the workspace settings hub and its sections match their templates`() {
        // ⛔ A ROUTE THAT DOES NOT MATCH ITS TEMPLATE IS A 404 AT RUNTIME AND NOTHING TYPE-CHECKS
        // IT. The three templates and the one builder live in the same object precisely so this
        // can be asserted; the builder takes a section rather than existing three times because
        // detekt caps this object's function count, and the sections are genuinely children of the
        // hub's path.
        assertEquals(
            "workspace/ws-1/settings/agency",
            Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY),
        )
        assertEquals(
            "workspace/ws-1/settings/client/persona",
            Routes.workspaceSettings("ws-1", WorkspaceRole.CLIENT, Routes.SECTION_PERSONA),
        )
        assertEquals(
            "workspace/ws-1/settings/client/capabilities",
            Routes.workspaceSettings("ws-1", WorkspaceRole.CLIENT, Routes.SECTION_CAPABILITIES),
        )

        // Each built route must have the same segment COUNT as the template it is meant to match —
        // the cheapest structural check that a section did not land on the wrong destination.
        listOf(
            Routes.WORKSPACE_SETTINGS to Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY),
            Routes.WORKSPACE_SETTINGS_PERSONA to
                Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY, Routes.SECTION_PERSONA),
            Routes.WORKSPACE_SETTINGS_CAPABILITIES to
                Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY, Routes.SECTION_CAPABILITIES),
            Routes.WORKSPACE_SETTINGS_DIRECTORY to
                Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY, Routes.SECTION_DIRECTORY),
            Routes.WORKSPACE_SETTINGS_ROUTING to
                Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY, Routes.SECTION_ROUTING),
            Routes.WORKSPACE_SETTINGS_KNOWLEDGE to
                Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY, Routes.SECTION_KNOWLEDGE),
            Routes.WORKSPACE_SETTINGS_MESSAGING to
                Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY, Routes.SECTION_MESSAGING),
            Routes.WORKSPACE_SETTINGS_MEMBERS to
                Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY, Routes.SECTION_MEMBERS),
            Routes.WORKSPACE_SETTINGS_VOICE_STUDIO to
                Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY, Routes.SECTION_VOICE_STUDIO),
        ).forEach { (template, built) ->
            assertEquals(
                "$built must have the same shape as $template",
                template.split("/").size,
                built.split("/").size,
            )
        }
    }

    @Test
    fun `a null role becomes the literal none, which fails closed`() {
        // ⛔ AND HERE THAT MATTERS MORE THAN ANYWHERE ELSE ON THIS LIST. Every route behind this
        // hub excludes `viewer` server-side INCLUDING the read, so an unparseable role must resolve
        // to no privileges rather than to a default — `fromWire` rejects "none".
        val route = Routes.workspaceSettings("ws-1", null)
        assertEquals("workspace/ws-1/settings/none", route)
        assertNull(WorkspaceRole.fromWire("none"))
    }

    @Test
    fun `the hub's own section constants agree with the navigation graph's`() {
        // ⛔ TWO DECLARATIONS OF THE SAME LITERALS, IN TWO PACKAGES, AND NOTHING ELSE CONNECTS THEM.
        // `Routes` lives in `ui` and the hub screen lives in `ui.settings.workspace`, which must not
        // depend on the navigation graph — so the hub passes a section STRING up and the graph turns
        // it into a route. A disagreement is a tap that silently does nothing, because Navigation
        // treats an unmatched route as a no-op rather than an error. This is the only place the two
        // sets are compared.
        assertEquals(
            com.distronode.districtai.ui.settings.workspace.SECTION_DIRECTORY,
            Routes.SECTION_DIRECTORY,
        )
        assertEquals(
            com.distronode.districtai.ui.settings.workspace.SECTION_ROUTING,
            Routes.SECTION_ROUTING,
        )
        assertEquals(
            com.distronode.districtai.ui.settings.workspace.SECTION_KNOWLEDGE,
            Routes.SECTION_KNOWLEDGE,
        )
        assertEquals(
            com.distronode.districtai.ui.settings.workspace.SECTION_MESSAGING,
            Routes.SECTION_MESSAGING,
        )
        assertEquals(
            com.distronode.districtai.ui.settings.workspace.SECTION_MEMBERS,
            Routes.SECTION_MEMBERS,
        )
        assertEquals(
            com.distronode.districtai.ui.settings.workspace.SECTION_VOICE_STUDIO,
            Routes.SECTION_VOICE_STUDIO,
        )
        assertEquals(
            "workspace/ws-1/settings/agency/voice-studio",
            Routes.workspaceSettings("ws-1", WorkspaceRole.AGENCY, Routes.SECTION_VOICE_STUDIO),
        )
    }

    @Test
    fun `every settings section template is a child of the hub's path`() {
        // ⚠️ SO A BACK PRESS FROM AN EDITOR LANDS ON THE HUB rather than on the overview — which is
        // what a hub is for, and what makes "open two editors in a row" not a maze.
        listOf(
            Routes.WORKSPACE_SETTINGS_PERSONA,
            Routes.WORKSPACE_SETTINGS_CAPABILITIES,
            Routes.WORKSPACE_SETTINGS_DIRECTORY,
            Routes.WORKSPACE_SETTINGS_ROUTING,
            Routes.WORKSPACE_SETTINGS_KNOWLEDGE,
            Routes.WORKSPACE_SETTINGS_MESSAGING,
            Routes.WORKSPACE_SETTINGS_MEMBERS,
            Routes.WORKSPACE_SETTINGS_VOICE_STUDIO,
        ).forEach { template ->
            assertTrue("$template must nest under the hub", template.startsWith(Routes.WORKSPACE_SETTINGS))
        }
    }

    @Test
    fun `the members section carries a role that gates two things at different widths`() {
        // ⛔ UNIQUE ON THIS LIST. The membership WRITES behind this destination are agency-only —
        // the narrowest guard in the API, because these rows are what every other permission check
        // is derived from — while the rename it also hosts admits agency and client. So
        // `WorkspaceRole.canMutate` is the wrong gate for one half and the right one for the other,
        // which is exactly the case its own KDoc warns about. Both are derived from this segment.
        assertTrue(Routes.WORKSPACE_SETTINGS_MEMBERS.contains("{role}"))
        assertEquals(
            "workspace/ws-1/settings/viewer/members",
            Routes.workspaceSettings("ws-1", WorkspaceRole.VIEWER, Routes.SECTION_MEMBERS),
        )
        // ⚠️ AND A VIEWER CAN STILL REACH IT ONLY BECAUSE THE HUB IN FRONT IS GATED, not because
        // this template refuses them: the roster READ admits viewer server-side.
        assertEquals(WorkspaceRole.VIEWER, WorkspaceRole.fromWire("viewer"))
    }

    @Test
    fun `a workspace id containing a slash cannot escape its segment`() {
        // ⚠️ Same rule as every sibling builder: an id that could introduce segments would address
        // a different destination. Ids are server-generated cuids, but the encoding is what makes
        // that irrelevant.
        val route = Routes.workspaceSettings("ws/../admin", WorkspaceRole.AGENCY)
        assertEquals(
            Routes.WORKSPACE_SETTINGS.split("/").size,
            route.split("/").size,
        )
    }
}

/** ⚠️ The same shape check `templates and builders agree on segment count` makes, hoisted so a
 * test outside that method can reuse it rather than re-deriving it. */
private fun segmentsOf(route: String): Int = route.split("/").size
