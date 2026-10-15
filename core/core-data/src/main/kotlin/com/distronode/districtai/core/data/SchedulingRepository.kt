package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingEnableResponse
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi
import com.distronode.districtai.core.network.SchedulingEnableRequest
import com.distronode.districtai.core.network.SchedulingHandOffRequest

/**
 * The workspace's booking pages: read the tenancy's state, provision one, and mint a hand-off into
 * a browser — onto the DASHBOARD's scheduling pages by default, and into the scheduler's own
 * `/admin/` console as the fallback that is on its way out.
 *
 * ⛔ TWO HAND-OFFS, AND THE ORDER OF PREFERENCE IS THE POINT. [dashboardHandOff]
 * mints a session for our own dashboard; [schedulerHandOff] signs the operator into the scheduler
 * fork's console, which is being switched off per region behind the fork's `ADMIN_SPA` flag. A
 * shipped app cannot be reverted, so both exist for one release: an installed build keeps working
 * on either side of a flip, and the second is removed once the flip is everywhere.
 *
 * ⛔ NEITHER JSON ROUTE CARRIES A `success` ENVELOPE, SO NEITHER GOES THROUGH [rejectedEnvelope],
 * AND THAT IS A DECISION RATHER THAN AN OMISSION. Almost every district route answers
 * `{success, …}` and its repository checks the flag by hand, because a required field rejects `{}`
 * and does not reject a well-formed `success: false`. These two answer
 * `{eligible, canManage, tenant}` and `{ok, status, publicHost, error}`: there is no flag, so
 * `affirm` would be checking a key the server never sends. What rejects `{}` here instead is that
 * `eligible`, `canManage`, `ok` and `status` carry no DTO default — see
 * [com.distronode.districtai.core.model.SchedulingStatusResponse].
 *
 * ⛔ FOUR OUTCOMES THAT ALL LOOK LIKE "IT DID NOT WORK" AND MUST NOT BE COLLAPSED, which is the
 * whole reason this repository is more than three lines:
 *
 *   - `tenant == null` on a status read is the LEGACY state — no row, nobody has pressed Enable.
 *     Ordinary, and the screen's job is to offer the button (if `eligible` and `canManage`), not
 *     to report a fault;
 *   - `eligible == false` is "this workspace is not admitted to the feature", which is also not a
 *     fault and is a different sentence from the one above;
 *   - `ok: false` on an enable is a SUCCESSFUL 202 carrying an operator-facing sentence in
 *     `error`. The provision ran and refused or failed; the sentence is the product, and mapping
 *     this onto a failure would discard it;
 *   - a **403** or a **429** is a real [ApiResult.Failure] and passes straight through. 403 means
 *     this workspace is not on the scheduling allowlist (a different check from the role gate, and
 *     one an owner can also fail); 429 means five enables in an hour for this workspace.
 *
 * ⛔ AND NONE OF THEM IS "THERE IS NOTHING HERE". "We could not look" rendered as "there is
 * nothing" reads to a user as account loss, and can route a paying customer to a checkout page.
 * On this surface the equivalent mistake
 * is telling somebody their booking page does not exist because a request timed out. Every read
 * below returns the failure intact so the UI's shared `FailureText` mapping can word it.
 *
 * ⛔ NOTHING HERE POLLS, AND NOTHING HERE MAY LEARN TO. [enable] reaches two third parties per
 * call — a tenancy at the scheduler and a DNS record at Cloudflare — and its 5/hour limiter fails
 * open, so a retry loop is somebody else's API quota and a zone full of records. A caller that
 * wants to know how a provision ended re-reads [status] on a human action, which is what the 202
 * is telling it to do.
 *
 * ⛔ AND THAT GOES FOR [status] TOO: THIS CLIENT HAS NO POLL AT ALL (unlike the iOS client's
 * scheduling screen). There is no timer here, and `SchedulingUiState.offersRefresh` records the
 * decision not to add one (a timer would have to be tied to BOTH screen visibility and process
 * foregroundedness, and getting either wrong leaves a backgrounded phone polling on a mobile
 * connection). Refresh is a button, offered in exactly the two states where a re-read could change
 * the answer.
 */
class SchedulingRepository(
    private val api: DistrictApi,
    /**
     * The website origin this build talks to — scheme and host, no trailing slash, exactly as
     * `ApiEnvironment.baseUrl` spells it.
     *
     * ⛔ A CONSTRUCTOR PARAMETER WITH NO DEFAULT, DELIBERATELY. A default would make this a check
     * that still passes when it is misconfigured, which is the failure mode the whole gate exists
     * to remove — and it would silently disagree with `BuildConfig.API_BASE_URL` the day a build
     * type points somewhere else. The app module owns the one origin; this module is handed it.
     */
    private val webOrigin: String,
) {

    /**
     * Read the tenancy's state.
     *
     * ⚠️ SUCCEEDS FOR A `viewer` TOO, with `canManage: false`. The card is readable by every role;
     * only the button is gated, and the server is what says so.
     */
    suspend fun status(workspaceId: String): ApiResult<SchedulingStatusResponse> =
        api.schedulingStatus(workspaceId)

    /**
     * Provision the tenancy, once, on an explicit press.
     *
     * ⛔ THE 202 IS A SUCCESS AND THE CLIENT TREATS IT AS ONE. `DistrictApiClient` accepts every
     * 2xx (`Response.isSuccessful` is 200..299, not `== 200`), so this needs no special case —
     * worth saying out loud because the natural mistake is a client that only accepts 200 and
     * therefore reports every successful enable as a failure, with the tenancy provisioned and the
     * screen claiming otherwise. A test drives a 202 stub for exactly that reason.
     *
     * ⛔ AND `ok: false` COMES BACK AS A SUCCESS. See the type's ⛔: the refusal sentence lives in
     * [SchedulingEnableResponse.error] and the caller decides what to say. Only 403 and 429 arrive
     * as failures.
     */
    suspend fun enable(workspaceId: String): ApiResult<SchedulingEnableResponse> =
        api.enableScheduling(SchedulingEnableRequest(workspaceId = workspaceId))

    /**
     * Ask for a scheduler hand-off and report where it points, without going there.
     *
     * ⛔ THE URL IS ANSWERED, NEVER STORED, AND NEVER LOGGED. It carries a 60-second single-use JWT
     * in its query string, so holding one would leave a live credential in memory long after the
     * browser closed and would make a second press cheap to serve from a value that is already
     * spent. Every diagnostic below is written from the STATUS and never from the header.
     *
     * ⛔ HTTPS ONLY, AND THE CHECK LIVES IN `DistrictApiClient.redirectTarget`, NOT HERE. The route
     * builds `https://<publicHost>/v1/auth/sso`, so anything else is contract drift, and a hand-off
     * that is not TLS would put a live token on the wire in clear. The client refuses a non-https
     * or unparseable `Location` as [ApiResult.DecodeFailure] for every redirect route at once, so
     * a second copy here could only drift from it.
     *
     * ⚠️ A **409** REACHES THE CALLER UNCHANGED. It means the tenancy is not `ready`, which is the
     * honest state of a workspace mid-provision, and the screen words it as such rather than as a
     * fault the user would try to fix.
     */
    suspend fun schedulerHandOff(workspaceId: String): ApiResult<String> =
        api.schedulingSsoTarget(workspaceId, SCHEDULER_ADMIN_PATH)

    /**
     * Ask for a hand-off that lands on a calendar provider's consent screen, for one Connect press.
     *
     * ⛔ THE SAME SSO ROUTE AS [schedulerHandOff] WITH A DIFFERENT `next`, AND IT IS NOT AN ADMIN
     * OP. The consent screen is the provider's, so the round trip has to happen in a browser; the
     * catalog has no `calendar.connect`, and `GET /v1/calendar/connect` lives on the scheduler behind
     * its own session. This is how the web's calendar Connect link and district-ios's
     * `SchedulingCalendarConnectModel` spend it. ⚠️ The route's 410 for a retired console does not
     * apply: this `next` is not under `/admin`.
     *
     * ⛔ THE URL IS ANSWERED, NEVER STORED, NEVER LOGGED, for the reasons on [schedulerHandOff], and
     * a 409 reaches the caller unchanged for the same reason.
     *
     * @param provider `google` or `microsoft`, the fork's own provider keys.
     */
    suspend fun calendarConnectHandOff(workspaceId: String, provider: String): ApiResult<String> =
        api.schedulingSsoTarget(workspaceId, calendarConnectNext(provider, webOrigin))

    /**
     * Ask for a signed-in browser onto the dashboard's own scheduling pages.
     *
     * ⛔ THIS IS THE PRIMARY HAND-OFF AND [schedulerHandOff] IS NOW THE FALLBACK. The scheduler's
     * `/admin/` console is being switched off region by region; the dashboard pages are not, and
     * they are cookie-only, so a Custom Tab pointed at them without this call lands on a login
     * screen. Both ship for one release so an installed build keeps working either side of the
     * flip.
     *
     * ⛔ THE URL IS ANSWERED, NEVER STORED, NEVER LOGGED — the same rule as [schedulerHandOff] and
     * for a sharper reason: this one carries a 43-character code that mints a SESSION COOKIE, not
     * merely a sign-in to a third-party console. Sixty seconds, one redemption. It is minted at the
     * moment of the tap rather than in advance, because a code held for later is a code that has
     * already expired.
     *
     * ⛔ AND IT IS VERIFIED AGAINST THIS BUILD'S OWN ORIGIN, WHICH IS STRICTLY MORE THAN THE `https`
     * TEST [schedulerHandOff] GETS FROM `redirectTarget`. That one guards a URL built from a
     * per-workspace scheduler host this client cannot know in advance, so the scheme is all there
     * is to check. ⚠️ And this one is a JSON body read through `send`, which never passes through
     * `redirectTarget`, so its scheme check below is its own and is not redundant. Here the server
     * builds the URL from the request's own host, so the answer is knowable — and a URL that is not
     * on it is either drift or a redirect somewhere else, and it would be carrying a live session
     * credential when it went.
     *
     * @param nonce the value the browser's leg 1 handed back on `districtai://handoff`, which binds
     *   the code to that browser's cookie. ⛔ NON-NULL, AND THAT IS THE POINT (security item S33):
     *   there is no unbound mint to fall back to, so the server can require the binding. ⚠️ Never
     *   logged: the request type's `toString` redacts it.
     */
    suspend fun dashboardHandOff(workspaceId: String, nonce: String): ApiResult<String> =
        when (
            val result = api.schedulingHandOff(
                SchedulingHandOffRequest(
                    workspaceId = workspaceId,
                    next = DASHBOARD_SCHEDULING_PATH,
                    nonce = nonce,
                ),
            )
        ) {
            is ApiResult.Success -> verifiedDashboardTarget(result.value.url)
            is ApiResult.Failure -> result
        }

    /**
     * The URL of the hand-off's leg 1 for [state], to open in the browser that will redeem the code.
     *
     * ⚠️ [state] IS INTERPOLATED UNENCODED, AND THAT IS SAFE ONLY BECAUSE OF ITS ALPHABET. The route
     * accepts 16 to 256 characters of `[A-Za-z0-9._~-]`, which are exactly RFC 3986's unreserved
     * characters, so there is nothing to encode; the caller generates it as base64url, which is a
     * subset. A state from anywhere else would need encoding here first.
     */
    fun handOffStartUrl(state: String): String =
        "${webOrigin.trimEnd('/')}$HANDOFF_START_PATH?state=$state"

    /**
     * ⛔ THE TRAILING SLASH IN THE EXPECTED PREFIX IS LOAD-BEARING AND IS THE WHOLE REASON THIS IS
     * NOT ONE LINE. `startsWith("https://www.distronode.com")` accepts
     * `https://www.distronode.com.evil.test/…` — a host an attacker controls, handed a Custom Tab
     * with a live single-use code in its query string. Requiring the separator is what makes the
     * test a HOST test rather than a string test.
     *
     * ⚠️ AND THE `https` CHECK IS KEPT ALONGSIDE IT RATHER THAN BEING IMPLIED BY THE ORIGIN. It is
     * not redundant: it is the one assertion that does not depend on [webOrigin] being right, so a
     * build configured with a plaintext origin still cannot put this credential on the wire in
     * clear.
     */
    private fun verifiedDashboardTarget(url: String): ApiResult<String> {
        val expectedPrefix = webOrigin.trimEnd('/') + "/"
        return if (
            url.startsWith(HTTPS_PREFIX, ignoreCase = true) &&
            url.startsWith(expectedPrefix, ignoreCase = true)
        ) {
            ApiResult.Success(url)
        } else {
            ApiResult.DecodeFailure(
                cause = IllegalStateException("the dashboard hand-off was not on this app's origin"),
                // ⛔ NO PREVIEW OF THE VALUE, AND NOT OF THE EXPECTED ORIGIN EITHER. The refused
                // URL carries a live code; quoting it would put that credential wherever the
                // diagnostic goes. The only reportable fact is the shape.
                bodyPreview = "SchedulingHandOff{origin!=configured}",
            )
        }
    }

    private companion object {
        /**
         * Where the hand-off lands inside the scheduler.
         *
         * ⚠️ SENT AS `next`, WHICH THE ROUTE VALIDATES AND MAY DROP. It refuses a
         * protocol-relative value, a backslash, a scheme and any control character rather than
         * sanitising one — so a value it rejects costs the operator the deep link and nothing
         * else: the far end still signs them in and shows its default page.
         */
        const val SCHEDULER_ADMIN_PATH = "/admin/"

        /**
         * Where the dashboard hand-off lands.
         *
         * ⚠️ SENT AS `next` AND VALIDATED SERVER-SIDE, like [SCHEDULER_ADMIN_PATH] — anything that
         * is not a same-origin path under `/dashboard` silently becomes this exact value anyway, so
         * a refusal costs nothing. It is sent explicitly so the intent is on the wire rather than
         * being inherited from a server-side default that could move.
         */
        const val DASHBOARD_SCHEDULING_PATH = "/dashboard/district/scheduling"

        const val HTTPS_PREFIX = "https://"

        /**
         * ⚠️ ON THE WEBSITE ORIGIN, OUTSIDE `/api`, because it is a browser route: the cookie it
         * sets is host-only, so it must be the host the code is later redeemed on.
         */
        const val HANDOFF_START_PATH = "/dashboard/handoff/start"
    }
}

/**
 * The `next` a calendar Connect asks the SSO route for, with `return_to` already encoded.
 *
 * ⛔ THE INNER ENCODE IS THE LOAD-BEARING ONE. The route's `safeReturnPath` refuses any `next` that
 * carries a literal `://`, and `return_to` is an ABSOLUTE URL, so it is encoded here before it goes
 * into `next`, and the network client encodes `next` as a whole query value on top
 * (`addQueryParameter` escapes `%`, `&` and `=`). A single encode reads correctly and has its `next`
 * dropped at the far end, where the only symptom is landing on the scheduler's default page.
 *
 * ⚠️ `return_to` IS THE WEB DASHBOARD'S CALENDAR PAGE, NOT AN APP SCHEME, exactly as district-ios
 * sends it: nothing establishes that the fork accepts a non-http `return_to`. The app re-reads the
 * status when the member comes back instead of being told the outcome.
 */
internal fun calendarConnectNext(provider: String, webOrigin: String): String {
    val returnTo = webOrigin.trimEnd('/') + CALENDAR_RETURN_PATH
    return "/v1/calendar/connect?provider=${encodeComponent(provider)}&return_to=${encodeComponent(returnTo)}"
}

/**
 * RFC 3986 unreserved characters only, every other UTF-8 byte as `%XX`: district-ios's
 * `SchedulingSSOClient.encode`, stricter than `encodeURIComponent` and decoded identically, so no
 * server's parser has a say about `!'()*`.
 */
internal fun encodeComponent(value: String): String = buildString {
    value.toByteArray(Charsets.UTF_8).forEach { byte ->
        val code = byte.toInt() and BYTE_MASK
        val char = code.toChar()
        if (char in UNRESERVED) {
            append(char)
        } else {
            append('%').append(HEX[code shr NIBBLE]).append(HEX[code and LOW_NIBBLE])
        }
    }
}

/** The dashboard page a calendar connection comes back to, district-ios's `calendarPath`. */
private const val CALENDAR_RETURN_PATH = "/dashboard/district/scheduling/calendar"
private val UNRESERVED: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "-._~".toList()).toSet()
private const val HEX = "0123456789ABCDEF"
private const val BYTE_MASK = 0xFF
private const val NIBBLE = 4
private const val LOW_NIBBLE = 0x0F
