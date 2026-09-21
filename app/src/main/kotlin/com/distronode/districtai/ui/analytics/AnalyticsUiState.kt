package com.distronode.districtai.ui.analytics

import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.ui.FailureText

/**
 * The analytics screen.
 *
 * ⛔ THE SCREEN SHOWS THREE INDEPENDENT READS AND ANY OF THEM MAY FAIL ON ITS OWN. Analytics comes
 * from raw SQL over the Call table; usage and usage history come from the metering store, on the
 * same route but through two different response shapes. They fail for different reasons, so a
 * single failure state would blank a correct answer the client already holds — an operator who can
 * see this month's SMS count would lose it because the call aggregate timed out.
 *
 * ⚠️ [Failed] AT THE TOP LEVEL THEREFORE MEANS *EVERY* READ FAILED, which is the one case where
 * there is nothing on the screen to preserve and a single retry is the honest control. Anything
 * partial stays in [Content] with the failure confined to its own card.
 */
sealed interface AnalyticsUiState {

    data object Loading : AnalyticsUiState

    /**
     * @param range the window the CHIPS should show as selected. ⚠️ Updated the moment a new range
     *   is chosen rather than when its response lands, so the selected chip never lags the tap —
     *   [refreshing] is what says the figures are still the previous window's.
     * @param refreshing a reload is in flight over content already on screen. The figures stay
     *   visible; blanking them for a range switch would flash the whole screen on every tap.
     */
    data class Content(
        val range: AnalyticsRange,
        val analytics: AnalyticsCardState,
        val usage: UsageCardState,
        /**
         * ⚠️ NOT WINDOWED BY [range]. The history is a fixed span of recent MONTHS from the
         * metering store, and the chips select a span of DAYS over the call log. They are re-read
         * together only because one reload path is safer than two — see the ViewModel.
         */
        val history: UsageHistoryCardState,
        val refreshing: Boolean = false,
    ) : AnalyticsUiState

    /** ⛔ Every read failed. See the ⚠️ on the interface. */
    data class Failed(val failure: FailureText) : AnalyticsUiState
}

/** The analytics half of [AnalyticsUiState.Content]. */
sealed interface AnalyticsCardState {

    data class Ready(val report: AnalyticsResponse) : AnalyticsCardState

    data class Failed(val failure: FailureText) : AnalyticsCardState
}

/** The usage half of [AnalyticsUiState.Content]. */
sealed interface UsageCardState {

    /**
     * @param usage ⛔ NULL MEANS "NOTHING METERED THIS MONTH YET", AND IT IS NOT ZERO. The server
     *   sends a JSON null when the month has no rows at all. Rendering that as a column of zeros
     *   would state a billing fact — "you sent no messages" — that was never measured, and it
     *   looks authoritative in exactly the place an operator would trust it. The screen says "no
     *   usage yet" instead. See `UsageResponse`.
     */
    data class Ready(val usage: UsageData?) : UsageCardState

    data class Failed(val failure: FailureText) : UsageCardState
}

/**
 * The usage-history half of [AnalyticsUiState.Content] — the web console's three-month trend.
 *
 * ⛔ A SEPARATE SUB-STATE FROM [UsageCardState] EVEN THOUGH BOTH COME FROM `workspace/usage`. They
 * are two requests with two response shapes (an object and an array, switched by `history=true`),
 * and either can fail while the other answers. Folding them together would mean a failed history
 * read blanking the current month's figures, which is the mistake this whole screen is built to
 * avoid.
 */
sealed interface UsageHistoryCardState {

    /**
     * @param months ⛔ EMPTY MEANS "NOTHING HAS EVER BEEN METERED", AND IT IS NOT A FAILURE. The
     *   server walks back a month at a time and appends only the months that had rows, so a
     *   workspace with no metering history at all answers `[]` on a 200 — the same distinction
     *   [UsageCardState.Ready]'s null carries for one month. It renders as a sentence, never as
     *   empty rows.
     *
     *   ⚠️ NEWEST FIRST, AS THE SERVER SENT IT, AND THE CLIENT DOES NOT RE-SORT. `month` is a
     *   `YYYY-MM` string that happens to sort correctly, which is exactly the kind of accident a
     *   re-sort would come to depend on.
     */
    data class Ready(val months: List<UsageData>) : UsageHistoryCardState

    data class Failed(val failure: FailureText) : UsageHistoryCardState
}
