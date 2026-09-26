package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.MESSAGING_PROVIDER_TWILIO
import com.distronode.districtai.core.model.MESSAGING_SOURCE_BYOK
import com.distronode.districtai.core.model.MessagingAccount
import com.distronode.districtai.core.model.MessagingProviderConfig
import com.distronode.districtai.core.model.MessagingResponse
import com.distronode.districtai.core.model.messagingCredentialFields
import com.distronode.districtai.ui.FailureText

/**
 * What the messaging screen holds.
 *
 * ⛔ THE ROUTE'S HAZARDS ARE EXPRESSED IN THE STATE RATHER THAN AVOIDED. It deletes accounts
 * (confirmed, with the phone-number release named out loud) and re-points the default sender (a
 * one-tap, reversible row control). It takes plaintext carrier credentials on the WIRE, but that
 * is no reason the FORM has to ask for them: a blank secret box means "keep the stored
 * ciphertext", which is what [MessagingDraft.secrets] encodes.
 *
 * ⛔ [canEdit] IS A UX GATE AND NEVER A SECURITY ONE. The server re-checks
 * `requireWorkspaceRole(["agency","client"])` on every write. Its job is that a viewer, who
 * genuinely arrives here, is never OFFERED a control that would 403. Every write in
 * [MessagingViewModel] re-checks it anyway, because a state flag is not a call site.
 *
 * ⚠️ FIVE SEPARATE [SaveState]s RATHER THAN ONE. The five writes are independent operations that
 * can be in flight or have failed on their own, and a shared banner would report a failed delete as
 * a failed default change. [busy] is the union, which is what disables the form.
 *
 * ⚠️ THE WHOLE READ ENVELOPE IS CARRIED, because `defaultAccountId` and `channelDefaults` are what
 * answer the question this screen exists for: which identity does a message actually leave from.
 */
data class MessagingUiState(
    val load: MessagingLoadState = MessagingLoadState.Loading,
    val canEdit: Boolean = false,
    /** ⚠️ Non-null exactly while the add/edit sheet is open. Null is "not editing anything". */
    val draft: MessagingDraft? = null,
    /**
     * ⛔ NEVER PRE-FILLED, AND THE REASON IS THE SAME ONE THE WORKSPACE RENAME RECORDS: nothing
     * this client can read returns the stored creator cell number (it is not on the messaging GET),
     * so a field seeded from what we know could only be blank — which is the shape that writes a
     * blank over a real value. The section says so, and an empty draft disables the save.
     */
    val creatorCellDraft: String = "",
    val accountSave: SaveState = SaveState.Idle,
    val defaultSave: SaveState = SaveState.Idle,
    val channelSave: SaveState = SaveState.Idle,
    val deleteSave: SaveState = SaveState.Idle,
    val metaSave: SaveState = SaveState.Idle,
    val test: MessagingTestState = MessagingTestState.Idle,
) {

    val messaging: MessagingResponse?
        get() = (load as? MessagingLoadState.Ready)?.messaging

    val accounts: List<MessagingAccount> get() = messaging?.accounts.orEmpty()

    val busy: Boolean
        get() = accountSave.busy || defaultSave.busy || channelSave.busy ||
            deleteSave.busy || metaSave.busy || test == MessagingTestState.Running

    /**
     * ⛔ EDITING REQUIRES A SUCCESSFUL READ, WHICH IS NOT THE SAME RULE AS THE ARRAY EDITORS' AND IS
     * NOT AS STRICT AS THEIRS EITHER. Nothing here is a wholesale replace — an account is created
     * and edited BY ID and the route merges `providerConfig` field-wise — so a form built from
     * nothing could not delete a stored value the way a blank `callDirectory` does. It is gated
     * anyway for a narrower reason: an EDIT needs the account it is editing, and "add an account"
     * offered against a list that failed to load invites a duplicate of one already there.
     */
    val canEditNow: Boolean get() = canEdit && load is MessagingLoadState.Ready && !busy

    /** ⚠️ A create needs no id; an edit needs one this workspace actually holds. */
    fun accountFor(accountId: String): MessagingAccount? = accounts.firstOrNull { it.id == accountId }

    /** ⛔ Blank writes an empty string rather than clearing nothing — so the button demands one. */
    val canSaveCreatorCell: Boolean get() = canEditNow && creatorCellDraft.isNotBlank()
}

/**
 * The read's own load state.
 *
 * ⚠️ SEPARATE FROM [ConfigState] BECAUSE IT IS A DIFFERENT READ WITH A DIFFERENT PAYLOAD, the same
 * reason [KnowledgeListState] is. Reusing the config state would imply this screen hydrates from
 * `workspace/config`, which excludes viewers — the opposite of this route.
 */
sealed interface MessagingLoadState {

    data object Loading : MessagingLoadState

    /** ⚠️ An empty `accounts` list is a real answer: a workspace with no carrier connected. */
    data class Ready(val messaging: MessagingResponse) : MessagingLoadState

    data class Failed(val failure: FailureText) : MessagingLoadState
}

/**
 * One account being created or edited.
 *
 * ⛔ [secrets] IS KEYED BY THE ROUTE'S OWN FIELD NAMES AND A BLANK ENTRY MEANS "KEEP". That is the
 * whole reason this form can exist against a redacted read: `buildEncryptedProviderConfig` reuses
 * `existing[field]` when the incoming value is blank or absent, so an operator editing a label
 * never touches a credential. [toProviderConfig] therefore OMITS a blank rather than sending `""`,
 * because the two are only equivalent for secret fields — for a plaintext one (`projectId`) an
 * empty string would be merged in and stored.
 *
 * ⛔ CHANGING [provider] ON AN EXISTING ACCOUNT DISCARDS THE STORED SECRETS. The route only reuses
 * `existingEnc` when the provider is unchanged, so on a provider switch a blank box means "store
 * nothing" rather than "keep" — [secretsRequired] is what the UI uses to say so.
 *
 * ⛔ [phoneNumbers] IS SENT ONLY WHEN [numbersEdited]. Sending the parsed list unconditionally would
 * make "open the form, change the label, save" rewrite the account's numbers from whatever this
 * client happened to render — and an empty box would REMOVE them all and release their hub claims.
 * The read gives us the numbers, so the round trip is usually lossless; "usually" is not good
 * enough for the field that routes inbound calls.
 */
data class MessagingDraft(
    /** ⚠️ Null for a create. The server mints `acct-<uuid>`; nothing here may invent one. */
    val accountId: String? = null,
    val provider: String = MESSAGING_PROVIDER_TWILIO,
    val credentialSource: String = MESSAGING_SOURCE_BYOK,
    val label: String = "",
    val secrets: Map<String, String> = emptyMap(),
    /** ⚠️ One per line as typed. Parsed by [parsedNumbers]; never sent unless [numbersEdited]. */
    val phoneNumbers: String = "",
    val numbersEdited: Boolean = false,
    val makeDefault: Boolean = false,
    /** ⚠️ The provider this draft STARTED on, so a switch can be detected. Null for a create. */
    val originalProvider: String? = null,
) {

    val isCreate: Boolean get() = accountId == null

    /**
     * ⛔ TRUE WHEN A BLANK SECRET WOULD BE STORED AS ABSENT RATHER THAN PRESERVED — a create, or an
     * edit that switched provider. The form says so instead of letting someone save an account with
     * no credentials at all, which the route accepts (it deletes the key) and which then fails at
     * send time with nothing pointing back here.
     */
    // ⚠️ No separate null test on [originalProvider]: it is null exactly for a create, which
    // [isCreate] has already answered, and every edit is seeded by [of] with a non-null one.
    val secretsRequired: Boolean get() = isCreate || originalProvider != provider

    /** ⚠️ Blank lines dropped and each entry trimmed, mirroring the route's own filter. */
    val parsedNumbers: List<String>
        get() = phoneNumbers.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * ⛔ EVERY SECRET FIELD MUST BE FILLED WHEN [secretsRequired], BECAUSE A HALF-FILLED SET IS
     * WORSE THAN AN EMPTY ONE. The route encrypts what it gets and drops what it does not, so
     * saving Twilio with a SID and no auth token stores a credential that can never authenticate.
     */
    val credentialsComplete: Boolean
        get() = messagingCredentialFields(provider)
            .filter { it.secret }
            .all { secrets[it.key].orEmpty().isNotBlank() }

    val canSave: Boolean get() = !secretsRequired || credentialsComplete

    /**
     * ⛔ THE PROBE NEEDS PLAINTEXT THIS FORM ACTUALLY HOLDS, so it is offered only when every field
     * the test route reads for this provider is filled — including Sinch's PLAINTEXT `projectId`,
     * which its client constructor requires and which is not a secret. Sending blanks would report
     * a carrier's 401 as though the STORED credentials were broken, on a screen where believing
     * that leads someone to retype a live key.
     */
    val canTest: Boolean
        get() = messagingCredentialFields(provider)
            .all { secrets[it.key].orEmpty().isNotBlank() }

    /**
     * The wire shape.
     *
     * ⚠️ `provider` IS OMITTED HERE AND SET ONLY FOR THE TEST ROUTE — see [toTestProviderConfig].
     * The save route deletes the key on arrival, so including it would be harmless and misleading.
     */
    fun toProviderConfig(): MessagingProviderConfig = providerConfig(includeProvider = false)

    /** ⚠️ The same values plus `provider`, which `messaging/test` dispatches on. */
    fun toTestProviderConfig(): MessagingProviderConfig = providerConfig(includeProvider = true)

    private fun providerConfig(includeProvider: Boolean): MessagingProviderConfig {
        // ⛔ `takeIf { isNotBlank() }` ON EVERY FIELD, WHICH IS THE OMIT-VERSUS-EMPTY RULE. A null
        // is dropped from the body by `explicitNulls = false`; an empty string is not, and for a
        // plaintext key it would overwrite what is stored.
        fun value(key: String): String? = secrets[key]?.trim()?.takeIf { it.isNotEmpty() }
        return MessagingProviderConfig(
            provider = provider.takeIf { includeProvider },
            phoneNumbers = parsedNumbers.takeIf { numbersEdited },
            accountSid = value("accountSid"),
            authToken = value("authToken"),
            projectId = value("projectId"),
            keyId = value("keyId"),
            keySecret = value("keySecret"),
            applicationKey = value("applicationKey"),
            applicationSecret = value("applicationSecret"),
            apiKey = value("apiKey"),
        )
    }

    companion object {
        /**
         * Seed a draft from an account the read returned.
         *
         * ⛔ NO SECRET IS SEEDED AND NONE CAN BE. The GET projects five keys and none of them is a
         * credential; there is nothing on this device to pre-fill with, which is exactly why a
         * blank box has to mean "keep".
         *
         * ⚠️ [numbersEdited] STARTS FALSE even though the box is pre-filled from the read. The box
         * shows what is stored so the operator can see it; only a keystroke makes the list part of
         * the save.
         */
        fun of(account: MessagingAccount): MessagingDraft = MessagingDraft(
            accountId = account.id,
            provider = account.provider ?: MESSAGING_PROVIDER_TWILIO,
            credentialSource = account.credentialSource ?: MESSAGING_SOURCE_BYOK,
            label = account.label.orEmpty(),
            phoneNumbers = account.phoneNumbers.joinToString("\n"),
            numbersEdited = false,
            originalProvider = account.provider ?: MESSAGING_PROVIDER_TWILIO,
        )
    }
}

/**
 * The credential probe's state.
 *
 * ⛔ [Rejected] AND [Unreachable] ARE SEPARATE CASES BECAUSE THEY ARE DIFFERENT ANSWERS. "The
 * carrier says these keys are wrong" is about what was typed; "we could not ask" is about the
 * network. Rendering the second as the first tells someone their working credentials are broken.
 */
sealed interface MessagingTestState {

    data object Idle : MessagingTestState

    data object Running : MessagingTestState

    /** @param detail an account name from Twilio, or a bare confirmation from the other two. */
    data class Passed(val detail: String?) : MessagingTestState

    /** ⚠️ Arrived as an HTTP 200 with `success:false`. The server's own sentence. */
    data class Rejected(val message: String) : MessagingTestState

    /** ⛔ Says nothing about the credentials. Includes the 10/min rate limit. */
    data class Unreachable(val failure: FailureText) : MessagingTestState
}
