package com.distronode.districtai.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.R
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MeetingsRepository
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.resolve

/**
 * What the contact screen needs to draw the video-call row.
 *
 * ⚠️ ITS OWN FILE AND ITS OWN HOLDER so [ContactDetailScreen] gains one parameter and one call
 * site, not a ViewModel's worth of callbacks.
 */
class ContactVideoCallUi(
    val phase: ContactVideoCallPhase,
    val onStart: (Contact) -> Unit,
    /** Open the room the invitation was sent for, by its full `meet_` name. */
    val onJoin: (String) -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * The row's ViewModel and state, for one contact-detail destination.
 *
 * ⚠️ RETURNS NULL FOR A ROLE THAT MAY NOT START ONE (see [ContactVideoCallViewModel.canStart]), so
 * a viewer is not offered a control both routes would refuse.
 */
@Composable
fun contactVideoCallUi(
    meetings: MeetingsRepository,
    inbox: InboxRepository,
    workspaceId: String,
    contactId: String,
    role: WorkspaceRole?,
    webOrigin: String,
    onJoin: (String) -> Unit,
): ContactVideoCallUi? {
    val resources = LocalContext.current.resources
    val viewModel: ContactVideoCallViewModel = viewModel(
        key = "contact-video-$workspaceId-$contactId",
        factory = ContactVideoCallViewModel.factory(
            meetings = meetings,
            inbox = inbox,
            workspaceId = workspaceId,
            role = role,
            copy = ContactVideoInvitationCopy(
                webOrigin = webOrigin,
                emailSubject = resources.getString(R.string.contact_video_email_subject),
                invitation = { link -> resources.getString(R.string.contact_video_invitation, link) },
            ),
        ),
    )
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    if (!viewModel.canStart) return null
    return ContactVideoCallUi(
        phase = phase,
        onStart = viewModel::start,
        onJoin = onJoin,
        onDismiss = viewModel::reset,
    )
}

/**
 * Start a video room and send the contact its guest link. Mirrors iOS `ContactDetailView`'s
 * video-call section.
 *
 * ⛔ ONE TAP IS ONE METERED, UNRECALLABLE MESSAGE, which is why the button turns into a disabled
 * "Setting up" the moment an attempt starts and a failure offers only Dismiss.
 *
 * ⚠️ THE ROOM OPENS ON A SECOND, DELIBERATE TAP once the invitation has gone, never by itself: the
 * operator may want to stay on the contact, and the room screen asks for the microphone.
 *
 * @param busy the contact screen's own write in flight; the action waits for it, as on iOS.
 */
@Composable
internal fun ContactVideoCallRow(ui: ContactVideoCallUi, contact: Contact, busy: Boolean) {
    when (val phase = ui.phase) {
        ContactVideoCallPhase.Idle -> DistrictButton(
            text = stringResource(R.string.contact_video_action),
            onClick = { ui.onStart(contact) },
            variant = ButtonVariant.Secondary,
            enabled = !busy && ContactVideoCallViewModel.target(contact) != null,
            modifier = Modifier.testTag(CONTACT_VIDEO_START_TAG),
        )

        ContactVideoCallPhase.Starting -> DistrictButton(
            text = stringResource(R.string.contact_video_starting),
            onClick = {},
            variant = ButtonVariant.Secondary,
            enabled = false,
            modifier = Modifier.testTag(CONTACT_VIDEO_STARTING_TAG),
        )

        is ContactVideoCallPhase.Sent -> DistrictButton(
            text = stringResource(R.string.contact_video_action),
            onClick = { ui.onJoin(phase.roomName) },
            modifier = Modifier.testTag(CONTACT_VIDEO_JOIN_TAG),
        )

        is ContactVideoCallPhase.Failed -> Column(modifier = Modifier.fillMaxWidth()) {
            // ⚠️ BESIDE THE CONTACT, NOT INSTEAD OF IT, and Dismiss is the only control: for a
            // failed send, trying again is a second charge.
            Text(
                text = phase.failure.message.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier.testTag(CONTACT_VIDEO_FAILURE_TAG),
            )
            DistrictButton(
                text = stringResource(R.string.contact_video_dismiss),
                onClick = ui.onDismiss,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.testTag(CONTACT_VIDEO_DISMISS_TAG),
            )
        }
    }
}

/** Stable handles for tests. */
const val CONTACT_VIDEO_START_TAG: String = "district-contact-video-start"
const val CONTACT_VIDEO_STARTING_TAG: String = "district-contact-video-starting"
const val CONTACT_VIDEO_JOIN_TAG: String = "district-contact-video-join"
const val CONTACT_VIDEO_FAILURE_TAG: String = "district-contact-video-failure"
const val CONTACT_VIDEO_DISMISS_TAG: String = "district-contact-video-dismiss"
