package com.distronode.districtai.ui.scheduling

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the booking-page card draws in each of its seven presentations, plus loading and failure.
 *
 * ⛔ EVERY STATE IS RENDERED, NOT JUST THE HAPPY ONE, AND THAT IS THE POINT RATHER THAN THOROUGHNESS
 * FOR ITS OWN SAKE. A composable rendered in a single state exercises none of its `when` arms, so
 * the arms that decide whether an Enable button exists — the ones that could provision a tenancy
 * somebody switched off, or hide the button from an owner — would be untested lines inside a
 * function that reported as covered.
 *
 * ⛔ AND THE FOUR THINGS IT MUST NEVER DRAW: a purchase path for a workspace the feature does not
 * admit, an Enable button for a `disabled` tenancy, a booking link rebuilt from the host, and an
 * empty card where a failed read should be.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT, for the reason the workflows and marketplace screens need one:
// `assertIsDisplayed` checks visible BOUNDS, and on a phone-sized Robolectric display the action
// row of a live tenancy sits below the fold. The assertion then fails with "is not displayed"
// against a node that is perfectly present, which reads as a rendering bug rather than a short
// viewport.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun tenant(
        status: String,
        bookingUrl: String? = null,
        lastError: String? = null,
        lastReadyAt: String? = null,
    ) = SchedulingTenant(
        status = status,
        publicHost = "acme-book.distronode.com",
        region = "us",
        lastReadyAt = lastReadyAt,
        lastError = lastError,
        hasCredentials = status == "ready",
        bookingUrl = bookingUrl,
    )

    /**
     * ⚠️ THE IN-FLIGHT AND RETIREMENT FLAGS ARRIVE THROUGH [transform] RATHER THAN AS THREE MORE
     * PARAMETERS, and that is detekt's `LongParameterList` (threshold 8, and it fires AT the
     * threshold) rather than a preference. A trailing copy also reads better at the call site: the
     * one flag a test cares about is named there instead of being the fifth `false` in a row.
     */
    private fun ready(
        eligible: Boolean = true,
        canManage: Boolean = true,
        tenant: SchedulingTenant? = null,
        busy: Boolean = false,
        notice: UiText? = null,
        transform: (SchedulingUiState) -> SchedulingUiState = { it },
    ) = transform(
        SchedulingUiState(
            screen = SchedulingScreenState.Ready(
                SchedulingStatusResponse(
                    eligible = eligible,
                    canManage = canManage,
                    tenant = tenant,
                ),
            ),
            busy = busy,
            notice = notice,
        ),
    )

    private fun render(
        state: SchedulingUiState,
        onRetry: () -> Unit = {},
        onEnable: () -> Unit = {},
        onOpenDashboard: () -> Unit = {},
        onOpenScheduler: () -> Unit = {},
        onDismissNotice: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                SchedulingScreen(
                    state = state,
                    onBack = {},
                    onRetry = onRetry,
                    onEnable = onEnable,
                    onOpenDashboard = onOpenDashboard,
                    onOpenScheduler = onOpenScheduler,
                    onDismissNotice = onDismissNotice,
                )
            }
        }
    }

    /**
     * ⚠️ RESOLVED FROM THE RESOURCE RATHER THAN RETYPED AS AN ENGLISH LITERAL, which is the
     * opposite of what the devices and workflows screen tests do. Several of these strings are
     * declared across two lines in `strings.xml` and AAPT collapses that whitespace, so a
     * hand-copied literal would differ from the rendered text by an invisible amount — and the
     * failure ("no node with text …") looks like a missing element rather than a whitespace
     * mismatch. The COPY is pinned in the resource file; these tests pin which copy is chosen.
     */
    private fun text(id: Int, arg: String? = null): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return if (arg == null) context.getString(id) else context.getString(id, arg)
    }

    // ── Loading and failure ──────────────────────────────────────────────────

    @Test
    fun `the loading state draws skeletons and no card`() {
        render(SchedulingUiState())

        composeRule.onNodeWithContentDescription(SCHEDULING_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_CARD_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a failed read draws a failure card, never an empty one`() {
        // ⛔ THE WHOLE POINT OF `Failed` BEING ITS OWN CASE. `tenant == null` is a legitimate answer
        // here, so a failure drawn as "there is nothing" would be indistinguishable from a
        // workspace that has simply never enabled scheduling.
        render(
            SchedulingUiState(
                screen = SchedulingScreenState.Failed(
                    FailureText(message = UiText.Literal("the region did not answer")),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(SCHEDULING_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("the region did not answer").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_CARD_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a retryable failure offers retry and an unretryable one does not`() {
        var retries = 0
        render(
            SchedulingUiState(
                screen = SchedulingScreenState.Failed(
                    FailureText(message = UiText.Literal("try later"), retryable = true),
                ),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(SCHEDULING_RETRY_DESCRIPTION).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `an unretryable failure offers no button that cannot work`() {
        render(
            SchedulingUiState(
                screen = SchedulingScreenState.Failed(
                    FailureText(message = UiText.Literal("you cannot see this"), retryable = false),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(SCHEDULING_RETRY_DESCRIPTION).assertDoesNotExist()
    }

    // ── The two "no tenancy row" states ──────────────────────────────────────

    @Test
    fun `an ineligible workspace gets one sentence and NO control at all`() {
        // ⛔ NO PURCHASE PATH, NO "CONTACT SALES", NO BADGE. Play's Payments policy is the same
        // shape as the App Store's 3.1.3(b) here, and a link out of a paid product's own settings
        // is that offer wearing a different hat.
        render(ready(eligible = false))

        composeRule.onNodeWithText(text(R.string.scheduling_not_eligible)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_REFRESH_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_BADGE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_HOST_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a legacy workspace offers Enable to somebody who may manage it`() {
        var enables = 0
        render(ready(), onEnable = { enables += 1 })

        composeRule.onNodeWithText(text(R.string.scheduling_legacy)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).performClick()
        assertEquals(1, enables)
        // ⚠️ And there is no tenancy row, so nothing to badge and no host to name.
        composeRule.onNodeWithContentDescription(SCHEDULING_BADGE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a viewer on a legacy workspace reads the card and gets no button`() {
        // ⛔ FROM THE SERVER'S `canManage`, NOT FROM A ROLE THIS SCREEN WAS TOLD. The route sends
        // it on every read precisely so the client does not re-derive it — a second gate built from
        // a role string would fail closed and hide the button from an owner.
        render(ready(canManage = false))

        composeRule.onNodeWithText(text(R.string.scheduling_legacy)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).assertDoesNotExist()
    }

    // ── The four tenancy states ──────────────────────────────────────────────

    @Test
    fun `a provisioning tenancy names its host, badges Setting up and offers only Refresh`() {
        var refreshes = 0
        render(ready(tenant = tenant("provisioning")), onRetry = { refreshes += 1 })

        composeRule.onNodeWithText(text(R.string.scheduling_badge_setting_up)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_HOST_DESCRIPTION).assertIsDisplayed()
        // ⛔ NO ENABLE WHILE ONE IS RUNNING, and no Open: the SSO route answers 409 until the
        // tenancy is ready.
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_REFRESH_DESCRIPTION).performClick()
        assertEquals(1, refreshes)
    }

    @Test
    fun `a live tenancy offers BOTH hand-offs, the dashboard one first`() {
        // ⛔ THE ORDER AND THE LABELS ARE THE POINT. The scheduler console is switched off region
        // by region and a shipped Play build cannot be reverted, so the primary action has to be
        // the hand-off that survives a flip — and the secondary has to say which console it means,
        // or two buttons on one card both read as "scheduling".
        var dashboards = 0
        var consoles = 0
        render(
            ready(tenant = tenant(status = "ready", bookingUrl = "https://x.test/b")),
            onOpenDashboard = { dashboards += 1 },
            onOpenScheduler = { consoles += 1 },
        )

        composeRule.onNodeWithText(text(R.string.scheduling_open_dashboard)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.scheduling_open_scheduler)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(SCHEDULING_DASHBOARD_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).performClick()
        assertEquals(1, dashboards)
        assertEquals(1, consoles)
    }

    @Test
    fun `a retired console leaves ONLY the dashboard hand-off`() {
        // ⛔ THE PHASE-A OUTCOME. Once a region has answered 410 the console button can only ever
        // produce the same refusal again, which is the same class of thing as a retry button on a
        // role refusal — and the primary action is exactly what the server's sentence points at, so
        // it must survive.
        render(
            ready(
                tenant = tenant(status = "ready", bookingUrl = "https://x.test/b"),
                notice = UiText.Literal(
                    "Scheduling for this workspace is managed on the web dashboard.",
                ),
            ) { it.copy(consoleRetired = true) },
        )

        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_DASHBOARD_DESCRIPTION).assertIsEnabled()
        // ⛔ THE SERVER'S SENTENCE, VERBATIM AND WITH NO RETRY BESIDE IT. The notice carries a
        // Dismiss and nothing else; a Retry here would offer to repeat a refusal that is permanent
        // for this region.
        composeRule
            .onNodeWithText("Scheduling for this workspace is managed on the web dashboard.")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_RETRY_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_CARD_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a live tenancy shows its booking link, its host and when it was last checked`() {
        var opens = 0
        render(
            ready(
                tenant = tenant(
                    status = "ready",
                    bookingUrl = "https://acme-book.distronode.com/book/phone-consultation",
                    lastReadyAt = "2026-09-06T11:20:00.000Z",
                ),
            ),
            onOpenScheduler = { opens += 1 },
        )

        composeRule.onNodeWithText(text(R.string.scheduling_badge_live)).assertIsDisplayed()
        composeRule
            .onNodeWithText("https://acme-book.distronode.com/book/phone-consultation")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_HOST_DESCRIPTION).assertIsDisplayed()
        composeRule
            .onNodeWithText(text(R.string.scheduling_last_checked, "2026-09-06T11:20:00.000Z"))
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).performClick()
        assertEquals(1, opens)
        // ⚠️ A live tenancy is settled, so no Refresh — and nothing to Enable.
        composeRule.onNodeWithContentDescription(SCHEDULING_REFRESH_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a ready tenancy with no bookingUrl says so and NEVER rebuilds one from the host`() {
        // ⛔ THE ONE READY-STATE SENTENCE THAT IS NOT A FAILURE AND NOT A LINK. The route derives
        // the URL server-side and sends it only for `ready`; a client that assembled its own would
        // publish a booking address for a page that may not be serving.
        render(ready(tenant = tenant(status = "ready", bookingUrl = null)))

        composeRule.onNodeWithContentDescription(SCHEDULING_NO_LINK_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_LINK_DESCRIPTION).assertDoesNotExist()
        // ⚠️ And the Open button is still there: the tenancy IS ready, so the hand-off works even
        // when the public link did not arrive.
        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed provision shows the operator-facing reason and offers Enable again`() {
        render(
            ready(
                tenant = tenant(
                    status = "error",
                    lastError = "cloudflare refused the dns record (HTTP 403)",
                ),
            ),
        )

        composeRule
            .onNodeWithText(text(R.string.scheduling_badge_needs_attention))
            .assertIsDisplayed()
        // ⛔ SHOWN TO ANY MEMBER, BY DESIGN — somebody who cannot see why provisioning failed has to
        // open a ticket to learn it. The server classifies and truncates it, so it can never carry
        // a credential or a raw remote body.
        composeRule
            .onNodeWithText("cloudflare refused the dns record (HTTP 403)")
            .assertIsDisplayed()
        // ⚠️ NOT TERMINAL: re-running Enable is the documented manual recovery, and Refresh is here
        // because the hourly reconciler may have fixed it already.
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_REFRESH_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed provision with no recorded reason still says something`() {
        render(ready(tenant = tenant(status = "error", lastError = null)))

        composeRule
            .onNodeWithText(text(R.string.scheduling_setup_failed_no_reason))
            .assertIsDisplayed()
    }

    @Test
    fun `a switched-off tenancy gets a sentence and NO way to resurrect it`() {
        // ⛔ THE ONE STATUS NOTHING RE-PROVISIONS, AND THIS DIVERGES FROM THE WEB CARD DELIBERATELY.
        // The web dashboard offers Enable for a disabled tenancy; bringing booking pages back
        // up is a change that should be made where the switch was thrown, not from a phone.
        render(ready(tenant = tenant("disabled")))

        composeRule.onNodeWithText(text(R.string.scheduling_switched_off)).assertIsDisplayed()
        composeRule
            .onNodeWithText(text(R.string.scheduling_badge_switched_off))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_REFRESH_DESCRIPTION).assertDoesNotExist()
        // ⚠️ The host is still named — the page exists, it is just off.
        composeRule.onNodeWithContentDescription(SCHEDULING_HOST_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a status this build does not model is reported as unknown, not as a failure`() {
        // ⛔ FORWARD COMPATIBILITY. The status column takes a new value with no migration, so this
        // is reachable on an installed build the day the server adds one. It must not read as
        // "setup failed" and must not offer to provision a tenancy that already exists.
        render(ready(tenant = tenant("retiring")))

        composeRule.onNodeWithText(text(R.string.scheduling_unrecognised)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.scheduling_badge_unknown)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_HOST_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SCHEDULING_REFRESH_DESCRIPTION).assertDoesNotExist()
    }

    // ── In-flight labels and the notice ──────────────────────────────────────

    @Test
    fun `a provision in flight disables Enable and Refresh but never the hand-off`() {
        // ⚠️ TWO FLAGS, NOT ONE. Opening the scheduler is a read that spends a single-use token; it
        // must stay pressable while a provision settles.
        render(ready(tenant = tenant("error"), busy = true))

        composeRule.onNodeWithText(text(R.string.scheduling_enabling)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(SCHEDULING_REFRESH_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a hand-off in flight disables only itself`() {
        render(
            ready(tenant = tenant(status = "error", lastError = "boom")) {
                it.copy(openingDashboard = true, openingConsole = true)
            },
        )

        // ⚠️ A failed provision offers Enable and Refresh but not Open, so this asserts the mirror
        // of the previous test: `opening` leaves the two write-side controls alone.
        composeRule.onNodeWithContentDescription(SCHEDULING_ENABLE_DESCRIPTION).assertIsEnabled()
        composeRule.onNodeWithContentDescription(SCHEDULING_REFRESH_DESCRIPTION).assertIsEnabled()
    }

    @Test
    fun `opening shows its own label on a live tenancy`() {
        render(
            ready(tenant = tenant(status = "ready", bookingUrl = "https://x.test/b")) {
                it.copy(openingConsole = true)
            },
        )

        composeRule.onNodeWithText(text(R.string.scheduling_opening)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).assertIsNotEnabled()
        // ⚠️ AND THE PRIMARY IS UNTOUCHED. Each hand-off mints its own credential at a different
        // server, so one in flight must not gate the other.
        composeRule.onNodeWithContentDescription(SCHEDULING_DASHBOARD_DESCRIPTION).assertIsEnabled()
        composeRule.onNodeWithText(text(R.string.scheduling_open_dashboard)).assertIsDisplayed()
    }

    @Test
    fun `the PRIMARY hand-off in flight disables only itself`() {
        render(
            ready(tenant = tenant(status = "ready", bookingUrl = "https://x.test/b")) {
                it.copy(openingDashboard = true)
            },
        )

        composeRule.onNodeWithContentDescription(SCHEDULING_DASHBOARD_DESCRIPTION)
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(SCHEDULING_OPEN_DESCRIPTION).assertIsEnabled()
        composeRule.onNodeWithText(text(R.string.scheduling_open_scheduler)).assertIsDisplayed()
    }

    @Test
    fun `a notice sits ALONGSIDE the card and dismisses without touching it`() {
        // ⛔ ABOVE THE CARD RATHER THAN INSTEAD OF IT. A refused provision leaves the card's own
        // answer intact and worth reading; replacing it would lose the state the operator was
        // looking at.
        var dismissed = 0
        render(
            ready(tenant = tenant("provisioning"), notice = UiText.Literal("that did not work")),
            onDismissNotice = { dismissed += 1 },
        )

        composeRule.onNodeWithContentDescription(SCHEDULING_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("that did not work").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_CARD_DESCRIPTION).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(SCHEDULING_DISMISS_DESCRIPTION).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a notice is shown over a FAILED read too`() {
        // ⚠️ THE PAIR THAT MOTIVATES THE PLACEMENT. A refused provision is followed by an
        // unconditional re-read, and that re-read can itself fail — at which point the sentence
        // explaining the refusal is the only thing on screen that says what happened.
        render(
            SchedulingUiState(
                screen = SchedulingScreenState.Failed(FailureText(message = UiText.Literal("gone"))),
                notice = UiText.Literal("the provision was refused"),
            ),
        )

        composeRule.onNodeWithText("the provision was refused").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_FAILURE_DESCRIPTION).assertIsDisplayed()
    }

    // ── Blank values from the server, and the preview ────────────────────────

    @Test
    fun `a blank failure reason reads as no recorded reason, not as an empty line`() {
        render(ready(tenant = tenant(status = "error", lastError = "  ")))

        composeRule
            .onNodeWithText(text(R.string.scheduling_setup_failed_no_reason))
            .assertIsDisplayed()
    }

    @Test
    fun `a blank bookingUrl is no link, and is never shown as one`() {
        render(ready(tenant = tenant(status = "ready", bookingUrl = " ")))

        composeRule.onNodeWithContentDescription(SCHEDULING_NO_LINK_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SCHEDULING_LINK_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the preview renders a live booking page with its link`() {
        composeRule.setContent { SchedulingScreenPreview() }

        composeRule.onNodeWithContentDescription(SCHEDULING_LINK_DESCRIPTION).assertIsDisplayed()
    }
}
