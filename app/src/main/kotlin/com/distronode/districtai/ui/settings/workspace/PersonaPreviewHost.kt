package com.distronode.districtai.ui.settings.workspace

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.PersonaPreviewForm

/**
 * Owns one persona audition's ViewModel and its dialog, and hands the screen a way to open it.
 *
 * ⛔ THE ViewModel IS KEYED ON THE WORKSPACE AND HOLDS NO FORM. The form is edited continuously, so
 * a ViewModel constructed with one would audition whatever was on screen when the persona editor
 * was first composed; [PersonaPreviewViewModel.start] takes the form at the tap instead.
 *
 * ⛔ DISMISSING THE DIALOG STOPS THE SESSION. A sheet that merely disappeared would leave a room
 * publishing this phone's microphone with nothing on screen able to reach it — the same obligation
 * `ActiveRoomViewModel.release` carries, discharged here because the dialog is the only way in.
 *
 * ⛔ NOTHING IN THIS FILE TOUCHES `TelecomBridge`. An audition is not a telephone call and must not
 * appear in the system call list, be answerable from it, or count against the one-call-at-a-time
 * `DistrictConnectionService` declares.
 */
@Composable
internal fun PersonaPreviewHost(
    repository: PersonaOptionsRepository,
    engineFactory: CallEngineFactory,
    workspaceId: String,
    formProvider: () -> PersonaPreviewForm?,
    content: @Composable (openPreview: () -> Unit) -> Unit,
) {
    var showing by remember { mutableStateOf(false) }
    val viewModel: PersonaPreviewViewModel = viewModel(
        key = "persona-preview-$workspaceId",
        factory = PersonaPreviewViewModel.factory(repository, workspaceId, engineFactory),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    val microphone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
        viewModel::onMicrophonePermissionResult,
    )

    // ⚠️ DRIVEN BY A COUNTER, LIKE THE DIALER'S. A permission launcher cannot be called from a
    // ViewModel, and a boolean flag would not re-fire for a second attempt after a denial.
    LaunchedEffect(state.microphoneRequest) {
        if (state.microphoneRequest > 0) microphone.launch(Manifest.permission.RECORD_AUDIO)
    }

    content { showing = true }

    if (showing) {
        PersonaPreviewDialog(
            state = state,
            onStart = { formProvider()?.let(viewModel::start) },
            onStop = viewModel::stop,
            onDismiss = {
                showing = false
                viewModel.stop()
            },
        )
    }
}
