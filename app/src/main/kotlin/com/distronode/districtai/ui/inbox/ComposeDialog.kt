package com.distronode.districtai.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.CHANNEL_EMAIL
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.ui.resolve

/**
 * Show the form while it is open, and hand a sent message to the caller.
 *
 * ⛔ THE FORM CLOSES ON A SENT MESSAGE EVEN WHEN THE THREAD COULD NOT BE RESOLVED. The message has
 * gone out, so keeping the form up with the text still in it would invite a second send: a second
 * charge and a duplicate for the customer.
 */
@Composable
internal fun ComposeHost(compose: InboxCompose) {
    val send = compose.state.send
    LaunchedEffect(send) {
        if (send is ComposeSendState.Sent) {
            compose.onSent(send.landing)
            compose.actions.dismiss()
        }
    }
    if (compose.state.open) ComposeDialog(compose.state, compose.actions)
}

/**
 * Start a conversation.
 *
 * ⛔ TWO STEPS ON ONE FORM, AND THE SECOND IS NOT DRAWN UNTIL THE FIRST IS SETTLED. Choosing WHO comes
 * before choosing HOW, because the channel choices are a property of the recipient; drawing both at
 * once would offer SMS and Email with no addresses behind them.
 *
 * ⚠️ AN `AlertDialog`, matching every other form in this app (see `DeskComposeSheet`), scrolled
 * because the suggestion list can be fifty rows.
 */
@Composable
fun ComposeDialog(state: ComposeUiState, actions: ComposeActions) {
    AlertDialog(
        onDismissRequest = { if (!state.sending) actions.dismiss() },
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.inbox_compose_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag(COMPOSE_ROOT_DESCRIPTION),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                // ⚠️ THE LABEL IS THE TEST, not `recipient`: it is non-null exactly when a recipient is
                // armed, and handing it down means the chosen line has no "no recipient" case to draw.
                val label = state.recipientLabel
                if (label == null) {
                    RecipientPicker(state, actions)
                } else {
                    ChosenRecipient(label, state.sending, actions)
                    ChannelSection(state, actions)
                    MessageSection(state, actions)
                }
                // ⚠️ THE SERVER'S OWN SENTENCE. An unverified sender, an exhausted A2P registration
                // and the per-workspace cap each need a different action from the operator.
                (state.send as? ComposeSendState.Failed)?.let { failed ->
                    Text(
                        text = failed.failure.message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.destructive,
                        modifier = Modifier.testTag(COMPOSE_FAILURE_DESCRIPTION),
                    )
                }
            }
        },
        confirmButton = {
            // ⛔ DISABLED FOR THE WHOLE ROUND TRIP, AND THAT GUARD IS ABOUT MONEY. See
            // ComposeUiState.submittable.
            DistrictButton(
                text = stringResource(
                    if (state.sending) R.string.inbox_compose_sending else R.string.inbox_compose_send
                ),
                onClick = actions::send,
                enabled = state.submittable,
                modifier = Modifier.testTag(COMPOSE_SEND_DESCRIPTION),
            )
        },
        dismissButton = {
            DistrictButton(
                text = stringResource(R.string.inbox_compose_cancel),
                onClick = actions::dismiss,
                variant = ButtonVariant.Ghost,
                enabled = !state.sending,
                modifier = Modifier.testTag(COMPOSE_CANCEL_DESCRIPTION),
            )
        },
    )
}

/**
 * ⚠️ ONE FIELD THAT BOTH FILTERS AND IS ITSELF A RECIPIENT. A stranger's number needs no mode switch:
 * the operator types it and taps "Message ...". The row is drawn only once something is typed, so an
 * untouched form does not invite a message to an empty string.
 */
@Composable
private fun RecipientPicker(state: ComposeUiState, actions: ComposeActions) {
    OutlinedTextField(
        value = state.query,
        onValueChange = actions::onQueryChange,
        label = { Eyebrow(stringResource(R.string.inbox_compose_to)) },
        singleLine = true,
        colors = districtFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(COMPOSE_TO_DESCRIPTION),
    )
    val typed = state.query.trim()
    if (typed.isNotEmpty()) {
        DistrictButton(
            text = stringResource(R.string.inbox_compose_message_address, typed),
            onClick = actions::useTypedAddress,
            variant = ButtonVariant.Ghost,
            modifier = Modifier.testTag(COMPOSE_USE_TYPED_DESCRIPTION),
        )
    }
    when (val contacts = state.contacts) {
        ComposeContacts.Loading -> SkeletonBlock(
            height = DistrictTheme.spacing.header,
            modifier = Modifier.testTag(COMPOSE_CONTACTS_LOADING_DESCRIPTION),
        )
        is ComposeContacts.Ready -> {
            state.matches.forEach { contact ->
                DistrictListRow(
                    title = contact.label(),
                    subtitle = contact.detail(),
                    onClick = { actions.choose(contact) },
                    modifier = Modifier.testTag(COMPOSE_CONTACT_DESCRIPTION),
                )
            }
            // ⛔ STATED, NOT SWALLOWED: the route cannot search, so this is the newest window and a
            // quieter contact is genuinely absent from it.
            Caption(stringResource(R.string.inbox_compose_recent_contacts), COMPOSE_CONTACTS_CAPTION_DESCRIPTION)
        }
        // ⛔ A CAPTION, NOT A DEAD END. Typing an address needs no CRM read.
        is ComposeContacts.Failed -> Column(modifier = Modifier.testTag(COMPOSE_CONTACTS_FAILED_DESCRIPTION)) {
            Caption(contacts.failure.message.resolve(), null)
            Caption(stringResource(R.string.inbox_compose_suggestions_failed), null)
        }
    }
}

/** The armed recipient, with a way back to choosing. */
@Composable
private fun ChosenRecipient(label: String, sending: Boolean, actions: ComposeActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Eyebrow(stringResource(R.string.inbox_compose_to))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.testTag(COMPOSE_RECIPIENT_DESCRIPTION),
            )
        }
        DistrictButton(
            text = stringResource(R.string.inbox_compose_change),
            onClick = actions::clearRecipient,
            variant = ButtonVariant.Ghost,
            enabled = !sending,
            modifier = Modifier.testTag(COMPOSE_CHANGE_DESCRIPTION),
        )
    }
}

/**
 * ⛔ BOTH CHANNELS DRAWN AT REST, because the choice decides whether the message costs a carrier
 * segment, and a collapsed menu would hide that there was a decision. ⚠️ One choice is stated rather
 * than offered, and no choice at all gets a sentence rather than a dead control.
 */
@Composable
private fun ChannelSection(state: ComposeUiState, actions: ComposeActions) {
    val choices = state.channelChoices
    // ⚠️ `target` IS NULL EXACTLY WHEN THERE IS NO CHOICE (the index is kept in range by
    // ComposeViewModel.selectChannel), so testing it first gives the address line a value to show.
    val target = state.target
    when {
        target == null -> Text(
            text = stringResource(R.string.inbox_compose_no_address),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier.testTag(COMPOSE_NO_ADDRESS_DESCRIPTION),
        )
        choices.size > 1 -> {
            Eyebrow(stringResource(R.string.inbox_compose_send_on))
            Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
                choices.forEachIndexed { index, choice ->
                    DistrictButton(
                        text = channelName(choice.channel),
                        onClick = { actions.selectChannel(index) },
                        variant = if (index == state.selectedChannel) ButtonVariant.Primary else ButtonVariant.Ghost,
                        enabled = !state.sending,
                        modifier = Modifier.testTag("$COMPOSE_CHANNEL_DESCRIPTION-${choice.channel}"),
                    )
                }
            }
            AddressLine(target.to)
        }
        else -> Row(
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DistrictBadge(
                text = channelName(target.channel),
                tone = Tone.Info,
                modifier = Modifier.testTag("$COMPOSE_CHANNEL_DESCRIPTION-${target.channel}"),
            )
            AddressLine(target.to)
        }
    }
}

/** ⚠️ Truncated in the MIDDLE: the tail of an email address is the half that says who it is. */
@Composable
private fun AddressLine(address: String) {
    Text(
        text = address,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier.testTag(COMPOSE_ADDRESS_DESCRIPTION),
    )
}

/**
 * ⛔ THE SUBJECT IS A CONTROL ON EMAIL AND ABSENT ON SMS. See ComposeUiState.requiresSubject.
 * ⚠️ Nothing is seeded: there is no thread to derive a `Re:` from, and this client invents no topic.
 */
@Composable
private fun MessageSection(state: ComposeUiState, actions: ComposeActions) {
    if (state.requiresSubject) {
        OutlinedTextField(
            value = state.subject,
            onValueChange = actions::onSubjectChange,
            label = { Eyebrow(stringResource(R.string.inbox_compose_subject)) },
            singleLine = true,
            enabled = !state.sending,
            colors = districtFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(COMPOSE_SUBJECT_DESCRIPTION),
        )
    }
    OutlinedTextField(
        value = state.body,
        onValueChange = actions::onBodyChange,
        label = { Eyebrow(stringResource(R.string.inbox_compose_message)) },
        minLines = MESSAGE_MIN_LINES,
        enabled = !state.sending,
        colors = districtFieldColors(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(COMPOSE_MESSAGE_DESCRIPTION),
    )
}

@Composable
private fun Caption(text: String, tag: String?) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = if (tag == null) Modifier else Modifier.testTag(tag),
    )
}

/** ⚠️ Only SMS and email reach here: the choices are built from this app's own two constants. */
@Composable
private fun channelName(channel: String): String =
    stringResource(
        if (channel == CHANNEL_EMAIL) R.string.inbox_compose_channel_email else R.string.inbox_compose_channel_sms
    )

/**
 * ⚠️ BOTH ADDRESSES WHERE THERE ARE BOTH, because the row is how an operator confirms they picked the
 * right Ada. One sentence with two placeholders rather than a joined string.
 */
@Composable
private fun Contact.detail(): String? {
    val phone = phoneNumber?.takeIf { it.isNotBlank() }
    val mail = email?.takeIf { it.isNotBlank() }
    return when {
        phone != null && mail != null -> stringResource(R.string.inbox_compose_contact_detail, phone, mail)
        else -> phone ?: mail
    }
}

private const val MESSAGE_MIN_LINES = 3

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val COMPOSE_ROOT_DESCRIPTION: String = "district-inbox-compose-root"
const val COMPOSE_OPEN_DESCRIPTION: String = "district-inbox-compose-open"
const val COMPOSE_TO_DESCRIPTION: String = "district-inbox-compose-to"
const val COMPOSE_USE_TYPED_DESCRIPTION: String = "district-inbox-compose-use-typed"
const val COMPOSE_CONTACT_DESCRIPTION: String = "district-inbox-compose-contact"
const val COMPOSE_CONTACTS_LOADING_DESCRIPTION: String = "district-inbox-compose-contacts-loading"
const val COMPOSE_CONTACTS_CAPTION_DESCRIPTION: String = "district-inbox-compose-contacts-caption"
const val COMPOSE_CONTACTS_FAILED_DESCRIPTION: String = "district-inbox-compose-contacts-failed"
const val COMPOSE_RECIPIENT_DESCRIPTION: String = "district-inbox-compose-recipient"
const val COMPOSE_CHANGE_DESCRIPTION: String = "district-inbox-compose-change"
const val COMPOSE_CHANNEL_DESCRIPTION: String = "district-inbox-compose-channel"
const val COMPOSE_ADDRESS_DESCRIPTION: String = "district-inbox-compose-address"
const val COMPOSE_NO_ADDRESS_DESCRIPTION: String = "district-inbox-compose-no-address"
const val COMPOSE_SUBJECT_DESCRIPTION: String = "district-inbox-compose-subject"
const val COMPOSE_MESSAGE_DESCRIPTION: String = "district-inbox-compose-message"
const val COMPOSE_SEND_DESCRIPTION: String = "district-inbox-compose-send"
const val COMPOSE_CANCEL_DESCRIPTION: String = "district-inbox-compose-cancel"
const val COMPOSE_FAILURE_DESCRIPTION: String = "district-inbox-compose-failure"
