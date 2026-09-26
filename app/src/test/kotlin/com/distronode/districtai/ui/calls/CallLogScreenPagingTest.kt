package com.distronode.districtai.ui.calls

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.testCall
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The call log fed by a real `Pager`, for the one case a static `PagingData` cannot produce: slots
 * counted ahead of the loaded rows.
 *
 * ⚠️ The production source disables placeholders (the feed has no total), but the screen takes any
 * paged list, and a slot with no item yet must neither crash it nor draw a blank row.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CallLogScreenPagingTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    /** One page of [rows], with [placeholdersAfter] empty slots counted after it. */
    private class CountedAheadSource(
        private val rows: List<CallSummary>,
        private val placeholdersAfter: Int,
    ) : PagingSource<Int, CallSummary>() {
        override fun getRefreshKey(state: PagingState<Int, CallSummary>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CallSummary> =
            LoadResult.Page(
                data = rows,
                prevKey = null,
                nextKey = null,
                itemsBefore = 0,
                itemsAfter = placeholdersAfter,
            )
    }

    @Test
    fun `placeholder slots draw nothing, and the loaded row still does`() {
        val flow = Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = true),
            pagingSourceFactory = { CountedAheadSource(listOf(testCall(id = "c1")), placeholdersAfter = 3) },
        ).flow
        composeRule.setContent {
            DistrictTheme {
                CallLogScreen(
                    calls = flow.collectAsLazyPagingItems(),
                    onOpenCall = {},
                    onSignIn = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithText("No caller ID").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(CALL_LOG_EMPTY_DESCRIPTION).assertDoesNotExist()
    }

    private companion object {
        const val PAGE_SIZE = 10
    }
}
