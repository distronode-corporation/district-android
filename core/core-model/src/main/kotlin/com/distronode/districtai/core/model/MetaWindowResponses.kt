package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/messages/send?workspaceId=&channel=&to=`: Messenger's or Instagram's reply
 * window for one customer, as the web composer reads it (`useMetaWindow`). The composer's preview
 * only: the send refuses a closed window itself with `messenger_window_closed`.
 */
@Serializable
data class MetaWindowResponse(
    val success: Boolean = false,
    val window: MetaWindow = MetaWindow(),
)

/**
 * The reply window. [state] is one of [META_WINDOW_OPEN], [META_WINDOW_HUMAN_AGENT] or
 * [META_WINDOW_CLOSED]; anything else is an answer this build does not know, which blocks nothing
 * (the web's reading).
 *
 * ⚠️ [state] IS A STRING, NOT AN ENUM: an enum would fail the whole response on a fourth state the
 * server adds later, and with it every reply on the channel.
 *
 * [until] is when the window closes, ISO-8601, null when closed. The composer shows no countdown,
 * as the web does not, so nothing here parses it.
 */
@Serializable
data class MetaWindow(
    val state: String = "",
    val until: String? = null,
)

/** Free text may go: within 24 hours of the customer's last message. */
const val META_WINDOW_OPEN: String = "open"

/** Only a person's reply may go, tagged as a human agent's: within 7 days, when the page allows it. */
const val META_WINDOW_HUMAN_AGENT: String = "human_agent"

/** Nothing may go until the customer writes again. */
const val META_WINDOW_CLOSED: String = "closed"
