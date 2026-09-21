package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * District Desk: the tenant's OWN customers' tickets, and the queue's settings.
 *
 * ⛔ THE MIRROR IMAGE OF [SupportRequestSummary] AND THE TWO MUST NEVER BE DESCRIBED WITH THE SAME
 * NOUN. `district/desk/…` is the tenant's customers writing to THEM; `district/support/…` is the
 * tenant writing to US. A bare "Tickets" on either surface collapses the distinction, and the two
 * reply routes are one word apart on the wire (`message` here, `body` there) — so a reader who has
 * merged them in their head will write a request that 400s while looking correct.
 *
 * ⛔ EVERY DESK ROUTE IS `["agency","client"]` AND EXCLUDES `viewer`, READS INCLUDED. These payloads
 * carry a customer's name, email address and phone number in the clear plus the correspondence
 * about them. The ENTRY POINT must be hidden for a viewer rather than merely captioned.
 *
 * ✅ PINNED BY GENERATED FIXTURES. The server's contract generator writes nine
 * `district-desk-*.json` files from the real handlers, and `DeskContractFixtureTest` decodes each
 * of them. `DeskContractShapeTest` still covers the branches
 * no fixture carries (a deduplicated or pending create, the degraded reply replay, an unknown status).
 */
@Serializable
data class DeskSettings(
    val enabled: Boolean = false,
    val notifyCustomersByEmail: Boolean = false,
    /**
     * ⚠️ EXPLICIT `null`, NEVER ABSENT, and null means "fall back to the workspace's own name"
     * rather than "unknown". The customer-facing page shows one or the other, so a screen that
     * rendered an empty brand line for null would show the tenant a blank where their name goes.
     */
    val publicBrandName: String? = null,
    /**
     * ⛔ READ-ONLY ON THIS TYPE. The settings PATCH does not accept `publicLogoUrl` and must never
     * be made to: the value has to be a URL this platform produced, and a caller-supplied string
     * would let a workspace member point their own customers' page at any image on the internet
     * with our domain's reputation attached. Only the logo route may store one.
     */
    val publicLogoUrl: String? = null,
) {
    companion object {
        /** ⚠️ The route trims and bounds at 80; over-length is a 400 rather than a truncation. */
        const val BRAND_NAME_MAX_LENGTH: Int = 80
    }
}

/** The GET, the PATCH and the logo POST all answer this. */
@Serializable
data class DeskSettingsResponse(
    val success: Boolean = false,
    val settings: DeskSettings? = null,
)

/**
 * The logo DELETE, which is the takedown path rather than a tidy-up.
 *
 * ⛔ ITS ANSWER HAS TWO PARTS BECAUSE THE OPERATION DOES. The column is cleared first (that is what
 * stops the image appearing on the tenant's customer-facing page) and the stored object second
 * (that is what stops the bytes being served at all). A 200 says the first happened;
 * [objectRemoved] says whether the second did.
 *
 * ⚠️ IDEMPOTENT: a workspace with no logo gets a 200 with `objectRemoved: false`, and so does a
 * workspace whose column was cleared while object storage refused the delete. The two are not
 * distinguishable from here.
 */
@Serializable
data class DeskLogoRemovalResponse(
    val success: Boolean = false,
    val settings: DeskSettings? = null,
    val objectRemoved: Boolean = false,
)

/**
 * One ticket as the queue lists it.
 *
 * ⚠️ EVERY FIELD DEFAULTS, which is what lets this same type decode the create echo, the reply echo
 * and the status echo as well as a list row. `status` and `source` are plain `String` rather than
 * enums for the reason [KnowledgeDocument.status] is: the column behind `status` is `TEXT`, chosen
 * so adding a state never needs a migration on four databases, and an installed build must render
 * an unrecognised state as itself rather than failing to read the queue at all.
 */
@Serializable
data class DeskTicketSummary(
    val id: String = "",
    /** The per-workspace counter. [displayReference] is what an operator says out loud. */
    val reference: Int = 0,
    val displayReference: String = "",
    val subject: String = "",
    val status: String = "",
    val source: String = "",
    val contactId: String? = null,
    val requesterName: String? = null,
    val requesterEmail: String? = null,
    val requesterPhone: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    /**
     * ⛔ STAMPED WHEN A TICKET IS RESOLVED AND CLEARED TO NULL ON ANY OTHER STATUS, both
     * server-side. That is why the echoed ticket must be adopted rather than the status that was
     * requested: a ticket resolved and then reopened would otherwise keep a resolution time in the
     * past, and every figure computed from it is wrong in a way that looks plausible.
     */
    val resolvedAt: String? = null,
    val messageCount: Int = 0,
) {
    /** Null for a status this build does not recognise, which renders as itself instead. */
    val knownStatus: DeskTicketStatus? get() = DeskTicketStatus.fromWire(status)

    /** ⚠️ The queue's own tickets come from calls; `manual` is one an operator raised by hand. */
    val fromCall: Boolean get() = source == DESK_SOURCE_VOICE_CALL
}

/** One message in a ticket's thread. */
@Serializable
data class DeskMessage(
    val id: String = "",
    val authorType: String = "",
    val body: String = "",
    val createdAt: String = "",
) {
    val knownAuthor: DeskMessageAuthor? get() = DeskMessageAuthor.fromWire(authorType)
}

/** A ticket and its whole thread. The list's 14 fields plus [messages]. */
@Serializable
data class DeskTicketDetail(
    val id: String = "",
    val reference: Int = 0,
    val displayReference: String = "",
    val subject: String = "",
    val status: String = "",
    val source: String = "",
    val contactId: String? = null,
    val requesterName: String? = null,
    val requesterEmail: String? = null,
    val requesterPhone: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
    val resolvedAt: String? = null,
    val messageCount: Int = 0,
    val messages: List<DeskMessage> = emptyList(),
) {
    val knownStatus: DeskTicketStatus? get() = DeskTicketStatus.fromWire(status)

    val fromCall: Boolean get() = source == DESK_SOURCE_VOICE_CALL

    /**
     * Adopt a summary the server echoed, keeping the thread this detail already holds.
     *
     * ⛔ THE SUMMARY IS THE AUTHORITY FOR EVERY FIELD IT CARRIES, INCLUDING `status`. A reply
     * auto-sets the ticket to `waiting` unless it is resolved, and a status change stamps or clears
     * `resolvedAt` — both server-side — so a screen that kept its own copy of either would show the
     * operator a state the server does not hold.
     *
     * ⚠️ [DeskTicketSummary.messageCount] IS DELIBERATELY NOT ADOPTED. It is recomputed from the
     * thread actually in hand, because appending a message locally and taking the server's count
     * would disagree the moment a reply raced a refresh.
     */
    fun adopting(
        ticket: DeskTicketSummary,
        appending: DeskMessage? = null,
    ): DeskTicketDetail {
        val thread = if (appending == null) messages else messages + appending
        return DeskTicketDetail(
            id = ticket.id,
            reference = ticket.reference,
            displayReference = ticket.displayReference,
            subject = ticket.subject,
            status = ticket.status,
            source = ticket.source,
            contactId = ticket.contactId,
            requesterName = ticket.requesterName,
            requesterEmail = ticket.requesterEmail,
            requesterPhone = ticket.requesterPhone,
            createdAt = ticket.createdAt,
            updatedAt = ticket.updatedAt,
            resolvedAt = ticket.resolvedAt,
            messageCount = thread.size,
            messages = thread,
        )
    }
}

@Serializable
data class DeskTicketsResponse(
    val success: Boolean = false,
    val tickets: List<DeskTicketSummary> = emptyList(),
)

@Serializable
data class DeskTicketResponse(
    val success: Boolean = false,
    val ticket: DeskTicketDetail? = null,
)

/**
 * The create's answer, which has two mutually exclusive shapes.
 *
 * ⛔ `ticket` IS ABSENT — NOT NULL — WHEN AN IDEMPOTENCY KEY IS REPLAYED. The server answers
 * `{success:true, deduplicated:true}` with no ticket key at all, so a non-nullable field here
 * would throw on the response to a submit the operator will read as having worked.
 */
@Serializable
data class DeskTicketCreateResponse(
    val success: Boolean = false,
    val ticket: DeskTicketSummary? = null,
    val deduplicated: Boolean? = null,
)

/**
 * The reply's answer, which has THREE shapes.
 *
 * ⛔ THE DEGRADED REPLAY CARRIES NOTHING BUT `deduplicated`. A replayed key whose cached payload
 * is no longer in Redis answers `{success:true, deduplicated:true}` with no ticket, no message and
 * no `notified` — so all three default, and a caller must treat their absence as "the reply
 * already landed and I cannot show you which one" rather than as a failure.
 */
@Serializable
data class DeskReplyResponse(
    val success: Boolean = false,
    val ticket: DeskTicketSummary? = null,
    val message: DeskMessage? = null,
    /** Whether the customer was emailed. Absent on a degraded replay. */
    val notified: Boolean? = null,
    val deduplicated: Boolean? = null,
)

@Serializable
data class DeskTicketStatusResponse(
    val success: Boolean = false,
    val ticket: DeskTicketSummary? = null,
)

/**
 * The three states a ticket can be in.
 *
 * ⛔ THE ROUTE VALIDATES WITH A `z.enum`, SO AN UNRECOGNISED VALUE IS A 400 RATHER THAN A STORED
 * ONE — and the column behind it is plain `TEXT`. This enum is what stops this client SENDING a
 * typo; [DeskTicketSummary.status] stays a `String` so it can still READ one.
 */
enum class DeskTicketStatus(val wire: String) {
    OPEN(DESK_STATUS_OPEN),
    WAITING(DESK_STATUS_WAITING),
    RESOLVED(DESK_STATUS_RESOLVED),
    ;

    companion object {
        fun fromWire(raw: String?): DeskTicketStatus? =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

/**
 * Who wrote a message.
 *
 * ⛔ NOT A PARAMETER ON THE REPLY ROUTE, AND THIS TYPE MUST NEVER BECOME ONE. The author type is
 * fixed to `team` server-side because it decides three things at once — the status transition,
 * whether the customer is emailed, and how the message is attributed — so accepting it from the
 * wire would let a caller post a message attributed to their own customer and suppress the
 * notification while doing it.
 */
enum class DeskMessageAuthor(val wire: String) {
    CUSTOMER("customer"),
    TEAM("team"),
    ASSISTANT("assistant"),
    ;

    companion object {
        fun fromWire(raw: String?): DeskMessageAuthor? =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() }
    }
}

const val DESK_STATUS_OPEN: String = "open"
const val DESK_STATUS_WAITING: String = "waiting"
const val DESK_STATUS_RESOLVED: String = "resolved"

/** ⚠️ The queue's own origin for a ticket the receptionist raised during a call. */
const val DESK_SOURCE_VOICE_CALL: String = "voice-call"

/**
 * What the public brand name is being told to do.
 *
 * ⛔ THREE STATES, AND A `String?` CANNOT EXPRESS THEM. The route distinguishes ABSENT ("leave it
 * alone"), a string ("store this") and an explicit NULL ("clear it, and fall back to the workspace
 * name") — and this client's request encoder runs `explicitNulls = false`, which DROPS a null pair
 * by design. So a nil `String?` would silently mean "leave it alone" at the one call site whose
 * whole purpose is to clear the value.
 *
 * ⚠️ AN EMPTY STRING WOULD ALSO CLEAR IT TODAY, and relying on that would be a mistake to inherit:
 * the route's schema is `.trim().transform(v => v || null)`, so `"  "` reaches the column as null
 * as a coincidence of the transform rather than as the contract. [Clear] says what is meant.
 */
sealed interface DeskBrandName {
    /** ⚠️ Trimmed and bounded at [DeskSettings.BRAND_NAME_MAX_LENGTH] server-side. */
    data class Set(val name: String) : DeskBrandName

    /** ⛔ The tenant's customers then see the workspace's own name. */
    data object Clear : DeskBrandName
}

/**
 * A partial write of the desk's settings.
 *
 * ⛔ SEND ONLY WHAT CHANGED. The route merges per field, so an omitted key is PRESERVED — and a
 * client that posted its whole form state would make this screen the writer of values it may have
 * read before another tab changed them. That is the `blank_form_overwrites_config` shape: a form
 * saved after a failed load writing blanks over live configuration.
 *
 * ⛔ AN EMPTY PATCH IS A **400**, NOT A NO-OP 200. The route refines on at least one field being
 * present, deliberately, because an empty body is always a client bug and answering 200 hides it.
 * [isEmpty] exists so a caller can decline to make the call at all.
 */
data class DeskSettingsPatch(
    val enabled: Boolean? = null,
    val notifyCustomersByEmail: Boolean? = null,
    val publicBrandName: DeskBrandName? = null,
) {
    val isEmpty: Boolean
        get() = enabled == null && notifyCustomersByEmail == null && publicBrandName == null
}

/**
 * A ticket an operator is raising on a customer's behalf.
 *
 * ⛔ A PARAMETER OBJECT RATHER THAN SIX ARGUMENTS, and for a sharper reason than tidiness: five of
 * these are strings, and three of THOSE are the customer's name, email address and phone number. A
 * positional list of interchangeable strings is exactly where an email ends up in the phone column,
 * which then reaches the customer as the address a notification is sent to.
 *
 * ⛔ A BLANK OPTIONAL MUST ARRIVE HERE AS NULL, NOT AS `""`. The route's schema permits these keys
 * to be ABSENT and not present-and-empty: `requesterEmail: ""` fails `.email()` and takes the whole
 * object down, so a phone-only ticket 400s with "A subject and a description are required" — naming
 * two fields that were both filled in. `DeskRepository` trims to null on the way in, so a form may
 * hand it whatever is in its boxes.
 */
data class DeskTicketDraft(
    val subject: String,
    /**
     * ⛔ THE OPENING MESSAGE, AND THE SERVER STORES IT AS THE CUSTOMER'S OWN WORDS. A ticket an
     * operator raises on someone's behalf still records the CUSTOMER as the author: it is their
     * problem, and attributing it to the team would make the thread read as us talking to
     * ourselves. The author type is fixed server-side.
     */
    val message: String,
    val requesterName: String? = null,
    val requesterEmail: String? = null,
    val requesterPhone: String? = null,
    /** The `Contact` row this ticket belongs to, when the operator picked one. A `uuid`. */
    val contactId: String? = null,
)

/** ⚠️ Bounds a form can stop at, matching the route's schema exactly. */
object DeskBounds {
    const val SUBJECT_MIN: Int = 3
    const val SUBJECT_MAX: Int = 200
    const val MESSAGE_MIN: Int = 1
    const val MESSAGE_MAX: Int = 10_000
    const val REQUESTER_NAME_MAX: Int = 200
    const val REQUESTER_EMAIL_MAX: Int = 320
    const val REQUESTER_PHONE_MAX: Int = 40
}
