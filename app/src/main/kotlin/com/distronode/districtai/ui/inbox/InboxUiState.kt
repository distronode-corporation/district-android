package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.data.ThreadCursor
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.MESSAGE_SEARCH_MIN_QUERY_LENGTH
import com.distronode.districtai.core.model.MessageSearchHit
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.UploadedMedia
import com.distronode.districtai.ui.FailureText

/** The Inbox list. */
sealed interface InboxUiState {

    data object Loading : InboxUiState

    /**
     * @param partial the server's scan window was exhausted, so older quiet threads are missing.
     *   ⚠️ Rendered as a note, never as an error — see [com.distronode.districtai.core.data.InboxPage].
     */
    data class Content(
        val conversations: List<ConversationSummary>,
        val partial: Boolean,
        val refreshing: Boolean = false,
        /**
         * Thread keys this operator has an unsent draft on.
         *
         * ⛔ KEYS ONLY, NEVER THE BODIES. A draft is author-scoped unfinished thought — half a
         * sentence, a price not yet agreed — and pulling ten thousand characters of it into a list
         * screen so a chip can be drawn would put it in every screenshot of the Inbox. The list
         * needs to know WHICH threads have one and nothing more.
         *
         * ⚠️ EMPTY IS ALSO WHAT A FAILED LOOKUP LOOKS LIKE, AND THAT IS THE RIGHT TRADE. The
         * badge read is fail-soft: if the drafts endpoint is down the conversations still render
         * without chips, because an Inbox that refuses to load over a missing badge is worse than
         * an Inbox with no badges.
         */
        val draftThreadKeys: Set<String> = emptySet(),
    ) : InboxUiState

    data class Failed(val failure: FailureText) : InboxUiState
}

/** One open thread. */
/**
 * Full-content search across the workspace, which is a different question from filtering the list.
 *
 * ⛔ IT IS NOT THE CONVERSATION LIST FILTERED. The list holds a bounded window of recent messages
 * grouped into threads; the route queries every `Message` row for the workspace, body and email
 * subject. Filtering the loaded rows instead would silently answer "no matches" for messages the
 * workspace definitely has — the failure `partial` exists to caption in the first place.
 *
 * ⛔ [truncated] IS SAID OUT LOUD RATHER THAN SWALLOWED. The server stops at its own ceiling, so
 * older matches exist and are not on screen; there is no offset to page on, so this is a note
 * rather than a control. Drawing a full page as if it were every match is the same mistake as
 * reporting a degraded region's absence as "you have no workspaces".
 */
data class InboxSearchState(
    val query: String = "",
    val running: Boolean = false,
    val hits: List<MessageSearchHit> = emptyList(),
    val truncated: Boolean = false,
    val failure: FailureText? = null,
) {

    /**
     * ⚠️ MEASURED ON THE TRIMMED QUERY, because the server trims before it measures its own
     * two-character floor. Measuring the untrimmed value would send requests the route answers
     * empty and then render "no matches" for them.
     */
    val active: Boolean get() = query.trim().length >= MESSAGE_SEARCH_MIN_QUERY_LENGTH
}

sealed interface ThreadUiState {

    data object Loading : ThreadUiState

    /**
     * @param sending a reply is in flight. ⛔ Disables the send control rather than queueing: every
     *   send is billable and the server caps a workspace at 30/min, so a second tap must not become a
     *   second charge.
     * @param sendFailure the server's own refusal, carried verbatim. Shown ALONGSIDE the thread, never
     *   instead of it — the conversation is still good, only the reply failed.
     * @param hasMore the server's `pageInfo.hasMore` for the OLDEST page loaded so far. ⚠️ It means
     *   "a source filled its window", not "there are definitely older events" — see
     *   [com.distronode.districtai.core.data.ThreadPage].
     * @param loadingOlder an older page is in flight. Unlike [sending] this guards nothing
     *   billable; it exists so a second tap does not spend a second read and, worse, merge the same
     *   page twice.
     * @param olderCursor what to send back to reach the page before [events]. ⛔ Null means there
     *   is no anchor to page from, so the affordance must not act even if [hasMore] is true.
     * @param olderFailure a failed "load older", shown at the TOP of the thread where the tap
     *   happened. ⛔ Never replaces the thread: the events already read are still correct, and the
     *   one thing that must not happen is losing the conversation the operator is reading because
     *   a page BEHIND it could not be fetched.
     */
    data class Content(
        val events: List<TimelineEvent>,
        val sending: Boolean = false,
        val sendFailure: FailureText? = null,
        /**
         * Images uploaded and waiting to go out with the next send.
         *
         * ⛔ ALREADY UPLOADED, NOT PENDING. Each entry is a `messages/media` response, so the URL
         * is real and the carrier can already fetch it. Attaching happens at PICK time rather than
         * at SEND time on purpose: an upload failure then costs the operator a retry of one image
         * instead of failing a billable send that has already spent its rate-limit slot.
         *
         * ⚠️ CAPPED AT FIVE, which is the server's ceiling on `messages/send`. A sixth would 400
         * the whole message, so the composer refuses the sixth pick instead.
         */
        val attachments: List<UploadedMedia> = emptyList(),
        /** An upload is in flight. The attach control is disabled rather than queueing picks. */
        val attaching: Boolean = false,
        /**
         * An AI draft generation is in flight.
         *
         * ⛔ DISABLES THE BUTTON, AND THAT GUARD IS ABOUT MONEY — the same reason [sending] does.
         * Every generation is a billed Vertex call capped at 20/min per workspace, so a double tap
         * must not become two of them.
         */
        val generating: Boolean = false,
        val hasMore: Boolean = false,
        val loadingOlder: Boolean = false,
        val olderCursor: ThreadCursor? = null,
        val olderFailure: FailureText? = null,
    ) : ThreadUiState

    data class Failed(val failure: FailureText) : ThreadUiState
}
