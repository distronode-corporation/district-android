package com.distronode.districtai.ui.settings.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.ROUTING_FIELDS
import com.distronode.districtai.core.model.ROUTING_OPERATORS
import com.distronode.districtai.core.model.ROUTING_VOICES
import com.distronode.districtai.core.model.RoutingRule
import com.distronode.districtai.core.model.RoutingRuleField

/**
 * Which callers get which voice, and what extra instruction the agent is given for them.
 *
 * ⛔ THE SAVE REPLACES EVERY RULE AND THE CONFIRMATION CARRIES THE COUNT, exactly as the directory
 * editor's does, and for the same reason: `POST workspace/routing-rules` stores what it receives.
 * The empty case has its own wording rather than a count of zero.
 *
 * ⛔ THE MODEL OVERRIDE IS DISPLAYED AND NOT EDITABLE, WHICH IS A DELIBERATE GAP AGAINST THE WEB
 * BUILDER. Its picker is `AVAILABLE_MODELS = [...PIPELINE_IDS]`, a catalogue that is derived
 * server-side and varies BY REGION — this client has no access to it, and a free-text box would let
 * an operator store an id that the agent then silently coerces to the default engine. The persona
 * form makes the same call about the same vocabulary, for the same reason. The stored value is
 * shown so a rule's real behaviour is legible, and it is carried untouched through every save.
 *
 * ⚠️ THE VOICE PICKER IS THE WEB'S OWN FIVE-VALUE LIST, which is hardcoded there rather than
 * derived — so mirroring it cannot drift the way a derived list would. A stored voice outside the
 * list is offered as its own first option rather than being replaced.
 */
@Composable
fun RoutingRulesScreen(
    state: RoutingRulesUiState,
    onAdd: () -> Unit,
    onEdit: (Int, RoutingRuleField, String) -> Unit,
    onRemove: (Int) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val guardedBack = rememberUnsavedChangesGuard(state.hasUnsavedChanges, onBack)
    var confirmingSave by remember { mutableStateOf(false) }

    if (confirmingSave) {
        ReplaceRulesDialog(
            count = state.rules.size,
            emptying = state.savingEmptiesRules,
            onConfirm = {
                confirmingSave = false
                onSave()
            },
            onDismiss = { confirmingSave = false },
        )
    }

    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = ROUTING_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.routing_title),
                onBack = guardedBack,
            )
        },
    ) { inset ->
        Column(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            when (val load = state.load) {
                ConfigState.Loading -> ContentContainer { ConfigSkeleton() }
                is ConfigState.LoadFailed -> ContentContainer {
                    ConfigLoadFailure(failure = load.failure, onRetry = onRetry)
                }
                is ConfigState.Ready ->
                    if (state.unmodellable) {
                        ContentContainer {
                            NotEditableNotice(
                                title = stringResource(R.string.routing_unmodellable_title),
                                body = stringResource(R.string.routing_unmodellable_body),
                                description = ROUTING_UNMODELLABLE_DESCRIPTION,
                            )
                        }
                    } else {
                        ContentContainer { RulesList(state, onEdit, onRemove) }
                        ContentContainer { RulesActions(state, onAdd) { confirmingSave = true } }
                    }
            }
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

/** Every stored rule, in evaluation order. */
@Composable
private fun RulesList(
    state: RoutingRulesUiState,
    onEdit: (Int, RoutingRuleField, String) -> Unit,
    onRemove: (Int) -> Unit,
) {
    if (state.rules.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.routing_empty_title),
            body = stringResource(R.string.routing_empty_body),
            modifier = Modifier.semantics { contentDescription = ROUTING_EMPTY_DESCRIPTION },
        )
        return
    }
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        state.rules.forEachIndexed { index, rule ->
            if (index > 0) DistrictRowDivider()
            RuleCard(
                index = index,
                rule = rule,
                enabled = !state.save.busy,
                onEdit = onEdit,
                onRemove = onRemove,
            )
        }
    }
}

/**
 * One rule.
 *
 * ⚠️ THE RULE'S RAW MATCH KEYS ARE NOT SHOWN AND NOT LOST. A rule stored in another shape
 * (`{match, action, target}` exists in real data) renders with empty builder fields, which is
 * honest — this editor genuinely does not know how to display it — and every one of its keys is
 * still carried through the save. Editing such a rule ADDS the builder's keys rather than
 * replacing the ones already there.
 */
@Composable
private fun RuleCard(
    index: Int,
    rule: RoutingRule,
    enabled: Boolean,
    onEdit: (Int, RoutingRuleField, String) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(stringResource(R.string.routing_rule_number, index + 1))
        OptionPicker(
            label = stringResource(R.string.routing_field),
            value = rule.value(RoutingRuleField.FIELD),
            options = ROUTING_FIELDS,
            description = routingFieldDescription(index, RoutingRuleField.FIELD),
            enabled = enabled,
        ) { onEdit(index, RoutingRuleField.FIELD, it) }
        OptionPicker(
            label = stringResource(R.string.routing_operator),
            value = rule.value(RoutingRuleField.OPERATOR),
            options = ROUTING_OPERATORS,
            description = routingFieldDescription(index, RoutingRuleField.OPERATOR),
            enabled = enabled,
        ) { onEdit(index, RoutingRuleField.OPERATOR, it) }
        SettingsTextField(
            value = rule.value(RoutingRuleField.VALUE),
            label = stringResource(R.string.routing_value),
            description = routingFieldDescription(index, RoutingRuleField.VALUE),
            enabled = enabled,
            singleLine = true,
        ) { onEdit(index, RoutingRuleField.VALUE, it) }
        OptionPicker(
            label = stringResource(R.string.routing_voice),
            value = rule.value(RoutingRuleField.VOICE),
            options = ROUTING_VOICES,
            description = routingFieldDescription(index, RoutingRuleField.VOICE),
            enabled = enabled,
        ) { onEdit(index, RoutingRuleField.VOICE, it) }
        SettingsTextField(
            value = rule.value(RoutingRuleField.INSTRUCTION),
            label = stringResource(R.string.routing_instruction),
            description = routingFieldDescription(index, RoutingRuleField.INSTRUCTION),
            enabled = enabled,
            singleLine = false,
        ) { onEdit(index, RoutingRuleField.INSTRUCTION, it) }
        // ⛔ READ-ONLY. See the ⛔ on the screen: the model catalogue is server-derived and
        // region-dependent, and a free-text override would be silently coerced by the agent.
        Text(
            text = stringResource(
                R.string.routing_model_read_only,
                rule.value(RoutingRuleField.MODEL).ifBlank {
                    stringResource(R.string.routing_model_none)
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.semantics {
                contentDescription = routingFieldDescription(index, RoutingRuleField.MODEL)
            },
        )
        DistrictButton(
            text = stringResource(R.string.routing_remove),
            onClick = { onRemove(index) },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            enabled = enabled,
            modifier = Modifier.semantics {
                contentDescription = routingRemoveDescription(index)
            },
        )
    }
}

/** Add, the banner, and the count-confirmed save. */
@Composable
private fun RulesActions(
    state: RoutingRulesUiState,
    onAdd: () -> Unit,
    onRequestSave: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        DistrictButton(
            text = stringResource(R.string.routing_add),
            onClick = onAdd,
            variant = ButtonVariant.Ghost,
            enabled = !state.save.busy,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = ROUTING_ADD_DESCRIPTION },
        )
        SaveNotice(state = state.save, description = ROUTING_NOTICE_DESCRIPTION)
        DistrictButton(
            text = stringResource(
                if (state.save.busy) R.string.workspace_settings_saving else R.string.routing_save,
            ),
            onClick = onRequestSave,
            enabled = state.canSave,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = ROUTING_SAVE_DESCRIPTION },
        )
    }
}

/** ⛔ Count in the wording, and a separate string for the wipe. See [ReplaceDirectoryDialog]. */
@Composable
private fun ReplaceRulesDialog(
    count: Int,
    emptying: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = {
            Text(
                stringResource(
                    if (emptying) R.string.routing_confirm_empty_title else R.string.routing_confirm_title,
                ),
            )
        },
        text = {
            Text(
                if (emptying) {
                    stringResource(R.string.routing_confirm_empty_body)
                } else {
                    pluralStringResource(R.plurals.routing_confirm_body, count, count)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = ROUTING_CONFIRM_DESCRIPTION },
            ) {
                Text(
                    stringResource(
                        if (emptying) {
                            R.string.routing_confirm_empty_action
                        } else {
                            R.string.routing_confirm_action
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = ROUTING_CANCEL_DESCRIPTION },
            ) {
                Text(stringResource(R.string.workspace_settings_keep_editing))
            }
        },
    )
}

/**
 * A closed vocabulary, chosen from a menu.
 *
 * ⛔ A STORED VALUE OUTSIDE [options] IS OFFERED AS ITS OWN FIRST ENTRY rather than dropped. The
 * server's real allow-list is per workspace and invisible here, so a value this list does not carry
 * is not necessarily wrong — and a picker that could not re-select the current value would make
 * "open the menu and close it" a silent edit.
 */
@Composable
private fun OptionPicker(
    label: String,
    value: String,
    options: List<String>,
    description: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (value.isNotBlank() && value !in options) listOf(value) + options else options
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        Eyebrow(label)
        DistrictButton(
            text = value.ifBlank { stringResource(R.string.routing_option_unset) },
            onClick = { expanded = true },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = description },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            shown.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = routingOptionDescription(description, option)
                    },
                )
            }
        }
    }
}

/** Per-row handles, derived in one place so a row and its test cannot drift. */
internal fun routingFieldDescription(index: Int, field: RoutingRuleField): String =
    "district-routing-$index-${field.key}"

internal fun routingRemoveDescription(index: Int): String = "district-routing-remove-$index"

internal fun routingOptionDescription(pickerDescription: String, option: String): String =
    "$pickerDescription-option-$option"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val ROUTING_ROOT_DESCRIPTION: String = "district-routing-root"
const val ROUTING_ADD_DESCRIPTION: String = "district-routing-add"
const val ROUTING_SAVE_DESCRIPTION: String = "district-routing-save"
const val ROUTING_NOTICE_DESCRIPTION: String = "district-routing-notice"
const val ROUTING_EMPTY_DESCRIPTION: String = "district-routing-empty"
const val ROUTING_UNMODELLABLE_DESCRIPTION: String = "district-routing-unmodellable"
const val ROUTING_CONFIRM_DESCRIPTION: String = "district-routing-confirm"
const val ROUTING_CANCEL_DESCRIPTION: String = "district-routing-cancel"

/**
 * ⛔ EVERY MUTATING HANDLE ON THIS SCREEN THAT IS NOT PER-RULE. Asserted ABSENT in the load-failed
 * and unmodellable states — the two in which a save would replace the stored array from nothing.
 */
val ROUTING_MUTATING_DESCRIPTIONS: List<String> = listOf(
    ROUTING_ADD_DESCRIPTION,
    ROUTING_SAVE_DESCRIPTION,
)
