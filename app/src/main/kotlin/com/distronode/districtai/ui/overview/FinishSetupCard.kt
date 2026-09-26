package com.distronode.districtai.ui.overview

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme

/**
 * "Finish setting up on the web", for a workspace OWNER who is still in the setup wizard (setup
 * wizard plan 2.5).
 *
 * ⛔ A POINTER, NOT THE WIZARD. Setup decides what the business's receptionist says and which
 * number it answers, and it runs on the web only. The card says so and opens the dashboard; it
 * carries no step list, because a list here would be a second copy of the wizard's state that
 * could disagree with the first.
 */
@Composable
internal fun FinishSetupCard(onFinishSetup: () -> Unit) {
    // ⚠️ The gutter is applied here rather than taken as a `modifier`: the overview is the one
    // caller, and it always passed exactly this padding.
    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = OVERVIEW_FINISH_SETUP_DESCRIPTION },
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
                .semantics { contentDescription = OVERVIEW_FINISH_SETUP_ACTION_DESCRIPTION },
        )
    }
}

const val OVERVIEW_FINISH_SETUP_DESCRIPTION: String = "district-overview-finish-setup"
const val OVERVIEW_FINISH_SETUP_ACTION_DESCRIPTION: String = "district-overview-finish-setup-open"

/**
 * The web dashboard's District home, where the setup wizard runs.
 *
 * ⚠️ BUILT FROM THE APP'S OWN API BASE, like `marketplaceWebUrl`, so a staging build opens staging.
 * One host for every region on purpose: the edge routes by session, and this app deliberately
 * claims no `distronode.ca` links (see `AppLinkResolver`).
 */
internal fun setupWebUrl(apiBaseUrl: String): String = apiBaseUrl.trimEnd('/') + SETUP_WEB_PATH

/** ⚠️ The web dashboard's own route. A rename there is a broken link here and nothing catches it. */
internal const val SETUP_WEB_PATH: String = "/dashboard/district"
