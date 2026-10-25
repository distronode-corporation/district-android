package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `POST /api/district/calls/{callId}/decline`: a person refused a ring on this device.
 *
 * ⚠️ `success` IS THE WHOLE BODY, AND NOTHING READS IT. The decline is fire-and-forget by design:
 * the route is idempotent, answers 200 when its Redis is down (the ring then simply runs to its
 * timeout, as it always did), and the phone has already stopped ringing by the time it returns. It
 * is modelled so the fixture is decoded strictly like every other one, which is what makes a field
 * the server adds fail here rather than ship unnoticed.
 */
@Serializable
data class CallDeclineResponse(
    val success: Boolean = false,
)

/**
 * `GET /api/district/messages/{id}/sender?workspaceId=`: the display name of a message's sender,
 * for the notification's title.
 *
 * ⛔ [name] IS NULL WHENEVER THE THREAD HAS NO NAMED CONTACT, AND NULL IS NOT AN ERROR. The route
 * never falls back to the address: an unnamed sender is a phone number or an email address, and that
 * is exactly what must stay off a lock screen. A null name keeps the notification's generic title.
 */
@Serializable
data class MessageSenderResponse(
    val success: Boolean = false,
    val name: String? = null,
)

/**
 * `POST /api/auth/native/notify-token`: the scoped, read-only credential the iOS notification
 * service extension uses to call the sender route above.
 *
 * ⚠️ MODELLED, NOT CALLED. This client builds its notifications in its own process, under its own
 * session, so it has no extension and no use for a second credential (`EndpointParity` lists the
 * endpoint as iOS-only). The DTO exists so the fixture is decoded strictly with the rest of the
 * corpus: `ContractManifest.NOT_YET_MODELLED` may not grow.
 */
@Serializable
data class NotifyTokenResponse(
    val success: Boolean = false,
    val token: String = "",
    /** ⚠️ An ISO-8601 UTC string. This module parses no dates. */
    val expiresAt: String = "",
)
