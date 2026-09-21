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
import androidx.compose.material3.Switch
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

/**
 * What the agent may DO on a call, and the external-enrichment opt-in.
 *
 * ⛔ THE SCREEN THIS WHOLE PACKAGE EXISTS FOR. `PATCH workspace/tools` sets
 * `toolConfig.allowedTools` to EXACTLY the array it receives — no merge — so a toggle list built
 * from anything other than a configuration that actually loaded is a deletion. A failed read
 * therefore renders [ConfigLoadFailure] and nothing else, and the screen test asserts that not one
 * switch, and no save button, exists in that state.
 *
 * ⛔ A ROW WITH NO NAME IS STILL A ROW. A workspace can store a capability id this client's
 * catalog does not know — `transfer_to_creator` was retired in 2026 and real rows still carry it
 * — and because the save is wholesale, hiding such a row would delete it on the next save. It is
 * drawn with its raw id and a note saying so.
 *
 * ⛔ TWO SAVE BUTTONS, TWO ROUTES, MIRRORING THE WEB. The allowlist goes to `workspace/tools`; the
 * enrichment flag is a persona field and goes to the merge-safe `workspace/persona`, exactly as
 * `EnrichmentSettingsForm` does from inside the web's capabilities tab. One button writing through
 * both would put the wholesale-replace route behind a tap nobody associated with it.
 */
@Composable
fun CapabilitiesScreen(
    state: CapabilitiesUiState,
    onToggleTool: (String, Boolean) -> Unit,
    onSaveTools: () -> Unit,
    onToggleEnrichment: (Boolean) -> Unit,
    onSaveEnrichment: () -> Unit,
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
        modifier = modifier.semantics { contentDescription = CAPABILITIES_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.capabilities_title),
                onBack = { if (state.hasUnsavedChanges) confirmingExit = true else onBack() },
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
                is ConfigState.Ready -> {
                    ContentContainer { ToolSection(state, onToggleTool, onSaveTools) }
                    ContentContainer {
                        EnrichmentSection(state, onToggleEnrichment, onSaveEnrichment)
                    }
                }
            }
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

/**
 * The capability allowlist.
 *
 * ⚠️ THE PREREQUISITE HINT IS ONLY SHOWN FOR THE ONE PREREQUISITE THIS SCREEN CAN OBSERVE. The
 * web form states the same rule and for the same reason: it answers "unmet" only for things in
 * its own state, because a warning that is sometimes wrong trains people to ignore all of them.
 * Here that is `transfer_to_agent` versus the stored support number — the calendar connection
 * lives behind a route this client does not call, so nothing is claimed about scheduling tools.
 */
@Composable
private fun ToolSection(
    state: CapabilitiesUiState,
    onToggleTool: (String, Boolean) -> Unit,
    onSaveTools: () -> Unit,
) {
    val busy = state.toolsSave.busy || state.enrichmentSave.busy
    val supportNumber = (state.load as? ConfigState.Ready)?.config?.toolConfig?.supportPhoneNumber
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.capabilities_section_tools),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )
        state.rows.forEach { row ->
            CapabilityToggleRow(
                row = row,
                enabled = !busy,
                // ⚠️ The hint is a SUBTITLE rather than a disabled state: the server accepts the
                // toggle either way, and refusing it here would be this client inventing a rule.
                subtitle = prerequisiteHint(row.id, supportNumber),
                onToggle = { onToggleTool(row.id, it) },
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            SaveNotice(state = state.toolsSave, description = CAPABILITIES_TOOLS_NOTICE_DESCRIPTION)
            DistrictButton(
                text = stringResource(
                    if (state.toolsSave.busy) {
                        R.string.workspace_settings_saving
                    } else {
                        R.string.capabilities_save_tools
                    },
                ),
                onClick = onSaveTools,
                enabled = state.canSaveTools,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = CAPABILITIES_SAVE_TOOLS_DESCRIPTION },
            )
        }
    }
}

/** One capability, with its switch. */
@Composable
private fun CapabilityToggleRow(
    row: CapabilityRow,
    enabled: Boolean,
    subtitle: String?,
    onToggle: (Boolean) -> Unit,
) {
    DistrictListRow(
        // ⛔ AN UNRECOGNISED ID IS SHOWN BY ITS RAW NAME rather than hidden. Hiding it would delete
        // it on the next save, because the allowlist is replaced wholesale.
        title = row.labelRes?.let { stringResource(it) } ?: row.id,
        // ⚠️ The prerequisite hint wins when there is one; otherwise an unrecognised row explains
        // itself, so a raw id on screen reads as "stored but unknown to this app" rather than as a
        // rendering fault.
        subtitle = subtitle
            ?: if (row.labelRes == null) stringResource(R.string.capabilities_unknown_tool) else null,
        trailing = {
            Switch(
                checked = row.enabled,
                onCheckedChange = onToggle,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = capabilityToggleDescription(row.id)
                },
            )
        },
    )
}

/**
 * The external-lead-enrichment opt-in.
 *
 * ⛔ ITS OWN SAVE, THROUGH THE PERSONA ROUTE. The published sub-processor list and the privacy
 * policy both promise this is OFF unless a workspace turns it on, so it is a consent control
 * rather than a preference — which is also why an untouched form never sends `false` for it.
 */
@Composable
private fun EnrichmentSection(
    state: CapabilitiesUiState,
    onToggleEnrichment: (Boolean) -> Unit,
    onSaveEnrichment: () -> Unit,
) {
    val busy = state.toolsSave.busy || state.enrichmentSave.busy
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.capabilities_section_enrichment),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )
        DistrictListRow(
            title = stringResource(R.string.capabilities_enrichment_title),
            subtitle = stringResource(R.string.capabilities_enrichment_body),
            trailing = {
                Switch(
                    checked = state.enrichmentEnabled,
                    onCheckedChange = onToggleEnrichment,
                    enabled = !busy,
                    modifier = Modifier.semantics {
                        contentDescription = CAPABILITIES_ENRICHMENT_TOGGLE_DESCRIPTION
                    },
                )
            },
        )
        Column(
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            SaveNotice(
                state = state.enrichmentSave,
                description = CAPABILITIES_ENRICHMENT_NOTICE_DESCRIPTION,
            )
            DistrictButton(
                text = stringResource(
                    if (state.enrichmentSave.busy) {
                        R.string.workspace_settings_saving
                    } else {
                        R.string.capabilities_save_enrichment
                    },
                ),
                onClick = onSaveEnrichment,
                enabled = state.canSaveEnrichment,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = CAPABILITIES_SAVE_ENRICHMENT_DESCRIPTION },
            )
        }
    }
}

/**
 * The one prerequisite this screen can honestly report on.
 *
 * ⚠️ `transfer_to_agent` needs somewhere to transfer to, and both the support number and the
 * creator's cell satisfy it server-side — but only the first is in `toolConfig`, which is what
 * this screen holds. Everything else the web hints at (a connected calendar, a populated transfer
 * directory, uploaded knowledge documents) lives behind routes this client does not call, so
 * nothing is claimed about them.
 */
@Composable
private fun prerequisiteHint(toolId: String, supportPhoneNumber: String?): String? =
    if (toolId == "transfer_to_agent" && supportPhoneNumber.isNullOrBlank()) {
        stringResource(R.string.capabilities_needs_support_number)
    } else {
        null
    }

/** A stable per-capability handle, derived in one place so a row and its test cannot drift. */
internal fun capabilityToggleDescription(id: String): String = "district-capability-$id"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val CAPABILITIES_ROOT_DESCRIPTION: String = "district-capabilities-root"
const val CAPABILITIES_SAVE_TOOLS_DESCRIPTION: String = "district-capabilities-save-tools"
const val CAPABILITIES_TOOLS_NOTICE_DESCRIPTION: String = "district-capabilities-tools-notice"
const val CAPABILITIES_ENRICHMENT_TOGGLE_DESCRIPTION: String = "district-capabilities-enrichment"
const val CAPABILITIES_SAVE_ENRICHMENT_DESCRIPTION: String =
    "district-capabilities-save-enrichment"
const val CAPABILITIES_ENRICHMENT_NOTICE_DESCRIPTION: String =
    "district-capabilities-enrichment-notice"

/**
 * ⛔ EVERY MUTATING HANDLE ON THIS SCREEN THAT IS NOT A PER-CAPABILITY SWITCH. `CapabilitiesScreenTest`
 * asserts all of these are ABSENT in the load-failed state, alongside every
 * [capabilityToggleDescription]. A control added and not listed here silently drops out of that
 * assertion — which is how a wholesale-replace form ends up rendering from nothing.
 */
val CAPABILITIES_MUTATING_DESCRIPTIONS: List<String> = listOf(
    CAPABILITIES_SAVE_TOOLS_DESCRIPTION,
    CAPABILITIES_ENRICHMENT_TOGGLE_DESCRIPTION,
    CAPABILITIES_SAVE_ENRICHMENT_DESCRIPTION,
)
