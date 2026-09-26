package com.distronode.districtai.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.PageHeader
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.SignedOutCause
import com.distronode.districtai.ui.resolve

/**
 * The signed-in landing screen.
 *
 * ⚠️ STILL NO "QUICK ACTIONS" GRID. The web dashboard has one (Contacts, Live Calls, Analytics,
 * Global Intelligence); the destinations arrive one task at a time, and a link is added here only
 * once the screen behind it exists. Shipping the grid ahead of them would mean buttons that do
 * nothing — worse than an honest absence, and the kind of thing that gets shipped and forgotten.
 */
@Composable
fun OverviewScreen(
    state: OverviewUiState,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenCallLog: (String) -> Unit = {},
    onOpenContacts: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    onOpenHq: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    /**
     * ⚠️ TAKES NO ROLE, unlike its two neighbours. Analytics is read-only for every role the
     * server admits, so there is nothing on that screen to gate.
     */
    onOpenAnalytics: (String) -> Unit = {},
    /**
     * ⚠️ TAKES A ROLE EVEN THOUGH THE MARKETPLACE IS ALSO READ-ONLY FOR EVERY ROLE — the opposite
     * call from [onOpenAnalytics] one line up, which is exactly why it is worth a note. Nothing
     * there is gated; the role decides what its read-only caption SAYS, since telling a viewer to
     * make the change on the web dashboard sends them somewhere that will also refuse them.
     */
    onOpenMarketplace: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    /**
     * ⛔ HERE RATHER THAN ON THE SETTINGS SCREEN, WHICH IS WHERE A BILLING ROW WOULD NORMALLY GO,
     * AND THE REASON IS SCOPE. Settings and Devices are deliberately ACCOUNT-scoped — their routes
     * carry no `workspaceId` and must stay reachable when no workspace resolves at all, which is
     * exactly the state a Play reviewer's fresh account is in. Billing is a property of ONE
     * WORKSPACE: the plan, the overage cap and the metered minutes all belong to a tenant, and the
     * route answers 404 without one. This screen is where workspace context already exists.
     *
     * ⚠️ TAKES A ROLE, like the marketplace and unlike analytics. Nothing behind it is gated; the
     * role decides what the read-only caption SAYS.
     */
    onOpenBilling: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    /**
     * ⛔ THE ENTRY IS SHOWN TO VIEWERS, AND THE ROWS INSIDE THE HUB CARRY THE GATE. Viewers have
     * read access to the knowledge base and the messaging accounts, both of which live behind this
     * hub. `workspace/config` excludes `viewer` from the READ as well as the writes, because the
     * payload is staff transfer numbers and the operator's own prompt, and that is why the four
     * sections backed by it stay hidden from a viewer.
     *
     * ⚠️ STILL CARRIES THE OVERVIEW'S ROLE, which is the EFFECTIVE per-request one: support
     * access can grant "agency" with no membership row, and the workspace list would understate
     * that.
     */
    onOpenWorkspaceSettings: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    /**
     * ⚠️ CARRIES A ROLE even though all three may read the meetings list and all three may join a
     * room. It is not a gate on the lobby; it is what the lobby hands to the ROOM, where a viewer's
     * token carries `canPublish:false` and the media controls are disabled accordingly.
     */
    onOpenRooms: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    onOpenDialer: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    /**
     * ⛔ A THIRD KIND OF ROLE ARGUMENT ON THIS SCREEN, AND IT IS WORTH NAMING BECAUSE THE OTHER
     * TWO ARE ALREADY DOCUMENTED ABOVE. [onOpenAnalytics] carries no role (nothing behind it is
     * gated); [onOpenWorkspaceSettings] and [onOpenDialer] carry one and their ENTRY is hidden
     * (every route behind them 403s a viewer). This one is UNCONDITIONAL and carries a role: the
     * workflow list, the run history and the campaign status all admit viewers, and only the
     * per-workflow toggle excludes them. So the destination is offered to everybody and the role
     * decides whether one control on it works — hiding it would take the answer away along with
     * the switch, from the role most likely to be asked to check.
     */
    onOpenWorkflows: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    /**
     * ⛔ A FOURTH KIND OF ROLE ARGUMENT — the absence of one, for a REASON THE OTHER THREE DO NOT
     * HAVE. [onOpenAnalytics] also carries none, but only because nothing behind it is gated. Here
     * something IS gated (the Enable button is owner/admin only, and allowlist-gated on top) and
     * the SERVER answers it: `GET /api/district/scheduling/status` sends `canManage` on every read,
     * so a role passed from here would be a second copy of a fact already arriving with the data —
     * one that fails CLOSED through `WorkspaceRole.fromWire` and would therefore hide the button
     * from an owner whom the server would have admitted.
     *
     * ⚠️ UNCONDITIONAL, like the workflows and rooms entries. The status route admits `viewer`
     * explicitly: whether the workspace has booking pages and where they are is the same class of
     * fact as "this workspace has a phone number", and hiding it would take the ANSWER away along
     * with a control a viewer never had.
     */
    onOpenScheduling: (String) -> Unit = {},
    /**
     * The two ticket surfaces, and they are MIRROR IMAGES rather than a pair.
     *
     * ⛔ [onOpenDesk] IS THE TENANT'S OWN CUSTOMERS' TICKETS WITH THEM; [onOpenSupport] IS THE
     * TENANT'S REQUESTS WITH DISTRONODE. Two callbacks with the same signature, adjacent, whose
     * meanings are opposite, which is exactly the shape a positional call site transposes, and the
     * consequence of transposing them is an operator answering the wrong people. Name them at the
     * call site.
     *
     * ⚠️ BOTH CARRY THE ROLE, and on these two it is a real gate rather than a caption: every route
     * behind either surface excludes `viewer`, the READS included (`DeskViewModel.canUse`,
     * `SupportViewModel.canUse`).
     */
    onOpenDesk: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    onOpenSupport: (String, WorkspaceRole?) -> Unit = { _, _ -> },
    /**
     * Opens the web dashboard, where the setup wizard runs. Offered only when
     * [OverviewUiState.Content.showFinishSetup] is true, i.e. to the workspace OWNER mid-wizard.
     *
     * ⛔ TAKES NOTHING. The wizard's page resolves the workspace from the web session, and the
     * caller must open it in a real browser (never an implicit intent), because this app is a
     * verified App Link handler for the very URL it opens. See `openInBrowser`.
     */
    onFinishSetup: () -> Unit = {},
) {
    // ⚠️ NO `modifier` PARAMETER: the one caller (the nav graph) never sized or placed this screen.
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = OVERVIEW_ROOT_DESCRIPTION },
        topBar = {
            // ⛔ IN THE APP BAR, SO IT IS PRESENT IN EVERY STATE, AND THAT PLACEMENT IS THE
            // REQUIREMENT. Sign-out and account deletion are Play prerequisites and they are
            // properties of the ACCOUNT — an account with no workspace, one whose subscription
            // lapsed, or one whose region is unreachable still has to reach both. Putting this
            // inside the Content branch would hide them from exactly the accounts a reviewer is
            // most likely to be holding.
            DistrictTopBar(
                title = stringResource(R.string.app_name),
                actions = {
                    DistrictButton(
                        text = stringResource(R.string.settings_open),
                        onClick = onOpenSettings,
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        modifier = Modifier.semantics {
                            contentDescription = OVERVIEW_OPEN_SETTINGS_DESCRIPTION
                        },
                    )
                },
            )
        },
    ) { inset ->
        when (state) {
            OverviewUiState.Loading -> LoadingState(inset)
            is OverviewUiState.Content -> ContentState(
                state = state,
                inset = inset,
                onRetry = onRetry,
                onSelectWorkspace = onSelectWorkspace,
                onOpenCallLog = onOpenCallLog,
                onOpenContacts = onOpenContacts,
                onOpenHq = onOpenHq,
                onOpenAnalytics = onOpenAnalytics,
                onOpenMarketplace = onOpenMarketplace,
                onOpenBilling = onOpenBilling,
                onOpenWorkspaceSettings = onOpenWorkspaceSettings,
                onOpenRooms = onOpenRooms,
                onOpenDialer = onOpenDialer,
                onOpenWorkflows = onOpenWorkflows,
                onOpenScheduling = onOpenScheduling,
                onOpenDesk = onOpenDesk,
                onOpenSupport = onOpenSupport,
                onFinishSetup = onFinishSetup,
            )
            OverviewUiState.NoWorkspaces -> MessageState(
                inset = inset,
                title = stringResource(R.string.overview_none_title),
                body = stringResource(R.string.overview_none_body),
                description = OVERVIEW_NO_WORKSPACES_DESCRIPTION,
            )
            is OverviewUiState.BillingBlocked -> MessageState(
                inset = inset,
                title = stringResource(R.string.overview_billing_title),
                // ⚠️ Read-only billing (Play Payments policy): this states the situation and names
                // the website. It deliberately offers no purchase path and no deep link into one.
                body = pluralStringResource(
                    R.plurals.overview_billing_body,
                    state.inactiveCount,
                    state.inactiveCount,
                ),
                description = OVERVIEW_BILLING_DESCRIPTION,
            )
            is OverviewUiState.Unavailable -> MessageState(
                inset = inset,
                title = stringResource(R.string.overview_unavailable_title),
                body = state.message.resolve(),
                description = OVERVIEW_UNAVAILABLE_DESCRIPTION,
                detail = state.degradedRegions
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        stringResource(R.string.overview_degraded_regions, it.joinToString(", "))
                    },
                // ⛔ ALWAYS RETRYABLE. This state means "we could not finish reading", never
                // "there is nothing" — so the one thing it must always offer is another attempt.
                action = stringResource(R.string.overview_retry) to onRetry,
            )
            // ⚠️ NEVER_SIGNED_IN is handled by the caller, which shows the ordinary sign-in screen
            // instead — "please sign in AGAIN" is wrong on a first run. Reaching this branch with
            // that cause would still be readable, just less apt.
            is OverviewUiState.SignedOut -> MessageState(
                inset = inset,
                title = stringResource(R.string.overview_signed_out_title),
                body = stringResource(
                    if (state.cause == SignedOutCause.ROUTINE) {
                        // Process death mid-refresh: a sign-out, but NOT a security event, and
                        // there is no recovery other than signing in again. An alarming message
                        // would be both wrong and unactionable.
                        R.string.overview_signed_out_routine
                    } else {
                        R.string.overview_signed_out_body
                    },
                ),
                description = OVERVIEW_SIGNED_OUT_DESCRIPTION,
                action = stringResource(R.string.overview_sign_in_again) to onSignIn,
            )
        }
    }
}

/**
 * ⚠️ SKELETON BLOCKS IN THE SHAPE OF THE CONTENT, not a centred spinner. A spinner in the middle
 * of an empty screen says only "wait"; blocks laid out as a header and four tiles say what is
 * arriving, and the layout does not jump when it does.
 */
@Composable
private fun LoadingState(inset: Modifier) {
    ContentContainer(modifier = inset.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = OVERVIEW_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            SkeletonBlock(
                height = SKELETON_TITLE_HEIGHT,
                modifier = Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION),
            )
            SkeletonBlock(modifier = Modifier.fillMaxWidth(SKELETON_SUBTITLE_FRACTION))
            repeat(SKELETON_TILE_ROWS) {
                SkeletonBlock(height = SKELETON_TILE_HEIGHT)
            }
        }
    }
}

@Composable
private fun ContentState(
    state: OverviewUiState.Content,
    inset: Modifier,
    onRetry: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onOpenCallLog: (String) -> Unit,
    onOpenContacts: (String, WorkspaceRole?) -> Unit,
    onOpenHq: (String, WorkspaceRole?) -> Unit,
    onOpenAnalytics: (String) -> Unit,
    onOpenMarketplace: (String, WorkspaceRole?) -> Unit,
    onOpenBilling: (String, WorkspaceRole?) -> Unit,
    onOpenWorkspaceSettings: (String, WorkspaceRole?) -> Unit,
    onOpenRooms: (String, WorkspaceRole?) -> Unit,
    onOpenDialer: (String, WorkspaceRole?) -> Unit,
    onOpenWorkflows: (String, WorkspaceRole?) -> Unit,
    onOpenScheduling: (String) -> Unit,
    onOpenDesk: (String, WorkspaceRole?) -> Unit,
    onOpenSupport: (String, WorkspaceRole?) -> Unit,
    onFinishSetup: () -> Unit,
) {
    LazyColumn(
        modifier = inset.fillMaxSize(),
        // ⚠️ Padding on the CONTENT, not the LazyColumn: a modifier padding clips the scroll
        // container, so a row scrolling past the bottom edge would be cut rather than sliding out.
        contentPadding = PaddingValues(
            bottom = DistrictTheme.spacing.header,
        ),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        item {
            ContentContainer {
                WorkspaceHeader(
                    active = state.active,
                    workspaces = state.workspaces,
                    canSwitch = state.canSwitchWorkspace,
                    readOnly = !state.canMutate,
                    onSelectWorkspace = onSelectWorkspace,
                )
            }
        }

        // ⛔ SURFACED, NOT SWALLOWED. A degraded region means the switcher is missing entries. The
        // figures are correct — they came from one workspace — but implying the user has fewer
        // workspaces than they do is the same class of mistake as showing an empty account.
        if (state.degradedRegions.isNotEmpty()) {
            item {
                ContentContainer {
                    DistrictBadge(
                        text = stringResource(
                            R.string.overview_partial_warning,
                            state.degradedRegions.joinToString(", "),
                        ),
                        tone = Tone.Warning,
                        modifier = Modifier
                            .padding(horizontal = DistrictTheme.spacing.gutter)
                            .semantics { contentDescription = OVERVIEW_PARTIAL_DESCRIPTION },
                    )
                }
            }
        }

        // ⚠️ ABOVE THE METRICS, because for an owner mid-setup the numbers are mostly zeros and
        // finishing setup is the one thing that changes them.
        if (state.showFinishSetup) {
            item {
                ContentContainer {
                    FinishSetupCard(onFinishSetup = onFinishSetup)
                }
            }
        }

        if (state.refreshing) {
            item {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = DistrictTheme.colors.district,
                    trackColor = DistrictTheme.colors.muted,
                )
            }
        }

        item {
            ContentContainer {
                MetricTiles(overview = state.overview)
            }
        }

        item {
            ContentContainer {
                SectionHeading(
                    label = stringResource(R.string.overview_recent_activity),
                    actionLabel = stringResource(R.string.call_log_open),
                    onAction = { onOpenCallLog(state.active.id) },
                )
            }
        }

        if (state.overview.recentCalls.isEmpty()) {
            item {
                ContentContainer {
                    EmptyState(
                        title = stringResource(R.string.overview_recent_empty_title),
                        body = stringResource(R.string.overview_recent_empty),
                    )
                }
            }
        } else {
            items(state.overview.recentCalls, key = { it.id }) { call ->
                ContentContainer {
                    ActivityRow(call)
                    DistrictRowDivider()
                }
            }
        }

        item {
            ContentContainer {
                Column(
                    modifier = Modifier.padding(
                        horizontal = DistrictTheme.spacing.gutter,
                        vertical = DistrictTheme.spacing.section,
                    ),
                    verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
                ) {
                    // ⚠️ Passes the OVERVIEW's role, which is the EFFECTIVE one for this caller:
                    // support access can grant "agency" with no membership row, and the
                    // workspace list would understate that.
                    DistrictButton(
                        text = stringResource(R.string.contacts_open),
                        onClick = { onOpenContacts(state.active.id, state.overview.role) },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = OVERVIEW_OPEN_CONTACTS_DESCRIPTION },
                    )
                    // ⚠️ HERE RATHER THAN IN THE NAV BAR, AND THAT IS A MEASURED CONSTRAINT, NOT A
                    // preference. The bar already carries five text items; a sixth is what pushes a
                    // text-labelled row past its width budget, and the failure mode is that a
                    // control is silently squeezed out of reach rather than wrapping visibly. HQ is
                    // also a place you go deliberately, not a tab you switch between.
                    //
                    // ⚠️ Passes the OVERVIEW's role for the same reason Contacts does: it is the
                    // EFFECTIVE role for this caller, which the workspace list can understate.
                    DistrictButton(
                        text = stringResource(R.string.hq_open),
                        onClick = { onOpenHq(state.active.id, state.overview.role) },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = OVERVIEW_OPEN_HQ_DESCRIPTION },
                    )
                    // ⚠️ HERE RATHER THAN IN THE NAV BAR, FOR THE SAME MEASURED REASON HQ IS. The
                    // bar carries five text items and a sixth pushes a text-labelled row past its
                    // width budget — where the failure mode is that a control is silently squeezed
                    // out of reach rather than wrapping visibly. This column is full-width buttons
                    // stacked vertically, so it has no such ceiling.
                    //
                    // ⚠️ AND IT IS LAST OF THE THREE DESTINATIONS, ABOVE the retry. The order is
                    // Contacts, HQ, Analytics — most-used first — and the ghost retry stays at the
                    // bottom so a new entry never displaces the control an operator reaches for
                    // when the screen looks wrong.
                    //
                    // ⚠️ NO ROLE PASSED. Both routes behind the screen admit viewers.
                    DistrictButton(
                        text = stringResource(R.string.analytics_open),
                        onClick = { onOpenAnalytics(state.active.id) },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = OVERVIEW_OPEN_ANALYTICS_DESCRIPTION },
                    )
                    // ⚠️ THE FOURTH DESTINATION IN THIS COLUMN, AND STILL NOT IN THE NAV BAR, for
                    // the measured reason HQ and Analytics are not: the bar carries five text
                    // items and a sixth pushes a text-labelled row past its width budget, where
                    // the failure mode is a control silently squeezed out of reach rather than
                    // wrapping visibly. This column is full-width buttons stacked vertically and
                    // has no such ceiling.
                    //
                    // ⚠️ AND IT IS LAST OF THE FOUR, ABOVE the retry — the ghost retry stays at
                    // the bottom so a new entry never displaces the control an operator reaches
                    // for when the screen looks wrong.
                    //
                    // ⚠️ Passes the OVERVIEW's role, which is the EFFECTIVE one for this caller.
                    // It gates nothing on that screen; it decides what its read-only caption
                    // says.
                    DistrictButton(
                        text = stringResource(R.string.marketplace_open),
                        onClick = { onOpenMarketplace(state.active.id, state.overview.role) },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = OVERVIEW_OPEN_MARKETPLACE_DESCRIPTION
                            },
                    )
                    // ⚠️ THE FIFTH DESTINATION IN THIS COLUMN, AND THE FIRST THAT WOULD MORE
                    // NATURALLY LIVE UNDER THE ACCOUNT SCREEN. It does not, and the reason is
                    // scope rather than layout: Settings and Devices carry no workspace in their
                    // routes ON PURPOSE, and a plan, an overage cap and a month's metered minutes
                    // all belong to one tenant. See the ⛔ on Routes.BILLING.
                    //
                    // ⚠️ AND IT IS LAST OF THE FIVE, STILL ABOVE the retry — the ghost retry stays
                    // at the bottom so a new entry never displaces the control an operator reaches
                    // for when the screen looks wrong.
                    //
                    // ⚠️ Passes the OVERVIEW's role, which is the EFFECTIVE one for this caller.
                    // It gates nothing on that screen; it decides what its read-only caption says.
                    DistrictButton(
                        text = stringResource(R.string.billing_open),
                        onClick = { onOpenBilling(state.active.id, state.overview.role) },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = OVERVIEW_OPEN_BILLING_DESCRIPTION
                            },
                    )
                    // ⛔ THE SIXTH DESTINATION, AND THE ONLY ONE IN THIS COLUMN THAT IS
                    // CONDITIONAL. Every route behind it — including the config READ — excludes
                    // `viewer` server-side, unusually for this surface and deliberately: the
                    // payload is the staff transfer directory and the operator's own prompt. So
                    // this is not a caption difference like the marketplace's, it is presence.
                    // `canMutate` reads the OVERVIEW's role, which is the effective per-request
                    // one: support access grants "agency" with no membership row, and the
                    // workspace list would understate that.
                    //
                    // ⚠️ STILL ABOVE the retry, like every entry before it: the ghost retry stays
                    // at the bottom so a new row never displaces the control an operator reaches
                    // for when the screen looks wrong.
                    // ⚠️ THE SIXTH DESTINATION IN THIS COLUMN AND STILL NOT IN THE NAV BAR, for the
                    // measured reason every entry above it is not: the bar carries five text items
                    // and a sixth pushes a text-labelled row past its width budget, where the
                    // failure mode is a control silently squeezed out of reach rather than wrapping
                    // visibly. This column is full-width buttons stacked vertically.
                    //
                    // ⚠️ UNCONDITIONAL, unlike the workspace-settings entry below it. Every route
                    // behind the rooms lobby admits `viewer` — the meetings list, the detail and the
                    // join token all do — so there is nothing here to hide. What a viewer gets is a
                    // room it cannot publish into, which the room itself states.
                    DistrictButton(
                        text = stringResource(R.string.rooms_open),
                        onClick = { onOpenRooms(state.active.id, state.overview.role) },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = OVERVIEW_OPEN_ROOMS_DESCRIPTION },
                    )
                    // ⚠️ THE SEVENTH DESTINATION, AND THE ONLY ONE WHOSE ROLE IS A *PARTIAL*
                    // GATE. Analytics carries no role at all; the marketplace and billing carry
                    // one that only words a caption; workspace settings and the dialler are
                    // hidden outright. This entry is UNCONDITIONAL — the workflow list, the run
                    // history and the SDR campaign status all admit `viewer` server-side — and
                    // the role it passes decides one thing on the screen behind it: whether the
                    // per-workflow switch is enabled. Hiding it from a viewer would remove the
                    // ANSWER ("is the follow-up automation running") along with the control.
                    //
                    // ⚠️ ABOVE the two conditional entries and still above the ghost retry, like
                    // every row before it, so a new destination never displaces the control an
                    // operator reaches for when the screen looks wrong.
                    DistrictButton(
                        text = stringResource(R.string.workflows_open),
                        onClick = { onOpenWorkflows(state.active.id, state.overview.role) },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = OVERVIEW_OPEN_WORKFLOWS_DESCRIPTION
                            },
                    )
                    // ⚠️ THE EIGHTH DESTINATION, UNCONDITIONAL, AND THE ONLY ONE IN THIS COLUMN
                    // THAT PASSES NO ROLE WHILE STILL HAVING SOMETHING GATED BEHIND IT. The
                    // scheduling status route admits `viewer` and sends `canManage` with every
                    // read, so the screen decides its own Enable button from the server's answer
                    // rather than from anything carried here. See the ⛔ on [onOpenScheduling].
                    //
                    // ⚠️ IMMEDIATELY BEFORE THE TWO CONDITIONAL ENTRIES AND STILL ABOVE THE GHOST
                    // RETRY, like every row before it. iOS puts it in the same place for the same
                    // reason — last of the workspace surfaces, because it is configuration rather
                    // than an operating screen.
                    DistrictButton(
                        text = stringResource(R.string.scheduling_open),
                        onClick = { onOpenScheduling(state.active.id) },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = OVERVIEW_OPEN_SCHEDULING_DESCRIPTION
                            },
                    )
                    // ⛔ THE NINTH DESTINATION AND THE SECOND CONDITIONAL ONE, gated for the
                    // same reason workspace settings is and unlike the rooms entry above it:
                    // `POST /api/district/calls/dial` excludes `viewer` server-side, so this is
                    // PRESENCE rather than a caption difference. A viewer offered this would meet
                    // a 403 on the screen's only action.
                    //
                    // ⚠️ ABOVE workspace settings deliberately — placing a call is a daily action
                    // and configuration is not — and both stay above the ghost retry, so a new
                    // entry never displaces the control an operator reaches for when the screen
                    // looks wrong.
                    //
                    // ⚠️ Passes the OVERVIEW's role, the EFFECTIVE one for this caller: support
                    // access can grant "agency" with no membership row, and the workspace list
                    // would understate that.
                    if (state.canMutate) {
                        DistrictButton(
                            text = stringResource(R.string.dialer_open),
                            onClick = { onOpenDialer(state.active.id, state.overview.role) },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = OVERVIEW_OPEN_DIALER_DESCRIPTION
                                },
                        )
                        // ⛔ THE TENTH AND ELEVENTH DESTINATIONS, INSIDE THE DIALLER'S GATE, AND
                        // THEY ARE MIRROR IMAGES RATHER THAN A PAIR. "Customer desk" is the tickets
                        // this workspace's own CUSTOMERS have raised with THEM; "Support" is this
                        // workspace's requests with DISTRONODE. Neither may ever be labelled
                        // "Tickets" alone: an operator who opens the wrong one answers the wrong
                        // people.
                        //
                        // ⛔ GATED ON `canMutate`, WHICH IS PRESENCE RATHER THAN A CAPTION, and the
                        // same rule both screens apply to themselves (`canUse` is
                        // `role.allowsMutation()` in DeskViewModel and SupportViewModel). Every
                        // route behind BOTH surfaces, the READS included, excludes `viewer`
                        // server-side, because those responses carry a customer's name, email and
                        // phone number plus the correspondence about them. A viewer offered either
                        // entry would be shown a refusal on arrival.
                        //
                        // ⚠️ Inside the existing block rather than a second `if` of their own, so
                        // this function's branch count does not move for two rows that share a
                        // gate with the dialler. Still above workspace settings and the ghost retry.
                        DistrictButton(
                            text = stringResource(R.string.desk_open),
                            onClick = { onOpenDesk(state.active.id, state.overview.role) },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = OVERVIEW_OPEN_DESK_DESCRIPTION
                                },
                        )
                        DistrictButton(
                            text = stringResource(R.string.support_open),
                            onClick = { onOpenSupport(state.active.id, state.overview.role) },
                            variant = ButtonVariant.Secondary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = OVERVIEW_OPEN_SUPPORT_DESCRIPTION
                                },
                        )
                    }
                    // ⛔ UNCONDITIONAL, LIKE THE ROOMS AND WORKFLOWS ENTRIES ABOVE IT, NOT GATED ON
                    // `canMutate`. Viewers have read access to the knowledge base and the messaging
                    // accounts, whose GETs admit viewers by design, and both live behind this hub.
                    // The persona, capability, directory and routing sections exclude viewer
                    // server-side, READ included, so the gate sits one level DOWN: the hub is open
                    // and it hides the four rows a viewer cannot read.
                    // ⚠️ The role is still passed, and it still decides what is drawn behind here.
                    DistrictButton(
                        text = stringResource(R.string.workspace_settings_open),
                        onClick = {
                            onOpenWorkspaceSettings(state.active.id, state.overview.role)
                        },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = OVERVIEW_OPEN_WORKSPACE_SETTINGS_DESCRIPTION
                            },
                    )
                    DistrictButton(
                        text = stringResource(R.string.overview_retry),
                        onClick = onRetry,
                        variant = ButtonVariant.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * An eyebrow-labelled section rule with a trailing link. Mirrors the browser's list headings.
 *
 * ⚠️ The link's description is the call log's constant rather than a parameter: this has one
 * caller, and a parameter that only ever carried one compile-time constant was a branch no input
 * could take.
 */
@Composable
private fun SectionHeading(
    label: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = DistrictTheme.spacing.gutter,
                end = DistrictTheme.spacing.tight,
                top = DistrictTheme.spacing.tight,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(text = label, modifier = Modifier.weight(1f))
        DistrictButton(
            text = actionLabel,
            onClick = onAction,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier.semantics { contentDescription = OVERVIEW_OPEN_CALL_LOG_DESCRIPTION },
        )
    }
}

@Composable
private fun WorkspaceHeader(
    active: WorkspaceEntry,
    workspaces: List<WorkspaceEntry>,
    canSwitch: Boolean,
    readOnly: Boolean,
    onSelectWorkspace: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(
            start = DistrictTheme.spacing.gutter,
            end = DistrictTheme.spacing.gutter,
            top = DistrictTheme.spacing.gutter,
        ),
    ) {
        PageHeader(
            title = active.name,
            // Region is shown because data residency is a product promise here, not a detail.
            subtitle = stringResource(R.string.overview_workspace_region, active.region.uppercase()),
            actions = {
                if (readOnly) {
                    // ⚠️ Stated up front rather than discovered by tapping something that 403s. The
                    // server excludes viewers from every mutating route.
                    DistrictBadge(
                        text = stringResource(R.string.overview_role_viewer),
                        tone = Tone.Neutral,
                        modifier = Modifier.semantics {
                            contentDescription = OVERVIEW_READ_ONLY_DESCRIPTION
                        },
                    )
                }
                if (canSwitch) {
                    DistrictButton(
                        text = stringResource(R.string.overview_switch_workspace),
                        onClick = { expanded = true },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Sm,
                        modifier = Modifier.semantics {
                            contentDescription = OVERVIEW_SWITCHER_DESCRIPTION
                        },
                    )
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            workspaces.forEach { workspace ->
                DropdownMenuItem(
                    text = { Text(workspace.name) },
                    onClick = {
                        expanded = false
                        onSelectWorkspace(workspace.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun MessageState(
    inset: Modifier,
    title: String,
    body: String,
    description: String,
    detail: String? = null,
    action: Pair<String, () -> Unit>? = null,
) {
    ContentContainer(modifier = inset.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = description },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = DistrictTheme.colors.foreground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
                )
            }
            action?.let { (label, onClick) ->
                DistrictButton(
                    text = label,
                    onClick = onClick,
                    modifier = Modifier.padding(top = DistrictTheme.spacing.section),
                )
            }
        }
    }
}

private val SKELETON_TITLE_HEIGHT = 28.dp
private val SKELETON_TILE_HEIGHT = 72.dp
private const val SKELETON_TILE_ROWS = 4

/**
 * ⚠️ Fractions, not fixed widths. A skeleton stands in for a WORKSPACE NAME, whose length is
 * unknown, so a fixed 400dp block would be wrong on both a phone and this app's 1920px surface.
 * These are roughly the proportions a real name and region line occupy.
 */
private const val SKELETON_TITLE_FRACTION = 0.6f
private const val SKELETON_SUBTITLE_FRACTION = 0.3f

/**
 * Stable handles for tests.
 *
 * ⚠️ Constants rather than literals duplicated in the test: a renamed description in one place and
 * not the other produces a test that silently matches nothing.
 */
const val OVERVIEW_ROOT_DESCRIPTION: String = "district-overview-root"
const val OVERVIEW_LOADING_DESCRIPTION: String = "district-overview-loading"
const val OVERVIEW_NO_WORKSPACES_DESCRIPTION: String = "district-overview-no-workspaces"
const val OVERVIEW_BILLING_DESCRIPTION: String = "district-overview-billing-blocked"
const val OVERVIEW_UNAVAILABLE_DESCRIPTION: String = "district-overview-unavailable"
const val OVERVIEW_SIGNED_OUT_DESCRIPTION: String = "district-overview-signed-out"
const val OVERVIEW_PARTIAL_DESCRIPTION: String = "district-overview-partial-list"
const val OVERVIEW_SWITCHER_DESCRIPTION: String = "district-overview-workspace-switcher"
const val OVERVIEW_READ_ONLY_DESCRIPTION: String = "district-overview-read-only"
const val OVERVIEW_OPEN_CALL_LOG_DESCRIPTION: String = "district-overview-open-call-log"
const val OVERVIEW_OPEN_CONTACTS_DESCRIPTION: String = "district-overview-open-contacts"
const val OVERVIEW_OPEN_HQ_DESCRIPTION: String = "district-overview-open-hq"
const val OVERVIEW_OPEN_ANALYTICS_DESCRIPTION: String = "district-overview-open-analytics"
const val OVERVIEW_OPEN_MARKETPLACE_DESCRIPTION: String = "district-overview-open-marketplace"
const val OVERVIEW_OPEN_BILLING_DESCRIPTION: String = "district-overview-open-billing"
const val OVERVIEW_OPEN_ROOMS_DESCRIPTION: String = "district-overview-open-rooms"

/** ⚠️ Present for EVERY role — see the ⚠️ at its call site. Only the switch behind it is gated. */
const val OVERVIEW_OPEN_WORKFLOWS_DESCRIPTION: String = "district-overview-open-workflows"
const val OVERVIEW_OPEN_SCHEDULING_DESCRIPTION: String = "district-overview-open-scheduling"

/** ⛔ Present only for a role the dial route would admit. See the ⛔ at its call site. */
const val OVERVIEW_OPEN_DIALER_DESCRIPTION: String = "district-overview-open-dialer"

/** ⛔ Present only for a role the desk routes would admit, READS included. See its call site. */
const val OVERVIEW_OPEN_DESK_DESCRIPTION: String = "district-overview-open-desk"

/** ⛔ Present only for a role the support routes would admit. Not the desk: see its call site. */
const val OVERVIEW_OPEN_SUPPORT_DESCRIPTION: String = "district-overview-open-support"

/** ⛔ Present only for a role the server would admit. See the ⛔ at its call site. */
const val OVERVIEW_OPEN_WORKSPACE_SETTINGS_DESCRIPTION: String =
    "district-overview-open-workspace-settings"
const val OVERVIEW_OPEN_SETTINGS_DESCRIPTION: String = "district-overview-open-settings"
