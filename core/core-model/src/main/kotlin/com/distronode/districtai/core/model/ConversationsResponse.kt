package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/conversations` — the unified Inbox list.
 *
 * ⛔ "UNIFIED" IS THE WHOLE POINT AND IT IS WHY [ConversationSummary.threadKey] EXISTS. One thread
 * can mix SMS and email, because a customer's phone number and their email address are two
 * different strings that resolve to the same Contact. The server folds them; a client that keys
 * this list by address instead would show the same person twice and split their history.
 *
 * ⚠️ NOT PAGED, AND THAT IS A SERVER PROPERTY THIS CLIENT MUST RESPECT. The server scans a bounded
 * window of recent messages ([scanLimit], 500) and groups what it finds — so this is "recent
 * conversations", not "all conversations". [scanned] reports how much of that window was consumed.
 * See the ⚠️ on [scanned] before adding a "load more".
 */
@Serializable
data class ConversationsResponse(
    val success: Boolean = false,
    val conversations: List<ConversationSummary> = emptyList(),
    /**
     * How many messages the server actually scanned to build this list.
     *
     * ⚠️ `scanned == scanLimit` MEANS THE LIST MAY BE INCOMPLETE — an older conversation with no
     * recent traffic falls outside the window entirely. It is not an error and there is no page to
     * request; it is a truthfulness signal, and the UI says so rather than implying the list is
     * everything.
     */
    val scanned: Int = 0,
    val scanLimit: Int = 0,
)

/**
 * One thread in the Inbox.
 *
 * ⚠️ [key] AND [kind] ARE MODELLED EVEN THOUGH BOTH ARE DEPRECATED SERVER-SIDE, AND THAT IS A
 * REVERSAL WORTH RECORDING. They were deliberately omitted at first, on the reasoning that
 * modelling `key` invites a client to key off it — which is exactly what could not merge a
 * customer's phone and email into one row.
 *
 * Omission turned out to be the weaker choice, for two reasons found by writing the contract
 * fixture:
 *   1. `ContractFixtureTest` decodes with `ignoreUnknownKeys = false`, so an unmodelled field is
 *      a hard decode failure. Omitting these two would have forced that gate to be relaxed for
 *      this one type — and a relaxed gate stops catching the NEW field it exists to catch.
 *   2. Omission is silent. `@Deprecated` is not: any code that reads either field now gets a
 *      compiler warning naming the replacement, which is strictly more protection than a field
 *      that simply was not there.
 */
@Serializable
data class ConversationSummary(
    /**
     * The normalized address of the thread's most recent message.
     *
     * ⛔ NOT THREAD IDENTITY. Superseded by [threadKey], and retained by the server only so a
     * browser running a previous JS bundle against a freshly deployed server keeps working. One
     * customer's phone and their email are two different strings, so this cannot identify a
     * thread that mixes channels — the fixture's folded thread has `key = "ada@contract.test"`
     * while carrying SMS from `14165550142`.
     */
    @Deprecated("Use threadKey. `key` cannot identify a thread that mixes channels.")
    val key: String = "",
    /**
     * Stable thread identity: `contact:<id>` when the counterpart resolves to a Contact,
     * `addr:<normalized>` when it does not.
     *
     * ⛔ THE LIST KEY, and the only safe one. Keying a `LazyColumn` on the counterpart address
     * would produce duplicate keys the moment one person's SMS and email fold together — a
     * duplicate key is an IllegalArgumentException, i.e. a crash, not a cosmetic repeat.
     */
    val threadKey: String = "",
    /** The counterpart as stored on the most recent message, in display form. */
    val counterpart: String = "",
    /**
     * Every normalized address that folds into this thread — a contact-keyed thread carries the
     * phone AND the email.
     *
     * ⚠️ Present so a deep link or a search hit on EITHER address lands on the already-open
     * conversation rather than opening a second one.
     */
    val matchKeys: List<String> = emptyList(),
    /**
     * The message type of the thread's most recent message.
     *
     * ⛔ NOT A THREAD PROPERTY. Superseded by [channels]. A thread that mixes SMS and email has no
     * single kind, and reading this one is how the web's reply box decided a customer who had only
     * ever emailed could not be sent an SMS. Use [canSms]/[canEmail] to decide what is sendable
     * and [channels] to describe what is present.
     */
    @Deprecated("Use channels, plus canSms/canEmail. A mixed thread has no single kind.")
    val kind: String = "",
    /** Distinct message types in the thread, e.g. `["sms", "email"]`. */
    val channels: List<String> = emptyList(),
    val contactId: String? = null,
    val contactName: String? = null,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
    /**
     * Which reply channels are actually available.
     *
     * ⛔ SERVER-DECIDED, NEVER INFERRED FROM [channels]. The web's reply box used to derive this
     * from the thread's most recent message type, which meant a customer who had only ever emailed
     * could not be sent an SMS even when their contact record held a number. Deriving it here would
     * reintroduce that bug on a second client.
     */
    val canSms: Boolean = false,
    val canEmail: Boolean = false,
    val lastMessage: ConversationLastMessage = ConversationLastMessage(),
    val unreadCount: Int = 0,
    val totalMessages: Int = 0,
) {
    /**
     * What to show as the thread's title.
     *
     * ⚠️ Falls back to the raw counterpart, never to a placeholder. An unresolved address IS the
     * identity of that thread — a phone number is a perfectly good label, and "Unknown" would hide
     * the one piece of information available.
     */
    val displayName: String get() = contactName?.takeIf { it.isNotBlank() } ?: counterpart

    /** True when this thread has anything the operator has not seen. */
    val hasUnread: Boolean get() = unreadCount > 0

    /**
     * Where a reply to this thread actually goes, and on which channel.
     *
     * ⛔ THE RECIPIENT IS AN ADDRESS, NEVER A THREAD IDENTITY. `messages/send` takes `to` as a
     * phone number or an email and hands it straight to the carrier or to Postmark — it does not
     * resolve a Contact id. Sending [threadKey]'s `contact:<id>` portion as `to` dispatches an SMS
     * to a cuid, which fails at the provider and surfaces as a raw 500. That is what this property
     * exists to make impossible, and it was a live bug: every contact-keyed thread (the majority,
     * since the server folds any counterpart that resolves to a Contact into that form) could not
     * be replied to at all.
     *
     * ⛔ GATED ON [canSms]/[canEmail], WHICH ARE SERVER-DECIDED. Never infer sendability from
     * [channels] — that is the web bug those flags exist to prevent, where a customer who had only
     * ever emailed could not be sent an SMS even though their contact record held a number.
     *
     * ⚠️ SMS is preferred when both are available, which preserves the previous behaviour for phone
     * threads. A per-thread channel picker belongs with attachment support (an email sent from here
     * would have no subject field), so this chooses rather than asks.
     *
     * Null means this thread has nothing to reply on, and the caller must offer no reply box.
     */
    val replyTarget: ReplyTarget?
        get() {
            if (canSms) {
                val phone = contactPhone?.takeIf { it.isNotBlank() }
                    ?: counterpart.takeIf { it.isNotBlank() && !it.contains("@") }
                if (phone != null) return ReplyTarget(phone, CHANNEL_SMS)
            }
            if (canEmail) {
                val email = contactEmail?.takeIf { it.isNotBlank() }
                    ?: counterpart.takeIf { it.contains("@") }
                if (email != null) return ReplyTarget(email, CHANNEL_EMAIL)
            }
            return null
        }
}

/** `messages/send`'s channel vocabulary. The server branches on these exact strings. */
const val CHANNEL_SMS = "sms"
const val CHANNEL_EMAIL = "email"

/**
 * A resolved reply destination: the address to send to, and the channel to send on.
 *
 * ⚠️ The two travel together on purpose. An email address on the `sms` channel reaches the carrier
 * branch, which does no address-shape validation, so the pair must be chosen at one place rather
 * than assembled from two independent decisions.
 */
data class ReplyTarget(val to: String, val channel: String)

@Serializable
data class ConversationLastMessage(
    val body: String = "",
    /** `inbound` | `outbound`. */
    val direction: String = "",
    /** `sms` | `email` | `whatsapp`, or null on older rows. */
    val type: String? = null,
    val status: String = "",
    /** ISO-8601, server-formatted. Not reformatted locally — see [TimelineEvent.timestamp]. */
    val createdAt: String = "",
)
