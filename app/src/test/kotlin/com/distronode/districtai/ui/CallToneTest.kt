package com.distronode.districtai.ui

import com.distronode.districtai.auth.LoginStatus
import com.distronode.districtai.core.designsystem.Tone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⛔ THESE TWO MAPPERS DECIDE WHAT COLOUR A FAILURE IS, WHICH IS THE ONLY REASON STATE IS SCANNABLE.
 * Before them every status rendered as identical grey text, so a completed call and a failed one
 * differed only in the word — at the far right of a 1920px row. A wrong tone is worse than no tone:
 * it asserts something about a call, so it is worth pinning rather than eyeballing.
 *
 * ⚠️ Plain JUnit, no Robolectric and no Compose. Both mappers are pure functions over a String and a
 * sealed type, which is exactly why they live outside the composables that use them.
 */
class CallToneTest {

    // ── Call status ──────────────────────────────────────────────────────────

    @Test
    fun `terminal success statuses are green`() {
        listOf("completed", "complete", "answered").forEach {
            assertEquals(it, Tone.Success, toneForCallStatus(it))
        }
    }

    @Test
    fun `terminal failures are red, including both provider spellings`() {
        // ⚠️ `busy` and `no-answer` are Twilio's; `failed` and `canceled` are the
        // provider-agnostic ones the route can also emit. Missing either family would paint a real
        // failure neutral.
        listOf("failed", "busy", "no-answer", "noanswer", "canceled", "cancelled").forEach {
            assertEquals(it, Tone.Danger, toneForCallStatus(it))
        }
    }

    @Test
    fun `in-flight statuses take the accent, not a verdict colour`() {
        listOf("in-progress", "in_progress", "inprogress", "ringing", "queued", "initiated").forEach {
            assertEquals(it, Tone.District, toneForCallStatus(it))
        }
    }

    @Test
    fun `an unknown status is neutral rather than a guess`() {
        // ⛔ The status column is a plain string with no enum behind it, so an unmodelled value is a
        // real possibility. Painting it green or red would assert something this client cannot know.
        assertEquals(Tone.Neutral, toneForCallStatus("teleported"))
        assertEquals(Tone.Neutral, toneForCallStatus(""))
        assertEquals(Tone.Neutral, toneForCallStatus(null))
    }

    @Test
    fun `matching is case and whitespace insensitive`() {
        // ⚠️ Nothing normalises this column on write, and the tier column in the same database is
        // verified MIXED CASE ("VoicePro"), so assuming lowercase is exactly the bug that makes a
        // comparison silently never match.
        assertEquals(Tone.Success, toneForCallStatus("COMPLETED"))
        assertEquals(Tone.Success, toneForCallStatus("  Completed  "))
        assertEquals(Tone.Danger, toneForCallStatus("No-Answer"))
    }

    // ── Login status ─────────────────────────────────────────────────────────

    @Test
    fun `a refused callback is alarming, and a wait is not`() {
        // ⛔ THE DISTINCTION THAT MATTERS. `Refused` is the authorization-code-injection case — a
        // callback whose state did not match the one this app generated. It must not look like the
        // progress note next to it.
        assertEquals(Tone.Danger, toneForLoginStatus(LoginStatus.Refused))
        assertEquals(Tone.District, toneForLoginStatus(LoginStatus.WaitingForBrowser))
        assertEquals(Tone.District, toneForLoginStatus(LoginStatus.Completing))
    }

    @Test
    fun `no browser is a dead end, not a retry`() {
        assertEquals(Tone.Danger, toneForLoginStatus(LoginStatus.NoBrowser))
    }

    @Test
    fun `recoverable failures warn rather than alarm`() {
        listOf(
            LoginStatus.DidNotComplete,
            LoginStatus.Interrupted,
            LoginStatus.Expired,
            LoginStatus.RateLimited,
            LoginStatus.Unreachable,
        ).forEach { assertEquals("$it", Tone.Warning, toneForLoginStatus(it)) }
    }

    @Test
    fun `a server-named refusal stays neutral`() {
        // ⚠️ Server-authored and could be anything from "user cancelled" to a policy refusal, so the
        // frame does not assert a severity this client cannot judge.
        assertEquals(Tone.Neutral, toneForLoginStatus(LoginStatus.Denied("sso_refused")))
    }
}
