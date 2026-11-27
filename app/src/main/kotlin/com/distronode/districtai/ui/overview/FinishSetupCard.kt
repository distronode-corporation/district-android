// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.overview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme

/**
 * "Finish setting up", for a workspace OWNER who is still in the setup wizard.
 *
 * ⛔ THE BUTTON OPENS THE WIZARD IN THE APP (`SetupWizardScreen`), whose six steps run here and whose
 * hand-offs (buying a number, a calendar sign-in, the email identity, the transfer number) open the
 * web's page in a Custom Tab from inside it. The card carries no step list: the wizard reads the
 * steps from the service, and a second copy here could disagree with it.
 */
@Composable
internal fun FinishSetupCard(onFinishSetup: () -> Unit) {
    // ⚠️ The gutter is applied here rather than taken as a `modifier`: the overview is the one
    // caller, and it always passed exactly this padding.
    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .testTag(OVERVIEW_FINISH_SETUP_DESCRIPTION),
    ) {
        Text(
            text = stringResource(R.string.overview_finish_setup_title),
            style = MaterialTheme.typography.titleMedium,
            color = DistrictTheme.colors.foreground,
        )
        Text(
            text = stringResource(R.string.overview_finish_setup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        DistrictButton(
            text = stringResource(R.string.overview_finish_setup_action),
            onClick = onFinishSetup,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DistrictTheme.spacing.row)
                .testTag(OVERVIEW_FINISH_SETUP_ACTION_DESCRIPTION),
        )
    }
}

const val OVERVIEW_FINISH_SETUP_DESCRIPTION: String = "district-overview-finish-setup"
const val OVERVIEW_FINISH_SETUP_ACTION_DESCRIPTION: String = "district-overview-finish-setup-open"

/**
 * The web dashboard's District home, where the web's own setup wizard runs. The app's wizard opens it
 * for buying a number, and the Studio pages under it for the rest of its hand-offs.
 *
 * ⚠️ BUILT FROM THE APP'S OWN API BASE, like `marketplaceWebUrl`, so a staging build opens staging.
 * One host for every region on purpose: the edge routes by session, and this app deliberately
 * claims no `distronode.ca` links (see `AppLinkResolver`).
 */
internal fun setupWebUrl(apiBaseUrl: String): String = apiBaseUrl.trimEnd('/') + SETUP_WEB_PATH

/** ⚠️ The web dashboard's own route. A rename there is a broken link here and nothing catches it. */
internal const val SETUP_WEB_PATH: String = "/dashboard/district"
