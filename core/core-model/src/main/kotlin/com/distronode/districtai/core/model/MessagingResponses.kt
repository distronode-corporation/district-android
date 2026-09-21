package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/workspace/messaging` — which carrier accounts this workspace sends through.
 *
 * ⛔ THE READ IS REDACTED, AND THAT IS WHAT MAKES THE WRITE SAFE. An edit form never has to ask an
 * operator to retype a live carrier secret into a mobile keyboard: a blank secret field means "keep
 * the stored ciphertext" (`buildEncryptedProviderConfig` preserves `existing[field]`), so the
 * ordinary edit retypes nothing. The write types live in `MessagingRequests.kt`; no credential may
 * appear on this type. A delete frees phone numbers another tenant could then claim, which is
 * answered by a confirmation that names the consequence, not by hiding the action. See
 * `MessagingDeleteRequest`.
 *
 * ⚠️ ADMITS `viewer`, unlike `workspace/config`. Nothing here is a credential and nothing is a
 * staff transfer number: it is the workspace's own outbound identity. A viewer genuinely reaches
 * this screen through the settings hub, so the "admits viewer" note is load-bearing.
 */
@Serializable
data class MessagingResponse(
    val success: Boolean = false,
    val accounts: List<MessagingAccount> = emptyList(),
    /** ⛔ Its OWN field, never an entry in [accounts]. See [ManagedAccount]. */
    val managedAccount: ManagedAccount? = null,
    /**
     * ⚠️ ABSENT — not null — WHEN THE WORKSPACE HAS NO ACCOUNTS. The route computes it with
     * `effectiveDefaultId`, which returns `undefined` for an empty list, and `JSON.stringify`
     * drops an undefined value's key entirely. Defaulted here rather than required for that reason.
     */
    val defaultAccountId: String? = null,
    /**
     * Per-channel sender overrides, keyed by channel name (`sms`, `whatsapp`, …).
     *
     * ⚠️ AN OPEN MAP, NOT AN ENUM OF CHANNELS. The server stores whatever key was set, so a
     * channel added on the web must render here rather than fail to decode.
     */
    val channelDefaults: Map<String, String> = emptyMap(),
)

/**
 * One carrier account the workspace sends through.
 *
 * ⚠️ [credentialSource] IS `byok` OR `managed`, and it is what an operator needs to see: a `byok`
 * account bills on their own carrier account and a `managed` one bills through us. The route
 * defaults it to `byok` when a legacy config omitted it, so it is never absent in practice.
 *
 * ⛔ NO CREDENTIAL FIELD EXISTS ON THIS TYPE AND NONE MAY BE ADDED. The route projects only these
 * five keys out of `providerConfig`; the account SID, auth token, key secret and application
 * secret never leave the server on this path. A field added here would be a request to widen the
 * route, not a decoding fix.
 */
@Serializable
data class MessagingAccount(
    val id: String = "",
    val provider: String? = null,
    val label: String? = null,
    val credentialSource: String? = null,
    val phoneNumbers: List<String> = emptyList(),
)

/**
 * The PLATFORM's carrier account for this workspace: numbers Distronode bought on the tenant's
 * behalf, on our credentials.
 *
 * ⛔ IT IS NOT AN ENTRY IN [MessagingResponse.accounts] AND MUST NOT BE RENDERED AS ONE. That array
 * is the id space `resolveSendingContext` validates a `from` against — a send whose sender id no
 * account owns is rejected outright as a spoofing attempt — so a synthetic managed entry would be
 * a pickable sender whose every send fails. It has no id here for exactly that reason.
 *
 * ⚠️ [provider] IS OPTIONAL EVEN WHEN THE OBJECT EXISTS. `projectManagedSummary` rebuilds a
 * two-field object and omits `provider` when the stored value is missing or blank; it returns null
 * outright when there are no numbers, which is the `managedAccount: null` branch.
 */
@Serializable
data class ManagedAccount(
    val provider: String? = null,
    val phoneNumbers: List<String> = emptyList(),
)

/**
 * What an upsert answers.
 *
 * ⛔ IT ECHOES IDS AND NOTHING ELSE, SO A SAVE MUST BE FOLLOWED BY A RE-READ. The route returns
 * `{success, accountId, defaultAccountId}` — no label, no provider, no numbers — so there is
 * nothing here to patch the on-screen list with. Appending a row built from the request would show
 * whatever the operator typed rather than what was stored, and the two genuinely differ: the label
 * is trimmed, a blank one is replaced by `defaultLabelFor`, and `phoneNumbers` is filtered.
 *
 * ⚠️ [accountId] IS THE SERVER'S, NOT THE REQUEST'S, on a create — `acct-<uuid>` minted inside the
 * transaction. On an edit it is the id that was sent back unchanged.
 *
 * ⚠️ [defaultAccountId] CAN CHANGE WITHOUT BEING ASKED TO. Saving the first account of a workspace
 * makes it the default (`if (makeDefault || !defaultAccountId)`), so a create that did not tick the
 * box can still come back naming itself.
 */
@Serializable
data class MessagingAccountSaveResponse(
    val success: Boolean = false,
    val accountId: String? = null,
    val defaultAccountId: String? = null,
)

/**
 * What `setDefault` and `delete` answer — the SAME shape, deliberately shared.
 *
 * ⛔ ONE TYPE FOR TWO ACTIONS BECAUSE THE ROUTE GENUINELY RETURNS ONE SHAPE, and splitting it would
 * imply a difference the server does not make. Both answer `{success, defaultAccountId}`.
 *
 * ⚠️ [defaultAccountId] IS ABSENT — not null — WHEN THE LAST ACCOUNT WAS DELETED. `mirrorLegacy`
 * assigns `undefined` for an empty list and `JSON.stringify` drops the key, which is why this
 * defaults rather than being required. It is also the reason a delete cannot be rendered from this
 * response alone: "no default" and "the field did not arrive" look identical.
 */
@Serializable
data class MessagingDefaultResponse(
    val success: Boolean = false,
    val defaultAccountId: String? = null,
)

/**
 * What `setChannelDefault` answers.
 *
 * ⚠️ THE WHOLE MAP COMES BACK, not just the channel that changed, because the route merges and
 * echoes. So this one write needs no re-read to redraw the channel section — though the screen
 * re-reads anyway, since the account LIST is what a channel default is rendered against.
 */
@Serializable
data class MessagingChannelDefaultResponse(
    val success: Boolean = false,
    val channelDefaults: Map<String, String> = emptyMap(),
)

/**
 * What `meta` answers.
 *
 * ⛔ A BARE `{success:true}` WITH NO ECHO, so the stored creator cell number cannot be read back
 * from a write. Nothing in this client can read it at all — it is not on the messaging GET — which
 * is why the form asks for a new value rather than pre-filling one. Same shape of problem as the
 * workspace rename, and handled the same way.
 */
@Serializable
data class MessagingMetaResponse(val success: Boolean = false)

/**
 * What `messaging/test` answers.
 *
 * ⛔ A FAILED CREDENTIAL CHECK IS AN HTTP **200** WITH `success:false`, AND THAT IS THE TRAP ON THIS
 * TYPE. Every other envelope in this client treats `success:false` on a 200 as contract drift —
 * `rejectedEnvelope` turns it into a decode failure — because for every other route it means the
 * body was structurally empty. Here it is the ANSWER: the route catches a carrier's 401 and returns
 * `{success:false, error}` with a 200 on purpose, so the operator is told "these keys do not
 * authenticate" rather than "the server broke". `MessagingRepository.testCredentials` is therefore
 * the one call that must NOT run the envelope guard.
 *
 * ⚠️ [details] IS TWO DIFFERENT SHAPES. Twilio answers `{friendlyName, status}`; Sinch and Telnyx
 * answer `{message}`. Every field is nullable for that reason — a type per provider would be three
 * types for one button, and a required field would throw on the other two providers' success.
 */
@Serializable
data class MessagingTestResponse(
    val success: Boolean = false,
    val error: String? = null,
    val details: MessagingTestDetails? = null,
)

/** ⚠️ The union of both provider shapes. See the ⚠️ on [MessagingTestResponse.details]. */
@Serializable
data class MessagingTestDetails(
    val friendlyName: String? = null,
    val status: String? = null,
    val message: String? = null,
)
