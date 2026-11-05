package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.data.ComposerRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.WhatsAppRepository
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.CHANNEL_WHATSAPP
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.fillWhatsAppTemplate
import com.distronode.districtai.core.model.jsTrim
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * The reply channels on offer and the one chosen, plus WhatsApp's window.
 *
 * @param choices the server's order; the first is chosen on open. Empty for a caller who may not
 *   reply, so nothing here sends for them.
 */
data class ReplyChannelState(
    val choices: List<ReplyTarget> = emptyList(),
    val selected: ReplyTarget? = null,
    val whatsApp: WhatsAppWindowState = WhatsAppWindowState.NotWhatsApp,
    val sheet: TemplateSheetState = TemplateSheetState(),
) {
    /** ⛔ A closed WhatsApp window takes free text and the AI draft away; only a template may go. */
    val allowsFreeText: Boolean get() = whatsApp !is WhatsAppWindowState.Closed

    /** ⛔ Attachments go by SMS only (the other routes refuse them, or email drops them silently). */
    val attachmentsAllowed: Boolean get() = selected?.channel == CHANNEL_SMS

    /** The `whatsappSenderId` a free-text send carries, or null to let the route choose. */
    val whatsAppSenderId: String? get() = (whatsApp as? WhatsAppWindowState.Open)?.senderId
}

/**
 * The composer's channel picker and its WhatsApp window and templates (app parity 1, 2 and 5).
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
     */
    fun windowClosed() {
        val current = _state.value
        val target = current.selected ?: return
        if (target.channel != CHANNEL_WHATSAPP || current.whatsApp is WhatsAppWindowState.Closed) return
        val senderId = current.whatsAppSenderId
        windowJob.cancel()
        windowJob = scope.launch { if (senderId != null) showClosed(senderId) else readWindow(target.to) }
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

    private fun select(target: ReplyTarget) {
        windowJob.cancel()
        val whatsApp = target.channel == CHANNEL_WHATSAPP
        _state.value = _state.value.copy(
            selected = target,
            whatsApp = if (whatsApp) WhatsAppWindowState.Checking else WhatsAppWindowState.NotWhatsApp,
            sheet = TemplateSheetState(),
        )
        windowJob = scope.launch { if (whatsApp) readWindow(target.to) }
    }

    /**
     * ⚠️ AN UNREADABLE WINDOW BLOCKS NOTHING: free text goes and the route, which checks the same
     * window, refuses it if it must. Blocking on a failed preview would take WhatsApp away entirely.
     */
    private suspend fun readWindow(to: String) {
        when (val result = services.whatsApp.window(workspaceId, to)) {
            is ApiResult.Success ->
                if (result.value.window.open) {
                    _state.windowIs(WhatsAppWindowState.Open(result.value.senderId))
                } else {
                    showClosed(result.value.senderId)
                }
            is ApiResult.Failure -> _state.windowIs(WhatsAppWindowState.Open(senderId = null))
        }
    }

    /** ⚠️ A closed window with no sender has no templates to read: it shows "none". */
    private suspend fun showClosed(senderId: String?) {
        if (senderId == null) {
            _state.windowIs(WhatsAppWindowState.Closed(null, WhatsAppTemplatesState.Ready(emptyList())))
            return
        }
        _state.windowIs(WhatsAppWindowState.Closed(senderId, WhatsAppTemplatesState.Loading))
        val templates = when (val result = services.whatsApp.templates(workspaceId, senderId)) {
            is ApiResult.Success -> WhatsAppTemplatesState.Ready(result.value)
            is ApiResult.Failure -> WhatsAppTemplatesState.Failed
        }
        _state.windowIs(WhatsAppWindowState.Closed(senderId, templates))
    }
}

/** ⚠️ Top-level, not a member: [ReplyChannels] sits at detekt's function ceiling. */
private fun MutableStateFlow<ReplyChannelState>.windowIs(window: WhatsAppWindowState) {
    value = value.copy(whatsApp = window)
}

/** The send route's refusal for free text outside WhatsApp's 24-hour window. */
internal const val CODE_WHATSAPP_WINDOW_CLOSED = "whatsapp_window_closed"

/** True for that refusal (HTTP 409 with its code). */
internal fun ApiResult.Failure.isWhatsAppWindowClosed(): Boolean =
    this is ApiResult.HttpFailure && code == CODE_WHATSAPP_WINDOW_CLOSED
