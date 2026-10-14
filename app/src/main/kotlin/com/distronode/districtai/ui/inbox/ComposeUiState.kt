package com.distronode.districtai.ui.inbox

import com.distronode.districtai.core.model.CHANNEL_EMAIL
import com.distronode.districtai.core.model.CHANNEL_SMS
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.MessageThreadTarget
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.ui.FailureText
import java.util.Locale

/**
 * Who a new conversation is with: a contact from the CRM, or an address typed by hand.
 *
 * ⛔ THE DIFFERENCE DECIDES WHERE THE CHANNEL COMES FROM. A picked contact carries its own phone and
 * email columns, so its choices are the server's stored values; a typed address is one string whose
 * channel this client has to derive (see [ComposeUiState.channelChoices]). Keeping the two apart in
 * the type is what stops the derivation leaking onto the contact path, where it would be a second,
 * disagreeing decision about a value the CRM already holds.
 */
sealed interface ComposeRecipient {
    data class Picked(val contact: Contact) : ComposeRecipient

    /**
     * ⚠️ The operator's own text, trimmed once and sent verbatim. The server normalises it; a
     * client-side rewrite would be a second normaliser that can disagree with the one that delivers.
     */
    data class Typed(val address: String) : ComposeRecipient
}

/**
 * The recipient suggestions: one window of the newest contacts.
 *
 * ⛔ [Failed] DOES NOT CLOSE THE FORM OR DISABLE THE FIELD. A number or address typed by hand needs
 * no CRM read at all, so a failed suggestion list is a caption beside a form that still works.
 */
sealed interface ComposeContacts {
    data object Loading : ComposeContacts
    data class Ready(val contacts: List<Contact>) : ComposeContacts
    data class Failed(val failure: FailureText) : ComposeContacts
}

/** What the send did. */
sealed interface ComposeSendState {
    data object Idle : ComposeSendState

    /**
     * ⛔ COVERS THE SEND AND THE THREAD LOOKUP AFTER IT. The form must not close, and must not offer
     * Send again, until it knows whether there is a thread to open.
     */
    data object Sending : ComposeSendState

    /**
     * The message went out.
     *
     * ⛔ [landing] IS OPTIONAL, AND NULL IS STILL A SENT MESSAGE. It means the thread lookup failed
     * (or the server echoed no row id), and that must never render as a failed send: the customer
     * has the message, and offering to send it again is a second charge and a duplicate. The form
     * closes and the Inbox list is re-read; the new thread is at the top of it.
     */
    data class Sent(val landing: MessageThreadTarget?) : ComposeSendState

    /** ⚠️ The server's own sentence: an unverified sender, an exhausted A2P registration, the cap. */
    data class Failed(val failure: FailureText) : ComposeSendState
}

/**
 * The "new message" form.
 *
 * ⚠️ THE DRAFT LIVES HERE, IN THE VIEWMODEL, AND SURVIVES A FAILED SEND. Losing a typed message to
 * a failed request would be two losses for one fault, and nothing autosaves on this surface because
 * there is no thread to save a draft against yet. Closing the form discards it, so a reopen starts
 * clean (see [ComposeViewModel.open]).
 *
 * @param open whether the form is on screen. ⚠️ Held here rather than in `rememberSaveable`, so a
 *   rotation keeps the form AND its draft together, and a process death drops both together rather
 *   than reopening an empty form whose contact list was never read.
 */
data class ComposeUiState(
    val open: Boolean = false,
    val contacts: ComposeContacts = ComposeContacts.Loading,
    val query: String = "",
    val recipient: ComposeRecipient? = null,
    val selectedChannel: Int = 0,
    val body: String = "",
    val subject: String = "",
    val send: ComposeSendState = ComposeSendState.Idle,
) {

    val sending: Boolean get() = send == ComposeSendState.Sending

    /**
     * The contacts matching what has been typed.
     *
     * ⚠️ NAME, NUMBER AND ADDRESS, case-insensitively: an operator is as likely to remember a number
     * as a name, and a contact named the literal "Unknown" (what the voice agent writes for an
     * unidentified caller) is only findable by its number. An empty query shows the window as it
     * came, newest first.
     */
    val matches: List<Contact>
        get() {
            val loaded = (contacts as? ComposeContacts.Ready)?.contacts.orEmpty()
            val needle = query.trim().lowercase(Locale.ROOT)
            if (needle.isEmpty()) return loaded
            return loaded.filter { contact ->
                listOf(contact.name, contact.phoneNumber.orEmpty(), contact.email.orEmpty())
                    .any { it.lowercase(Locale.ROOT).contains(needle) }
            }
        }

    /**
     * Every channel the recipient can be reached on, SMS first.
     *
     * ⛔ A PICKED CONTACT TAKES ITS CHOICES FROM ITS STORED COLUMNS, NEVER FROM AN `@` TEST. The
     * derivation is confined to a typed address, which has no thread and no server answer to carry;
     * everywhere else in this app the reply channel is the server's (`ConversationSummary.replyTarget`,
     * the message resolver), and nothing here may be copied there.
     *
     * ⚠️ A CONTACT WITH NEITHER ADDRESS GIVES AN EMPTY LIST. The CRM is email-first and admits
     * phone-less rows, so this is a real state; [submittable] refuses rather than sending with no
     * `to`. A blank column counts as absent: both are free text an operator can edit.
     */
    val channelChoices: List<ReplyTarget>
        get() = when (val chosen = recipient) {
            null -> emptyList()
            is ComposeRecipient.Picked -> listOfNotNull(
                chosen.contact.phoneNumber?.takeIf { it.isNotBlank() }?.let { ReplyTarget(it, CHANNEL_SMS) },
                chosen.contact.email?.takeIf { it.isNotBlank() }?.let { ReplyTarget(it, CHANNEL_EMAIL) },
            )
            // ⛔ AN `@` AND NOTHING ELSE, the server address normaliser's own test. A richer guess
            // would disagree with the server about exactly the inputs that are hard.
            is ComposeRecipient.Typed -> listOf(
                ReplyTarget(chosen.address, if ('@' in chosen.address) CHANNEL_EMAIL else CHANNEL_SMS),
            )
        }

    /** The address and channel the send will use. */
    val target: ReplyTarget? get() = channelChoices.getOrNull(selectedChannel)

    /**
     * ⛔ REQUIRED ON EMAIL. `messages/send` substitutes "Message from District" for a blank subject
     * and DELIVERS, so this form is the last place a missing one can be caught, and on a first email
     * that literal is the first thing the customer ever sees from this workspace.
     */
    val requiresSubject: Boolean get() = target?.channel == CHANNEL_EMAIL

    /**
     * Whether Send may be pressed.
     *
     * ⛔ IN FLIGHT MEANS NO, AND THAT GUARD IS ABOUT MONEY: every send is carrier segments or a
     * Postmark send, capped at 30/min per workspace. ⛔ A blank body is refused because the route
     * guards on `!body` before anything else, and a blank email subject for the reason
     * [requiresSubject] gives.
     */
    val submittable: Boolean
        get() = target != null && !sending && body.isNotBlank() && (!requiresSubject || subject.isNotBlank())

    /** What the "To" line shows for the armed recipient. */
    val recipientLabel: String?
        get() = when (val chosen = recipient) {
            null -> null
            is ComposeRecipient.Picked -> chosen.contact.label()
            is ComposeRecipient.Typed -> chosen.address
        }
}

/**
 * The form's controls, as the screen sees them.
 *
 * ⚠️ FUNCTION VALUES BUILT ONCE, OUTSIDE COMPOSITION (see [ComposeViewModel.actions]), so the screen
 * hands each control an existing value rather than a method reference that Compose would wrap and
 * re-remember on every redraw. One value rather than ten parameters, and a screen test builds its own
 * with recording lambdas.
 */
data class ComposeActions(
    val open: () -> Unit,
    val dismiss: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val choose: (Contact) -> Unit,
    val useTypedAddress: () -> Unit,
    val clearRecipient: () -> Unit,
    val selectChannel: (Int) -> Unit,
    val onBodyChange: (String) -> Unit,
    val onSubjectChange: (String) -> Unit,
    val send: () -> Unit,
)

/**
 * The name a suggestion row and the "To" line show.
 *
 * ⚠️ [Contact.displayName] first, which already treats the voice agent's literal "Unknown" as no
 * name, then the number, then the address, and the raw name only when there is nothing else.
 */
internal fun Contact.label(): String =
    displayName ?: phoneNumber?.takeIf { it.isNotBlank() } ?: email?.takeIf { it.isNotBlank() } ?: name

/** Clear a failed send's sentence when the operator changes who they are writing to. */
internal fun ComposeUiState.withoutFailure(): ComposeUiState =
    if (send is ComposeSendState.Failed) copy(send = ComposeSendState.Idle) else this

/**
 * The Inbox's "new message" form, as the Inbox screen hosts it.
 *
 * @param onSent called once per sent message with the thread it landed in, or null when the thread
 *   could not be resolved. ⛔ The caller re-reads the Inbox list either way: a send whose thread could
 *   not be resolved still happened, and the new conversation is at the top of the list.
 */
data class InboxCompose(
    val state: ComposeUiState,
    val actions: ComposeActions,
    val onSent: (MessageThreadTarget?) -> Unit,
)
