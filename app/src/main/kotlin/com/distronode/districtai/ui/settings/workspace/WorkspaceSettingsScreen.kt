package com.distronode.districtai.ui.settings.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar

/**
 * The workspace settings hub: which section to open.
 *
 * ⛔ WORKSPACE-SCOPED, WHICH IS WHY IT IS NOT A ROW ON THE ACCOUNT SETTINGS SCREEN. `Routes.SETTINGS`
 * and `Routes.DEVICES` carry no workspace in their routes ON PURPOSE — sign-out and device
 * management belong to a USER and must stay reachable when no workspace resolves at all — so a
 * tenant-scoped section cannot hang off them without either encoding a workspace into a
 * destination whose siblings have none, or leaving this screen unable to name what it is
 * configuring. It is reached from the overview instead, where workspace context already exists.
 * Same reasoning as `Routes.BILLING`.
 *
 * ⛔ THE HUB IS OPEN TO VIEWERS AND THE ROWS ARE GATED. Persona, capabilities, the transfer
 * directory and the routing rules all hydrate from `workspace/config`, whose READ excludes
 * `viewer` server-side as a deliberate design, because the payload carries staff phone numbers and
 * the operator's own prompt. `workspace/knowledge`, `workspace/knowledge-mode` and
 * `workspace/messaging` have GETs that admit viewers by design. So a viewer sees exactly the two
 * sections whose reads they may make, and the four config-backed rows stay hidden rather than
 * leading to a 403.
 *
 * ⛔ THE MEMBERS AND MARKETPLACE ROWS STAY HIDDEN TOO, AND FOR A DIFFERENT REASON THAN THE CONFIG
 * FOUR. Their reads WOULD serve a viewer (the roster admits all three roles, and so does the
 * numbers view), but viewer access is scoped to knowledge and messaging, and each is a screen
 * whose own affordance gating would need auditing first. Recorded rather than quietly widened:
 * this is a deliberate stopping point, not an oversight, and `WorkspaceSettingsScreenTest` pins it.
 *
 * ⚠️ HOLDS NO STATE AND MAKES NO REQUEST. Each section loads its own configuration when it opens,
 * so a hub-level read would be a second copy of the same row going stale between screens. That is
 * also why [canMutate] is passed in rather than derived here — the hub has nothing to derive it
 * from, and the value that matters is the EFFECTIVE per-request role the overview resolved (support
 * access grants "agency" with no membership row, which the workspace list would understate).
 *
 * ⚠️ "Phone numbers" POINTS AT THE EXISTING MARKETPLACE SCREEN rather than re-reading anything
 * here. That screen is already built, already read-only, and already says where a number changes
 * hands; a second numbers view under settings would be a second place for that boundary to drift.
 */
@Composable
fun WorkspaceSettingsScreen(
    /**
     * ⛔ FALSE FOR A VIEWER AND FOR AN UNPARSEABLE ROLE. `WorkspaceRole.fromWire` fails closed to
     * null and `allowsMutation()` answers false for it, so a corrupted role segment shows the
     * viewer's two rows rather than the full eight — the safe direction.
     */
    canMutate: Boolean,
    onOpenPersona: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onOpenNumbers: () -> Unit,
    /**
     * ⚠️ ONE CALLBACK TAKING A SECTION RATHER THAN FOUR MORE PARAMETERS. Compose exempts
     * `@Composable` functions from detekt's `LongParameterList`, so this is not the ceiling talking
     * — it is that the four new rows are the same navigation with a different tail, exactly as
     * `Routes.workspaceSettings` models them.
     */
    onOpenSection: (String) -> Unit,
    onBack: () -> Unit,
) {
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = WORKSPACE_SETTINGS_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.workspace_settings_title), onBack = onBack)
        },
    ) { inset ->
        Column(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            ContentContainer {
                // ⛔ THE FOUR CONFIG-BACKED ROWS. Persona, capabilities, the transfer directory and
                // the routing rules all hydrate from `workspace/config`, whose GET excludes
                // `viewer` BY DESIGN — the payload carries staff phone numbers and the operator's
                // own prompt. That design stands, so a viewer is not offered a row that leads to a
                // read they cannot make. Everything inside this block is presence, not enablement:
                // a disabled row that 403s on tap is worse than no row.
                if (canMutate) {
                    DistrictListRow(
                        title = stringResource(R.string.persona_title),
                        subtitle = stringResource(R.string.workspace_settings_persona_subtitle),
                        onClick = onOpenPersona,
                        modifier = Modifier.semantics {
                            contentDescription = WORKSPACE_SETTINGS_PERSONA_ROW_DESCRIPTION
                        },
                    )
                    DistrictRowDivider()
                    // ⛔ THE STUDIO READ AND THE PERSONA PATCH BOTH EXCLUDE `viewer`, so the row
                    // sits with the persona's, inside the gate.
                    DistrictListRow(
                        title = stringResource(R.string.voice_studio_title),
                        subtitle = stringResource(R.string.workspace_settings_voice_studio_subtitle),
                        onClick = { onOpenSection(SECTION_VOICE_STUDIO) },
                        modifier = Modifier.semantics {
                            contentDescription = WORKSPACE_SETTINGS_VOICE_STUDIO_ROW_DESCRIPTION
                        },
                    )
                    DistrictRowDivider()
                    DistrictListRow(
                        title = stringResource(R.string.capabilities_title),
                        subtitle = stringResource(R.string.workspace_settings_capabilities_subtitle),
                        onClick = onOpenCapabilities,
                        modifier = Modifier.semantics {
                            contentDescription = WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION
                        },
                    )
                    DistrictRowDivider()
                    // ⛔ THE TWO DESTRUCTIVE-ARRAY EDITORS. Each opens its own configuration read
                    // and each save REPLACES a stored array, which is why they are separate
                    // destinations rather than tabs of one screen — see
                    // Routes.WORKSPACE_SETTINGS_DIRECTORY.
                    DistrictListRow(
                        title = stringResource(R.string.directory_title),
                        subtitle = stringResource(R.string.workspace_settings_directory_subtitle),
                        onClick = { onOpenSection(SECTION_DIRECTORY) },
                        modifier = Modifier.semantics {
                            contentDescription = WORKSPACE_SETTINGS_DIRECTORY_ROW_DESCRIPTION
                        },
                    )
                    DistrictRowDivider()
                    DistrictListRow(
                        title = stringResource(R.string.routing_title),
                        subtitle = stringResource(R.string.workspace_settings_routing_subtitle),
                        onClick = { onOpenSection(SECTION_ROUTING) },
                        modifier = Modifier.semantics {
                            contentDescription = WORKSPACE_SETTINGS_ROUTING_ROW_DESCRIPTION
                        },
                    )
                    DistrictRowDivider()
                }
                // ⛔ THE TWO ROWS EVERY ROLE GETS, AND THE ONLY TWO A VIEWER GETS. Both back onto
                // reads that admit `viewer` server-side: `workspace/knowledge`,
                // `workspace/knowledge-mode` and `workspace/messaging`. The screens behind them
                // gate their own write affordances on the role.
                // ⛔ OUTSIDE THE `canMutate` BLOCK, DELIBERATELY, AND IT IS THE ONLY SECTION HERE
                // THAT IS. Both routes behind it ADMIT `viewer` — the handling mode explains a call
                // list a viewer can already see, and the availability read answers a viewer `false`
                // with a reason rather than refusing — while both PATCHes exclude one. So the screen
                // is offered to every role and withholds its own controls, the same shape knowledge
                // and messaging use.
                DistrictListRow(
                    title = stringResource(R.string.call_handling_title),
                    subtitle = stringResource(R.string.workspace_settings_calls_subtitle),
                    onClick = { onOpenSection(SECTION_CALLS) },
                    modifier = Modifier.semantics {
                        contentDescription = WORKSPACE_SETTINGS_CALLS_ROW_DESCRIPTION
                    },
                )
                DistrictRowDivider()
                DistrictListRow(
                    title = stringResource(R.string.knowledge_title),
                    subtitle = stringResource(R.string.workspace_settings_knowledge_subtitle),
                    onClick = { onOpenSection(SECTION_KNOWLEDGE) },
                    modifier = Modifier.semantics {
                        contentDescription = WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION
                    },
                )
                DistrictRowDivider()
                DistrictListRow(
                    title = stringResource(R.string.messaging_title),
                    subtitle = stringResource(R.string.workspace_settings_messaging_subtitle),
                    onClick = { onOpenSection(SECTION_MESSAGING) },
                    modifier = Modifier.semantics {
                        contentDescription = WORKSPACE_SETTINGS_MESSAGING_ROW_DESCRIPTION
                    },
                )
                // ⛔ TWO ROWS WHOSE READS WOULD SERVE A VIEWER AND WHICH ARE STILL HIDDEN FROM ONE.
                // The member LIST admits all three roles and so does the marketplace, so this is a
                // deliberate stopping point rather than a rule: viewer access covers knowledge and
                // messaging, and each of these screens needs its own affordance audit before it is
                // widened. Recorded so the asymmetry is not read as
                // an oversight and not quietly "fixed" either way.
                if (canMutate) {
                    DistrictRowDivider()
                    DistrictListRow(
                        title = stringResource(R.string.members_title),
                        subtitle = stringResource(R.string.workspace_settings_members_subtitle),
                        onClick = { onOpenSection(SECTION_MEMBERS) },
                        modifier = Modifier.semantics {
                            contentDescription = WORKSPACE_SETTINGS_MEMBERS_ROW_DESCRIPTION
                        },
                    )
                    DistrictRowDivider()
                    DistrictListRow(
                        title = stringResource(R.string.marketplace_title),
                        subtitle = stringResource(R.string.workspace_settings_numbers_subtitle),
                        onClick = onOpenNumbers,
                        modifier = Modifier.semantics {
                            contentDescription = WORKSPACE_SETTINGS_NUMBERS_ROW_DESCRIPTION
                        },
                    )
                }
            }

            // ⛔ SAYS WHY THE LIST STOPS WHERE IT DOES, AND IT SAYS TWO DIFFERENT THINGS. What
            // remains web-only is campaign settings and the avatar form. For a VIEWER the sentence
            // is a different one entirely: two rows out of eight with no explanation reads as a
            // broken screen, and "edit it on the web" would be false, since a viewer cannot edit it
            // there either.
            ContentContainer {
                Text(
                    text = stringResource(
                        if (canMutate) {
                            R.string.workspace_settings_more_on_web
                        } else {
                            R.string.workspace_settings_viewer_note
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier
                        .padding(
                            horizontal = DistrictTheme.spacing.gutter,
                            vertical = DistrictTheme.spacing.row,
                        )
                        .semantics { contentDescription = WORKSPACE_SETTINGS_MORE_DESCRIPTION },
                )
            }
        }
    }
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val WORKSPACE_SETTINGS_ROOT_DESCRIPTION: String = "district-workspace-settings-root"
const val WORKSPACE_SETTINGS_VOICE_STUDIO_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-voice-studio"
const val WORKSPACE_SETTINGS_PERSONA_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-persona"
const val WORKSPACE_SETTINGS_CAPABILITIES_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-capabilities"
const val WORKSPACE_SETTINGS_NUMBERS_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-numbers"
const val WORKSPACE_SETTINGS_DIRECTORY_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-directory"
const val WORKSPACE_SETTINGS_ROUTING_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-routing"
const val WORKSPACE_SETTINGS_KNOWLEDGE_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-knowledge"
const val WORKSPACE_SETTINGS_MESSAGING_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-messaging"
const val WORKSPACE_SETTINGS_MEMBERS_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-members"
const val WORKSPACE_SETTINGS_MORE_DESCRIPTION: String = "district-workspace-settings-more"
const val WORKSPACE_SETTINGS_CALLS_ROW_DESCRIPTION: String =
    "district-workspace-settings-row-calls"

/**
 * The section segments this hub navigates to.
 *
 * ⚠️ DUPLICATED FROM `Routes` DELIBERATELY-NOT: these are the same literals, and they are declared
 * here only because the hub is in the `settings.workspace` package while `Routes` is in `ui`, and a
 * UI package must not depend on the navigation graph. `RoutesTest` pins that they agree.
 */
internal const val SECTION_DIRECTORY = "directory"
internal const val SECTION_ROUTING = "routing"
internal const val SECTION_KNOWLEDGE = "knowledge"
internal const val SECTION_MESSAGING = "messaging"
internal const val SECTION_MEMBERS = "members"
internal const val SECTION_CALLS = "calls"
internal const val SECTION_VOICE_STUDIO = "voice-studio"
