package com.distronode.districtai.ui.calls

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.PagedLoadException
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.testCall
import com.distronode.districtai.core.designsystem.DistrictTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * How the call log renders Paging's load states.
 *
 * ⛔ THE DISTINCTION UNDER TEST: a REFRESH failure owns the whole screen, an APPEND failure owns only
 * a footer. Conflating them replaces a populated, scrolled list with a full-screen error because one
 * extra page failed — which is both jarring and destroys the user's position. And the empty state
 * must only be reachable once refresh has SUCCEEDED, so "we could not load your calls" can never be
 * drawn as "you have no calls".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CallLogScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun states(
        refresh: LoadState = LoadState.NotLoading(endOfPaginationReached = true),
        append: LoadState = LoadState.NotLoading(endOfPaginationReached = true),
    ) = LoadStates(
        refresh = refresh,
        prepend = LoadState.NotLoading(endOfPaginationReached = true),
        append = append,
    )

    private fun render(
        items: List<CallSummary>,
        loadStates: LoadStates = states(),
        onOpenCall: (String) -> Unit = {},
        onSignIn: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                Log(items, loadStates, onOpenCall, onSignIn)
            }
        }
    }

    @Composable
    private fun Log(
        items: List<CallSummary>,
        loadStates: LoadStates,
        onOpenCall: (String) -> Unit,
        onSignIn: () -> Unit,
    ) {
        val paging = flowOf(
            PagingData.from(items, sourceLoadStates = loadStates),
        ).collectAsLazyPagingItems()

        CallLogScreen(calls = paging, onOpenCall = onOpenCall, onSignIn = onSignIn)
    }

    private fun degraded() = PagedLoadException(
        ApiResult.RegionsDegraded("A region is unreachable. Your account has not changed.", listOf("eu")),
    )

    @Test
    fun `renders rows`() {
        // ⚠️ Distinct names on purpose: two rows sharing one caller name make onNodeWithText
        // ambiguous and the assertion fails with "found 2 nodes" rather than telling you anything
        // about the screen.
        render(
            listOf(
                testCall(id = "c1").copy(number = "Ada Lovelace"),
                testCall(id = "c2").copy(number = "Bob Barker"),
            ),
        )

        composeRule.onNodeWithContentDescription(CALL_LOG_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithText("Bob Barker").assertIsDisplayed()
    }

    @Test
    fun `shows the empty state only after refresh succeeded`() {
        // ⚠️ Reachable only from NotLoading, which is what makes "no calls yet" honest here.
        render(emptyList())

        composeRule.onNodeWithContentDescription(CALL_LOG_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `shows a labelled spinner while refreshing`() {
        render(emptyList(), states(refresh = LoadState.Loading))

        composeRule.onNodeWithContentDescription(CALL_LOG_LOADING_DESCRIPTION).assertIsDisplayed()
        // ⛔ NOT the empty state. An in-flight load is not an absence of calls.
        composeRule.onNodeWithContentDescription(CALL_LOG_EMPTY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a refresh failure is a full-screen failure, never the empty state`() {
        // ⛔ THE INCIDENT SHAPE. Rendering this as "no calls yet" is indistinguishable from data
        // loss to the person holding the phone.
        render(emptyList(), states(refresh = LoadState.Error(degraded())))

        composeRule.onNodeWithContentDescription(CALL_LOG_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CALL_LOG_EMPTY_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithText("Affected regions: eu").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun `a refresh failure offers sign-in rather than retry when the session is gone`() {
        // Retrying a dead session just fails again; the only way forward is signing in.
        render(
            emptyList(),
            states(refresh = LoadState.Error(PagedLoadException(ApiResult.Unauthorized(reason = null)))),
        )

        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a refresh failure offers no retry for contract drift`() {
        // ⛔ Retrying cannot fix a response shape this build cannot parse, so a button would be a
        // button that can only fail.
        render(
            emptyList(),
            states(
                refresh = LoadState.Error(
                    PagedLoadException(ApiResult.DecodeFailure(RuntimeException("nope"), "{}")),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(CALL_LOG_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
        composeRule.onNodeWithText("Sign in").assertDoesNotExist()
    }

    @Test
    fun `an append failure keeps the rows and reports only in the footer`() {
        // ⛔ THE OTHER HALF. One failed extra page must not destroy a list the user has scrolled.
        render(
            listOf(
                testCall(id = "c1").copy(number = "Ada Lovelace"),
                testCall(id = "c2").copy(number = "Bob Barker"),
            ),
            states(append = LoadState.Error(degraded())),
        )

        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CALL_LOG_APPEND_FAILURE_DESCRIPTION).assertIsDisplayed()
        // Emphatically NOT the full-screen treatment.
        composeRule.onNodeWithContentDescription(CALL_LOG_FAILURE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an append in flight shows a footer spinner and keeps the rows`() {
        render(listOf(testCall(id = "c1")), states(append = LoadState.Loading))

        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CALL_LOG_APPENDING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `renders a placeholder rather than the literal Unknown`() {
        render(listOf(testCall(id = "c1").copy(number = "Unknown")))

        composeRule.onNodeWithText("No caller ID").assertIsDisplayed()
        composeRule.onNodeWithText("Unknown").assertDoesNotExist()
    }

    @Test
    fun `flags a live call and shows a stale one by its display status`() {
        // The server has already downgraded a stale in-progress call, so a day-old row cannot read
        // as Live here.
        render(
            listOf(
                testCall(id = "live", status = "in-progress"),
                testCall(id = "stale", status = "no-answer"),
            ),
        )

        composeRule.onNodeWithText("Live").assertIsDisplayed()
        composeRule.onNodeWithText("no-answer").assertIsDisplayed()
    }

    @Test
    fun `a failed transfer is flagged on its row, and a completed one is marked transferred`() {
        // A transfer that failed means the caller never reached the human they were handed to,
        // which is the single most actionable thing in the log, so it must be on the row itself.
        render(
            listOf(
                testCall(id = "handed").copy(number = "Ada Lovelace", transferStatus = "success"),
                testCall(id = "dropped").copy(number = "Bob Barker", transferStatus = "failed"),
            ),
        )

        composeRule.onNodeWithText("Transferred").assertIsDisplayed()
        composeRule.onNodeWithText("Transfer failed").assertIsDisplayed()
    }

    @Test
    fun `an outbound call says so, and an inbound one says inbound`() {
        render(
            listOf(
                testCall(id = "out").copy(number = "Ada Lovelace", direction = "outbound"),
                testCall(id = "in").copy(number = "Bob Barker"),
            ),
        )

        composeRule.onNodeWithText("Outbound", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Inbound", substring = true).assertIsDisplayed()
    }
}
