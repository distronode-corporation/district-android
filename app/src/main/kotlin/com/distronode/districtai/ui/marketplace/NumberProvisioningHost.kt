package com.distronode.districtai.ui.marketplace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.core.data.NumberProvisioningRepository
import com.distronode.districtai.core.model.WorkspaceRole

/**
 * Number management for the marketplace destination: its ViewModel, read when its tab opens.
 *
 * ⚠️ IN ITS OWN FILE, NOT IN DistrictNavHost.kt, which sits at detekt's per-file function ceiling.
 *
 * ⚠️ EACH TAB IS READ AS IT OPENS, as iOS reads it, and again each time it is opened: a filing's
 * status or a carrier's balance is only as fresh as the read that showed it.
 */
@Composable
fun numberProvisioningUi(
    repository: NumberProvisioningRepository,
    workspaceId: String,
    role: WorkspaceRole?,
    tab: MarketplaceTab,
): ProvisioningUi {
    val viewModel: NumberProvisioningViewModel = viewModel(
        key = "numbers-$workspaceId",
        factory = NumberProvisioningViewModel.factory(repository, workspaceId, role),
    )
    LaunchedEffect(viewModel, tab) {
        when (tab) {
            MarketplaceTab.REGISTRATIONS -> viewModel.loadRegistrations()
            MarketplaceTab.CARRIER -> viewModel.loadCarrierTab()
            MarketplaceTab.OWNED, MarketplaceTab.SEARCH -> Unit
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    return ProvisioningUi(
        state = state,
        canProvision = viewModel.canProvision,
        handlers = ProvisioningHandlers(
            onRetryCarrier = viewModel::loadCarrier,
            onRetryVerify = viewModel::loadVerify,
            onRetrySip = viewModel::loadSip,
            onSetVerify = viewModel::setVerify,
            onSipDraft = viewModel::updateSipDraft,
            onCreateSip = viewModel::createSipTrunk,
            onLookupNumber = viewModel::updateLookupNumber,
            onRequestConfirmation = viewModel::requestConfirmation,
            onA2PDraft = viewModel::updateA2PDraft,
            onTollFreeDraft = viewModel::updateTollFreeDraft,
            onRegistrationDraft = viewModel::updateRegistrationDraft,
            onCheckRequirements = viewModel::loadRequirements,
            onStartRegistration = viewModel::createRegistration,
            onRetryRegistrations = viewModel::loadRegistrations,
            onRemoveDocument = viewModel::removeDocument,
        ),
        onOpenNumber = viewModel::openNumber,
        onCloseNumber = viewModel::closeNumber,
        onConfigure = viewModel::configure,
        onConfirm = viewModel::confirm,
        onDismissConfirmation = viewModel::dismissConfirmation,
    )
}
