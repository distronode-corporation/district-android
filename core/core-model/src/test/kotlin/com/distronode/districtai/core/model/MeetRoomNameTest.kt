package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one function that builds a room name.
 *
 * ⛔ THIS TEST EXISTS FOR ONE ASSERTION AND THE REST IS SUPPORTING WORK: no input can make this
 * produce a `video_` name. `/api/district/calls/token` accepts `meet_` and `video_` through the
 * same `startsWith` chain and the same regex, and treats both as standalone rooms — so nothing
 * server-side would refuse a `video_` name minted by mistake. It would simply work, and start a
 * BILLABLE Tavus avatar session for a surface this app does not implement. There is no error to
 * notice and no log to read; the only place the mistake is catchable is here.
 */
class MeetRoomNameTest {

    @Test
    fun `the prefix is meet_ and a video_ name is unreachable`() {
        val name = MeetRoomName.of("ws-abc", "standup")

        assertEquals("meet_ws-abc_standup", name)
        assertTrue(name!!.startsWith("meet_"))

        // ⛔ THE GUARD. Every input that could plausibly be aimed at the avatar surface — the word
        // itself, the prefix as a workspace, the prefix as a suffix — still produces a `meet_`
        // name. If a future edit made the prefix a parameter, or interpolated part of the input
        // ahead of the prefix, this is what fails.
        val hostile = listOf(
            "video" to "standup",
            "video_ws" to "standup",
            "ws-abc" to "video_room",
            "ws-abc" to "VIDEO",
            "" to "video",
        )
        hostile.forEach { (workspace, suffix) ->
            val built = MeetRoomName.of(workspace, suffix)
            assertFalse(
                "\"$workspace\" / \"$suffix\" must not produce a video_ room",
                built.orEmpty().startsWith("video_"),
            )
            if (built != null) {
                assertTrue(
                    "\"$workspace\" / \"$suffix\" must produce a meet_ room",
                    built.startsWith("meet_"),
                )
            }
        }
    }

    @Test
    fun `an empty suffix is refused rather than sent`() {
        // ⛔ NULL, NOT `meet_<ws>_`. That name fails the server's `^(meet|video)_([a-zA-Z0-9-]+)_(.+)$`
        // and comes back as 400 "Invalid meeting room format", which reads as a server fault for
        // what is really an empty field.
        assertNull(MeetRoomName.of("ws-abc", ""))
        assertNull(MeetRoomName.of("ws-abc", "   "))
        // ⚠️ Punctuation-only normalises to nothing, so it is the same case as empty.
        assertNull(MeetRoomName.of("ws-abc", "!!!"))
        assertNull(MeetRoomName.of("ws-abc", "---"))
        // A blank workspace cannot address anything either.
        assertNull(MeetRoomName.of("", "standup"))
        assertNull(MeetRoomName.of("   ", "standup"))
    }

    @Test
    fun `normalisation matches what the web lobby accepts`() {
        // ⚠️ The web restricts a typed suffix to [a-zA-Z0-9-]; two people who type "Weekly Review"
        // and "weekly review" must land in the SAME room, which is only true if both platforms
        // normalise identically.
        assertEquals("weekly-review", MeetRoomName.normalizeSuffix("Weekly Review"))
        assertEquals("weekly-review", MeetRoomName.normalizeSuffix("  weekly review  "))
        assertEquals("standup", MeetRoomName.normalizeSuffix("Stand/up!"))
        assertEquals("q3-planning", MeetRoomName.normalizeSuffix("Q3 planning"))
        // ⚠️ Leading and trailing hyphens are trimmed: `meet_ws_-standup-` is legal server-side but
        // reads as a typo and would not match what anyone else typed.
        assertEquals("standup", MeetRoomName.normalizeSuffix("-standup-"))
        // ⚠️ Underscores are stripped, not kept, even though the server's `.+` would accept them —
        // an underscore in the suffix makes the name ambiguous to anything splitting on `_`.
        assertEquals("weeklyreview", MeetRoomName.normalizeSuffix("weekly_review"))
    }

    @Test
    fun `isMeetRoom filters out rooms from other surfaces`() {
        // ⚠️ The Meeting table records whatever room the Companion was dispatched into, which
        // includes `video_` avatar sessions. A row that fails this belongs to another product
        // surface; it is not corrupt data and it is not an error.
        assertTrue(MeetRoomName.isMeetRoom("meet_ws-abc_standup"))
        assertFalse(MeetRoomName.isMeetRoom("video_ws-abc_standup"))
        assertFalse(MeetRoomName.isMeetRoom("CA1234567890"))
        assertFalse(MeetRoomName.isMeetRoom(""))
    }

    @Test
    fun `displayName shows the human half and never blanks`() {
        assertEquals("standup", MeetRoomName.displayName("meet_ws-abc_standup"))
        // ⚠️ SPLITS ON EXACTLY TWO SEPARATORS. The suffix may legitimately contain further
        // underscores when the name came from somewhere other than `of` — the server accepts `.+`
        // there — and a naive `split("_").last()` would show only the tail.
        assertEquals("weekly_review", MeetRoomName.displayName("meet_ws-abc_weekly_review"))
        // ⛔ FALLS BACK TO THE WHOLE STRING RATHER THAN TO EMPTY. A row whose name has another
        // shape still has to render as something; a blank title is a row nobody can identify.
        assertEquals("video_ws_avatar", MeetRoomName.displayName("video_ws_avatar"))
        assertEquals("nonsense", MeetRoomName.displayName("nonsense"))
        assertEquals("meet_ws_", MeetRoomName.displayName("meet_ws_"))
    }

    @Test
    fun `a workspace id keeps its hyphens and a suffix keeps its digits`() {
        // ⚠️ The server's regex admits `[a-zA-Z0-9-]` in the workspace group, so hyphenated ids
        // (which is what this product mints) must survive untouched — the suffix normaliser must
        // never be applied to the workspace half.
        assertEquals(
            "meet_ws-contract-test_q3-planning",
            MeetRoomName.of("ws-contract-test", "Q3 Planning"),
        )
    }
}
