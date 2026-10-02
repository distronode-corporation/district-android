package com.distronode.districtai.ui.scheduling

import androidx.lifecycle.viewmodel.CreationExtras
import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.model.SchedulingEnableResponse
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.resourceIdOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The Scheduling screen's state machine.
 *
 * ⛔ THE SEVEN THINGS THIS SCREEN MUST NEVER DO. It must not render "we could not read the status"
 * as "this workspace has no booking page". It must not spend a second of five hourly provisions on
 * a double tap. It must not throw away the sentence a refused provision came back with. It must not
 * park either single-use hand-off URL in state. It must not stop offering a hand-off while a
 * provision is settling, or while the OTHER hand-off is in flight. It must not echo the dashboard
 * route's `rate_limited` machine token at a person. And once a region has answered 410, it must not
 * keep offering a console button whose only possible outcome is that same refusal again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val readyTenant = SchedulingTenant(
        status = "ready",
        publicHost = "acme-book.distronode.com",
        region = "us",
        lastReadyAt = "2026-09-06T11:20:00.000Z",
        hasCredentials = true,
        bookingUrl = "https://acme-book.distronode.com/book/phone-consultation",
    )

    private fun api(block: FakeDistrictApi.() -> Unit = {}) = FakeDistrictApi().apply(block)

    /**
     * ⚠️ `ApiEnvironment.baseUrl` RATHER THAN A LITERAL, exactly as the production graph wires it.
     * The repository verifies the dashboard hand-off url against this origin, so a fixture host
     * here would exercise the check against a value nothing else in the build agrees with.
     */
    private fun repository(api: FakeDistrictApi) =
        SchedulingRepository(api, ApiEnvironment.baseUrl)

    private fun model(api: FakeDistrictApi) = SchedulingViewModel(repository(api), "ws-1")

    // ── The read ─────────────────────────────────────────────────────────────

    @Test
    fun `the first read lands as Ready and asks for this workspace`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }

        val viewModel = model(api)
        advanceUntilIdle()

        assertEquals(listOf("ws-1"), api.schedulingApi.schedulingStatusRequests)
        val screen = viewModel.state.value.screen as SchedulingScreenState.Ready
        assertEquals(readyTenant, screen.status.tenant)
    }

    @Test
    fun `a legacy workspace is Ready with a null tenant, not a Failed screen`() = runTest {
        // ⛔ THE CONFLATION THE WHOLE SURFACE GUARDS AGAINST. `tenant == null` is the ordinary state
        // of every workspace before anybody presses Enable; rendering it as a failure would tell an
        // operator something is broken when nothing is.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = null),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        val screen = viewModel.state.value.screen as SchedulingScreenState.Ready
        assertNull(screen.status.tenant)
    }

    @Test
    fun `a failed read is its own state and keeps the failure's wording`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.RegionsDegraded("degraded", listOf("eu"))
        }
        val viewModel = model(api)
        advanceUntilIdle()

        // ⛔ NEVER AN EMPTY CARD. A degraded region means the read did not complete, and this is the
        // one case where "there is nothing here" would read as the customer's booking page having
        // been deleted.
        val screen = viewModel.state.value.screen as SchedulingScreenState.Failed
        assertEquals(listOf("eu"), screen.failure.degradedRegions)
    }

    @Test
    fun `a refreshing read keeps the card on screen while it is in flight`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.load(refreshing = true)

        // ⚠️ BEFORE the dispatcher runs the new read: the previous answer is still drawn. Blanking
        // it would flash the one card this screen has, exactly when somebody is watching to see
        // whether their booking page came up.
        assertTrue(viewModel.state.value.screen is SchedulingScreenState.Ready)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.screen is SchedulingScreenState.Ready)
    }

    @Test
    fun `a non-refreshing reload blanks to Loading first`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.load()

        assertEquals(SchedulingScreenState.Loading, viewModel.state.value.screen)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.screen is SchedulingScreenState.Ready)
    }

    // ── The provision ────────────────────────────────────────────────────────

    @Test
    fun `a successful enable clears the notice and re-reads the status`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true),
            )
            schedulingApi.schedulingEnableResult = ApiResult.Success(
                SchedulingEnableResponse(ok = true, status = "ready", publicHost = "acme-book.x"),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.enable()
        advanceUntilIdle()

        // ⛔ THE RE-READ IS UNCONDITIONAL. The 202 says the provision has already run and the ROW is
        // the truth; trusting the POST's own view would leave the card describing a state that no
        // longer exists.
        assertEquals(listOf("ws-1", "ws-1"), api.schedulingApi.schedulingStatusRequests)
        assertNull(viewModel.state.value.notice)
        assertFalse(viewModel.state.value.busy)
    }

    @Test
    fun `ok false keeps the server's sentence rather than inventing one`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true),
            )
            schedulingApi.schedulingEnableResult = ApiResult.Success(
                SchedulingEnableResponse(
                    ok = false,
                    status = "error",
                    error = "cloudflare refused the dns record (HTTP 403)",
                ),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.enable()
        advanceUntilIdle()

        // ⛔ THE PROVISIONER'S OWN CLASSIFIED TEXT, carried as a Literal because it is not ours to
        // translate and it is more specific than anything this client could infer.
        assertEquals(
            "cloudflare refused the dns record (HTTP 403)",
            viewModel.state.value.notice?.literalOrNull,
        )
    }

    @Test
    fun `ok false with no reason falls back to our own sentence`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true),
            )
            schedulingApi.schedulingEnableResult = ApiResult.Success(
                SchedulingEnableResponse(ok = false, status = "error", error = null),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.enable()
        advanceUntilIdle()

        // ⚠️ THE FALLBACK EXISTS ONLY FOR A SERVER THAT SENT NEITHER. It says so out loud rather
        // than showing a blank notice, which would read as a refusal with no cause at all.
        assertEquals(
            R.string.scheduling_enable_failed_fallback,
            viewModel.state.value.notice?.resourceIdOrNull,
        )
    }

    @Test
    fun `a 403 says the workspace is not admitted, not that the user lacks permission`() = runTest {
        // ⛔ THE ALLOWLIST, NOT THE ROLE. The shared failure mapping's generic permission wording
        // would send an owner looking for a colleague to ask — and there is no colleague who can
        // grant this, because the check is about the WORKSPACE.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true),
            )
            schedulingApi.schedulingEnableResult = ApiResult.Forbidden("Scheduling is not enabled")
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.enable()
        advanceUntilIdle()

        assertEquals(
            R.string.scheduling_not_eligible,
            viewModel.state.value.notice?.resourceIdOrNull,
        )
    }

    @Test
    fun `a 429 gets the wait-an-hour sentence rather than the generic one`() = runTest {
        // ⚠️ FIVE PRESSES AN HOUR, SHARED BY THE WHOLE WORKSPACE. It is the one failure on this
        // screen that genuinely resolves by waiting, and the shared mapping would just echo the
        // server's own rate-limit copy without saying that it is per workspace.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true),
            )
            schedulingApi.schedulingEnableResult = ApiResult.RateLimited("slow down")
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.enable()
        advanceUntilIdle()

        assertEquals(
            R.string.scheduling_too_many_attempts,
            viewModel.state.value.notice?.resourceIdOrNull,
        )
    }

    @Test
    fun `any other enable failure falls to the shared mapping`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true),
            )
            schedulingApi.schedulingEnableResult = ApiResult.NetworkFailure(java.io.IOException("down"))
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.enable()
        advanceUntilIdle()

        assertEquals(R.string.failure_offline, viewModel.state.value.notice?.resourceIdOrNull)
    }

    @Test
    fun `a second press while one is in flight is DROPPED, not queued`() = runTest {
        // ⛔ ONE OF FIVE HOURLY PROVISIONS PER PRESS, AND EACH REACHES TWO THIRD PARTIES. A queued
        // second press would spend another one for an answer already on its way.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true),
            )
            schedulingApi.schedulingEnableResult = ApiResult.Success(
                SchedulingEnableResponse(ok = true, status = "ready"),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.enable()
        viewModel.enable()
        advanceUntilIdle()

        assertEquals(1, api.schedulingApi.schedulingEnables.size)
    }

    // ── The dashboard hand-off (the PRIMARY action) ──────────────────────────

    @Test
    fun `the primary action mints a dashboard hand-off and delivers it to the caller`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        val delivered = mutableListOf<String>()
        viewModel.openDashboard { delivered.add(it) }
        advanceUntilIdle()

        // ⛔ THE DASHBOARD ROUTE, NOT THE SSO ONE. The scheduler console is switched off region by
        // region and a shipped Play build cannot be reverted, so the primary action has to be the
        // hand-off that survives a flip.
        assertEquals(1, api.schedulingApi.schedulingHandOffs.size)
        assertEquals(emptyList<String>(), api.schedulingApi.schedulingSsoRequests)
        assertEquals(
            listOf("https://www.distronode.com/dashboard/handoff?code=stub&next=%2Fdashboard"),
            delivered,
        )
        // ⛔ AND NOTHING ABOUT IT SURVIVES ON THE STATE. This code redeems into a SESSION COOKIE,
        // so parking it would leave a live credential in memory long after the browser closed.
        assertFalse(viewModel.state.value.openingDashboard)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun `a 403 on the hand-off says the SESSION ended, not that the workspace is unadmitted`() =
        runTest {
            // ⛔ THE BEARER, NOT THE ALLOWLIST, WHICH IS THE OPPOSITE OF THE SAME STATUS ON THE
            // ENABLE ROUTE. This route answers 403 for a missing, unverifiable or mismatched token,
            // with a bare `{"error":"Forbidden"}`. Telling somebody their workspace is
            // not admitted when their session has lapsed sends them looking for a colleague who
            // cannot help.
            val api = api {
                schedulingApi.schedulingStatusResult = ApiResult.Success(
                    SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
                )
                schedulingApi.schedulingHandOffResult = ApiResult.Forbidden("Forbidden")
            }
            val viewModel = model(api)
            advanceUntilIdle()

            viewModel.openDashboard { true }
            advanceUntilIdle()

            assertEquals(
                R.string.failure_signed_out,
                viewModel.state.value.notice?.resourceIdOrNull,
            )
        }

    @Test
    fun `a 429 on the hand-off never echoes the server's machine token`() = runTest {
        // ⛔ THE ONE PLACE THE SHARED MAPPING WOULD PRINT snake_case ON A CUSTOMER'S PHONE. This
        // route's 429 body is `{"error":"rate_limited","message":"…"}` — the human half lives in
        // `message`, which `ApiErrorEnvelope` does not model — so `RateLimited.message` carries
        // `rate_limited` and `toFailureText` renders it verbatim.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingHandOffResult = ApiResult.RateLimited("rate_limited")
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openDashboard { true }
        advanceUntilIdle()

        // ⚠️ AND IT IS ITS OWN SENTENCE RATHER THAN `scheduling_too_many_attempts`, which is about
        // five PROVISIONS an hour for the workspace. Nothing was provisioned here.
        assertEquals(
            R.string.scheduling_handoff_rate_limited,
            viewModel.state.value.notice?.resourceIdOrNull,
        )
        assertNull("the machine token must not reach the screen", viewModel.state.value.notice?.literalOrNull)
    }

    @Test
    fun `any other hand-off failure falls to the shared mapping`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingHandOffResult =
                ApiResult.NetworkFailure(java.io.IOException("down"))
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openDashboard { true }
        advanceUntilIdle()

        assertEquals(R.string.failure_offline, viewModel.state.value.notice?.resourceIdOrNull)
    }

    @Test
    fun `a second primary press while one is in flight is dropped`() = runTest {
        // ⚠️ TEN A MINUTE FOR THE ACCOUNT, and each press writes a row and spends a slot. A double
        // tap would burn one for a code that is never redeemed.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openDashboard { true }
        viewModel.openDashboard { true }
        advanceUntilIdle()

        assertEquals(1, api.schedulingApi.schedulingHandOffs.size)
    }

    @Test
    fun `the two hand-offs do not disable each other`() = runTest {
        // ⚠️ THREE FLAGS, NOT ONE. Each hand-off mints its own credential at a different server and
        // enabling is a third budget again; none may gate another.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openDashboard { true }
        assertTrue(viewModel.state.value.openingDashboard)
        viewModel.openScheduler { true }
        assertTrue("the console hand-off started anyway", viewModel.state.value.openingConsole)
        advanceUntilIdle()

        assertEquals(1, api.schedulingApi.schedulingHandOffs.size)
        assertEquals(1, api.schedulingApi.schedulingSsoRequests.size)
    }

    @Test
    fun `a hand-off no browser took says so in the notice, on either action`() = runTest {
        // ⛔ IN STATE, NOT THROUGH THE CALL SITE'S SNACKBAR. The launcher is held across the mint and
        // may only capture the application context, so "no browser" cannot be shown by it; the
        // notice is rendered by whichever Activity exists when the mint lands.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openDashboard { false }
        advanceUntilIdle()
        assertEquals(R.string.settings_browser_missing, viewModel.state.value.notice?.resourceIdOrNull)
        assertFalse(viewModel.state.value.openingDashboard)

        viewModel.dismissNotice()
        viewModel.openScheduler { false }
        advanceUntilIdle()
        assertEquals(R.string.settings_browser_missing, viewModel.state.value.notice?.resourceIdOrNull)
        assertFalse(viewModel.state.value.openingConsole)
    }

    @Test
    fun `a launch that worked leaves the other hand-off's failure on screen`() = runTest {
        // ⚠️ THE SUCCESS PATH NOW WRITES `notice`, SO IT MUST NOT WIPE ONE IT DID NOT CAUSE. The
        // console hand-off fails while the dashboard mint is still in flight; its sentence is still
        // true when the dashboard opens a moment later.
        val gate = CompletableDeferred<Unit>()
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingSsoResult = ApiResult.HttpFailure(status = 503, message = "no secret")
            schedulingApi.schedulingHandOffGate = gate
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openDashboard { true }
        advanceUntilIdle()
        viewModel.openScheduler { true }
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.openingDashboard)
        assertEquals(R.string.failure_server, viewModel.state.value.notice?.resourceIdOrNull)
    }

    // ── The scheduler console hand-off (the SECONDARY action) ────────────────

    @Test
    fun `a successful CONSOLE hand-off is delivered to the caller and never kept in state`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        val delivered = mutableListOf<String>()
        viewModel.openScheduler { delivered.add(it) }
        advanceUntilIdle()

        assertEquals(
            listOf("https://acme-book.distronode.com/v1/auth/sso?token=stub"),
            delivered,
        )
        // ⛔ AND NOTHING ABOUT IT SURVIVES ON THE STATE. It carries a 60-second single-use token, so
        // parking it would leave a live credential in memory long after the browser closed and make
        // a second press cheap to serve from a value that is already spent.
        assertFalse(viewModel.state.value.openingConsole)
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun `a 409 hand-off says the tenancy is not ready, which is not a fault`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingSsoResult = ApiResult.HttpFailure(status = 409, message = "not ready")
        }
        val viewModel = model(api)
        advanceUntilIdle()

        val delivered = mutableListOf<String>()
        viewModel.openScheduler { delivered.add(it) }
        advanceUntilIdle()

        assertEquals(emptyList<String>(), delivered)
        assertEquals(
            R.string.scheduling_not_ready_yet,
            viewModel.state.value.notice?.resourceIdOrNull,
        )
    }

    @Test
    fun `any other CONSOLE hand-off failure falls to the shared mapping`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingSsoResult = ApiResult.HttpFailure(status = 503, message = "no secret")
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openScheduler { true }
        advanceUntilIdle()

        // ⚠️ A 5xx BODY IS NOT SHOWABLE — the shared mapper substitutes its own sentence rather
        // than echoing a server error into a customer's face.
        assertEquals(R.string.failure_server, viewModel.state.value.notice?.resourceIdOrNull)
    }

    @Test
    fun `a second open while one is in flight is dropped`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openScheduler { true }
        viewModel.openScheduler { true }
        advanceUntilIdle()

        // ⚠️ EACH PRESS MINTS A TOKEN AND CLAIMS A NONCE AT THE FAR END, so a double tap would burn
        // one for nothing.
        assertEquals(1, api.schedulingApi.schedulingSsoRequests.size)
    }

    @Test
    fun `opening stays available while a provision is settling`() = runTest {
        // ⚠️ THE TWO FLAGS ARE SEPARATE ON PURPOSE. Enabling is the screen's only write; opening is
        // a read that spends a single-use token. One must not disable the other.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingEnableResult = ApiResult.Success(
                SchedulingEnableResponse(ok = true, status = "ready"),
            )
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.enable()
        assertTrue("the provision is in flight", viewModel.state.value.busy)

        viewModel.openScheduler { true }
        assertTrue("and the hand-off started anyway", viewModel.state.value.openingConsole)
        advanceUntilIdle()

        assertEquals(1, api.schedulingApi.schedulingSsoRequests.size)
    }

    // ── Phase A: the console is retired ──────────────────────────────────────

    private fun consoleRetired(message: String = CONSOLE_RETIRED_SENTENCE) = ApiResult.HttpFailure(
        status = 410,
        message = message,
        code = "scheduler_console_retired",
    )

    @Test
    fun `a 410 shows the server's sentence VERBATIM and withdraws the console button`() = runTest {
        // ⛔ THE SENTENCE IS PRODUCT COPY AND IS NOT OURS TO REWRITE. It is deliberately
        // hostname-free and fixed so a client can pin it, and it already points the operator at the
        // primary action. Substituting our own would put a second, drifting copy of a product
        // decision in an app that ships on its own release schedule.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingSsoResult = consoleRetired()
        }
        val viewModel = model(api)
        advanceUntilIdle()

        val delivered = mutableListOf<String>()
        viewModel.openScheduler { delivered.add(it) }
        advanceUntilIdle()

        assertEquals(emptyList<String>(), delivered)
        assertEquals(CONSOLE_RETIRED_SENTENCE, viewModel.state.value.notice?.literalOrNull)
        // ⛔ AND THE CARD IS UNTOUCHED. A refusal must never blank a read the operator is looking
        // at, and it must never become a `Failed` screen — that is the one presentation that offers
        // Retry, and retrying this cannot work.
        assertTrue(viewModel.state.value.screen is SchedulingScreenState.Ready)
        assertTrue("the button that can only fail again is gone", viewModel.state.value.consoleRetired)
    }

    @Test
    fun `the retirement latch survives an ordinary failure that follows it`() = runTest {
        // ⛔ `|| `, NOT `=`. A network blip after the console was retired must not bring the dead
        // button back — the flip is a property of the REGION and does not un-happen because the
        // next request timed out.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingSsoResult = consoleRetired()
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openScheduler { true }
        advanceUntilIdle()
        api.schedulingApi.schedulingSsoResult = ApiResult.NetworkFailure(java.io.IOException("down"))
        viewModel.openScheduler { true }
        advanceUntilIdle()

        assertTrue(viewModel.state.value.consoleRetired)
        assertEquals(R.string.failure_offline, viewModel.state.value.notice?.resourceIdOrNull)
    }

    @Test
    fun `a 410 WITHOUT the code is not a retirement, and an edge 410 carries none`() = runTest {
        // ⛔ THE STATUS ALONE IS NOT THE SIGNAL. A 410 from an edge proxy means nothing of the kind
        // and carries no `code`; treating it as a retirement would withdraw a working button and
        // show whatever prose the proxy happened to emit as though it were product copy.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingSsoResult =
                ApiResult.HttpFailure(status = 410, message = "Gone", code = null)
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openScheduler { true }
        advanceUntilIdle()

        assertFalse(viewModel.state.value.consoleRetired)
        // ⚠️ It falls to the shared 4xx mapping, which shows the server's own wording — correct for
        // an ordinary client error and not a claim about the console.
        assertEquals("Gone", viewModel.state.value.notice?.literalOrNull)
    }

    @Test
    fun `the retirement never touches the PRIMARY hand-off`() = runTest {
        // ⚠️ THE WHOLE POINT OF SHIPPING BOTH. The console being gone is exactly the condition in
        // which the dashboard hand-off has to keep working, so a latch that gated both would turn
        // a planned migration into an outage on every installed phone.
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true, tenant = readyTenant),
            )
            schedulingApi.schedulingSsoResult = consoleRetired()
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.openScheduler { true }
        advanceUntilIdle()

        val delivered = mutableListOf<String>()
        viewModel.openDashboard { delivered.add(it) }
        advanceUntilIdle()

        assertEquals(1, delivered.size)
        assertTrue(viewModel.state.value.consoleRetired)
    }

    @Test
    fun `dismissing clears the notice without re-reading`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true),
            )
            schedulingApi.schedulingEnableResult = ApiResult.Forbidden("nope")
        }
        val viewModel = model(api)
        advanceUntilIdle()

        viewModel.enable()
        advanceUntilIdle()
        val readsBefore = api.schedulingApi.schedulingStatusRequests.size

        viewModel.dismissNotice()

        assertNull(viewModel.state.value.notice)
        assertEquals(readsBefore, api.schedulingApi.schedulingStatusRequests.size)
    }

    @Test
    fun `the factory builds a model that reads on construction`() = runTest {
        val api = api {
            schedulingApi.schedulingStatusResult = ApiResult.Success(
                SchedulingStatusResponse(eligible = true, canManage = true),
            )
        }

        val viewModel = SchedulingViewModel
            .factory(repository(api), "ws-2")
            .create(SchedulingViewModel::class.java, CreationExtras.Empty)
        advanceUntilIdle()

        assertEquals(listOf("ws-2"), api.schedulingApi.schedulingStatusRequests)
        assertTrue(viewModel.state.value.screen is SchedulingScreenState.Ready)
    }

    private companion object {
        /**
         * ⛔ THE WEBSITE'S EXACT SENTENCE. It is fixed and hostname-free precisely so a client can
         * pin it; if this literal ever has to change, the copy changed on the server and this app's
         * job is to keep showing whatever it sends rather than to keep up.
         */
        const val CONSOLE_RETIRED_SENTENCE =
            "Scheduling for this workspace is managed on the web dashboard."
    }
}
