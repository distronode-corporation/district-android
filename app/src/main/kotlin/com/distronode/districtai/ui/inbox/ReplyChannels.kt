package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.WhatsAppRepository
import com.distronode.districtai.core.model.CHANNEL_INSTAGRAM
import com.distronode.districtai.core.model.CHANNEL_MESSENGER
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.CHANNEL_WHATSAPP
import com.distronode.districtai.core.model.META_WINDOW_CLOSED
import com.distronode.districtai.core.model.META_WINDOW_HUMAN_AGENT
import com.distronode.districtai.core.model.META_WINDOW_OPEN
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.fillWhatsAppTemplate
import com.distronode.districtai.core.model.jsTrim
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a thread's composer talks to.
 *
 * ⚠️ ONE VALUE SO [ThreadViewModel] STAYS UNDER detekt's constructor ceiling, which the WhatsApp
 * reads would otherwise have crossed.
 */
data class ThreadServices(
    val inbox: InboxRepository,
    val composer: ComposerRepository,
    val whatsApp: WhatsAppRepository,
)

/** What the composer knows about WhatsApp's 24-hour window for the chosen number. */
sealed interface WhatsAppWindowState {

    /** The chosen channel is not WhatsApp. */
    data object NotWhatsApp : WhatsAppWindowState

    /** The read is on its way. ⚠️ Free text is not held up for it; the send route checks again. */
    data object Checking : WhatsAppWindowState

    /**
     * Free text may go. [senderId] is the sender the read named, sent as `whatsappSenderId`; null
     * when the read failed, and the route then picks the sender itself.
     */
    data class Open(val senderId: String?) : WhatsAppWindowState

    /** Only an approved template may go now, from [senderId]. */
    data class Closed(val senderId: String?, val templates: WhatsAppTemplatesState) : WhatsAppWindowState
}

/** The closed window's templates. */
sealed interface WhatsAppTemplatesState {
    data object Loading : WhatsAppTemplatesState
    data object Failed : WhatsAppTemplatesState

    /** The sendable ones. ⚠️ Empty is a real answer: this number has none approved yet. */
    data class Ready(val templates: List<WhatsAppTemplate>) : WhatsAppTemplatesState
}

/**
 * Messenger's or Instagram's reply window for the chosen customer, as the web composer reads it.
 *
 * ⚠️ [UNKNOWN] IS A READ THAT FAILED OR AN ANSWER THIS BUILD DOES NOT KNOW, AND IT BLOCKS NOTHING:
 * the send refuses a closed window itself, and blocking on a failed preview would take the channel
 * away entirely. [CHECKING] blocks nothing either, as WhatsApp's does not.
 */
enum class MetaWindowState {
    /** The chosen channel is neither Messenger nor Instagram. */
    NOT_META,
    CHECKING,
    OPEN,

    /** Only a person's reply may go (the 7-day human agent window): the AI draft is off. */
    HUMAN_AGENT,

    /** Nothing may go until the customer writes again: free text, the AI draft and Send are off. */
    CLOSED,
    UNKNOWN,
}

/**
 * The template sheet: one template, one value per `{{n}}`, and the send.
 *
 * ⛔ [preview] IS THE SAME FUNCTION AS THE BODY SENT (`InboxRepository.sendTemplate`), so what the
 * operator reads is what the customer gets.
 */
data class TemplateSheetState(
    val open: Boolean = false,
    val chosen: WhatsAppTemplate? = null,
    val values: List<String> = emptyList(),
    /** ⛔ A template send is billable: a second tap while this is true does nothing. */
    val sending: Boolean = false,
    val failure: FailureText? = null,
) {
    val preview: String get() = chosen?.let { fillWhatsAppTemplate(it.body, values) }.orEmpty()

    /** Every variable filled (by JavaScript's trim, as the web composer measures). */
    val complete: Boolean get() = chosen != null && values.all { jsTrim(it).isNotEmpty() }
}

/**
 * The reply channels on offer and the one chosen, plus WhatsApp's window and Messenger's or
 * Instagram's.
 *
 * @param choices the server's order; the first is chosen on open. Empty for a caller who may not
 *   reply, so nothing here sends for them.
 */
data class ReplyChannelState(
    val choices: List<ReplyTarget> = emptyList(),
    val selected: ReplyTarget? = null,
    val whatsApp: WhatsAppWindowState = WhatsAppWindowState.NotWhatsApp,
    val sheet: TemplateSheetState = TemplateSheetState(),
    val meta: MetaWindowState = MetaWindowState.NOT_META,
) {
    /**
     * ⛔ A closed window takes free text, the AI draft and Send away: WhatsApp's (only a template
     * may go) and Messenger's or Instagram's (nothing may, until the customer writes again).
     */
    val allowsFreeText: Boolean
        get() = whatsApp !is WhatsAppWindowState.Closed && meta != MetaWindowState.CLOSED

    /** ⛔ The human agent window is for a person's reply only, so the AI draft is off there too. */
    val allowsDraft: Boolean get() = allowsFreeText && meta != MetaWindowState.HUMAN_AGENT

    /** ⛔ Attachments go by SMS only (the other routes refuse them, or email drops them silently). */
    val attachmentsAllowed: Boolean get() = selected?.channel == CHANNEL_SMS

    /** The `whatsappSenderId` a free-text send carries, or null to let the route choose. */
    val whatsAppSenderId: String? get() = (whatsApp as? WhatsAppWindowState.Open)?.senderId
}

/**
 * The composer's channel picker, its WhatsApp window and templates (app parity 1, 2 and 5), and
 * Messenger's or Instagram's reply window.
 *
 * ⚠️ A CLASS OF ITS OWN, OWNED BY [ThreadViewModel], because that class is at detekt's function
 * ceiling. It shares the ViewModel's scope, so nothing here outlives the thread.
 *
 * ⛔ SWITCHING IS REFUSED WHILE A SEND IS IN FLIGHT ([busy]), so a reply cannot land on a channel
 * other than the one the operator saw when they tapped Send.
 *
 * ⛔ LEAVING WHATSAPP DROPS A LATE ANSWER: the window or template read is cancelled with the channel,
 * so a slow "closed" cannot take the text box away from an SMS reply.
 */
class ReplyChannels(
    choices: List<ReplyTarget>,
    private val workspaceId: String,
    private val services: ThreadServices,
    private val scope: CoroutineScope,
    private val busy: () -> Boolean,
    private val onTemplateSent: () -> Unit,
) {
    private val _state = MutableStateFlow(ReplyChannelState(choices = choices))
    val state: StateFlow<ReplyChannelState> = _state.asStateFlow()

    /** The window or template read for the chosen number. Cancelled with the channel. */
    private var windowJob: Job = Job()

    init {
        choices.firstOrNull()?.let(::select)
    }

    /** Reply on [target] from now on. Refused while a send is in flight, and for a channel not on offer. */
    fun choose(target: ReplyTarget) {
        val current = _state.value
        if (busy() || current.sheet.sending) return
        if (target == current.selected || target !in current.choices) return
        select(target)
    }

    /**
     * The send route answered `whatsapp_window_closed`: the window shut since it was read (or the
     * read failed), so the composer turns to templates. ⚠️ With no sender known yet the window is
     * read again, because the templates belong to a sender.
     *
     * For Messenger and Instagram (`messenger_window_closed`) the window is simply closed: there is
     * nothing to offer in its place. ⚠️ The refusal is for the channel the send went on, which is
     * still the chosen one: switching is refused while a send is in flight.
     */
    fun windowClosed() {
        val current = _state.value
        val target = current.selected ?: return
        if (target.isMeta()) {
            windowJob.cancel()
            _state.value = current.copy(meta = MetaWindowState.CLOSED)
            return
        }
        if (target.channel != CHANNEL_WHATSAPP || current.whatsApp is WhatsAppWindowState.Closed) return
        val senderId = current.whatsAppSenderId
        windowJob.cancel()
        windowJob = scope.launch {
            if (senderId != null) _state.showClosed(services, workspaceId, senderId) else readWindow(target.to)
        }
    }

    /**
     * Read the window again: the screen came back to the front, and the customer may have written
     * (a closed window opens) or the 24 hours may have run out meanwhile.
     *
     * ⚠️ A READ ALREADY OUT IS LEFT TO ANSWER, and nothing is re-read while a template is on its
     * way. What is shown stays until the answer: a re-check never flashes "checking".
     */
    fun recheckWindow() {
        val current = _state.value
        val target = current.selected ?: return
        if (target.isMeta()) {
            if (current.meta == MetaWindowState.CHECKING) return
            windowJob.cancel()
            windowJob = scope.launch { _state.readMetaWindow(services, workspaceId, target) }
            return
        }
        if (target.channel != CHANNEL_WHATSAPP || current.sheet.sending) return
        val window = current.whatsApp
        val reading = window is WhatsAppWindowState.Checking ||
            (window is WhatsAppWindowState.Closed && window.templates is WhatsAppTemplatesState.Loading)
        if (reading) return
        windowJob.cancel()
        windowJob = scope.launch { readWindow(target.to) }
    }

    /** Open the template sheet. Only a closed window has one. */
    fun openTemplates() {
        val current = _state.value
        if (current.whatsApp !is WhatsAppWindowState.Closed) return
        _state.value = current.copy(sheet = current.sheet.copy(open = true))
    }

    /** Close the sheet, keeping what was filled. Refused while the template is on its way. */
    fun dismissTemplates() {
        val current = _state.value
        if (current.sheet.sending) return
        _state.value = current.copy(sheet = current.sheet.copy(open = false, failure = null))
    }

    /** Pick a template; its variables start empty, one per `{{n}}`. */
    fun chooseTemplate(template: WhatsAppTemplate) {
        val current = _state.value
        if (current.sheet.sending || template == current.sheet.chosen) return
        val sheet = current.sheet.copy(
            chosen = template,
            values = List(template.variableCount ?: 0) { "" },
            failure = null,
        )
        _state.value = current.copy(sheet = sheet)
    }

    /** Fill variable [index] (0 is `{{1}}`). */
    fun setTemplateValue(index: Int, value: String) {
        val current = _state.value
        val sheet = current.sheet
        if (sheet.sending || index !in sheet.values.indices) return
        val values = sheet.values.toMutableList().also { it[index] = value }
        _state.value = current.copy(sheet = sheet.copy(values = values))
    }

    /**
     * Send the chosen template.
     *
     * ⛔ SINGLE-FLIGHT, AND A REFUSAL KEEPS WHAT WAS FILLED: a retry must not mean typing every
     * variable again. On success the sheet empties and the thread is read again.
     */
    fun sendTemplate() {
        val current = _state.value
        val sheet = current.sheet
        val template = sheet.chosen ?: return
        val to = current.selected?.to ?: return
        val senderId = (current.whatsApp as? WhatsAppWindowState.Closed)?.senderId ?: return
        if (sheet.sending || !sheet.complete) return
        _state.value = current.copy(sheet = sheet.copy(sending = true, failure = null))
        scope.launch {
            val result = services.inbox.sendTemplate(workspaceId, to, senderId, template, sheet.values)
            val latest = _state.value
            when (result) {
                is ApiResult.Success -> {
                    _state.value = latest.copy(sheet = TemplateSheetState())
                    onTemplateSent()
                }
                is ApiResult.Failure -> _state.value = latest.copy(
                    sheet = latest.sheet.copy(sending = false, failure = result.toFailureText()),
                )
            }
        }
    }

    /**
     * Reply on [target], and read its window.
     *
     * ⛔ THE READ BELONGS TO THE TARGET IT WAS ASKED FOR: the previous one's is cancelled first, so
     * an answer for another customer (a thread offering both Messenger and Instagram) never paints
     * the one now chosen.
     */
    private fun select(target: ReplyTarget) {
        windowJob.cancel()
        val whatsApp = target.channel == CHANNEL_WHATSAPP
        val meta = target.isMeta()
        _state.value = _state.value.copy(
            selected = target,
            whatsApp = if (whatsApp) WhatsAppWindowState.Checking else WhatsAppWindowState.NotWhatsApp,
            sheet = TemplateSheetState(),
            meta = if (meta) MetaWindowState.CHECKING else MetaWindowState.NOT_META,
        )
        windowJob = scope.launch {
            when {
                whatsApp -> readWindow(target.to)
                meta -> _state.readMetaWindow(services, workspaceId, target)
            }
        }
    }

    /**
     * ⚠️ AN UNREADABLE WINDOW BLOCKS NOTHING: free text goes and the route, which checks the same
     * window, refuses it if it must. Blocking on a failed preview would take WhatsApp away entirely.
     *
     * ⛔ AN OPEN WINDOW IS OPEN UNTIL ITS `expiresAt`, NOT FOREVER: one already past is closed (the
     * web's reading), and one still open is read again when it passes, so the composer turns to
     * templates when the server would. An open window with no readable `expiresAt` stays open
     * and the route decides, as on the web.
     *
     * @param waited the closing instant the last read waited for. ⚠️ An answer at that re-read
     *   that does not close later is closed: only a window the customer re-opened is waited on
     *   again, so the re-reads always end.
     */
    private suspend fun readWindow(to: String, waited: Long? = null) {
        when (val result = services.whatsApp.window(workspaceId, to)) {
            is ApiResult.Success -> {
                val closesAt = result.value.window.expiresAt?.let(::epochMillisOrNull)
                val now = System.currentTimeMillis()
                val passed = closesAt != null && (closesAt <= now || (waited != null && closesAt <= waited))
                if (!result.value.window.open || passed) {
                    _state.showClosed(services, workspaceId, result.value.senderId)
                    return
                }
                _state.windowOpened(result.value.senderId)
                if (closesAt != null) {
                    delay(closesAt - now)
                    readWindow(to, waited = closesAt)
                }
            }
            is ApiResult.Failure -> _state.windowIs(WhatsAppWindowState.Open(senderId = null))
        }
    }
}

/** ⚠️ Top-level, not a member: [ReplyChannels] sits at detekt's function ceiling. */
private fun MutableStateFlow<ReplyChannelState>.windowIs(window: WhatsAppWindowState) {
    value = value.copy(whatsApp = window)
}

/**
 * The window is open. ⚠️ A window that was closed (the customer wrote since) also closes the
 * template sheet: its templates were for a closed window, and free text may go again.
 */
private fun MutableStateFlow<ReplyChannelState>.windowOpened(senderId: String?) {
    val current = value
    value = current.copy(
        whatsApp = WhatsAppWindowState.Open(senderId),
        sheet = if (current.whatsApp is WhatsAppWindowState.Closed) TemplateSheetState() else current.sheet,
    )
}

/**
 * The window is closed: show it, with [senderId]'s sendable templates.
 *
 * ⚠️ A closed window with no sender has no templates to read: it shows "none". The same sender's
 * templates already read are kept, so a re-check of a window still closed reads nothing more.
 * Top-level for the reason [windowIs] is.
 */
private suspend fun MutableStateFlow<ReplyChannelState>.showClosed(
    services: ThreadServices,
    workspaceId: String,
    senderId: String?,
) {
    if (senderId == null) {
        windowIs(WhatsAppWindowState.Closed(null, WhatsAppTemplatesState.Ready(emptyList())))
        return
    }
    val shown = value.whatsApp
    if (shown is WhatsAppWindowState.Closed && shown.senderId == senderId &&
        shown.templates is WhatsAppTemplatesState.Ready
    ) {
        return
    }
    windowIs(WhatsAppWindowState.Closed(senderId, WhatsAppTemplatesState.Loading))
    val templates = when (val result = services.whatsApp.templates(workspaceId, senderId)) {
        is ApiResult.Success -> WhatsAppTemplatesState.Ready(result.value)
        is ApiResult.Failure -> WhatsAppTemplatesState.Failed
    }
    windowIs(WhatsAppWindowState.Closed(senderId, templates))
}

/** An ISO-8601 instant as epoch milliseconds, or null when it is not one (an unknown closing time). */
private fun epochMillisOrNull(iso: String): Long? =
    try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

/** Messenger or Instagram: the channels with a Meta reply window. */
internal fun ReplyTarget.isMeta(): Boolean = channel == CHANNEL_MESSENGER || channel == CHANNEL_INSTAGRAM

/**
 * Read Messenger's or Instagram's window for [target] and show it. ⚠️ Top-level for the reason
 * [windowIs] is. What is shown stays until the answer (a re-check never flashes "checking").
 */
private suspend fun MutableStateFlow<ReplyChannelState>.readMetaWindow(
    services: ThreadServices,
    workspaceId: String,
    target: ReplyTarget,
) {
    val meta = when (val result = services.whatsApp.metaWindow(workspaceId, target.channel, target.to)) {
        is ApiResult.Success -> when (result.value.window.state) {
            META_WINDOW_OPEN -> MetaWindowState.OPEN
            META_WINDOW_HUMAN_AGENT -> MetaWindowState.HUMAN_AGENT
            META_WINDOW_CLOSED -> MetaWindowState.CLOSED
            else -> MetaWindowState.UNKNOWN
        }
        is ApiResult.Failure -> MetaWindowState.UNKNOWN
    }
    value = value.copy(meta = meta)
}

/** The send route's refusal for free text outside WhatsApp's 24-hour window. */
internal const val CODE_WHATSAPP_WINDOW_CLOSED = "whatsapp_window_closed"

/** True for that refusal (HTTP 409 with its code). */
internal fun ApiResult.Failure.isWhatsAppWindowClosed(): Boolean =
    this is ApiResult.HttpFailure && code == CODE_WHATSAPP_WINDOW_CLOSED

/** The send route's refusal outside Messenger's or Instagram's reply window (HTTP 409). */
internal const val CODE_META_WINDOW_CLOSED = "messenger_window_closed"

/** True for that refusal. */
internal fun ApiResult.Failure.isMetaWindowClosed(): Boolean =
    this is ApiResult.HttpFailure && code == CODE_META_WINDOW_CLOSED
