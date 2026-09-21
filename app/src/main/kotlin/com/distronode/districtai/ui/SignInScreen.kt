package com.distronode.districtai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.auth.LoginStatus
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Tone

/**
 * Sign-in entry point.
 *
 * ⚠️ IT GETS THE CARD TREATMENT THE WEB'S LOGIN PAGE USES, BUT ITS JOB IS MINIMAL. It starts the
 * browser leg of the PKCE flow and reports the outcome; it collects no credentials, because the
 * password and both SSO providers live on the server's own login surface and a native form could
 * not reach them.
 *
 * @param status ⚠️ A [LoginStatus], not the `String?` this used to take. The activity held
 *   pre-rendered English, which made every login message the only copy in the app `stringResource`
 *   could not reach. Resolving it here is what puts the wording in `strings.xml`.
 */
@Composable
fun SignInScreen(
    status: LoginStatus?,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = SIGN_IN_ROOT_DESCRIPTION },
        color = DistrictTheme.colors.background,
    ) {
        ContentContainer(maxWidth = CARD_MAX_WIDTH) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DistrictTheme.spacing.section),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DistrictCard {
                    // ⛔ NO EYEBROW HERE, UNLIKE EVERY OTHER CARD IN THE APP, AND THE ABSENCE IS
                    // THE DECISION. The eyebrow is a micro-label naming the SECTION a card belongs
                    // to, which works when the heading underneath says something different. On the
                    // sign-in card the heading is the app's own name, so the eyebrow repeated it
                    // verbatim: "DISTRICT AI" directly above "District AI", which reads as a
                    // template nobody filled in rather than as branding.
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        color = DistrictTheme.colors.foreground,
                    )
                    Text(
                        text = stringResource(R.string.sign_in_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DistrictTheme.colors.mutedForeground,
                        modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
                    )
                    // ⚠️ THE ONLY CONTROL ON THIS SCREEN, AND THE ONLY ONE THE SMOKE FLOW CAN
                    // REACH BEFORE IT IS SIGNED IN. Addressed by handle rather than by its label,
                    // because `sign_in_action` is translatable and a flow matching the English
                    // string would fail on the first locale that ships.
                    DistrictButton(
                        text = stringResource(R.string.sign_in_action),
                        onClick = onSignIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = DistrictTheme.spacing.section)
                            .semantics { contentDescription = SIGN_IN_ACTION_DESCRIPTION },
                    )
                    // ⚠️ The browser hand-off is stated up front. Sign-in leaves the app, and a user
                    // who is not expecting that reads the browser opening as a crash or a redirect
                    // they did not ask for.
                    Text(
                        text = stringResource(R.string.sign_in_browser_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.mutedForeground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = DistrictTheme.spacing.row),
                    )
                }

                status?.let {
                    // ⛔ A TONED STATUS, NOT UNDIFFERENTIATED GREY TEXT. "Waiting for the browser"
                    // and "that sign-in was refused" are not the same kind of message, and the
                    // refusal case is the authorization-code-injection one — it should not look like
                    // a progress note.
                    StatusPanel(status = it, modifier = Modifier.padding(top = DistrictTheme.spacing.section))
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(status: LoginStatus, modifier: Modifier = Modifier) {
    val tone = toneForLoginStatus(status)
    val ink = when (tone) {
        Tone.Danger -> DistrictTheme.colors.destructive
        Tone.Warning -> DistrictTheme.colors.warning
        Tone.District -> DistrictTheme.colors.district
        else -> DistrictTheme.colors.mutedForeground
    }
    Text(
        text = status.text(),
        style = MaterialTheme.typography.bodyMedium,
        color = ink,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = SIGN_IN_STATUS_DESCRIPTION },
    )
}

/**
 * The user-facing sentence for a login status.
 *
 * ⚠️ Exhaustive `when` with no `else`, on purpose: adding a [LoginStatus] case without deciding what
 * it says to the user should not compile.
 */
@Composable
private fun LoginStatus.text(): String = when (this) {
    LoginStatus.NoBrowser -> stringResource(R.string.login_status_no_browser)
    LoginStatus.WaitingForBrowser -> stringResource(R.string.login_status_waiting)
    LoginStatus.DidNotComplete -> stringResource(R.string.login_status_did_not_complete)
    LoginStatus.Completing -> stringResource(R.string.login_status_completing)
    LoginStatus.Refused -> stringResource(R.string.login_status_refused)
    LoginStatus.LinkExpired -> stringResource(R.string.login_status_link_expired)
    LoginStatus.Expired -> stringResource(R.string.login_status_expired)
    LoginStatus.RateLimited -> stringResource(R.string.login_status_rate_limited)
    LoginStatus.Unreachable -> stringResource(R.string.login_status_unreachable)
    // ⚠️ The reason is the server's own text and is not translatable; the frame around it is.
    is LoginStatus.Denied -> stringResource(R.string.login_status_denied, reason)
}

/**
 * ⚠️ Narrower than [ContentContainer]'s 720dp default. A login card stretched to the reading width
 * of a list looks like a form that lost its fields; the web's own login card is `max-w-md`.
 */
private val CARD_MAX_WIDTH = 480.dp

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val SIGN_IN_ROOT_DESCRIPTION: String = "district-sign-in-root"
const val SIGN_IN_STATUS_DESCRIPTION: String = "district-sign-in-status"
const val SIGN_IN_ACTION_DESCRIPTION: String = "district-sign-in-action"
