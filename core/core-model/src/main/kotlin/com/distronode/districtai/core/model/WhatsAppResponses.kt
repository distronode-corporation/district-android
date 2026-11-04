package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/workspace/whatsapp/window?workspaceId=&to=[&senderId=]`: whether free-form
 * WhatsApp text may go from a sender to `to` now, and until when. The composer's preview only:
 * the send route enforces the same window itself.
 *
 * [senderId] is the sender whose window this is (the operator's pick, else the one the send route
 * would choose). [receivedOn] is the number of ours the customer's latest inbound WhatsApp message
 * reached, or null when they never wrote; it labels the thread and never names the sender.
 */
@Serializable
data class WhatsAppWindowResponse(
    val success: Boolean = false,
    val window: WhatsAppWindow = WhatsAppWindow(),
    val senderId: String? = null,
    val receivedOn: String? = null,
)

/**
 * The 24-hour customer-service window. [expiresAt] is an ISO-8601 string while [open], null when
 * closed (a closed window offers a template instead). This module owns no date parsing.
 */
@Serializable
data class WhatsAppWindow(
    val open: Boolean = false,
    val expiresAt: String? = null,
)

/** `GET /api/district/workspace/whatsapp/templates?workspaceId=&senderId=`. */
@Serializable
data class WhatsAppTemplatesResponse(
    val success: Boolean = false,
    val templates: List<WhatsAppTemplate> = emptyList(),
)

/**
 * One message template. Only an `approved` [status] is sendable. [variableCount] is the number of
 * positional `{{n}}` variables in [body], or null when it uses named ones or has no body text.
 * [senderId] is null for a template not tied to one sender.
 */
@Serializable
data class WhatsAppTemplate(
    val id: String = "",
    val senderId: String? = null,
    val name: String = "",
    val language: String = "",
    val category: String = "",
    val status: String = "",
    val body: String = "",
    val variableCount: Int? = null,
)
