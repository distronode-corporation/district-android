package com.distronode.districtai.ui.analytics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.AnalyticsMetrics
import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.CallVolumeDelta
import com.distronode.districtai.core.model.DIRECTION_DOWN
import com.distronode.districtai.core.model.DIRECTION_FLAT
import com.distronode.districtai.core.model.DIRECTION_UP
import com.distronode.districtai.core.model.EngagementPoint
import com.distronode.districtai.core.model.FunnelStage
import com.distronode.districtai.core.model.SentimentSlice
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the analytics screen renders, and the three things it must never render: a null percentage
 * as "0%", an unmetered month as a column of zeros, and a chart that crashes on the all-zero series
 * every brand-new workspace produces.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT, AND IT IS NOT COSMETIC. This screen is a `verticalScroll` Column rather than
// a LazyColumn, so every card is COMPOSED whether or not it is on screen — and `assertIsDisplayed`
// checks visible BOUNDS, not existence. On a default phone-sized Robolectric display the usage
// card sits below the fold and the assertion fails with "is not displayed" while the node is
// perfectly present, which reads as a rendering bug rather than as a viewport that is too short.
// The alternatives are worse: `assertExists` would stop checking that anything is actually drawn,
// and sprinkling `performScrollTo()` would make each test depend on the layout order of the ones
// around it.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class AnalyticsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val trends = listOf(
        EngagementPoint(date = "Aug 9", isoDate = "2026-08-09", calls = 9, avgDuration = 100),
        EngagementPoint(date = "Aug 10", isoDate = "2026-08-10", calls = 0, avgDuration = 0),
        EngagementPoint(date = "Aug 11", isoDate = "2026-08-11", calls = 11, avgDuration = 120),
    )

    private val report = AnalyticsResponse(
        success = true,
        metrics = AnalyticsMetrics(
            totalCalls = 48,
            avgDuration = 120,
            conversionRate = 38,
            abandonedCalls = 4,
            missedCalls = 9,
        ),
        callVolumeDelta = CallVolumeDelta(current = 48, prior = 40, pct = 20, direction = DIRECTION_UP),
        engagementTrends = trends,
        funnelData = listOf(
            FunnelStage("Total Dials", 48),
            FunnelStage("Connected Calls", 35),
            FunnelStage("Successful Leads", 18),
        ),
        sentimentDistribution = listOf(
            SentimentSlice("Positive Sentiment", 21, "#10b981"),
            SentimentSlice("Neutral Sentiment", 16, "#f59e0b"),
            SentimentSlice("Friction Sentiment", 11, "#ef4444"),
        ),
    )

    private val usage = UsageData(
        month = "2026-08",
        smsOutbound = 412.0,
        callMinutesInbound = 1204.25,
        whatsappOutbound = 0.0,
    )

    /**
     * The history fixture, shaped like `district-usage-history.json`.
     *
     * ⛔ NEWEST FIRST, AND THE OLDEST MONTH HAS NO CALL MINUTES AT ALL. That third row is the one
     * worth having: it is the absent-metric branch, which must render a dash rather than a zero.
     *
     * ⚠️ EVERY NUMBER HERE IS DELIBERATELY DISTINCT FROM THE USAGE AND ANALYTICS FIXTURES ABOVE.
     * `onNodeWithText` fails on TWO matches as loudly as on none, so a history month that happened
     * to total the same as a usage row would break unrelated assertions on this screen with a
     * message about node counts rather than about the collision.
     */
    private val months = listOf(
        UsageData(
            month = "2026-08",
            smsOutbound = 501.0,
            callMinutesOutbound = 200.5,
            callMinutesInbound = 300.25,
        ),
        UsageData(month = "2026-07", smsOutbound = 333.0, callMinutesOutbound = 250.0),
        UsageData(month = "2026-06", smsOutbound = 77.0),
    )

    /**
     * ⚠️ The three callbacks are BUNDLED rather than passed individually, matching `HqScreenTest`:
     * a helper mirroring the screen's parameters plus its state lands over detekt's
     * `LongParameterList` threshold, and a plain test helper is not `@Composable`-exempt.
     */
    private class Callbacks(
        val onSelectRange: (AnalyticsRange) -> Unit = {},
        val onRetry: () -> Unit = {},
        val onBack: () -> Unit = {},
    )

    private fun render(state: AnalyticsUiState, callbacks: Callbacks = Callbacks()) {
        composeRule.setContent {
            DistrictTheme {
                AnalyticsScreen(
                    state = state,
                    onSelectRange = callbacks.onSelectRange,
                    onRetry = callbacks.onRetry,
                    onSignIn = {},
                    onBack = callbacks.onBack,
                )
            }
        }
    }

    /**
     * ⚠️ THE HISTORY DEFAULTS TO A POPULATED THREE-MONTH LIST, not to an empty one. An empty
     * default would make every unrelated assertion on this screen run against the "nothing has
     * ever been metered" branch, and the branch that renders rows would be exercised by exactly
     * one test.
     */
    private fun content(
        analytics: AnalyticsCardState = AnalyticsCardState.Ready(report),
        usageCard: UsageCardState = UsageCardState.Ready(usage),
        history: UsageHistoryCardState = UsageHistoryCardState.Ready(months),
        range: AnalyticsRange = AnalyticsRange.SEVEN_DAYS,
        refreshing: Boolean = false,
    ) = AnalyticsUiState.Content(range, analytics, usageCard, history, refreshing)

    private fun failure(
        message: String = "Something went wrong on our side.",
        retryable: Boolean = true,
    ) = FailureText(message = UiText.Literal(message), retryable = retryable)

    // ── Shell ────────────────────────────────────────────────────────────────

    @Test
    fun `the loading state stands in for the content rather than spinning`() {
        render(AnalyticsUiState.Loading)

        composeRule.onNodeWithContentDescription(ANALYTICS_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_LOADING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the app bar back action is present`() {
        var backs = 0
        render(content(), Callbacks(onBack = { backs++ }))

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        assertEquals(1, backs)
    }

    // ── Metrics ──────────────────────────────────────────────────────────────

    @Test
    fun `the headline figures are rendered as the server derived them`() {
        render(content())

        composeRule.onNodeWithContentDescription(ANALYTICS_METRICS_DESCRIPTION).assertIsDisplayed()
        // ⚠️ UPPERCASE, because `Eyebrow` transforms its input — CSS `text-transform: uppercase`
        // has no TextStyle equivalent in Compose, so the design system does it in Kotlin and the
        // string resource stays sentence-case and translatable.
        composeRule.onNodeWithText("TOTAL CALLS").assertIsDisplayed()
        // ⚠️ NOT asserted on the value "48": the funnel's "Total Dials" stage carries the same
        // number, so a bare text match finds two nodes. Asserting a figure that appears twice on
        // one screen proves less than it looks like it does anyway.
        composeRule.onNodeWithText("9").assertIsDisplayed()
        // ⛔ THE TILE DURATION FORM, and it is COMPLETED-only — the caption says so, because
        // avgDuration * totalCalls is not talk time.
        composeRule.onNodeWithText("2m 0s").assertIsDisplayed()
        composeRule.onNodeWithText("Across completed sessions").assertIsDisplayed()
        // ⚠️ ALREADY A PERCENTAGE. A screen that multiplied by 100 would show "3800%".
        composeRule.onNodeWithText("38%").assertIsDisplayed()
    }

    @Test
    fun `there is no active-agents tile, because the field is hardcoded to zero`() {
        // ⛔ The server has no presence signal and sends 0 unconditionally, so a tile would read
        // "0 agents" forever and look like a measurement. Absent is the honest rendering, and this
        // pins it so a future reader does not "complete" the tile set.
        render(content())

        composeRule.onNodeWithText("Active Agents").assertDoesNotExist()
    }

    // ── The call-volume delta ────────────────────────────────────────────────

    @Test
    fun `a rise shows its magnitude with the arrow carrying the sign`() {
        render(content())

        composeRule.onNodeWithContentDescription(ANALYTICS_DELTA_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("▲ 20% vs the previous period").assertIsDisplayed()
        composeRule.onNodeWithText("48 now, 40 in the previous period").assertIsDisplayed()
    }

    @Test
    fun `a fall shows a magnitude, not a double negative`() {
        // ⚠️ The server sends a NEGATIVE percentage for a fall, so rendering it verbatim beside a
        // down arrow reads "▼ -20%".
        val falling = report.copy(
            callVolumeDelta = CallVolumeDelta(current = 32, prior = 40, pct = -20, direction = DIRECTION_DOWN),
        )
        render(content(analytics = AnalyticsCardState.Ready(falling)))

        composeRule.onNodeWithText("▼ 20% vs the previous period").assertIsDisplayed()
    }

    @Test
    fun `a NULL percentage renders New, never zero percent`() {
        // ⛔ THE FIXTURE-BACKED BRANCH. `pct: null` means there is no prior period to compare
        // against; rendering it as "0%" tells a brand-new customer their call volume is flat
        // during their first week on the platform.
        val fresh = report.copy(
            callVolumeDelta = CallVolumeDelta(current = 0, prior = 0, pct = null, direction = DIRECTION_FLAT),
        )
        render(content(analytics = AnalyticsCardState.Ready(fresh)))

        composeRule.onNodeWithText("New — no previous period to compare").assertIsDisplayed()
        composeRule.onNodeWithText("0% vs the previous period").assertDoesNotExist()
        composeRule.onNodeWithText("No change vs the previous period").assertDoesNotExist()
    }

    @Test
    fun `an unchanged volume with a real baseline says so rather than showing zero percent`() {
        val flat = report.copy(
            callVolumeDelta = CallVolumeDelta(current = 40, prior = 40, pct = 0, direction = DIRECTION_FLAT),
        )
        render(content(analytics = AnalyticsCardState.Ready(flat)))

        composeRule.onNodeWithText("No change vs the previous period").assertIsDisplayed()
    }

    // ── Charts ───────────────────────────────────────────────────────────────

    @Test
    fun `the trend chart draws and labels its axis with the server's display strings`() {
        render(content())

        composeRule.onNodeWithContentDescription(ANALYTICS_TREND_DESCRIPTION).assertIsDisplayed()
        // ⚠️ RENDERED VERBATIM. `date` is localized server-side to the operator's timezone and
        // carries no year; the client never parses it.
        composeRule.onNodeWithText("Aug 9 to Aug 11").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_TREND_EMPTY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an ALL-ZERO series draws without crashing and is labelled as empty`() {
        // ⛔ THE DEGENERATE CASE EVERY NEW WORKSPACE PRODUCES. The server always sends one point
        // per bucket, so "no calls" arrives as a series of zeros and the scaling maths divides by
        // a maximum of zero. An unlabelled flat chart would also read as a broken renderer rather
        // than as an honest empty window.
        val empty = report.copy(
            metrics = AnalyticsMetrics(),
            engagementTrends = trends.map { it.copy(calls = 0, avgDuration = 0) },
            funnelData = listOf(FunnelStage("Total Dials", 0), FunnelStage("Connected Calls", 0)),
            sentimentDistribution = report.sentimentDistribution.map { it.copy(value = 0) },
        )
        render(content(analytics = AnalyticsCardState.Ready(empty)))

        composeRule.onNodeWithContentDescription(ANALYTICS_TREND_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_TREND_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("No calls in this window.").assertIsDisplayed()
        // The proportional bar has to survive a total of zero too.
        composeRule.onNodeWithContentDescription(ANALYTICS_SENTIMENT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("No calls to analyse yet.").assertIsDisplayed()
    }

    @Test
    fun `a single-point series draws without dividing by a zero span`() {
        val single = report.copy(engagementTrends = listOf(trends.last()))
        render(content(analytics = AnalyticsCardState.Ready(single)))

        composeRule.onNodeWithContentDescription(ANALYTICS_TREND_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Aug 11 to Aug 11").assertIsDisplayed()
    }

    @Test
    fun `a series the server somehow sent empty does not crash the screen`() {
        // ⚠️ NOT A SHAPE THE CONTRACT PRODUCES — the route always seeds the buckets — which is
        // exactly why it is worth pinning: nothing upstream would catch a change that started
        // sending one, and `first()`/`last()` on an empty list throws.
        val none = report.copy(engagementTrends = emptyList())
        render(content(analytics = AnalyticsCardState.Ready(none)))

        composeRule.onNodeWithContentDescription(ANALYTICS_TREND_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_TREND_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the funnel renders each stage with its own count`() {
        render(content())

        composeRule.onNodeWithContentDescription(ANALYTICS_FUNNEL_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Total Dials").assertIsDisplayed()
        composeRule.onNodeWithText("Successful Leads").assertIsDisplayed()
        composeRule.onNodeWithText("18").assertIsDisplayed()
    }

    @Test
    fun `an unparseable server colour falls back instead of failing the screen`() {
        // ⛔ THE COLOUR IS PRESENTATIONAL AND MUST NEVER COST THE DATA. Parsing it in the DTO
        // would have failed the whole analytics response — every metric above — over a shade.
        val odd = report.copy(
            sentimentDistribution = listOf(
                SentimentSlice("Positive Sentiment", 21, "rgb(16,185,129)"),
                SentimentSlice("Neutral Sentiment", 16, ""),
                SentimentSlice("Friction Sentiment", 11, "#ef4444"),
            ),
        )
        render(content(analytics = AnalyticsCardState.Ready(odd)))

        composeRule.onNodeWithContentDescription(ANALYTICS_SENTIMENT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Positive Sentiment").assertIsDisplayed()
        composeRule.onNodeWithText("21").assertIsDisplayed()
    }

    // ── Range chips ──────────────────────────────────────────────────────────

    @Test
    fun `every window is offered and tapping one reports it`() {
        var chosen: AnalyticsRange? = null
        render(content(), Callbacks(onSelectRange = { chosen = it }))

        composeRule.onNodeWithContentDescription(rangeDescription(AnalyticsRange.SEVEN_DAYS))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(rangeDescription(AnalyticsRange.THIRTY_DAYS))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(rangeDescription(AnalyticsRange.NINETY_DAYS))
            .performClick()

        assertEquals(AnalyticsRange.NINETY_DAYS, chosen)
    }

    @Test
    fun `the chips keep reporting taps after the selected window moves`() {
        // ⚠️ One screen, its window changed underneath it the way the ViewModel does after a tap:
        // every chip is redrawn against the new selection and must still say which window it is.
        val chosen = mutableListOf<AnalyticsRange>()
        var state by mutableStateOf(content())
        composeRule.setContent {
            DistrictTheme {
                AnalyticsScreen(
                    state = state,
                    onSelectRange = { chosen += it },
                    onRetry = {},
                    onSignIn = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription(rangeDescription(AnalyticsRange.THIRTY_DAYS)).performClick()
        state = content(range = AnalyticsRange.THIRTY_DAYS)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(rangeDescription(AnalyticsRange.SEVEN_DAYS)).performClick()

        assertEquals(listOf(AnalyticsRange.THIRTY_DAYS, AnalyticsRange.SEVEN_DAYS), chosen)
    }

    @Test
    fun `a reload over existing content is announced without blanking the figures`() {
        render(content(range = AnalyticsRange.NINETY_DAYS, refreshing = true))

        composeRule.onNodeWithContentDescription(ANALYTICS_REFRESHING_DESCRIPTION).assertIsDisplayed()
        // ⛔ The previous window's figures stay on screen — a range switch must not flash the page.
        composeRule.onNodeWithContentDescription(ANALYTICS_METRICS_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("48 now, 40 in the previous period").assertIsDisplayed()
    }

    // ── Usage ────────────────────────────────────────────────────────────────

    @Test
    fun `a metered month lists only the metrics it actually carries`() {
        render(content())

        composeRule.onNodeWithContentDescription(ANALYTICS_USAGE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Month 2026-08").assertIsDisplayed()
        // Whole amounts print whole; fractional ones keep their decimals — these are billing
        // figures and a truncation is a wrong number.
        composeRule.onNodeWithText("412").assertIsDisplayed()
        composeRule.onNodeWithText("1204.25").assertIsDisplayed()
        // ⚠️ Present-at-zero IS shown; absent is not. Those are different facts.
        composeRule.onNodeWithText("WhatsApp sent").assertIsDisplayed()
        composeRule.onNodeWithText("SMS received").assertDoesNotExist()
    }

    @Test
    fun `every metric a month carries gets a row of its own`() {
        // ⚠️ THE FULLY-POPULATED SHAPE. The fixture-shaped month above is deliberately sparse, so
        // on its own it leaves most of the row list unexercised — and the row list is where a
        // mislabelled metric hides. Video minutes in particular are labelled "(tracked)" because
        // they are metered but deliberately excluded from overage billing; listing them
        // unqualified beside billable metrics would imply a charge that is not made.
        val everything = UsageData(
            month = "2026-08",
            provider = "twilio",
            smsOutbound = 412.0,
            smsInbound = 87.0,
            mmsOutbound = 6.0,
            whatsappOutbound = 2.0,
            whatsappInbound = 1.0,
            callMinutesOutbound = 318.5,
            callMinutesInbound = 1204.25,
            numberCount = 3.0,
            videoMinutes = 42.0,
        )
        render(content(usageCard = UsageCardState.Ready(everything)))

        composeRule.onNodeWithText("SMS sent").assertIsDisplayed()
        composeRule.onNodeWithText("SMS received").assertIsDisplayed()
        composeRule.onNodeWithText("MMS sent").assertIsDisplayed()
        composeRule.onNodeWithText("WhatsApp sent").assertIsDisplayed()
        composeRule.onNodeWithText("WhatsApp received").assertIsDisplayed()
        composeRule.onNodeWithText("Outbound call minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Inbound call minutes").assertIsDisplayed()
        composeRule.onNodeWithText("Phone numbers").assertIsDisplayed()
        composeRule.onNodeWithText("Video minutes (tracked)").assertIsDisplayed()
        // Fractional minutes survive; whole counts print whole.
        composeRule.onNodeWithText("318.5").assertIsDisplayed()
        composeRule.onNodeWithText("87").assertIsDisplayed()
    }

    @Test
    fun `a month carrying only numbers and video lists those two and nothing else`() {
        // ⚠️ The mirror image of the sparse fixture above: SMS, WhatsApp and inbound minutes are the
        // metrics that month DOES carry, and here they are the ones absent.
        render(
            content(
                usageCard = UsageCardState.Ready(
                    UsageData(month = "2026-08", numberCount = 3.0, videoMinutes = 42.0),
                ),
            ),
        )

        composeRule.onNodeWithText("Phone numbers").assertIsDisplayed()
        composeRule.onNodeWithText("Video minutes (tracked)").assertIsDisplayed()
        composeRule.onNodeWithText("SMS sent").assertDoesNotExist()
        composeRule.onNodeWithText("WhatsApp sent").assertDoesNotExist()
        composeRule.onNodeWithText("Inbound call minutes").assertDoesNotExist()
    }

    @Test
    fun `an unmetered month says so and renders NO zeros`() {
        // ⛔ THE FAILURE THIS TEST EXISTS FOR. A DTO or a screen that defaulted a null month to a
        // zeroed row would state, with the authority of a billing figure, that a workspace sent
        // nothing — when the truth is that nothing was measured. The distinction is invisible once
        // it is drawn as "0".
        render(content(usageCard = UsageCardState.Ready(null)))

        composeRule.onNodeWithContentDescription(ANALYTICS_USAGE_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("No usage has been recorded for this month yet.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("SMS sent").assertDoesNotExist()
        composeRule.onNodeWithText("Outbound call minutes").assertDoesNotExist()
        composeRule.onNodeWithText("0").assertDoesNotExist()
    }

    // ── Usage history ────────────────────────────────────────────────────────

    @Test
    fun `a month whose messages move while its minutes hold redraws only the new count`() {
        // ⚠️ A reload that changed one month's message total and nothing about its minutes: the row
        // re-renders the new figure while its bar, scaled against the same maximum, stays put.
        var state by mutableStateOf(content())
        composeRule.setContent {
            DistrictTheme {
                AnalyticsScreen(state = state, onSelectRange = {}, onRetry = {}, onSignIn = {}, onBack = {})
            }
        }
        composeRule.onNodeWithText("501").assertIsDisplayed()

        val moved = listOf(months[0].copy(smsOutbound = 601.0)) + months.drop(1)
        state = content(history = UsageHistoryCardState.Ready(moved))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("601").assertIsDisplayed()
        composeRule.onNodeWithText("501").assertDoesNotExist()
        composeRule.onNodeWithText("500.75").assertIsDisplayed()
    }

    @Test
    fun `the history card renders one labelled row per month, newest first`() {
        render(content())

        composeRule.onNodeWithContentDescription(ANALYTICS_HISTORY_DESCRIPTION).assertIsDisplayed()
        // ⛔ THE MONTH LABEL COMES FROM THE `YYYY-MM` KEY BY LOOKUP, NOT BY PARSING IT AS A DATE.
        // A formatter would resolve the key against a timezone it does not have and can shift a
        // month by one for a reader west of UTC.
        composeRule.onNodeWithContentDescription(historyMonthDescription("2026-08"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Aug 2026").assertIsDisplayed()
        composeRule.onNodeWithText("Jul 2026").assertIsDisplayed()
        composeRule.onNodeWithText("Jun 2026").assertIsDisplayed()

        // Both directions are summed into one metered figure: 200.5 + 300.25.
        composeRule.onNodeWithText("500.75").assertIsDisplayed()
        composeRule.onNodeWithText("501").assertIsDisplayed()
        composeRule.onNodeWithText("250").assertIsDisplayed()
        composeRule.onNodeWithText("333").assertIsDisplayed()
    }

    @Test
    fun `a month with no call minutes shows a dash rather than a zero`() {
        // ⛔ THE FAILURE THIS TEST EXISTS FOR, one level down from the unmetered-month test above.
        // June carries only an SMS count, so BOTH call-minute keys are absent from the wire. A
        // sum that defaulted them to zero would print "0" under a billing label for a month that
        // was never measured for calls at all.
        render(content())

        composeRule.onNodeWithContentDescription(historyMonthDescription("2026-06"))
            .assertIsDisplayed()
        composeRule.onNodeWithText("—").assertIsDisplayed()
        composeRule.onNodeWithText("77").assertIsDisplayed()
        composeRule.onAllNodesWithText("Metered call minutes").assertCountEquals(3)
        composeRule.onAllNodesWithText("Messages").assertCountEquals(3)
    }

    @Test
    fun `a workspace that has never been metered is told so, not shown empty rows`() {
        // ⛔ AN EMPTY LIST IS AN ANSWER. The server appends only months that had rows, so `[]`
        // means "nothing has ever been metered" — not "we could not look" and not "you used
        // nothing".
        render(content(history = UsageHistoryCardState.Ready(emptyList())))

        composeRule.onNodeWithContentDescription(ANALYTICS_HISTORY_EMPTY_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("No usage has been recorded in any recent month yet.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Aug 2026").assertDoesNotExist()
        composeRule.onAllNodesWithText("Messages").assertCountEquals(0)
    }

    @Test
    fun `a failed history read keeps this month's usage on screen`() {
        // ⛔ TWO REQUESTS AGAINST ONE ROUTE, AND THEY FAIL SEPARATELY. `history=true` is the only
        // difference between them, which is exactly why folding them into one state is tempting
        // and wrong: it would discard the current month over a trend nobody asked for first.
        render(content(history = UsageHistoryCardState.Failed(failure())))

        composeRule.onNodeWithContentDescription(ANALYTICS_HISTORY_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_USAGE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Month 2026-08").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_FAILED_DESCRIPTION).assertDoesNotExist()
    }

    // ── Independent failures ─────────────────────────────────────────────────

    @Test
    fun `a failed analytics read keeps the usage card on screen`() {
        // ⛔ THE CENTRAL GUARANTEE. The two reads are unrelated server-side, so one failing must
        // not discard the other's answer.
        render(content(analytics = AnalyticsCardState.Failed(failure())))

        composeRule.onNodeWithContentDescription(ANALYTICS_ANALYTICS_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_USAGE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Month 2026-08").assertIsDisplayed()
        // And the whole-screen failure state is NOT what was shown.
        composeRule.onNodeWithContentDescription(ANALYTICS_FAILED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a failed usage read keeps the analytics figures on screen`() {
        render(content(usageCard = UsageCardState.Failed(failure())))

        composeRule.onNodeWithContentDescription(ANALYTICS_USAGE_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_METRICS_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_TREND_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a card failure offers a retry that calls back`() {
        var retried = 0
        render(
            content(analytics = AnalyticsCardState.Failed(failure())),
            Callbacks(onRetry = { retried++ }),
        )

        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun `a non-retryable card failure offers no retry`() {
        // ⚠️ Contract drift produces the identical failure on every attempt, so a retry button
        // there is a control that cannot succeed.
        render(content(analytics = AnalyticsCardState.Failed(failure(retryable = false))))

        composeRule.onNodeWithContentDescription(ANALYTICS_ANALYTICS_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a total failure blanks the screen and offers one retry`() {
        var retried = 0
        render(
            AnalyticsUiState.Failed(failure("A region is unreachable.")),
            Callbacks(onRetry = { retried++ }),
        )

        composeRule.onNodeWithContentDescription(ANALYTICS_FAILED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("A region is unreachable.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_RETRY_DESCRIPTION).performClick()

        assertEquals(1, retried)
        // Neither card area is drawn — there is nothing to preserve.
        composeRule.onNodeWithContentDescription(ANALYTICS_USAGE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a total failure that cannot be retried offers no button`() {
        render(AnalyticsUiState.Failed(failure(retryable = false)))

        composeRule.onNodeWithContentDescription(ANALYTICS_FAILED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ANALYTICS_RETRY_DESCRIPTION).assertDoesNotExist()
    }
}
