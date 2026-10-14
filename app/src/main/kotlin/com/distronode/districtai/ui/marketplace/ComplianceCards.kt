package com.distronode.districtai.ui.marketplace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme

/**
 * The messaging registrations (iOS `ComplianceSection`): A2P 10DLC and toll-free verification.
 *
 * ⛔ NEITHER HAS A STATUS READ ANYWHERE, so the caption says what is shown is what the carrier
 * answered when it was submitted. Both are billed or reviewed by hand and are confirmed first.
 */
@Composable
internal fun ComplianceCards(state: NumberProvisioningUiState, canProvision: Boolean, handlers: ProvisioningHandlers) {
    Muted(stringResource(R.string.numbers_compliance_no_status))
    if (!canProvision) return
    A2PCard(state.a2pDraft, state.carrierWrite, handlers)
    TollFreeCard(state.tollFreeDraft, state.carrierWrite, handlers)
}

@Composable
private fun A2PCard(draft: A2PDraft, write: NumberWriteState, handlers: ProvisioningHandlers) {
    val editable = write != NumberWriteState.Running
    val update = handlers.onA2PDraft
    SectionCard(stringResource(R.string.numbers_a2p_title)) {
        Muted(stringResource(R.string.numbers_a2p_caption))
        NumberField(draft.businessName, {
            update(draft.copy(businessName = it))
        }, R.string.numbers_a2p_business_label, editable)
        NumberField(draft.ein, { update(draft.copy(ein = it)) }, R.string.numbers_a2p_ein_label, editable)
        NumberField(draft.website, { update(draft.copy(website = it)) }, R.string.numbers_a2p_website_label, editable)
        NumberField(
            draft.vertical,
            { update(draft.copy(vertical = it)) },
            R.string.numbers_a2p_vertical_label,
            editable
        )
        NumberField(
            value = draft.campaignDescription,
            onChange = { update(draft.copy(campaignDescription = it)) },
            label = R.string.numbers_a2p_description_label,
            enabled = editable,
            singleLine = false,
        )
        NumberField(draft.sampleOne, {
            update(draft.copy(sampleOne = it))
        }, R.string.numbers_a2p_sample_one_label, editable, false)
        NumberField(draft.sampleTwo, {
            update(draft.copy(sampleTwo = it))
        }, R.string.numbers_a2p_sample_two_label, editable, false)
        // ⚠️ THE CAMPAIGN TYPES ARE WIRE VALUES, SHOWN AS THEY ARE, as iOS shows them.
        Choices(
            values = A2P_CAMPAIGN_TYPES.associateWith { it },
            selected = draft.campaignType,
            enabled = editable,
        ) { update(draft.copy(campaignType = it)) }
        DistrictButton(
            text = stringResource(R.string.numbers_a2p_submit_action),
            onClick = { handlers.onRequestConfirmation(NumberConfirmation.A2P) },
            enabled = draft.isSubmittable && write.isArmed,
            modifier = Modifier.testTag(A2P_SUBMIT_TAG),
        )
    }
}

@Composable
private fun TollFreeCard(draft: TollFreeDraft, write: NumberWriteState, handlers: ProvisioningHandlers) {
    val editable = write != NumberWriteState.Running
    val update = handlers.onTollFreeDraft
    val useCases = TOLL_FREE_USE_CASES.zip(
        listOf(
            stringResource(R.string.numbers_tfv_use_case_customer_support),
            stringResource(R.string.numbers_tfv_use_case_marketing),
            stringResource(R.string.numbers_tfv_use_case_notifications),
            stringResource(R.string.numbers_tfv_use_case_otp),
        ),
    ).toMap()
    SectionCard(stringResource(R.string.numbers_tfv_title)) {
        Muted(stringResource(R.string.numbers_tfv_caption))
        NumberField(
            draft.phoneNumber,
            { update(draft.copy(phoneNumber = it)) },
            R.string.numbers_lookup_number_label,
            editable
        )
        NumberField(draft.businessName, {
            update(draft.copy(businessName = it))
        }, R.string.numbers_a2p_business_label, editable)
        NumberField(draft.optInImageUrl, {
            update(draft.copy(optInImageUrl = it))
        }, R.string.numbers_tfv_opt_in_label, editable)
        Muted(stringResource(R.string.numbers_tfv_opt_in_caption))
        NumberField(
            value = draft.messageContent,
            onChange = { update(draft.copy(messageContent = it)) },
            label = R.string.numbers_tfv_message_label,
            enabled = editable,
            singleLine = false,
        )
        Muted(stringResource(R.string.numbers_tfv_use_case_label))
        Choices(values = useCases, selected = draft.useCase, enabled = editable) { update(draft.copy(useCase = it)) }
        DistrictButton(
            text = stringResource(R.string.numbers_tfv_submit_action),
            onClick = { handlers.onRequestConfirmation(NumberConfirmation.TollFree) },
            enabled = draft.isSubmittable && write.isArmed,
            modifier = Modifier.testTag(TFV_SUBMIT_TAG),
        )
    }
}

/** One choice among [values] (wire value to label), the selected one drawn primary. */
@Composable
private fun Choices(values: Map<String, String>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        values.forEach { (value, label) ->
            DistrictButton(
                text = label,
                onClick = { onSelect(value) },
                variant = if (value == selected) ButtonVariant.Primary else ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag(choiceTag(value)),
            )
        }
    }
}

/** A choice's test handle, from its wire value. */
fun choiceTag(value: String): String = "district-number-choice-$value"

/** Stable handles for tests. */
const val A2P_SUBMIT_TAG: String = "district-number-a2p-submit"
const val TFV_SUBMIT_TAG: String = "district-number-tfv-submit"
