package com.distronode.districtai.ui.marketplace

import androidx.compose.runtime.Composable
import com.distronode.districtai.core.model.ListedNumber

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
 * Number management, as the marketplace screen takes it: the state, the role's answer, and the
 * ViewModel's actions.
 */
data class ProvisioningUi(
    val state: NumberProvisioningUiState,
    val canProvision: Boolean,
    val handlers: ProvisioningHandlers,
    val onOpenNumber: (ListedNumber) -> Unit,
    val onCloseNumber: () -> Unit,
    val onConfigure: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onDismissConfirmation: () -> Unit,
)

/**
 * The open number's actions, and the open confirmation over them.
 *
 * ⚠️ THE CONFIRMATION IS DRAWN AFTER THE ACTIONS, so a release confirmed from them sits on top.
 */
@Composable
internal fun ProvisioningDialogs(ui: ProvisioningUi) {
    val state = ui.state
    state.selected?.let { number ->
        NumberActionsDialog(
            number = number,
            canProvision = ui.canProvision,
            write = state.numberWrite,
            onConfigure = { ui.onConfigure(number.phoneNumber) },
            onRelease = { ui.handlers.onRequestConfirmation(NumberConfirmation.Release(number.phoneNumber)) },
            onClose = ui.onCloseNumber,
        )
    }
    state.confirmation?.let { confirmation ->
        NumberConfirmDialog(confirmation = confirmation, onConfirm = ui.onConfirm, onDismiss = ui.onDismissConfirmation)
    }
}

/** An owned number's row handle, from its number. */
fun ownedNumberTag(phoneNumber: String): String = "district-marketplace-owned-$phoneNumber"
