package com.distronode.districtai.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.auth.ReauthReason
import com.distronode.districtai.core.data.PagedLoadException
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one mapping every screen shares.
 *
 * ⛔ WHAT THIS PROTECTS. The rule is that a failure is never presented as an absence of data. "We
 * could not look" and "there is nothing" are different answers, and showing the second when the
 * first is true reads as account loss — a mistake this product has already shipped once on the web,
 * where an unreachable region produced an empty workspace list and sent a paying customer to a
 * checkout page. Centralising the mapping is what stops each new screen re-deriving it.
 *
 * ⚠️ ROBOLECTRIC, BECAUSE THE ASSERTIONS HERE ARE ABOUT WORDING. The mapping now yields a [UiText]
 * rather than a `String` — the whole point of Fix 8, since a mapper has no `Context` and was
 * therefore holding untranslatable English — so proving "this message tells the user their account
 * has not changed" means resolving a resource, which needs one. Tests that only care WHICH message
 * was chosen assert on [UiText.resourceIdOrNull] and stay off Robolectric.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class FailureTextTest {

    @Test
    fun `no session is a first run, not a sign-out to apologise for`() {
        val text = ApiResult.Unauthorized(ReauthReason.NoSession).toFailureText()

        assertEquals(SignedOutCause.NEVER_SIGNED_IN, text.signedOutCause)
        // ⚠️ Wording matters: "please sign in again" is wrong the first time the app is opened.
        assertFalse("a dead session cannot be retried", text.retryable)
    }

    @Test
    fun `an interrupted refresh is routine, not a security event`() {
        // The process died mid-refresh, so the stored token is presumed spent. Re-sending it would
        // revoke the whole token family and log a replay warning describing an attack that did not
        // happen — so the client signs out instead, and the copy must not alarm.
        assertEquals(
            SignedOutCause.ROUTINE,
            ApiResult.Unauthorized(ReauthReason.InterruptedRefresh).toFailureText().signedOutCause,
        )
        assertEquals(
            SignedOutCause.ROUTINE,
            ApiResult.Unauthorized(ReauthReason.RefreshUnreachable).toFailureText().signedOutCause,
        )
    }

    @Test
    fun `every unauthorized reason signs the user out with the signed-out wording`() {
        // ⛔ NO VALUE OF ReauthReason MAY PRODUCE A NULL CAUSE. Everything downstream keys on
        // `signedOutCause` being non-null to tear the graph down and show the sign-in screen, so a
        // null here would strand the user on a screen whose every request fails.
        (ReauthReason.entries + null).forEach { reason ->
            val text = ApiResult.Unauthorized(reason).toFailureText()

            assertNotNull("$reason must sign the user out", text.signedOutCause)
            assertEquals("$reason", R.string.failure_signed_out, text.message.resourceIdOrNull)
            // ⚠️ Not retryable: the credential is gone, so pressing a button re-sends nothing.
            assertFalse("$reason", text.retryable)
        }
    }

    @Test
    fun `a refused or expired credential is an invalid session`() {
        assertEquals(
            SignedOutCause.SESSION_INVALID,
            ApiResult.Unauthorized(ReauthReason.RefreshRejected).toFailureText().signedOutCause,
        )
        assertEquals(
            SignedOutCause.SESSION_INVALID,
            ApiResult.Unauthorized(ReauthReason.RefreshTokenExpired).toFailureText().signedOutCause,
        )
        // A bare 401 straight from the API carries no reason.
        assertEquals(
            SignedOutCause.SESSION_INVALID,
            ApiResult.Unauthorized(reason = null).toFailureText().signedOutCause,
        )
    }

    @Test
    fun `degraded regions are named and retryable, and are not a sign-out`() {
        // ⛔ THE INCIDENT CASE. The account is unchanged; the read simply did not finish.
        val text = ApiResult.RegionsDegraded("unreachable", listOf("eu", "apac")).toFailureText()

        assertNull("a degraded region must not sign anyone out", text.signedOutCause)
        assertEquals(listOf("eu", "apac"), text.degradedRegions)
        assertTrue("retrying is exactly the right advice", text.retryable)
        assertTrue(
            "the message must say the account is unchanged, was '${text.message}'",
            text.message.resolveInTest().contains("has not changed"),
        )
    }

    @Test
    fun `a rate limit keeps the session and stays retryable`() {
        // ⛔ NOT A SIGN-OUT. The server rate-limits before rotating a refresh token, so nothing was
        // spent, and the limits are sized to tolerate several devices behind one NAT.
        val text = ApiResult.RateLimited("Too many requests.").toFailureText()

        assertNull(text.signedOutCause)
        assertTrue(text.retryable)
        assertEquals("Too many requests.", text.message.literalOrNull)
    }

    @Test
    fun `a throttled refresh is also just rate limited`() {
        val text = ApiResult.RateLimited("slow down", refreshThrottled = true).toFailureText()

        assertNull("a throttled refresh must not sign the user out", text.signedOutCause)
        assertTrue(text.retryable)
    }

    @Test
    fun `a role refusal is not retryable`() {
        // ⚠️ The role will not change because the user pressed a button again, and offering "try
        // again" implies this attempt was merely unlucky.
        val text = ApiResult.Forbidden("Forbidden: Insufficient workspace privileges").toFailureText()

        assertFalse(text.retryable)
        assertEquals("Forbidden: Insufficient workspace privileges", text.message.literalOrNull)
    }

    @Test
    fun `a not-found is not retryable and keeps the server's wording`() {
        val text = ApiResult.NotFound("Call not found").toFailureText()

        assertFalse(text.retryable)
        assertEquals("Call not found", text.message.literalOrNull)
    }

    @Test
    fun `being offline says so and is retryable`() {
        val text = ApiResult.NetworkFailure(IOException("no route")).toFailureText()

        assertTrue(text.retryable)
        assertTrue(
            "should mention the connection, was '${text.message}'",
            text.message.resolveInTest().contains("connection", ignoreCase = true),
        )
    }

    @Test
    fun `contract drift points at updating the app and offers no retry`() {
        // ⛔ "Check your connection" would be wrong AND unactionable: retrying can never fix a shape
        // this build cannot parse, so a retry button would be a button that can only fail.
        val text = ApiResult.DecodeFailure(RuntimeException("bad shape"), "{}").toFailureText()

        assertFalse("retrying cannot fix a parse failure", text.retryable)
        assertTrue(
            "should mention updating, was '${text.message}'",
            text.message.resolveInTest().contains("Updating", ignoreCase = true),
        )
    }

    @Test
    fun `a 4xx keeps the server's own wording, which is more specific than ours`() {
        // ⚠️ The server authors a sentence for the user here, and a generic message would make a 409
        // duplicate indistinguishable from a validation refusal.
        val text = ApiResult.HttpFailure(409, "A contact with that phone number already exists.")
            .toFailureText()

        assertEquals("A contact with that phone number already exists.", text.message.literalOrNull)
        assertTrue(text.retryable)
    }

    @Test
    fun `a 5xx body is NEVER shown to the user`() {
        // ⛔ THE LEAK THIS BRANCH CLOSES. `contacts` routes return the RAW exception message on an
        // unhandled error, so the body of a 500 can be a Postgres constraint name or a Prisma stack
        // fragment — internal schema detail, in an alarming register, that the user can do nothing
        // with. It used to be rendered verbatim.
        val leaked = "Invalid prisma.contact.create(): Unique constraint failed on: (phoneNumber)"
        val text = ApiResult.HttpFailure(500, leaked, "INTERNAL_ERROR").toFailureText()

        assertNull("the server's 5xx body must not reach a screen", text.message.literalOrNull)
        assertEquals(R.string.failure_server, text.message.resourceIdOrNull)
        val shown = text.message.resolveInTest()
        assertFalse("leaked '$shown'", shown.contains("prisma", ignoreCase = true))
        assertFalse("leaked '$shown'", shown.contains("constraint", ignoreCase = true))
        // ⚠️ Still retryable: a 5xx genuinely can succeed on a second attempt.
        assertTrue(text.retryable)
    }

    @Test
    fun `the 4xx-5xx boundary is the status, not the body`() {
        // A regression guard on the boundary itself, worded about the rule rather than about one
        // endpoint's habits: below 500 the server's wording is for the user, at or above it, it is
        // not. 499 is not a status this API returns.
        assertEquals("edge", ApiResult.HttpFailure(499, "edge").toFailureText().message.literalOrNull)
        assertNull(ApiResult.HttpFailure(500, "edge").toFailureText().message.literalOrNull)
    }

    @Test
    fun `only unauthorized failures ever report a sign-out`() {
        // A regression guard on the shape of the mapping itself: if a future branch starts reporting
        // a sign-out cause, that is a decision to sign users out and it should be deliberate.
        val nonAuthFailures = listOf(
            ApiResult.RegionsDegraded("x", emptyList()),
            ApiResult.RateLimited("x"),
            ApiResult.Forbidden("x"),
            ApiResult.NotFound("x"),
            ApiResult.NetworkFailure(IOException("x")),
            ApiResult.DecodeFailure(RuntimeException("x"), ""),
            ApiResult.HttpFailure(500, "x"),
        )

        nonAuthFailures.forEach { failure ->
            assertNull(
                "${failure::class.simpleName} must not sign the user out",
                failure.toFailureText().signedOutCause,
            )
        }
    }

    // ── Paging's error channel ───────────────────────────────────────────────

    @Test
    fun `a paged load failure carries the API's own failure through`() {
        val text = PagedLoadException(ApiResult.NetworkFailure(IOException("offline")))
            .toPagedFailure(UiText.Literal("fallback"))

        assertEquals(ApiResult.NetworkFailure(IOException("offline")).toFailureText().message, text.message)
        assertTrue(text.retryable)
    }

    @Test
    fun `an error Paging raised itself gets the caller's fallback, retryable, with no invented cause`() {
        val text = IllegalStateException("paging internals").toPagedFailure(UiText.Resource(R.string.overview_retry))

        assertEquals(R.string.overview_retry, text.message.resourceIdOrNull)
        assertTrue(text.retryable)
        assertNull(text.signedOutCause)
    }
}
