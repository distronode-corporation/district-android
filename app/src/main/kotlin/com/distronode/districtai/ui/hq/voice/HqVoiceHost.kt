package com.distronode.districtai.ui.hq.voice

import android.Manifest
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.l10n.AppLanguage
import com.distronode.districtai.l10n.appLanguage

/**
 * The voice bar, wired to its session, the microphone permission and the screen's lifecycle.
 *
 * ⛔ THE SESSION ENDS WHEN THE SCREEN STOPS: the app going to the background (as on iOS), the
 * member leaving HQ, the screen being replaced. ⚠️ NOT ON A ROTATION OR ANOTHER CONFIGURATION
 * CHANGE, which recreates the activity while the ViewModel and its session carry on.
 *
 * ⛔ THE PERMISSION IS ASKED FOR ONLY AFTER THE CONSENT NOTICE'S START, when the session asks
 * ([HqVoiceUiState.microphoneRequest]); never at the Talk tap and never as the screen opens.
 *
 * @param history the console's turns, read at the Talk tap, so the agent knows the conversation.
 */
@Composable
fun HqVoiceHost(
    session: HqVoiceSession,
    workspaceRegion: String?,
    history: () -> List<HqTurn>,
    onOpenSettings: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    LifecycleStartEffect(session) {
        onStopOrDispose {
            if (endsOnStop(activity?.isChangingConfigurations)) session.end()
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.onMicrophonePermission(granted)
    }
    LaunchedEffect(state.microphoneRequest) {
        if (state.microphoneRequest > 0) {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
            session.onMicrophoneRequested()
        }
    }
    // ⚠️ THE LANGUAGE ON SCREEN, read from the resources as every caller here reads it.
    val locale = if (appLanguage() == AppLanguage.FR) "fr" else "en"
    HqVoiceBar(
        state = state,
        canConfirm = session.canConfirm,
        workspaceRegion = workspaceRegion,
        actions = session.actions(history, locale, onOpenSettings),
    )
}

/**
 * Whether a stop ends the session: always, except a configuration change (a rotation). ⚠️ No activity
 * (a preview, a test host) is not a configuration change.
 */
internal fun endsOnStop(changingConfigurations: Boolean?): Boolean = changingConfigurations != true
