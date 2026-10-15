package com.distronode.districtai.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.NumberRegistration
import com.distronode.districtai.core.model.RegistrationDocument
import com.distronode.districtai.ui.InlineFailure
import androidx.compose.foundation.layout.FlowRow

/**
 * The Registrations tab (iOS `RegistrationsSection`): what a country's regulator asks for, starting
 * a filing, and the workspace's filings with their status, the carrier's objections and their
 * documents.
 *
 * ⛔ ONLY A DRAFT MAY BE FILED OR HAVE A DOCUMENT REMOVED: every other status is with the carrier.
 *
 * ⚠️ THERE IS NO UPLOAD CONTROL, as on iOS: choosing a file is a picker screen of its own. The API
 * can upload (`NumberProvisioningApi.uploadRegistrationDocument`).
 */
@Composable
fun RegistrationsTab(state: NumberProvisioningUiState, canProvision: Boolean, handlers: ProvisioningHandlers) {
    ContentContainer {
        Column(
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter).testTag(REGISTRATIONS_TAB_TAG),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            if (!canProvision) {
                EmptyState(
                    title = stringResource(R.string.numbers_registrations_eyebrow),
                    body = stringResource(R.string.numbers_viewer_cannot_provision),
                )
                return@Column
            }
            NumberWriteNotice(state.registrationWrite)
            StartCard(state.registrationDraft, state.registrationWrite, handlers)
            RequirementsCard(state.requirements, handlers.onCheckRequirements)
            when (val registrations = state.registrations) {
                SectionState.Loading -> SkeletonBlock(height = DistrictTheme.spacing.header)
                is SectionState.Failed -> InlineFailure(
                    title = stringResource(R.string.numbers_registrations_failed),
                    failure = registrations.failure,
                    onRetry = handlers.onRetryRegistrations,
                    description = REGISTRATIONS_FAILURE_TAG,
                )
                is SectionState.Ready -> RegistrationsList(registrations.value, state.registrationWrite, handlers)
            }
        }
    }
}

@Composable
private fun StartCard(draft: RegistrationDraft, write: NumberWriteState, handlers: ProvisioningHandlers) {
    val editable = write != NumberWriteState.Running
    SectionCard(stringResource(R.string.numbers_registration_start_title)) {
        NumberField(
            value = draft.country,
            onChange = { handlers.onRegistrationDraft(draft.copy(country = it)) },
            label = R.string.numbers_registration_country_label,
            enabled = editable,
        )
        NumberField(
            value = draft.friendlyName,
            onChange = { handlers.onRegistrationDraft(draft.copy(friendlyName = it)) },
            label = R.string.numbers_registration_name_label,
            enabled = editable,
        )
        // ⚠️ A FLOW ROW, so « Commencer l'inscription » keeps one line and moves under « Vérifier les
        // exigences » when the two do not fit side by side (visual QA AA5).
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            DistrictButton(
                text = stringResource(R.string.numbers_requirements_check_action),
                onClick = handlers.onCheckRequirements,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = draft.trimmedCountry.isNotEmpty(),
                modifier = Modifier.testTag(REQUIREMENTS_CHECK_TAG),
            )
            DistrictButton(
                text = stringResource(R.string.numbers_registration_start_action),
                onClick = handlers.onStartRegistration,
                size = ButtonSize.Sm,
                enabled = draft.trimmedCountry.isNotEmpty() && write.isArmed,
                modifier = Modifier.testTag(REGISTRATION_START_TAG),
            )
        }
    }
}

@Composable
private fun RequirementsCard(state: RequirementsState, onRetry: () -> Unit) {
    when (state) {
        RequirementsState.Idle -> Unit
        RequirementsState.Loading -> SkeletonBlock(height = DistrictTheme.spacing.header)
        is RequirementsState.Failed -> InlineFailure(
            title = stringResource(R.string.numbers_requirements_eyebrow),
            failure = state.failure,
            onRetry = onRetry,
            description = REQUIREMENTS_FAILURE_TAG,
        )
        is RequirementsState.NoRegulation -> SectionCard(stringResource(R.string.numbers_requirements_eyebrow)) {
            Text(
                text = stringResource(R.string.numbers_requirements_none_heading, state.country),
                style = MaterialTheme.typography.titleSmall,
            )
            Muted(stringResource(R.string.numbers_requirements_none_body))
            if (!state.purchasable) Muted(stringResource(R.string.numbers_requirements_not_purchasable))
        }
        is RequirementsState.Ready -> SectionCard(stringResource(R.string.numbers_requirements_eyebrow)) {
            Text(text = state.regulation.friendlyName, style = MaterialTheme.typography.titleSmall)
            if (!state.purchasable) Muted(stringResource(R.string.numbers_requirements_not_purchasable))
            state.regulation.requirements.forEach { requirement ->
                Column(modifier = Modifier.padding(top = DistrictTheme.spacing.tight)) {
                    Text(text = requirement.name, style = MaterialTheme.typography.bodyMedium)
                    val detail = requirement.kind == END_USER
                    DistrictBadge(
                        text = stringResource(
                            if (detail) {
                                R.string.numbers_requirement_kind_field
                            } else {
                                R.string.numbers_requirement_kind_document
                            },
                        ),
                        tone = if (detail) Tone.Info else Tone.District,
                    )
                    Muted(
                        requirement.fields.joinToString(
                            ", "
                        ).ifEmpty { stringResource(R.string.numbers_requirement_no_fields) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RegistrationsList(value: Registrations, write: NumberWriteState, handlers: ProvisioningHandlers) {
    if (value.platformCountries.isNotEmpty() || value.approvedCountries.isNotEmpty()) {
        SectionCard(stringResource(R.string.numbers_registrations_eyebrow)) {
            if (value.platformCountries.isNotEmpty()) {
                Gate(stringResource(R.string.numbers_ready_buy_today), value.platformCountries)
            }
            if (value.approvedCountries.isNotEmpty()) {
                Gate(stringResource(R.string.numbers_your_approvals), value.approvedCountries)
            }
        }
    }
    if (value.registrations.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.numbers_registrations_empty_title),
            body = stringResource(R.string.numbers_registrations_empty_body),
        )
        return
    }
    Muted(stringResource(R.string.numbers_registration_status_caption))
    value.registrations.forEach { RegistrationCard(it, write, handlers) }
}

@Composable
private fun Gate(label: String, countries: List<String>) {
    Column {
        Muted(label)
        Text(text = countries.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RegistrationCard(
    registration: NumberRegistration,
    write: NumberWriteState,
    handlers: ProvisioningHandlers
) {
    val isDraft = registration.status == REGISTRATION_DRAFT
    SectionCard(stringResource(R.string.numbers_registration_title, registration.isoCountry, registration.numberType)) {
        registration.friendlyName?.trim()?.takeIf { it.isNotEmpty() }?.let { Muted(it) }
        val (label, tone) = registrationStatus(registration.status)
        DistrictBadge(
            text = label,
            tone = tone,
            modifier = Modifier.testTag(registrationStatusTag(registration.id)),
        )
        if (registration.rejectionReasons.isNotEmpty()) {
            Eyebrow(stringResource(R.string.numbers_registration_reasons_eyebrow))
            registration.rejectionReasons.forEach {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = DistrictTheme.colors.destructive)
            }
        }
        if (registration.documents.isNotEmpty()) {
            Eyebrow(stringResource(R.string.numbers_registration_documents_eyebrow))
            registration.documents.forEach { document ->
                DocumentRow(document, removable = isDraft && write.isArmed) {
                    handlers.onRemoveDocument(registration.id, document.id)
                }
            }
        }
        if (isDraft) {
            DistrictButton(
                text = stringResource(R.string.numbers_submit_confirm_action),
                onClick = {
                    handlers.onRequestConfirmation(
                        NumberConfirmation.SubmitRegistration(registration.id, registration.isoCountry),
                    )
                },
                enabled = write.isArmed,
                modifier = Modifier.testTag(registrationSubmitTag(registration.id)),
            )
        }
    }
}

@Composable
private fun DocumentRow(document: RegistrationDocument, removable: Boolean, onRemove: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = document.requirementName, style = MaterialTheme.typography.bodySmall)
            Muted(
                stringResource(
                    when {
                        document.submitted -> R.string.numbers_registration_document_filed
                        document.stored -> R.string.numbers_registration_document_stored
                        else -> R.string.numbers_registration_document_recorded
                    },
                ),
            )
        }
        if (removable) {
            DistrictButton(
                text = stringResource(R.string.numbers_document_remove_action),
                onClick = onRemove,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag(documentRemoveTag(document.id)),
            )
        }
    }
}

/**
 * A filing status's label and tone. ⚠️ A status this build does not know shows as it was sent, in
 * the neutral tone, rather than as a guess.
 */
@Composable
private fun registrationStatus(status: String): Pair<String, Tone> = when (status) {
    REGISTRATION_DRAFT -> stringResource(R.string.numbers_registration_status_draft) to Tone.Neutral
    "pending-review" -> stringResource(R.string.numbers_registration_status_pending_review) to Tone.Info
    "in-review" -> stringResource(R.string.numbers_registration_status_in_review) to Tone.Info
    "twilio-approved" -> stringResource(R.string.numbers_registration_status_approved) to Tone.Success
    "twilio-rejected" -> stringResource(R.string.numbers_registration_status_refused) to Tone.Danger
    else -> status to Tone.Neutral
}

private const val END_USER = "end_user"

/** Stable handles for tests. */
const val REGISTRATIONS_TAB_TAG: String = "district-number-registrations-tab"
const val REGISTRATIONS_FAILURE_TAG: String = "district-number-registrations-failure"
const val REQUIREMENTS_FAILURE_TAG: String = "district-number-requirements-failure"
const val REQUIREMENTS_CHECK_TAG: String = "district-number-requirements-check"
const val REGISTRATION_START_TAG: String = "district-number-registration-start"
