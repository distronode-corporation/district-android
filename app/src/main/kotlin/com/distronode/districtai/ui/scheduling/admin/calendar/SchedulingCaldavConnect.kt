package com.distronode.districtai.ui.scheduling.admin.calendar

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminError
import com.distronode.districtai.core.data.SchedulingAdminFailureCode
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.connectCaldav
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The four ways to reach a CalDAV server, after district-ios's `SchedulingCaldavPresetC`.
 *
 * ⚠️ ONLY TWO ARE PRESETS AT THE FAR END. The fork's preset table holds `icloud` and `fastmail`;
 * Nextcloud is a server URL like any other, listed only because a host thinks of it as a product.
 */
enum class CaldavPreset(
    @get:StringRes val label: Int,
    @get:StringRes val note: Int,
    val needsServerUrl: Boolean,
) {
    ICLOUD(
        R.string.scheduling_admin_calendar_caldav_preset_icloud,
        R.string.scheduling_admin_calendar_caldav_preset_icloud_note,
        false,
    ),
    FASTMAIL(
        R.string.scheduling_admin_calendar_caldav_preset_fastmail,
        R.string.scheduling_admin_calendar_caldav_preset_fastmail_note,
        false,
    ),
    NEXTCLOUD(
        R.string.scheduling_admin_calendar_caldav_preset_nextcloud,
        R.string.scheduling_admin_calendar_caldav_preset_nextcloud_note,
        true,
    ),
    CUSTOM(
        R.string.scheduling_admin_calendar_caldav_preset_custom,
        R.string.scheduling_admin_calendar_caldav_preset_custom_note,
        true,
    ),
    ;

    /** The fork's preset key. */
    val wire: String get() = name.lowercase()
}

/** Which box a refusal belongs to. */
enum class CaldavField { SERVER_URL, USERNAME, APP_PASSWORD }

/**
 * The CalDAV form.
 *
 * ⛔ [appPassword] IS A CREDENTIAL. It is sent once, never logged and never echoed back: [toString]
 * leaves it out, so no diagnostic that prints this form can quote it, and the flow empties every box
 * once the account is connected. ⛔ It is NOT trimmed: it is bytes a provider generated, and a
 * leading or trailing space may be one of them. The username and the server URL are trimmed,
 * because a person typed them.
 */
data class CaldavForm(
    val preset: CaldavPreset = CaldavPreset.ICLOUD,
    val serverUrl: String = "",
    val username: String = "",
    val appPassword: String = "",
    val erroredField: CaldavField? = null,
    @get:StringRes val fieldMessage: Int? = null,
) {
    override fun toString(): String =
        "CaldavForm(preset=$preset, serverUrl=$serverUrl, username=$username, erroredField=$erroredField)"
}

/**
 * Link a CalDAV account by server, username and app password.
 *
 * ⛔ EXACTLY ONE OF `preset` AND `server_url` IS SENT. The fork prefers `server_url` when it is
 * non-empty, so sending both would silently ignore whichever the member chose.
 */
class CaldavConnectFlow(
    private val host: CalendarDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
) {
    /** ⚠️ A fresh form per opening, so the password box opens empty. */
    fun open() = host.show(CalendarDialog.Caldav(host.nextKey()))

    fun edit(change: (CaldavForm) -> CaldavForm) {
        val dialog = host.current as? CalendarDialog.Caldav ?: return
        if (dialog.state.isWorking) return
        val state = if (dialog.state is WriteState.Failed) WriteState.Idle else dialog.state
        val form = change(dialog.form).copy(erroredField = null, fieldMessage = null)
        host.show(dialog.copy(form = form, state = state))
    }

    fun submit() {
        val dialog = host.current as? CalendarDialog.Caldav ?: return
        if (dialog.state.isWorking) return
        val refusal = refusal(dialog.form)
        if (refusal != null) {
            val (field, message) = refusal
            host.show(dialog.copy(form = dialog.form.copy(erroredField = field, fieldMessage = message)))
            return
        }
        val form = dialog.form
        val custom = form.preset.needsServerUrl
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            val result = admin.connectCaldav(
                workspaceId,
                username = form.username.trim(),
                appPassword = form.appPassword,
                preset = if (custom) null else form.preset.wire,
                serverUrl = if (custom) form.serverUrl.trim() else null,
            )
            host.update<CalendarDialog.Caldav>(dialog.key) { open ->
                when (result) {
                    // ⛔ Every box emptied, the password included: a dialog left open holds no credential.
                    is SchedulingAdminResult.Ok -> open.copy(
                        form = CaldavForm(preset = form.preset),
                        state = WriteState.Done(connected(result.value.accountEmail)),
                    )
                    is SchedulingAdminResult.Failed ->
                        open.copy(state = WriteState.Failed(connectFailure(result.error)))
                }
            }
            if (result is SchedulingAdminResult.Ok) onChanged()
        }
    }

    companion object {
        /**
         * ⛔ NAMES THE ADDRESS THE SERVER RESOLVED, NOT THE USERNAME THAT WAS SENT: CalDAV usernames
         * are often not addresses, and the fork answers the one it found on the principal.
         */
        private fun connected(accountEmail: String): UiText =
            UiText.Resource(R.string.scheduling_admin_calendar_caldav_connected_to, accountEmail)

        /**
         * The first thing wrong with the form, in the order it is filled in: server URL, username,
         * then password, because only one message shows at a time.
         */
        fun refusal(form: CaldavForm): Pair<CaldavField, Int>? {
            val server = form.serverUrl.trim()
            val serverIssue = when {
                !form.preset.needsServerUrl -> null
                server.isEmpty() -> R.string.scheduling_admin_calendar_caldav_server_url_missing
                else -> serverUrlIssue(server)
            }
            return when {
                serverIssue != null -> CaldavField.SERVER_URL to serverIssue
                form.username.isBlank() ->
                    CaldavField.USERNAME to R.string.scheduling_admin_calendar_caldav_username_missing
                form.appPassword.isEmpty() ->
                    CaldavField.APP_PASSWORD to R.string.scheduling_admin_calendar_caldav_password_missing
                else -> null
            }
        }

        /**
         * What is wrong with a server URL, said to the member.
         *
         * ⚠️ NONE OF THIS IS AN SSRF BOUNDARY: the fork's CalDAV client dials, and a hostname that
         * resolves to 10.x defeats every syntactic check. What it buys is a sentence instead of a
         * timeout for the obvious probe, and that an app password is never aimed at `http://`.
         */
        @StringRes
        fun serverUrlIssue(value: String): Int? {
            val uri = try {
                URI(value.trim())
            } catch (_: URISyntaxException) {
                return R.string.scheduling_admin_calendar_caldav_server_url_not_a_url
            }
            val host = uri.host
            if (uri.scheme == null || host == null) {
                return R.string.scheduling_admin_calendar_caldav_server_url_not_a_url
            }
            return when {
                !uri.scheme.equals("https", ignoreCase = true) ->
                    R.string.scheduling_admin_calendar_caldav_server_url_not_https
                isIpLiteralHost(host) -> R.string.scheduling_admin_calendar_caldav_server_url_is_ip
                else -> null
            }
        }

        /**
         * Whether [host] names a machine rather than a name.
         *
         * ⛔ THE ALL-DIGITS CASE IS NOT PEDANTRY. `https://2130706433/` is 127.0.0.1 in decimal and
         * the fork's dialer accepts it; `0x7f000001` is the same trick in hex. ⚠️ An IPv6 literal
         * keeps its brackets in [URI.getHost], which makes that case one character. [host] is never
         * empty here: [URI.getHost] answers null rather than "", and [serverUrlIssue] refuses null.
         */
        internal fun isIpLiteralHost(host: String): Boolean {
            val lower = host.trim().lowercase()
            val hex = lower.removePrefix("0x")
            return when {
                lower.startsWith("[") -> true
                lower == "localhost" || lower.endsWith(".localhost") -> true
                lower.all { it.isDigit() || it == '.' } -> true
                else -> lower.startsWith("0x") && hex.isNotEmpty() && hex.all { it in HEX_DIGITS }
            }
        }

        /**
         * ⛔ THE FORK'S OWN 4xx TEXT IS NOT AVAILABLE AND MUST NOT BE INVENTED. A refused credential
         * arrives as the generic unknown, so that one code gets the sentence naming the three things
         * worth checking; ⚠️ a response this build could not read says nothing about the server, the
         * username or the password, so it keeps the shared sentence, as does every other code.
         */
        internal fun connectFailure(error: SchedulingAdminError): FailureText =
            if (error != SchedulingAdminError.Decoding && error.uiCode == SchedulingAdminFailureCode.UNKNOWN) {
                FailureText(
                    UiText.Resource(R.string.scheduling_admin_calendar_caldav_refused),
                    retryable = false,
                )
            } else {
                error.toFailureText()
            }

        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
