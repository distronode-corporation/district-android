package com.distronode.districtai.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.ProviderStatusEntry
import com.distronode.districtai.core.model.SipTrunk
import com.distronode.districtai.ui.InlineFailure

/** What the number-management tabs ask of their ViewModel, bundled. */
data class ProvisioningHandlers(
    val onRetryCarrier: () -> Unit = {},
    val onRetryVerify: () -> Unit = {},
    val onRetrySip: () -> Unit = {},
    val onSetVerify: (Boolean) -> Unit = {},
    val onSipDraft: (SipTrunkDraft) -> Unit = {},
    val onCreateSip: () -> Unit = {},
    val onLookupNumber: (String) -> Unit = {},
    val onRequestConfirmation: (NumberConfirmation) -> Unit = {},
    val onA2PDraft: (A2PDraft) -> Unit = {},
    val onTollFreeDraft: (TollFreeDraft) -> Unit = {},
    val onRegistrationDraft: (RegistrationDraft) -> Unit = {},
    val onCheckRequirements: () -> Unit = {},
    val onStartRegistration: () -> Unit = {},
    val onRetryRegistrations: () -> Unit = {},
    val onRemoveDocument: (bundleId: String, documentId: String) -> Unit = { _, _ -> },
)

/**
 * The Carrier tab (iOS `CarrierSection`, `NumberLookupCard`, `ComplianceSection`): the carrier
 * accounts, the one-time passcode sender, SIP trunks, the billed lookup and the messaging
 * registrations.
 */
@Composable
fun CarrierTab(state: NumberProvisioningUiState, canProvision: Boolean, handlers: ProvisioningHandlers) {
    ContentContainer {
        Column(
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter).testTag(CARRIER_TAB_TAG),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            NumberWriteNotice(state.carrierWrite)
            CarrierAccountsCard(state.carrier, handlers.onRetryCarrier)
            VerifyCard(state.verify, canProvision, state.carrierWrite, handlers)
            SipCard(state, canProvision, handlers)
            if (!canProvision) Muted(stringResource(R.string.numbers_viewer_cannot_provision))
            LookupCard(state, canProvision, handlers)
            ComplianceCards(state, canProvision, handlers)
        }
    }
}

@Composable
private fun CarrierAccountsCard(state: SectionState<CarrierAccounts>, onRetry: () -> Unit) {
    when (state) {
        SectionState.Loading -> SkeletonBlock(height = DistrictTheme.spacing.header)
        is SectionState.Failed -> InlineFailure(
            title = stringResource(R.string.numbers_carrier_eyebrow),
            failure = state.failure,
            onRetry = onRetry,
            description = CARRIER_FAILURE_TAG,
        )
        is SectionState.Ready -> if (state.value.notConnected) {
            EmptyState(
                title = stringResource(R.string.numbers_carrier_none_title),
                body = stringResource(R.string.numbers_carrier_none_body),
            )
        } else {
            SectionCard(stringResource(R.string.numbers_carrier_eyebrow)) {
                (state.value.connected + state.value.refused).forEach { name ->
                    CarrierRow(name, state.value.entries.getValue(name))
                }
            }
        }
    }
}

/**
 * ⚠️ A REFUSED CARRIER IS SAID SO AND CANNOT BE RECONNECTED HERE; a managed one shows no account
 * details, which are not the workspace's to see.
 */
@Composable
private fun CarrierRow(name: String, entry: ProviderStatusEntry) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = DistrictTheme.spacing.tight),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            Text(text = name, style = MaterialTheme.typography.titleSmall)
            DistrictBadge(
                text = stringResource(
                    if (entry.connected) R.string.numbers_carrier_connected else R.string.numbers_carrier_refused_badge,
                ),
                tone = if (entry.connected) Tone.Success else Tone.Danger,
            )
        }
        when {
            !entry.connected -> Muted(stringResource(R.string.numbers_carrier_refused))
            entry.managed == true -> Muted(stringResource(R.string.numbers_carrier_managed))
            else -> {
                entry.accountName?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                entry.balance?.let { balance ->
                    Muted(
                        entry.currency?.let { stringResource(R.string.numbers_carrier_balance_with_currency, balance, it) }
                            ?: stringResource(R.string.numbers_carrier_balance_amount, balance),
                    )
                }
                entry.numberCount?.let { Muted(pluralStringResource(R.plurals.numbers_carrier_number_count, it, it)) }
            }
        }
    }
}

/** ⛔ Enabling creates a sender the carrier bills for; the caption says so before the tap. */
@Composable
private fun VerifyCard(
    state: SectionState<VerifyService>,
    canProvision: Boolean,
    write: NumberWriteState,
    handlers: ProvisioningHandlers,
) {
    when (state) {
        SectionState.Loading -> SkeletonBlock(height = DistrictTheme.spacing.header)
        is SectionState.Failed -> InlineFailure(
            title = stringResource(R.string.numbers_verify_eyebrow),
            failure = state.failure,
            onRetry = handlers.onRetryVerify,
            description = VERIFY_FAILURE_TAG,
        )
        is SectionState.Ready -> SectionCard(stringResource(R.string.numbers_verify_eyebrow)) {
            val enabled = state.value.enabled
            Text(
                text = stringResource(if (enabled) R.string.numbers_verify_on else R.string.numbers_verify_off),
                style = MaterialTheme.typography.bodyMedium,
            )
            Muted(
                stringResource(
                    if (enabled) R.string.numbers_verify_disable_caption else R.string.numbers_verify_enable_caption
                )
            )
            if (canProvision) {
                DistrictButton(
                    text = stringResource(
                        if (enabled) R.string.numbers_verify_disable_action else R.string.numbers_verify_enable_action
                    ),
                    onClick = { handlers.onSetVerify(!enabled) },
                    variant = if (enabled) ButtonVariant.Secondary else ButtonVariant.Primary,
                    enabled = write.isArmed,
                    modifier = Modifier.testTag(VERIFY_TOGGLE_TAG),
                )
            }
        }
    }
}

/** ⛔ A trunk adds a monthly charge and cannot be removed here: the caption says so above the form. */
@Composable
private fun SipCard(state: NumberProvisioningUiState, canProvision: Boolean, handlers: ProvisioningHandlers) {
    when (val sip = state.sip) {
        SectionState.Loading -> SkeletonBlock(height = DistrictTheme.spacing.header)
        is SectionState.Failed -> InlineFailure(
            title = stringResource(R.string.numbers_sip_eyebrow),
            failure = sip.failure,
            onRetry = handlers.onRetrySip,
            description = SIP_FAILURE_TAG,
        )
        is SectionState.Ready -> SectionCard(stringResource(R.string.numbers_sip_eyebrow)) {
            if (sip.value.isEmpty()) Muted(stringResource(R.string.numbers_sip_empty_body))
            sip.value.forEach { SipTrunkRow(it) }
            if (canProvision) SipForm(state.sipDraft, state.carrierWrite, handlers)
        }
    }
}

@Composable
private fun SipTrunkRow(trunk: SipTrunk) {
    val addresses =
        pluralStringResource(
            R.plurals.numbers_sip_address_count,
            trunk.ipAccessControlList.size,
            trunk.ipAccessControlList.size
        )
    Column(modifier = Modifier.fillMaxWidth().padding(top = DistrictTheme.spacing.tight)) {
        Text(text = trunk.domain, style = MaterialTheme.typography.bodyMedium)
        Muted(stringResource(R.string.numbers_sip_trunk_meta_line, trunk.status, addresses))
        if (trunk.domainSid == null && trunk.ipAclSid == null) Muted(stringResource(R.string.numbers_sip_unprovisioned))
    }
}

@Composable
private fun SipForm(draft: SipTrunkDraft, write: NumberWriteState, handlers: ProvisioningHandlers) {
    val editable = write != NumberWriteState.Running
    Muted(stringResource(R.string.numbers_sip_one_way))
    NumberField(draft.name, { handlers.onSipDraft(draft.copy(name = it)) }, R.string.numbers_sip_name_label, editable)
    NumberField(
        draft.domain,
        { handlers.onSipDraft(draft.copy(domain = it)) },
        R.string.numbers_sip_domain_label,
        editable
    )
    Muted(stringResource(R.string.numbers_sip_domain_caption))
    NumberField(
        value = draft.ipAddresses,
        onChange = { handlers.onSipDraft(draft.copy(ipAddresses = it)) },
        label = R.string.numbers_sip_ip_label,
        enabled = editable,
        singleLine = false,
    )
    DistrictButton(
        text = stringResource(R.string.numbers_sip_create_action),
        onClick = handlers.onCreateSip,
        enabled = draft.isSubmittable && write.isArmed,
        modifier = Modifier.testTag(SIP_CREATE_TAG),
    )
}

/** ⛔ Billed per lookup, an invalid number included: confirmed first, one tap one lookup. */
@Composable
private fun LookupCard(state: NumberProvisioningUiState, canProvision: Boolean, handlers: ProvisioningHandlers) {
    if (!canProvision) return
    SectionCard(stringResource(R.string.numbers_lookup_eyebrow)) {
        NumberField(
            value = state.lookupNumber,
            onChange = handlers.onLookupNumber,
            label = R.string.numbers_lookup_number_label,
            enabled = state.lookupWrite != NumberWriteState.Running,
        )
        Muted(stringResource(R.string.numbers_lookup_confirm_body))
        DistrictButton(
            text = stringResource(R.string.numbers_lookup_action),
            onClick = { handlers.onRequestConfirmation(NumberConfirmation.Lookup) },
            variant = ButtonVariant.Secondary,
            enabled = state.lookupNumber.isNotBlank() && state.lookupWrite.isArmed,
            modifier = Modifier.testTag(LOOKUP_TAG),
        )
        NumberWriteNotice(state.lookupWrite)
        state.lookupResult?.let { result ->
            Text(
                text = if (result.valid) {
                    result.lines.joinToString(
                        " · "
                    )
                } else {
                    stringResource(R.string.numbers_lookup_invalid)
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(LOOKUP_RESULT_TAG),
            )
        }
    }
}

/** A card with an eyebrow, the shape every number-management card takes. */
@Composable
internal fun SectionCard(eyebrow: String, content: @Composable ColumnScope.() -> Unit) {
    DistrictCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(DistrictTheme.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            Eyebrow(eyebrow)
            content()
        }
    }
}

/** One labelled text field of a number-management form. */
@Composable
internal fun NumberField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    enabled: Boolean,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else MULTILINE_ROWS,
        colors = districtFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag(numberFieldTag(label)),
    )
}

private const val MULTILINE_ROWS = 3

/** A field's test handle, from its label resource. */
fun numberFieldTag(label: Int): String = "district-number-field-$label"

/** Stable handles for tests. */
const val CARRIER_TAB_TAG: String = "district-number-carrier-tab"
const val CARRIER_FAILURE_TAG: String = "district-number-carrier-failure"
const val VERIFY_FAILURE_TAG: String = "district-number-verify-failure"
const val VERIFY_TOGGLE_TAG: String = "district-number-verify-toggle"
const val SIP_FAILURE_TAG: String = "district-number-sip-failure"
const val SIP_CREATE_TAG: String = "district-number-sip-create"
const val LOOKUP_TAG: String = "district-number-lookup"
const val LOOKUP_RESULT_TAG: String = "district-number-lookup-result"
