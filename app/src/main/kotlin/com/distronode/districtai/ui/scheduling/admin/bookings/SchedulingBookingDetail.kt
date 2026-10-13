package com.distronode.districtai.ui.scheduling.admin.bookings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.bookingAnswers
import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.core.model.SchedulingBookingAnswer
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.access
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One booking's screen: the attendee's answers, and Cancel for a member who may manage. */
data class SchedulingBookingDetailUiState(
    val answers: SectionState<List<SchedulingBookingAnswer>> = SectionState.Loading,
    val canManage: Boolean = false,
)

/**
 * Ported from district-ios's `SchedulingBookingDetailModel`. ⚠️ The screen knows the booking by id
 * only, so it offers Cancel and nothing that needs the booking's own fields (its event type for a
 * reschedule, its host for a reassign); those live on the list's rows.
 */
class SchedulingBookingDetailViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val workspaceId: String,
    val bookingId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingBookingDetailUiState())
    val state: StateFlow<SchedulingBookingDetailUiState> = _state.asStateFlow()

    private val dialogs = BookingDialogHost()

    /** The open cancel dialog, if any. */
    val dialog: StateFlow<BookingDialog?> = dialogs.dialog

    val cancelFlow = BookingCancelFlow(dialogs, viewModelScope, admin, workspaceId, onChanged = ::load)

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(answers = SectionState.Loading)
        viewModelScope.launch {
            val answers = async { admin.bookingAnswers(workspaceId, bookingId) }
            val access = scheduling.access(workspaceId)
            _state.value = SchedulingBookingDetailUiState(
                answers = answers.await().toSectionState(),
                canManage = access.canManage,
            )
        }
    }

    /** The cancel dialog for this booking. Only its id is needed. */
    fun cancel() = cancelFlow.open(SchedulingBooking(id = bookingId, startAt = "", endAt = "", status = ""))

    fun closeDialog() = dialogs.close()

    companion object {
        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            workspaceId: String,
            bookingId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingBookingDetailViewModel(admin, scheduling, workspaceId, bookingId) }
        }
    }
}

/** One booking, wired to its ViewModel. */
@Composable
fun SchedulingBookingDetailRoute(deps: SchedulingAdminDeps, bookingId: String) {
    val viewModel: SchedulingBookingDetailViewModel = viewModel(
        key = "scheduling-booking-${deps.workspaceId}-$bookingId",
        factory = SchedulingBookingDetailViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId, bookingId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    SchedulingBookingDetailScreen(
        state = state,
        onBack = deps.onBack,
        onRefresh = viewModel::load,
        onCancel = viewModel::cancel,
    )
    (dialog as? BookingDialog.Cancel)?.let {
        SchedulingBookingCancelDialog(it, viewModel.cancelFlow, viewModel::closeDialog)
    }
}

/** The attendee's answers to the booking questions, and Cancel. */
@Composable
fun SchedulingBookingDetailScreen(
    state: SchedulingBookingDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
) {
    SchedulingSectionScaffold(
        title = stringResource(R.string.scheduling_admin_section_title_booking),
        rootTag = SCHEDULING_BOOKING_DETAIL_ROOT_TAG,
        onBack = onBack,
        onRefresh = onRefresh,
    ) {
        if (state.canManage) {
            SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_common_manage)) {
                DistrictButton(
                    text = stringResource(R.string.scheduling_admin_bookings_cancel_confirm),
                    onClick = onCancel,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag(SCHEDULING_BOOKING_DETAIL_CANCEL_TAG),
                )
            }
        }
        SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_bookings_answers_eyebrow)) {
            when (val answers = state.answers) {
                SectionState.Loading -> SchedulingSectionLoading(SCHEDULING_BOOKING_DETAIL_LOADING_TAG, rows = 1)
                is SectionState.Failed ->
                    SchedulingSectionFailure(answers.failure, onRefresh, SCHEDULING_BOOKING_DETAIL_FAILURE_TAG)
                is SectionState.Ready -> if (answers.value.isEmpty()) {
                    SchedulingHint(stringResource(R.string.scheduling_admin_bookings_no_answers))
                } else {
                    answers.value.forEach { answer ->
                        SchedulingReadOnlyRow(
                            label = answer.label,
                            value = answer.value.ifEmpty { stringResource(R.string.scheduling_admin_common_none) },
                        )
                    }
                }
            }
        }
    }
}

const val SCHEDULING_BOOKING_DETAIL_ROOT_TAG: String = "district-scheduling-booking-detail"
const val SCHEDULING_BOOKING_DETAIL_CANCEL_TAG: String = "district-scheduling-booking-detail-cancel"
const val SCHEDULING_BOOKING_DETAIL_LOADING_TAG: String = "district-scheduling-booking-detail-loading"
const val SCHEDULING_BOOKING_DETAIL_FAILURE_TAG: String = "district-scheduling-booking-detail-failure"
