package com.distronode.districtai.ui.settings.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.CallHandling

/**
 * Who answers an inbound call, how long this phone is rung, and whether it is rung at all.
 *
 * ⛔ TWO SECTIONS, TWO SCOPES, TWO SAVE BEHAVIOURS, AND THE SPLIT IS THE POINT OF THE SCREEN. The
 * mode and the ring window are WORKSPACE settings behind an explicit Save; the availability switch
 * is a fact about THIS person's membership row and sends on the tap. One combined button would let
 * a colleague's routing change ride on somebody deciding they are on call.
 *
 * ⛔ A VIEWER SEES THE VALUES AND NONE OF THE CONTROLS. Both reads admit `viewer` and both writes
 * exclude one, so hiding the screen would withhold the explanation for a call list they can
 * already see, while offering a control would draw something that 403s.
 */
@Composable
fun CallHandlingScreen(
    state: CallHandlingUiState,
    onSelectMode: (String) -> Unit,
    onSelectRingSeconds: (Int) -> Unit,
    onSave: () -> Unit,
    onSetAvailability: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingExit by remember { mutableStateOf(false) }

    // ⛔ The system back gesture, not only the top-bar arrow — see PersonaFormScreen.
    BackHandler(enabled = state.hasUnsavedChanges) { confirmingExit = true }

    if (confirmingExit) {
        UnsavedChangesDialog(
            onDiscard = {
                confirmingExit = false
                onBack()
            },
            onDismiss = { confirmingExit = false },
        )
    }

    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = CALL_HANDLING_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.call_handling_title),
                onBack = { if (state.hasUnsavedChanges) confirmingExit = true else onBack() },
            )
        },
    ) { inset ->
        Column(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            when (val load = state.load) {
                CallHandlingLoad.Loading -> ContentContainer { ConfigSkeleton() }
                is CallHandlingLoad.LoadFailed -> ContentContainer {
                    ConfigLoadFailure(failure = load.failure, onRetry = onRetry)
                }
                is CallHandlingLoad.Ready -> ContentContainer {
                    HandlingSection(state, onSelectMode, onSelectRingSeconds, onSave)
                }
            }

            ContentContainer { AvailabilitySection(state, onSetAvailability, onRetry) }

            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

/** The workspace's answering behaviour and the ring window, behind one Save. */
@Composable
private fun HandlingSection(
    state: CallHandlingUiState,
    onSelectMode: (String) -> Unit,
    onSelectRingSeconds: (Int) -> Unit,
    onSave: () -> Unit,
) {
    val enabled = state.canMutate && !state.save.busy
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.call_handling_section_mode),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )

        CallHandling.MODES.forEach { mode ->
            DistrictListRow(
                title = stringResource(modeTitle(mode)),
                subtitle = stringResource(modeSubtitle(mode)),
                onClick = if (enabled) {
                    { onSelectMode(mode) }
                } else {
                    null
                },
                trailing = {
                    RadioButton(
                        selected = state.mode == mode,
                        onClick = if (enabled) {
                            { onSelectMode(mode) }
                        } else {
                            null
                        },
                        enabled = enabled,
                        modifier = Modifier.semantics {
                            contentDescription = callHandlingModeDescription(mode)
                        },
                    )
                },
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            Eyebrow(stringResource(R.string.call_handling_ring_title))
            Text(
                text = stringResource(R.string.call_handling_ring_value, state.ringSeconds),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.semantics {
                    contentDescription = CALL_HANDLING_RING_VALUE_DESCRIPTION
                },
            )
            // ⛔ THE RANGE IS THE SERVER'S AND THE STEPS LAND ON WHOLE SECONDS. `appRingSeconds` is
            // a zod `.int()`, so a fractional slider position would be a 400 rather than a rounded
            // save; the ViewModel clamps as well, because a control is not a guarantee.
            Slider(
                value = state.ringSeconds.toFloat(),
                onValueChange = { onSelectRingSeconds(it.toInt()) },
                valueRange = CallHandling.MINIMUM_RING_SECONDS.toFloat()..CallHandling.MAXIMUM_RING_SECONDS.toFloat(),
                steps = RING_SLIDER_STEPS,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = CALL_HANDLING_RING_DESCRIPTION },
            )
            Text(
                text = stringResource(R.string.call_handling_ring_hint),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
            )

            SaveNotice(state = state.save, description = CALL_HANDLING_NOTICE_DESCRIPTION)

            if (state.canMutate) {
                DistrictButton(
                    text = stringResource(
                        if (state.save.busy) {
                            R.string.workspace_settings_saving
                        } else {
                            R.string.workspace_settings_save
                        },
                    ),
                    onClick = onSave,
                    enabled = state.canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = CALL_HANDLING_SAVE_DESCRIPTION },
                )
            } else {
                Text(
                    text = stringResource(R.string.call_handling_viewer_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier.semantics {
                        contentDescription = CALL_HANDLING_VIEWER_DESCRIPTION
                    },
                )
            }
        }
    }
}

/**
 * This person's own "available for calls" switch.
 *
 * ⛔ IT IS ABOUT THE CALLER AND NOBODY ELSE. The route takes no email and no user id, so there is
 * no colleague this control could address and the copy must not suggest one.
 *
 * ⚠️ A REFUSAL ARRIVES AS A 200 WITH A REASON. `role` and `no_member_row` are different facts and
 * get different sentences; neither is an error, so neither draws the failure treatment.
 */
@Composable
private fun AvailabilitySection(
    state: CallHandlingUiState,
    onSetAvailability: (Boolean) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.call_handling_section_availability),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )

        when (val load = state.availability) {
            AvailabilityLoad.Loading -> ConfigSkeleton()
            is AvailabilityLoad.LoadFailed ->
                ConfigLoadFailure(failure = load.failure, onRetry = onRetry)
            is AvailabilityLoad.Ready -> {
                DistrictListRow(
                    title = stringResource(R.string.call_handling_availability_title),
                    subtitle = stringResource(availabilitySubtitle(state)),
                    trailing = {
                        Switch(
                            checked = state.availableForCalls,
                            onCheckedChange = onSetAvailability,
                            enabled = state.canToggleAvailability,
                            modifier = Modifier.semantics {
                                contentDescription = CALL_HANDLING_AVAILABILITY_DESCRIPTION
                            },
                        )
                    },
                )
                Column(modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter)) {
                    SaveNotice(
                        state = state.availabilitySave,
                        description = CALL_HANDLING_AVAILABILITY_NOTICE_DESCRIPTION,
                    )
                }
            }
        }
    }
}

private fun modeTitle(mode: String): Int = when (mode) {
    CallHandling.AI_THEN_APP -> R.string.call_handling_mode_ai_then_app
    CallHandling.APP_FIRST -> R.string.call_handling_mode_app_first
    else -> R.string.call_handling_mode_ai_first
}

private fun modeSubtitle(mode: String): Int = when (mode) {
    CallHandling.AI_THEN_APP -> R.string.call_handling_mode_ai_then_app_body
    CallHandling.APP_FIRST -> R.string.call_handling_mode_app_first_body
    else -> R.string.call_handling_mode_ai_first_body
}

/**
 * ⚠️ THE REASON WINS OVER THE VALUE. "Off" and "off because you are a viewer" are different
 * statements, and a switch captioned only by its position would make the second look like a choice.
 */
private fun availabilitySubtitle(state: CallHandlingUiState): Int = when {
    state.availabilityBlockedByRole -> R.string.call_handling_availability_role
    state.availabilityBlockedByMissingRow -> R.string.call_handling_availability_no_member_row
    state.availableForCalls -> R.string.call_handling_availability_on
    else -> R.string.call_handling_availability_off
}

/**
 * ⚠️ The slider's interior stops: 5..30 inclusive is 26 positions, so 24 stops sit between the
 * ends. Derived rather than written as a literal, so the two bounds stay the only numbers.
 */
private const val RING_SLIDER_STEPS: Int =
    CallHandling.MAXIMUM_RING_SECONDS - CallHandling.MINIMUM_RING_SECONDS - 1

const val CALL_HANDLING_ROOT_DESCRIPTION: String = "district-call-handling-root"
const val CALL_HANDLING_RING_DESCRIPTION: String = "district-call-handling-ring"
const val CALL_HANDLING_RING_VALUE_DESCRIPTION: String = "district-call-handling-ring-value"
const val CALL_HANDLING_SAVE_DESCRIPTION: String = "district-call-handling-save"
const val CALL_HANDLING_NOTICE_DESCRIPTION: String = "district-call-handling-notice"
const val CALL_HANDLING_VIEWER_DESCRIPTION: String = "district-call-handling-viewer"
const val CALL_HANDLING_AVAILABILITY_DESCRIPTION: String = "district-call-handling-availability"
const val CALL_HANDLING_AVAILABILITY_NOTICE_DESCRIPTION: String =
    "district-call-handling-availability-notice"

fun callHandlingModeDescription(mode: String): String = "district-call-handling-mode-$mode"
