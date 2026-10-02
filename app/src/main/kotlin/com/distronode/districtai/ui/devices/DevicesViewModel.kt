package com.distronode.districtai.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.DevicesRepository
import com.distronode.districtai.core.model.DeviceListResponse
import com.distronode.districtai.core.model.DeviceRevokeResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The device list's state machine.
 *
 * ⛔ THIS VIEWMODEL DOES NOT SIGN THE USER OUT AND MUST NOT LEARN HOW. Two of its actions end
 * THIS installation's session server-side, and the local half of that — wiping the Keystore,
 * clearing the workspace selection, advancing the session epoch, in that order — belongs to
 * `AppContainer.signOut` and to the process scope it owns. So the sign-out arrives as a callback
 * at the call site, exactly the way `ContactDetailViewModel.delete` takes its `onDeleted`. A
 * ViewModel reaching into the container would also make this untestable without one.
 *
 * ⛔ THE LOCAL SIGN-OUT IS FIRED ON A SUCCESSFUL RESPONSE REGARDLESS OF `revoked`. A zero count
 * for this device means the row was already gone — revoked from another device, or rotated out
 * from under the list — and in every one of those cases the credential this app is holding is
 * dead or about to be. Keeping the user signed in on a `revoked: 0` would leave them staring at
 * a device list that no longer contains them, which is the confusing half of both outcomes.
 *
 * ⚠️ [thisDeviceId] IS INJECTED AS A STRING rather than by handing this class a `DeviceIdentity`.
 * Reading the id can WRITE (it generates and commits a UUID on first call), and a ViewModel
 * constructed per navigation is the wrong place for that; the container reads it once and passes
 * the value.
 */
class DevicesViewModel(
    private val repository: DevicesRepository,
    /** ⛔ The only trustworthy way to tell which row is the phone in the user's hand. */
    private val thisDeviceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * Read the list.
     *
     * @param refreshing ⚠️ TRUE KEEPS THE ROWS ON SCREEN. A re-read after a revoke, or on a
     *   session change, would otherwise blank a list the user is reading in order to redraw
     *   almost the same thing — and on this screen the flicker lands exactly when someone is
     *   checking whether their lost phone is gone.
     */
    fun load(refreshing: Boolean = false) {
        if (!refreshing) _state.value = _state.value.copy(devices = DevicesListState.Loading)
        viewModelScope.launch {
            // ⛔ Read the state AFTER the request lands. One `copy` around the call reads the
            // receiver first and holds it across the suspension, so a write started meanwhile
            // would have its `busy` flag cleared by the read, re-enabling both controls mid-write.
            val devices = listState(repository.devices())
            _state.value = _state.value.copy(devices = devices)
        }
    }

    /**
     * Sign out one device.
     *
     * @param onSignedOut invoked ONLY when the device signed out is this one. ⛔ It is the local
     *   half of the sign-out and it is not optional: without it this app would keep a refresh
     *   token the server has just stopped honouring, and would discover that as a 401 on some
     *   later screen instead of as the sign-out the user asked for.
     */
    fun revokeDevice(deviceId: String, onSignedOut: () -> Unit) {
        val isThisDevice = deviceId == thisDeviceId
        startWrite {
            when (val result = repository.revokeDevice(deviceId)) {
                is ApiResult.Success -> finishRevoke(result.value, isThisDevice, onSignedOut)
                is ApiResult.Failure -> failWrite(result)
            }
        }
    }

    /**
     * Sign out every device, INCLUDING THIS ONE.
     *
     * ⛔ [onSignedOut] IS UNCONDITIONAL ON SUCCESS, because the server's "all" genuinely means
     * all: its route header states that sparing the caller would be a control nobody could
     * reason about. A client that stayed signed in here would be holding a credential the
     * server has already retired.
     */
    fun revokeAllDevices(onSignedOut: () -> Unit) {
        startWrite {
            when (val result = repository.revokeAllDevices()) {
                is ApiResult.Success -> finishRevoke(result.value, signsOutThisDevice = true, onSignedOut)
                is ApiResult.Failure -> failWrite(result)
            }
        }
    }

    /** ⚠️ The notice is transient by nature, so the screen can dismiss it without a re-read. */
    fun dismissNotices() {
        _state.value = _state.value.copy(mutationFailure = null, nothingRevoked = false)
    }

    /**
     * ⛔ THE SECOND TAP IS DROPPED, NOT QUEUED. Both writes end sessions and both are followed by
     * a re-read; a queued second one could revoke a row the refreshed list no longer shows, or
     * race the local sign-out the first one is about to trigger.
     */
    private fun startWrite(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, mutationFailure = null, nothingRevoked = false)
        viewModelScope.launch { block() }
    }

    private fun finishRevoke(
        response: DeviceRevokeResponse,
        signsOutThisDevice: Boolean,
        onSignedOut: () -> Unit,
    ) {
        // ⚠️ Cleared BEFORE the callback: `onSignedOut` tears this screen down, and leaving the
        // flag set would strand a disabled list if the teardown were ever made conditional.
        _state.value = _state.value.copy(
            busy = false,
            // ⚠️ Only meaningful for another device. For this one the screen is going away, so a
            // notice nobody can read would be noise — see the ⛔ on the class for why a zero
            // count still signs this device out.
            nothingRevoked = !signsOutThisDevice && response.revoked == 0,
        )

        if (signsOutThisDevice) {
            onSignedOut()
        } else {
            // ⚠️ Refreshing rather than reloading: the row that vanished is the only change, and
            // blanking the rest to redraw it would lose the user's place.
            load(refreshing = true)
        }
    }

    private fun failWrite(failure: ApiResult.Failure) {
        _state.value = _state.value.copy(busy = false, mutationFailure = failure.toFailureText())
    }

    /**
     * ⛔ AN EMPTY LIST IS A SUCCESS. It arrives when a chain is mid-rotation, which on a
     * single-device account is a perfectly ordinary moment on a perfectly good session. Mapping
     * it to anything else would tell someone they had been signed out while they were not.
     */
    private fun listState(result: ApiResult<DeviceListResponse>): DevicesListState = when (result) {
        is ApiResult.Success -> DevicesListState.Ready(result.value.devices)
        is ApiResult.Failure -> DevicesListState.Failed(result.toFailureText())
    }

    companion object {
        fun factory(
            repository: DevicesRepository,
            thisDeviceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DevicesViewModel(repository, thisDeviceId) }
        }
    }
}
