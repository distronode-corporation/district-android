package com.distronode.districtai.ui.inbox

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.distronode.districtai.R
import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.UploadedMedia
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Which conversation this is, as one value.
 *
 * ⛔ FIVE FIELDS THAT MUST AGREE, SO THEY TRAVEL TOGETHER. [threadKey] is the drafts table's key,
 * [contactId]/[address] are the timeline's selector, and [replyTarget] is where a send goes — all
 * three describe the same thread in three different vocabularies, and assembling them from
 * independent navigation arguments is how a draft gets saved against one thread and displayed in
 * another. The same reasoning that put `to` and `channel` inside [ReplyTarget] rather than leaving
 * them as two parameters.
 *
 * ⚠️ It also keeps [ThreadViewModel]'s constructor under detekt's parameter ceiling, which is a
 * consequence rather than the reason.
 */
data class ThreadTarget(
    val workspaceId: String,
    /** Exactly one of [contactId]/[address] is set — a thread with no Contact row has only an address. */
    val contactId: String?,
    val address: String?,
    /**
     * `contact:<id>` or `addr:<normalized>`.
     *
     * ⛔ THE DRAFTS ROUTE VALIDATES THIS PREFIX AND 400s ANYTHING ELSE, which is deliberate: a key
     * outside those two forms names a thread the Inbox can never show, so storing a draft under it
     * would create a row nothing could ever restore.
     */
    val threadKey: String,
    val replyTarget: ReplyTarget?,
)

/**
 * One conversation: its interleaved history, the reply composer, and the composer's saved draft.
 *
 * ⛔ THE THREAD IS ADDRESSED BY `contactId` OR BY `address`, EXACTLY ONE. A thread whose counterpart
 * never resolved to a Contact row has only an address, which is why the server accepts both — and why
 * this cannot assume an id exists.
 *
 * ⛔ THE COMPOSER'S TEXT LIVES IN [SavedStateHandle], NOT IN THE STATE OBJECT AND NOT IN THE SCREEN.
 * Three things needed it and no single previous home served all three: autosave has to read it on a
 * timer, the AI generator has to WRITE it, and it has to survive process death — which a
 * `rememberSaveable` in the composable did, but a ViewModel field would not. Keeping it out of
 * [ThreadUiState.Content] also stops every keystroke from re-emitting the whole timeline list.
 *
 * ⛔ AUTOSAVE IS A DEBOUNCE, NOT A KEYSTROKE WRITE. The drafts route allows 60 writes/min per
 * WORKSPACE and it is shared — two operators typing in the same workspace share that budget — so a
 * write per character would trip it in under a second and the 429 would be attributed to whoever
 * happened to type last.
 */
class ThreadViewModel(
    private val repository: InboxRepository,
    private val composer: ComposerRepository,
    private val target: ThreadTarget,
    role: WorkspaceRole?,
    private val attachmentReader: AttachmentReader,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /**
     * ⛔ `messages/send` excludes `viewer` server-side.
     *
     * ⚠️ ALSO FALSE WHEN THERE IS NOTHING TO REPLY TO. A thread the server marked neither
     * `canSms` nor `canEmail` has no reachable address, so offering a reply box would collect a
     * message that could only ever fail to send.
     */
    val canReply: Boolean = role.allowsMutation() && target.replyTarget != null

    /**
     * Whether to offer the attach control at all.
     *
     * ⛔ SMS ONLY, AND THE GATE IS THE CHANNEL RATHER THAN THE ROLE. Three server rules decide
     * whether attachments are accepted and this client can only see one of them:
     *   - WhatsApp is refused outright ("WhatsApp sends are text-only for now").
     *   - A workspace whose gateway resolves to SINCH is refused ("Sinch sends text-only SMS").
     *     ⚠️ THAT ONE IS NOT KNOWABLE HERE — the provider is resolved server-side at send time from
     *     the workspace's messaging config, so a Sinch workspace still sees the attach button and
     *     learns the truth from the server's own refusal text. Hiding it would need a config read
     *     this screen does not do; guessing would hide it from Twilio workspaces too.
     *   - The email branch never looks at `mediaUrls` at all — it sends `text` to Postmark — so an
     *     attachment on an email thread would upload, cost a database write, and silently not be
     *     delivered. That is the worst of the three failures, and it is the one this gate prevents.
     *
     * The web composer makes the same call from `capabilities.mms && !isWhatsApp`; this is the
     * client-knowable half of it.
     */
    val canAttach: Boolean = canReply && target.replyTarget?.channel == CHANNEL_SMS

    /**
     * The composer's text.
     *
     * ⚠️ Read from [SavedStateHandle] so a process death mid-reply restores what was typed, not
     * merely what the last autosave managed to persist. The server draft is the cross-DEVICE
     * story; this is the same-device one, and they are not substitutes: autosave debounces, so the
     * last two seconds of typing exist only here.
     */
    val composerText: StateFlow<String> = savedState.getStateFlow(KEY_COMPOSER, "")

    private val _state = MutableStateFlow<ThreadUiState>(ThreadUiState.Loading)
    val state: StateFlow<ThreadUiState> = _state.asStateFlow()

    /** The pending debounced save. Cancelled and replaced on every edit. */
    private var autosaveJob: Job? = null

    init {
        load()
        restoreDraft()
    }

    /**
     * Read the NEWEST window of the thread.
     *
     * ⚠️ THIS DISCARDS ANY OLDER PAGES ALREADY EXPANDED, and that is the right trade rather than an
     * oversight. It is the retry path and the post-send refresh, both of which exist to show the
     * operator the server's current truth; re-walking the cursor to rebuild the expansion would
     * spend N requests to restore scrollback nobody asked for. The affordance is still there to
     * expand again.
     */
    fun load() {
        _state.value = ThreadUiState.Loading
        viewModelScope.launch {
            when (val result = repository.thread(target.workspaceId, target.contactId, target.address)) {
                is ApiResult.Success ->
                    _state.value = ThreadUiState.Content(
                        events = result.value.events,
                        hasMore = result.value.hasMore,
                        olderCursor = result.value.cursor,
                    )
                is ApiResult.Failure ->
                    _state.value = ThreadUiState.Failed(result.toFailureText())
            }
        }
    }

    /**
     * Expand the thread backwards by one page.
     *
     * ⛔ MERGED WITH A DEDUPE BY EVENT ID, BECAUSE OVERLAP IS PART OF THE CONTRACT. The server
     * windows messages and calls independently and merges afterwards, so one cursor point can sit
     * inside one source's window and past the other's — sending it back re-reads rows this client
     * already holds from the less dense source. That is the server's stated trade against a
     * per-source cursor pair. Appending blind would show the operator the same message twice, and
     * `LazyColumn` keys on the event id, so a duplicate key is also a crash-shaped render bug.
     *
     * ⛔ THE COPY ALREADY ON SCREEN WINS A COLLISION. Both are the same row from the same server, so
     * neither is fresher — and replacing it would recompose a bubble the operator is looking at for
     * no visible change.
     *
     * ⚠️ RE-SORTED AFTER THE MERGE RATHER THAN CONCATENATED. Concatenation is only correct if every
     * event of the older page precedes every event held — which is what the cursor promises, but it
     * is a promise about a server the client cannot see, and the cost of being wrong is a
     * conversation that reads out of order. The comparator is the repository's, id tiebreak
     * included, so the merged list has the same total order the server itself used.
     *
     * ⚠️ THE COMPOSER IS UNTOUCHED. Its text lives in [SavedStateHandle] and never in this state,
     * and the attachments ride through on the `copy` — an expansion of history must not disturb a
     * reply half-written.
     */
    fun loadOlder() {
        val current = _state.value
        if (current !is ThreadUiState.Content || current.loadingOlder || !current.hasMore) return
        val cursor = current.olderCursor ?: return

        _state.value = current.copy(loadingOlder = true, olderFailure = null)

        viewModelScope.launch {
            val result = repository.thread(
                workspaceId = target.workspaceId,
                contactId = target.contactId,
                address = target.address,
                olderThan = cursor,
            )
            when (result) {
                is ApiResult.Success -> _state.withContent { latest ->
                    val merged = LinkedHashMap<String, TimelineEvent>(
                        latest.events.size + result.value.events.size,
                    )
                    latest.events.forEach { merged[it.id] = it }
                    result.value.events.forEach { merged.putIfAbsent(it.id, it) }
                    latest.copy(
                        events = merged.values.sortedWith(compareBy({ it.timestamp }, { it.id })),
                        loadingOlder = false,
                        // ⚠️ ADOPTED FROM THE NEW PAGE, NOT ANDed WITH THE OLD ONE. An empty page
                        // reports `hasMore = false` and a null cursor, which is exactly the end of
                        // the thread — the server's `hasMore` is allowed to have been optimistic.
                        hasMore = result.value.hasMore,
                        olderCursor = result.value.cursor,
                    )
                }
                // ⛔ THE THREAD STAYS ON SCREEN. Failing to read a page BEHIND the conversation is
                // no reason to take the conversation away; the failure sits at the top where the
                // tap happened and the affordance remains, so the retry is the same control.
                is ApiResult.Failure -> _state.withContent {
                    it.copy(loadingOlder = false, olderFailure = result.toFailureText())
                }
            }
        }
    }

    /**
     * Record an edit and schedule the autosave.
     *
     * ⛔ A BLANK BOX SENDS DELETE, NEVER A PUT WITH AN EMPTY BODY. The server answers 400
     * `code: "empty_body"` to the latter, deliberately, because a blank draft is the ABSENCE of a
     * draft rather than an empty one — and a stored blank row would make the Inbox badge count a
     * thread with nothing to restore. The two writes share one rate-limit bucket, so getting this
     * wrong also burns a slot to be refused.
     *
     * ⚠️ THE DEBOUNCE IS RESTARTED, NOT EXTENDED FROM THE FIRST EDIT. A sustained typist would
     * otherwise never save at all.
     */
    fun onComposerChange(text: String) {
        savedState[KEY_COMPOSER] = text
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            if (text.isBlank()) {
                composer.deleteDraft(target.workspaceId, target.threadKey)
            } else {
                composer.saveDraft(
                    workspaceId = target.workspaceId,
                    threadKey = target.threadKey,
                    body = text,
                    // ⚠️ Read at FIRE time, not at edit time: an image attached during the
                    // debounce belongs on the draft the timer is about to write.
                    mediaUrls = (_state.value as? ThreadUiState.Content)
                        ?.attachments?.map { it.url }.orEmpty(),
                )
            }
        }
    }

    /**
     * Send a reply, with whatever is attached.
     *
     * ⛔ GUARDED AGAINST A SECOND TAP WHILE ONE IS IN FLIGHT, AND THAT GUARD IS ABOUT MONEY. Every send
     * is billable SMS/MMS segments or an email, and the server caps a workspace at 30/min — so a
     * double tap must not become two charges and two messages the customer receives twice.
     *
     * ⛔ REFUSES AN EMPTY BODY EVEN WITH AN IMAGE ATTACHED. `messages/send` guards on
     * `!workspaceId || !to || !body` BEFORE it looks at `mediaUrls`, so a picture with no caption
     * is a 400 "Missing required parameters" rather than a message. Learning that from the server
     * would spend a round trip and present as a fault.
     *
     * ⚠️ ON SUCCESS THE THREAD IS RE-READ RATHER THAN APPENDED TO LOCALLY. The sent row gets its id,
     * status and timestamp from the server, and a locally-invented bubble would show a delivery status
     * this client made up — the one thing an operator is actually checking after a send.
     *
     * ⚠️ AND THE SERVER DRAFT IS DELETED, NOT LEFT TO EXPIRE. A draft that survives its own send is
     * a message the operator sends twice on their next device.
     */
    fun send(body: String) {
        if (!canReply) return
        val current = _state.value
        if (current !is ThreadUiState.Content || current.sending) return
        if (body.isBlank()) return

        val recipient = target.replyTarget ?: return
        val media = current.attachments.map { it.url }

        _state.value = current.copy(sending = true, sendFailure = null)

        viewModelScope.launch {
            val result = repository.send(
                workspaceId = target.workspaceId,
                to = recipient.to,
                body = body,
                channel = recipient.channel,
                mediaUrls = media,
            )
            when (result) {
                is ApiResult.Success -> {
                    // ⛔ THE PENDING AUTOSAVE IS CANCELLED FIRST. Without this a debounce armed by
                    // the last keystroke fires AFTER the delete and re-creates the draft that was
                    // just sent, which is the duplicate-reply bug in slow motion.
                    autosaveJob?.cancel()
                    savedState[KEY_COMPOSER] = ""
                    composer.deleteDraft(target.workspaceId, target.threadKey)
                    load()
                }
                is ApiResult.Failure -> {
                    val latest = _state.value
                    // ⚠️ The failure is attached to the CONTENT state, so the conversation stays on
                    // screen. The thread is still good; only the reply failed, and blanking it would
                    // lose what the operator was reading. The attachments stay too — they are
                    // already uploaded, and dropping them would make a retry re-pick every image.
                    if (latest is ThreadUiState.Content) {
                        _state.value = latest.copy(
                            sending = false,
                            sendFailure = result.toFailureText(),
                        )
                    }
                }
            }
        }
    }

    /** Dismiss a send (or attach, or generation) failure without disturbing the thread. */
    fun dismissSendFailure() {
        val current = _state.value
        if (current is ThreadUiState.Content) {
            _state.value = current.copy(sendFailure = null)
        }
    }

    /**
     * Read a picked image and upload it.
     *
     * ⛔ THE TYPE AND SIZE ARE CHECKED BEFORE THE UPLOAD, NOT AFTER. A 5MB body spent on a metered
     * connection to be told the format is wrong is a real cost to the operator, and the local
     * refusal can name the actual rule. The server re-checks both; this is a shortcut, never the
     * boundary.
     *
     * ⚠️ EVERY REFUSAL LANDS IN `sendFailure` RATHER THAN IN A SECOND CHANNEL. Attach, generate and
     * send all fail in the same place on screen — the strip above the composer — and giving each
     * its own slot would mean three dismiss controls for one visual location.
     */
    fun attach(uri: String) {
        if (!canAttach) return
        val current = _state.value
        if (current !is ThreadUiState.Content || current.attaching) return
        if (current.attachments.size >= ComposerRepository.MAX_ATTACHMENTS) {
            _state.value = current.copy(sendFailure = refusal(R.string.thread_attach_too_many))
            return
        }

        _state.value = current.copy(attaching = true, sendFailure = null)

        viewModelScope.launch {
            val picked = attachmentReader.read(uri)
            // ⚠️ THREE DISTINCT REFUSALS, NOT ONE. "could not be read", "wrong format" and "too
            // big" call for three different next actions from the operator, and collapsing them
            // into "attachment failed" would leave them re-picking the same unsupported file.
            val rejection = when {
                picked == null -> R.string.thread_attach_unreadable
                !picked.isSupportedType() -> R.string.thread_attach_unsupported
                !picked.isWithinSizeLimit() -> R.string.thread_attach_too_large
                else -> null
            }
            if (rejection == null && picked != null) {
                upload(picked)
            } else if (rejection != null) {
                _state.withContent { it.copy(attaching = false, sendFailure = refusal(rejection)) }
            }
        }
    }

    /**
     * Drop an attachment before it is sent.
     *
     * ⚠️ THE UPLOADED ROW IS NOT DELETED SERVER-SIDE, AND THERE IS NO ROUTE THAT WOULD. It becomes
     * an orphan `MessageMedia` row that nothing references — bounded at 5MB, invisible, and
     * cheaper than inventing a delete endpoint whose only caller would be this button.
     */
    fun removeAttachment(url: String) {
        val current = _state.value
        if (current is ThreadUiState.Content) {
            _state.value = current.copy(attachments = current.attachments.filterNot { it.url == url })
        }
    }

    /**
     * Ask the model for a reply and put it in the composer.
     *
     * ⛔ BILLABLE AND NON-IDEMPOTENT — one Vertex generation per tap, 20/min per workspace. Guarded
     * against a double tap for the same reason [send] is.
     *
     * ⛔ THE GENERATED TEXT IS TREATED EXACTLY AS IF THE OPERATOR HAD TYPED IT: it goes through
     * [onComposerChange], so it autosaves like anything else. The alternative considered was to
     * hold it un-persisted until the operator edits it, on the reasoning that text they never
     * touched should not overwrite a draft they wrote on another device. That was rejected because
     * it makes the composer lie: the box would show text that a process death would silently
     * discard, and "what is on screen is what is saved" is worth more than protecting a draft the
     * operator has already chosen to replace by tapping Draft.
     *
     * ⚠️ AN EMPTY GENERATION IS DISCARDED RATHER THAN WRITTEN. The route answers `""` when the
     * model returns nothing, and blanking a composer the operator had typed in would be the single
     * most destructive thing this button could do.
     */
    fun generateDraft() {
        if (!canReply) return
        val current = _state.value
        if (current !is ThreadUiState.Content || current.generating) return

        _state.value = current.copy(generating = true, sendFailure = null)

        viewModelScope.launch {
            when (val result = composer.generateDraft(target.workspaceId, target.contactId, target.address)) {
                is ApiResult.Success -> {
                    _state.withContent { it.copy(generating = false) }
                    if (result.value.isNotBlank()) onComposerChange(result.value)
                }
                is ApiResult.Failure -> {
                    _state.withContent { it.copy(generating = false, sendFailure = result.toFailureText()) }
                }
            }
        }
    }

    /**
     * Adopt the server's draft on open.
     *
     * ⛔ THE LOCAL BOX WINS WHEN IT IS NOT EMPTY, AND THE RULE IS DELIBERATELY THAT SIMPLE. A
     * timestamp comparison was considered and rejected: [composerText] has no timestamp (it is
     * whatever survived process death, which may be seconds or days old), so the comparison would
     * have to invent one, and inventing one on the wrong side silently DESTROYS typed text. The
     * asymmetry is the point — adopting into an empty box can only add, while overwriting a
     * non-empty box can only lose. The next autosave then makes the server agree.
     *
     * ⚠️ ATTACHMENTS COME BACK WITH IT. A draft saved with two images restores as text plus two
     * chips; restoring the text alone would send a message the operator believed had pictures on it.
     */
    private fun restoreDraft() {
        viewModelScope.launch {
            val result = composer.loadDraft(target.workspaceId, target.threadKey)
            val draft = (result as? ApiResult.Success)?.value ?: return@launch
            if (composerText.value.isNotBlank()) return@launch
            savedState[KEY_COMPOSER] = draft.body
            if (draft.mediaUrls.isNotEmpty()) {
                // ⚠️ A RESTORED DRAFT CARRIES URLs AND NOTHING ELSE — the drafts row stores a list
                // of strings, not media rows. The id, mime type and size are unknown and unused:
                // the chip renders from the URL and the send route takes the URL. Leaving them at
                // their defaults is honest because nothing reads them.
                _state.withContent { content ->
                    content.copy(attachments = draft.mediaUrls.map { UploadedMedia(url = it) })
                }
            }
        }
    }

    private suspend fun upload(picked: PickedAttachment) {
        val result = composer.uploadMedia(
            workspaceId = target.workspaceId,
            fileName = picked.fileName,
            mimeType = picked.mimeType,
            bytes = picked.bytes,
        )
        when (result) {
            is ApiResult.Success ->
                _state.withContent { it.copy(attaching = false, attachments = it.attachments + result.value) }
            is ApiResult.Failure ->
                _state.withContent { it.copy(attaching = false, sendFailure = result.toFailureText()) }
        }
    }

    class Factory(
        private val repository: InboxRepository,
        private val composer: ComposerRepository,
        private val target: ThreadTarget,
        private val role: WorkspaceRole?,
        private val attachmentReader: AttachmentReader,
    ) : ViewModelProvider.Factory {
        /**
         * ⛔ THE `CreationExtras` OVERLOAD, NOT `create(modelClass)`, BECAUSE THE SAVED-STATE HANDLE
         * ONLY EXISTS THERE. `createSavedStateHandle()` reads the owner out of the extras, which
         * `androidx.lifecycle.viewmodel.compose.viewModel(factory = …)` always supplies from the
         * NavBackStackEntry. Implementing the one-argument overload instead would compile, run, and
         * hand every ThreadViewModel an EMPTY handle — the composer would then lose its text on
         * process death with nothing to indicate why.
         */
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            @Suppress("UNCHECKED_CAST")
            return ThreadViewModel(
                repository,
                composer,
                target,
                role,
                attachmentReader,
                extras.createSavedStateHandle(),
            ) as T
        }
    }

    private companion object {
        const val KEY_COMPOSER = "thread-composer-text"

        /**
         * ⚠️ TWO SECONDS, CHOSEN AGAINST THE SERVER'S 60 WRITES/MIN PER WORKSPACE RATHER THAN
         * AGAINST TYPING FEEL. That budget is shared across every operator in the workspace, so a
         * 200ms debounce would let one fast typist consume it alone and 429 a colleague. Two
         * seconds bounds one composer to 30/min at absolute worst, and in practice far less because
         * the timer restarts on each keystroke.
         */
        const val AUTOSAVE_DEBOUNCE_MS = 2_000L
    }
}

/**
 * A client-authored refusal.
 *
 * ⛔ `UiText.Resource`, NOT A HARDCODED STRING. A ViewModel has no `Context`, so text held here
 * as a `String` is untranslatable English in an app that declares `supportsRtl="true"` — the
 * distinction [FailureText.message] exists to preserve. The SERVER's own refusals stay literal
 * because only it knows them.
 *
 * ⚠️ `retryable = false` ON ALL OF THEM: re-running the identical pick is refused identically.
 * The operator's next action is to choose a different image, not to press try-again.
 *
 * ⚠️ A TOP-LEVEL FUNCTION rather than a private method, so it does not count against
 * [ThreadViewModel]'s detekt function ceiling for what is one expression.
 */
private fun refusal(resourceId: Int) =
    FailureText(message = UiText.Resource(resourceId), retryable = false)

/**
 * Apply [block] only while the thread is loaded; a failure state has no composer to update.
 *
 * ⚠️ A TOP-LEVEL EXTENSION rather than a private method, for exactly the reason [refusal] is one:
 * it reads no state of [ThreadViewModel] beyond the flow it is called on, and that class sits on
 * detekt's function ceiling — which `loadOlder` is what pushed it to. Moving a pure helper out is
 * the honest answer to that; raising the threshold would be the other one.
 */
private fun MutableStateFlow<ThreadUiState>.withContent(
    block: (ThreadUiState.Content) -> ThreadUiState.Content,
) {
    val current = value
    if (current is ThreadUiState.Content) value = block(current)
}
