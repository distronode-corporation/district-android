// ⚠️ FILE-SCOPED, because `@EncodeDefault` is opted into by four separate properties below and a
// per-declaration annotation would be four chances to forget one — where forgetting means the
// action silently stops reaching the wire. See the ⛔ on the header comment.
@file:OptIn(ExperimentalSerializationApi::class)

package com.distronode.districtai.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The write half of `PATCH /api/district/workspace/messaging`.
 *
 * ⛔ THE PHONE EDITS MESSAGING CONFIGURATION, AND TWO RISKS SHAPE HOW. (a) A delete releases the
 * phone-number claims that stop another tenant sending as this workspace; that is real and is
 * answered by a confirmation that NAMES the consequence. (b) An edit form might seem to need an
 * operator to retype a live carrier secret on a phone keyboard; it does not, because the GET is
 * redacted and a blank secret field means "keep the stored ciphertext", so the ordinary edit types
 * no secret at all.
 *
 * ⛔ ONE REQUEST TYPE PER ACTION, NOT ONE BODY WITH AN `action` STRING THE CALLER PICKS. The route
 * is a five-way switch on that string and the branches are not interchangeable — `delete` removes
 * an account and frees its numbers, `setDefault` re-points every outbound send. A shared type would
 * make "the wrong action" a one-character mistake that still compiles. Each type below bakes its
 * own action in as a default, and the UPSERT deliberately has no `action` field at all because the
 * route reaches it through the switch's `default` arm.
 *
 * ⛔ EVERY `action` IS `@EncodeDefault`, AND WITHOUT IT NONE OF THEM REACHES THE WIRE.
 * kotlinx.serialization OMITS a property still holding its declared default unless `encodeDefaults`
 * is on, which this client leaves off. Without the annotation the four action bodies below go out
 * WITHOUT their action, each falls through the route's switch to `handleUpsert`, and each answers
 * 400 "Missing required fields". `WorkspaceEditRequestTest` pins it by asserting the encoded body
 * rather than the object, which is the only layer that can.
 *
 * ⚠️ ANNOTATED RATHER THAN HAVING THE DEFAULT REMOVED, WHICH IS THE OPPOSITE CALL FROM
 * [SendMessageRequest] AND FOR THE OPPOSITE REASON. There, the default was "only ever a way for a
 * value to go missing" because every real caller passes a channel. Here NO caller should pass an
 * action — it is a property of the type, and making it a constructor argument would put the
 * one-character mistake back. Flipping `encodeDefaults` on the shared body encoder was also
 * rejected: it would change the shape of every other request this client builds.
 */

/** The carriers `PROVIDERS` in the route allows. ⛔ Anything else is a 400 "Unsupported provider". */
const val MESSAGING_PROVIDER_TWILIO: String = "twilio"
const val MESSAGING_PROVIDER_SINCH: String = "sinch"
const val MESSAGING_PROVIDER_TELNYX: String = "telnyx"

/** ⚠️ The route's own order, so the picker reads the way the web console does. */
val MESSAGING_PROVIDERS: List<String> = listOf(
    MESSAGING_PROVIDER_TWILIO,
    MESSAGING_PROVIDER_SINCH,
    MESSAGING_PROVIDER_TELNYX,
)

/**
 * Whose carrier account is billed.
 *
 * ⛔ `managed` IS AN ENTITLEMENT, NOT A PREFERENCE, AND THE SERVER DECIDES. Sending it makes the
 * credentials resolver hand this workspace the PLATFORM's shared Sinch/Telnyx keys, so the route
 * checks `isEntitledToManagedCredentials` independently and answers **403** with a sentence naming
 * support when the workspace is not on a managed plan. This client offers the option and surfaces
 * that refusal; it must never pre-decide entitlement, because nothing it can read tells it.
 */
const val MESSAGING_SOURCE_BYOK: String = "byok"
const val MESSAGING_SOURCE_MANAGED: String = "managed"

val MESSAGING_CREDENTIAL_SOURCES: List<String> = listOf(
    MESSAGING_SOURCE_BYOK,
    MESSAGING_SOURCE_MANAGED,
)

/** ⛔ The route validates against exactly these three; a fourth is a 400 "Invalid channel: X". */
val MESSAGING_CHANNELS: List<String> = listOf("sms", "voice", "whatsapp")

/**
 * One credential field on the account form.
 *
 * ⛔ [secret] IS WHAT MAKES A BLANK FIELD SAFE. The route's `SECRET_FIELDS` map is the server-side
 * twin of this list: for a secret, a blank or absent incoming value means "keep the stored
 * ciphertext", which is the only reason an edit form can exist against a redacted read. For a
 * PLAINTEXT field (`projectId`) a blank is just a blank — it merges field-wise over the stored
 * config like any other key, so leaving it empty on an edit preserves it only because the key is
 * omitted entirely rather than sent as "".
 */
data class MessagingCredentialField(val key: String, val secret: Boolean)

/**
 * The fields one provider needs, in the order the form draws them.
 *
 * ⛔ MIRRORS `SECRET_FIELDS` IN THE ROUTE AND MUST NOT DRIFT FROM IT. A key spelled differently
 * here is not a validation error anywhere: `buildEncryptedProviderConfig` carries unknown keys
 * through as PLAINTEXT identifiers, so a misspelled `authToken` would be stored unencrypted and the
 * real one would be deleted (its `else` arm drops a secret field that arrives blank with nothing
 * stored). `MessagingRequestsTest` pins the three lists against this file.
 *
 * ⚠️ SINCH'S `projectId` IS NOT A SECRET AND IS STORED IN THE CLEAR, which is why it is on the form
 * at all — `effectiveCredentialsForProbe` and the test route both read it as plaintext. `smsRegion`
 * is deliberately NOT offered: it is a stored plaintext key that defaults to `us` server-side, and
 * a free-text region box on a phone is a way to break an EU account's routing with a typo. An edit
 * from this client omits the key, so a stored value survives the field-wise merge untouched.
 */
fun messagingCredentialFields(provider: String): List<MessagingCredentialField> = when (provider) {
    MESSAGING_PROVIDER_TWILIO -> listOf(
        MessagingCredentialField("accountSid", secret = true),
        MessagingCredentialField("authToken", secret = true),
    )
    MESSAGING_PROVIDER_SINCH -> listOf(
        MessagingCredentialField("projectId", secret = false),
        MessagingCredentialField("keyId", secret = true),
        MessagingCredentialField("keySecret", secret = true),
        MessagingCredentialField("applicationKey", secret = true),
        MessagingCredentialField("applicationSecret", secret = true),
    )
    MESSAGING_PROVIDER_TELNYX -> listOf(
        MessagingCredentialField("apiKey", secret = true),
    )
    // ⚠️ EMPTY RATHER THAN A THROW. A provider added server-side must not crash the settings screen
    // on an already-installed build; the form renders no credential boxes and the save is refused
    // by the route's own allowlist, which is the honest place for that refusal.
    else -> emptyList()
}

/**
 * The provider-specific half of a save.
 *
 * ⛔ EVERY FIELD IS NULLABLE AND NULLS ARE OMITTED ON THE WIRE (`explicitNulls = false` in
 * `HttpDistrictApi.BODY_JSON`), WHICH IS THE ENTIRE CONTRACT. The route merges field-wise
 * (`{...existing, ...incoming}`) and treats an ABSENT secret as "keep the stored ciphertext". An
 * explicit `null` would land in the merged object and, for a plaintext key, be persisted as null.
 * Do not add `= ""` defaults here: an empty string for a SECRET is also read as "keep", but for a
 * plaintext identifier it OVERWRITES the stored value with a blank.
 *
 * ⛔ [phoneNumbers] IS THE DANGEROUS ONE AND IS NULLABLE FOR THAT REASON. The route filters and
 * stores exactly what arrives, then claims each number in the hub index — so sending `[]` on an
 * edit does not "leave the numbers alone", it removes them from the account. Omit the key unless
 * the operator actually edited the list.
 *
 * ⚠️ [provider] IS FOR THE **TEST** ROUTE ONLY. `messaging/test` dispatches on
 * `providerConfig.provider`, while the save route deletes the key on arrival (`delete out.provider`)
 * — so it is harmless on a save and required on a test.
 */
@Serializable
data class MessagingProviderConfig(
    val provider: String? = null,
    val phoneNumbers: List<String>? = null,
    val accountSid: String? = null,
    val authToken: String? = null,
    val projectId: String? = null,
    val keyId: String? = null,
    val keySecret: String? = null,
    val applicationKey: String? = null,
    val applicationSecret: String? = null,
    val apiKey: String? = null,
)

/**
 * Create or edit one carrier account.
 *
 * ⛔ NO `action` FIELD. The route's switch falls through to `handleUpsert` for an absent or
 * unrecognised action, so adding one here could only ever route this body somewhere else.
 *
 * ⛔ [accountId] IS THE ONLY THING SEPARATING AN EDIT FROM A CREATE, and a create is the one
 * non-idempotent call on this surface (a second delivery mints a second `acct-<uuid>`). See
 * `MessagingApi.saveMessagingAccount` for why the client's single 401 retry cannot duplicate one.
 *
 * ⛔ ALL THREE OF [activeProvider], [credentialSource] AND [providerConfig] ARE REQUIRED — the route
 * answers **400 "Missing required fields"** if any is absent, on an EDIT as much as on a create. So
 * "change only the label" still sends the provider and the source; it is the providerConfig's blank
 * secrets that make that safe.
 *
 * ⚠️ [creatorCellNumber] RIDES THE UPSERT because the route writes it on the same workspace update.
 * It is also reachable on its own through the `meta` action; both are offered, because a workspace
 * with no carrier account at all has no upsert to attach it to.
 */
@Serializable
data class MessagingAccountRequest(
    val workspaceId: String,
    val activeProvider: String,
    val credentialSource: String,
    val providerConfig: MessagingProviderConfig,
    val accountId: String? = null,
    val label: String? = null,
    val makeDefault: Boolean? = null,
    val creatorCellNumber: String? = null,
)

/**
 * Make one account the workspace's default sender.
 *
 * ⚠️ REVERSIBLE AND CHEAP, which is why it is a row control rather than a confirmed action — unlike
 * [MessagingDeleteRequest], which frees phone numbers.
 */
@Serializable
data class MessagingDefaultRequest(
    val workspaceId: String,
    val accountId: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val action: String = "setDefault",
)

/**
 * Override the sender for ONE channel.
 *
 * ⚠️ MERGES PER FIELD rather than replacing the map: the route spreads the stored `channelDefaults`
 * and writes one key. So there is no way to CLEAR an override from this client, which is worth
 * knowing before someone reads the absence as a gap.
 */
@Serializable
data class MessagingChannelDefaultRequest(
    val workspaceId: String,
    val channel: String,
    val accountId: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val action: String = "setChannelDefault",
)

/**
 * Remove one carrier account.
 *
 * ⛔ IT ALSO RELEASES THE HUB'S CLAIM ON EVERY NUMBER ONLY THIS ACCOUNT HELD, and that is the part a
 * confirmation has to say out loud. `PhoneNumberIndex` is what routes an inbound call or SMS to a
 * workspace and what four sibling routes check ownership against; once a row is deleted the number
 * is unclaimed, and the next workspace to type it into its own settings can take it — the carrier
 * probe will agree, because whoever still owns it at the carrier is not us.
 *
 * ⚠️ THE DEFAULT MOVES SILENTLY. Deleting the default account re-points it at whichever account is
 * left, and the response echoes the new `defaultAccountId` rather than announcing the change.
 */
@Serializable
data class MessagingDeleteRequest(
    val workspaceId: String,
    val accountId: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val action: String = "delete",
)

/**
 * Write the workspace's creator cell number and nothing else.
 *
 * ⛔ ANSWERS **400 "Nothing to update"** FOR AN ABSENT VALUE, so this field is non-null and an
 * operator clearing it sends `""` — which the route stores as an empty string rather than
 * rejecting.
 */
@Serializable
data class MessagingMetaRequest(
    val workspaceId: String,
    val creatorCellNumber: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val action: String = "meta",
)

/**
 * `POST /api/district/workspace/messaging/test` — do these credentials authenticate?
 *
 * ⛔ IT TAKES PLAINTEXT CREDENTIALS THAT HAVE NOT BEEN SAVED, WHICH BOUNDS WHEN IT IS OFFERABLE.
 * The route's own header says so: the values are what the form just typed, pre-KMS. So it can be
 * offered for a CREATE, or for an edit in which every secret was re-typed — and it cannot work on
 * the ordinary edit, where the secret boxes are blank precisely so nobody has to retype a live key
 * on a phone. The UI gates on that rather than sending empties and reporting a carrier's 401 as if
 * the stored credentials were broken.
 *
 * ⛔ RATE LIMITED AT 10/MIN PER WORKSPACE, and the route explains why in security terms: it is a
 * credential-validation oracle that makes one authenticated third-party call per POST from our
 * origin IPs. A client-side retry on this would be spending someone else's reputation.
 */
@Serializable
data class MessagingTestRequest(
    val workspaceId: String,
    val providerConfig: MessagingProviderConfig,
)
