package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingEnableResponse
import com.distronode.districtai.core.model.SchedulingHandOffResponse
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * The booking pages' four calls.
 *
 * ⛔ THE FOUR DISTINCTIONS THIS LAYER EXISTS TO KEEP, none of which is "it failed":
 *   1. `tenant == null` on an ELIGIBLE workspace is "nobody has pressed Enable yet" — the ordinary
 *      state of every workspace — and must reach the screen as a success carrying a null;
 *   2. `eligible == false` is "this workspace is not admitted to the feature", a different
 *      sentence and a different screen from the one above;
 *   3. `ok: false` inside a 202 is a SUCCESSFUL response carrying the one sentence that says what
 *      went wrong. Promoting it to a failure would discard that sentence;
 *   4. a 403 or a 429 is a real failure and passes through untouched, because each needs its own
 *      wording (the allowlist, and five presses an hour shared by the whole workspace).
 *
 * ⛔ AND THE DASHBOARD HAND-OFF IS THE CALL THIS LAYER ACTUALLY GUARDS. Everything else here is a
 * pass-through. `dashboardHandOff` refuses a url that is not https AND not on this build's own
 * website origin, because it is about to be handed to a browser carrying a live single-use
 * credential in a query string.
 *
 * ⛔ THE CONSOLE HAND-OFF'S GUARD IS IN THE NETWORK CLIENT, AND THE DIFFERENCE IS WHAT IS KNOWABLE.
 * It lands on a per-workspace scheduler host this client cannot predict, so the scheme is all
 * there is to check, and `DistrictApiClient.redirectTarget` checks it for every redirect route at
 * once (`RedirectTargetTest`). The dashboard hand-off is built by the server from the request's
 * own host, so the expected answer is known exactly and anything else is drift or a redirection.
 */
class SchedulingRepositoryTest {

    /**
     * ⚠️ THE REAL PRODUCTION ORIGIN RATHER THAN A `https://test.example`, AND IT IS NOT LAZINESS.
     * The dashboard hand-off is verified against this string, and a fixture host would let a test
     * pass while the shipped app refused every real URL — the check would be exercised against a
     * value nothing else in the build agrees with. `BuildConfig.API_BASE_URL` is this exact string
     * for every build type; the app module passes it through `ApiEnvironment.baseUrl`.
     */
    private val webOrigin = "https://www.distronode.com"

    private fun repository(api: FakeDistrictApi) = SchedulingRepository(api, webOrigin)

    private val readyTenant = SchedulingTenant(
        status = "ready",
        publicHost = "acme-book.distronode.com",
        region = "us",
        lastReadyAt = "2026-09-06T11:20:00.000Z",
        hasCredentials = true,
        bookingUrl = "https://acme-book.distronode.com/book/phone-consultation",
    )

    // ── The status read ──────────────────────────────────────────────────────

    @Test
    fun `a ready tenancy is carried through with the workspace it was asked for`() = runTest {
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }

        val result = repository(api).status("ws-1")

        // ⛔ THE WORKSPACE IS REQUIRED ON THE WIRE, unlike the overview's optional one. This client
        // holds no selection cookie, so an omitted id would let the server pick from its own
        // membership listing — and the wrong answer here is somebody else's booking URL.
        assertEquals(listOf("ws-1"), api.schedulingApi.schedulingStatusRequests)
        assertEquals(readyTenant, (result as ApiResult.Success).value.tenant)
    }

    @Test
    fun `a null tenant on an eligible workspace is a SUCCESS, not an absence`() = runTest {
        // ⛔ THE CONFLATION THIS WHOLE LAYER EXISTS TO PREVENT, in its sharpest form on this
        // surface. Rendering "we could not look" as "there is nothing" reads to a user as account
        // loss; here the mirror mistake is treating the
        // ordinary never-provisioned state as a fault and telling somebody their booking page is
        // broken when they have simply never made one.
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = null),
            )
        }

        val result = repository(api).status("ws-1")

        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).value.tenant)
        assertEquals(true, result.value.eligible)
    }

    @Test
    fun `an ineligible workspace is a success too, and keeps canManage separate`() = runTest {
        // ⚠️ TWO INDEPENDENT ANSWERS, WHICH IS WHY BOTH ARE SENT. A workspace can be admitted and
        // unprovisioned, or provisioned and later removed from the allowlist. Collapsing them into
        // one boolean would put a button in front of somebody the enable route answers 403.
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = false, canManage = true),
            )
        }

        val result = repository(api).status("ws-1") as ApiResult.Success

        assertEquals(false, result.value.eligible)
        assertEquals(true, result.value.canManage)
    }

    @Test
    fun `a failed status read stays a failure and is never flattened to an empty card`() = runTest {
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingStatusResult = ApiResult.RegionsDegraded("degraded", listOf("eu"))
        }

        val result = repository(api).status("ws-1")

        // ⛔ ITS OWN CASE ALL THE WAY UP. A degraded region means the read did not complete; a
        // repository that mapped it to a blank status would tell an EU customer their booking page
        // does not exist because Frankfurt did not answer.
        assertTrue(result is ApiResult.RegionsDegraded)
        assertEquals(listOf("eu"), (result as ApiResult.RegionsDegraded).degradedRegions)
    }

    // ── The provision ────────────────────────────────────────────────────────

    @Test
    fun `enable sends the workspace in the body and carries the 202 back`() = runTest {
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingEnableResult = ApiResult.Success(
                SchedulingEnableResponse(
                    ok = true,
                    status = "ready",
                    publicHost = "acme-book.distronode.com",
                ),
            )
        }

        val result = repository(api).enable("ws-1")

        assertEquals(listOf("ws-1"), api.schedulingApi.schedulingEnables.map { it.workspaceId })
        assertEquals(true, (result as ApiResult.Success).value.ok)
    }

    @Test
    fun `ok false inside a 202 is a SUCCESS carrying the reason, not a failure`() = runTest {
        // ⛔ THE SENTENCE IS THE PRODUCT. The provision ran and failed; the server classified why
        // and said so in a well-formed 202. Mapping this onto a transport failure would throw away
        // the only text that tells an operator what happened, and replace it with "something went
        // wrong".
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingEnableResult = ApiResult.Success(
                SchedulingEnableResponse(
                    ok = false,
                    status = "error",
                    publicHost = "acme-book.distronode.com",
                    error = "cloudflare refused the dns record (HTTP 403)",
                ),
            )
        }

        val result = repository(api).enable("ws-1")

        assertTrue("a refusal inside a 202 is still a decoded response", result is ApiResult.Success)
        assertEquals(
            "cloudflare refused the dns record (HTTP 403)",
            (result as ApiResult.Success).value.error,
        )
        // ⚠️ AND THE HOST SURVIVES THE FAILURE, because it is allocated once and reused forever.
        assertEquals("acme-book.distronode.com", result.value.publicHost)
    }

    @Test
    fun `a 403 and a 429 pass through untouched`() = runTest {
        // ⛔ THE TWO REFUSALS THAT NEVER REACHED THE PROVISIONER, and the two that must not be
        // reworded here. A 403 is the ALLOWLIST rather than the role — an owner can fail it — and a
        // 429 is five presses in an hour for the whole workspace, which is the one failure on this
        // surface that genuinely resolves by waiting. The screen words each; this layer must not.
        val forbidden = FakeDistrictApi().apply {
            schedulingApi.schedulingEnableResult = ApiResult.Forbidden("not on the allowlist")
        }
        assertTrue(repository(forbidden).enable("ws-1") is ApiResult.Forbidden)

        val limited = FakeDistrictApi().apply {
            schedulingApi.schedulingEnableResult = ApiResult.RateLimited("slow down")
        }
        assertTrue(repository(limited).enable("ws-1") is ApiResult.RateLimited)
    }

    @Test
    fun `nothing in this layer retries or loops a provision`() = runTest {
        // ⛔ ONE CALL PER PRESS, MEASURED RATHER THAN ASSERTED IN A COMMENT. Each enable creates a
        // tenancy at the scheduler and a DNS record at Cloudflare, and the 5/hour limiter FAILS
        // OPEN — so a retry added here would be somebody else's API quota and a zone full of
        // records, with nothing server-side to stop it.
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingEnableResult = ApiResult.NetworkFailure(java.io.IOException("down"))
        }

        repository(api).enable("ws-1")

        assertEquals(1, api.schedulingApi.schedulingEnables.size)
    }

    // ── The scheduler console hand-off (the SECONDARY path) ──────────────────

    @Test
    fun `the SECONDARY hand-off still pins the admin path and returns the https target`() = runTest {
        val api = FakeDistrictApi()

        val result = repository(api).schedulerHandOff("ws-1")

        // ⛔ STILL `/admin/`, AND THE PIN STAYS EXACTLY BECAUSE THIS PATH IS ON ITS WAY OUT. The
        // fork's `ADMIN_SPA` switch refuses any `next` under `/admin` once a region is flipped, so
        // this literal is what the website's 410 keys on; changing it here would move the client
        // off the one path the server has been taught to refuse politely, and back onto a bare 404
        // in a Custom Tab with nothing on screen to say so. The primary hand-off is
        // `dashboardHandOff`, below.
        //
        // ⚠️ `next` IS SENT AND MAY BE DROPPED BY THE ROUTE, which refuses a protocol-relative
        // value, a backslash, a scheme or a control character rather than sanitising one. A value
        // it rejects costs the deep link and nothing else — the far end still signs the user in.
        assertEquals(listOf("ws-1|/admin/"), api.schedulingApi.schedulingSsoRequests)
        assertEquals(
            "https://acme-book.distronode.com/v1/auth/sso?token=stub",
            (result as ApiResult.Success).value,
        )
    }

    @Test
    fun `the SECONDARY hand-off passes the client's refusal through untouched`() = runTest {
        // ⛔ THE https CHECK MOVED TO `DistrictApiClient.redirectTarget`, which answers a non-https
        // `Location` as this exact failure. The repository must hand it on as it is: re-wrapping it
        // would lose the shape-only preview, which is what keeps the token out of a diagnostic.
        val refused = ApiResult.DecodeFailure(IllegalStateException("not https"), "RedirectTarget{scheme!=https}")
        val api = FakeDistrictApi().apply { schedulingApi.schedulingSsoResult = refused }

        assertSame(refused, repository(api).schedulerHandOff("ws-1"))
    }

    // ── The dashboard hand-off ───────────────────────────────────────────────

    @Test
    fun `the dashboard hand-off asks for the scheduling page and returns the url`() = runTest {
        val api = FakeDistrictApi()

        val result = repository(api).dashboardHandOff("ws-1")

        // ⛔ `next` IS SENT EXPLICITLY RATHER THAN LEFT TO THE ROUTE'S DEFAULT. The route coerces
        // anything that is not a same-origin path under `/dashboard` to this exact value today, so
        // sending it costs nothing and puts the intent on the wire instead of inheriting a
        // server-side default that could move.
        assertEquals(
            listOf("ws-1" to "/dashboard/district/scheduling"),
            api.schedulingApi.schedulingHandOffs.map { it.workspaceId to it.next },
        )
        assertEquals(
            "https://www.distronode.com/dashboard/handoff?code=stub&next=%2Fdashboard",
            (result as ApiResult.Success).value,
        )
    }

    @Test
    fun `a hand-off url on ANOTHER host is refused, prefix-suffix attack included`() = runTest {
        // ⛔ THE ONE THAT A NAIVE `startsWith(origin)` LETS THROUGH, AND THE REASON THE EXPECTED
        // PREFIX CARRIES A TRAILING SLASH. `https://www.distronode.com.evil.test/…` starts with
        // the origin as a STRING and is a completely different host — one an attacker controls,
        // about to be handed a Custom Tab with a live single-use session code in its query string.
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingHandOffResult = ApiResult.Success(
                SchedulingHandOffResponse(
                    url = "https://www.distronode.com.evil.test/dashboard/handoff?code=live",
                    expiresIn = 60,
                ),
            )
        }

        val result = repository(api).dashboardHandOff("ws-1")

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a non-https hand-off url is refused even when the host is right`() = runTest {
        // ⚠️ THE `https` CHECK IS NOT IMPLIED BY THE ORIGIN CHECK. It is the one assertion that
        // does not depend on the configured origin being right, so a build pointed at a plaintext
        // host still cannot put this credential on the wire in clear.
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingHandOffResult = ApiResult.Success(
                SchedulingHandOffResponse(url = "http://www.distronode.com/dashboard/handoff?code=x"),
            )
        }

        assertTrue(repository(api).dashboardHandOff("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `the refusal diagnostic quotes the SHAPE and never the url`() = runTest {
        // ⛔ THE PREVIEW IS WHERE A LIVE CREDENTIAL WOULD LEAK, and a `DecodeFailure` preview is
        // exactly the kind of string that ends up in a crash report. It names the shape and
        // nothing else — not the refused URL, and not the expected origin either.
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingHandOffResult = ApiResult.Success(
                SchedulingHandOffResponse(url = "https://evil.test/dashboard/handoff?code=live-code"),
            )
        }

        val preview = (repository(api).dashboardHandOff("ws-1") as ApiResult.DecodeFailure).bodyPreview

        assertEquals("SchedulingHandOff{origin!=configured}", preview)
        assertTrue("the refused address must not be quoted", !preview.contains("evil.test"))
        assertTrue("nor the code it carried", !preview.contains("live-code"))
    }

    @Test
    fun `a hand-off body that carried no expiry is still a usable hand-off`() = runTest {
        // ⚠️ THE LENIENT HALF OF THE DTO, AT THIS LAYER. `expiresIn` defaults because no committed
        // fixture pins this route, so a server that stopped sending it must degrade to "the app
        // ignores a number it does not read" rather than to a broken screen.
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingHandOffResult = ApiResult.Success(
                SchedulingHandOffResponse(url = "https://www.distronode.com/dashboard/handoff?code=x"),
            )
        }

        val result = repository(api).dashboardHandOff("ws-1")

        assertEquals(
            "https://www.distronode.com/dashboard/handoff?code=x",
            (result as ApiResult.Success).value,
        )
    }

    @Test
    fun `403 and 429 on the dashboard hand-off pass through for the screen to word`() = runTest {
        // ⛔ NEITHER IS REWORDED HERE, AND EACH MEANS SOMETHING DIFFERENT FROM ITS NAMESAKE ON THE
        // ENABLE ROUTE. A 403 here is the BEARER — missing, unverifiable or mismatched — not the
        // allowlist and not the role. A 429 here is ten hand-offs a minute for the ACCOUNT, and its
        // body puts the machine token `rate_limited` in `error`, so the screen must not echo it.
        val forbidden = FakeDistrictApi().apply {
            schedulingApi.schedulingHandOffResult = ApiResult.Forbidden("Forbidden")
        }
        assertTrue(repository(forbidden).dashboardHandOff("ws-1") is ApiResult.Forbidden)

        val limited = FakeDistrictApi().apply {
            schedulingApi.schedulingHandOffResult = ApiResult.RateLimited("rate_limited")
        }
        assertTrue(repository(limited).dashboardHandOff("ws-1") is ApiResult.RateLimited)
    }

    @Test
    fun `nothing in this layer retries a hand-off, because each one mints a credential`() = runTest {
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingHandOffResult =
                ApiResult.NetworkFailure(java.io.IOException("down"))
        }

        repository(api).dashboardHandOff("ws-1")

        assertEquals(1, api.schedulingApi.schedulingHandOffs.size)
    }

    @Test
    fun `a hand-off refused by the server passes through with its status`() = runTest {
        // ⚠️ A 409 IS NOT A FAULT: the route answers it when the tenancy is not `ready`, which is
        // the honest state of a workspace mid-provision. The screen has its own sentence for it, so
        // this layer must keep the status rather than collapsing it into a generic failure.
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingSsoResult = ApiResult.HttpFailure(status = 409, message = "not ready")
        }

        val result = repository(api).schedulerHandOff("ws-1")

        assertEquals(409, (result as ApiResult.HttpFailure).status)
    }

    @Test
    fun `a 410 keeps BOTH the sentence and the code, because the screen needs each`() = runTest {
        // ⛔ THE CONSOLE-RETIRED REFUSAL, AND IT IS THE ONE FAILURE ON THIS SURFACE WHERE THE TWO
        // HALVES OF THE BODY DO DIFFERENT JOBS. `error` is product copy shown to a person VERBATIM
        // — a fixed sentence with no hostname in it, written that way precisely so a client can
        // pin it — and `code` is the branch key. A repository that dropped either would force the
        // screen to choose between branching on English prose and inventing its own sentence.
        val api = FakeDistrictApi().apply {
            schedulingApi.schedulingSsoResult = ApiResult.HttpFailure(
                status = 410,
                message = "Scheduling for this workspace is managed on the web dashboard.",
                code = "scheduler_console_retired",
            )
        }

        val result = repository(api).schedulerHandOff("ws-1") as ApiResult.HttpFailure

        assertEquals(410, result.status)
        assertEquals("scheduler_console_retired", result.code)
        assertEquals(
            "Scheduling for this workspace is managed on the web dashboard.",
            result.message,
        )
    }
}
