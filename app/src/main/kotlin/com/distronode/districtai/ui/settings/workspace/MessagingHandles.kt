package com.distronode.districtai.ui.settings.workspace

/**
 * Every per-item test handle the messaging screens mint.
 *
 * ⛔ ONE FILE FOR ALL OF THEM BECAUSE detekt CAPS A FILE AT 11 TOP-LEVEL FUNCTIONS AND THE MESSAGING
 * SURFACE IS FOUR FILES. Collecting the derivers here is what keeps `MessagingScreen`,
 * `MessagingAccountForm` and `MessagingEditDialogs` each under the ceiling without any of them
 * needing a suppression — the same "another file rather than a raised threshold" call
 * `MembersDialogs` and `ContractFixtures` record.
 *
 * ⛔ AND IT IS THE REASON A ROW AND ITS TEST CANNOT DRIFT. Every one of these is derived, never
 * typed out at a call site, so renaming a handle is one edit and a test asserting the old string
 * fails to compile rather than silently matching nothing. `onNodeWithContentDescription` returning
 * no node is a PASS for `assertDoesNotExist`, which is exactly the assertion the viewer cases below
 * rely on — a literal duplicated into one of those tests would make it pass for the wrong reason.
 */

/** The account row itself, and the three controls on it. */
internal fun messagingAccountDescription(accountId: String): String =
    "district-messaging-account-$accountId"

internal fun messagingEditDescription(accountId: String): String =
    "district-messaging-edit-$accountId"

internal fun messagingRemoveDescription(accountId: String): String =
    "district-messaging-remove-$accountId"

internal fun messagingDefaultDescription(accountId: String): String =
    "district-messaging-default-$accountId"

/**
 * The channel family.
 *
 * ⚠️ THREE SEPARATE DERIVERS, NOT ONE, BECAUSE ALL THREE CAN BE ON SCREEN AT ONCE. A stored
 * override renders as a row, its setter renders as a button, and the picker's options render inside
 * a dialog — sharing one handle would make `onNodeWithContentDescription` ambiguous the moment a
 * test opened the picker over a list that already had an override.
 */
internal fun messagingChannelDescription(channel: String): String =
    "district-messaging-channel-$channel"

internal fun messagingChannelSetDescription(channel: String): String =
    "district-messaging-channel-set-$channel"

internal fun messagingChannelOptionDescription(accountId: String): String =
    "district-messaging-channel-option-$accountId"

/** The account form's pickers and credential boxes. */
internal fun messagingProviderDescription(provider: String): String =
    "district-messaging-provider-$provider"

internal fun messagingSourceDescription(source: String): String =
    "district-messaging-source-$source"

/**
 * ⚠️ KEYED ON THE ROUTE'S OWN FIELD NAME (`accountSid`, `keySecret`, …) rather than on a display
 * label, so a test asserting that a box exists is asserting about the key that will be SENT.
 */
internal fun messagingCredentialDescription(key: String): String =
    "district-messaging-credential-$key"
