package com.distronode.districtai.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.UploadedMedia
import com.distronode.districtai.ui.FailureState
import com.distronode.districtai.ui.resolve

/**
 * One conversation, with a reply box.
 *
 * ⛔ THIS RENDERS CALLS AS WELL AS MESSAGES, AND FILTERING THEM OUT WOULD BE A BUG. The server's
 * timeline interleaves SMS, email and calls because an operator reading a thread needs to see that the
 * customer PHONED between two texts — dropping the call leaves an unexplained gap and, worse, hides a
 * missed call, which is the one event in a thread that needs acting on.
 */
@Composable
fun ThreadScreen(
    title: String,
    state: ThreadUiState,
    canReply: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onDismissSendFailure: () -> Unit,
    /** Expand the thread backwards by one page. See `ThreadViewModel.loadOlder`. */
    onLoadOlder: () -> Unit,
    composer: ComposerHandlers,
) {
    // ⚠️ NO `modifier` PARAMETER: the one caller (the nav graph) never sized or placed this screen.
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = THREAD_ROOT_DESCRIPTION },
        topBar = { DistrictTopBar(title = title, onBack = onBack) },
    ) { inset ->
        Box(modifier = inset.fillMaxSize()) {
            when (state) {
                ThreadUiState.Loading -> ThreadLoading()
                is ThreadUiState.Failed -> FailureState(
                    failure = state.failure,
                    onRetry = onRetry,
                    onSignIn = onSignIn,
                    description = THREAD_FAILURE_DESCRIPTION,
                )
                is ThreadUiState.Content ->
                    Loaded(state, canReply, onSend, onDismissSendFailure, onLoadOlder, composer)
            }
        }
    }
}

/**
 * The composer's collaborators, bundled.
 *
 * ⛔ ONE PARAMETER RATHER THAN SEVEN, AND NOT ONLY FOR TIDINESS. [text] and [onTextChange] are a
 * hoisted pair that must come from the SAME owner: the ViewModel holds the text in a
 * `SavedStateHandle` so autosave can read it and the AI generator can write it, and a screen that
 * accepted the value from one place and the callback from another could render text nothing was
 * persisting. Keeping them in one value makes that mismatch unconstructible — the same reasoning
 * that put `to` and `channel` inside `ReplyTarget`.
 *
 * ⚠️ [canAttach] IS THE SERVER'S SMS-ONLY RULE, arriving already decided. See
 * `ThreadViewModel.canAttach` for why an email thread must not offer it and why a Sinch workspace
 * still does.
 */
data class ComposerHandlers(
    val text: String,
    val onTextChange: (String) -> Unit,
    val canAttach: Boolean,
    /** Opens the system photo picker. Owned by the caller, because it needs an Activity. */
    val onAttach: () -> Unit,
    val onRemoveAttachment: (String) -> Unit,
    /** ⛔ One billed Vertex generation per invocation. See `ThreadViewModel.generateDraft`. */
    val onGenerateDraft: () -> Unit,
    val imageLoader: MediaImageLoader,
    /** Opens one attachment in the browser. See [MediaImage]. */
    val onOpenMedia: (String) -> Unit,
)

@Composable
private fun Loaded(
    state: ThreadUiState.Content,
    canReply: Boolean,
    onSend: (String) -> Unit,
    onDismissSendFailure: () -> Unit,
    onLoadOlder: () -> Unit,
    composer: ComposerHandlers,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.events.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.thread_empty_title),
                    body = stringResource(R.string.thread_empty),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                // ⛔ THE FIRST ITEM, AND IT MUST BE KEYED LIKE THE REST. LazyColumn holds its
                // scroll anchor by the KEY of the first visible item, so prepending an older page
                // to a keyed list leaves the operator looking at the same message rather than
                // jumping. An unkeyed header would break that for the item next to it.
                if (state.hasMore || state.olderFailure != null) {
                    item(key = LOAD_OLDER_KEY) {
                        LoadOlder(state, onLoadOlder)
                    }
                }
                items(state.events, key = { it.id }) { event ->
                    ContentContainer {
                        if (event.isMessage) {
                            MessageBubble(event, composer.imageLoader, composer.onOpenMedia)
                        } else {
                            CallEvent(event)
                        }
                    }
                }
            }
        }

        state.sendFailure?.let { failure ->
            ContentContainer {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DistrictTheme.spacing.gutter)
                        .semantics { contentDescription = THREAD_SEND_FAILURE_DESCRIPTION },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
                ) {
                    Text(
                        text = failure.message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.destructive,
                        modifier = Modifier.weight(1f),
                    )
                    DistrictButton(
                        text = stringResource(R.string.thread_dismiss),
                        onClick = onDismissSendFailure,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                    )
                }
            }
        }

        // ⚠️ Offered only to a role the server would admit. `messages/send` excludes `viewer`.
        if (canReply) {
            AttachmentChips(state.attachments, composer.onRemoveAttachment)
            ReplyBox(state, onSend, composer)
        }
    }
}

/**
 * "Load older" — the top of the thread, where the history keeps going.
 *
 * ⚠️ A LABELLED BUTTON RATHER THAN AN INFINITE-SCROLL TRIGGER, and deliberately so. Reaching the
 * top of a conversation is a place an operator arrives by scrolling UP through a reply they are
 * writing about; auto-fetching there would move the content under their finger every time they
 * overshot. An explicit tap also makes the request attributable when a thread is slow.
 *
 * ⚠️ THE LABEL CARRIES THE PROGRESS STATE, matching Attach/Draft/Send in the composer below. The
 * control is disabled while the page is in flight, so a spinner beside it would leave the button
 * unexplained; the changed word IS the explanation.
 *
 * ⛔ A FAILURE KEEPS THE BUTTON. The events already read are still correct, so the honest shape is
 * "that did not work, here is the same control again" rather than a second retry affordance next
 * to a dead one — and the row is rendered when [ThreadUiState.Content.olderFailure] is set even if
 * `hasMore` has since gone false, so a failure can never be silently swallowed.
 */
@Composable
private fun LoadOlder(state: ThreadUiState.Content, onLoadOlder: () -> Unit) {
    ContentContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = DistrictTheme.spacing.gutter,
                    vertical = DistrictTheme.spacing.tight,
                )
                .semantics { contentDescription = THREAD_LOAD_OLDER_ROW_DESCRIPTION },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            state.olderFailure?.let { failure ->
                Text(
                    text = failure.message.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.destructive,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = DistrictTheme.spacing.hairline)
                        .semantics { contentDescription = THREAD_LOAD_OLDER_FAILURE_DESCRIPTION },
                )
            }
            DistrictButton(
                text = stringResource(
                    if (state.loadingOlder) {
                        R.string.thread_load_older_loading
                    } else {
                        R.string.thread_load_older
                    },
                ),
                onClick = onLoadOlder,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = !state.loadingOlder,
                modifier = Modifier.semantics { contentDescription = THREAD_LOAD_OLDER_DESCRIPTION },
            )
        }
    }
}

/**
 * The row of images waiting to go out with the next send.
 *
 * ⚠️ ALREADY UPLOADED, so removing one costs nothing server-side and the send carries only URLs.
 * See [ThreadUiState.Content.attachments] for why the upload happens at pick time.
 */
@Composable
private fun AttachmentChips(attachments: List<UploadedMedia>, onRemove: (String) -> Unit) {
    if (attachments.isEmpty()) return
    ContentContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DistrictTheme.spacing.gutter)
                .semantics { contentDescription = THREAD_ATTACHMENTS_DESCRIPTION },
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            attachments.forEach { media ->
                DistrictButton(
                    // ⚠️ The URL's last segment, not the whole URL: the id is enough to tell two
                    // attachments apart and the full absolute URL would not fit on a phone.
                    text = media.url.substringAfterLast('/').take(CHIP_LABEL_CHARS),
                    onClick = { onRemove(media.url) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.semantics {
                        contentDescription = THREAD_ATTACHMENT_CHIP_DESCRIPTION
                    },
                )
            }
        }
    }
}

/**
 * ⛔ THE TEXT IS HOISTED, NOT `rememberSaveable`-D HERE ANY MORE, AND THAT IS A CAPABILITY CHANGE
 * RATHER THAN A REFACTOR. It used to live in this composable, which survived process death but
 * could not be READ by autosave or WRITTEN by the AI draft button — both of which now need it. It
 * lives in the ViewModel's `SavedStateHandle`, which does all three. Do not reintroduce a local
 * copy: a second source of truth here means the box shows one thing and the server stores another.
 */
@Composable
private fun ReplyBox(
    state: ThreadUiState.Content,
    onSend: (String) -> Unit,
    composer: ComposerHandlers,
) {
    ContentContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DistrictTheme.spacing.gutter),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            OutlinedTextField(
                value = composer.text,
                onValueChange = composer.onTextChange,
                label = { Eyebrow(stringResource(R.string.thread_reply_label)) },
                enabled = !state.sending,
                maxLines = REPLY_MAX_LINES,
                colors = districtFieldColors(),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = THREAD_REPLY_FIELD_DESCRIPTION },
            )
            ComposerActions(state, onSend, composer)
        }
    }
}

/**
 * Attach, draft and send.
 *
 * ⛔ TWO OF THESE THREE CONTROLS SPEND MONEY AND BOTH ARE DISABLED WHILE IN FLIGHT. A send is
 * billable SMS/MMS segments capped at 30/min per workspace; an AI draft is a Vertex generation
 * capped at 20/min. A double tap on either must not become two of them.
 *
 * ⚠️ ATTACH IS HIDDEN, NOT DISABLED, WHEN THE CHANNEL CANNOT CARRY IT. A greyed-out button invites
 * the operator to work out why; on an email thread the honest answer is that attachments are not a
 * thing this channel does at all. See `ThreadViewModel.canAttach`.
 */
@Composable
private fun ComposerActions(
    state: ThreadUiState.Content,
    onSend: (String) -> Unit,
    composer: ComposerHandlers,
) {
    Column(horizontalAlignment = Alignment.End) {
        if (composer.canAttach) {
            DistrictButton(
                text = stringResource(
                    if (state.attaching) R.string.thread_attaching else R.string.thread_attach,
                ),
                onClick = composer.onAttach,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = !state.attaching && !state.sending,
                modifier = Modifier.semantics { contentDescription = THREAD_ATTACH_DESCRIPTION },
            )
        }
        DistrictButton(
            text = stringResource(
                if (state.generating) R.string.thread_ai_drafting else R.string.thread_ai_draft,
            ),
            onClick = composer.onGenerateDraft,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            enabled = !state.generating && !state.sending,
            modifier = Modifier.semantics { contentDescription = THREAD_AI_DRAFT_DESCRIPTION },
        )
        // ⛔ DISABLED WHILE A SEND IS IN FLIGHT, AND THAT GUARD IS ABOUT MONEY. Every send is
        // billable and the server caps a workspace at 30/min, so a double tap must not become two
        // charges and a message the customer receives twice.
        //
        // ⛔ AND DISABLED ON BLANK TEXT EVEN WITH IMAGES ATTACHED. `messages/send` guards on
        // `!body` BEFORE it looks at `mediaUrls`, so a picture with no caption is a 400 rather
        // than a message — enabling the button would collect a send that could only fail.
        DistrictButton(
            text = stringResource(
                if (state.sending) R.string.thread_sending else R.string.thread_send,
            ),
            onClick = { onSend(composer.text) },
            enabled = !state.sending && composer.text.isNotBlank(),
            modifier = Modifier.semantics { contentDescription = THREAD_SEND_DESCRIPTION },
        )
    }
}

/**
 * ⚠️ ALIGNMENT CARRIES DIRECTION, and the tone carries channel rather than direction. An outbound
 * message is the workspace talking, so it sits right in the accent; inbound is the customer and sits
 * left on the muted fill. Using the accent for BOTH would make the conversation unreadable at a glance,
 * which is the only thing alignment is for.
 */
@Composable
private fun MessageBubble(
    event: TimelineEvent,
    imageLoader: MediaImageLoader,
    onOpenMedia: (String) -> Unit,
) {
    val outbound = event.direction == OUTBOUND
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = DistrictTheme.spacing.gutter,
                vertical = DistrictTheme.spacing.hairline,
            ),
        horizontalArrangement = if (outbound) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .background(
                    color = if (outbound) {
                        DistrictTheme.colors.district.copy(alpha = BUBBLE_ACCENT_ALPHA)
                    } else {
                        DistrictTheme.colors.muted
                    },
                    shape = RoundedCornerShape(BUBBLE_RADIUS),
                )
                .padding(DistrictTheme.spacing.row),
        ) {
            // Email only. An SMS has no subject and printing an empty line would look like a bug.
            event.subject?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmall,
                    color = DistrictTheme.colors.foreground,
                )
            }
            Text(
                text = event.body,
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
            )
            Row(
                modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // Server-formatted in the OPERATOR's timezone; never reformatted locally.
                    text = event.timestamp,
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                )
                // ⚠️ Status only on OUTBOUND. An inbound message's "status" is a receipt artefact and
                // means nothing to the operator; on an outbound one it is the delivery answer they are
                // actually looking for after sending.
                if (outbound && event.status.isNotBlank()) {
                    DistrictBadge(text = event.status, tone = toneForMessageStatus(event.status))
                }
                if (event.mediaUrls.isNotEmpty()) {
                    DistrictBadge(
                        // ⚠️ A PLURAL, not a formatted string: "1 attachments" is the kind of detail
                        // that makes a product look unfinished, and other languages have more than two
                        // plural forms.
                        text = pluralStringResource(
                            R.plurals.thread_attachments,
                            event.mediaUrls.size,
                            event.mediaUrls.size,
                        ),
                        tone = Tone.Info,
                    )
                }
            }
            // ⚠️ THE BADGE STAYS ALONGSIDE THE THUMBNAILS RATHER THAN BEING REPLACED BY THEM. A
            // thumbnail that has not loaded (or cannot) leaves no indication that the message HAD
            // an attachment, which is the one fact the operator must not lose.
            if (event.mediaUrls.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
                    horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                ) {
                    event.mediaUrls.forEach { url ->
                        MediaImage(url = url, loader = imageLoader, onOpen = onOpenMedia)
                    }
                }
            }
        }
    }
}

/**
 * A call, rendered as a centred event rather than a bubble.
 *
 * ⛔ NOT A BUBBLE, BECAUSE A CALL IS NOT SOMETHING EITHER SIDE *SAID*. And a MISSED call has no side
 * at all — `direction` is a three-state value here (`inbound`/`outbound`/`missed`), so putting it left
 * or right would assert something untrue about who spoke.
 */
@Composable
private fun CallEvent(event: TimelineEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = DistrictTheme.spacing.gutter,
                vertical = DistrictTheme.spacing.tight,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DistrictBadge(
            text = stringResource(
                if (event.isMissedCall) R.string.thread_call_missed else R.string.thread_call,
            ),
            tone = if (event.isMissedCall) Tone.Danger else Tone.Neutral,
            modifier = Modifier.semantics { contentDescription = THREAD_CALL_DESCRIPTION },
        )
        Text(
            text = event.timestamp,
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
        )
        // The voice agent's own summary, when there is one. Far more useful in a thread than a
        // duration, and it is the reason a call belongs here at all.
        event.summary?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
            )
        }
    }
}

@Composable
private fun ThreadLoading() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = THREAD_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_BUBBLES) { SkeletonBlock(height = SKELETON_BUBBLE_HEIGHT) }
        }
    }
}

private const val OUTBOUND = "outbound"

/**
 * The "load older" header's LazyColumn key.
 *
 * ⛔ A CONSTANT THAT CANNOT COLLIDE WITH AN EVENT ID. Every other item is keyed by its server id,
 * and a duplicate key inside one LazyColumn is a hard crash rather than a rendering oddity. Server
 * ids are cuids, so a literal with a space in it can never be one.
 */
private const val LOAD_OLDER_KEY = "district-thread load-older"

/** Enough of an attachment id to tell two chips apart on a phone. */
private const val CHIP_LABEL_CHARS = 12
private const val REPLY_MAX_LINES = 5
private const val SKELETON_BUBBLES = 6
private val SKELETON_BUBBLE_HEIGHT = 48.dp
private val BUBBLE_MAX_WIDTH = 420.dp
private val BUBBLE_RADIUS = 12.dp
private const val BUBBLE_ACCENT_ALPHA = 0.12f

/** Stable handles for tests. */
const val THREAD_ROOT_DESCRIPTION: String = "district-thread-root"
const val THREAD_LOADING_DESCRIPTION: String = "district-thread-loading"
const val THREAD_FAILURE_DESCRIPTION: String = "district-thread-failure"
const val THREAD_SEND_FAILURE_DESCRIPTION: String = "district-thread-send-failure"
const val THREAD_REPLY_FIELD_DESCRIPTION: String = "district-thread-reply-field"
const val THREAD_SEND_DESCRIPTION: String = "district-thread-send"
const val THREAD_CALL_DESCRIPTION: String = "district-thread-call"
const val THREAD_ATTACH_DESCRIPTION: String = "district-thread-attach"
const val THREAD_ATTACHMENTS_DESCRIPTION: String = "district-thread-attachments"
const val THREAD_ATTACHMENT_CHIP_DESCRIPTION: String = "district-thread-attachment-chip"
const val THREAD_AI_DRAFT_DESCRIPTION: String = "district-thread-ai-draft"
const val THREAD_LOAD_OLDER_ROW_DESCRIPTION: String = "district-thread-load-older-row"
const val THREAD_LOAD_OLDER_DESCRIPTION: String = "district-thread-load-older"
const val THREAD_LOAD_OLDER_FAILURE_DESCRIPTION: String = "district-thread-load-older-failure"
