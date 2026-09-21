package com.distronode.districtai.ui.devices

import com.distronode.districtai.core.model.NativeDevice
import com.distronode.districtai.ui.FailureText

/**
 * The device list's state.
 *
 * ⛔ THE READ AND THE WRITES CARRY SEPARATE FAILURES, for the reason the Inbox and the contact
 * dossier do: a revoke that failed must not blank a list the user is reading. The list they can
 * see is a correct answer already in hand, and replacing it with an error would lose the very
 * rows they were about to act on.
 *
 * ⛔ AND `nothingRevoked` IS NOT A FAILURE EITHER. `revoked: 0` comes back for a device id the
 * account does not own (the server refuses to be a membership oracle), for a row another device
 * already revoked, and for a chain that rotated between the list read and the tap. None of those
 * is an error and all of them mean the same thing to the user: what you tapped is not what is
 * there now, here is the current list. Rendering it in red would report a fault that did not
 * happen; rendering it as success would claim a revocation that did not happen either.
 */
data class DevicesUiState(
    val devices: DevicesListState = DevicesListState.Loading,
    /**
     * ⚠️ ONE FLAG FOR EVERY WRITE ON THE SCREEN, not one per row. Both writes end sessions and
     * both are followed by a re-read, so allowing a second while the first is in flight would
     * race two revokes against one refresh — and one of the two can sign this device out
     * mid-flight. Same call [com.distronode.districtai.ui.contacts.ContactDetailViewModel] makes.
     */
    val busy: Boolean = false,
    /** ⚠️ Shown ALONGSIDE the list, never instead of it. */
    val mutationFailure: FailureText? = null,
    /** See the ⛔ on the class: a neutral notice, not an error. */
    val nothingRevoked: Boolean = false,
)

sealed interface DevicesListState {

    data object Loading : DevicesListState

    /**
     * @param devices ⚠️ MAY BE EMPTY ON A PERFECTLY GOOD SESSION. The server filters on
     *   `rotatedAt: null`, so a chain caught mid-refresh is briefly invisible — on a
     *   single-device account that is an empty list while the user is very much signed in. It
     *   renders as an explanatory empty state and must never read as "you have been signed out".
     */
    data class Ready(val devices: List<NativeDevice>) : DevicesListState

    data class Failed(val failure: FailureText) : DevicesListState
}
